import { spawn } from "node:child_process";
import { resolve } from "node:path";

const MAX_OUTPUT = 30000;
const PACKAGE_RE = /^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$/;
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
    const ok = identity.exitCode === 0 && /uid=2000\\(shell\\)/.test(identity.output);
    return {
      exitCode: ok ? 0 : 3,
      output: identity.output,
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
    return { ...result, shizuku: "uid=2000(shell)" };
  }

  async packageInventory({ include_system = false } = {}) {
    const flag = include_system === true ? "" : " -3";
    const result = await this.command("pm list packages" + flag + " -f -i", 45000);
    return {
      ...result,
      output: result.output || "No packages returned by Android Package Manager.",
      inventory_scope: include_system === true ? "all installed packages" : "third-party packages only",
    };
  }

  async packageInfo({ package_name }) {
    const name = validatePackage(package_name);
    const command = [
      "echo 'PACKAGE_PATH'",
      "pm path " + name,
      "echo 'LAUNCH_ACTIVITY'",
      "cmd package resolve-activity --brief " + name,
      "echo 'INSTALL_SOURCE'",
      "pm list packages -i " + name,
      "echo 'APP_OPS'",
      "cmd appops get " + name,
    ].join("; ");
    return await this.command(command, 45000);
  }

  async appOps({ package_name }) {
    const name = validatePackage(package_name);
    return await this.command("cmd appops get " + name, 30000);
  }

  async vpnStatus() {
    const result = await this.command("dumpsys connectivity", 45000);
    const lines = result.output.split(/\\r?\\n/);
    const matches = lines.filter(line => /vpn|tun[0-9]*|TRANSPORT_VPN|underlying.?network/i.test(line));
    return {
      ...result,
      output: matches.slice(0, 180).join("\\n") || "No VPN-related lines were found in dumpsys connectivity.",
      total_matching_lines: matches.length,
    };
  }
}
