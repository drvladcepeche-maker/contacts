# ChatGPT ↔ «Мои контакты» bridge

## Components

- Android app: sends a snapshot of contacts to Supabase and polls for commands while the app is open.
- Supabase project: `xevqbgjtxuveeqaiacvm`.
- Edge Functions:
  - `contacts-bridge`: device sync and command queue API.
  - `contacts-mcp`: authenticated MCP Streamable HTTP endpoint.
- PostgreSQL tables use RLS with no public client policies. Service-role access stays server-side.

## Android setup

1. Install the latest APK from the repository's successful GitHub Actions build.
2. Open **Мои контакты** and grant contacts permissions.
3. Tap **Связь с ChatGPT**.
4. Paste the device token supplied separately by the project owner, then tap **Сохранить и синхронизировать**.
5. Keep the app open when you want ChatGPT commands to run. Android may pause polling if the app is backgrounded or the device is in power-saving mode.

The device token is a credential. Do not publish it in source control or send it to other people. Revoke it by setting `revoked_at` for its row in `public.contact_bridge_devices` and register a new token.

## MCP connector

MCP endpoint:

`https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-mcp`

Transport: Streamable HTTP, JSON-RPC 2.0. Authenticate with an HTTP header:

`Authorization: Bearer <MCP connector token>`

The MCP token is different from the Android device token. Keep it in the connector's protected authentication settings; never put it in this repository, an APK, or a public document.

Available tools:
- `contacts_status`
- `contacts_list`
- `contacts_search`
- `contacts_request_change`
- `contacts_command_status`

To connect it in ChatGPT, use the account's supported custom MCP connector/developer-mode setup and provide the endpoint and bearer token. This repository cannot install a connector into the ChatGPT account automatically.

## Write safety and limitations

- Read results come from the latest cached snapshot, not a live phone query. Run sync in the Android app to refresh it.
- The Android app must be open to poll and execute queued commands.
- Create/update/delete/merge requests are queued. The phone displays a confirmation dialog before applying each write.
- Delete and merge requests also require explicit authorization when calling the MCP tool.
- This is a personal bridge, not a multi-tenant contact service. Do not share tokens.
- Review Supabase logs and revoke tokens if a device or connector token is exposed.
