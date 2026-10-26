# Termux command bridge

This folder contains the Android-side worker for the existing Supabase termux-bridge endpoint. It deliberately does **not** accept arbitrary shell text.

## Command policy

Automatically executed:
- `pwd`
- `list_files` (names only, one directory at a time, workspace-confined)
- `git_status`
- `git_log` (at most 20 entries)
- `git_diff_stat`

Requires an interactive confirmation in the Termux terminal (type exactly `YES`):
- `gradle_test`
- `gradle_assemble_debug`

The Gradle tasks execute repository build scripts, so they are treated as potentially unsafe. If the agent is not attached to an interactive terminal, it rejects these tasks. Unknown command types are rejected. Do not add a generic shell, exec, or arbitrary command handler.

## 1. Install prerequisites in Termux

Install Termux from the official sources linked by https://github.com/termux/termux-app, then run:

```sh
pkg update
pkg install nodejs git
```

Clone the bridge branch (or use the branch after it is merged):

```sh
git clone -b feature/termux-command-bridge https://github.com/drvladcepeche-maker/contacts.git
cd contacts
```

## 2. Create a device token locally

```sh
node tools/termux-bridge/setup.mjs
```

The script writes the raw token only to:
`~/.config/termux-bridge/device-token`

It prints the SHA-256 hash, not the token. Copy the hash into Supabase SQL Editor and run the insert statement printed by the script. Only the hash is stored in Supabase. Never paste the raw token into ChatGPT or GitHub.

If the device is already registered, do not run setup again. To rotate it, disable/revoke the old row first and then securely remove the local token file before regenerating.

## 3. Start the agent

From the repository root:

```sh
node tools/termux-bridge/bridge.mjs
```

Keep the Termux session running. Android battery management can stop background work; exempt Termux from battery optimization if you want the bridge to remain available. The agent polls every five seconds by default. Configure POLL_MS or WORKSPACE in the environment if needed.

## 4. Queue a command

The assistant can queue a command using the authenticated Supabase database connection. To queue a read-only status check for the most recently active enabled device:

```sql
insert into public.termux_bridge_jobs (device_id, command_type, args)
select id, 'git_status', '{}'::jsonb
from public.termux_bridge_devices
where enabled = true and revoked_at is null
order by last_seen_at desc nulls last, created_at desc
limit 1
returning id, status, command_type, created_at;
```

Allowed command types are constrained by the database. Never insert raw shell commands. To inspect a result, query the returned job ID:

```sql
select id, status, output, error_text, exit_code, created_at, finished_at
from public.termux_bridge_jobs
where id = 'JOB_UUID';
```

## Security model

- Device authentication uses a random 256-bit token; only its SHA-256 hash is stored in Supabase.
- The edge function checks device enablement and revocation on every request.
- Row-level security is enabled and direct access is revoked from anon and authenticated roles.
- The agent uses spawn with shell=false and fixed argument arrays; it does not interpret server-provided strings as shell commands.
- File listing is restricted to the configured workspace and returns names only.
- Output is bounded and sent only to the private Supabase queue.
- Revoking a device: set enabled=false or revoked_at=now() for its row in termux_bridge_devices.

This bridge is a user-authorized terminal for the current Termux account, not Android root. Treat repository contents and build scripts as untrusted code.