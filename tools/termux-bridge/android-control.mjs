import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { resolve } from "node:path";

const MAX_OUTPUT = 30000;
const PACKAGE_RE = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
const DEFAULT_RISH = resolve(process.env.PREFIX || "/data/data/com.termux/files/usr", "bin/rish");

function run(program, args, timeoutMs = 30000) {
  return new Promise(resolveRun => {
    const child = spawn(program, args, {
      shell: false,
      env: { ...process.env, RISH_APPLICATION_ID: "com.termux" },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 1500).unref();
    }, timeoutMs);
    const append = (current, chunk, limit = MAX_OUTPUT) => (current + chunk.toString()).slice(-limit);
    child.stdout.on("data", chunk => { stdout = append(stdout, chunk); });
    child.stderr.on("data", chunk => { stderr = append(stderr, chunk, 8000); });
    child.on("error", error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolveRun({ exitCode: 127, output: stdout, errorText: error.message });
    });
    child.on("close", code => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolveRun({ exitCode: Number.isInteger(code) ? code : 1, output: stdout, errorText: stderr });
    });
  });
}

function validatePackage(value) {
  const name = String(value || "");
  if (!PACKAGE_RE.test(name) || name.length > 255) {
    throw new Error("A valid Android package name is required.");
  }
  return name;
}

export class AndroidControl {
  constructor({ rishBin = DEFAULT_RISH } = {}) {
    this.rishBin = rishBin;
  }

  async status() {
    const identity = await run(this.rishBin, ["-c", "id"], 15000);
    // Shizuku's app_process may route shell command output through stderr.
    // Validate the shell identity against both streams.
    const combined = [identity.output, identity.errorText].filter(Boolean).join("\n").trim();
    const ok = identity.exitCode === 0 && /uid=2000\(shell\)/.test(combined);
    return {
      exitCode: ok ? 0 : 3,
      output: combined,
      errorText: ok ? "" : "Shizuku did not return Android shell identity. " +
        "rish exit=" + identity.exitCode + "; stderr=" + (identity.errorText || "(empty)") +
        "; stdout=" + (identity.output || "(empty)") +
        ". Verify that the Shizuku service is running and Termux is authorized.",
    };
  }

  async command(command, timeoutMs = 30000) {
    const identity = await this.status();
    if (identity.exitCode !== 0) {
      throw new Error(identity.errorText);
    }
    const result = await run(this.rishBin, ["-c", command], timeoutMs);
    // Normalize Shizuku output because app_process may emit it on stderr.
    return {
      ...result,
      output: [result.output, result.errorText].filter(Boolean).join("\n").trim(),
      shizuku: "uid=2000(shell)",
    };
  }

  async packageInventory({ include_system = false, user_id = 0 } = {}) {
    const user = Number(user_id);
    if (![0, 999].includes(user)) throw new Error("Only Android users 0 and 999 are supported by this bridge.");
    const args = ["list", "packages", "--user", String(user)];
    if (include_system !== true) args.push("-3");
    args.push("-f", "-i");
    let result;
    if (user === 0) {
      // The Termux app belongs to Android user 0. Explicit user selection avoids
      // MIUI's ambiguous default profile and needs no Shizuku permission.
      result = await run("/system/bin/pm", args, 45000);
    } else {
      // MIUI XSpace (user 999) is a cross-user query and therefore requires Shizuku.
      const command = "pm list packages --user 999" +
        (include_system === true ? "" : " -3") + " -f -i";
      result = await this.command(command, 45000);
    }
    const combinedOutput = [result.output, result.errorText].filter(Boolean).join("\n").trim();
    return {
      ...result,
      output: combinedOutput || "No packages returned by Android Package Manager.",
      errorText: "",
      inventory_scope: include_system === true ? "all installed packages" : "third-party packages only",
      android_user_id: user,
      source: user === 0 ? "Android Package Manager via Termux app UID" : "Android Package Manager via Shizuku",
    };
  }

