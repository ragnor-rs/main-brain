"""The Telegram user account this suite drives is usable.

The credential rail is checked before anything depends on it, because its failure mode is
quiet. A session string that has been revoked, or minted against a different application,
does not announce itself — it surfaces later as a bot that never answers, which reads as a
defect in the bot.
"""

import asyncio

import pytest
from telethon import TelegramClient
from telethon.sessions import StringSession


@pytest.mark.telegram
def test_the_user_session_is_authorized(telegram_account):
    async def check():
        client = TelegramClient(
            StringSession(telegram_account.session_string()),
            telegram_account.api_id(),
            telegram_account.api_hash(),
        )
        await client.connect()
        try:
            authorized = await client.is_user_authorized()
            assert authorized, (
                "MB_E2E_SESSION connected but is not authorized. The string is stale or was "
                "minted against a different application — re-run `python3 e2e/login.py`."
            )
            # Asked for as well as asserted on: a session can be authorized and still belong
            # to an account nobody expected, and the whole suite plants messages into
            # whichever account this is.
            me = await client.get_me()
            assert me is not None, "Authorized, but Telegram returned no account for it."
        finally:
            await client.disconnect()

    asyncio.run(check())
