import os
import json
import time
import uuid
import shlex
import urllib.request
import urllib.error
import subprocess
from pathlib import Path

BASE_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge"
EXPECTED_DEVICE_ID = "a61b3111-fe6d-469e-8074-f9a1f841e145"
HOME = Path.home()
TOKEN_FILE = HOME / ".config/osiris/device-token"
STATE_FILE = HOME / ".config/osiris/bridge-state.json"
LOG_FILE = HOME / "osiris-bridge/worker.log"
POLL_SECONDS = 5
SU = "/system/xbin/su"

def log(message):
    line = time.strftime("%Y-%m-%d %H:%M:%S") + " " + str(message)
    print(line, flush=True)
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")

def save_state(state):
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state), encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(STATE_FILE)

def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"claim_token": str(uuid.uuid4())}

def api(payload, timeout=30):
    token = TOKEN_FILE.read_text(encoding="ascii").strip()
    if len(token) != 64:
        raise RuntimeError("Invalid local token length")
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL, data=data,
        headers={"Authorization": "Bearer " + token,
                 "Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))

def run_fixed(args, timeout=12):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return {"exit_code": result.returncode,
                "output": (result.stdout + result.stderr).strip()[:5000]}
    except Exception as e:
        return {"exit_code": 1, "output": type(e).__name__ + ": " + str(e)[:300]}

def root_shell(command, timeout=15):
    return subprocess.run([SU, "-c", command], capture_output=True, timeout=timeout)

def collect_device_info():
    result = []
    commands = [
        ("ANDROID_IDENTITY", ["id"]), ("KERNEL", ["uname", "-a"]),
        ("ANDROID_VERSION", ["getprop", "ro.build.version.release"]),
        ("ANDROID_API", ["getprop", "ro.build.version.sdk"]),
        ("DEVICE_MODEL", ["getprop", "ro.product.model"]),
        ("MANUFACTURER", ["getprop", "ro.product.manufacturer"]),
        ("CPU_ABI", ["getprop", "ro.product.cpu.abi"]),
        ("STORAGE", ["df", "-h", "/data"]),
        ("SHIZUKU_PACKAGE", ["pm", "path", "moe.shizuku.privileged.api"]),
        ("SHIZUKU_PROCESS", ["pidof", "moe.shizuku.privileged.api"]),
        ("ROOT_CHECK", [SU, "-c", "id"]),
    ]
    for name, command in commands:
        item = run_fixed(command)
        result.append("=== " + name + " ===\n" +
                      (item["output"] or "(no output)") +
                      "\nexit_code=" + str(item["exit_code"]))
    return "\n\n".join(result)

def capture_screen():
    result = root_shell("screencap -p", timeout=25)
    if result.returncode != 0 or not result.stdout.startswith(b"\\x89PNG\\r\\n\\x1a\\n"):
        err = result.stderr.decode("utf-8", "replace")[:1000]
        raise RuntimeError("screencap failed: " + err)
    png = result.stdout
    filename = "screen-" + str(uuid.uuid4()) + ".png"
    grant = api({"action": "create_upload_url", "filename": filename,
                 "size": len(png), "content_type": "image/png"})
    signed_url = grant.get("signed_url")
    if not signed_url:
        raise RuntimeError("Upload grant missing signed_url")
    req = urllib.request.Request(
        signed_url, data=png,
        headers={"Content-Type": "image/png", "x-upsert": "false"},
        method="PUT")
    with urllib.request.urlopen(req, timeout=60) as response:
        if response.status not in (200, 201):
            raise RuntimeError("Screenshot upload HTTP " + str(response.status))
    path = grant.get("path")
    download = api({"action": "create_download_url", "path": path})
    url = download.get("signed_url")
    if not url:
        raise RuntimeError("Download grant missing signed_url")
    return {"path": path, "signed_url": url, "size": len(png)}

def screen_action(args):
    action = str(args.get("action", ""))
    if action in ("back", "home"):
        key = "KEYCODE_BACK" if action == "back" else "KEYCODE_HOME"
        command = "input keyevent " + key
    elif action == "tap":
        x, y = args.get("x"), args.get("y")
        if not all(isinstance(v, int) and 0 <= v <= 10000 for v in (x, y)):
            raise ValueError("tap requires integer x,y in range 0..10000")
        command = "input tap %d %d" % (x, y)
    elif action == "swipe":
        vals = [args.get(k) for k in ("x1", "y1", "x2", "y2")]
        if not all(isinstance(v, int) and 0 <= v <= 10000 for v in vals):
            raise ValueError("swipe coordinates must be integers in range 0..10000")
        duration = args.get("duration_ms", 400)
        if not isinstance(duration, int) or not 100 <= duration <= 5000:
            raise ValueError("duration_ms must be 100..5000")
        command = "input swipe %d %d %d %d %d" % (*vals, duration)
    elif action == "keyevent":
        keycode = str(args.get("keycode", ""))
        allowed = {"ENTER", "BACK", "HOME", "MENU", "TAB", "DPAD_UP",
                   "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DEL",
                   "FORWARD_DEL", "ESCAPE", "SPACE"}
        if keycode not in allowed:
            raise ValueError("Unsupported keycode")
        command = "input keyevent KEYCODE_" + keycode
    elif action == "text":
        value = args.get("text", "")
        if not isinstance(value, str) or len(value) > 500 or any(ord(c) < 32 for c in value):
            raise ValueError("text must be printable and at most 500 characters")
        command = "input text " + shlex.quote(value.replace(" ", "%s"))
    else:
        raise ValueError("Unsupported screen action")
    result = root_shell(command, timeout=15)
    output = result.stderr.decode("utf-8", "replace")[:1000]
    if result.returncode != 0:
        raise RuntimeError("root input failed: " + output)
    return {"action": action, "result": "sent", "stderr": output}

def execute_job(job):
    command_type = job.get("command_type")
    args = job.get("args") or {}
    if command_type == "device_info":
        return 0, collect_device_info(), ""
    if command_type == "shizuku_status":
        output = []
        for name, command in [
            ("PACKAGE", ["pm", "path", "moe.shizuku.privileged.api"]),
            ("PROCESS", ["pidof", "moe.shizuku.privileged.api"])]:
            item = run_fixed(command)
            output.append("=== " + name + " ===\n" +
                          (item["output"] or "(not found)"))
        return 0, "\n\n".join(output), ""
    if command_type == "screen_snapshot":
        return 0, json.dumps(capture_screen(), ensure_ascii=False), ""
    if command_type == "screen_action":
        return 0, json.dumps(screen_action(args), ensure_ascii=False), ""
    return 2, "", "Unsupported command type: " + str(command_type)

def main():
    if not TOKEN_FILE.is_file():
        raise RuntimeError("Local token file is missing")
    os.chmod(LOG_FILE, 0o600) if LOG_FILE.exists() else None
    state = load_state()
    log("Worker v2 starting")
    heartbeat = api({"action": "heartbeat"})
    if not heartbeat.get("ok") or heartbeat.get("device_id") != EXPECTED_DEVICE_ID:
        raise RuntimeError("Heartbeat rejected or device ID mismatch")
    log("Heartbeat OK; device=" + EXPECTED_DEVICE_ID)
    last_heartbeat = time.time()
    while True:
        try:
            if time.time() - last_heartbeat >= 30:
                heartbeat = api({"action": "heartbeat"})
                if heartbeat.get("device_id") != EXPECTED_DEVICE_ID:
                    raise RuntimeError("Heartbeat device mismatch")
                last_heartbeat = time.time()
            claim_token = state.get("claim_token") or str(uuid.uuid4())
            state["claim_token"] = claim_token
            save_state(state)
            response = api({"action": "claim", "claim_token": claim_token})
            job = response.get("job")
            if not job:
                state["claim_token"] = str(uuid.uuid4())
                state.pop("active_job_id", None)
                save_state(state)
                time.sleep(POLL_SECONDS)
                continue
            job_id = job.get("id")
            if not job_id:
                raise RuntimeError("Claimed job has no ID")
            state["active_job_id"] = job_id
            save_state(state)
            log("Claimed job " + job_id + " type=" + str(job.get("command_type")))
            try:
                exit_code, output, error_text = execute_job(job)
            except Exception as e:
                exit_code, output, error_text = 1, "", type(e).__name__ + ": " + str(e)[:1000]
            api({"action": "complete", "job_id": job_id,
                 "exit_code": exit_code, "output": output[:30000],
                 "error_text": error_text[:8000]})
            log("Completed job " + job_id + " exit_code=" + str(exit_code))
            state["claim_token"] = str(uuid.uuid4())
            state.pop("active_job_id", None)
            save_state(state)
        except KeyboardInterrupt:
            log("Worker stopped by user")
            break
        except urllib.error.HTTPError as e:
            log("HTTP error " + str(e.code))
            time.sleep(10)
        except Exception as e:
            log("Error: " + type(e).__name__ + ": " + str(e)[:200])
            time.sleep(10)

if __name__ == "__main__":
    main()
