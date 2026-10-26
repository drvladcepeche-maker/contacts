# Contacts Telegram Worker

Standalone Node.js worker for the Hostless Worker resource. It is intentionally separate from the Android app and Supabase Edge Functions.

## What it does

- Keeps a Telegram MTProto client connected using a dedicated Telegram session.
- Claims one queued job at a time through the Supabase RPC `claim_telegram_match_job`.
- Imports international-format phone numbers in batches of 20, records Telegram user IDs and usernames, and updates job progress.
- Exposes `GET /health` on port 8080.
- Uses one worker loop and a delay between batches. API errors are recorded; FloodWait errors stop the current job instead of retrying rapidly.

## Before deployment

1. Apply `../../supabase/migrations/202610260001_telegram_match_jobs.sql` to the Supabase project.
2. Add the environment variables from `.env.example` as Hostless secrets. Never commit real keys or a Telegram session.
3. Create a dedicated Telegram account/session for server use. The existing Android TDLib session is encrypted with Android Keystore and is not exportable by this worker.
4. Deploy this directory as the Worker working directory: `server/telegram-worker`, using its Dockerfile.

The Android app is not yet wired to enqueue jobs. This worker is a server foundation; end-to-end use requires adding authenticated job submission and result retrieval to the app/bridge.

## Session security

`TELEGRAM_SESSION` grants access to the Telegram account. Store it only as a protected Hostless secret, restrict project access, and revoke the session from Telegram active sessions if it is exposed. Do not use a personal account unless that is an intentional, informed choice.
