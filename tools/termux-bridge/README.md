# Termux / Shizuku / Brave bridge

This branch extends the existing Supabase command bridge. It has three separate execution layers:

- **Termux:** shell commands, interactive pipe-based sessions, Git, builds, diagnostics, and file uploads.
- **Shizuku (rish):** selected Android Shell operations, APK installation, and file operations in two dedicated shared-storage folders.
- **AccessibilityService:** reads and operates the Brave UI only (com.brave.browser); the authenticated Termux app sends the command broadcast directly, so Brave control does not depend on launching rish from a non-interactive Node process.

This is not root access. Shell commands run as the normal Termux app user; Shizuku commands run as Android Shell UID 2000.

## Command types

### Termux
- terminal_run: run a shell command with a 15-minute hard timeout, workspace-confined working directory, and bounded output.
- terminal_start, terminal_input, terminal_read, terminal_stop: manage up to three in-memory interactive processes.
- file_upload: upload a selected file to the private Supabase Storage bucket termux-bridge-artifacts using a short-lived signed URL (maximum 50 MiB).
- Existing diagnostics: pwd, list_files, file_read, git_status, git_log, git_diff_stat, git_fetch_main, node_check_bridge, node_check_apk_inspect, apk_inventory, apk_inspect.

### Shizuku
- shizuku_storage_check: fixed read-only check of the APK folder.
- install_apk: in-place update only for an already-installed allowlisted My Contacts package; checks package metadata and relies on Android PackageManager to enforce signing-certificate compatibility; first-time package installation is rejected.
- shizuku_file_list, shizuku_file_mkdir, shizuku_file_copy, shizuku_file_move: paths are restricted to /sdcard/Download/TermuxBridge and /sdcard/Documents/TermuxBridge.
- shizuku_file_delete: single-file deletion only; requires args.confirm=true; root folders cannot be deleted.

### Brave Accessibility
- brave_accessibility with actions snapshot, click, input, scroll, back, and open_url.
- The Android companion app receiver accepts explicit broadcasts only from Android Shell UID 2000 or the installed Termux app UID (resolved through PackageManager). Termux sends this restricted action payload directly; the receiver still rejects all other senders.
- Accessibility data is scoped to the Brave package. A single response file is written to Download/TermuxBridge/accessibility-result.json, then read by Termux.

## Security and runtime limits

- Terminal commands are powerful and can access files available to the Termux user. Only a trusted authenticated controller should be allowed to queue them.
- Commands do not run as root and do not automatically inherit environment variables whose names contain token, secret, password, credential, or private-key markers. The device token is not sent to Supabase Storage.
- Shell commands are limited to 8192 characters; one-shot commands have a 15-minute maximum; interactive sessions are capped at three and expire after 15 minutes of inactivity.
- Remote shell execution (terminal_run/start/input), APK installation, file uploads, Shizuku file mutations, and Brave click/input/open_url actions require a fresh local confirmation: the user must type exactly YES in the visible Termux terminal within 60 seconds. If the bridge has no interactive TTY or approval times out, the operation is rejected and is not executed.
- Read-only diagnostics, file listing/inspection, terminal output reads, and Brave snapshot/back/scroll remain available without an approval prompt.
- Interactive sessions use stdin/stdout pipes, not a PTY. Full-screen terminal programs such as vim, nano, and htop are not supported.
- File uploads are limited to 50 MiB. Workspace paths are allowed; shared Downloads uploads must use @downloads/TermuxBridge/... or @downloads/PnS_APK/.... Sensitive filename patterns are blocked.
- Shizuku file operations do not use arbitrary caller-supplied shell commands. Paths are limited to dedicated shared-storage folders. Delete is non-recursive and requires explicit confirmation.
- Accessibility commands require the updated companion app to be installed and the Accessibility service manually enabled in Android Settings. The receiver rejects broadcasts unless they originate from Android Shell UID 2000 or the exact UID assigned to package com.termux. The Termux bridge targets the isolated com.mycontacts.bridgefix4 receiver directly and sends the broadcast with am; it does not query PackageManager from the Termux app UID. Shizuku remains required for the separate file and APK-install operations.
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

## Safe Brave command interface

Do not insert Brave jobs directly into the database. Use the authenticated, allowlisted Edge Function interface through the local CLI.

Keep the normal worker running in one Termux session:

    cd ~/contacts
    node tools/termux-bridge/bridge.mjs

In a second Termux session, run one of the supported one-shot commands:

    cd ~/contacts
    node tools/termux-bridge/bridge.mjs brave snapshot
    node tools/termux-bridge/bridge.mjs brave back
    node tools/termux-bridge/bridge.mjs brave open_url https://www.reg.ru/

The one-shot command queues exactly one approved action, polls the authenticated status endpoint for that device's own job, prints the result, and exits. The server permits only snapshot, back, and the exact public URL https://www.reg.ru/. It rejects arbitrary URLs and other Brave actions. If the command times out, leave the worker running and inspect the worker session before retrying; do not queue duplicate commands while one is pending.

The status endpoint requires the same registered device token and filters by both the authenticated device ID and command type. It returns only the matching job's status, output, error, and exit code.

## Local approval

All consequential operations listed above require typing exactly YES in the interactive Termux terminal. This is an independent on-device confirmation; possession of the server/device token alone is not sufficient to execute shell commands, mutate shared storage, upload files, install APKs, or perform consequential Brave UI actions. `gradle_test` and `gradle_assemble_debug` also require the same confirmation because repository build scripts execute code. If no interactive terminal is attached, these operations are rejected.

The APK installer validates the APK package, requires the target package to already be installed, requires Shizuku to report uid=2000(shell), then runs only `pm install -r --user 0`. Android PackageManager enforces signing-certificate compatibility for the in-place update. First-time installation, uninstall, downgrade, and signature-bypass flags are not supported.
