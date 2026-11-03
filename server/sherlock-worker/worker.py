#!/usr/bin/env python3
"""Private Sherlock discovery worker for My Contacts. Never edits source contact rows."""
from __future__ import annotations
import argparse, csv, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path
import sherlock_project
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlparse
import requests

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://xevqbgjtxuveeqaiacvm.supabase.co").rstrip("/")
SERVICE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
SITES = ["Telegram", "Instagram", "TikTok", "Reddit", "GitHub", "Twitter", "VK", "LinkedIn", "YouTube", "Pinterest", "Snapchat"]
MAX_PER_RUN = max(1, int(os.environ.get("SHERLOCK_MAX_CANDIDATES", "12")))
MAX_CONTACT_ROWS = max(1000, int(os.environ.get("SHERLOCK_MAX_CONTACT_ROWS", "20000")))
CACHE_DAYS = max(1, int(os.environ.get("SHERLOCK_CACHE_DAYS", "30")))
TIMEOUT = max(5, int(os.environ.get("SHERLOCK_SITE_TIMEOUT", "12")))
HANDLE_RE = re.compile(r"(?<![A-Za-z0-9_])@([A-Za-z0-9_.-]{3,32})(?![A-Za-z0-9_])")
EMAIL_RE = re.compile(r"(?i)(?<![\w.+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}(?![\w.-])")
URL_RE = re.compile(r"(?i)\b(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me|instagram\.com|www\.instagram\.com|x\.com|twitter\.com|tiktok\.com|reddit\.com|github\.com|vk\.com|linkedin\.com|youtube\.com|youtu\.be|pinterest\.com|snapchat\.com)/[^\s<>\"']+")
PHONE_RE = re.compile(r"(?<!\w)\+?\d[\d\s().-]{5,}\d(?!\w)")
URL_SITES = {
    "t.me":"Telegram", "telegram.me":"Telegram",
    "instagram.com":"Instagram", "x.com":"Twitter", "twitter.com":"Twitter",
    "tiktok.com":"TikTok", "reddit.com":"Reddit", "github.com":"GitHub",
    "vk.com":"VK", "linkedin.com":"LinkedIn", "youtube.com":"YouTube",
    "youtu.be":"YouTube", "pinterest.com":"Pinterest", "snapchat.com":"Snapchat",
}
RESERVED = {"share","intent","login","logout","settings","explore","search","about","privacy","terms","watch","channel","c","user","users","add","in","company","home","feed"}

def api(method, table, params=None, payload=None, prefer=None):
    if not SERVICE_KEY:
        raise RuntimeError("SUPABASE_SERVICE_ROLE_KEY is not configured")
    headers = {"apikey": SERVICE_KEY, "Authorization": "Bearer " + SERVICE_KEY, "Content-Type":"application/json"}
    if prefer: headers["Prefer"] = prefer
    response = requests.request(method, f"{SUPABASE_URL}/rest/v1/{table}", params=params, json=payload, headers=headers, timeout=30)
    response.raise_for_status()
    if response.text.strip():
        return response.json()
    return None

def expected_for_mime(mime):
    m = (mime or "").lower()
    if "email" in m: return "email"
    if "phone" in m: return "phone"
    if "website" in m: return "profile_url"
    if m.endswith("/im"): return "username"
    return None

