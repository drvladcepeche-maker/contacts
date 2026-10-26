import { createServer } from "node:http";
import { setTimeout as sleep } from "node:timers/promises";
import { TelegramClient, Api } from "telegram";
import { StringSession } from "telegram/sessions/index.js";
import bigInt from "big-integer";
import { createClient, SupabaseClient } from "@supabase/supabase-js";

type PhoneCandidate = { contact_id?: string | number; name?: string; phone: string };
type MatchResult = { contact_id?: string | number; phone: string; telegram_id: string | null; username: string | null; status: "matched" | "not_found" | "error"; error?: string };
type Job = { id: string; phones: PhoneCandidate[]; attempts?: number };

const required = ["SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "TELEGRAM_API_ID", "TELEGRAM_API_HASH", "TELEGRAM_SESSION"] as const;
for (const name of required) {
  if (!process.env[name]?.trim()) {
    console.error(`Missing required environment variable: ${name}`);
    process.exit(1);
  }
}
const supabaseUrl = process.env.SUPABASE_URL!;
const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY!;
const apiId = Number(process.env.TELEGRAM_API_ID);
if (!Number.isInteger(apiId) || apiId <= 0) throw new Error("TELEGRAM_API_ID must be a positive integer");
const pollMs = Math.max(2000, Number(process.env.POLL_INTERVAL_MS) || 5000);
const batchDelayMs = Math.max(3000, Number(process.env.BATCH_DELAY_MS) || 3000);
const supabase = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

let stopping = false;
let telegram: TelegramClient | undefined;
let isReady = false;

const server = createServer((req, res) => {
  if (req.url === "/health" && req.method === "GET") {
    res.writeHead(isReady && !stopping ? 200 : 503, { "content-type": "application/json" });
    res.end(JSON.stringify({ status: isReady && !stopping ? "ok" : "starting", telegramConnected: isReady }));
    return;
  }
  res.writeHead(404, { "content-type": "application/json" });
  res.end(JSON.stringify({ error: "not_found" }));
});
server.listen(Number(process.env.PORT) || 8080, "0.0.0.0", () => console.log("Health endpoint listening"));

async function updateJob(id: string, patch: Record<string, unknown>) {
  const { error } = await supabase.from("telegram_match_jobs").update({ ...patch, updated_at: new Date().toISOString() }).eq("id", id);
  if (error) throw error;
}

function phoneValue(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed.startsWith("+")) return null;
  const digits = trimmed.replace(/\D/g, "");
  return digits.length >= 8 && digits.length <= 15 ? `+${digits}` : null;
}

async function processJob(job: Job) {
  const candidates = Array.isArray(job.phones) ? job.phones : [];
  const results: MatchResult[] = [];
  let checked = 0;
  for (let start = 0; start < candidates.length; start += 20) {
    if (stopping) break;
    const batch = candidates.slice(start, start + 20);
    const valid = batch.map((candidate, batchIndex) => ({ candidate, phone: phoneValue(String(candidate.phone ?? "")), clientId: bigInt(start + batchIndex + 1) }));
    for (const item of valid.filter((x) => !x.phone)) {
      results.push({ contact_id: item.candidate.contact_id, phone: String(item.candidate.phone ?? ""), telegram_id: null, username: null, status: "error", error: "Phone must be international format +countrycode..." });
      checked++;
    }
    const importable = valid.filter((x): x is { candidate: PhoneCandidate; phone: string; clientId: bigInt.BigInteger } => Boolean(x.phone));
    if (importable.length) {
      try {
        const imported = await telegram!.invoke(new Api.contacts.ImportContacts({
          contacts: importable.map(({ candidate, phone, clientId }) => new Api.InputPhoneContact({
            clientId,
            phone,
            firstName: String(candidate.name || "Contact").slice(0, 64),
            lastName: "",
          })),
        }));
        const users = new Map<string, Api.TypeUser>();
        if ("users" in imported) {
          for (const user of imported.users) if ("id" in user) users.set(String(user.id), user as Api.TypeUser);
        }
        const userIds = new Map<string, string>();
        if ("imported" in imported) {
          for (const item of imported.imported) userIds.set(String(item.clientId), String(item.userId));
        }
        for (let i = 0; i < importable.length; i++) {
          const { candidate, phone } = importable[i];
          const match = imported.imported.find((x) => String(x.clientId) === String(importable[i].clientId));
          const user = match ? users.get(String(match.userId)) : undefined;
          const userId = match ? String(match.userId) : null;
          const username = user && "username" in user && user.username ? String(user.username) : null;
          results.push({ contact_id: candidate.contact_id, phone, telegram_id: userId, username, status: userId ? "matched" : "not_found" });
          checked++;
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        // Stop this job on FloodWait or API-level errors instead of retrying aggressively.
        results.push(...importable.map(({ candidate, phone }) => ({ contact_id: candidate.contact_id, phone, telegram_id: null, username: null, status: "error" as const, error: message.slice(0, 300) })));
        checked += importable.length;
        if (/FLOOD_WAIT|flood wait|A wait of/i.test(message)) {
          await updateJob(job.id, { status: "failed", checked, matched: results.filter((r) => r.status === "matched").length, errors: results.filter((r) => r.status === "error").length, results, error_message: message.slice(0, 500), completed_at: new Date().toISOString() });
          return;
        }
      }
    }
    await updateJob(job.id, { checked, matched: results.filter((r) => r.status === "matched").length, errors: results.filter((r) => r.status === "error").length, results });
    if (start + 20 < candidates.length) await sleep(batchDelayMs);
  }
  if (!stopping) {
    await updateJob(job.id, { status: "completed", checked, matched: results.filter((r) => r.status === "matched").length, errors: results.filter((r) => r.status === "error").length, results, completed_at: new Date().toISOString() });
  }
}

async function claimJob(): Promise<Job | null> {
  const { data, error } = await supabase.rpc("claim_telegram_match_job");
  if (error) throw error;
  if (!data) return null;
  return data as Job;
}

async function main() {
  telegram = new TelegramClient(new StringSession(process.env.TELEGRAM_SESSION!), apiId, process.env.TELEGRAM_API_HASH!, {
    connectionRetries: 3,
    autoReconnect: true,
    requestRetries: 1,
  });
  await telegram.connect();
  if (!(await telegram.checkAuthorization())) {
    throw new Error("Telegram session is not authorized. Generate a StringSession locally and set TELEGRAM_SESSION in Hostless secrets.");
  }
  isReady = true;
  console.log("Telegram client authorized; worker loop started");
  while (!stopping) {
    try {
      const job = await claimJob();
      if (job) {
        console.log(`Claimed Telegram match job ${job.id} (${job.phones?.length ?? 0} candidates)`);
        try {
          await processJob(job);
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          console.error("Job failed", job.id, message);
          await updateJob(job.id, { status: "failed", error_message: message.slice(0, 500), completed_at: new Date().toISOString() });
        }
      } else {
        await sleep(pollMs);
      }
    } catch (error) {
      console.error("Queue polling failed", error);
      await sleep(pollMs);
    }
  }
}

process.on("SIGTERM", () => { stopping = true; server.close(); });
process.on("SIGINT", () => { stopping = true; server.close(); });
main().catch((error) => { console.error("Worker startup failed", error); process.exitCode = 1; });
