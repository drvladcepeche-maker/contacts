import os
import json
import time
import uuid
import shlex
import urllib.request
import urllib.error
import subprocess
import shutil
import signal
import select
import fcntl
import random
import tempfile
from urllib.parse import urlsplit
from pathlib import Path
import xml.etree.ElementTree as ET

BASE_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge"
EXPECTED_DEVICE_ID = "a61b3111-fe6d-469e-8074-f9a1f841e145"
HOME = Path.home()
TOKEN_FILE = HOME / ".config/osiris/device-token"
STATE_FILE = HOME / ".config/osiris/bridge-state.json"
LOG_FILE = HOME / "osiris-bridge/worker.log"
POLL_SECONDS = 5
SU = "/system/xbin/su"
TERMINALS = {}

def log(message):
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    if LOG_FILE.exists() and LOG_FILE.stat().st_size > 2 * 1024 * 1024:
        rotated = LOG_FILE.with_name("worker.log.1")
        try:
            if rotated.exists():
                rotated.unlink()
            os.replace(str(LOG_FILE), str(rotated))
        except OSError:
            pass
    line = time.strftime("%Y-%m-%d %H:%M:%S") + " " + str(message)
    print(line, flush=True)
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")

def save_state(state):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_name(STATE_FILE.name + "." + str(os.getpid()) + "." + uuid.uuid4().hex + ".tmp")
    data = json.dumps(state, ensure_ascii=False)
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(str(tmp), str(STATE_FILE))
        os.chmod(STATE_FILE, 0o600)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except OSError:
            pass

def acquire_worker_lock():
    lock_path = STATE_FILE.with_name("worker_v3.lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(lock_path), os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        raise RuntimeError("Another worker_v3 instance is already running")
    os.ftruncate(fd, 0)
    os.write(fd, str(os.getpid()).encode("ascii"))
    return fd

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
    headers = {"Authorization": "Bearer " + token,
               "Content-Type": "application/json"}
    req = urllib.request.Request(BASE_URL, data=data, headers=headers, method="POST")
    result = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # Do not retry permanent client/auth errors. Retry only transient server/rate limits.
        if e.code not in (408, 425, 429, 500, 502, 503, 504):
            raise
        log("API HTTP " + str(e.code) + "; trying curl IPv4/HTTP1.1 fallback")
        result = _curl_api_fallback(token, data, timeout)
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
        log("urllib API transport failed (" + type(e).__name__ + "); trying curl IPv4/HTTP1.1 fallback")
        result = _curl_api_fallback(token, data, timeout)
    try:
        health_file = HOME / ".termux/worker-v3.heartbeat"
        health_file.parent.mkdir(parents=True, exist_ok=True)
        health_file.touch()
    except Exception:
        pass
    return result


def _curl_api_fallback(token, data, timeout):
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("urllib failed and curl is unavailable")
    # Keep bearer token and request body out of process arguments and logs.
    with tempfile.TemporaryDirectory(prefix="osiris-api-", dir=str(HOME)) as temp_dir:
        os.chmod(temp_dir, 0o700)
        body_path = Path(temp_dir) / "request.json"
        config_path = Path(temp_dir) / "curl.conf"
        body_path.write_bytes(data)
        os.chmod(body_path, 0o600)
        def cfg(value):
            return '"' + str(value).replace('\\', '\\\\').replace('"', '\"').replace('\n', '') + '"'
        config = (
            "url = " + cfg(BASE_URL) + "\n"
            "request = \"POST\"\n"
            "header = " + cfg("Authorization: Bearer " + token) + "\n"
            "header = \"Content-Type: application/json\"\n"
            "data-binary = " + cfg("@" + str(body_path)) + "\n"
            "write-out = \"%{http_code}\"\n"
        )
        config_path.write_text(config, encoding="utf-8")
        os.chmod(config_path, 0o600)
        proc = subprocess.run(
            [curl, "--config", str(config_path), "--ipv4", "--http1.1",
             "--silent", "--show-error", "--connect-timeout", "8",
             "--max-time", str(timeout)],
            capture_output=True, timeout=timeout + 5
        )
        raw = proc.stdout
        try:
            body, status = raw[:-3], raw[-3:]
            code = int(status)
        except Exception:
            body, code = raw, 0
        if proc.returncode != 0 or code < 200 or code >= 300:
            detail = proc.stderr.decode("utf-8", "replace")[-500:]
            if not detail:
                detail = body.decode("utf-8", "replace")[-500:]
            raise RuntimeError("curl fallback failed rc=" + str(proc.returncode) +
                               " HTTP=" + str(code) + " " + detail)
        return json.loads(body.decode("utf-8"))

def run_fixed(args, timeout=12):
    try:
        env = dict(os.environ)
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/sbin:" + env.get("PATH", "")
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=env)
        return {"exit_code": result.returncode,
                "output": (result.stdout + result.stderr).strip()[:5000]}
    except Exception as e:
        return {"exit_code": 1, "output": type(e).__name__ + ": " + str(e)[:300]}

