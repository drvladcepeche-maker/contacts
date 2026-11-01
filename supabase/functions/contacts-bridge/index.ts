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
      // Deduplicate repeated local IDs inside a single batch; the last occurrence wins.
      // Unique (device_id, local_contact_id) + upsert makes repeated full syncs idempotent.
      const byLocalId = new Map<string, Record<string, unknown>>();
      for (const item of contacts as any[]) {
        const localId = String(item?.id ?? "").trim();
        if (!localId) continue;
        byLocalId.set(localId, {
          device_id: deviceId,
          local_contact_id: localId,
          contact_data: item,
          synced_at: new Date().toISOString(),
        });
      }
      const rows = Array.from(byLocalId.values());
      if (rows.length) {
        const ids = rows.map((row) => String(row.local_contact_id));
        const { data: previousRows, error: previousError } = await supabase
          .from("contact_bridge_snapshots")
          .select("local_contact_id, contact_data")
          .eq("device_id", deviceId)
          .in("local_contact_id", ids);
        if (previousError) return json({ error: previousError.message }, 500);
        const previousById = new Map((previousRows ?? []).map((row: any) => [String(row.local_contact_id), row.contact_data]));
        const mergedRows = rows.map((row) => {
          const incoming = row.contact_data as Record<string, unknown>;
          const previous = previousById.get(String(row.local_contact_id));
          if (!previous || typeof previous !== "object" || Array.isArray(previous)) return row;
          const prior = previous as Record<string, unknown>;
          const merged: Record<string, unknown> = { ...prior, ...incoming };
          // Android may sync before it has polled the queued write. Preserve resolver
          // metadata so checked phone numbers are not scanned again after every sync.
          for (const key of [
            "telegram_match_status",
            "telegram_match_phone",
            "telegram_match_checked_phones",
            "telegram_match_checked_at",
          ]) {
            if (prior[key] !== undefined) merged[key] = prior[key];
          }
          // A server-confirmed result remains authoritative until the queued Android
          // update is applied. A not_resolved result also preserves a deliberate clear.
          const hasServerTelegramValue = Boolean(prior.telegram_id) ||
            (typeof prior.telegram_username === "string" && prior.telegram_username.length > 0);
          if (hasServerTelegramValue) {
            // A later non-match for another number must not erase an earlier match.
            if (prior.telegram_id !== undefined) merged.telegram_id = prior.telegram_id;
            if (prior.telegram_username !== undefined) merged.telegram_username = prior.telegram_username;
          } else if (prior.telegram_match_status === "not_resolved") {
            // Preserve an intentional server-side clear for the last checked number.
            merged.telegram_id = prior.telegram_id ?? null;
            merged.telegram_username = prior.telegram_username ?? null;
          }
          return { ...row, contact_data: merged };
        });
        const { error } = await supabase.from("contact_bridge_snapshots")
          .upsert(mergedRows, { onConflict: "device_id,local_contact_id" });
        if (error) return json({ error: error.message }, 500);
      }
      return json({ synced: rows.length, unique_contacts: rows.length, duplicate_ids_in_batch_removed: contacts.length - rows.length });
    }

    if (action === "prune_contacts") {
      const rawKeepIds = Array.isArray(body.keep_ids) ? body.keep_ids : null;
      if (!rawKeepIds) return json({ error: "keep_ids must be an array" }, 400);
      if (rawKeepIds.length > 7000) return json({ error: "Maximum 7000 contacts per device snapshot" }, 413);
      const normalized = rawKeepIds.map((value: unknown) =>
        typeof value === "string" ? value.trim() : ""
      );
      if (normalized.some((value: string) => !value)) {
        return json({ error: "keep_ids must contain only non-empty strings" }, 400);
      }
      const keepIds = Array.from(new Set(normalized));
      const keepSet = new Set(keepIds);
      const existingRows: Array<{ local_contact_id: string }> = [];
      for (let offset = 0; ; offset += 1000) {
        const { data: page, error: pageError } = await supabase
          .from("contact_bridge_snapshots")
          .select("local_contact_id")
          .eq("device_id", deviceId)
          .order("local_contact_id", { ascending: true })
          .range(offset, offset + 999);
        if (pageError) return json({ error: pageError.message }, 500);
        existingRows.push(...(page ?? []));
        if (!page || page.length < 1000) break;
      }
      const staleIds = existingRows
        .map((row) => String(row.local_contact_id))
        .filter((id) => !keepSet.has(id));
      for (let offset = 0; offset < staleIds.length; offset += 500) {
        const batch = staleIds.slice(offset, offset + 500);
        const { error: deleteError } = await supabase
          .from("contact_bridge_snapshots")
          .delete()
          .eq("device_id", deviceId)
          .in("local_contact_id", batch);
        if (deleteError) return json({ error: deleteError.message }, 500);
      }
      return json({ kept: keepIds.length, deleted: staleIds.length });
    }

    if (action === "queue_telegram_match") {
      const { data: activeJobs, error: activeError } = await supabase
        .from("telegram_match_jobs")
        .select("id, status")
        .in("status", ["queued", "running"])
        .order("created_at", { ascending: false })
        .limit(1);
      if (activeError) return json({ error: activeError.message }, 500);
      if (activeJobs?.length) {
        return json({ error: "A Telegram matching job is already queued or running", job_id: activeJobs[0].id }, 409);
      }

      const { count: snapshotCount, error: countError } = await supabase
        .from("contact_bridge_snapshots")
        .select("local_contact_id", { count: "exact", head: true })
        .eq("device_id", deviceId);
      if (countError) return json({ error: countError.message }, 500);
      if ((snapshotCount ?? 0) > 7000) {
        return json({ error: "Device snapshot exceeds the 7000-contact limit; run a full sync first" }, 413);
      }
      const snapshots: Array<{ local_contact_id: string; contact_data: unknown }> = [];
      for (let offset = 0; ; offset += 1000) {
        const { data: page, error: pageError } = await supabase
          .from("contact_bridge_snapshots")
          .select("local_contact_id, contact_data")
          .eq("device_id", deviceId)
          .order("local_contact_id", { ascending: true })
          .range(offset, offset + 999);
        if (pageError) return json({ error: pageError.message }, 500);
        snapshots.push(...(page ?? []));
        if (!page || page.length < 1000) break;
      }

      const byPhone = new Map<string, {
        device_id: string;
        local_contact_id: string;
        local_contact_ids: string[];
        name: string;
        phone: string;
      }>();
      for (const row of snapshots ?? []) {
        const localId = String(row.local_contact_id ?? "").trim();
        const data = row.contact_data && typeof row.contact_data === "object" && !Array.isArray(row.contact_data)
          ? row.contact_data as Record<string, unknown>
          : {};
        const phones = Array.isArray(data.phones) ? data.phones : [];
        for (const value of phones) {
          const raw = typeof value === "string" ? value.trim() : "";
          if (!raw.startsWith("+")) continue;
          const digits = raw.replace(/\D/g, "");
          if (digits.length < 8 || digits.length > 15) continue;
          const phone = "+" + digits;
          const existing = byPhone.get(phone);
          if (!existing) {
            byPhone.set(phone, {
              device_id: deviceId!,
              local_contact_id: localId,
              local_contact_ids: [],
              name: String(data.name ?? ""),
              phone,
            });
          } else if (localId && localId !== existing.local_contact_id && !existing.local_contact_ids.includes(localId)) {
            existing.local_contact_ids.push(localId);
          }
        }
      }

      const candidates = Array.from(byPhone.values()).filter((candidate) => candidate.local_contact_id);
      if (candidates.length > 25000) {
        return json({ error: "Telegram match job exceeds the 25000 unique-phone safety limit" }, 413);
      }
      if (!candidates.length) {
        return json({ queued: false, contacts: (snapshots ?? []).length, unique_phone_count: 0 });
      }
      const { data: job, error: insertError } = await supabase
        .from("telegram_match_jobs")
        .insert({
          status: "queued",
          phones: candidates,
          results: [],
          checked: 0,
          matched: 0,
          errors: 0,
        })
        .select("id")
        .single();
      if (insertError) return json({ error: insertError.message }, 500);
      return json({
        queued: true,
        job_id: job.id,
        contacts: (snapshots ?? []).length,
        unique_phone_count: candidates.length,
      });
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
    const allowed = ["list_contacts", "search_contacts", "upsert_contact", "delete_contact", "merge_contacts", "sync_contacts", "update_telegram_fields"];
    if (!allowed.includes(commandAction)) return json({ error: "Unsupported command_action" }, 400);
    if (!deviceId && body.device_id) deviceId = String(body.device_id);
    if (!deviceId) {
      // Prefer the device that has checked in most recently. The oldest
      // registered device may have been replaced and left stale but unrevoked.
      const { data: first } = await supabase.from("contact_bridge_devices")
        .select("id")
        .is("revoked_at", null)
        .order("last_seen_at", { ascending: false, nullsFirst: false })
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();
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