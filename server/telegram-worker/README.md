# Contacts Telegram Resolver Worker

A standalone Node.js MTProto worker for Hostless. It resolves phone numbers already present in the Supabase contact snapshot without importing or adding them to the Telegram account's contacts.

## Behaviour

- Calls `contacts.resolvePhone` one phone number at a time.
- Reads GramJS `Api.User.usernames` entries, keeps active usernames, and also includes the legacy primary username; stores distinct values joined with comma + space.
- Enforces a minimum 5-second interval between Telegram lookup requests, plus random additive jitter (default 0–3 seconds).
- On Telegram FloodWait, stops processing the current job and re-queues it with `paused_until` set beyond the server-specified wait.
- Persists each result and progress to Supabase immediately; a restarted worker resumes at the first unprocessed candidate.
- Updates the corresponding `contact_bridge_snapshots.contact_data` record by `device_id + local_contact_id`.
- Stores the real username when present. If Telegram resolves a user but no username is returned, stores `СКРЫТ` in `telegram_username`. Unresolvable numbers are marked `not_resolved`, never `СКРЫТ`.
- Does not import contacts, send messages, or change Telegram account contact lists.

Telegram rate limits are dynamic. A 5-second minimum interval is a conservative throttle, not a guarantee against FloodWait or account restrictions. The worker obeys FloodWait and never retries the same lookup immediately.

## Deployment requirements

Working directory: `server/telegram-worker`; build from the included Dockerfile for a persistent worker, or use the GitHub Actions runtime workflow.

Required runtime secrets:
- `SUPABASE_SERVICE_ROLE_KEY`
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_SESSION`

For the legacy Hostless Docker deployment, also set `SUPABASE_URL` and expose port 8080.

Optional:
- `POLL_INTERVAL_MS` (default 5000)
- `MIN_REQUEST_INTERVAL_MS` (default 5000; values below 5000 are clamped to 5000)
- `REQUEST_JITTER_MS` (default 15000; additive random delay)
- `MIN_REQUEST_INTERVAL_MS` (minimum 60000; the worker clamps lower values to 60000)
- `MAX_CANDIDATES_PER_RUN` (runtime workflow default 5)
- `BATCH_COOLDOWN_MS` (default 3600000)
- `PORT` (default 8080)

The worker surfaces every Telegram FloodWait immediately (GramJS `floodSleepThreshold=0`), stores the wait count and last wait in `telegram_match_jobs`, pauses for the server-requested duration plus a 60-second margin, and increases the minimum inter-request interval by one minute after each FloodWait, up to five minutes. Do not clear or shorten `paused_until` to force a retry.

Apply the SQL migration `telegram_resolver_rate_limit_resume` before starting the worker. The existing Android TDLib session is encrypted with Android Keystore and is not portable to this worker; generate a separate GramJS StringSession.

## Generate the session on Android (Termux)

This workflow does not require a laptop. Use the official Termux app and keep the generated session on the same Android device until you add it to GitHub Secrets.

1. Install Node.js and Git in Termux:
   ```sh
   pkg update
   pkg install nodejs git
   ```
2. Clone the repository:
   ```sh
   git clone https://github.com/drvladcepeche-maker/contacts.git
   cd contacts/server/telegram-worker
   npm install
   ```
3. Enter API credentials without putting them into shell history:
   ```sh
   read -r -p "Telegram API ID: " TELEGRAM_API_ID
   read -r -s -p "Telegram API HASH: " TELEGRAM_API_HASH
   echo
   export TELEGRAM_API_ID TELEGRAM_API_HASH
   ```
4. Start the interactive authorization:
   ```sh
   npm run generate-session
   ```
   Enter the Telegram phone number in international format, then the login code Telegram sends, and the 2FA password if enabled.

   If a session file already exists the script will ask for confirmation before overwriting. To force overwrite without a prompt use:
   ```sh
   npx tsx scripts/generate-session.ts --force
   ```
5. On success, the script saves the session to:
   `~/.config/contacts-telegram/TELEGRAM_SESSION`
   The directory and file are created with owner-only permissions (0700/0600). The session value is not printed. Do not delete this file until the GitHub secret is saved.
6. In the GitHub repository open Settings → Secrets and variables → Actions → New repository secret. Name it exactly `TELEGRAM_SESSION`. Open the local file in Termux and copy its contents directly into that secret field. Never paste the value into ChatGPT, issues, commits, screenshots, or logs. Clear the Android clipboard after saving.

## GitHub Actions runtime (no separate worker host)

The scheduled workflow checks queue readiness every 10 minutes. A lightweight Supabase precheck avoids installing and building the worker unless a queued job is due or a stale `running` job needs recovery. Each worker run processes up to 20 phone candidates, saves progress, and sets `paused_until` to 60 minutes after the last Telegram lookup request. This cooldown is deliberately conservative: live `contacts.resolvePhone` calls reached a server FloodWait after 20 successful lookups, with a reported wait of about 58 minutes. The 10-minute checks resume the queue shortly after the cooldown expires. The run timeout is 20 minutes and overlapping runs are not cancelled.

1. In repository Settings → Secrets and variables → Actions, add the four secrets listed above.
2. Add repository variable `TELEGRAM_RESOLVER_ENABLED=true` only after all four secrets are saved.
3. Run the workflow manually once from Actions to verify startup, then inspect its logs. The session and service-role key are injected as secrets and must never be echoed.

The Telegram StringSession is equivalent to a login credential and can access the Telegram account. Prefer a dedicated Telegram account. Never paste the session or service-role key into chat, source files, issues, or commits. The scheduled worker uses the Supabase service-role key only as a protected GitHub Actions secret.

## Health check

For the persistent Docker worker, `GET /health` returns HTTP 200 after the MTProto session has been authorized.

## Result semantics

- `matched`: Telegram user resolved and username exists.
- `username_missing`: Telegram user resolved but username is absent; the phonebook field is set to `Скрыт` only if no previous username is known.
- `not_resolved`: Telegram did not resolve the phone number. Telegram privacy rules can make this indistinguishable from a number with no Telegram account.
- `error`: an unexpected technical failure.

## Username preservation

Previously discovered usernames are retained and combined with new values using comma + space. A later unresolved phone lookup cannot erase an already confirmed Telegram match on the same contact.

## Duplicate phone numbers

The queue stores each normalized international phone number once per device. If the same number appears in several contacts, one Telegram lookup result is applied to every matching local contact ID, avoiding repeated Telegram requests.
