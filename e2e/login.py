"""Mint a Telegram session string, once.

Run interactively. It asks for a phone number, the login code Telegram sends, and a two-factor
password if the account has one, then prints a string that stands in for all three.

Once, deliberately. Telegram rate-limits repeated logins, and each one is a new authorised
session on the account.

    export MB_E2E_API_ID=... MB_E2E_API_HASH=...
    python3 e2e/login.py

Treat what it prints the way you would treat a session file, because that is what it is:
possession of the string is possession of the account.
"""

from telethon import TelegramClient
from telethon.sessions import StringSession

import config


def main() -> None:
    with TelegramClient(StringSession(), config.api_id(), config.api_hash()) as client:
        print()
        print("MB_E2E_SESSION=" + client.session.save())
        print()
        print("Put that in e2e/.env.local, which is git-ignored. Do not export it into a")
        print("shell history or paste it anywhere it will be kept.")


if __name__ == "__main__":
    main()
