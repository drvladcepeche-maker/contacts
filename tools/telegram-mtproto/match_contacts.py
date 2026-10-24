#!/usr/bin/env python3
"""Opt-in Telegram MTProto contact matcher for a My Contacts CSV export.

No phone numbers are sent to Telegram unless --upload-contacts is supplied.
The input CSV is never modified; results are written to a separate file.
"""
import argparse
import asyncio
import csv
import os
import re
import sys
from pathlib import Path

from telethon import TelegramClient
from telethon.tl.functions.contacts import ImportContactsRequest
from telethon.tl.types import InputPhoneContact

NAME_HEADERS = ("Имя", "Name", "name")
PHONE_HEADERS = ("Телефоны", "Телефон", "Phones", "Phone", "phones", "phone")
TG_HEADERS = ("Telegram / ID", "Telegram", "Telegram username/ID", "telegram")


def find_header(headers, choices):
    by_lower = {h.strip().lower(): h for h in headers}
    return next((by_lower[c.lower()] for c in choices if c.lower() in by_lower), None)


def phone_list(value):
    result = []
    for raw in re.split(r"[,\n|]", value or ""):
        digits = re.sub(r"\D", "", raw)
        if len(digits) >= 7:
            normalized = "+" + digits
            if normalized not in result:
                result.append(normalized)
    return result


async def main(args):
    api_id = os.getenv("TELEGRAM_API_ID", "").strip()
    api_hash = os.getenv("TELEGRAM_API_HASH", "").strip()
    if not api_id or not api_hash:
        print("Set TELEGRAM_API_ID and TELEGRAM_API_HASH first.", file=sys.stderr)
        return 2
    try:
        api_id = int(api_id)
    except ValueError:
        print("TELEGRAM_API_ID must be an integer.", file=sys.stderr)
        return 2

    source, destination = Path(args.input), Path(args.output)
    if source.resolve() == destination.resolve():
        print("Input and output paths must differ.", file=sys.stderr)
        return 2

    with source.open(encoding="utf-8-sig", newline="") as f:
        sample = f.read(8192)
        f.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=";,\t")
        except csv.Error:
            dialect = csv.excel
            dialect.delimiter = ";"
        reader = csv.DictReader(f, dialect=dialect)
        if not reader.fieldnames:
            print("CSV header row is missing.", file=sys.stderr)
            return 2
        headers = list(reader.fieldnames)
        name_col = find_header(headers, NAME_HEADERS)
        phone_col = find_header(headers, PHONE_HEADERS)
        tg_col = find_header(headers, TG_HEADERS)
        if not name_col or not phone_col:
            print("Expected columns like Имя;Телефоны;Telegram / ID.", file=sys.stderr)
            return 2
        if not tg_col:
            tg_col = "Telegram / ID"
            headers.append(tg_col)
        rows = list(reader)

    phone_rows = {}
    contact_by_phone = {}
    for idx, row in enumerate(rows):
        name = (row.get(name_col) or "").strip() or "Contact"
        for phone in phone_list(row.get(phone_col) or ""):
            phone_rows.setdefault(phone, []).append(idx)
            if phone not in contact_by_phone:
                first, _, last = name.partition(" ")
                contact_by_phone[phone] = InputPhoneContact(
                    client_id=len(contact_by_phone) + 1,
                    phone=phone,
                    first_name=first[:64] or "Contact",
                    last_name=last[:64],
                )

    contacts = list(contact_by_phone.values())
    if not contacts:
        print("No valid phone numbers found.", file=sys.stderr)
        return 2
    if not args.upload_contacts:
        print(f"Preflight only: {len(rows)} rows, {len(contacts)} unique phone numbers.")
        print("Nothing was sent to Telegram. Add --upload-contacts to opt in.")
        return 0

    phone_by_client_id = {c.client_id: c.phone for c in contacts}
    matches = {}
    session = Path(args.session)
    session.parent.mkdir(parents=True, exist_ok=True)
    async with TelegramClient(str(session), api_id, api_hash) as client:
        if not await client.is_user_authorized():
            await client.start()
        for offset in range(0, len(contacts), 100):
            batch = contacts[offset:offset + 100]
            result = await client(ImportContactsRequest(batch))
            users = {u.id: u for u in result.users}
            for imported in result.imported:
                phone = phone_by_client_id.get(imported.client_id)
                user = users.get(imported.user_id)
                if not phone or not user:
                    continue
                username = getattr(user, "username", None)
                matches[phone] = (f"@{username} | ID: {user.id}" if username else f"ID: {user.id}")
            if offset + 100 < len(contacts):
                await asyncio.sleep(1)

    for phone, indexes in phone_rows.items():
        value = matches.get(phone)
        if not value:
            continue
        for idx in indexes:
            # Never overwrite a value already entered by the user.
            if not (rows[idx].get(tg_col) or "").strip():
                rows[idx][tg_col] = value

    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=headers, delimiter=";", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Rows: {len(rows)}")
    print(f"Unique phone numbers queried: {len(contacts)}")
    print(f"Confirmed Telegram matches: {len(matches)}")
    print(f"Output: {destination}")
    print("Review before importing. No match does not prove the person lacks Telegram.")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="CSV exported from My Contacts")
    parser.add_argument("-o", "--output", default="contacts_telegram_enriched.csv")
    parser.add_argument("--session", default="private/telegram_user", help="Private local session path")
    parser.add_argument("--upload-contacts", action="store_true", help="Consent to send these phone numbers to Telegram")
    raise SystemExit(asyncio.run(main(parser.parse_args())))
