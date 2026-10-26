import { createHash } from "node:crypto";
import { inspectApk } from "./apk-inspect.mjs";
import { readFile, readdir, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, relative, resolve, sep } from "node:path";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline/promises";

const API_URL = process.env.TERMUX_BRIDGE_URL ||
  "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge";
const TOKEN_PATH = resolve(homedir(), ".config/termux-bridge/device-token");
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

function curlConfigQuote(value) {
  return '"' + String(value)
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\r/g, "\\r")
    .replace(/\n/g, "\\n") + '"';
}

async function requestViaCurlOnce(body) {
  const payload = JSON.stringify(body);
  const marker = "__TERMUX_BRIDGE_HTTP_STATUS__";
  const config = [
    `url = ${curlConfigQuote(API_URL)}`,
    'request = "POST"',
    `header = ${curlConfigQuote("content-type: application/json")}`,
    `header = ${curlConfigQuote("authorization: Bearer " + token)}`,
    `data = ${curlConfigQuote(payload)}`,
    `max-time = "20"`,
    `write-out = ${curlConfigQuote("\\n" + marker + "%{http_code}")}`,
  ].join("\n") + "\n";

  const result = await new Promise((resolveRun) => {
    const child = spawn("curl", [
      "--silent", "--show-error", "--ipv4", "--http1.1",
      "--connect-timeout", "10", "--max-time", "20", "--config", "-"
    ], {
      cwd: WORKSPACE,
      shell: false,
      env: { ...process.env, TERMUX_BRIDGE_TOKEN: undefined },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => child.kill("SIGKILL"), 22000);
    child.stdout.on("data", chunk => { stdout = (stdout + chunk.toString()).slice(-MAX_OUTPUT - 128); });
    child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString()).slice(-4000); });
    child.on("error", error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolveRun({ code: 127, stdout, stderr: error.message });
    });
    child.on("close", code => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolveRun({ code: Number.isInteger(code) ? code : 1, stdout, stderr });
    });
    child.stdin.end(config);
  });

  const markerAt = result.stdout.lastIndexOf(marker);
  if (result.code !== 0 || markerAt < 0) {
    throw new Error(`Bridge curl fallback failed (exit ${result.code}): ${result.stderr || "no HTTP status received"}`);
  }
  const responseText = result.stdout.slice(0, markerAt).replace(/\n$/, "");
  const statusText = result.stdout.slice(markerAt + marker.length).trim();
  const status = Number(statusText);
  let data;
  try { data = responseText ? JSON.parse(responseText) : {}; }
  catch { throw new Error(`Bridge returned invalid JSON (HTTP ${status})`); }
  if (status < 200 || status >= 300) throw new Error(data.error || `Bridge HTTP ${status}`);
  return data;
}

async function requestViaCurl(body) {
  const maxAttempts = 5;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const data = await requestViaCurlOnce(body);
      if (attempt > 1) {
        console.error(`Curl transport recovered on attempt ${attempt}/${maxAttempts}.`);
      }
      return data;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      const transientTlsFailure =
        /Bridge curl fallback failed \(exit 35\)/i.test(message) ||
        /TLS connect error|unexpected eof while reading/i.test(message);
      if (!transientTlsFailure || attempt >= maxAttempts) throw error;
      const delayMs = Math.min(10000, 750 * (2 ** (attempt - 1)));
      const detail = message.replace(/[\r\n]+/g, " ").slice(0, 300);
      console.error(
        `Transient TLS failure on attempt ${attempt}/${maxAttempts}: ${detail}; retrying in ${delayMs} ms.`
      );
      await sleep(delayMs);
    }
  }
  throw new Error("Curl transport exhausted its retry budget.");
}
let preferCurlTransport = false;

async function request(body) {
  if (preferCurlTransport) return await requestViaCurl(body);
  let response;
  try {
    response = await fetch(API_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    });
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    preferCurlTransport = true;
    console.error(`Node fetch unavailable; using curl transport: ${reason}`);
    return await requestViaCurl(body);
  }

  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; }
  catch { throw new Error(`Bridge returned invalid JSON (HTTP ${response.status})`); }
  if (!response.ok) throw new Error(data.error || `Bridge HTTP ${response.status}`);
  return data;
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
  return { exitCode: 0, output: rows.join("\\n") || "No APK files found.", errorText: "" };
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
console.log("Automatic commands: pwd, list_files, file_read (bounded text/code only), apk_inventory, apk_inspect, git_status, git_log, git_diff_stat, git_fetch_main, node_check_bridge, node_check_apk_inspect.");
console.log("Gradle commands require typing YES in this terminal.");
console.log("No arbitrary shell command is accepted.");

process.on("SIGINT", () => { stopping = true; });
process.on("SIGTERM", () => { stopping = true; });

while (!stopping) {
  try {
    const response = await request({ action: "claim" });
    const job = response.job;
    if (!job) {
      await sleep(POLL_MS);
      continue;
    }
    console.log(`Received ${job.command_type} (${job.id})`);
    let result;
    try {
      result = await execute(job);
    } catch (error) {
      result = { exitCode: 1, output: "", errorText: String(error?.message || error).slice(0, 8000) };
    }
    await request({
      action: "complete",
      job_id: job.id,
      output: String(result.output || "").slice(0, MAX_OUTPUT),
      error_text: String(result.errorText || "").slice(0, 8000),
      exit_code: Number.isInteger(result.exitCode) ? result.exitCode : 1,
    });
    console.log(`Finished ${job.id} with exit code ${result.exitCode}`);
  } catch (error) {
    console.error("Bridge cycle failed:", String(error?.message || error));
    await sleep(POLL_MS);
  }
}
console.log("Termux bridge stopped.");
