import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const headers = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store, max-age=0",
  "Pragma": "no-cache",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers });
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const size = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

// WebCrypto ECDSA returns fixed-width IEEE P1363 r||s; Android JCA
// SHA256withECDSA expects the ASN.1 DER SEQUENCE(INTEGER r, INTEGER s) form.
function p1363ToDer(signature: Uint8Array): Uint8Array {
  if (signature.length !== 64) throw new Error("Unexpected P-256 signature size");

  const encodeInteger = (value: Uint8Array): Uint8Array => {
    let first = 0;
    while (first < value.length - 1 && value[first] === 0) first++;
    let body = value.subarray(first);
    if ((body[0] & 0x80) !== 0) body = concatBytes(new Uint8Array([0]), body);
    return concatBytes(new Uint8Array([0x02, body.length]), body);
  };

  const body = concatBytes(
    encodeInteger(signature.subarray(0, 32)),
    encodeInteger(signature.subarray(32, 64)),
  );
  if (body.length >= 128) throw new Error("Unexpected DER signature length");
  return concatBytes(new Uint8Array([0x30, body.length]), body);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function validateConfig(value: unknown): Record<string, unknown> {
  if (!isPlainObject(value) || !hasOnlyKeys(value, ["features", "sync"])) {
    throw new Error("Stored config object has unknown or missing fields");
  }
  const features = value.features;
  const sync = value.sync;
  if (!isPlainObject(features) || !isPlainObject(sync)) {
    throw new Error("Stored config containers are invalid");
  }
  if (!hasOnlyKeys(features, ["telegram_match_enabled", "brave_automation_enabled"]) ||
      !hasOnlyKeys(sync, ["poll_interval_seconds"])) {
    throw new Error("Stored config contains unsupported keys");
  }
  for (const [key, flag] of Object.entries(features)) {
    if (typeof flag !== "boolean") throw new Error("Feature flag must be boolean: " + key);
  }
  if ("poll_interval_seconds" in sync) {
    const interval = sync.poll_interval_seconds;
    if (!Number.isSafeInteger(interval) || Number(interval) < 15 || Number(interval) > 300) {
      throw new Error("Stored polling interval is outside the allowed range");
    }
  }
  return value;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  const bearer = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  if (!/^[a-f0-9]{64}$/.test(bearer)) return json({ error: "Unauthorized" }, 401);

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const privateKeyBase64 = Deno.env.get("REMOTE_CONFIG_PRIVATE_KEY_PKCS8_B64");
  if (!url || !serviceKey) return json({ error: "Server configuration missing" }, 500);
  if (!privateKeyBase64) return json({ error: "Remote config signing is not provisioned" }, 503);

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }
  if (!isPlainObject(body) || Object.keys(body).length !== 0) {
    return json({ error: "Empty JSON object required" }, 400);
  }

  try {
    const supabase = createClient(url, serviceKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    const tokenHash = await sha256Hex(bearer);
    const { data: device, error: deviceError } = await supabase
      .from("termux_bridge_devices")
      .select("id")
      .eq("token_hash", tokenHash)
      .eq("enabled", true)
      .is("revoked_at", null)
      .maybeSingle();

    if (deviceError) return json({ error: "Device lookup failed" }, 500);
    if (!device) return json({ error: "Unauthorized or revoked device" }, 401);

    await supabase.from("termux_bridge_devices")
      .update({ last_seen_at: new Date().toISOString() })
      .eq("id", device.id);

    const { data: configRow, error: configError } = await supabase
      .from("remote_app_config")
      .select("sequence, issued_at, expires_at, config")
      .eq("id", 1)
      .maybeSingle();

    if (configError) return json({ error: "Remote config lookup failed" }, 500);
    if (!configRow) return json({ error: "Remote config is not initialized" }, 503);

    const now = Math.floor(Date.now() / 1000);
    const sequence = Number(configRow.sequence);
    const issuedAt = Number(configRow.issued_at);
    const expiresAt = Number(configRow.expires_at);
    if (!Number.isSafeInteger(sequence) || sequence < 1 ||
        !Number.isSafeInteger(issuedAt) || issuedAt < 0 ||
        !Number.isSafeInteger(expiresAt) || expiresAt <= issuedAt ||
        expiresAt <= now || issuedAt > now + 300 ||
        expiresAt - issuedAt > 30 * 24 * 60 * 60) {
      return json({ error: "Remote config timestamps or sequence are invalid" }, 503);
    }

    const payloadObject = {
      schema_version: 1,
      sequence,
      issued_at: issuedAt,
      expires_at: expiresAt,
      config: validateConfig(configRow.config),
    };
    const payloadBytes = new TextEncoder().encode(JSON.stringify(payloadObject));
    if (payloadBytes.length > 65_536) return json({ error: "Remote config is too large" }, 503);

    const privateKeyBytes = base64ToBytes(privateKeyBase64);
    if (privateKeyBytes.length < 80 || privateKeyBytes.length > 256) {
      return json({ error: "Signing key configuration is invalid" }, 503);
    }
    const privateKey = await crypto.subtle.importKey(
      "pkcs8",
      privateKeyBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"],
    );
    const rawSignature = new Uint8Array(await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      privateKey,
      payloadBytes,
    ));
    const derSignature = p1363ToDer(rawSignature);

    return json({
      payload: bytesToBase64Url(payloadBytes),
      signature: bytesToBase64Url(derSignature),
    });
  } catch (error) {
    console.error("Remote config signing failed:", error instanceof Error ? error.message : "unknown error");
    return json({ error: "Remote config signing failed" }, 500);
  }
});
