import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { resolve, sep } from "node:path";

const MAX_SESSIONS = 3;
const MAX_COMMAND = 8192;
const MAX_INPUT = 16384;
const MAX_BUFFER = 65536;
const DEFAULT_TIMEOUT = 120000;
const MAX_TIMEOUT = 15 * 60 * 1000;
const SESSION_TTL = 15 * 60 * 1000;

function bounded(value, max) {
  return String(value ?? "").slice(0, max);
}

function redact(value) {
  return String(value)
    .replace(/\b[a-f0-9]{64}\b/gi, "[REDACTED_TOKEN]")
    .replace(/(Bearer\s+)[A-Za-z0-9._~-]+/gi, "$1[REDACTED]")
    .replace(/((?:password|passwd|secret|token|api[_-]?key)\s*[=:]\s*)[^\s"'&]+/gi, "$1[REDACTED]");
}

function safeEnv(source) {
  const env = {};
  for (const [key, value] of Object.entries(source)) {
    if (/token|secret|password|credential|private.?key|service.?role/i.test(key)) continue;
    if (/^(PATH|HOME|PREFIX|TMPDIR|LANG|LC_ALL|TERM|ANDROID_ROOT|ANDROID_DATA|EXTERNAL_STORAGE|USER|SHELL|COLORTERM)$/i.test(key)) {
      if (typeof value === "string") env[key] = value;
    }
  }
  return env;
}

function workspacePath(workspace, candidate) {
  const target = resolve(workspace, candidate || ".");
  if (target !== workspace && !target.startsWith(workspace + sep)) {
    throw new Error("Terminal working directory must remain inside the configured workspace.");
  }
  return target;
}

export class TerminalSessions {
  constructor({ workspace, env = process.env, logger = console }) {
    this.workspace = resolve(workspace);
    this.env = safeEnv(env);
    this.logger = logger;
    this.sessions = new Map();
  }

  async run({ command, cwd = ".", timeout_ms = DEFAULT_TIMEOUT }) {
    const source = bounded(command, MAX_COMMAND);
    if (!source.trim() || String(command).length > MAX_COMMAND) {
      throw new Error("terminal_run requires a non-empty command of at most 8192 characters.");
    }
    const dir = workspacePath(this.workspace, cwd);
    const timeout = Math.min(MAX_TIMEOUT, Math.max(1000, Number(timeout_ms) || DEFAULT_TIMEOUT));
    return await new Promise((resolveRun) => {
      const child = spawn("bash", ["--noprofile", "--norc", "-c", source], {
        cwd: dir,
        shell: false,
        env: this.env,
        stdio: ["ignore", "pipe", "pipe"],
      });
      let stdout = "";
      let stderr = "";
      let settled = false;
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        child.kill("SIGTERM");
        setTimeout(() => child.kill("SIGKILL"), 2000).unref();
      }, timeout);
      const append = (current, chunk) => (current + redact(chunk.toString())).slice(-MAX_BUFFER);
      child.stdout.on("data", chunk => { stdout = append(stdout, chunk); });
      child.stderr.on("data", chunk => { stderr = append(stderr, chunk); });
      child.on("error", error => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolveRun({ exitCode: 127, output: stdout, errorText: redact(error.message), timedOut });
      });
      child.on("close", code => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolveRun({
          exitCode: timedOut ? 124 : (Number.isInteger(code) ? code : 1),
          output: stdout,
          errorText: [stderr, timedOut ? "Command timed out and was terminated." : ""].filter(Boolean).join("\n"),
          timedOut,
        });
      });
    });
  }

  start({ command = "", cwd = "." }) {
    if (this.sessions.size >= MAX_SESSIONS) throw new Error("Maximum of 3 active terminal sessions reached.");
    const source = bounded(command, MAX_COMMAND);
    if (String(command).length > MAX_COMMAND) throw new Error("Initial command exceeds 8192 characters.");
    const dir = workspacePath(this.workspace, cwd);
    const id = randomUUID();
    const child = spawn("bash", ["--noprofile", "--norc"], {
      cwd: dir,
      shell: false,
      env: this.env,
      stdio: ["pipe", "pipe", "pipe"],
    });
    const session = {
      id, child, cwd: dir, startedAt: Date.now(), lastActivity: Date.now(),
      stdout: "", stderr: "", exitCode: null, closed: false, truncated: false,
    };
    const append = (field, chunk) => {
      const next = session[field] + redact(chunk.toString());
      if (next.length > MAX_BUFFER) {
        session[field] = next.slice(-MAX_BUFFER);
        session.truncated = true;
      } else session[field] = next;
      session.lastActivity = Date.now();
    };
    child.stdout.on("data", chunk => append("stdout", chunk));
    child.stderr.on("data", chunk => append("stderr", chunk));
    child.on("error", error => {
      session.stderr = (session.stderr + redact(error.message)).slice(-MAX_BUFFER);
      session.exitCode = 127;
      session.closed = true;
    });
    child.on("close", code => {
      session.exitCode = Number.isInteger(code) ? code : 1;
      session.closed = true;
      session.lastActivity = Date.now();
    });
    this.sessions.set(id, session);
    if (source.trim()) child.stdin.write(source + "\n");
    return { session_id: id, pid: child.pid, cwd: dir, state: "running" };
  }

  input({ session_id, input }) {
    const session = this.get(session_id);
    const value = String(input ?? "");
    if (value.length > MAX_INPUT) throw new Error("Terminal input exceeds 16384 characters.");
    if (session.closed || !session.child.stdin.writable) throw new Error("Terminal session stdin is closed.");
    session.child.stdin.write(value);
    session.lastActivity = Date.now();
    return { session_id: session.id, accepted_chars: value.length, state: "running" };
  }

  read({ session_id, clear = true }) {
    const session = this.get(session_id);
    const result = {
      session_id: session.id,
      stdout: session.stdout,
      stderr: session.stderr,
      exit_code: session.exitCode,
      state: session.closed ? "exited" : "running",
      truncated: session.truncated,
      age_ms: Date.now() - session.startedAt,
    };
    if (clear) {
      session.stdout = "";
      session.stderr = "";
      session.truncated = false;
    }
    session.lastActivity = Date.now();
    if (session.closed && clear) this.sessions.delete(session.id);
    return result;
  }

  stop({ session_id }) {
    const session = this.get(session_id);
    if (!session.closed) {
      session.child.kill("SIGTERM");
      setTimeout(() => {
        if (!session.closed) session.child.kill("SIGKILL");
      }, 2000).unref();
    }
    this.sessions.delete(session.id);
    return { session_id: session.id, state: "stop_requested" };
  }


  pruneExpired() {
    const now = Date.now();
    for (const [id, session] of this.sessions.entries()) {
      if (now - session.lastActivity > SESSION_TTL) {
        if (!session.closed) session.child.kill("SIGTERM");
        this.sessions.delete(id);
      }
    }
  }

  get(id) {
    const session = this.sessions.get(String(id || ""));
    if (!session) throw new Error("Terminal session not found or expired.");
    if (Date.now() - session.lastActivity > SESSION_TTL) {
      session.child.kill("SIGTERM");
      this.sessions.delete(session.id);
      throw new Error("Terminal session expired after 15 minutes of inactivity.");
    }
    return session;
  }

  closeAll() {
    for (const session of this.sessions.values()) {
      if (!session.closed) session.child.kill("SIGTERM");
    }
    this.sessions.clear();
  }
}
