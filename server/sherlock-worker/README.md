# Sherlock Username Discovery Worker

Separate, read-only public-profile discovery layer for My Contacts. It complements (and does not replace) the GramJS Telegram phone resolver.

## What it does
- Reads the current contact snapshot from Supabase and inspects all captured typed Android fields.
- Classifies emails, international and ambiguous phone-like values, known Telegram IDs, usernames and supported profile URLs. It records where each value came from and flags likely field misplacements (for example, a phone number in an Email field).
- Extracts explicit @handles and supported profile URLs. It does not derive usernames from contact names or email local-parts.
- Runs the pinned Sherlock Project engine against a small allowlist of public sites. Explicit profile URLs narrow the search to their corresponding platform; free-standing handles use the allowlist.
- Stores classification evidence in `contact_identifier_observations` and Sherlock results in `contact_osint_profiles`.
- Reuses results for 30 days and processes at most 12 unique handles per scheduled run.

## Safety and interpretation
- Only usernames/profile URLs are submitted to public sites. Phone numbers, email addresses and numeric IDs are never submitted to Sherlock.
- NSFW sites are excluded. The worker uses Sherlock's bundled catalog for the pinned package version (`--local`); weekly upstream review is tracked separately.
- `CLAIMED` means the platform probe suggests a profile exists. It does not prove that the profile belongs to the contact. `AVAILABLE`, `UNKNOWN`, `ILLEGAL`, and `WAF` remain distinct; WAF/unknown are not treated as account absence.
- Original contact snapshots are never edited. No contact is merged, deleted, or automatically enriched. Evidence is stored in separate RLS-protected tables for review.
- Unprefixed numeric values are classified as ambiguous phone-like values and are not normalized to E.164 or sent to Telegram automatically.

## Deployment
Apply migration `20261103133000_sherlock_identifier_discovery.sql`, then ensure repository Actions secret `SUPABASE_SERVICE_ROLE_KEY` is present. The workflow runs every six hours and can also be launched manually. It scans up to 20,000 snapshot rows and checks at most 12 unique handles per run. The worker writes only to its two evidence tables.

## Local test
```sh
python -m pip install -r requirements.txt
python worker.py --self-test
```

Sherlock Project is MIT-licensed. See https://github.com/sherlock-project/sherlock.
