# Termux command bridge

This folder contains the Android-side worker for the Supabase Termux bridge. It supports authenticated shell execution as the ordinary Termux app user, bounded interactive sessions, scoped Shizuku file operations, APK updates, and private Storage uploads. It does not provide root access or automatically elevate shell commands through Shizuku.

## Command policy

Automatically executed:
- `pwd`
- `list_files` (names only, one directory at a time, workspace-confined)
- `git_status`
- `git_log` (at most 20 entries)
- `git_diff_stat`
- `apk_inventory` and `apk_inspect`
- `shizuku_storage_check` (read-only; uses Shizuku `rish` to list `/sdcard/Download/PnS_APK`, without Termux shared-storage permission)
- `install_apk` (only a named APK directly inside `~/storage/downloads/PnS_APK`, and only package `com.mycontacts`; requires Shizuku `rish` to run as Android shell)

Requires an interactive confirmation in the Termux terminal (type exactly `YES`):
- `gradle_test`
- `gradle_assemble_debug`

Terminal commands are intentionally powerful: they execute as the Termux app user and can access files and packages available to that user. Only queue commands from a trusted, authenticated controller. Commands do not run as root and do not inherit environment variables whose names contain token, secret, password, credential, or private-key markers. Pipe-based sessions are not a PTY, so full-screen terminal programs such as `vim`, `nano`, and `htop` are not supported. Sessions live only in memory and expire after 15 minutes of inactivity. Shizuku file operations are separately scoped to two dedicated shared-storage folders; deletion is non-recursive and requires explicit confirmation. File uploads use a short-lived signed URL for a private bucket; the raw device token is never sent to Storage. The Shizuku storage check remains fixed and read-only. The Gradle tasks execute repository build scripts, so they are treated as potentially unsafe. If the agent is not attached to an interactive terminal, it rejects these tasks. The APK installer is narrowly scoped: it validates a plain filename, rejects symlinks and oversized files, checks the APK package with `aapt`, requires Shizuku `rish` to report `uid=2000(shell)`, confirms the existing package is installed, then runs only `pm install -r --user 0`. Android PackageManager enforces signing-key compatibility; a mismatch fails without uninstalling the old app. No downgrade or signature-bypass flags are used. Install `aapt` in Termux with `pkg install aapt` if it is missing. Unknown command types are rejected. Do not add a generic shell, exec, or arbitrary command handler.

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

Allowed command types are constrained by the database. Example shell command: queue `terminal_run` with `{"command":"git status --short --branch","cwd":"."}`. For a long-running process, queue `terminal_start` with `{"command":"python -u -i"}`, then use `terminal_input` with the returned `session_id` and an `input` string, and poll with `terminal_read`. Stop it with `terminal_stop`. These are pipe sessions, not PTYs. For Brave, queue `brave_accessibility` with `{"action":"snapshot"}` to read the visible accessibility tree. Other actions: `{"action":"click","text":"Sign in"}`, `{"action":"input","view_id":"com.brave.browser:id/url_bar","value":"https://example.com"}`, `{"action":"scroll","direction":"down"}`, `{"action":"back"}`, or `{"action":"open_url","url":"https://example.com"}`. The companion APK must be installed and its Accessibility service manually enabled in Android Settings → Accessibility → Installed apps. It only observes `com.brave.browser`; the command receiver accepts broadcasts from Android Shell UID 2000 only. Termux must have shared-storage access to read the result file.\n\nQueue `shizuku_storage_check` with an empty JSON object to inspect the shared APK folder through Shizuku. The file-operation commands accept paths only under `/sdcard/Download/TermuxBridge` or `/sdcard/Documents/TermuxBridge`. Create these folders first with `shizuku_file_mkdir`. To upload a file in the workspace, queue `file_upload` with `{"path":"relative/path.zip"}`. To upload from Termux shared Downloads, use `{"path":"@downloads/TermuxBridge/report.zip"}`. Uploads are limited to 50 MiB and are stored privately in the `termux-bridge-artifacts` bucket under a device-specific prefix. Do not upload tokens, passwords, keystores, private keys, or personal files unless explicitly required. To install a specific APK already listed by `apk_inventory`, queue `install_apk` with `{"file":"app-debug.apk"}` (replace with the exact listed filename). Never insert raw shell commands. This operation updates `com.mycontacts` in place and does not uninstall it. To inspect a result, query the returned job ID:

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