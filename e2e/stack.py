"""Reading the running stack, through Compose rather than through the services themselves.

Asked of `docker compose ps` because that is the side that knows what was *meant* to be
running. A service that exited is absent from its own health endpoint in exactly the way a
service that was never defined is, and those two need to be told apart.
"""

import json
import subprocess

import config

# The five containers of the stack. Named here rather than read from the Compose file, so that
# a service silently dropped from `docker-compose.yml` fails this list instead of agreeing
# with it.
SERVICES = ("postgres", "embeddings", "ingest", "backend", "bot")


class StackError(RuntimeError):
    """Compose could not be asked. Distinct from the stack being unhealthy."""


def _compose(*args: str) -> str:
    try:
        result = subprocess.run(
            ("docker", "compose", *args),
            cwd=config.compose_project_dir(),
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except FileNotFoundError as exc:
        raise StackError("`docker` is not on PATH, so the stack cannot be inspected.") from exc
    except subprocess.TimeoutExpired as exc:
        raise StackError("`docker compose` did not answer within 60s.") from exc

    if result.returncode != 0:
        raise StackError(f"`docker compose {' '.join(args)}` failed: {result.stderr.strip()}")
    return result.stdout


def states() -> dict[str, str]:
    """
    Each defined service mapped to its container state, with the absent ones included.

    Absent is a state and not a missing key, because the reading a caller most needs is "which
    of the five is not there" — and a dictionary that silently lacks an entry answers that
    question only if the caller remembered to ask it.
    """
    # `--all` so a service that exited is reported rather than omitted, which is the whole
    # distinction this module exists to preserve.
    raw = _compose("ps", "--all", "--format", "json")

    # Compose emits one JSON object per line, not a JSON array.
    running = {}
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        entry = json.loads(line)
        running[entry.get("Service", "")] = entry.get("State", "unknown")

    return {service: running.get(service, "absent") for service in SERVICES}
