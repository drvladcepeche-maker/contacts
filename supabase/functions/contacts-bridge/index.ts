import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: cors });

async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return json({ error: "Server configuration missing" }, 500);

  const supabase = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const bearer = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "").trim();
  if (!bearer) return json({ error: "Bearer token required" }, 401);

  const tokenHash = await sha256(bearer);
  const { data: device } = await supabase
    .from("contact_bridge_devices")
    .select("id, device_name")
    .eq("token_hash", tokenHash)
    .is("revoked_at", null)
    .maybeSingle();

  let role: "device" | "assistant" | null = device ? "device" : null;
  let deviceId: string | null = device?.id ?? null;

  if (!role) {
    const { data: admin } = await supabase
      .from("contact_bridge_admin_tokens")
      .select("id")
      .eq("token_hash", tokenHash)
      .is("revoked_at", null)
      .maybeSingle();
    if (admin) role = "assistant";
  }
  if (!role) return json({ error: "Invalid or revoked token" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }
  const action = String(body.action ?? "");

  if (role === "device") {
    await supabase.from("contact_bridge_devices")
      .update({ last_seen_at: new Date().toISOString() }).eq("id", deviceId);

    if (action === "sync_contacts") {
      const contacts = Array.isArray(body.contacts) ? body.contacts : [];
      if (contacts.length > 1000) return json({ error: "Maximum 1000 contacts per sync batch" }, 413);
      const rows = contacts.map((item: any) => ({
        device_id: deviceId,
        local_contact_id: String(item?.id ?? ""),
        contact_data: item,
        synced_at: new Date().toISOString(),
      })).filter((item: any) => item.local_contact_id.length > 0);
      if (rows.length) {
        const { error } = await supabase.from("contact_bridge_snapshots")
          .upsert(rows, { onConflict: "device_id,local_contact_id" });
        if (error) return json({ error: error.message }, 500);
      }
      return json({ synced: rows.length });
    }

    if (action === "poll_commands") {
      // Requeue claims older than five minutes. This recovers commands if the
      // app process exits after claiming but before persisting its local queue.
      const expiredBefore = new Date(Date.now() - 5 * 60 * 1000).toISOString();
      const { error: recoveryError } = await supabase.from("contact_bridge_commands")
        .update({ status: "pending", claimed_at: null })
        .eq("device_id", deviceId).eq("status", "claimed")
        .lt("claimed_at", expiredBefore);
      if (recoveryError) return json({ error: recoveryError.message }, 500);

      const { data: commands, error } = await supabase.from("contact_bridge_commands")
        .select("id, action, payload, created_at")
        .eq("device_id", deviceId).eq("status", "pending")
        .order("created_at", { ascending: true }).limit(20);
      if (error) return json({ error: error.message }, 500);
      const ids = (commands ?? []).map((c: any) => c.id);
      if (!ids.length) return json({ commands: [] });

      // Return only rows successfully transitioned by this poll request.
      const { data: claimed, error: claimError } = await supabase.from("contact_bridge_commands")
        .update({ status: "claimed", claimed_at: new Date().toISOString() })
        .in("id", ids).eq("device_id", deviceId).eq("status", "pending")
        .select("id, action, payload, created_at");
      if (claimError) return json({ error: claimError.message }, 500);
      return json({ commands: claimed ?? [] });
    }

    if (action === "complete_command") {
      const commandId = String(body.command_id ?? "");
      const status = body.status === "failed" ? "failed" : "completed";

      // Read the claimed command so successful contact writes can refresh the cache.
      const { data: command, error: commandReadError } = await supabase
        .from("contact_bridge_commands")
        .select("id, action, status")
        .eq("id", commandId)
        .eq("device_id", deviceId)
        .eq("status", "claimed")
        .maybeSingle();
      if (commandReadError) return json({ error: commandReadError.message }, 500);
      if (!command) return json({ error: "Command not found or not claimed" }, 404);

      let cacheUpdated = false;
      if (status === "completed" && command.action === "upsert_contact") {
        const result = body.result as Record<string, unknown> | null;
        const contact = result && result.contact && typeof result.contact === "object"
          ? result.contact as Record<string, unknown>
          : null;
        const contactId = contact?.id;
        if (contact && contactId !== undefined && contactId !== null && String(contactId)) {
          const localId = String(contactId);
          const { data: existing, error: existingError } = await supabase
            .from("contact_bridge_snapshots")
            .select("contact_data")
            .eq("device_id", deviceId)
            .eq("local_contact_id", localId)
            .maybeSingle();
          if (existingError) return json({ error: existingError.message }, 500);

          // Keep fields from the last full sync that the command result does not include.
          const prior = existing?.contact_data && typeof existing.contact_data === "object"
            ? existing.contact_data as Record<string, unknown>
            : {};
          const mergedContact = { ...prior, ...contact, id: contactId };
          const { error: snapshotError } = await supabase
            .from("contact_bridge_snapshots")
            .upsert({
              device_id: deviceId,
              local_contact_id: localId,
              contact_data: mergedContact,
              synced_at: new Date().toISOString(),
            }, { onConflict: "device_id,local_contact_id" });
          if (snapshotError) return json({ error: snapshotError.message }, 500);
          cacheUpdated = true;
        }
      }

      const { data, error } = await supabase.from("contact_bridge_commands")
        .update({
          status,
          result: body.result ?? null,
          error_message: body.error_message ? String(body.error_message).slice(0, 1000) : null,
          completed_at: new Date().toISOString(),
        })
        .eq("id", commandId).eq("device_id", deviceId).eq("status", "claimed")
        .select("id").maybeSingle();
      if (error) return json({ error: error.message }, 500);
      if (!data) return json({ error: "Command not found or not claimed" }, 404);
      return json({ ok: true, cache_updated: cacheUpdated });
    }

    return json({ error: "Action not allowed for device token" }, 403);
  }

  if (action === "status") {
    const { data: devices, error } = await supabase.from("contact_bridge_devices")
      .select("id, device_name, created_at, last_seen_at, revoked_at");
    if (error) return json({ error: error.message }, 500);
    const { count } = await supabase.from("contact_bridge_snapshots")
      .select("id", { count: "exact", head: true });
    return json({ devices, cached_contact_count: count ?? 0, bridge: "online" });
  }

  if (action === "list_contacts" || action === "search_contacts") {
    const q = String((body.query ?? "")).toLocaleLowerCase().trim();
    const limit = Math.min(Math.max(Number(body.limit ?? 100), 1), 500);
    let query = supabase.from("contact_bridge_snapshots")
      .select("local_contact_id, contact_data, synced_at, device_id")
      .order("synced_at", { ascending: false }).limit(500);
    if (body.device_id) query = query.eq("device_id", String(body.device_id));
    const { data, error } = await query;
    if (error) return json({ error: error.message }, 500);
    let contacts = data ?? [];
    if (action === "search_contacts") {
      if (!q) return json({ error: "query required" }, 400);
      contacts = contacts.filter((row: any) =>
        JSON.stringify(row.contact_data).toLocaleLowerCase().includes(q));
    }
    return json({ contacts: contacts.slice(0, limit), returned: Math.min(contacts.length, limit) });
  }

  if (action === "queue_command") {
    const commandAction = String(body.command_action ?? "");
    const allowed = ["list_contacts", "search_contacts", "upsert_contact", "delete_contact", "merge_contacts", "sync_contacts"];
    if (!allowed.includes(commandAction)) return json({ error: "Unsupported command_action" }, 400);
    if (!deviceId && body.device_id) deviceId = String(body.device_id);
    if (!deviceId) {
      const { data: first } = await supabase.from("contact_bridge_devices")
        .select("id").is("revoked_at", null).order("created_at", { ascending: true }).limit(1).maybeSingle();
      deviceId = first?.id ?? null;
    }
    if (!deviceId) return json({ error: "No paired device" }, 409);
    if (["delete_contact", "merge_contacts"].includes(commandAction) &&
        (body.confirmed_by_user !== true)) {
      return json({ error: "Destructive commands require confirmed_by_user=true" }, 403);
    }
    const { data, error } = await supabase.from("contact_bridge_commands")
      .insert({ device_id: deviceId, action: commandAction, payload: body.payload ?? {} })
      .select("id, device_id, action, status, created_at").single();
    if (error) return json({ error: error.message }, 500);
    return json({ command: data }, 201);
  }

  if (action === "command_status") {
    const commandId = String(body.command_id ?? "");
    if (!commandId) return json({ error: "command_id required" }, 400);
    const { data, error } = await supabase.from("contact_bridge_commands")
      .select("id, device_id, action, payload, status, result, error_message, created_at, completed_at")
      .eq("id", commandId).maybeSingle();
    if (error) return json({ error: error.message }, 500);
    if (!data) return json({ error: "Command not found" }, 404);
    return json({ command: data });
  }

  return json({ error: "Unknown action" }, 400);
});