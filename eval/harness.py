"""Configuration for the evaluation set, all of it from the environment.

Scoring retrieval needs real embeddings and answering needs a model credential, so this suite
cannot run in CI and is not meant to: CI is credential-free, and an evaluation set there against
a mocked embedding model would measure the mock. It runs locally, in the two places the
end-to-end suite runs — after a full dev deploy, and before the reviewing pass waits on CI.

**Named `harness` rather than `config`, unlike its counterpart in `e2e/`.** Both directories are
pytest rootdirs with no package marker, so both would offer a top-level module called `config`,
and one `pytest e2e/ eval/` would import whichever came first for both suites. The failure is a
suite silently reading the other's settings, which is worth one unusual filename to make
impossible.

**It reads the repository's own `.env`.** The store password and the shared secret are already
there for Compose, and a second copy of either is a copy that drifts — the suite would then be
scoring a backend it cannot reach, for a reason that looks like a defect in the stack.
"""

import hashlib
import os

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)

# Settings may come from the environment, from eval/.env.local, or from the repository's .env.
# Both files are git-ignored and nothing reads them but this module, at runtime.
_LOCAL_ENV = os.path.join(_HERE, ".env.local")
_COMPOSE_ENV = os.path.join(_ROOT, ".env")


def _load_env_file(path: str) -> None:
    if not os.path.isfile(path):
        return
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip().strip("'\"")
            # A shell-style `export NAME=value` line is the likeliest thing to be pasted here.
            if key.startswith("export "):
                key = key[len("export ") :].strip()
            if not value:
                continue
            # A real value already in the environment wins, so a one-off export overrides both
            # files without an edit. The order below makes eval/.env.local win over .env for the
            # same reason: the more specific file is the one somebody wrote for this suite.
            if not os.environ.get(key, "").strip():
                os.environ[key] = value


_load_env_file(_LOCAL_ENV)
_load_env_file(_COMPOSE_ENV)


class ConfigError(RuntimeError):
    """A required setting is missing — raised with the exact command that produces it."""


def _required(name: str, how: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(f"{name} is not set. {how}")
    return value


def _optional(name: str, default: str = "") -> str:
    return os.environ.get(name, "").strip() or default


DEV = "dev"


def target() -> str:
    """
    Which stack the suite may touch. No default, and only one accepted value.

    **The same variable the end-to-end suite reads, which is what makes it the same guard.** Two
    variables would be two things to set and one thing to forget, and the forgotten one would be
    the suite that writes.

    This suite writes to a store of its own rather than to a Telegram account, so what it would
    damage pointed elsewhere is narrower than what the other one would. It is refused anyway:
    production runs the corpus the client is shown, and a suite that seeds fixtures has no
    business holding a connection to it.
    """
    value = _required("MB_E2E_TARGET", f"Set MB_E2E_TARGET={DEV}. The suite runs nowhere else.")
    if value != DEV:
        raise ConfigError(
            f"MB_E2E_TARGET must be {DEV!r}, got {value!r}. Refusing to run: this suite seeds a "
            "fixture corpus and scores answers against it, and production holds the live one."
        )
    return value


def compose_project_dir() -> str:
    """Where `docker-compose.yml` lives — the repository root, one level up from here."""
    return _ROOT


# --- the evaluation stack -----------------------------------------------------------------
#
# Two services under the `eval` Compose profile, both published on the loopback address. The
# serving backend is unreachable from here and stays that way.

BACKEND_SERVICE = "backend-eval"


def backend_url() -> str:
    """Where the backend under evaluation answers."""
    return f"http://127.0.0.1:{_optional('EVAL_BACKEND_PORT', '18080')}"


def store_dsn() -> str:
    """The evaluation store, which the suite seeds and reads the derived passes back from."""
    return (
        f"host=127.0.0.1"
        f" port={_optional('EVAL_POSTGRES_PORT', '5433')}"
        f" dbname={_optional('POSTGRES_DB', 'mainbrain')}"
        f" user={_optional('POSTGRES_USER', 'mainbrain')}"
        f" password={_required('POSTGRES_PASSWORD', 'It is in the repository .env, for Compose.')}"
    )


def backend_secret() -> str:
    """The one hop the backend serves. Without it every request is refused at the edge."""
    return _required("BACKEND_SECRET", "It is in the repository .env, for Compose.")


# --- what an unconfigured run is missing ---------------------------------------------------
#
# Checked as a group rather than demanded, so that a run which cannot score still says what it
# could not score and why. A suite that measured nothing must not exit looking like one that
# measured everything.


def missing() -> list[str]:
    """
    The settings this suite cannot run without, named as a deployer would set them.

    Empty exactly when a run can score. Derived from the values rather than recorded beside them,
    so it cannot disagree with what was read.
    """
    needed = ("POSTGRES_PASSWORD", "BACKEND_SECRET", "ANTHROPIC_API_KEY")
    return [name for name in needed if not _optional(name)]


def snapshot_max_age_hours() -> int:
    """
    How long a prepared corpus is reused before it is rebuilt.

    The fixtures are positioned in time relative to the moment they were seeded — *what comes
    next* has to be ahead of now to be retrievable at all — so a snapshot does go stale, and a
    suite scoring an old one would report a shape failing for having nothing upcoming.

    Rebuilding costs a recognition sweep over the fixtures, which is the one part of this that
    spends the model budget. A day is the compromise: long enough that a working session's runs
    pay for it once, short enough that nothing is scored against a corpus whose future is in the
    past.
    """
    raw = _optional("EVAL_SNAPSHOT_MAX_AGE_HOURS", "24")
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigError(
            f"EVAL_SNAPSHOT_MAX_AGE_HOURS must be a whole number of hours, got {raw!r}"
        ) from exc


def derivation_timeout_seconds() -> int:
    """
    How long a rebuild waits for the stack to derive the fixtures.

    **Sized against the provider's batch turnaround, not against local compute.** Recognition's
    classification goes to the batch endpoint, which is allowed an hour for most submissions and up
    to a day for any of them — so a bound picked for a synchronous pass expires while the work is
    still being done, and reports a corpus that was never derived as a corpus that could not be.
    The fixtures are one submission's worth, so it is one turnaround rather than several.

    Generous costs nothing on the path that matters: the wait polls and returns as soon as the marks
    land, so this is the point at which it stops being worth waiting, not an estimate of how long
    the work takes. It is settable because the one number that cannot be reasoned about from here is
    how loaded the provider is.
    """
    raw = _optional("EVAL_DERIVATION_TIMEOUT_SECONDS", "5400")
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigError(
            f"EVAL_DERIVATION_TIMEOUT_SECONDS must be a whole number of seconds, got {raw!r}"
        ) from exc


# --- the fixtures --------------------------------------------------------------------------

FIXTURES = os.path.join(_HERE, "fixtures")
CORPUS_FILE = os.path.join(FIXTURES, "corpus.json")
CASES_FILE = os.path.join(FIXTURES, "cases.json")


def corpus_digest() -> str:
    """
    What identifies the corpus currently in the store.

    The fixture file's own bytes, so that any edit to it rebuilds the snapshot and nothing else
    does. A count of records would miss an edited body; a modification time would rebuild on a
    checkout that changed nothing.
    """
    with open(CORPUS_FILE, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()
