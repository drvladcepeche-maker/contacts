# Contacts Telegram Resolver Worker

A standalone Node.js MTProto worker for Hostless. It resolves phone numbers already present in the Supabase contact snapshot without importing or adding them to the Telegram account's contacts.

## Behaviour

- Calls `contacts.resolvePhone` one phone number at a time.
- Enforces a minimum 5-second interval between Telegram lookup requests, plus random additive jitter (default 0–3 seconds).
- On Telegram FloodWait, stops processing the current job and re-queues it with `paused_until` set beyond the server-specified wait.
- Persists each result and progress to Supabase immediately; a restarted worker resumes at the first unprocessed candidate.
- Updates the corresponding `contact_bridge_snapshots.contact_data` record by `device_id + local_contact_id`.
- Stores the real username when present. If Telegram resolves a user but no username is returned, stores `СКРЫТ` in `telegram_username`. Unresolvable numbers are marked `not_resolved`, never `СКРЫТ`.
- Does not import contacts, send messages, or change Telegram account contact lists.

Telegram rate limits are dynamic. A 5-second minimum interval is a conservative throttle, not a guarantee against FloodWait or account restrictions. The worker obeys FloodWait and never retries the same lookup immediately.

## Legacy Hostless deployment (optional)

Working directory: `server/telegram-worker`; build from the included Dockerfile; expose port 8080.

Required Hostless secrets:
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_SESSION`

Optional:
- `POLL_INTERVAL_MS` (default 5000)
- `MIN_REQUEST_INTERVAL_MS` (default 5000; values below 5000 are clamped to 5000)
- `REQUEST_JITTER_MS` (default 3000)
- `PORT` (default 8080)

Apply the new SQL migration before starting the worker. Generate a dedicated MTProto StringSession on a trusted local machine; do not paste it into chat, commit it, or place it in build logs. On Windows, run `generate-session.bat` from this directory (it uses CMD, not PowerShell); enter the API ID and API hash when prompted. It installs dependencies and starts the Telegram phone/code/2FA authorization flow. Copy the final session string directly into the GitHub repository secret `TELEGRAM_SESSION` (or the Hostless secret if using that legacy deployment). The existing Android TDLib session is encrypted with Android Keystore and is not portable to this worker.

## Health check

`GET /health` returns HTTP 200 after the MTProto session has been authorized.

## Result semantics

- `matched`: Telegram user resolved and username exists.
- `username_missing`: Telegram user resolved but username is absent; phonebook field is set to `СКРЫТ`.
- `not_resolved`: Telegram did not resolve the phone number. Telegram privacy rules can make this indistinguishable from a number with no Telegram account.
- `error`: an unexpected technical failure.


## GitHub Actions runtime (no separate worker host)

The resolver can also run as a scheduled, checkpointed GitHub Actions job; this avoids keeping a paid Docker worker online.

1. In GitHub repository Settings → Secrets and variables → Actions, add these repository secrets:
   - `SUPABASE_SERVICE_ROLE_KEY`
   - `TELEGRAM_API_ID`
   - `TELEGRAM_API_HASH`
   - `TELEGRAM_SESSION`
2. Generate a dedicated Telegram session on a trusted computer after installing Node.js:
   `cd server/telegram-worker && npm install && npm run generate-session`
3. Add repository variable `TELEGRAM_RESOLVER_ENABLED=true` only after all four secrets are saved.
4. The scheduled workflow processes up to 250 phone candidates per run, saves progress, and resumes on a later run. It runs every five minutes when enabled.

The Telegram StringSession is equivalent to a login credential and can access the Telegram account. Prefer a dedicated Telegram account. Never paste the session or service-role key into chat, source files, issues, or commits. The scheduled worker uses the Supabase service-role key only as a protected GitHub Actions secret.

### Username preservation

When a Telegram account resolves but currently has no public username, the resolver stores `Скрыт` only if no earlier username is known. Previously discovered usernames are retained and combined with new values using comma + space. A later unresolved phone lookup also cannot erase an already confirmed Telegram match on the same contact.

### Duplicate phone numbers

The queue stores each normalized international phone number once per device. If the same number appears in several contacts, one Telegram lookup result is applied to every matching local contact ID, avoiding repeated Telegram requests.
