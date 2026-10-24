#!/usr/bin/env python3
"""Match an exported My Contacts CSV against Telegram using an authenticated MTProto user session.

The script uploads phone numbers to Telegram only when --upload-contacts is supplied.
It writes a separate enriched CSV and never modifies Android contacts or the server directly.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import os
import re
import sys
from pathlib import Path
from typing import Any

from telethon import TelegramClient
from telethon.tl.functions.contacts import ImportContactsRequest
from telethon.tl.types import InputPhoneContact

PHONE_HEADERS = ("Телефоны", "Телефон", "Phone", "Phones", "phone", "phones")
NAME_HEADERS = ("Имя", "Name", "name")
TELEGRAM_HEADERS = ("Telegram / ID", "Telegram", "Telegram username/ID", "telegram")


def norm_phone(value: str) -> str:
    value = value.strip()
    if not value:
        return ""
    # Preserve a leading plus, strip display punctuation, and reject values with too few digits.
    digits = re.sub(r"\D", "", value)
    if len(digits) < 7:
        return ""
    return ("+" if value.startswith("+") else "+") + digits


def split_phones(value: str) -> list[str]:
    # The app's current CSV exporter joins multiple phone numbers with commas.
    parts = re.split(r"[,\n|]", value or "")
    return list(dict.fromkeys(p for p in (norm_phone(x) for x in parts) if p))


def find_header(headers: list[str], choices: tuple[str, ...]) -> str | None:
    for choice in choices:
        if choice in headers:
            return choice
    lower = {h.strip().lower(): h for h in headers}
    for choice in choices:
        if choice.strip().lower() in lower:
            return lower[choice.strip().lower()]
    return None


async def run(args: argparse.Namespace) -> int:
    api_id_text = os.environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
    if not api_id_text or not api_hash:
        print("Set TELEGRAM_API_ID and TELEGRAM_API_HASH environment variables first.", file=sys.stderr)
        return 2
    try:
        api_id = int(api_id_text)
    except ValueError:
        print("TELEGRAM_API_ID must be an integer.", file=sys.stderr)
        return 2

    input_path = Path(args.input)
    output_path = Path(args.output)
    if input_path.resolve() == output_path.resolve():
        print("Input and output files must be different; source file will not be overwritten.", file=sys.stderr)
        return 2

    with input_path.open("r", encoding="utf-8-sig", newline="") as f:
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
        telegram_col = find_header(headers, TELEGRAM_HEADERS)
        if not name_col or not phone_col:
            print("CSV must contain name and phone columns (for example: Имя;Телефоны).", file=sys.stderr)
            return 2
        if not telegram_col:
            telegram_col = "Telegram / ID"
            headers.append(telegram_col)
        rows = list(reader)

    # A phone can occur more than once; retain all rows so duplicate records are not silently lost.
    phone_to_rows: dict[str, list[int]] = {}
    contacts: list[InputPhoneContact] = []
    client_to_phone: dict[int, str] = {}
    next_id = 1
    for row_index, row in enumerate(rows):
        name = (row.get(name_col) or "").strip() or "Contact"
        for phone in split_phones(row.get(phone_col) or ""):
            if phone in client_to_phone.values():
                phone_to_rows.setdefault(phone, []).append(row_index)
                continue
            client_id = next_id
            next_id += 1
            client_to_phone[client_id] = phone
            phone_to_rows.setdefault(phone, []).append(row_index)
            first, _, last = name.partition(" ")
            contacts.append(InputPhoneContact(client_id=client_id, phone=phone, first_name=first[:64], last_name=last[:64]))

    if not contacts:
        print("No valid phone numbers found in the CSV.", file=sys.stderr)
        return 2
    if not args.upload_contacts:
        print(f"Found {len(contacts)} unique phone numbers. Nothing was sent to Telegram.")
        print("To explicitly upload these numbers for matching, rerun with --upload-contacts.")
        return 3

    matched: dict[str, tuple[int, str]] = {}
    session_path = Path(args.session)
    session_path.parent.mkdir(parents=True, exist_ok=True)
    async with TelegramClient(str(session_path), api_id, api_hash) as client:
        # Telethon asks for the phone, login code, and 2FA password if needed on first use.
        if not await client.is_user_authorized():
            await client.start()
        # Telegram's import method is designed for matching phone contacts. Keep batches bounded.
        for start in range(0, len(contacts), 100):
            batch = contacts[start : start + 100]
            result = await client(ImportContactsRequest(batch))
            users_by_id: dict[int, Any] = {u.id: u for u in result.users}
            for item in result.imported:
                phone = client_to_phone.get(item.client_id)
                user = users_by_id.get(item.user_id)
                if not phone or not user:
                    continue
                username = getattr(user, "username", None)
                value = f"@{username} | ID: {user.id}" if username else f"ID: {user.id}"
                matched[phone] = (user.id, value)
            if start + 100 < len(contacts):
                await asyncio.sleep(1.0)

    for phone, row_indexes in phone_to_rows.items():
        match = matched.get(phone)
        if not match:
            continue
        for row_index in row_indexes:
            # Preserve an existing manually entered value rather than silently replacing it.
            if not (rows[row_index].get(telegram_col) or "").strip():
                rows[row_index][telegram_col] = match[1]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=headers, delimiter=";", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)

    print(f"Input rows: {len(rows)}")
    print(f"Unique phone numbers checked: {len(contacts)}")
    print(f"Confirmed Telegram matches: {len(matched)}")
    print(f"Output written to: {output_path}")
    print("Review the output before importing it. Unmatched numbers may be hidden by Telegram privacy settings.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="CSV exported from My Contacts")
    parser.add_argument("-o", "--output", default="contacts_telegram_enriched.csv", help="Output CSV path")
    parser.add_argument("--session", default="private/telegram_user", help="Local Telethon session path; keep private")
    parser.add_argument(
        "--upload-contacts",
        action="store_true",
        help="Explicitly upload phone numbers from the CSV to Telegram for matching",
    )
    args = parser.parse_args()
    return asyncio.run(run(args))


if __name__ == "__main__":
    raise SystemExit(main())
