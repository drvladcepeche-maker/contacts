import { createHash, randomUUID } from "node:crypto";
import { inspectApk } from "./apk-inspect.mjs";
import { access, mkdir, readFile, readdir, realpath, rename, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, relative, resolve, sep } from "node:path";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline/promises";

const API_URL = process.env.TERMUX_BRIDGE_URL ||
  "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge";
const STATE_DIR = resolve(homedir(), ".config/termux-bridge");
const TOKEN_PATH = resolve(STATE_DIR, "device-token");
const STATE_PATH = resolve(STATE_DIR, "bridge-state.json");
const WORKSPACE = resolve(process.env.WORKSPACE || process.cwd());
const APK_DIR = resolve(homedir(), "storage", "downloads", "PnS_APK");
const POLL_MS = Math.max(3000, Number(process.env.POLL_MS) || 5000);
const MAX_OUTPUT = 30000;
let stopping = false;

const token = (await readFile(TOKEN_PATH, "utf8")).trim();
if (!/^[a-f0-9]{64}$/.test(token)) {
  throw new Error("Invalid local device token. Re-run setup only after revoking the old device.");
}
const tokenHash = createHash("sha256").update(token).digest("hex");
if (!tokenHash) throw new Error("Could not validate local token.");

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function transientTransportError(error) {
  if (Number.isInteger(error?.status)) return error.status === 408 || error.status === 425 || error.status === 429 || error.status >= 500;
  return /Bridge (?:fetch|curl) transport failed|AbortError|timed out|ECONNRESET|ECONNREFUSED|EHOSTUNREACH|ENETUNREACH|TLS connect error|unexpected eof while reading|curl fallback failed \(exit (?:5|6|7|18|28|35|52|55|56|92)\)/i.test(String(error?.message || error));
}
function makeHttpError(status, data) {
  const error = new Error(data?.error || ("Bridge HTTP " + status));
  error.status = status;
  return error;
}
function curlConfigQuote(value) {
  return '"' + String(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\r/g, "\\r").replace(/\n/g, "\\n") + '"';
}
async function requestViaCurlOnce(body) {
  const payload = JSON.stringify(body), marker = "__TERMUX_BRIDGE_HTTP_STATUS__";
  const config = [
    "url = " + curlConfigQuote(API_URL), 'request = "POST"',
    "header = " + curlConfigQuote("content-type: application/json"),
    "header = " + curlConfigQuote("authorization: Bearer " + token),
    "data = " + curlConfigQuote(payload), 'connect-timeout = "10"', 'max-time = "20"',
  ].join("\n") + "\n";
  const result = await new Promise((resolveRun) => {
    const child = spawn("curl", ["--silent", "--show-error", "--ipv4", "--http1.1", "--connect-timeout", "10", "--max-time", "20", "--write-out", "\n" + marker + "%{http_code}", "--config", "-"], {
      cwd: WORKSPACE, shell: false, env: { ...process.env, TERMUX_BRIDGE_TOKEN: undefined }, stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "", stderr = "", settled = false;
    const timer = setTimeout(() => child.kill("SIGKILL"), 22000);
    child.stdout.on("data", chunk => { stdout = (stdout + chunk.toString()).slice(-MAX_OUTPUT - 128); });
    child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString()).slice(-4000); });
    child.on("error", error => { if (settled) return; settled = true; clearTimeout(timer); resolveRun({ code: 127, stdout, stderr: error.message }); });
    child.on("close", code => { if (settled) return; settled = true; clearTimeout(timer); resolveRun({ code: Number.isInteger(code) ? code : 1, stdout, stderr }); });
    child.stdin.end(config);
  });
  const markerAt = result.stdout.lastIndexOf(marker);
  if (result.code !== 0 || markerAt < 0) {
    const error = new Error("Bridge curl transport failed (exit " + result.code + "): " + (result.stderr || "no HTTP status received"));
    error.transport = true; throw error;
  }
  const responseText = result.stdout.slice(0, markerAt).replace(/\n$/, "");
  const status = Number(result.stdout.slice(markerAt + marker.length).trim());
  let data;
  try { data = responseText ? JSON.parse(responseText) : {}; }
  catch { throw new Error("Bridge returned invalid JSON (HTTP " + status + ")"); }
  if (status < 200 || status >= 300) throw makeHttpError(status, data);
  return data;
}
async function requestOnce(body) {
  try {
    const response = await fetch(API_URL, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer " + token },
      body: JSON.stringify(body), signal: AbortSignal.timeout(20000),
    });
    const responseText = await response.text();
    let data;
    try { data = responseText ? JSON.parse(responseText) : {}; }
    catch { throw new Error("Bridge returned invalid JSON (HTTP " + response.status + ")"); }
    if (!response.ok) throw makeHttpError(response.status, data);
    return data;
  } catch (error) {
    if (Number.isInteger(error?.status)) throw error;
    const reason = String(error?.message || error).replace(/[\r\n]+/g, " ").slice(0, 240);
    console.error("Node fetch transport failed; trying curl for this request: " + reason);
    return await requestViaCurlOnce(body);
  }
}
async function request(body) {
  const maxAttempts = 5;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const data = await requestOnce(body);
      if (attempt > 1) console.error("Bridge request recovered on attempt " + attempt + "/" + maxAttempts + ".");
      return data;
    } catch (error) {
      if (!transientTransportError(error) || attempt === maxAttempts) throw error;
      const delayMs = Math.min(8000, 750 * (2 ** (attempt - 1)));
      const detail = String(error?.message || error).replace(/[\r\n]+/g, " ").slice(0, 300);
      console.error("Transient bridge failure on attempt " + attempt + "/" + maxAttempts + ": " + detail + "; retrying in " + delayMs + " ms.");
      await sleep(delayMs);
    }
  }
  throw new Error("Bridge request exhausted its retry budget.");
}
async function loadBridgeState() {
  await mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
  try {
    const parsed = JSON.parse(await readFile(STATE_PATH, "utf8"));
    if (!parsed || !/^[0-9a-f-]{36}$/i.test(parsed.claimToken || "")) throw new Error("Bridge state file has an invalid claim token; refusing to overwrite it.");
    if (parsed.pending && (!parsed.pending.job || !/^[0-9a-f-]{36}$/i.test(parsed.pending.job.id || ""))) throw new Error("Bridge state file has an invalid pending job; refusing to overwrite it.");
    return parsed;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    const initial = { claimToken: randomUUID(), pending: null };
    await saveBridgeState(initial);
    return initial;
  }
}
async function saveBridgeState(state) {
  const temporaryPath = STATE_PATH + "." + process.pid + ".tmp";
  await writeFile(temporaryPath, JSON.stringify(state), { encoding: "utf8", mode: 0o600 });
  await rename(temporaryPath, STATE_PATH);
}