def root_shell(command, timeout=15):
    command = "export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH; " + command
    proc = subprocess.Popen(
        [SU, "-c", command],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True
    )
    try:
        stdout, stderr = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except Exception:
            proc.kill()
        try:
            stdout, stderr = proc.communicate(timeout=5)
        except Exception:
            stdout, stderr = b"", b""
        raise TimeoutError("root command timed out after " + str(timeout) + " seconds")
    return subprocess.CompletedProcess(
        [SU, "-c", command], proc.returncode, stdout, stderr
    )

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
    """Capture the display; fall back to Android UIAutomator when screencap is unavailable."""
    local_path = "/data/local/tmp/osiris-screen-" + str(uuid.uuid4()) + ".png"
    try:
        result = root_shell("export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH; screencap -p " + shlex.quote(local_path) + " && chmod 644 " + shlex.quote(local_path), timeout=25)
        if result.returncode != 0:
            raise RuntimeError("screencap exit=" + str(result.returncode) + ": " + result.stderr.decode("utf-8", "replace")[:500])
        png = Path(local_path).read_bytes()
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("screencap returned invalid/empty PNG (" + str(len(png)) + " bytes)")
        filename = "screen-" + str(uuid.uuid4()) + ".png"
        grant = api({"action": "create_upload_url", "filename": filename,
                     "size": len(png), "content_type": "image/png"})
        signed_url = grant.get("signed_url")
        if not signed_url:
            raise RuntimeError("Upload grant missing signed_url")
        req = urllib.request.Request(signed_url, data=png,
            headers={"Content-Type": "image/png", "x-upsert": "false"}, method="PUT")
        with urllib.request.urlopen(req, timeout=60) as response:
            if response.status not in (200, 201):
                raise RuntimeError("Screenshot upload HTTP " + str(response.status))
        path = grant.get("path")
        download = api({"action": "create_download_url", "path": path})
        url = download.get("signed_url")
        if not url:
            raise RuntimeError("Download grant missing signed_url")
        return {"mode": "screenshot", "path": path, "signed_url": url, "size": len(png)}
    except Exception as screenshot_error:
        log("Screenshot capture unavailable; using UIAutomator fallback: " + str(screenshot_error)[:300])
        xml_path = "/data/local/tmp/osiris-ui-" + str(uuid.uuid4()) + ".xml"
        command = ("export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH; "
                   "uiautomator dump " + shlex.quote(xml_path) + " >/dev/null 2>&1 && "
                   "chmod 644 " + shlex.quote(xml_path) + " && cat " + shlex.quote(xml_path) + "; "
                   "rc=$?; rm -f " + shlex.quote(xml_path) + "; exit $rc")
        tree_result = root_shell(command, timeout=35)
        if tree_result.returncode != 0:
            raise RuntimeError("Screenshot failed (" + str(screenshot_error)[:250] + "); UIAutomator dump failed: " + tree_result.stderr.decode("utf-8", "replace")[:500])
        raw = tree_result.stdout.decode("utf-8", "replace")
        xml_start = raw.find("<?xml")
        if xml_start < 0:
            raise RuntimeError("UIAutomator returned no XML hierarchy: " + raw[:500])
        try:
            root = ET.fromstring(raw[xml_start:])
        except ET.ParseError as e:
            raise RuntimeError("UIAutomator XML parse failed: " + str(e)[:300])
        elements = []
        for node in root.iter("node"):
            a = node.attrib
            text_value = a.get("text", "")
            desc = a.get("content-desc", "")
            rid = a.get("resource-id", "")
            clickable = a.get("clickable") == "true"
            focusable = a.get("focusable") == "true"
            scrollable = a.get("scrollable") == "true"
            if not (text_value or desc or rid or clickable or focusable or scrollable):
                continue
            elements.append({"text": text_value[:300], "description": desc[:300],
                "resource_id": rid[:200], "class": a.get("class", ""),
                "package": a.get("package", ""), "clickable": clickable,
                "enabled": a.get("enabled") == "true", "focusable": focusable,
                "scrollable": scrollable, "bounds": a.get("bounds", "")})
            if len(elements) >= 250:
                break
        if not elements:
            raise RuntimeError("UIAutomator hierarchy contained no actionable or labeled elements")
        return {"mode": "uiautomator", "rotation": root.attrib.get("rotation"),
                "element_count": len(elements), "elements": elements,
                "fallback_reason": str(screenshot_error)[:250]}
    finally:
        try:
            root_shell("rm -f " + shlex.quote(local_path), timeout=8)
        except Exception:
            pass

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