  async packageInfo({ package_name, user_id = 0 }) {
    const name = validatePackage(package_name);
    const user = Number(user_id);
    if (![0, 999].includes(user)) throw new Error("Only Android users 0 and 999 are supported by this bridge.");
    const path = await run("/system/bin/pm", ["path", name], 15000);
    const activity = user === 0
      ? await run("/system/bin/cmd", ["package", "resolve-activity", "--brief", "--user", "0", name], 15000)
      : await this.command("cmd package resolve-activity --brief --user 999 " + name, 15000);
    const installer = user === 0
      ? await run("/system/bin/pm", ["list", "packages", "--user", "0", "-i", name], 15000)
      : await this.command("pm list packages --user 999 -i " + name, 15000);
    return {
      exitCode: path.exitCode,
      output: [
        "PACKAGE: " + name,
        "PACKAGE_PATH",
        path.output.trim() || path.errorText,
        "LAUNCH_ACTIVITY",
        activity.output.trim() || activity.errorText,
        "INSTALL_SOURCE",
        installer.output.trim() || installer.errorText,
      ].join("\n"),
      errorText: path.errorText || activity.errorText || installer.errorText,
      source: "Android Package Manager via Termux app UID",
    };
  }

  async deviceInfo() {
    const keys = [
      "ro.product.manufacturer",
      "ro.product.model",
      "ro.product.device",
      "ro.build.version.release",
      "ro.build.version.sdk",
      "ro.build.version.security_patch",
    ];
    const rows = [];
    for (const key of keys) {
      const value = await run("/system/bin/getprop", [key], 5000);
      rows.push(key + "=" + value.output.trim());
    }
    return { exitCode: 0, output: rows.join("\n"), source: "Android system properties" };
  }

  async appOps({ package_name }) {
    const name = validatePackage(package_name);
    return await this.command("cmd appops get " + name, 30000);
  }

  async vpnStatus() {
    const identity = await this.status();
    if (identity.exitCode !== 0) {
      return {
        exitCode: 3,
        output: "VPN state requires Shizuku shell access. Basic package inventory remains available without it.",
        errorText: identity.errorText,
      };
    }
    const result = await this.command("dumpsys connectivity", 45000);
    const lines = result.output.split(/\r?\n/);
    const matches = lines.filter(line => /vpn|tun[0-9]*|TRANSPORT_VPN|underlying.?network/i.test(line));
    return {
      ...result,
      output: matches.slice(0, 180).join("\n") || "No VPN-related lines were found in dumpsys connectivity.",
      total_matching_lines: matches.length,
      source: "Android system diagnostics via Shizuku",
    };
  }

  async uiSnapshot() {
    // Never return or persist UI XML: it can contain private messages, account
    // names, notifications, and other on-screen content. Collect only counts.
    const path = "/sdcard/osiris-bridge-window-" + randomUUID() + ".xml";
    const command = "tmp='" + path + "'; " +
      "uiautomator dump \"$tmp\" >/dev/null 2>&1; rc=$?; " +
      "bytes=0; nodes=0; " +
      "if [ \"$rc\" -eq 0 ] && [ -s \"$tmp\" ]; then " +
      "bytes=$(wc -c < \"$tmp\" | tr -d '[:space:]'); " +
      "nodes=$(grep -o '<node ' \"$tmp\" 2>/dev/null | wc -l | tr -d '[:space:]'); " +
      "fi; " +
      "rm -f -- \"$tmp\" >/dev/null 2>&1; cleanup=$?; " +
      "printf 'snapshot_ok=%s\\nxml_bytes=%s\\nnode_count=%s\\ncleanup_ok=%s\\n' " +
      "\"$([ \"$rc\" -eq 0 ] && [ -s \"$tmp\" ] && echo 1 || [ \"$rc\" -eq 0 ] && echo 1 || echo 0)\" " +
      "\"$bytes\" \"$nodes\" \"$([ \"$cleanup\" -eq 0 ] && echo 1 || echo 0)\"; " +
      "if [ \"$rc\" -ne 0 ]; then exit \"$rc\"; fi; " +
      "exit \"$cleanup\"";
    const result = await this.command(command, 45000);
    const metrics = Object.fromEntries(
      String(result.output || "").split(/\\r?\\n/)
        .map(line => line.split("="))
        .filter(parts => parts.length === 2)
        .map(([key, value]) => [key, value])
    );
    const ok = result.exitCode === 0 && metrics.snapshot_ok === "1" &&
      metrics.cleanup_ok === "1" && /^\\d+$/.test(metrics.xml_bytes || "") &&
      /^\\d+$/.test(metrics.node_count || "");
    return {
      exitCode: ok ? 0 : (result.exitCode || 1),
      output: ok
        ? "snapshot_ok=1\\nxml_bytes=" + metrics.xml_bytes + "\\nnode_count=" + metrics.node_count + "\\ncleanup_ok=1\\nui_content=not_exported"
        : "snapshot_ok=0\\ncleanup_ok=" + (metrics.cleanup_ok || "unknown") + "\\nui_content=not_exported",
      errorText: ok ? "" : "UI snapshot metadata collection failed; raw UI content was not returned.",
      source: "uiautomator metadata only; XML content discarded locally",
    };
  }

