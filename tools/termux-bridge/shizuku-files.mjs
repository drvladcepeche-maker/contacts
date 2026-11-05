import { access } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";

const ROOTS = ["/sdcard/Download/TermuxBridge", "/sdcard/Documents/TermuxBridge"];
const MAX_OUTPUT = 30000;

function quote(value) {
  return "'" + String(value).replace(/'/g, "'\\''") + "'";
}

function approvedPath(value) {
  const path = resolve("/", String(value || "").replace(/^\/+/, ""));
  const ok = ROOTS.some(root => path === root || path.startsWith(root + "/"));
  if (!ok) throw new Error("Shizuku file operations are restricted to Download/TermuxBridge and Documents/TermuxBridge.");
  if (path === "/sdcard" || path === "/sdcard/Download" || path === "/sdcard/Documents") {
    throw new Error("Operation on a shared-storage parent directory is blocked.");
  }
  return path;
}

function run(program, args, timeoutMs = 30000) {
  return new Promise(resolveRun => {
    const child = spawn(program, args, { shell: false, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "", settled = false;
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.stdout.on("data", chunk => { stdout = (stdout + chunk.toString()).slice(-MAX_OUTPUT); });
    child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString()).slice(-8000); });
    child.on("error", error => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      resolveRun({ exitCode: 127, output: stdout, errorText: error.message });
    });
    child.on("close", code => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      resolveRun({ exitCode: Number.isInteger(code) ? code : 1, output: stdout, errorText: stderr });
    });
  });
}

export class ShizukuFiles {
  constructor({ workspace, rishBin }) {
    this.workspace = resolve(workspace);
    this.rishBin = rishBin || "rish";
  }

  async command(command, timeout = 30000) {
    try { await access(this.rishBin); }
    catch {
      if (this.rishBin !== "rish") throw new Error("Configured rish executable was not found.");
    }
    const identity = await run(this.rishBin, ["-c", "id"], 15000);
    if (identity.exitCode !== 0 || !/uid=2000\(shell\)/.test(identity.output)) {
      throw new Error("Shizuku is not authorized. Expected rish to run as Android shell uid=2000.");
    }
    return await run(this.rishBin, ["-c", command], timeout);
  }

  async list({ path }) {
    const target = approvedPath(path);
    const result = await this.command("ls -la -- " + quote(target));
    return { ...result, path: target };
  }

  async mkdir({ path }) {
    const target = approvedPath(path);
    const result = await this.command("mkdir -p -- " + quote(target));
    return { ...result, path: target };
  }

  async copy({ source, destination }) {
    const src = approvedPath(source);
    const dest = approvedPath(destination);
    if (src === dest) throw new Error("Source and destination must differ.");
    const result = await this.command("cp -f -- " + quote(src) + " " + quote(dest), 120000);
    return { ...result, source: src, destination: dest };
  }

  async move({ source, destination }) {
    const src = approvedPath(source);
    const dest = approvedPath(destination);
    if (src === dest) throw new Error("Source and destination must differ.");
    const result = await this.command("mv -f -- " + quote(src) + " " + quote(dest), 120000);
    return { ...result, source: src, destination: dest };
  }

  async remove({ path, confirm }) {
    if (confirm !== true) throw new Error("shizuku_file_delete requires args.confirm=true.");
    const target = approvedPath(path);
    if (ROOTS.includes(target)) throw new Error("Deleting an approved root directory is blocked.");
    const result = await this.command("rm -f -- " + quote(target));
    return { ...result, path: target };
  }
}