def classify_value(value, path, mime=None):
    if not isinstance(value, str): return []
    raw = value.strip()
    if not raw: return []
    expected = expected_for_mime(mime)
    if path.startswith("phones["): expected = "phone"
    if path.startswith("emails["): expected = "email"
    if path.startswith("telegram_username"): expected = "username"
    if path == "telegram_id": expected = "telegram_id"
    found = []
    def add(kind, normalized, confidence="high", source_value=None):
        found.append({
            "source_path": path, "source_mimetype": mime, "source_value": source_value if source_value is not None else raw,
            "detected_type": kind, "expected_type": expected, "normalized_value": normalized,
            "confidence": confidence, "is_misfiled": bool(expected and expected != kind and not (expected == "phone" and kind == "possible_phone"))
        })
    for email in EMAIL_RE.findall(raw):
        add("email", email.lower(), source_value=email)
    for match in URL_RE.findall(raw):
        candidate_url = match.rstrip(".,);]")
        parsed = urlparse(candidate_url if "://" in candidate_url else "https://" + candidate_url)
        host = parsed.netloc.lower().removeprefix("www.")
        parts = [p for p in parsed.path.split("/") if p]
        site = URL_SITES.get(host)
        if site and parts:
            handle = parts[0].lstrip("@")
            if site == "Reddit" and len(parts) > 1 and parts[0].lower() in ("user","u"): handle = parts[1]
            if site == "LinkedIn" and parts[0].lower() == "in" and len(parts)>1: handle = parts[1]
            if site == "YouTube" and parts[0].startswith("@"): handle = parts[0][1:]
            if site == "Snapchat" and parts[0].lower()=="add" and len(parts)>1: handle = parts[1]
            if re.fullmatch(r"[A-Za-z0-9_.-]{3,32}", handle) and handle.lower() not in RESERVED:
                add("profile_url", candidate_url, source_value=candidate_url)
                found.append({"source_path":path,"source_mimetype":mime,"source_value":handle,
                    "detected_type":"username","expected_type":expected,"normalized_value":handle,
                    "confidence":"high","is_misfiled":bool(expected and expected not in ("profile_url","username")),
                    "platform_hint":site})
    # International and unprefixed numeric values are observations only. Unprefixed
    # numbers are not promoted to E.164 and are never sent to Telegram automatically.
    for number in ([] if path == "telegram_id" else PHONE_RE.findall(raw)):
        digits = re.sub(r"\D", "", number)
        if 7 <= len(digits) <= 15:
            kind = "phone" if number.strip().startswith("+") else "possible_phone"
            add(kind, ("+" if kind == "phone" else "") + digits, "high" if kind == "phone" else "low", source_value=number.strip())
    handle_text = EMAIL_RE.sub(" ", raw)
    for handle in HANDLE_RE.findall(handle_text):
        if handle.lower() not in RESERVED:
            found.append({"source_path":path,"source_mimetype":mime,"source_value":"@" + handle,
                "detected_type":"username","expected_type":expected,"normalized_value":handle,
                "confidence":"medium","is_misfiled":bool(expected and expected not in ("username","profile_url"))})
    # Bare values in the dedicated Telegram username field are valid handles.
    if path.startswith("telegram_username") and re.fullmatch(r"[A-Za-z0-9_.-]{3,32}", raw) and raw.lower() not in RESERVED:
        found.append({"source_path":path,"source_mimetype":mime,"source_value":raw,"detected_type":"username",
            "expected_type":expected,"normalized_value":raw,"confidence":"high","is_misfiled":False})
    # Known Telegram ID fields are classified as IDs; generic numeric strings remain ambiguous.
    if path == "telegram_id" and re.fullmatch(r"\d{5,20}", raw):
        add("telegram_id", raw)
    return found

def extract_contact(contact):
    observations, candidates = [], {}
    def add_value(value, path, mime=None):
        for item in classify_value(value, path, mime):
            observations.append(item)
            if item["detected_type"] != "username": continue
            username = str(item.get("normalized_value") or "").strip().lstrip("@")
            if not re.fullmatch(r"[A-Za-z0-9_.-]{3,32}", username): continue
            platform = item.get("platform_hint")
            key = username.casefold()
            entry = candidates.setdefault(key, {"username":username,"sites":set(),"source_path":path})
            if platform: entry["sites"].add(platform)
            else: entry["sites"].update(SITES)
    for key in ("phones","emails","telegram","telegram_id","telegram_username"):
        value = contact.get(key)
        if isinstance(value, list):
            for i, v in enumerate(value): add_value(v, f"{key}[{i}]")
        elif isinstance(value, str):
            # Telegram username fields can contain comma-separated historical names.
            if key == "telegram_username":
                for i, part in enumerate(re.split(r"[,;\n]+", value)):
                    add_value(part, f"{key}[{i}]")
            else: add_value(value, key)
    fields = contact.get("fields")
    if isinstance(fields, list):
        for i, field in enumerate(fields):
            if not isinstance(field, dict): continue
            mime = str(field.get("mimetype") or "")
            values = field.get("values")
            if isinstance(values, dict):
                for column, value in values.items():
                    add_value(value, f"fields[{i}].{column}", mime)
    return observations, candidates

