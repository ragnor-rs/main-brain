"""Configuration for the end-to-end harness, all of it from the environment.

The harness drives a real Telegram **user** account. There is no mock layer and there cannot
be one: the Bot API can only act as the bot side of a conversation, so the human side has to
be an actual user session. That is also why this suite is Python beside a Kotlin codebase —
the client libraries for a user account live here.

Telegram is both a source and an interface in this system, so one session does double duty: it
plants a message for ingestion to find, and it asks the assistant about it afterwards. The
planting half is why the target guard below is not a formality.
"""

import os

# Settings may come from the environment or from e2e/.env.local, which is git-ignored. The
# file exists so credentials need not be exported into a shell, pasted into a chat, or
# committed — nothing reads it but this module, at runtime.
_LOCAL_ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env.local")


def _load_local_env() -> None:
    if not os.path.isfile(_LOCAL_ENV):
        return
    with open(_LOCAL_ENV, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip().strip("'\"")
            # A shell-style `export NAME=value` line is the likeliest thing to be pasted here.
            # Accepting it beats a setting that sits in the file, looks right, and reads as
            # missing.
            if key.startswith("export "):
                key = key[len("export ") :].strip()
            # Blank assignments are skipped rather than set. The precedence rule below treats
            # empty and unset alike, so this changes no outcome today — it keeps the file from
            # putting empty strings into the environment, which a stricter rule would trip on.
            if not value:
                continue
            # A real value already in the environment wins, so a one-off export overrides the
            # file without editing it.
            if not os.environ.get(key, "").strip():
                os.environ[key] = value


_load_local_env()


class ConfigError(RuntimeError):
    """A required setting is missing — raised with the exact command that produces it."""


def _required(name: str, how: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(f"{name} is not set. {how}")
    return value


def _optional(name: str) -> str:
    return os.environ.get(name, "").strip()


DEV = "dev"


def target() -> str:
    """
    Which stack the suite may touch. No default, and only one accepted value.

    There are two environments — dev and production — and this suite runs on the first.
    Production holds the live corpus and the logged-in ingest session, so pointing the suite
    at it would write test material into the corpus the client is shown; a Telegram account
    cannot be rolled back the way a database can.

    So the variable is required and checked rather than defaulted. One typo is not an
    acceptable distance from writing to a live account.
    """
    value = _required("MB_E2E_TARGET", f"Set MB_E2E_TARGET={DEV}. The suite runs nowhere else.")
    if value != DEV:
        raise ConfigError(
            f"MB_E2E_TARGET must be {DEV!r}, got {value!r}. Refusing to run: this suite writes "
            "to a real Telegram account, and production ingests into the live corpus."
        )
    return value


def compose_project_dir() -> str:
    """Where `docker-compose.yml` lives — the repository root, one level up from here."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# --- the Telegram user account ----------------------------------------------------------
#
# Optional as a group. The stack checks need none of it, and a run that can only do half the
# suite is more useful than one that refuses to start — provided the half it skips says so.


def has_telegram_account() -> bool:
    return all(_optional(name) for name in ("MB_E2E_API_ID", "MB_E2E_API_HASH", "MB_E2E_SESSION"))


def api_id() -> int:
    raw = _required("MB_E2E_API_ID", "Create an application at https://my.telegram.org/apps.")
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigError(f"MB_E2E_API_ID must be numeric, got {raw!r}") from exc


def api_hash() -> str:
    return _required("MB_E2E_API_HASH", "Create an application at https://my.telegram.org/apps.")


def session_string() -> str:
    return _required(
        "MB_E2E_SESSION",
        "Run `python3 e2e/login.py` once and keep the string it prints.",
    )


def bot_username() -> str:
    """The assistant bot under test. Needed once there is a bot to talk to."""
    return _required("MB_E2E_BOT", "Set it to the bot on the dev stack, e.g. @my_dev_bot.")


# --- a subject the corpus actually holds -------------------------------------------------
#
# Three of the request shapes are reached by a command with a name after it, and the name has
# to be one the corpus knows or the stack correctly answers that it could not establish a
# subject — which is a true reply and tests nothing about the shape.
#
# **Configured rather than written down, and that is not a convenience.** Every name in this
# corpus belongs to a real person or organisation, and this repository is not private. A
# counterpart's name committed here would be exactly the disclosure the whole arrangement
# avoids. So it arrives the way the credentials do, from a git-ignored file, and the cases that
# need it skip by name when it is absent.


def has_subject() -> bool:
    return bool(_optional("MB_E2E_SUBJECT"))


def subject() -> str:
    """A name the corpus holds, for the shapes that are asked about somebody.

    One name for all of them rather than one each. Any counterpart with history answers every
    shape — what differs is how much each finds, which is a question about the archive and not
    about whether the shape renders.
    """
    return _required(
        "MB_E2E_SUBJECT",
        "Set it to a counterpart the corpus holds, as you would type it to the bot.",
    )
