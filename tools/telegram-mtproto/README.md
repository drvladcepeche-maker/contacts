# Telegram contact matching (MTProto)

This utility matches phone numbers from a CSV exported by **My Contacts** with Telegram users and writes a new CSV containing a confirmed username and/or numeric Telegram ID. It does not edit Android contacts, Supabase, or the source CSV.

## Privacy and safety

- The script does **not** upload phone numbers unless you pass `--upload-contacts`.
- Matching uses Telegram's MTProto contact-import method. The phone numbers you choose to process are sent to Telegram; some accounts may not be returned because of privacy settings.
- Use only your own Telegram account. Do not share the API hash, login code, 2FA password, or the generated session file.
- Keep the session file private. It can grant access to the Telegram account.
- Review the resulting CSV before importing it into the contacts app. Existing Telegram values are preserved rather than overwritten.

## Setup

Python 3.10+ is recommended.

```bash
python -m venv .venv
# Windows: .venv\\Scripts\\activate
# Linux/macOS/Termux: source .venv/bin/activate
python -m pip install -r tools/telegram-mtproto/requirements.txt
```

Create your own Telegram application at https://my.telegram.org/apps. Set credentials as environment variables; do not commit them into the repository.

Linux/macOS/Termux:
```bash
export TELEGRAM_API_ID="your_api_id"
export TELEGRAM_API_HASH="your_api_hash"
```

Windows PowerShell:
```powershell
$env:TELEGRAM_API_ID="your_api_id"
$env:TELEGRAM_API_HASH="your_api_hash"
```

## Run

First do a non-networking preflight. This lists the count of valid numbers and sends nothing to Telegram:

```bash
python tools/telegram-mtproto/match_contacts.py contacts.csv
```

Only if you agree to upload those phone numbers for matching, run:

```bash
python tools/telegram-mtproto/match_contacts.py contacts.csv --upload-contacts -o contacts_telegram_enriched.csv
```

On first authorized run, Telethon will ask for the Telegram phone number, login code, and possibly the account's 2FA password. The session is stored locally at `private/telegram_user.session`; do not send or upload it.

The script expects a semicolon-delimited CSV with columns like `Имя;Телефоны;Telegram / ID`. It also recognizes common English headers. Multiple phone numbers in one field can be comma-separated.

## Current limitations

- This is a review/export step, not a live write into Android or Supabase.
- Telegram may return no match due to privacy settings or account availability; no ID is guessed.
- Importing the enriched CSV back into the app requires its CSV import path to support the Telegram column. The app's export format already includes it; verify importer compatibility before using bulk import.