function run(program, args, timeoutMs = 30000) {
  return new Promise((resolveRun) => {
    const child = spawn(program, args, {
      cwd: WORKSPACE,
      shell: false,
      env: { ...process.env, TERMUX_BRIDGE_TOKEN: undefined },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 2000).unref();
    }, timeoutMs);
    const append = (current, chunk) => (current + chunk.toString()).slice(-MAX_OUTPUT);
    child.stdout.on("data", chunk => { stdout = append(stdout, chunk); });
    child.stderr.on("data", chunk => { stderr = append(stderr, chunk); });
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

function withinWorkspace(candidate) {
  const target = resolve(WORKSPACE, candidate || ".");
  if (target !== WORKSPACE && !target.startsWith(WORKSPACE + sep)) {
    throw new Error("Path is outside the configured workspace.");
  }
  return target;
}

async function readWorkspaceFile(args) {
  const rel = String(args.path || "");
  if (!rel || rel.startsWith("/") || rel.includes("\\\\") || rel.split("/").some(part => !part || part === "." || part === ".." || part.startsWith("."))) {
    throw new Error("file_read requires a plain relative path without hidden or traversal segments.");
  }
  const leaf = basename(rel).toLowerCase();
  if (/(secret|token|credential|password|\.env|private|keystore|\.jks|\.p12|\.pem)/i.test(leaf)) {
    throw new Error("Reading sensitive filenames is blocked.");
  }
  if (!/\.(mjs|cjs|js|ts|tsx|json|md|ya?ml|sql|gradle|xml|properties|kt|java|txt|toml|sh)$/i.test(leaf)) {
    throw new Error("file_read only supports approved text/code extensions.");
  }
  const target = withinWorkspace(rel);
  const info = await stat(target);
  if (!info.isFile()) throw new Error("Requested path is not a file.");
  if (info.size > 65536) throw new Error("File exceeds the 64 KiB read limit.");
  const output = await readFile(target, "utf8");
  return { exitCode: 0, output, errorText: "" };
}

async function shizukuStorageCheck() {
  let rishBin = process.env.RISH_BIN || resolve(WORKSPACE, "rish");
  try { await access(rishBin); } catch { rishBin = "rish"; }
  const identity = await run(rishBin, ["-c", "id"], 15000);
  if (identity.exitCode !== 0 || !identity.output.includes("uid=2000(shell)")) {
    return {
      exitCode: 3,
      output: identity.output,
      errorText: "Shizuku is unavailable or not authorized for Termux; expected Android shell uid=2000.",
    };
  }

  const target = "/sdcard/Download/PnS_APK";
  const listing = await run(rishBin, ["-c", "ls " + target], 15000);
  if (listing.exitCode !== 0) {
    const parent = await run(rishBin, ["-c", "ls /sdcard/Download"], 15000);
    return {
      exitCode: 1,
      output: [
        "Shizuku identity: " + identity.output.trim(),
        "Download directory contents:",
        parent.output.trim(),
      ].join("\n"),
      errorText: "Shizuku could not list " + target + ". " +
        (listing.errorText || listing.output || "Directory missing or inaccessible."),
    };
  }

  return {
    exitCode: 0,
    output: [
      "Shizuku identity: " + identity.output.trim(),
      "Read-only listing: " + target,
      listing.output.trim(),
    ].join("\n"),
    errorText: "",
  };
}

async function apkInventory() {
  let names;
  try { names = await readdir(APK_DIR); }
  catch (error) { return { exitCode: 1, output: "", errorText: "APK directory unavailable: " + String(error?.message || error) }; }
  const rows = [];
  for (const name of names.filter(n => /\.apk$/i.test(n)).sort()) {
    try {
      const info = await stat(resolve(APK_DIR, name));
      rows.push(name + "\\t" + info.size + " bytes");
    } catch { rows.push(name + "\\tstat failed"); }
  }
  return { exitCode: 0, output: rows.join("\n") || "No APK files found.", errorText: "" };
}


async function installApk(args) {
  const filename = String(args.file || "");
  if (!filename || filename.length > 180 ||
      !/^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$/i.test(filename)) {
    return { exitCode: 2, output: "", errorText: "install_apk requires args.file with a plain APK filename from the APK inventory." };
  }

  const target = resolve(APK_DIR, filename);
  let actual;
  let info;
  try {
    actual = await realpath(target);
    info = await stat(target);
  } catch {
    return { exitCode: 2, output: "", errorText: "APK not found in the approved Download/PnS_APK folder." };
  }
  if (actual !== resolve(await realpath(APK_DIR), filename) || !info.isFile()) {
    return { exitCode: 2, output: "", errorText: "APK must be a regular file directly inside the approved APK folder; symlinks are rejected." };
  }
  if (info.size < 1 || info.size > 250 * 1024 * 1024) {
    return { exitCode: 2, output: "", errorText: "APK size is outside the allowed 1 byte–250 MiB range." };
  }

  const hash = createHash("sha256").update(await readFile(actual)).digest("hex");
  const metadata = await run("aapt", ["dump", "badging", actual], 30000);
  if (metadata.exitCode !== 0) {
    return {
      exitCode: 2,
      output: metadata.output,
      errorText: "Could not inspect APK package. Install the Termux package 'aapt' (pkg install aapt), then retry. " + (metadata.errorText || ""),
    };
  }
  const packageLine = metadata.output.split("\n").find(line => line.startsWith("package:"));
  const packageMatch = packageLine?.match(/\bname='([^']+)'/);
  if (packageMatch?.[1] !== "com.mycontacts") {
    return {
      exitCode: 2,
      output: packageLine || metadata.output.slice(0, 1000),
      errorText: "Rejected: only package com.mycontacts can be installed by this command.",
    };
  }

  let rishBin = process.env.RISH_BIN || resolve(WORKSPACE, "rish");
  try { await access(rishBin); }
  catch {
    rishBin = "rish";
  }

  const identity = await run(rishBin, ["-c", "id"], 15000);
  if (identity.exitCode !== 0 || !/uid=2000\(shell\)/.test(identity.output)) {
    return {
      exitCode: 3,
      output: identity.output,
      errorText: "Shizuku bridge unavailable or not authorized. Expected rish to run as Android shell (uid=2000). Check Shizuku authorization and RISH_BIN.",
    };
  }

  const installed = await run(rishBin, ["-c", "pm path com.mycontacts"], 15000);
  if (installed.exitCode !== 0 || !installed.output.includes("package:")) {
    return {
      exitCode: 3,
      output: installed.output,
      errorText: "Installed package com.mycontacts was not found for Android user 0. No installation was attempted.",
    };
  }

  // PackageManager enforces signing-certificate compatibility. -r replaces in place
  // and preserves app data; no uninstall, downgrade, or signature-bypass flags are used.
  const install = await run(
    rishBin,
    ["-c", "pm install -r --user 0 '" + actual + "'"],
    120000
  );
  const report = [
    "APK: " + filename,
    "Package: com.mycontacts",
    "Size: " + info.size + " bytes",
    "SHA-256: " + hash,
    "Shizuku identity: " + identity.output.trim(),
    "PackageManager result:",
    install.output.trim(),
  ].filter(Boolean).join("\n");

  if (install.exitCode !== 0 || !/Success/.test(install.output)) {
    return {
      exitCode: install.exitCode || 1,
      output: report,
      errorText: (install.errorText || "Android PackageManager rejected the update. The existing app was not uninstalled.") +
        " If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE, the APK signing key differs from the installed app. Do not uninstall the existing app.",
    };
  }
  return { exitCode: 0, output: report + "\nApp updated in place; existing app data was not intentionally cleared.", errorText: "" };
}

async function listFiles(args) {
  const target = withinWorkspace(String(args.path || "."));
  const entries = await readdir(target, { withFileTypes: true });
  const lines = entries
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, 200)
    .map(entry => `${entry.isDirectory() ? "DIR " : entry.isFile() ? "FILE" : "OTHER"} ${relative(WORKSPACE, resolve(target, entry.name))}`);
  return { exitCode: 0, output: lines.join("\n"), errorText: "" };
}

