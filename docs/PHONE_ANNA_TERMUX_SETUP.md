# Osiris phone Anna — Termux Worker + Shizuku Bridge

This profile uses the repository's existing Node.js Termux Worker and the existing Supabase `termux-bridge` Edge Function. It does not use a GitHub Actions runner, a native C/C++ component, a Python runtime, or a separate Android companion APK.

## Device identity

- Display label: `телефон Анна`
- Independent device row in `public.termux_bridge_devices`
- Independent random 256-bit bearer token stored locally at `~/.config/termux-bridge/device-token`
- Device ID is assigned by Supabase and is resolved dynamically from the token; no Xiaomi device ID is copied.

## Termux prerequisites

Run in Termux:

```sh
pkg update
pkg install nodejs git curl
termux-setup-storage
```

The device must have Node.js 20+ (Node.js 24 is preferred). No C/C++ build is part of this profile.

## Install rish

Export `rish` and `rish_shizuku.dex` from the Shizuku app into Android's Downloads folder. Then copy both into Termux's executable directory:

```sh
cp "$HOME/storage/downloads/rish" "$PREFIX/bin/rish"
cp "$HOME/storage/downloads/rish_shizuku.dex" "$PREFIX/bin/rish_shizuku.dex"
chmod 700 "$PREFIX/bin/rish"
chmod 600 "$PREFIX/bin/rish_shizuku.dex"
```

Configure the Shizuku application ID for Termux:

```sh
grep -q 'RISH_APPLICATION_ID=com.termux' "$HOME/.bashrc" 2>/dev/null || echo 'export RISH_APPLICATION_ID=com.termux' >> "$HOME/.bashrc"
source "$HOME/.bashrc"
```

In the Shizuku app, start its service and grant Shizuku permission to **Termux**. Test:

```sh
"$PREFIX/bin/rish" -c 'id'
```

Expected identity: `uid=2000(shell)`. Do not continue to registration until this check succeeds.

## Clone this profile

```sh
git clone -b feature/osiris-phone-anna https://github.com/drvladcepeche-maker/contacts.git "$HOME/contacts"
cd "$HOME/contacts"
```

If `$HOME/contacts` already exists, fetch and checkout the branch rather than cloning over it.

## Register the new device

Run once only:

```sh
node tools/termux-bridge/setup.mjs 'телефон Анна'
```

The script creates a local token and prints only its SHA-256 hash plus an SQL INSERT statement. Copy that SQL statement into the existing Supabase SQL Editor. It inserts a new device row with label `телефон Анна`; it does not reuse or alter the Xiaomi row.

Never paste the raw token into chat, GitHub, or SQL. Do not run setup again after registration: the token file is the device's identity.

## Start the Worker

From the repository root:

```sh
export RISH_APPLICATION_ID=com.termux
export RISH_BIN="$PREFIX/bin/rish"
node tools/termux-bridge/bridge.mjs
```

This single Node.js process is the persistent Worker and Bridge client. It sends heartbeats, claims only jobs assigned to its own device row, executes the allowlisted Termux/Shizuku operations, and reports results. It must remain running in Termux for remote control to be online.

## Supported control surface

- Read-only device diagnostics: model, Android release/API, kernel, storage and package inventory.
- Shizuku status: verifies the shell identity is UID 2000.
- UI snapshot through Android UIAutomator.
- UI actions: launch an installed package, tap, swipe, type text, Back/Home/navigation keys.
- Bounded Shizuku file operations in the dedicated TermuxBridge folders.
- Existing Termux workspace diagnostics and authenticated command queue.

Shizuku is not root. Protected system dialogs, biometric checks, locked-device restrictions and app authentication still require normal Android authorization.

## Supabase migration

Before queueing Android control jobs, apply:

`supabase/migrations/20261127100000_expand_termux_bridge_android_control.sql`

This expands the existing command-type CHECK constraint to include the Android control types implemented by the worker. It does not alter existing device records or queue contents.

## No-runner policy

Do not dispatch GitHub Actions workflows for this profile. The branch name is intentionally outside the existing workflow push filters. Do not open a pull request until device-side validation is complete.
