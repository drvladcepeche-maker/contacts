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

  if (action === "claim") {
    const { data, error } = await supabase.rpc("claim_termux_bridge_job", {
      p_device_id: device.id,
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
    if (!data) return json({ error: "Command not found or not running for this device" }, 404);
    return json({ ok: true });
  }

  return json({ error: "Unsupported action" }, 400);
});
