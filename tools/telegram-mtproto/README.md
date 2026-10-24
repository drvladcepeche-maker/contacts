# Telegram MTProto contact matching

This tool matches phone numbers in a CSV exported by **My Contacts** against Telegram through an authenticated MTProto user session. It writes a new CSV; it does not change Android contacts, Supabase, or the source file.

## Privacy

- Default mode is a local preflight: it reads the CSV and counts valid phone numbers but sends nothing to Telegram.
- To query Telegram, you must explicitly add `--upload-contacts`. Telegram will receive the phone numbers selected for matching.
- Only use your own Telegram account. Do not share API credentials, login codes, 2FA password, or the session file.
- The session file can grant account access. Keep it private and outside version control.
- Existing Telegram values are not overwritten. Review the output before importing it.
- A missing match is inconclusive; Telegram privacy settings can prevent a number from resolving to an account.

## Install

Python 3.10+:

```bash
python -m venv .venv
# Termux/Linux/macOS:
source .venv/bin/activate
python -m pip install -r tools/telegram-mtproto/requirements.txt
```

Create your own application at https://my.telegram.org/apps. Set credentials as environment variables; never commit them to the repository.

Termux/Linux/macOS:
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

Preflight only, no Telegram network requests:
```bash
python tools/telegram-mtproto/match_contacts.py contacts.csv
```

After reviewing the count and deciding to send the listed phone numbers to Telegram:
```bash
python tools/telegram-mtproto/match_contacts.py contacts.csv --upload-contacts -o contacts_telegram_enriched.csv
```

On first run, Telethon prompts for Telegram phone number, login code, and 2FA password if enabled. The session defaults to `private/telegram_user.session`; never share it. Keep the session directory private.

The CSV should contain columns like `Имя;Телефоны;Telegram / ID`. Common English headers are also recognized. Multiple numbers in a phone field may be comma-separated.

## Limitations

This is a review/export workflow, not a direct write to Android or Supabase. The app's CSV export includes Telegram / ID, but its importer must be checked for symmetric import support before bulk import. No usernames or IDs are guessed.