def observation_rows(device_id, local_id, observations):
    rows=[]
    for o in observations:
        source_value=o["source_value"]
        rows.append({
            "device_id":device_id, "local_contact_id":str(local_id),
            "source_path":o["source_path"], "source_mimetype":o.get("source_mimetype"),
            "source_value":source_value,
            "source_value_hash":hashlib.sha256(source_value.encode("utf-8")).hexdigest(),
            "detected_type":o["detected_type"],
            "expected_type":o.get("expected_type"), "normalized_value":o.get("normalized_value"),
            "confidence":o.get("confidence","medium"), "is_misfiled":o.get("is_misfiled",False),
            "last_seen_at":datetime.now(timezone.utc).isoformat()
        })
    return rows

def selected_catalog_sites(sites):
    catalog=str(Path(sherlock_project.__file__).parent / "resources" / "data.json")
    with open(catalog,encoding="utf-8") as f:
        manifest=json.load(f)
    available={name.casefold():name for name in manifest.keys()}
    aliases={"twitter":"x"}
    selected=[]
    for site in sorted(set(sites) & set(SITES)):
        actual=available.get(site.casefold())
        if not actual and site.casefold() in aliases:
            actual=available.get(aliases[site.casefold()])
        if actual and actual not in selected: selected.append(actual)
    return catalog, selected

def run_sherlock(username, sites):
    with tempfile.TemporaryDirectory(prefix="contacts-sherlock-") as folder:
        catalog, selected = selected_catalog_sites(sites)
        if not selected:
            raise RuntimeError("No allowlisted sites are present in the pinned Sherlock catalog")
        cmd=["sherlock","--json",catalog,"--csv","--print-all","--no-color","--timeout",str(TIMEOUT),"--folderoutput",folder]
        for site in selected:
            cmd.extend(["--site",site])
        cmd.append(username)
        proc=subprocess.run(cmd,capture_output=True,text=True,timeout=max(90,TIMEOUT*len(selected)+30))
        csv_path=os.path.join(folder,username+".csv")
        if proc.returncode != 0 or not os.path.exists(csv_path):
            raise RuntimeError("Sherlock command failed or produced no CSV")
        with open(csv_path,newline="",encoding="utf-8") as f:
            return list(csv.DictReader(f))

def canonical_platform(platform):
    # Sherlock 0.16 renamed the Twitter catalog entry to X. Keep database
    # cache keys stable across the old and new labels.
    value = str(platform or "")
    return "Twitter" if value.casefold() == "x" else value


def latest_cache(username, selected_sites):
    rows=api("GET","contact_osint_profiles",params={
        "select":"platform,result_status,profile_url,http_status,response_time_s,checked_at,manifest_version,evidence",
        "normalized_username":"eq."+username,
        "order":"checked_at.desc","limit":"200"
    }) or []
    now=datetime.now(timezone.utc)
    selected_keys={canonical_platform(site) for site in selected_sites}
    cache={}
    for row in rows:
        platform=canonical_platform(row.get("platform"))
        if platform not in selected_keys or platform in cache: continue
        try: checked=datetime.fromisoformat(row["checked_at"].replace("Z","+00:00"))
        except Exception: continue
        status=str(row.get("result_status") or "")
        ttl_days=CACHE_DAYS if status in {"CLAIMED","AVAILABLE","ILLEGAL"} else min(CACHE_DAYS,7)
        if checked >= now-timedelta(days=ttl_days): cache[platform]=row
    return cache