async function approveBuild(label) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    return false;
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = await rl.question(
      `\nCONFIRMATION REQUIRED: ${label}\nWorkspace: ${WORKSPACE}\nThis runs repository build scripts. Execute? Type YES: `
    );
    return answer.trim() === "YES";
  } finally {
    rl.close();
  }
}

async function execute(job) {
  const args = job.args && typeof job.args === "object" ? job.args : {};
  switch (job.command_type) {
    case "pwd":
      return { exitCode: 0, output: WORKSPACE, errorText: "" };
    case "list_files":
      return await listFiles(args);
    case "file_read":
      return await readWorkspaceFile(args);
    case "apk_inventory":
      return await apkInventory();
    case "shizuku_storage_check":
      return await shizukuStorageCheck();
    case "install_apk":
      return await installApk(args);
    case "git_fetch_main":
      return await run("git", ["fetch", "origin", "main"], 120000);
    case "node_check_bridge":
      return await run("node", ["--check", resolve(homedir(), "termux-bridge-live", "tools", "termux-bridge", "bridge.mjs")]);
    case "node_check_apk_inspect":
      return await run("node", ["--check", resolve(homedir(), "termux-bridge-live", "tools", "termux-bridge", "apk-inspect.mjs")]);
    case "apk_inspect":
      return await inspectApk();
    case "git_status":
      return await run("git", ["status", "--short", "--branch"]);
    case "git_log": {
      const count = Math.min(20, Math.max(1, Number(args.limit) || 10));
      return await run("git", ["log", `--max-count=${count}`, "--oneline", "--decorate"]);
    }
    case "git_diff_stat":
      return await run("git", ["diff", "--stat"]);
    case "gradle_test":
    case "gradle_assemble_debug": {
      const label = job.command_type === "gradle_test" ? "./gradlew test" : "./gradlew assembleDebug";
      if (!(await approveBuild(label))) {
        return { exitCode: 125, output: "", errorText: "Blocked: explicit local approval was not given." };
      }
      const wrapper = resolve(WORKSPACE, "gradlew");
      const { access } = await import("node:fs/promises");
      let program = "gradle";
      let commandArgs = job.command_type === "gradle_test" ? ["test"] : ["assembleDebug"];
      try {
        await access(wrapper);
        program = wrapper;
      } catch { /* use installed gradle */ }
      return await run(program, commandArgs, 15 * 60 * 1000);
    }
    default:
      return { exitCode: 126, output: "", errorText: "Rejected: command type is not on the local allowlist." };
  }
}