  async uiAction(args = {}) {
    const action = String(args.action || "");
    if (action === "resolve_happ_link" || action === "open_happ_link") {
      const url = String(args.url || "");
      const match = /^happ:\/\/add\/(https:\/\/xevqbgjtxuveeqaiacvm\.supabase\.co\/functions\/v1\/happ-sub\/[a-f0-9]{64})$/.exec(url);
      if (!match) {
        throw new Error("Only the configured Happ subscription import link is allowed.");
      }
      const quoted = "'" + url.replace(/'/g, "'\\''") + "'";
      if (action === "resolve_happ_link") {
        return await this.command("cmd package query-activities --brief -a android.intent.action.VIEW -d " + quoted, 15000);
      }
      return await this.command("am start -a android.intent.action.VIEW -d " + quoted, 30000);
    }
    if (action === "launch") {
      const name = validatePackage(args.package_name);
      return await this.command("monkey -p " + name + " 1", 30000);
    }
    if (action === "launch_component") {
      const name = validatePackage(args.package_name);
      const activity = String(args.activity || "");
      if (!/^[A-Za-z0-9_.$]+$/.test(activity) || activity.length > 255) {
        throw new Error("A valid fully qualified or relative Android activity name is required.");
      }
      const component = activity.startsWith(".") ? name + activity : activity;
      if (!component.startsWith(name + ".")) {
        throw new Error("The activity must belong to the supplied package.");
      }
      return await this.command("am start -n " + name + "/" + component, 30000);
    }
    if (action === "tap") {
      const x = Number(args.x), y = Number(args.y);
      if (!Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0 || x > 10000 || y > 10000) {
        throw new Error("tap requires integer x/y coordinates from 0 to 10000.");
      }
      return await this.command("input tap " + x + " " + y, 15000);
    }
    if (action === "swipe") {
      const x1 = Number(args.x1), y1 = Number(args.y1), x2 = Number(args.x2), y2 = Number(args.y2);
      const duration = Number(args.duration_ms || 400);
      if (![x1,y1,x2,y2].every(Number.isInteger) || [x1,y1,x2,y2].some(v => v < 0 || v > 10000) ||
          !Number.isInteger(duration) || duration < 100 || duration > 3000) {
        throw new Error("swipe requires integer coordinates from 0 to 10000 and duration_ms from 100 to 3000.");
      }
      return await this.command("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration, 15000);
    }
    if (action === "text") {
      const value = String(args.text ?? "");
      if (!value || value.length > 120 || /[\r\n\t\x00-\x1f]/.test(value)) {
        throw new Error("text requires 1–120 printable characters and no control characters.");
      }
      const encoded = value.replace(/ /g, "%s");
      const quoted = "'" + encoded.replace(/'/g, "'\\''") + "'";
      return await this.command("input text " + quoted, 15000);
    }
    const keys = {
      back: "KEYCODE_BACK",
      home: "KEYCODE_HOME",
      enter: "KEYCODE_ENTER",
      tab: "KEYCODE_TAB",
      up: "KEYCODE_DPAD_UP",
      down: "KEYCODE_DPAD_DOWN",
      left: "KEYCODE_DPAD_LEFT",
      right: "KEYCODE_DPAD_RIGHT",
      center: "KEYCODE_DPAD_CENTER",
      menu: "KEYCODE_MENU",
      escape: "KEYCODE_ESCAPE",
    };
    if (Object.hasOwn(keys, action)) {
      return await this.command("input keyevent " + keys[action], 15000);
    }
    throw new Error("Unsupported UI action. Allowed: launch, tap, swipe, text, back, home, enter, tab, up, down, left, right, center, menu, escape.");
  }
}
