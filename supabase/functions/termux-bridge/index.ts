import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}

async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return json({ error: "Server configuration missing" }, 500);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return json({ error: "JSON object required" }, 400);
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  const bearer = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  if (!/^[a-f0-9]{64}$/.test(bearer)) return json({ error: "Unauthorized" }, 401);

  const supabase = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const tokenHash = await sha256(bearer);
  const { data: device, error: deviceError } = await supabase
    .from("termux_bridge_devices")
    .select("id, label")
    .eq("token_hash", tokenHash)
    .eq("enabled", true)
    .is("revoked_at", null)
    .maybeSingle();

  if (deviceError) return json({ error: "Device lookup failed" }, 500);
  if (!device) return json({ error: "Unauthorized or revoked device" }, 401);

  const action = String(body.action ?? "");
  await supabase.from("termux_bridge_devices")
    .update({ last_seen_at: new Date().toISOString() })
    .eq("id", device.id);

  if (action === "heartbeat") return json({ ok: true, device_id: device.id });


  if (action === "create_upload_url") {
    const filename = String(body.filename ?? "");
    const size = Number(body.size);
    const contentType = String(body.content_type ?? "application/octet-stream");
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(filename) || filename === "." || filename === "..") {
      return json({ error: "Invalid upload filename" }, 400);
    }
    if (!Number.isSafeInteger(size) || size < 1 || size > 50 * 1024 * 1024) {
      return json({ error: "Upload size must be between 1 byte and 50 MiB" }, 400);
    }
    const contentTypeParts = contentType.split("/");
    if (contentTypeParts.length !== 2 || contentTypeParts.some(part => !/^[A-Za-z0-9.+_-]+$/.test(part)) || contentType.length > 128) {
      return json({ error: "Invalid content type" }, 400);
    }
    const objectPath = `${device.id}/${crypto.randomUUID()}-${filename}`;
    const { data, error } = await supabase.storage
      .from("termux-bridge-artifacts")
      .createSignedUploadUrl(objectPath, { upsert: false });
    if (error || !data?.signedUrl || !data?.token) {
      return json({ error: "Could not create a private upload grant" }, 500);
    }
    return json({
      path: objectPath,
      token: data.token,
      signed_url: data.signedUrl,
      bucket: "termux-bridge-artifacts",
      expires_in: 7200,
    });
  }

  if (action === "brave_status") {
    const id = String(body.job_id ?? "");
    if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "Invalid job_id" }, 400);

    const { data: job, error } = await supabase
      .from("termux_bridge_jobs")
      .select("id, status, output, error_text, exit_code, created_at, finished_at")
      .eq("id", id)
      .eq("device_id", device.id)
      .eq("command_type", "brave_accessibility")
      .maybeSingle();

    if (error) return json({ error: "Could not read Brave command status" }, 500);
    if (!job) return json({ error: "Brave command not found for this device" }, 404);
    return json({ job });
  }

  if (action === "claim") {
    const claimToken = String(body.claim_token ?? "");
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claimToken)) {
      return json({ error: "Invalid claim_token" }, 400);
    }
    const { data, error } = await supabase.rpc("claim_termux_bridge_job", {
      p_device_id: device.id,
      p_claim_token: claimToken,
    });
    if (error) return json({ error: "Could not claim command" }, 500);
    const job = Array.isArray(data) ? (data[0] ?? null) : data;
    return json({ job });
  }

  if (action === "complete") {
    const id = String(body.job_id ?? "");
    if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "Invalid job_id" }, 400);
    const exitCode = Number.isInteger(body.exit_code) ? Number(body.exit_code) : 1;
    if (exitCode < -1 || exitCode > 255) return json({ error: "Invalid exit_code" }, 400);

    const patch = {
      status: exitCode === 0 ? "succeeded" : "failed",
      output: String(body.output ?? "").slice(0, 30000),
      error_text: String(body.error_text ?? "").slice(0, 8000) || null,
      exit_code: exitCode,
      finished_at: new Date().toISOString(),
    };
    const { data, error } = await supabase
      .from("termux_bridge_jobs")
      .update(patch)
      .eq("id", id)
      .eq("device_id", device.id)
      .eq("status", "running")
      .select("id")
      .maybeSingle();

    if (error) return json({ error: "Could not save command result" }, 500);
    if (data) return json({ ok: true });

    // A previous completion may have committed even when its HTTP response was lost.
    // Accept an exact replay, but never overwrite a terminal or conflicting result.
    const { data: existing, error: readError } = await supabase
      .from("termux_bridge_jobs")
      .select("status, output, error_text, exit_code")
      .eq("id", id)
      .eq("device_id", device.id)
      .maybeSingle();
    if (readError) return json({ error: "Could not verify command completion" }, 500);
    const sameResult = existing &&
      existing.status === patch.status &&
      (existing.output ?? "") === patch.output &&
      (existing.error_text ?? null) === patch.error_text &&
      existing.exit_code === patch.exit_code;
    if (sameResult) return json({ ok: true, already_completed: true });
    return json({ error: "Command not found, not running, or has a conflicting result" }, 409);
  }

  return json({ error: "Unsupported action" }, 400);
});
