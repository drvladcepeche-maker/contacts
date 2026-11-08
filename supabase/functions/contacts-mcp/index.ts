import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, mcp-session-id, mcp-protocol-version",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Expose-Headers": "WWW-Authenticate",
  "Content-Type": "application/json",
};

const tools = [
  { name: "contacts_status", description: "Check bridge status, paired Android devices, last sync and cached contact count.", inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "contacts_list", description: "List cached contacts from one paired Android device. Optional account_name/account_type filters return only matching account memberships; the returned accounts array is narrowed to the selected source.", inputSchema: { type: "object", properties: { device_id: { type: "string", description: "Device ID returned by contacts_status" }, account_name: { type: "string", description: "Exact account name or label, case-insensitive" }, account_type: { type: "string", description: "Exact Android account type, case-insensitive" }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000, description: "Zero-based pagination offset; request successive pages." } }, required: ["device_id"], additionalProperties: false } },
  { name: "contacts_search", description: "Search cached contacts by name, phone, or email-like fields, optionally restricted to one account source.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, query: { type: "string", minLength: 1 }, account_name: { type: "string", description: "Exact account name or label, case-insensitive" }, account_type: { type: "string", description: "Exact Android account type, case-insensitive" }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000, description: "Zero-based pagination offset; request successive pages." } }, required: ["device_id", "query"], additionalProperties: false } },
  { name: "contacts_list_v2", description: "List cached contacts filtered by account_name/account_type. Use this refreshed tool when filtering contacts by Android account.", inputSchema: { type: "object", properties: { device_id: { type: "string", description: "Device ID returned by contacts_status" }, account_name: { type: "string", description: "Exact account name or label, case-insensitive" }, account_type: { type: "string", description: "Exact Android account type, case-insensitive" }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000, description: "Zero-based pagination offset; request successive pages." } }, required: ["device_id"], additionalProperties: false } },
  { name: "contacts_search_v2", description: "Search cached contacts by name, phone, or email, optionally restricted to one Android account source.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, query: { type: "string", minLength: 1 }, account_name: { type: "string", description: "Exact account name or label, case-insensitive" }, account_type: { type: "string", description: "Exact Android account type, case-insensitive" }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000, description: "Zero-based pagination offset; request successive pages." } }, required: ["device_id", "query"], additionalProperties: false } },
  { name: "contacts_request_change", description: "Queue a contact create/update/delete/merge/move request for Android. The phone displays a confirmation prompt before any write is applied. Delete, merge and move additionally require confirmed_by_user=true in this tool call.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, operation: { type: "string", enum: ["create", "update", "delete", "merge", "move"] }, id: { type: "integer", description: "Local Android contact ID for update/delete" }, name: { type: "string" }, phone: { type: "string" }, ids: { type: "array", items: { type: "integer" }, minItems: 2 }, source_account_name: { type: "string" }, source_account_type: { type: "string" }, target_account_name: { type: "string" }, target_account_type: { type: "string" }, confirmed_by_user: { type: "boolean", description: "Must be true for delete or merge when the user explicitly authorized the operation." } }, required: ["device_id", "operation"], additionalProperties: false } },
  { name: "contacts_command_status", description: "Check whether a queued Android contact command has completed.", inputSchema: { type: "object", properties: { command_id: { type: "string" } }, required: ["command_id"], additionalProperties: false } },
  { name: "contacts_identifier_report", description: "Read classified identifiers and likely misplaced values for one paired device. This is evidence only; it never edits the Android contact.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, detected_type: { type: "string" }, misfiled_only: { type: "boolean" }, resolution_status: { type: "string", enum: ["pending","accepted","rejected","corrected"] }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000 } }, required: ["device_id"], additionalProperties: false } },
  { name: "contacts_identifier_review", description: "Record a review decision for one identifier observation. This updates only the evidence ledger, never the source contact.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, observation_id: { type: "string" }, resolution_status: { type: "string", enum: ["accepted","rejected","corrected"] }, corrected_value: { type: "string" }, review_note: { type: "string" } }, required: ["device_id","observation_id","resolution_status"], additionalProperties: false } },
  { name: "contacts_osint_profiles", description: "List Sherlock public-profile checks and their evidence for one paired device. CLAIMED is a candidate profile, not identity proof.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, username: { type: "string" }, result_status: { type: "string", enum: ["CLAIMED","AVAILABLE","UNKNOWN","ILLEGAL","WAF","ERROR"] }, review_status: { type: "string", enum: ["pending","confirmed","rejected"] }, limit: { type: "integer", minimum: 1, maximum: 500 }, offset: { type: "integer", minimum: 0, maximum: 10000 } }, required: ["device_id"], additionalProperties: false } },
  { name: "contacts_osint_review", description: "Record whether a Sherlock profile candidate was confirmed or rejected for one paired device. Does not modify contact data.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, profile_id: { type: "string" }, review_status: { type: "string", enum: ["confirmed","rejected"] }, review_note: { type: "string" } }, required: ["device_id","profile_id","review_status"], additionalProperties: false } },
];

function response(id: unknown, result: unknown, status = 200) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), { status, headers: CORS });
}
function errorResponse(id: unknown, code: number, message: string, status = 400) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }), { status, headers: CORS });
}
function toolResult(value: unknown, isError = false) {
  return { content: [{ type: "text", text: JSON.stringify(value) }], isError };
}
async function sha256(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  const requestUrl = new URL(req.url);
  const projectUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const resourceUrl = projectUrl ? `${projectUrl}/functions/v1/contacts-mcp` : "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-mcp";
  const metadataUrl = `${resourceUrl}/oauth-protected-resource`;
  const metadataSuffix = "/oauth-protected-resource";
  if (requestUrl.pathname.endsWith(metadataSuffix) && (req.method === "GET" || req.method === "OPTIONS")) {

    if (!projectUrl) return new Response(JSON.stringify({ error: "Server configuration missing" }), { status: 500, headers: CORS });
    return new Response(JSON.stringify({
      resource: resourceUrl,
      authorization_servers: [`${projectUrl}/auth/v1`],
      bearer_methods_supported: ["header"]
    }), { status: 200, headers: CORS });
  }
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return new Response("MCP Streamable HTTP endpoint; POST JSON-RPC requests.", { status: 405, headers: CORS });
  const bearer = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "").trim();
  const unauthorized = (message: string) => new Response(message, { status: 401, headers: { ...CORS, "WWW-Authenticate": `Bearer resource_metadata="${metadataUrl}"` } });
  if (!bearer) return unauthorized("Bearer token required");

  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) return new Response("Server configuration missing", { status: 500, headers: CORS });
  const db = createClient(url, key, { auth: { autoRefreshToken: false, persistSession: false } });
  const { data: valid } = await db.from("contact_bridge_admin_tokens").select("id")
    .eq("token_hash", await sha256(bearer)).is("revoked_at", null).maybeSingle();
  const isAdminToken = Boolean(valid);
  let ownedDeviceIds: string[] = [];
  if (!isAdminToken) {
    const { data: authData, error: authError } = await db.auth.getUser(bearer);
    if (authError || !authData?.user) return unauthorized("Invalid bearer token");
    const { data: mappings, error: mappingError } = await db.from("contact_bridge_user_devices")
      .select("device_id").eq("user_id", authData.user.id);
    if (mappingError) return new Response("Authorization lookup failed", { status: 500, headers: CORS });
    ownedDeviceIds = (mappings ?? []).map((row: any) => String(row.device_id));
  }
  const canAccessDevice = (deviceId: string) => isAdminToken || ownedDeviceIds.includes(deviceId);
  const denied = (id: unknown) => response(id, toolResult({ error: "Device is not paired to this account" }, true));

  let reqBody: any;
  try { reqBody = await req.json(); } catch { return errorResponse(null, -32700, "Parse error"); }
  const id = reqBody.id ?? null;
  const method = String(reqBody.method ?? "");
  const params = reqBody.params ?? {};

  if (method === "notifications/initialized" || method === "notifications/cancelled") {
    return new Response(null, { status: 202, headers: CORS });
  }
  if (method === "initialize") {
    return response(id, {
      protocolVersion: params.protocolVersion ?? "2025-03-26",
      capabilities: { tools: {} },
      serverInfo: { name: "moi-kontakty", version: "1.0.0" },
      instructions: "Android contact data is private. Cached results may be stale until the Android app is open and synced. Contact writes are queued; the phone must confirm each write.",
    });
  }
  if (method === "ping") return response(id, {});
  if (method === "tools/list") return response(id, { tools });
  if (method !== "tools/call") return errorResponse(id, -32601, "Method not found", 404);

  const name = String(params.name ?? "");
  const args = params.arguments ?? {};
  try {
    if (name === "contacts_status") {
      let devicesQuery = db.from("contact_bridge_devices")
        .select("id, device_name, created_at, last_seen_at, revoked_at").is("revoked_at", null);
      if (!isAdminToken) devicesQuery = ownedDeviceIds.length ? devicesQuery.in("id", ownedDeviceIds) : devicesQuery.eq("id", "00000000-0000-0000-0000-000000000000");
      const { data: devices, error } = await devicesQuery;
      if (error) throw error;
      let countQuery = db.from("contact_bridge_snapshots").select("id", { count: "exact", head: true });
      if (!isAdminToken) countQuery = ownedDeviceIds.length ? countQuery.in("device_id", ownedDeviceIds) : countQuery.eq("device_id", "00000000-0000-0000-0000-000000000000");
      const { count, error: countError } = await countQuery;
      if (countError) throw countError;
      return response(id, toolResult({ bridge: "online", devices: devices ?? [], cached_contact_count: count ?? 0 }));
    }

    if (name === "contacts_list" || name === "contacts_search" || name === "contacts_list_v2" || name === "contacts_search_v2") {
      const deviceId = String(args.device_id ?? "");
      if (!deviceId) return response(id, toolResult({ error: "device_id required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      const offset = Math.min(Math.max(Number(args.offset ?? 0), 0), 10000);
      const limit = Math.min(Math.max(Number(args.limit ?? 500), 1), 500);
      let rows: any[] = [];
      // Scan before filtering so account-scoped pagination covers the full device cache,
      // rather than only filtering the requested page.
      for (let scanOffset = 0; scanOffset < 10000; scanOffset += 500) {
        const { data: page, error } = await db.from("contact_bridge_snapshots")
          .select("local_contact_id, contact_data, synced_at, device_id")
          .eq("device_id", deviceId)
          .order("synced_at", { ascending: false })
          .range(scanOffset, scanOffset + 499);
        if (error) throw error;
        const batch = page ?? [];
        rows.push(...batch);
        if (batch.length < 500) break;
      }
      if (name === "contacts_search" || name === "contacts_search_v2") {
        const q = String(args.query ?? "").trim().toLocaleLowerCase();
        if (!q) return response(id, toolResult({ error: "query required" }, true));
        rows = rows.filter((r: any) => JSON.stringify(r.contact_data).toLocaleLowerCase().includes(q));
      }
      const accountName = String(args.account_name ?? "").trim().toLocaleLowerCase();
      const accountType = String(args.account_type ?? "").trim().toLocaleLowerCase();
      const accountFilterActive = Boolean(accountName || accountType);
      if (accountFilterActive) {
        rows = rows.filter((r: any) => {
          const accounts = Array.isArray(r.contact_data?.accounts) ? r.contact_data.accounts : [];
          return accounts.some((a: any) => {
            const name = String(a?.account_name ?? "").trim().toLocaleLowerCase();
            const label = String(a?.label ?? "").trim().toLocaleLowerCase();
            const type = String(a?.account_type ?? "").trim().toLocaleLowerCase();
            return (!accountName || name === accountName || label === accountName) &&
              (!accountType || type === accountType);
          });
        }).map((r: any) => {
          const accounts = Array.isArray(r.contact_data?.accounts) ? r.contact_data.accounts : [];
          const matching = accounts.filter((a: any) => {
            const name = String(a?.account_name ?? "").trim().toLocaleLowerCase();
            const label = String(a?.label ?? "").trim().toLocaleLowerCase();
            const type = String(a?.account_type ?? "").trim().toLocaleLowerCase();
            return (!accountName || name === accountName || label === accountName) &&
              (!accountType || type === accountType);
          });
          return { ...r, contact_data: { ...r.contact_data, accounts: matching } };
        });
      }
      const pageRows = rows.slice(offset, offset + limit);
      return response(id, toolResult({ contacts: pageRows.map((r: any) => ({ ...r.contact_data, local_contact_id: r.local_contact_id, synced_at: r.synced_at })), returned: pageRows.length, offset, limit, has_more: offset + pageRows.length < rows.length, total_matching: rows.length, account_filter: accountFilterActive ? { account_name: accountName || null, account_type: accountType || null } : null }));
    }


    if (name === "contacts_identifier_report") {
      const deviceId = String(args.device_id ?? "");
      if (!deviceId) return response(id, toolResult({ error: "device_id required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      const offset = Math.min(Math.max(Number(args.offset ?? 0), 0), 10000);
      const limit = Math.min(Math.max(Number(args.limit ?? 100), 1), 500);
      let query = db.from("contact_identifier_observations")
        .select("id,local_contact_id,source_path,source_mimetype,source_value,detected_type,expected_type,normalized_value,confidence,is_misfiled,resolution_status,corrected_value,reviewed_at,review_note,first_seen_at,last_seen_at", { count: "exact" })
        .eq("device_id", deviceId);
      if (args.detected_type) query = query.eq("detected_type", String(args.detected_type));
      if (args.misfiled_only === true) query = query.eq("is_misfiled", true);
      if (args.resolution_status) query = query.eq("resolution_status", String(args.resolution_status));
      const { data, count, error } = await query.order("last_seen_at", { ascending: false }).range(offset, offset + limit - 1);
      if (error) throw error;
      const [misfiled, pending] = await Promise.all([
        db.from("contact_identifier_observations").select("id", { count: "exact", head: true }).eq("device_id", deviceId).eq("is_misfiled", true),
        db.from("contact_identifier_observations").select("id", { count: "exact", head: true }).eq("device_id", deviceId).eq("resolution_status", "pending")
      ]);
      if (misfiled.error) throw misfiled.error;
      if (pending.error) throw pending.error;
      return response(id, toolResult({ observations: data ?? [], total_matching: count ?? 0, total_misfiled: misfiled.count ?? 0, total_pending_review: pending.count ?? 0, offset, limit }));
    }

    if (name === "contacts_identifier_review") {
      const deviceId = String(args.device_id ?? "");
      const observationId = String(args.observation_id ?? "");
      const status = String(args.resolution_status ?? "");
      if (!deviceId || !observationId || !["accepted","rejected","corrected"].includes(status)) {
        return response(id, toolResult({ error: "device_id, observation_id and a valid resolution_status are required" }, true));
      }
      if (!canAccessDevice(deviceId)) return denied(id);
      const correctedValue = String(args.corrected_value ?? "");
      if (status === "corrected" && !correctedValue.trim()) {
        return response(id, toolResult({ error: "corrected_value is required when resolution_status is corrected" }, true));
      }
      const patch: Record<string, unknown> = {
        resolution_status: status, reviewed_at: new Date().toISOString(),
        review_note: String(args.review_note ?? "").slice(0, 1000) || null
      };
      if (status === "corrected") patch.corrected_value = correctedValue.trim();
      const { data, error } = await db.from("contact_identifier_observations")
        .update(patch).eq("id", observationId).eq("device_id", deviceId)
        .select("id,device_id,local_contact_id,source_path,detected_type,normalized_value,resolution_status,corrected_value,reviewed_at,review_note")
        .maybeSingle();
      if (error) throw error;
      if (!data) return response(id, toolResult({ error: "Observation not found" }, true));
      return response(id, toolResult({ updated: true, observation: data, note: "Only the review ledger was updated; the Android contact remains unchanged." }));
    }

    if (name === "contacts_osint_profiles") {
      const deviceId = String(args.device_id ?? "");
      if (!deviceId) return response(id, toolResult({ error: "device_id required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      const offset = Math.min(Math.max(Number(args.offset ?? 0), 0), 10000);
      const limit = Math.min(Math.max(Number(args.limit ?? 100), 1), 500);
      let query = db.from("contact_osint_profiles")
        .select("id,local_contact_id,source_path,normalized_username,platform,profile_url,result_status,http_status,response_time_s,checked_at,manifest_version,evidence,review_status,reviewed_at,review_note", { count: "exact" })
        .eq("device_id", deviceId);
      if (args.username) query = query.ilike("normalized_username", String(args.username));
      if (args.result_status) query = query.eq("result_status", String(args.result_status));
      if (args.review_status) query = query.eq("review_status", String(args.review_status));
      const { data, count, error } = await query.order("checked_at", { ascending: false }).range(offset, offset + limit - 1);
      if (error) throw error;
      return response(id, toolResult({ profiles: data ?? [], total_matching: count ?? 0, offset, limit, note: "A CLAIMED result indicates a likely public profile URL only; it does not establish that the profile belongs to this contact." }));
    }

    if (name === "contacts_osint_review") {
      const deviceId = String(args.device_id ?? "");
      const profileId = String(args.profile_id ?? "");
      const status = String(args.review_status ?? "");
      if (!deviceId || !profileId || !["confirmed","rejected"].includes(status)) {
        return response(id, toolResult({ error: "device_id, profile_id and a valid review_status are required" }, true));
      }
      if (!canAccessDevice(deviceId)) return denied(id);
      const { data, error } = await db.from("contact_osint_profiles")
        .update({ review_status: status, reviewed_at: new Date().toISOString(), review_note: String(args.review_note ?? "").slice(0, 1000) || null })
        .eq("id", profileId).eq("device_id", deviceId)
        .select("id,normalized_username,platform,profile_url,result_status,review_status,reviewed_at,review_note")
        .maybeSingle();
      if (error) throw error;
      if (!data) return response(id, toolResult({ error: "Profile result not found" }, true));
      return response(id, toolResult({ updated: true, profile: data, note: "Only the Sherlock evidence review was updated; no contact fields were changed." }));
    }

    if (name === "contacts_request_change") {
      const deviceId = String(args.device_id ?? "");
      const operation = String(args.operation ?? "");
      const actionMap: Record<string, string> = { create: "upsert_contact", update: "upsert_contact", delete: "delete_contact", merge: "merge_contacts", move: "move_contact" };
      const action = actionMap[operation];
      if (!deviceId || !action) return response(id, toolResult({ error: "Valid device_id and operation required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      if (["delete", "merge", "move"].includes(operation) && args.confirmed_by_user !== true) {
        return response(id, toolResult({ error: "Explicit user authorization is required for delete/merge. Set confirmed_by_user=true only when the user has authorized it." }, true));
      }
      const payload: Record<string, unknown> = {};
      if (operation === "create" || operation === "update") {
        if (!String(args.name ?? "").trim()) return response(id, toolResult({ error: "name required" }, true));
        payload.name = String(args.name).trim();
        payload.phone = String(args.phone ?? "").trim();
        if (operation === "update") {
          if (!Number.isInteger(args.id) || Number(args.id) <= 0) return response(id, toolResult({ error: "valid local contact id required for update" }, true));
          payload.id = Number(args.id);
        }
      } else if (operation === "delete") {
        if (!Number.isInteger(args.id) || Number(args.id) <= 0) return response(id, toolResult({ error: "valid local contact id required for delete" }, true));
        payload.id = Number(args.id);
      } else if (operation === "merge") {
        if (!Array.isArray(args.ids) || args.ids.length < 2 || args.ids.some((v: unknown) => !Number.isInteger(v))) return response(id, toolResult({ error: "ids must contain at least two local contact IDs" }, true));
        payload.ids = args.ids;
      } else if (operation === "move") {
        if (!Number.isInteger(args.id) || Number(args.id) <= 0) return response(id, toolResult({ error: "valid local contact id required for move" }, true));
        const sourceName = String(args.source_account_name ?? "").trim();
        const sourceType = String(args.source_account_type ?? "").trim();
        const targetName = String(args.target_account_name ?? "").trim();
        const targetType = String(args.target_account_type ?? "").trim();
        if (!sourceName || !sourceType || !targetName || !targetType) {
          return response(id, toolResult({ error: "move requires source_account_name, source_account_type, target_account_name and target_account_type" }, true));
        }
        payload.id = Number(args.id);
        payload.source_account_name = sourceName;
        payload.source_account_type = sourceType;
        payload.target_account_name = targetName;
        payload.target_account_type = targetType;
      }
      const { data, error } = await db.from("contact_bridge_commands")
        .insert({ device_id: deviceId, action, payload })
        .select("id, device_id, action, status, created_at").single();
      if (error) throw error;
      return response(id, toolResult({ queued: true, command: data, note: "Keep the Android app open. The phone will request confirmation before applying changes." }));
    }

    if (name === "contacts_command_status") {
      const commandId = String(args.command_id ?? "");
      if (!commandId) return response(id, toolResult({ error: "command_id required" }, true));
      const { data, error } = await db.from("contact_bridge_commands")
        .select("id, device_id, action, status, result, error_message, created_at, completed_at")
        .eq("id", commandId).maybeSingle();
      if (error) throw error;
      if (!data || !canAccessDevice(String(data.device_id))) return response(id, toolResult({ error: "Command not found" }, true));
      return response(id, toolResult({ command: data }));
    }
    return response(id, toolResult({ error: "Unknown tool" }, true));
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    console.error("[contacts-mcp] tool call failed", {
      tool: name,
      requestId: id,
      message: message.slice(0, 1000),
      timestamp: new Date().toISOString(),
    });
    return response(id, toolResult({ error: message }, true));
  }
});