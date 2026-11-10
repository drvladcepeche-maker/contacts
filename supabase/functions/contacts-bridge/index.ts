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

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(stableJson).join(",") + "]";
  if (value && typeof value === "object") {
    const object = value as Record<string, unknown>;
    return "{" + Object.keys(object).sort().map((key) => JSON.stringify(key) + ":" + stableJson(object[key])).join(",") + "}";
  }
  return JSON.stringify(value) ?? "null";
}

function contactCore(value: unknown): Record<string, unknown> {
  const data = value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
  // Compare the complete user-visible contact payload. Exclude only the local
  // record ID and account metadata: those necessarily differ across accounts.
  // Telegram fields and arbitrary typed Data values remain part of exact equality.
  return Object.fromEntries(Object.entries(data).filter(([key]) =>
    key !== "id" && key !== "accounts"
  ));
}

function mergeContactData(previousValue: unknown, incomingValue: unknown): Record<string, unknown> {
  const previous = previousValue && typeof previousValue === "object" && !Array.isArray(previousValue)
    ? previousValue as Record<string, unknown> : {};
  const incoming = incomingValue && typeof incomingValue === "object" && !Array.isArray(incomingValue)
    ? incomingValue as Record<string, unknown> : {};
  // Incoming Android fields are the latest source snapshot. Preserve server-only
  // metadata and any keys not emitted by older app builds.
  const merged: Record<string, unknown> = { ...previous, ...incoming };
  for (const [key, value] of Object.entries(previous)) {
    if (!(key in incoming)) merged[key] = value;
  }
  if (!Array.isArray(incoming.fields) && Array.isArray(previous.fields)) merged.fields = previous.fields;

  // Resolver-enriched Telegram IDs must survive until the Android-side write has
  // been applied. Keep all discovered usernames instead of replacing them with one.
  if (!String(incoming.telegram_id ?? "").trim() && String(previous.telegram_id ?? "").trim()) {
    merged.telegram_id = previous.telegram_id;
  }
  if (!String(incoming.telegram ?? "").trim() && String(previous.telegram ?? "").trim()) {
    merged.telegram = previous.telegram;
  }
  const names = [...new Set([
    ...String(previous.telegram_username ?? "").split(","),
    ...String(incoming.telegram_username ?? "").split(","),
  ].map(value => value.trim().replace(/^@/, "")).filter(value => value && value.toUpperCase() !== "СКРЫТ"))];
  if (names.length) merged.telegram_username = names.join(", ");
  else if (
    String(incoming.telegram_username ?? "").toUpperCase() === "СКРЫТ" ||
    String(previous.telegram_username ?? "").toUpperCase() === "СКРЫТ"
  ) {
    merged.telegram_username = "Скрыт";
  }
  for (const [key, value] of Object.entries(previous)) {
    if (key.startsWith("telegram_match_")) merged[key] = value;
  }
  return merged;
}

