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
  { name: "contacts_request_change", description: "Queue a contact create/update/delete/merge request for Android. The phone displays a confirmation prompt before any write is applied. Delete and merge additionally require confirmed_by_user=true in this tool call.", inputSchema: { type: "object", properties: { device_id: { type: "string" }, operation: { type: "string", enum: ["create", "update", "delete", "merge"] }, id: { type: "integer", description: "Local Android contact ID for update/delete" }, name: { type: "string" }, phone: { type: "string" }, ids: { type: "array", items: { type: "integer" }, minItems: 2 }, confirmed_by_user: { type: "boolean", description: "Must be true for delete or merge when the user explicitly authorized the operation." } }, required: ["device_id", "operation"], additionalProperties: false } },
  { name: "contacts_command_status", description: "Check whether a queued Android contact command has completed.", inputSchema: { type: "object", properties: { command_id: { type: "string" } }, required: ["command_id"], additionalProperties: false } },
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

    if (name === "contacts_list" || name === "contacts_search") {
      const deviceId = String(args.device_id ?? "");
      if (!deviceId) return response(id, toolResult({ error: "device_id required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      const offset = Math.min(Math.max(Number(args.offset ?? 0), 0), 10000);
      const limit = Math.min(Math.max(Number(args.limit ?? 500), 1), 500);
      let rows: any[] = [];
      if (name === "contacts_list") {
        const { data: page, error } = await db.from("contact_bridge_snapshots")
          .select("local_contact_id, contact_data, synced_at, device_id")
          .eq("device_id", deviceId)
          .order("synced_at", { ascending: false })
          .range(offset, offset + limit - 1);
        if (error) throw error;
        rows = page ?? [];
      } else {
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
      }
      if (name === "contacts_search") {
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

    if (name === "contacts_request_change") {
      const deviceId = String(args.device_id ?? "");
      const operation = String(args.operation ?? "");
      const actionMap: Record<string, string> = { create: "upsert_contact", update: "upsert_contact", delete: "delete_contact", merge: "merge_contacts" };
      const action = actionMap[operation];
      if (!deviceId || !action) return response(id, toolResult({ error: "Valid device_id and operation required" }, true));
      if (!canAccessDevice(deviceId)) return denied(id);
      if (["delete", "merge"].includes(operation) && args.confirmed_by_user !== true) {
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
      } else {
        if (!Array.isArray(args.ids) || args.ids.length < 2 || args.ids.some((v: unknown) => !Number.isInteger(v))) return response(id, toolResult({ error: "ids must contain at least two local contact IDs" }, true));
        payload.ids = args.ids;
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
    return response(id, toolResult({ error: e instanceof Error ? e.message : String(e) }, true));
  }
});