def upsert_profiles(associations, username, results, version):
    now=datetime.now(timezone.utc).isoformat()
    rows=[]
    for assoc in associations:
        for result in results:
            status=str(result.get("exists") or "UNKNOWN").upper()
            if status not in {"CLAIMED","AVAILABLE","UNKNOWN","ILLEGAL","WAF"}: status="UNKNOWN"
            rows.append({
                "device_id":assoc["device_id"],"local_contact_id":str(assoc["local_contact_id"]),
                "source_path":assoc["source_path"],"normalized_username":username,
                "platform":result.get("name") or "unknown",
                "profile_url":result.get("url_user") if status=="CLAIMED" else None,
                "result_status":status,
                "http_status":int(result["http_status"]) if str(result.get("http_status","")).isdigit() else None,
                "response_time_s":float(result["response_time_s"]) if str(result.get("response_time_s","")).replace(".","",1).isdigit() else None,
                "checked_at":now,"manifest_version":version,
                "evidence":{"url_main":result.get("url_main"),"status":status}
            })
    for start in range(0,len(rows),500):
        api("POST","contact_osint_profiles",params={"on_conflict":"device_id,local_contact_id,normalized_username,platform"},
            payload=rows[start:start+500],prefer="resolution=merge-duplicates,return=minimal")

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--self-test",action="store_true")
    args=parser.parse_args()
    if args.self_test:
        sample={"telegram_username":"@sample_handle, another_name","fields":[
            {"mimetype":"vnd.android.cursor.item/email_v2","values":{"data1":"+33123456789"}},
            {"mimetype":"vnd.android.cursor.item/website","values":{"data1":"https://t.me/sample_handle"}},
            {"mimetype":"vnd.android.cursor.item/note","values":{"data1":"backup @second_handle mail a@b.example"}}
        ]}
        obs,candidates=extract_contact(sample)
        assert any(x["detected_type"]=="phone" and x["is_misfiled"] for x in obs)
        assert any(x["detected_type"]=="profile_url" for x in obs)
        assert "sample_handle" in candidates and "second_handle" in candidates and "another_name" in candidates
        assert not any(x["detected_type"]=="email" and x.get("normalized_value")=="b.example" for x in obs)
        assert expected_for_mime("vnd.android.cursor.item/website") is None
        assert expected_for_mime("vnd.android.cursor.item/im") == "username"
        assert canonical_platform("X") == canonical_platform("Twitter") == "Twitter"
        print("Sherlock worker self-test passed")
        return
    if not SERVICE_KEY: raise SystemExit("SUPABASE_SERVICE_ROLE_KEY is required")
    version=subprocess.run(["sherlock","--version"],capture_output=True,text=True,timeout=15).stdout.strip() or "unknown"
    associations=defaultdict(list)
    pending_observations=[]
    offset=0
    while offset < MAX_CONTACT_ROWS:
        rows=api("GET","contact_bridge_snapshots",params={
            "select":"device_id,local_contact_id,contact_data",
            "order":"device_id.asc,local_contact_id.asc","limit":"1000","offset":str(offset)
        }) or []
        if not rows: break
        for row in rows:
            data=row.get("contact_data") if isinstance(row.get("contact_data"),dict) else {}
            obs,candidates=extract_contact(data)
            if obs:
                pending_observations.extend(observation_rows(row["device_id"],row["local_contact_id"],obs))
                if len(pending_observations)>=1000:
                    api("POST","contact_identifier_observations",
                        params={"on_conflict":"device_id,local_contact_id,source_path,source_value_hash,detected_type"},
                        payload=pending_observations,prefer="resolution=merge-duplicates,return=minimal")
                    pending_observations.clear()
            for candidate in candidates.values():
                associations[(candidate["username"].casefold(),candidate["username"])].append({
                    "device_id":row["device_id"],"local_contact_id":row["local_contact_id"],
                    "source_path":candidate["source_path"],"sites":candidate["sites"]
                })
        offset += len(rows)
        if len(rows)<1000: break
    if pending_observations:
        api("POST","contact_identifier_observations",
            params={"on_conflict":"device_id,local_contact_id,source_path,source_value_hash,detected_type"},
            payload=pending_observations,prefer="resolution=merge-duplicates,return=minimal")
    processed=0
    for (key, username), contacts in associations.items():
        if processed >= MAX_PER_RUN: break
        site_union=set().union(*(x["sites"] for x in contacts)) or set(SITES)
        _, selected_sites=selected_catalog_sites(site_union)
        cached=latest_cache(username,selected_sites)
        required_cache_keys={canonical_platform(site) for site in selected_sites}
        if required_cache_keys and required_cache_keys.issubset(cached):
            continue
        try:
            results=run_sherlock(username,selected_sites)
            upsert_profiles(contacts,username,results,version)
            processed += 1
            print(f"Candidate {processed}: {len(results)} site results; linked_contact_rows={len(contacts)}")
        except Exception as exc:
            # Contact handles and profile URLs are personal data. Never put them or
            # exception strings (which may embed request URLs) into public CI logs.
            print(f"Candidate {processed + 1} failed: {type(exc).__name__}",file=sys.stderr)
    print(json.dumps({"snapshot_rows_scanned":offset,"unique_usernames":len(associations),"checked_usernames":processed,"manifest_version":version}))

if __name__=="__main__":
    try:
        main()
    except Exception as exc:
        # Avoid traceback/request URL leakage into public GitHub Actions logs.
        print(f"Worker failed: {type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
