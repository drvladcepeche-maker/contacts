# Termux / Shizuku / Brave bridge

This branch extends the existing Supabase command bridge. It has three separate execution layers:

- **Termux:** shell commands, interactive pipe-based sessions, Git, builds, diagnostics, and file uploads.
- **Shizuku (rish):** selected Android Shell operations, APK installation, and file operations in two dedicated shared-storage folders.
- **AccessibilityService:** reads and operates the Brave UI only (com.brave.browser).

This is not root access. Shell commands run as the normal Termux app user; Shizuku commands run as Android Shell UID 2000.

## Command types

### Termux
- terminal_run: run a shell command with a 15-minute hard timeout, workspace-confined working directory, and bounded output.
- terminal_start, terminal_input, terminal_read, terminal_stop: manage up to three in-memory interactive processes.
- file_upload: upload a selected file to the private Supabase Storage bucket termux-bridge-artifacts using a short-lived signed URL (maximum 50 MiB).
- Existing diagnostics: pwd, list_files, file_read, git_status, git_log, git_diff_stat, git_fetch_main, node_check_bridge, node_check_apk_inspect, apk_inventory, apk_inspect.

### Shizuku
- shizuku_storage_check: fixed read-only check of the APK folder.
- install_apk: PackageManager update for com.mycontacts or com.mycontacts.pairing2; checks package metadata and signature compatibility; never uninstalls the old app.
- shizuku_file_list, shizuku_file_mkdir, shizuku_file_copy, shizuku_file_move: paths are restricted to /sdcard/Download/TermuxBridge and /sdcard/Documents/TermuxBridge.
- shizuku_file_delete: single-file deletion only; requires args.confirm=true; root folders cannot be deleted.

### Brave Accessibility
- brave_accessibility with actions snapshot, click, input, scroll, back, and open_url.
- The Android companion app receiver accepts explicit broadcasts only from Android Shell UID 2000 (Shizuku).
- Accessibility data is scoped to the Brave package. A single response file is written to Download/TermuxBridge/accessibility-result.json, then read by Termux.

## Security and runtime limits

- Terminal commands are powerful and can access files available to the Termux user. Only a trusted authenticated controller should be allowed to queue them.
- Commands do not run as root and do not automatically inherit environment variables whose names contain token, secret, password, credential, or private-key markers. The device token is not sent to Supabase Storage.
- Shell commands are limited to 8192 characters; one-shot commands have a 15-minute maximum; interactive sessions are capped at three and expire after 15 minutes of inactivity.
- Interactive sessions use stdin/stdout pipes, not a PTY. Full-screen terminal programs such as vim, nano, and htop are not supported.
- File uploads are limited to 50 MiB. Workspace paths are allowed; shared Downloads uploads must use @downloads/TermuxBridge/... or @downloads/PnS_APK/.... Sensitive filename patterns are blocked.
- Shizuku file operations do not use arbitrary caller-supplied shell commands. Paths are limited to dedicated shared-storage folders. Delete is non-recursive and requires explicit confirmation.
- Accessibility commands require the companion app to be installed and the Accessibility service manually enabled in Android Settings. The receiver rejects broadcasts not sent by UID 2000.
- Revoking the device: set enabled=false or revoked_at=now() for its row in termux_bridge_devices.

## Install/update on the phone

Install prerequisites in Termux:

    pkg update
    pkg install nodejs git aapt
    termux-setup-storage

If the repository is already cloned:

    cd ~/contacts
    git fetch origin
    git checkout feature/brave-accessibility-control
    git pull --ff-only origin feature/brave-accessibility-control

Otherwise:

    git clone -b feature/brave-accessibility-control https://github.com/drvladcepeche-maker/contacts.git ~/contacts
    cd ~/contacts

Do not run setup.mjs again if the device is already registered. Keep the existing token file at ~/.config/termux-bridge/device-token.

Start the worker:

    node tools/termux-bridge/bridge.mjs

Keep the Termux session alive and exempt Termux from battery optimization if continuous access is required.

## Example queue payloads

Insert a job into public.termux_bridge_jobs for the registered device. Example payloads:

    {"command":"git status --short --branch","cwd":"."}

Use this with command_type=terminal_run.

For a persistent Python prompt:
- terminal_start: {"command":"python -u -i"}
- terminal_input: {"session_id":"<returned-session-id>","input":"print(1 + 1)\n"}
- terminal_read: {"session_id":"<returned-session-id>"}
- terminal_stop: {"session_id":"<returned-session-id>"}

For a file upload:
- Workspace file: {"path":"relative/path/report.zip"}
- Downloads artifact: {"path":"@downloads/TermuxBridge/report.zip"}
- APK: {"path":"@downloads/PnS_APK/app-debug.apk"}

For Shizuku file operations, create the dedicated folders first:
- shizuku_file_mkdir: {"path":"/sdcard/Download/TermuxBridge"}
- shizuku_file_list: {"path":"/sdcard/Download/TermuxBridge"}

For Brave:
- Snapshot: {"action":"snapshot"}
- Click: {"action":"click","text":"Sign in"}
- Input: {"action":"input","view_id":"com.brave.browser:id/url_bar","value":"https://example.com"}
- Scroll: {"action":"scroll","direction":"down"}
- Back: {"action":"back"}
- Open URL: {"action":"open_url","url":"https://example.com"}

After installing the updated APK, manually enable Settings → Accessibility → Installed apps → Мои контакты — привязка. The service is configured to observe Brave only.

## Build approval

gradle_test and gradle_assemble_debug still require typing exactly YES in an interactive Termux terminal because repository build scripts execute code. If no interactive terminal is attached, those tasks are rejected.

The APK installer validates the APK package, checks that the target package is already installed, requires Shizuku to report uid=2000(shell), then runs only pm install -r --user 0. Android PackageManager enforces signing-key compatibility. No uninstall, downgrade, or signature-bypass flags are used.
