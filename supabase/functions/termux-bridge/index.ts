import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}

async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return json({ error: "Server configuration missing" }, 500);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return json({ error: "JSON object required" }, 400);
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  const bearer = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  if (!/^[a-f0-9]{64}$/.test(bearer)) return json({ error: "Unauthorized" }, 401);

  const supabase = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const tokenHash = await sha256(bearer);
  const { data: device, error: deviceError } = await supabase
    .from("termux_bridge_devices")
    .select("id, label")
    .eq("token_hash", tokenHash)
    .eq("enabled", true)
    .is("revoked_at", null)
    .maybeSingle();

  if (deviceError) return json({ error: "Device lookup failed" }, 500);
  if (!device) return json({ error: "Unauthorized or revoked device" }, 401);

  const action = String(body.action ?? "");
  await supabase.from("termux_bridge_devices")
    .update({ last_seen_at: new Date().toISOString() })
    .eq("id", device.id);

  if (action === "worker_source") {
    if (device.id !== "a61b3111-fe6d-469e-8074-f9a1f841e145") {
      return json({ error: "Worker source unavailable for this device" }, 403);
    }
    return new Response("import os\nimport json\nimport time\nimport uuid\nimport shlex\nimport urllib.request\nimport urllib.error\nimport subprocess\nimport signal\nfrom pathlib import Path\n\nBASE_URL = \"https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge\"\nEXPECTED_DEVICE_ID = \"a61b3111-fe6d-469e-8074-f9a1f841e145\"\nHOME = Path.home()\nTOKEN_FILE = HOME / \".config/osiris/device-token\"\nSTATE_FILE = HOME / \".config/osiris/bridge-state.json\"\nLOG_FILE = HOME / \"osiris-bridge/worker.log\"\nPOLL_SECONDS = 5\nSU = \"/system/xbin/su\"\n\ndef log(message):\n    line = time.strftime(\"%Y-%m-%d %H:%M:%S\") + \" \" + str(message)\n    print(line, flush=True)\n    with LOG_FILE.open(\"a\", encoding=\"utf-8\") as f:\n        f.write(line + \"\\n\")\n\ndef save_state(state):\n    tmp = STATE_FILE.with_suffix(\".tmp\")\n    tmp.write_text(json.dumps(state), encoding=\"utf-8\")\n    os.chmod(tmp, 0o600)\n    tmp.replace(STATE_FILE)\n\ndef load_state():\n    if STATE_FILE.exists():\n        try:\n            return json.loads(STATE_FILE.read_text(encoding=\"utf-8\"))\n        except Exception:\n            pass\n    return {\"claim_token\": str(uuid.uuid4())}\n\ndef api(payload, timeout=30):\n    token = TOKEN_FILE.read_text(encoding=\"ascii\").strip()\n    if len(token) != 64:\n        raise RuntimeError(\"Invalid local token length\")\n    data = json.dumps(payload).encode(\"utf-8\")\n    req = urllib.request.Request(\n        BASE_URL, data=data,\n        headers={\"Authorization\": \"Bearer \" + token,\n                 \"Content-Type\": \"application/json\"},\n        method=\"POST\")\n    with urllib.request.urlopen(req, timeout=timeout) as response:\n        return json.loads(response.read().decode(\"utf-8\"))\n\ndef run_fixed(args, timeout=12):\n    try:\n        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)\n        return {\"exit_code\": result.returncode,\n                \"output\": (result.stdout + result.stderr).strip()[:5000]}\n    except Exception as e:\n        return {\"exit_code\": 1, \"output\": type(e).__name__ + \": \" + str(e)[:300]}\n\ndef root_shell(command, timeout=15):\n    proc = subprocess.Popen(\n        [SU, \"-c\", command],\n        stdout=subprocess.PIPE,\n        stderr=subprocess.PIPE,\n        start_new_session=True\n    )\n    try:\n        stdout, stderr = proc.communicate(timeout=timeout)\n    except subprocess.TimeoutExpired:\n        try:\n            os.killpg(proc.pid, signal.SIGKILL)\n        except ProcessLookupError:\n            pass\n        except Exception:\n            proc.kill()\n        try:\n            stdout, stderr = proc.communicate(timeout=5)\n        except Exception:\n            stdout, stderr = b\"\", b\"\"\n        raise TimeoutError(\"root command timed out after \" + str(timeout) + \" seconds\")\n    return subprocess.CompletedProcess(\n        [SU, \"-c\", command], proc.returncode, stdout, stderr\n    )\n\ndef collect_device_info():\n    result = []\n    commands = [\n        (\"ANDROID_IDENTITY\", [\"id\"]), (\"KERNEL\", [\"uname\", \"-a\"]),\n        (\"ANDROID_VERSION\", [\"getprop\", \"ro.build.version.release\"]),\n        (\"ANDROID_API\", [\"getprop\", \"ro.build.version.sdk\"]),\n        (\"DEVICE_MODEL\", [\"getprop\", \"ro.product.model\"]),\n        (\"MANUFACTURER\", [\"getprop\", \"ro.product.manufacturer\"]),\n        (\"CPU_ABI\", [\"getprop\", \"ro.product.cpu.abi\"]),\n        (\"STORAGE\", [\"df\", \"-h\", \"/data\"]),\n        (\"SHIZUKU_PACKAGE\", [\"pm\", \"path\", \"moe.shizuku.privileged.api\"]),\n        (\"SHIZUKU_PROCESS\", [\"pidof\", \"moe.shizuku.privileged.api\"]),\n        (\"ROOT_CHECK\", [SU, \"-c\", \"id\"]),\n    ]\n    for name, command in commands:\n        item = run_fixed(command)\n        result.append(\"=== \" + name + \" ===\\n\" +\n                      (item[\"output\"] or \"(no output)\") +\n                      \"\\nexit_code=\" + str(item[\"exit_code\"]))\n    return \"\\n\\n\".join(result)\n\ndef capture_screen():\n    local_path = \"/data/local/tmp/osiris-screen-\" + str(uuid.uuid4()) + \".png\"\n    result = root_shell(\"screencap -p \" + shlex.quote(local_path) + \" && chmod 644 \" + shlex.quote(local_path), timeout=25)\n    if result.returncode != 0:\n        err = result.stderr.decode(\"utf-8\", \"replace\")[:1000]\n        raise RuntimeError(\"screencap-to-file failed (exit=\" + str(result.returncode) + \"): \" + err)\n    try:\n        png = Path(local_path).read_bytes()\n    except Exception as e:\n        raise RuntimeError(\"cannot read screenshot file: \" + type(e).__name__ + \": \" + str(e)[:500])\n    finally:\n        root_shell(\"rm -f \" + shlex.quote(local_path), timeout=10)\n    if not png.startswith(b\"\\x89PNG\\r\\n\\x1a\\n\"):\n        raise RuntimeError(\"invalid PNG file: bytes=\" + str(len(png)) + \", prefix=\" + repr(png[:16]))\n    filename = \"screen-\" + str(uuid.uuid4()) + \".png\"\n    grant = api({\"action\": \"create_upload_url\", \"filename\": filename,\n                 \"size\": len(png), \"content_type\": \"image/png\"})\n    signed_url = grant.get(\"signed_url\")\n    if not signed_url:\n        raise RuntimeError(\"Upload grant missing signed_url\")\n    req = urllib.request.Request(\n        signed_url, data=png,\n        headers={\"Content-Type\": \"image/png\", \"x-upsert\": \"false\"},\n        method=\"PUT\")\n    with urllib.request.urlopen(req, timeout=60) as response:\n        if response.status not in (200, 201):\n            raise RuntimeError(\"Screenshot upload HTTP \" + str(response.status))\n    path = grant.get(\"path\")\n    download = api({\"action\": \"create_download_url\", \"path\": path})\n    url = download.get(\"signed_url\")\n    if not url:\n        raise RuntimeError(\"Download grant missing signed_url\")\n    return {\"path\": path, \"signed_url\": url, \"size\": len(png)}\n\ndef screen_action(args):\n    action = str(args.get(\"action\", \"\"))\n    if action in (\"back\", \"home\"):\n        key = \"KEYCODE_BACK\" if action == \"back\" else \"KEYCODE_HOME\"\n        command = \"input keyevent \" + key\n    elif action == \"tap\":\n        x, y = args.get(\"x\"), args.get(\"y\")\n        if not all(isinstance(v, int) and 0 <= v <= 10000 for v in (x, y)):\n            raise ValueError(\"tap requires integer x,y in range 0..10000\")\n        command = \"input tap %d %d\" % (x, y)\n    elif action == \"swipe\":\n        vals = [args.get(k) for k in (\"x1\", \"y1\", \"x2\", \"y2\")]\n        if not all(isinstance(v, int) and 0 <= v <= 10000 for v in vals):\n            raise ValueError(\"swipe coordinates must be integers in range 0..10000\")\n        duration = args.get(\"duration_ms\", 400)\n        if not isinstance(duration, int) or not 100 <= duration <= 5000:\n            raise ValueError(\"duration_ms must be 100..5000\")\n        command = \"input swipe %d %d %d %d %d\" % (*vals, duration)\n    elif action == \"keyevent\":\n        keycode = str(args.get(\"keycode\", \"\"))\n        allowed = {\"ENTER\", \"BACK\", \"HOME\", \"MENU\", \"TAB\", \"DPAD_UP\",\n                   \"DPAD_DOWN\", \"DPAD_LEFT\", \"DPAD_RIGHT\", \"DEL\",\n                   \"FORWARD_DEL\", \"ESCAPE\", \"SPACE\"}\n        if keycode not in allowed:\n            raise ValueError(\"Unsupported keycode\")\n        command = \"input keyevent KEYCODE_\" + keycode\n    elif action == \"text\":\n        value = args.get(\"text\", \"\")\n        if not isinstance(value, str) or len(value) > 500 or any(ord(c) < 32 for c in value):\n            raise ValueError(\"text must be printable and at most 500 characters\")\n        command = \"input text \" + shlex.quote(value.replace(\" \", \"%s\"))\n    else:\n        raise ValueError(\"Unsupported screen action\")\n    result = root_shell(command, timeout=15)\n    output = result.stderr.decode(\"utf-8\", \"replace\")[:1000]\n    if result.returncode != 0:\n        raise RuntimeError(\"root input failed: \" + output)\n    return {\"action\": action, \"result\": \"sent\", \"stderr\": output}\n\ndef execute_job(job):\n    command_type = job.get(\"command_type\")\n    args = job.get(\"args\") or {}\n    if command_type == \"device_info\":\n        return 0, collect_device_info(), \"\"\n    if command_type == \"shizuku_status\":\n        output = []\n        for name, command in [\n            (\"PACKAGE\", [\"pm\", \"path\", \"moe.shizuku.privileged.api\"]),\n            (\"PROCESS\", [\"pidof\", \"moe.shizuku.privileged.api\"])]:\n            item = run_fixed(command)\n            output.append(\"=== \" + name + \" ===\\n\" +\n                          (item[\"output\"] or \"(not found)\"))\n        return 0, \"\\n\\n\".join(output), \"\"\n    if command_type == \"screen_snapshot\":\n        return 0, json.dumps(capture_screen(), ensure_ascii=False), \"\"\n    if command_type == \"screen_action\":\n        return 0, json.dumps(screen_action(args), ensure_ascii=False), \"\"\n    return 2, \"\", \"Unsupported command type: \" + str(command_type)\n\ndef main():\n    if not TOKEN_FILE.is_file():\n        raise RuntimeError(\"Local token file is missing\")\n    os.chmod(LOG_FILE, 0o600) if LOG_FILE.exists() else None\n    state = load_state()\n    log(\"Worker v2 starting\")\n    heartbeat = api({\"action\": \"heartbeat\"})\n    if not heartbeat.get(\"ok\") or heartbeat.get(\"device_id\") != EXPECTED_DEVICE_ID:\n        raise RuntimeError(\"Heartbeat rejected or device ID mismatch\")\n    log(\"Heartbeat OK; device=\" + EXPECTED_DEVICE_ID)\n    last_heartbeat = time.time()\n    while True:\n        try:\n            if time.time() - last_heartbeat >= 30:\n                heartbeat = api({\"action\": \"heartbeat\"})\n                if heartbeat.get(\"device_id\") != EXPECTED_DEVICE_ID:\n                    raise RuntimeError(\"Heartbeat device mismatch\")\n                last_heartbeat = time.time()\n            claim_token = state.get(\"claim_token\") or str(uuid.uuid4())\n            state[\"claim_token\"] = claim_token\n            save_state(state)\n            response = api({\"action\": \"claim\", \"claim_token\": claim_token})\n            job = response.get(\"job\")\n            if not job:\n                state[\"claim_token\"] = str(uuid.uuid4())\n                state.pop(\"active_job_id\", None)\n                save_state(state)\n                time.sleep(POLL_SECONDS)\n                continue\n            job_id = job.get(\"id\")\n            if not job_id:\n                raise RuntimeError(\"Claimed job has no ID\")\n            state[\"active_job_id\"] = job_id\n            save_state(state)\n            log(\"Claimed job \" + job_id + \" type=\" + str(job.get(\"command_type\")))\n            try:\n                exit_code, output, error_text = execute_job(job)\n            except Exception as e:\n                exit_code, output, error_text = 1, \"\", type(e).__name__ + \": \" + str(e)[:1000]\n            api({\"action\": \"complete\", \"job_id\": job_id,\n                 \"exit_code\": exit_code, \"output\": output[:30000],\n                 \"error_text\": error_text[:8000]})\n            log(\"Completed job \" + job_id + \" exit_code=\" + str(exit_code))\n            state[\"claim_token\"] = str(uuid.uuid4())\n            state.pop(\"active_job_id\", None)\n            save_state(state)\n        except KeyboardInterrupt:\n            log(\"Worker stopped by user\")\n            break\n        except urllib.error.HTTPError as e:\n            log(\"HTTP error \" + str(e.code))\n            time.sleep(10)\n        except Exception as e:\n            log(\"Error: \" + type(e).__name__ + \": \" + str(e)[:200])\n            time.sleep(10)\n\nif __name__ == \"__main__\":\n    main()", {
      status: 200,
      headers: { ...cors, "Content-Type": "text/x-python; charset=utf-8" },
    });
  }

  if (action === "heartbeat") return json({ ok: true, device_id: device.id });

  if (action === "enqueue_brave") {
    const braveAction = String(body.brave_action ?? "");
    if (!["snapshot", "back", "open_url"].includes(braveAction)) {
      return json({ error: "Unsupported Brave command" }, 400);
    }

    const args: Record<string, unknown> = { action: braveAction };
    if (braveAction === "open_url") {
      const targetUrl = String(body.url ?? "");
      if (targetUrl !== "https://www.reg.ru/") {
        return json({ error: "Only the approved public REG.RU URL is allowed" }, 400);
      }
      args.url = targetUrl;
    } else if (body.url !== undefined) {
      return json({ error: "URL is not allowed for this Brave command" }, 400);
    }

    const { data: job, error } = await supabase
      .from("termux_bridge_jobs")
      .insert({
        device_id: device.id,
        command_type: "brave_accessibility",
        args,
      })
      .select("id")
      .single();

    if (error || !job) return json({ error: "Could not queue Brave command" }, 500);
    return json({ job_id: job.id });
  }


  if (action === "create_upload_url") {
    const filename = String(body.filename ?? "");
    const size = Number(body.size);
    const contentType = String(body.content_type ?? "application/octet-stream");
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(filename) || filename === "." || filename === "..") {
      return json({ error: "Invalid upload filename" }, 400);
    }
    if (!Number.isSafeInteger(size) || size < 1 || size > 50 * 1024 * 1024) {
      return json({ error: "Upload size must be between 1 byte and 50 MiB" }, 400);
    }
    const contentTypeParts = contentType.split("/");
    if (contentTypeParts.length !== 2 || contentTypeParts.some(part => !/^[A-Za-z0-9.+_-]+$/.test(part)) || contentType.length > 128) {
      return json({ error: "Invalid content type" }, 400);
    }
    const objectPath = `${device.id}/${crypto.randomUUID()}-${filename}`;
    const { data, error } = await supabase.storage
      .from("termux-bridge-artifacts")
      .createSignedUploadUrl(objectPath, { upsert: false });
    if (error || !data?.signedUrl || !data?.token) {
      return json({ error: "Could not create a private upload grant" }, 500);
    }
    return json({
      path: objectPath,
      token: data.token,
      signed_url: data.signedUrl,
      bucket: "termux-bridge-artifacts",
      expires_in: 7200,
    });
  }

  if (action === "create_download_url") {
    const objectPath = String(body.path ?? "");
    const prefix = `${device.id}/`;
    if (!objectPath.startsWith(prefix) || objectPath.includes("..") || objectPath.includes("\\\\")) {
      return json({ error: "Invalid artifact path for this device" }, 400);
    }
    const { data, error } = await supabase.storage
      .from("termux-bridge-artifacts")
      .createSignedUrl(objectPath, 3600);
    if (error || !data?.signedUrl) {
      return json({ error: "Could not create a private download grant" }, 500);
    }
    return json({ path: objectPath, signed_url: data.signedUrl, expires_in: 3600 });
  }

  if (action === "brave_status") {
    const id = String(body.job_id ?? "");
    if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "Invalid job_id" }, 400);

    const { data: job, error } = await supabase
      .from("termux_bridge_jobs")
      .select("id, status, output, error_text, exit_code, created_at, finished_at")
      .eq("id", id)
      .eq("device_id", device.id)
      .eq("command_type", "brave_accessibility")
      .maybeSingle();

    if (error) return json({ error: "Could not read Brave command status" }, 500);
    if (!job) return json({ error: "Brave command not found for this device" }, 404);
    return json({ job });
  }

  if (action === "claim") {
    const claimToken = String(body.claim_token ?? "");
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claimToken)) {
      return json({ error: "Invalid claim_token" }, 400);
    }
    const { data, error } = await supabase.rpc("claim_termux_bridge_job", {
      p_device_id: device.id,
      p_claim_token: claimToken,
    });
    if (error) return json({ error: "Could not claim command" }, 500);
    const job = Array.isArray(data) ? (data[0] ?? null) : data;
    return json({ job });
  }

  if (action === "complete") {
    const id = String(body.job_id ?? "");
    if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "Invalid job_id" }, 400);
    const exitCode = Number.isInteger(body.exit_code) ? Number(body.exit_code) : 1;
    if (exitCode < -1 || exitCode > 255) return json({ error: "Invalid exit_code" }, 400);

    const patch = {
      status: exitCode === 0 ? "succeeded" : "failed",
      output: String(body.output ?? "").slice(0, 30000),
      error_text: String(body.error_text ?? "").slice(0, 8000) || null,
      exit_code: exitCode,
      finished_at: new Date().toISOString(),
    };
    const { data, error } = await supabase
      .from("termux_bridge_jobs")
      .update(patch)
      .eq("id", id)
      .eq("device_id", device.id)
      .eq("status", "running")
      .select("id")
      .maybeSingle();

    if (error) return json({ error: "Could not save command result" }, 500);
    if (data) return json({ ok: true });

    // A previous completion may have committed even when its HTTP response was lost.
    // Accept an exact replay, but never overwrite a terminal or conflicting result.
    const { data: existing, error: readError } = await supabase
      .from("termux_bridge_jobs")
      .select("status, output, error_text, exit_code")
      .eq("id", id)
      .eq("device_id", device.id)
      .maybeSingle();
    if (readError) return json({ error: "Could not verify command completion" }, 500);
    const sameResult = existing &&
      existing.status === patch.status &&
      (existing.output ?? "") === patch.output &&
      (existing.error_text ?? null) === patch.error_text &&
      existing.exit_code === patch.exit_code;
    if (sameResult) return json({ ok: true, already_completed: true });
    return json({ error: "Command not found, not running, or has a conflicting result" }, 409);
  }

  return json({ error: "Unsupported action" }, 400);
});