def browser_accessibility(args):
    action = str(args.get("action", ""))
    if action == "snapshot":
        screen = capture_screen()
        activity = root_shell("/system/bin/dumpsys activity activities", timeout=10)
        activity_text = (activity.stdout + activity.stderr).decode("utf-8", "replace")
        return {"screen": screen, "activity": activity_text[-3000:]}
    if action == "back":
        return screen_action({"action": "back"})
    if action in ("tap", "text"):
        return screen_action(args)
    if action == "open_url":
        target = args.get("url")
        if not isinstance(target, str) or len(target) > 2048:
            raise ValueError("open_url requires a URL up to 2048 characters")
        parsed = urlsplit(target)
        allowed_hosts = {
            "accounts.nvidia.com", "www.nvidia.com", "play.geforcenow.com",
            "www.reg.ru", "reg.ru", "play.google.com"
        }
        if parsed.scheme != "https" or parsed.hostname not in allowed_hosts or parsed.username or parsed.password:
            raise ValueError("URL must use HTTPS and an approved browser host")
        result = run_fixed(["/system/bin/am", "start", "-a", "android.intent.action.VIEW", "-d", target], timeout=20)
        if result["exit_code"] != 0:
            raise RuntimeError("Could not open URL: " + result["output"][:500])
        return {"action": action, "url": target, "result": result["output"][:1000]}
    if action == "wait":
        seconds = args.get("seconds", 2)
        if not isinstance(seconds, (int, float)) or seconds < 0 or seconds > 15:
            raise ValueError("wait seconds must be between 0 and 15")
        time.sleep(seconds)
        return {"action": action, "waited": seconds}
    raise ValueError("Unsupported browser accessibility action")

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
    if command_type == "brave_accessibility":
        return 0, json.dumps(browser_accessibility(args), ensure_ascii=False), ""
    if command_type == "terminal_run":
        command = args.get("command")
        if not isinstance(command, str) or not command.strip() or len(command) > 12000:
            raise ValueError("command must be a non-empty string up to 12000 characters")
        timeout = args.get("timeout", 60)
        if not isinstance(timeout, int) or timeout < 1 or timeout > 300:
            raise ValueError("timeout must be an integer from 1 to 300")
        cwd = args.get("cwd") or str(HOME)
        if not isinstance(cwd, str) or len(cwd) > 1000 or chr(0) in cwd:
            raise ValueError("invalid cwd")
        if args.get("root", False):
            wrapped = "cd " + shlex.quote(cwd) + " 2>/dev/null || exit 72; " + command
            result = root_shell(wrapped, timeout=timeout)
        else:
            result = subprocess.run(command, shell=True, cwd=cwd, capture_output=True,
                                    text=False, timeout=timeout, start_new_session=True)
        out = result.stdout.decode("utf-8", "replace")
        err = result.stderr.decode("utf-8", "replace")
        payload = {"exit_code": result.returncode, "stdout": out[-24000:],
                   "stderr": err[-6000:], "cwd": cwd, "root": bool(args.get("root", False))}
        return result.returncode, json.dumps(payload, ensure_ascii=False), ""
    if command_type == "terminal_start":
        command = args.get("command")
        if not isinstance(command, str) or not command.strip() or len(command) > 12000:
            raise ValueError("command must be a non-empty string up to 12000 characters")
        cwd = args.get("cwd") or str(HOME)
        root = bool(args.get("root", False))
        if root:
            wrapped = "cd " + shlex.quote(cwd) + " 2>/dev/null || exit 72; " + command
            proc = subprocess.Popen([SU, "-c", wrapped], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        else:
            proc = subprocess.Popen(command, shell=True, cwd=cwd, executable="/system/bin/sh", stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        sid = str(uuid.uuid4())
        TERMINALS[sid] = proc
        return 0, json.dumps({"session_id": sid, "pid": proc.pid, "root": root}), ""
    if command_type == "terminal_read":
        sid = str(args.get("session_id", ""))
        proc = TERMINALS.get(sid)
        if not proc: raise ValueError("unknown terminal session")
        limit = args.get("max_bytes", 12000)
        if not isinstance(limit, int) or limit < 1 or limit > 24000: raise ValueError("max_bytes must be 1..24000")
        data = b""
        if proc.stdout:
            ready, _, _ = select.select([proc.stdout], [], [], 0.25)
            if ready: data = os.read(proc.stdout.fileno(), limit)
        code = proc.poll()
        if code is not None and not data: TERMINALS.pop(sid, None)
        return 0, json.dumps({"session_id": sid, "output": data.decode("utf-8", "replace"), "running": code is None, "exit_code": code}, ensure_ascii=False), ""
    if command_type == "terminal_input":
        sid = str(args.get("session_id", ""))
        proc = TERMINALS.get(sid)
        value = args.get("text", "")
        if not proc or proc.poll() is not None or not proc.stdin: raise ValueError("terminal session is not running")
        if not isinstance(value, str) or len(value) > 8000: raise ValueError("text must be a string up to 8000 characters")
        proc.stdin.write(value.encode("utf-8")); proc.stdin.flush()
        return 0, json.dumps({"session_id": sid, "written": len(value)}), ""
    if command_type == "terminal_stop":
        sid = str(args.get("session_id", ""))
        proc = TERMINALS.pop(sid, None)
        if not proc: return 0, json.dumps({"session_id": sid, "stopped": False}), ""
        try:
            os.killpg(proc.pid, signal.SIGTERM); proc.wait(timeout=3)
        except Exception:
            try: os.killpg(proc.pid, signal.SIGKILL)
            except Exception: pass
        return 0, json.dumps({"session_id": sid, "stopped": True}), ""
    return 2, "", "Unsupported command type: " + str(command_type)

def main():
    if not TOKEN_FILE.is_file():
        raise RuntimeError("Local token file is missing")
    os.chmod(LOG_FILE, 0o600) if LOG_FILE.exists() else None
    lock_fd = acquire_worker_lock()
    state = load_state()
    log("Worker v3 starting; single-instance lock acquired")
    initial_failures = 0
    while True:
        try:
            heartbeat = api({"action": "heartbeat"})
            if not heartbeat.get("ok") or heartbeat.get("device_id") != EXPECTED_DEVICE_ID:
                raise RuntimeError("Heartbeat rejected or device ID mismatch")
            log("Heartbeat OK; device=" + EXPECTED_DEVICE_ID)
            break
        except Exception as e:
            initial_failures += 1
            delay = min(60, 5 * (2 ** min(initial_failures - 1, 4))) + random.uniform(0, 2)
            log("Initial heartbeat failed; retry " + str(initial_failures) + " in " +
                str(round(delay, 1)) + "s: " + type(e).__name__ + ": " + str(e)[:200])
            time.sleep(delay)
    last_heartbeat = time.time()
    consecutive_errors = 0
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
                consecutive_errors = 0
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
            consecutive_errors = 0
        except KeyboardInterrupt:
            log("Worker stopped by user")
            break
        except urllib.error.HTTPError as e:
            consecutive_errors += 1
            delay = min(60, 5 * (2 ** min(consecutive_errors - 1, 4))) + random.uniform(0, 2)
            log("HTTP error " + str(e.code) + "; retry " + str(consecutive_errors) +
                " in " + str(round(delay, 1)) + "s")
            time.sleep(delay)
        except Exception as e:
            consecutive_errors += 1
            delay = min(60, 5 * (2 ** min(consecutive_errors - 1, 4))) + random.uniform(0, 2)
            log("Error: " + type(e).__name__ + ": " + str(e)[:200] +
                "; retry " + str(consecutive_errors) + " in " + str(round(delay, 1)) + "s")
            time.sleep(delay)

if __name__ == "__main__":
    main()
