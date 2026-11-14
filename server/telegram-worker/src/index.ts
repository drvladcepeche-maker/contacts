import { createServer } from "node:http";
import { setTimeout as sleep } from "node:timers/promises";
import { TelegramClient, Api } from "telegram";
import { StringSession } from "telegram/sessions/index.js";
import { createClient } from "@supabase/supabase-js";
import { extractTelegramUsernames, mergeTelegramUsernames } from "./telegram-usernames.js";
import {
  adaptiveRequestIntervalMs,
  calculateBatchResumeAt,
  calculateFloodWaitResumeAt,
  floodWaitSeconds,
} from "./rate-limit.js";
import { filterActiveContactIds } from "./contact-manifest.js";

type PhoneCandidate = {
  device_id: string;
  local_contact_id: string;
  local_contact_ids?: string[];
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
  flood_wait_count?: number;
  last_flood_wait_seconds?: number | null;
  last_flood_wait_at?: string | null;
};

const required = ["SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY"] as const;
for (const name of required) {
  if (!process.env[name]?.trim()) {
    console.error(`Missing required environment variable: ${name}`);
    process.exit(1);
  }
}
const fetchWithTimeout: typeof fetch = (input, init = {}) => {
  const timeoutSignal = AbortSignal.timeout(15000);
  const signal = init.signal ? AbortSignal.any([init.signal, timeoutSignal]) : timeoutSignal;
  return fetch(input, { ...init, signal });
};
const supabase = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SERVICE_ROLE_KEY!, {
  auth: { persistSession: false, autoRefreshToken: false },
  global: { fetch: fetchWithTimeout },
});
const pollMs = Math.max(3000, Number(process.env.POLL_INTERVAL_MS) || 5000);
const minIntervalMs = Math.max(60_000, Number(process.env.MIN_REQUEST_INTERVAL_MS) || 60_000);
const jitterMs = Math.max(0, Number(process.env.REQUEST_JITTER_MS) || 15_000);
const port = Number(process.env.PORT) || 8080;
const runOnce = process.env.RUN_ONCE === "true";
const maxCandidatesPerRun = Math.max(1, Number(process.env.MAX_CANDIDATES_PER_RUN) || 250);
const batchCooldownMs = Math.max(60_000, Number(process.env.BATCH_COOLDOWN_MS) || 60 * 60_000);

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
  const digits = raw.replace(/\D/g, "");
  return digits.length >= 8 && digits.length <= 15 ? `+${digits}` : null;
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
function describeSession(value: string): string {
  const length = value.length;
  const hasWhitespace = /\s/.test(value);
  const printable = /^[A-Za-z0-9+/=_-]+$/.test(value);
  return `len=${length} whitespace=${hasWhitespace} charset_ok=${printable}`;
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
  // Usernames belong to a specific Telegram account. Merge aliases only when
  // the newly resolved phone points to the same Telegram ID; otherwise stale
  // aliases from a previous account would be attached to the new ID.
  const existingTelegramId = String(prior.telegram_id ?? "");
  const mergedUsernames = mergeTelegramUsernames(
    existingTelegramId,
    String(result.telegram_id ?? ""),
    prior.telegram_username,
    result.username,
  );
  const preserveExistingMatch = alreadyMatched && result.status === "not_resolved";
  const next: Record<string, unknown> = {
    ...prior,
    telegram_match_status: preserveExistingMatch ? existingStatus : result.status,
    telegram_match_phone: preserveExistingMatch ? existingPhone : result.phone,
    telegram_match_checked_phones: checkedPhones,
    telegram_match_checked_at: result.checked_at,
  };
  if (result.status === "matched") {
    next.telegram_id = result.telegram_id;
    next.telegram_username = mergedUsernames ?? "";
  } else if (result.status === "username_missing") {
    next.telegram_id = result.telegram_id;
    // A private/hidden current username must not erase previously discovered names.
    next.telegram_username = mergedUsernames ?? "Скрыт";
  } else if (result.status === "not_resolved" && !alreadyMatched) {
    // An unresolved phone is not evidence that a previously confirmed Telegram match is invalid.
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
  const username = extractTelegramUsernames(user);
  return { id: userId, username };
}
async function waitForRateLimit(lastRequestAt?: string | null, floodWaitCount = 0) {
  const intervalMs = adaptiveRequestIntervalMs(minIntervalMs, floodWaitCount);
  const lastRequestMs = lastRequestAt ? Date.parse(lastRequestAt) : Number.NaN;
  // Even the first request in a fresh job observes the full interval. A missing
  // timestamp must never turn into an immediate Telegram request.
  const nextAllowedAt = Number.isFinite(lastRequestMs)
    ? lastRequestMs + intervalMs
    : Date.now() + intervalMs;
  const remaining = nextAllowedAt - Date.now();
  if (remaining > 0) await sleep(remaining);
  // Jitter is additive and cannot reduce the configured minimum interval.
  if (jitterMs > 0) await sleep(Math.floor(Math.random() * (jitterMs + 1)));
}
async function processJob(job: Job) {
  const candidates = Array.isArray(job.phones) ? job.phones : [];
  const results: MatchResult[] = Array.isArray(job.results) ? job.results : [];
  let lastRequestAt = job.last_request_at ?? null;

  // Load the last complete Android contact set once per device. Historical snapshots
  // remain archived, but Telegram lookups and Contacts Provider writes must target
  // only IDs that still exist on the phone.
  const activeIdsByDevice = new Map<string, Set<string>>();
  const deviceIds = Array.from(new Set(candidates
    .map(candidate => String(candidate?.device_id ?? ""))
    .filter(Boolean)));
  for (const deviceId of deviceIds) {
    const { data: manifest, error: manifestError } = await supabase
      .from("contact_bridge_sync_manifests")
      .select("local_contact_ids")
      .eq("device_id", deviceId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (manifestError) throw manifestError;
    if (!manifest) {
      const resumeAt = new Date(Date.now() + 60 * 60_000).toISOString();
      await updateJob(job.id, {
        status: "queued", paused_until: resumeAt,
        error_message: "Waiting for a complete Android contact sync manifest. Run a full contact sync before Telegram matching.",
      });
      console.warn(`Job ${job.id} paused: no full sync manifest for device ${deviceId}`);
      return;
    }
    activeIdsByDevice.set(deviceId, new Set<string>((manifest.local_contact_ids ?? []).map((id: unknown) => String(id))));
  }

  const startIndex = results.length;
  for (let index = startIndex; index < candidates.length; index++) {
    if (stopping) return;
    if (index - startIndex >= maxCandidatesPerRun) {
      const resumeAt = calculateBatchResumeAt(lastRequestAt, Date.now(), batchCooldownMs);
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

    const activeIds = activeIdsByDevice.get(candidate.device_id) ?? new Set<string>();
    const activeCandidateIds = filterActiveContactIds(candidate, activeIds);
    if (!activeCandidateIds.length) {
      // Count stale candidates as checked, but do not spend a Telegram request or
      // write back to a contact that no longer exists on the Android device.
      results.push({
        device_id: candidate.device_id,
        local_contact_id: candidate.local_contact_id,
        phone,
        telegram_id: null,
        username: null,
        status: "not_resolved",
        error: "Skipped: contact ID is absent from the latest full sync manifest.",
        checked_at: checkedAt,
      });
      await updateJob(job.id, {
        results, checked: results.length,
        matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
        errors: results.filter(x => x.status === "error").length,
        paused_until: null,
        error_message: null,
      });
      continue;
    }
    const activeCandidate: PhoneCandidate = {
      ...candidate,
      local_contact_id: activeCandidateIds[0],
      local_contact_ids: activeCandidateIds.slice(1),
    };

    await waitForRateLimit(lastRequestAt, job.flood_wait_count ?? 0);
    let result: MatchResult;
    try {
      const match = await resolvePhone(phone);
      result = match
        ? {
            device_id: activeCandidate.device_id, local_contact_id: activeCandidate.local_contact_id, phone,
            telegram_id: match.id, username: match.username,
            status: match.username ? "matched" : "username_missing", checked_at: new Date().toISOString(),
          }
        : {
            device_id: activeCandidate.device_id, local_contact_id: activeCandidate.local_contact_id, phone,
            telegram_id: null, username: null, status: "not_resolved", checked_at: new Date().toISOString(),
          };
    } catch (error) {
      const waitSeconds = floodWaitSeconds(error);
      if (waitSeconds !== null) {
        const floodWaitCount = (job.flood_wait_count ?? 0) + 1;
        const resumeAt = calculateFloodWaitResumeAt(waitSeconds);
        const floodWaitAt = new Date().toISOString();
        const nextIntervalMs = adaptiveRequestIntervalMs(minIntervalMs, floodWaitCount);
        await updateJob(job.id, {
          status: "queued", paused_until: resumeAt, last_request_at: floodWaitAt,
          flood_wait_count: floodWaitCount,
          last_flood_wait_seconds: waitSeconds,
          last_flood_wait_at: floodWaitAt,
          results, checked: results.length,
          matched: results.filter(x => x.status === "matched" || x.status === "username_missing").length,
          errors: results.filter(x => x.status === "error").length,
          error_message: `Telegram FloodWait #${floodWaitCount}: server requested ${waitSeconds}s; paused until ${resumeAt}; next request interval ${Math.round(nextIntervalMs / 1000)}s.`,
        });
        console.warn(JSON.stringify({
          event: "telegram_flood_wait",
          jobId: job.id,
          waitSeconds,
          floodWaitCount,
          resumeAt,
          nextRequestIntervalSeconds: Math.round(nextIntervalMs / 1000),
        }));
        return;
      }
      if (rpcIsNotResolved(error)) {
        result = {
          device_id: activeCandidate.device_id, local_contact_id: activeCandidate.local_contact_id, phone,
          telegram_id: null, username: null, status: "not_resolved", checked_at: new Date().toISOString(),
        };
      } else {
        result = {
          device_id: activeCandidate.device_id, local_contact_id: activeCandidate.local_contact_id, phone,
          telegram_id: null, username: null, status: "error",
          error: (error instanceof Error ? error.message : String(error)).slice(0, 300),
          checked_at: new Date().toISOString(),
        };
      }
    }
    lastRequestAt = new Date().toISOString();
    const targetContactIds = Array.from(new Set([
      activeCandidate.local_contact_id,
      ...(Array.isArray(activeCandidate.local_contact_ids) ? activeCandidate.local_contact_ids : []),
    ].filter((value): value is string => typeof value === "string" && value.length > 0)));
    for (const localContactId of targetContactIds) {
      await saveContactResult(
        { ...activeCandidate, local_contact_id: localContactId, local_contact_ids: [] },
        { ...result, local_contact_id: localContactId },
      );
    }
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
  console.log("Queue poll: requesting claim_telegram_match_job RPC");
  const rpc = supabase.rpc("claim_telegram_match_job");
  const timeout = new Promise<never>((_, reject) => {
    setTimeout(() => reject(new Error("Timed out after 20s waiting for claim_telegram_match_job RPC")), 20000);
  });
  const { data, error } = await Promise.race([rpc, timeout]);
  if (error) throw error;
  console.log(data ? "Queue poll: job claimed" : "Queue poll: no queued job");
  return data ? data as Job : null;
}
async function ensureTelegramConnected() {
  if (telegram && ready) return;
  const apiIdText = process.env.TELEGRAM_API_ID?.trim() ?? "";
  const apiHash = process.env.TELEGRAM_API_HASH?.trim() ?? "";
  const sessionString = process.env.TELEGRAM_SESSION?.trim() ?? "";
  const apiId = Number(apiIdText);
  if (!Number.isInteger(apiId) || apiId <= 0) throw new Error("TELEGRAM_API_ID is missing or invalid");
  if (!apiHash || !sessionString) throw new Error("TELEGRAM_API_HASH or TELEGRAM_SESSION is missing");
  console.log(`Telegram auth diagnostics: apiId=${apiId} apiHashLen=${apiHash.length} session(${describeSession(sessionString)})`);
  if (sessionString.length < 100) {
    throw new Error(`TELEGRAM_SESSION looks too short (${sessionString.length} chars). Re-copy the full StringSession into the GitHub secret.`);
  }
  telegram = new TelegramClient(new StringSession(sessionString), apiId, apiHash, {
    connectionRetries: 3,
    autoReconnect: true,
    requestRetries: 2,
    // Surface every FloodWait to the durable queue state instead of allowing
    // GramJS to sleep/retry invisibly inside the request.
    floodSleepThreshold: 0,
  });
  await telegram.connect();
  let authorized = false;
  try {
    const me: any = await telegram.getMe();
    authorized = !!me;
    console.log("getMe OK, id:", String(me?.id));
  } catch (e: any) {
    // Log the actual exception metadata without logging request payloads or credentials.
    const cause = e?.cause;
    console.error("TG getMe failure diagnostics:", {
      name: e?.name ?? null,
      message: e?.message ?? String(e),
      errorMessage: e?.errorMessage ?? null,
      code: e?.code ?? null,
      errorCode: e?.errorCode ?? null,
      causeName: cause?.name ?? null,
      causeMessage: cause?.message ?? (cause == null ? null : String(cause)),
      stack: typeof e?.stack === "string" ? e.stack : null,
    });
  }
  if (!authorized) {
    await telegram.disconnect().catch(() => {});
    telegram = undefined;
    throw new Error(
      `Telegram getMe request failed; session authorization is unconfirmed (${describeSession(sessionString)}). See the preceding TG getMe failure diagnostics.`
    );
  }
  ready = true;
  console.log("Dedicated Telegram MTProto session authorized; resolver worker connected.");
}

async function main() {
  console.log("Telegram resolver worker started; Telegram connects only when a queued job exists.");
  while (!stopping) {
    try {
      const job = await claimJob();
      if (job) {
        try {
          await ensureTelegramConnected();
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          await updateJob(job.id, {
            status: "queued", claimed_at: null,
            error_message: message.slice(0, 500),
          });
          throw error;
        }
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
    if (telegram) await telegram.disconnect().catch(() => {});
    server.close();
    console.log("One-shot resolver run finished.");
  }
}
process.on("SIGTERM", () => { stopping = true; server.close(); });
process.on("SIGINT", () => { stopping = true; server.close(); });
main().catch(error => { console.error("Worker startup failed", error); server.close(); process.exitCode = 1; });
