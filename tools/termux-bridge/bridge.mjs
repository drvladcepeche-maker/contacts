import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { readdir } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, relative, resolve, sep } from "node:path";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline/promises";

const API_URL = process.env.TERMUX_BRIDGE_URL ||
  "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/termux-bridge";
const TOKEN_PATH = resolve(homedir(), ".config/termux-bridge/device-token");
const WORKSPACE = resolve(process.env.WORKSPACE || process.cwd());
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

async function request(body) {
  const response = await fetch(API_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
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
console.log("Automatic commands: pwd, list_files, git_status, git_log, git_diff_stat.");
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
