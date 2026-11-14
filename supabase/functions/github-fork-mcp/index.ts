import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { isAllowedGithubForkUser } from "./auth.ts";

const TARGET_OWNER = "drvladcepeche-maker";
const SOURCES: Record<string, { owner: string; repo: string }> = {
  "PhoneInfoga": { owner: "sundowndev", repo: "phoneinfoga" },
  "SearchPhone": { owner: "HackUnderway", repo: "SearchPhone" },
  "X-osint": { owner: "TermuxHackz", repo: "X-osint" },
  "ignorant": { owner: "megadose", repo: "ignorant" },
  "recon-ng": { owner: "lanmaster53", repo: "recon-ng" },
  "OSINT-Framework": { owner: "lockfale", repo: "OSINT-Framework" },
  "awesome-osint": { owner: "jivoi", repo: "awesome-osint" },
  "openalpr": { owner: "openalpr", repo: "openalpr" },
};
const API = "https://api.github.com";
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, mcp-session-id, mcp-protocol-version",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Expose-Headers": "WWW-Authenticate",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};
const tools = [
  { name: "github_fork_status", description: "Read-only: verify whether the correct upstream fork exists under drvladcepeche-maker. Only eight verified upstreams are enabled; russian-license-plates is excluded until its exact source is confirmed.", inputSchema: { type: "object", properties: { repo: { type: "string", enum: Object.keys(SOURCES) } }, required: ["repo"], additionalProperties: false } },
  { name: "github_create_fork", description: "Write operation: create a fork of the verified upstream under drvladcepeche-maker. Checks target and upstream first.", inputSchema: { type: "object", properties: { repo: { type: "string", enum: Object.keys(SOURCES) } }, required: ["repo"], additionalProperties: false } },
];
function rpc(id: unknown, result: unknown, status = 200) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), { status, headers: CORS });
}
function rpcError(id: unknown, code: number, message: string, status = 400) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }), { status, headers: CORS });
}
function toolResult(value: unknown, isError = false) {
  return { content: [{ type: "text", text: JSON.stringify(value) }], isError };
}
function textReply(message: string, status: number, extra: Record<string, string> = {}) {
  return new Response(message, { status, headers: { ...CORS, ...extra } });
}
async function github(path: string, token: string, method = "GET") {
  const response = await fetch(API + path, {
    method,
    headers: { "Accept": "application/vnd.github+json", "Authorization": `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "github-fork-mcp",
      ...(method === "POST" ? { "Content-Type": "application/json" } : {}) },
    ...(method === "POST" ? { body: "{}" } : {}),
  });
  const raw = await response.text();
  let data: Record<string, unknown> = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { /* ignore */ }
  return { status: response.status, data };
}
function sourceUrl(source: { owner: string; repo: string }) { return `https://github.com/${source.owner}/${source.repo}`; }
async function inspectFork(source: { owner: string; repo: string }, token: string) {
  const result = await github(`/repos/${TARGET_OWNER}/${source.repo}`, token);
  if (result.status === 404) return { exists: false, source: sourceUrl(source) };
  if (result.status !== 200) return { error: "GitHub API request failed", github_status: result.status };
  const parent = result.data.parent as Record<string, unknown> | undefined;
  const matches = Boolean(result.data.fork) &&
    String(parent?.full_name ?? "").toLowerCase() === `${source.owner}/${source.repo}`.toLowerCase();
  return { exists: true, correct_fork: matches, full_name: result.data.full_name,
    html_url: result.data.html_url, is_fork: result.data.fork, source: parent?.full_name ?? null,
    default_branch: result.data.default_branch };
}
Deno.serve(async (req: Request) => {
  const requestUrl = new URL(req.url);
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const resourceUrl = `${supabaseUrl}/functions/v1/github-fork-mcp`;
  const metadataUrl = `${resourceUrl}/oauth-protected-resource`;
  if (requestUrl.pathname.endsWith("/oauth-protected-resource") && (req.method === "GET" || req.method === "OPTIONS")) {
    return new Response(JSON.stringify({ resource: resourceUrl, authorization_servers: [`${supabaseUrl}/auth/v1`], bearer_methods_supported: ["header"] }), { status: 200, headers: CORS });
  }
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return textReply("MCP Streamable HTTP endpoint. Send JSON-RPC POST requests.", 405);
  const bearer = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "").trim() ?? "";
  const unauthorized = (message: string) => textReply(message, 401, { "WWW-Authenticate": `Bearer resource_metadata="${metadataUrl}"` });
  if (!bearer) return unauthorized("Supabase access token required.");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const githubToken = Deno.env.get("GITHUB_TOKEN");
  if (!supabaseUrl || !serviceKey || !githubToken) return textReply("Server configuration missing.", 503);
  const allowedUserId = Deno.env.get("GITHUB_FORK_ADMIN_USER_ID")?.trim() ?? "";
  if (!allowedUserId) return textReply("GitHub fork administration is not provisioned.", 503);
  const db = createClient(supabaseUrl, serviceKey, { auth: { autoRefreshToken: false, persistSession: false } });
  const { data: authData, error: authError } = await db.auth.getUser(bearer);
  if (authError || !authData?.user) return unauthorized("Invalid Supabase access token.");
  if (!isAllowedGithubForkUser(authData.user.id, allowedUserId)) {
    return textReply("Forbidden: this account is not authorized to manage GitHub forks.", 403);
  }
  let body: any;
  try { body = await req.json(); } catch { return rpcError(null, -32700, "Parse error"); }
  const id = body?.id ?? null, method = String(body?.method ?? ""), params = body?.params ?? {};
  if (method === "notifications/initialized" || method === "notifications/cancelled") return new Response(null, { status: 202, headers: CORS });
  if (method === "initialize") return rpc(id, { protocolVersion: params.protocolVersion ?? "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "github-fork-mcp", version: "2.1.0" }, instructions: "Only the explicitly configured GitHub fork administrator can access this service. Only eight verified upstream repositories are enabled." });
  if (method === "ping") return rpc(id, {});
  if (method === "tools/list") return rpc(id, { tools });
  if (method !== "tools/call") return rpcError(id, -32601, "Method not found", 404);
  const name = String(params.name ?? ""), args = params.arguments ?? {}, key = typeof args.repo === "string" ? args.repo : "";
  const source = SOURCES[key];
  if (!source) return rpc(id, toolResult({ error: "Repository is not on the verified allowlist." }, true));
  try {
    if (name === "github_fork_status") {
      const status = await inspectFork(source, githubToken);
      return rpc(id, toolResult(status, Boolean((status as any).error)));
    }
    if (name === "github_create_fork") {
      const before = await inspectFork(source, githubToken);
      if ((before as any).error) return rpc(id, toolResult(before, true));
      if ((before as any).exists) {
        if ((before as any).correct_fork) return rpc(id, toolResult({ ok: true, already_exists: true, ...before }));
        return rpc(id, toolResult({ error: "Target repository name is occupied by a repository that is not the expected fork.", ...before }, true));
      }
      const upstream = await github(`/repos/${source.owner}/${source.repo}`, githubToken);
      if (upstream.status !== 200) return rpc(id, toolResult({ error: "Source repository could not be verified", github_status: upstream.status }, true));
      const created = await github(`/repos/${source.owner}/${source.repo}/forks`, githubToken, "POST");
      if (created.status === 202 || created.status === 201) return rpc(id, toolResult({ ok: true, state: "accepted", source: sourceUrl(source), fork: created.data.html_url ?? `https://github.com/${TARGET_OWNER}/${source.repo}` }));
      if (created.status === 422) return rpc(id, toolResult({ ok: false, state: "already_exists_or_unavailable", source: sourceUrl(source), message: "GitHub rejected the fork request; re-check target status." }, true));
      return rpc(id, toolResult({ error: "GitHub API request failed", github_status: created.status }, true));
    }
    return rpc(id, toolResult({ error: "Unknown tool." }, true));
  } catch {
    return rpc(id, toolResult({ error: "GitHub fork operation failed." }, true));
  }
});