function phoneCandidates(data: Record<string, unknown>): string[] {
  const candidates = new Set<string>();
  const add = (value: unknown) => {
    if (typeof value !== "string") return;
    // Only international-format numbers are safe for automated Telegram lookup.
    // Unprefixed numeric strings remain searchable but are not guessed into E.164.
    const matches = value.match(/\+\s*\d[\d\s().-]{5,}\d/g) ?? [];
    for (const match of matches) {
      const digits = match.replace(/\D/g, "");
      if (digits.length >= 8 && digits.length <= 15) candidates.add("+" + digits);
    }
  };
  if (Array.isArray(data.phones)) data.phones.forEach(add);
  // Inspect all captured typed fields, regardless of their Android MIME type.
  // This finds phone numbers accidentally entered in Email, Website, Notes, etc.
  if (Array.isArray(data.fields)) {
    for (const field of data.fields) {
      if (!field || typeof field !== "object" || Array.isArray(field)) continue;
      const values = (field as Record<string, unknown>).values;
      if (values && typeof values === "object" && !Array.isArray(values)) {
        Object.values(values as Record<string, unknown>).forEach(add);
      }
    }
  }
  return Array.from(candidates);
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

  // A valid admin token can exchange itself for a dedicated device token.
  // The plaintext device token is returned exactly once; only its SHA-256 hash is stored.
  if (action === "pair_device") {
    if (role !== "assistant") return json({ error: "Administrative token required for device pairing" }, 403);
    const randomBytes = new Uint8Array(32);
    crypto.getRandomValues(randomBytes);
    const deviceToken = Array.from(randomBytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
    const deviceTokenHash = await sha256(deviceToken);
    const requestedName = String(body.device_name ?? "Android contacts app — refreshed sync").trim();
    const deviceName = (requestedName || "Android contacts app — refreshed sync").slice(0, 80);

    // Reuse the original snapshot-owning device so restored contacts stay
    // attached to their original device ID. Pairing rotates only its bearer hash.
    const { data: existingDevice, error: lookupError } = await supabase
      .from("contact_bridge_devices")
      .select("id, device_name, created_at")
      .eq("device_name", deviceName)
      .is("revoked_at", null)
      .order("last_seen_at", { ascending: false, nullsFirst: false })
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (lookupError) return json({ error: lookupError.message }, 500);

    let created = existingDevice;
    const reusedExistingDevice = Boolean(existingDevice);
    if (existingDevice) {
      const { data: updated, error: updateError } = await supabase
        .from("contact_bridge_devices")
        .update({ token_hash: deviceTokenHash })
        .eq("id", existingDevice.id)
        .is("revoked_at", null)
        .select("id, device_name, created_at")
        .maybeSingle();
      if (updateError) return json({ error: updateError.message }, 500);
      if (!updated) return json({ error: "Existing device is no longer available for pairing" }, 409);
      created = updated;
    } else {
      const { data: inserted, error: createError } = await supabase
        .from("contact_bridge_devices")
        .insert({ device_name: deviceName, token_hash: deviceTokenHash })
        .select("id, device_name, created_at")
        .single();
      if (createError) return json({ error: createError.message }, 500);
      created = inserted;
    }
    return json({
      paired: true,
      device: created,
      device_token: deviceToken,
      token_delivery: "shown_once",
      reused_existing_device: reusedExistingDevice,
    }, 201);
  }

  if (role === "device") {
    await supabase.from("contact_bridge_devices")
      .update({ last_seen_at: new Date().toISOString() }).eq("id", deviceId);

    if (action === "record_sync_manifest") {
      const syncId = String(body.sync_id ?? "").trim();
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(syncId)) {
        return json({ error: "sync_id must be a UUID" }, 400);
      }
      const rawIds = Array.isArray(body.local_contact_ids) ? body.local_contact_ids : null;
      if (!rawIds) return json({ error: "local_contact_ids must be an array" }, 400);
      if (rawIds.length > 25000) return json({ error: "Sync manifest exceeds the 25000 contact safety limit" }, 413);

      const normalizedIds: string[] = [];
      for (const value of rawIds) {
        if (typeof value !== "string" || !/^[1-9]\d*$/.test(value.trim())) {
          return json({ error: "local_contact_ids must contain positive Android contact IDs as strings" }, 400);
        }
        normalizedIds.push(value.trim());
      }
      const localContactIds = Array.from(new Set(normalizedIds));
      const rawSummary = body.sync_summary && typeof body.sync_summary === "object" && !Array.isArray(body.sync_summary)
        ? body.sync_summary as Record<string, unknown>
        : {};
      const safeCount = (value: unknown, fallback: number) => {
        const parsed = Number(value);
        return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
      };
      const syncSummary = {
        attempted: safeCount(rawSummary.attempted, localContactIds.length),
        unique_contacts: safeCount(rawSummary.unique_contacts, localContactIds.length),
        inserted: safeCount(rawSummary.inserted, 0),
        updated_existing: safeCount(rawSummary.updated_existing, 0),
        skipped_existing: safeCount(rawSummary.skipped_existing, 0),
        conflicts: safeCount(rawSummary.conflicts, 0),
        duplicates: safeCount(rawSummary.duplicates, 0),
      };
      const rawConflictDetails = Array.isArray(body.conflict_details) ? body.conflict_details : [];
      if (rawConflictDetails.length > 25000) {
        return json({ error: "Conflict report exceeds the 25000 item safety limit" }, 413);
      }
      const conflictDetails: Array<{ id: string; fields: string[] }> = [];
      for (const item of rawConflictDetails) {
        if (!item || typeof item !== "object" || Array.isArray(item)) {
          return json({ error: "Each conflict detail must be an object" }, 400);
        }
        const detail = item as Record<string, unknown>;
        const id = typeof detail.id === "string" ? detail.id.trim() : "";
        if (!/^[1-9]\d*$/.test(id)) {
          return json({ error: "Conflict IDs must be positive Android contact IDs" }, 400);
        }
        if (!Array.isArray(detail.fields) || detail.fields.length > 64 ||
            detail.fields.some((field) => typeof field !== "string" || !field.trim())) {
          return json({ error: "Conflict fields must be an array of non-empty strings (max 64)" }, 400);
        }
        const fields = Array.from(new Set((detail.fields as string[])
          .map((field) => field.trim().slice(0, 100)))).sort();
        conflictDetails.push({ id, fields });
      }

      const { data: manifest, error: manifestError } = await supabase
        .from("contact_bridge_sync_manifests")
        .upsert({
          device_id: deviceId,
          sync_id: syncId,
          local_contact_ids: localContactIds,
          contact_count: localContactIds.length,
          sync_summary: syncSummary,
          conflict_details: conflictDetails,
        }, { onConflict: "device_id,sync_id" })
        .select("sync_id, contact_count, created_at")
        .single();
      if (manifestError) return json({ error: manifestError.message }, 500);
      return json({
        recorded: true,
        sync_id: manifest.sync_id,
        contact_count: manifest.contact_count,
        created_at: manifest.created_at,
      });
    }

    if (action === "upload_diagnostics") {
      const entries = Array.isArray(body.entries) ? body.entries.slice(0, 50) : [];
      if (!entries.length) return json({ received: 0, inserted: 0, rejected: 0 });
      const allowedLevels = new Set(["INFO", "WARN", "ERROR", "FATAL"]);
      const rows = entries.flatMap((entry: any) => {
        if (!entry || typeof entry !== "object") return [];
        const eventId = String(entry.event_id ?? "").trim();
        const occurredAt = new Date(String(entry.occurred_at ?? ""));
        const level = String(entry.level ?? "ERROR").toUpperCase();
        if (!eventId || eventId.length > 80 || Number.isNaN(occurredAt.getTime()) || !allowedLevels.has(level)) return [];
        return [{
          device_id: deviceId,
          event_id: eventId,
          occurred_at: occurredAt.toISOString(),
          level,
          tag: String(entry.tag ?? "Unknown").slice(0, 120),
          message: String(entry.message ?? "").slice(0, 2000),
          stack_trace: entry.stack_trace == null ? null : String(entry.stack_trace).slice(0, 12000),
          app_version: entry.app_version == null ? null : String(entry.app_version).slice(0, 40),
        }];
      });
      if (!rows.length) return json({ received: entries.length, inserted: 0, rejected: entries.length });
      const { data, error } = await supabase.from("contact_bridge_diagnostics")
        .upsert(rows, { onConflict: "device_id,event_id", ignoreDuplicates: true })
        .select("event_id");
      if (error) return json({ error: "Diagnostics upload failed: " + error.message }, 500);
      return json({
        received: entries.length,
        inserted: data?.length ?? 0,
        rejected: entries.length - rows.length,
      });
    }

    if (action === "sync_contacts") {
      const contacts = Array.isArray(body.contacts) ? body.contacts : [];
      if (contacts.length > 1000) return json({ error: "Maximum 1000 contacts per sync batch" }, 413);
      // Android Contacts._ID is the stable per-contact key for this device.
      // Existing IDs are merged with the incoming Android snapshot; report divergent fields.
      const byLocalId = new Map<string, Record<string, unknown>>();
      const conflicts: Array<{ id: string; fields: string[] }> = [];
      for (const item of contacts as any[]) {
        const localId = String(item?.id ?? "").trim();
        if (!localId) continue;
        const priorInBatch = byLocalId.get(localId);
        if (priorInBatch) {
          if (stableJson(contactCore(priorInBatch.contact_data)) !== stableJson(contactCore(item))) {
            conflicts.push({ id: localId, fields: ["duplicate_id_in_batch"] });
          }
          continue;
        }
        byLocalId.set(localId, {
          device_id: deviceId,
          local_contact_id: localId,
          contact_data: item,
          synced_at: new Date().toISOString(),
        });
      }
      const rows = Array.from(byLocalId.values());
      let insertedCount = 0;
      let updatedExistingCount = 0;
      let skippedCount = 0;
      if (rows.length) {
        const ids = rows.map((row) => String(row.local_contact_id));
        const { data: previousRows, error: previousError } = await supabase
          .from("contact_bridge_snapshots")
          .select("local_contact_id, contact_data")
          .eq("device_id", deviceId)
          .in("local_contact_id", ids);
        if (previousError) return json({ error: previousError.message }, 500);
        const previousById = new Map((previousRows ?? []).map((row: any) => [String(row.local_contact_id), row.contact_data]));
        const newRows: typeof rows = [];
        const updateRows: typeof rows = [];
        for (const row of rows) {
          const id = String(row.local_contact_id);
          if (previousById.has(id)) {
            const previous = previousById.get(id);
            const merged = mergeContactData(previous, row.contact_data);
            if (stableJson(contactCore(previous)) !== stableJson(contactCore(merged))) {
              const left = contactCore(previous);
              const right = contactCore(merged);
              const fields = Array.from(new Set([...Object.keys(left), ...Object.keys(right)]))
                .filter((key) => stableJson(left[key]) !== stableJson(right[key]))
                .sort();
              conflicts.push({ id, fields });
            }
            if (stableJson(previous) !== stableJson(merged)) {
              updateRows.push({ ...row, contact_data: merged });
            } else {
              skippedCount++;
            }
          } else {
            newRows.push(row);
          }
        }
        if (updateRows.length) {
          const { data: updated, error: updateError } = await supabase
            .from("contact_bridge_snapshots")
            .upsert(updateRows, { onConflict: "device_id,local_contact_id" })
            .select("local_contact_id");
          if (updateError) return json({ error: updateError.message }, 500);
          updatedExistingCount = updated?.length ?? 0;
        }
        if (newRows.length) {
          const { data: inserted, error: insertError } = await supabase
            .from("contact_bridge_snapshots")
            .upsert(newRows, { onConflict: "device_id,local_contact_id", ignoreDuplicates: true })
            .select("local_contact_id");
          if (insertError?.code === "23505") {
        return json({ error: "A Telegram matching job is already queued or running" }, 409);
      }
      if (insertError) return json({ error: insertError.message }, 500);
          insertedCount = inserted?.length ?? 0;
          skippedCount += newRows.length - insertedCount;
        }
      }
      return json({
        synced: insertedCount + updatedExistingCount,
        inserted: insertedCount,
        updated_existing: updatedExistingCount,
        skipped_existing: skippedCount,
        unique_contacts: rows.length,
        duplicate_ids_in_batch_removed: contacts.length - rows.length,
        conflicts,
      });
    }

    if (action === "prune_contacts") {
      const rawKeepIds = Array.isArray(body.keep_ids) ? body.keep_ids : null;
      if (!rawKeepIds) return json({ error: "keep_ids must be an array" }, 400);
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
        const phones = phoneCandidates(data);
        for (const phone of phones) {
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