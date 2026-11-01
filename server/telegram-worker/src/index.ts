import { createServer } from "node:http";
import { setTimeout as sleep } from "node:timers/promises";
import { TelegramClient, Api } from "telegram";
import { StringSession } from "telegram/sessions/index.js";
import { createClient } from "@supabase/supabase-js";

type PhoneCandidate = {
  device_id: string;
  local_contact_id: string;
  name?: string;
  phone: string;
};
type MatchStatus = "matched" | "username_missing" | "not_resolved" | "error";
type MatchResult = {
  device_id: string;
  local_contact_id: string;
  phone: string;
  telegram_id: string | null;
  username: string | null;
  status: MatchStatus;
  error?: string;
  checked_at: string;
};
type Job = {
  id: string;
  phones: PhoneCandidate[];
  results?: MatchResult[];
  checked?: number;
  matched?: number;
  errors?: number;
  last_request_at?: string | null;
};

const required = ["SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "TELEGRAM_API_ID", "TELEGRAM_API_HASH", "TELEGRAM_SESSION"] as const;
for (const name of required) {
  if (!process.env[name]?.trim()) {
    console.error(`Missing required environment variable: ${name}`);
    process.exit(1);
  }
}
const supabase = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SERVICE_ROLE_KEY!, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const apiId = Number(process.env.TELEGRAM_API_ID);
if (!Number.isInteger(apiId) || apiId <= 0) throw new Error("TELEGRAM_API_ID must be a positive integer");
const pollMs = Math.max(3000, Number(process.env.POLL_INTERVAL_MS) || 5000);
const minIntervalMs = Math.max(5000, Number(process.env.MIN_REQUEST_INTERVAL_MS) || 5000);
const jitterMs = Math.max(0, Number(process.env.REQUEST_JITTER_MS) || 3000);
const port = Number(process.env.PORT) || 8080;
const runOnce = process.env.RUN_ONCE === "true";
const maxCandidatesPerRun = Math.max(1, Number(process.env.MAX_CANDIDATES_PER_RUN) || 250);

let stopping = false;
let ready = false;
let telegram: TelegramClient | undefined;

const server = createServer((req, res) => {
  if (req.url === "/health" && req.method === "GET") {
    res.writeHead(ready && !stopping ? 200 : 503, { "content-type": "application/json" });
    res.end(JSON.stringify({ status: ready && !stopping ? "ok" : "starting", telegramConnected: ready }));
    return;
  }
  res.writeHead(404, { "content-type": "application/json" });
  res.end(JSON.stringify({ error: "not_found" }));
});
server.listen(port, "0.0.0.0", () => console.log(`Health endpoint listening on ${port}`));

function normalizePhone(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const raw = value.trim();
  if (!raw.startsWith("+")) return null;
  const digits = raw.replace(/\\D/g, "");
  return digits.length >= 8 && digits.length <= 15 ? `+${digits}` : null;
}
function floodWaitSeconds(error: unknown): number | null {
  const message = error instanceof Error ? error.message : String(error);
  const direct = message.match(/FLOOD(?:_PREMIUM)?_WAIT_(\\d+)/i);
  if (direct) return Number(direct[1]);
  const natural = message.match(/A wait of (\\d+) seconds is required/i);
  return natural ? Number(natural[1]) : null;
}
function rpcIsNotResolved(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /PHONE_NOT_OCCUPIED|PHONE_NUMBER_INVALID|USER_ID_INVALID|CONTACT_ID_INVALID/i.test(message);
}
function resolvedUserId(resolved: any): string | null {
  const peer = resolved?.peer;
  if (peer && peer.className === "PeerUser" && peer.userId !== undefined) return String(peer.userId);
  if (peer && peer.userId !== undefined) return String(peer.userId);
  const users = Array.isArray(resolved?.users) ? resolved.users : [];
  return users.length === 1 && users[0]?.id !== undefined ? String(users[0].id) : null;
}
async function updateJob(jobId: string, patch: Record<string, unknown>) {
  const { error } = await supabase.from("telegram_match_jobs")
    .update({ ...patch, updated_at: new Date().toISOString() }).eq("id", jobId);
  if (error) throw error;
}
async function saveContactResult(candidate: PhoneCandidate, result: MatchResult) {
  const { data: row, error: readError } = await supabase.from("contact_bridge_snapshots")
    .select("contact_data")
    .eq("device_id", candidate.device_id)
    .eq("local_contact_id", candidate.local_contact_id)
    .maybeSingle();
  if (readError) throw readError;
  if (!row) return;
  const prior = row.contact_data && typeof row.contact_data === "object" && !Array.isArray(row.contact_data)
    ? row.contact_data as Record<string, unknown> : {};
  const existingStatus = String(prior.telegram_match_status ?? "");
  const existingPhone = String(prior.telegram_match_phone ?? "");
  const checkedPhones = Array.isArray(prior.telegram_match_checked_phones)
    ? prior.telegram_match_checked_phones.filter((value): value is string => typeof value === "string")
    : [];
  if (!checkedPhones.includes(result.phone)) checkedPhones.push(result.phone);
  // Never let a later non-match for a second number erase an already confirmed match.
  const alreadyMatched = existingStatus === "matched" || existingStatus === "username_missing";
  const samePhone = existingPhone === result.phone;
  const next: Record<string, unknown> = {
    ...prior,
    telegram_match_status: result.status,
    telegram_match_phone: result.phone,
    telegram_match_checked_phones: checkedPhones,
    telegram_match_checked_at: result.checked_at,
  };
  if (result.status === "matched") {
    next.telegram_id = result.telegram_id;
    const priorUsernames = String(prior.telegram_username ?? "")
      .split(",").map((value) => value.trim().replace(/^@/, ""))
      .filter((value) => value && value.toUpperCase() !== "СКРЫТ");
    const foundUsernames = String(result.username ?? "")
      .split(",").map((value) => value.trim().replace(/^@/, "")).filter(Boolean);
    next.telegram_username = [...new Set([...priorUsernames, ...foundUsernames])].join(", ");
  } else if (result.status === "username_missing") {
    next.telegram_id = result.telegram_id;
    next.telegram_username = "Скрыт";
  } else if (result.status === "not_resolved" && !(alreadyMatched && !samePhone)) {
    // The number was not resolvable; do not label it as a privacy-hidden username.
    if (samePhone) {
      next.telegram_id = null;
      next.telegram_username = null;
    }
  }
  const telegramIdChanged = String(prior.telegram_id ?? "") !== String(next.telegram_id ?? "");
  const usernameChanged = String(prior.telegram_username ?? "") !== String(next.telegram_username ?? "");
  const { error: writeError } = await supabase.from("contact_bridge_snapshots")
    .update({ contact_data: next, synced_at: new Date().toISOString() })
    .eq("device_id", candidate.device_id)
    .eq("local_contact_id", candidate.local_contact_id);
  if (writeError) throw writeError;

  // Queue the actual Android Contacts Provider write. It will be applied when
  // that device reconnects; the server-side snapshot remains authoritative meanwhile.
  if (telegramIdChanged || usernameChanged) {
    const { error: commandError } = await supabase.from("contact_bridge_commands").insert({
      device_id: candidate.device_id,
      action: "update_telegram_fields",
      payload: {
        id: candidate.local_contact_id,
        telegram_id: String(next.telegram_id ?? ""),
        telegram_username: String(next.telegram_username ?? ""),
      },
    });
    if (commandError) throw commandError;
  }
}
async function resolvePhone(phone: string): Promise<{ id: string; username: string | null } | null> {
  const resolved: any = await telegram!.invoke(new Api.contacts.ResolvePhone({ phone }));
  const userId = resolvedUserId(resolved);
  if (!userId) return null;
  const users: any[] = Array.isArray(resolved?.users) ? resolved.users : [];
  const user = users.find((item) => String(item?.id) === userId);
  const usernameObject = user?.usernames && typeof user.usernames === "object"
    ? user.usernames : null;
  const activeUsernames = Array.isArray(usernameObject?.activeUsernames)
    ? usernameObject.activeUsernames : [];
  const legacyUsername = typeof user?.username === "string" ? [user.username] : [];
  const usernames = [...activeUsernames, ...legacyUsername]
    .map((value) => String(value ?? "").trim().replace(/^@/, ""))
    .filter(Boolean);
  const username = [...new Set(usernames)].join(", ") || null;
  return { id: userId, username };
}
async function waitForRateLimit(lastRequestAt?: string | null) {
  if (lastRequestAt) {
    const elapsed = Date.now() - new Date(lastRequestAt).getTime();
    const remaining = minIntervalMs - elapsed;
    if (remaining > 0) await sleep(remaining);
  }
  // The extra random delay is additive, never a substitute for the minimum interval.
  if (jitterMs > 0) await sleep(Math.floor(Math.random() * (jitterMs + 1)));
}
async function processJob(job: Job) {
  const candidates = Array.isArray(job.phones) ? job.phones : [];
  const results: MatchResult[] = Array.isArray(job.results) ? job.results : [];
  let lastRequestAt = job.last_request_at ?? null;
  const startIndex = results.length;
  for (let index = startIndex; index < candidates.length; index++) {
    if (stopping) return;
    if (index - startIndex >= maxCandidatesPerRun) {
      const resumeAt = new Date(Date.now() + 60_000).toISOString();
      await updateJob(job.id, {
        status: "queued", results, checked: results.length,
        matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
        errors: results.filter(x => x.status === "error").length,
        last_request_at: lastRequestAt, paused_until: resumeAt,
        error_message: null,
      });
      console.log(`Job ${job.id} checkpointed after ${results.length}/${candidates.length} candidates; resume at ${resumeAt}`);
      return;
    }
    const candidate = candidates[index];
    const phone = normalizePhone(candidate?.phone);
    const checkedAt = new Date().toISOString();
    if (!candidate?.device_id || !candidate?.local_contact_id || !phone) {
      const invalid: MatchResult = {
        device_id: String(candidate?.device_id ?? ""),
        local_contact_id: String(candidate?.local_contact_id ?? ""),
        phone: String(candidate?.phone ?? ""),
        telegram_id: null, username: null, status: "error",
        error: "Candidate must include device_id, local_contact_id and an international phone number.",
        checked_at: checkedAt,
      };
      results.push(invalid);
      await updateJob(job.id, { results, checked: results.length, matched: results.filter(x => x.status === "matched").length, errors: results.filter(x => x.status === "error").length });
      continue;
    }

    await waitForRateLimit(lastRequestAt);
    let result: MatchResult;
    try {
      const match = await resolvePhone(phone);
      result = match
        ? {
            device_id: candidate.device_id, local_contact_id: candidate.local_contact_id, phone,
            telegram_id: match.id, username: match.username,
            status: match.username ? "matched" : "username_missing", checked_at: new Date().toISOString(),
          }
        : {
            device_id: candidate.device_id, local_contact_id: candidate.local_contact_id, phone,
            telegram_id: null, username: null, status: "not_resolved", checked_at: new Date().toISOString(),
          };
    } catch (error) {
      const waitSeconds = floodWaitSeconds(error);
      if (waitSeconds !== null) {
        const resumeAt = new Date(Date.now() + (waitSeconds + 5) * 1000).toISOString();
        await updateJob(job.id, {
          status: "queued", paused_until: resumeAt, last_request_at: new Date().toISOString(),
          results, checked: results.length,
          matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
          errors: results.filter(x => x.status === "error").length,
          error_message: `Telegram FloodWait: paused for ${waitSeconds + 5} seconds.`,
        });
        console.warn(`FloodWait: job ${job.id} paused until ${resumeAt}`);
        return;
      }
      if (rpcIsNotResolved(error)) {
        result = {
          device_id: candidate.device_id, local_contact_id: candidate.local_contact_id, phone,
          telegram_id: null, username: null, status: "not_resolved", checked_at: new Date().toISOString(),
        };
      } else {
        result = {
          device_id: candidate.device_id, local_contact_id: candidate.local_contact_id, phone,
          telegram_id: null, username: null, status: "error",
          error: (error instanceof Error ? error.message : String(error)).slice(0, 300),
          checked_at: new Date().toISOString(),
        };
      }
    }
    lastRequestAt = new Date().toISOString();
    await saveContactResult(candidate, result);
    results.push(result);
    await updateJob(job.id, {
      results, checked: results.length,
      matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
      errors: results.filter(x => x.status === "error").length,
      last_request_at: lastRequestAt, paused_until: null, error_message: null,
    });
  }
  if (!stopping) {
    await updateJob(job.id, {
      status: "completed", checked: results.length,
      matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
      errors: results.filter(x => x.status === "error").length,
      completed_at: new Date().toISOString(), paused_until: null,
    });
  }
}
async function claimJob(): Promise<Job | null> {
  const { data, error } = await supabase.rpc("claim_telegram_match_job");
  if (error) throw error;
  return data ? data as Job : null;
}
async function main() {
  telegram = new TelegramClient(new StringSession(process.env.TELEGRAM_SESSION!), apiId, process.env.TELEGRAM_API_HASH!, {
    connectionRetries: 3, autoReconnect: true, requestRetries: 0,
  });
  await telegram.connect();
  if (!(await telegram.checkAuthorization())) throw new Error("Telegram session is not authorized. Generate a dedicated StringSession and store it as a Hostless secret.");
  ready = true;
  console.log("Dedicated Telegram MTProto session authorized; resolver worker started.");
  while (!stopping) {
    try {
      const job = await claimJob();
      if (job) {
        console.log(`Claimed job ${job.id}; remaining candidates: ${Math.max(0, (job.phones?.length ?? 0) - (job.results?.length ?? 0))}`);
        try { await processJob(job); }
        catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          console.error("Job processing failed", job.id, message);
          await updateJob(job.id, { status: "failed", error_message: message.slice(0, 500), completed_at: new Date().toISOString() });
        }
        if (runOnce) break;
      } else {
        if (runOnce) break;
        await sleep(pollMs);
      }
    } catch (error) {
      console.error("Queue polling failed", error instanceof Error ? error.message : String(error));
      if (runOnce) throw error;
      await sleep(pollMs);
    }
  }
  if (runOnce) {
    ready = false;
    stopping = true;
    await telegram.disconnect().catch(() => {});
    server.close();
    console.log("One-shot resolver run finished.");
  }
}
process.on("SIGTERM", () => { stopping = true; server.close(); });
process.on("SIGINT", () => { stopping = true; server.close(); });
main().catch(error => { console.error("Worker startup failed", error); process.exitCode = 1; });