console.log("Termux bridge started.");
console.log("Workspace:", WORKSPACE);
console.log("Endpoint:", API_URL);
console.log("Automatic commands: pwd, list_files, file_read (bounded text/code only), apk_inventory, shizuku_storage_check (read-only Shizuku listing), apk_inspect, install_apk (com.mycontacts only; Shizuku PackageManager update), git_status, git_log, git_diff_stat, git_fetch_main, node_check_bridge, node_check_apk_inspect.");
console.log("Gradle commands require typing YES in this terminal.");
console.log("No arbitrary shell command is accepted.");

process.on("SIGINT", () => { stopping = true; });
process.on("SIGTERM", () => { stopping = true; });

let bridgeState = await loadBridgeState();
while (!stopping) {
  try {
    if (!bridgeState.pending) {
      const response = await request({ action: "claim", claim_token: bridgeState.claimToken });
      if (!response.job) {
        bridgeState.claimToken = randomUUID();
        await saveBridgeState(bridgeState);
        await sleep(POLL_MS);
        continue;
      }
      bridgeState.pending = { job: response.job, phase: "claimed" };
      await saveBridgeState(bridgeState);
    }
    const pending = bridgeState.pending;
    if (pending.phase === "claimed" || pending.phase === "executing") {
      pending.phase = "result";
      pending.result = {
        exitCode: 125, output: "",
        errorText: "Bridge restarted while this job was in progress. Its execution outcome is unknown; the command was not automatically re-run.",
      };
      await saveBridgeState(bridgeState);
    }
    if (pending.phase === "result" && pending.result) {
      await request({
        action: "complete", job_id: pending.job.id,
        output: String(pending.result.output || "").slice(0, MAX_OUTPUT),
        error_text: String(pending.result.errorText || "").slice(0, 8000),
        exit_code: Number.isInteger(pending.result.exitCode) ? pending.result.exitCode : 1,
      });
      console.log("Finished " + pending.job.id + " with exit code " + pending.result.exitCode);
      bridgeState.pending = null;
      bridgeState.claimToken = randomUUID();
      await saveBridgeState(bridgeState);
      continue;
    }
    if (pending.phase === "claimed") {
      pending.phase = "executing";
      await saveBridgeState(bridgeState);
      let result;
      try {
        console.log("Received " + pending.job.command_type + " (" + pending.job.id + ")");
        result = await execute(pending.job);
      } catch (error) {
        result = { exitCode: 1, output: "", errorText: String(error?.message || error).slice(0, 8000) };
      }
      pending.result = {
        exitCode: Number.isInteger(result.exitCode) ? result.exitCode : 1,
        output: String(result.output || "").slice(0, MAX_OUTPUT),
        errorText: String(result.errorText || "").slice(0, 8000),
      };
      pending.phase = "result";
      await saveBridgeState(bridgeState);
    }
  } catch (error) {
    console.error("Bridge cycle failed:", String(error?.message || error));
    await sleep(POLL_MS);
  }
}
console.log("Termux bridge stopped.");
