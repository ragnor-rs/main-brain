"""Asking the backend, and reading back what the attempt retrieved.

**Through `POST /answer`, which is the route a request uses.** The suite holds no runner, no store
connection on the answer path and no shape — so it cannot pass by bypassing a stage, which is the
property that makes it a gate rather than a second implementation of the thing it is scoring.

**What was retrieved comes from the log, because the response does not carry it.** An ungrounded
citation and an empty corpus arrive at an interface as the same reply, deliberately: in both cases
there is nothing the system can stand behind. That is right for the bot and useless for scoring,
so the backend writes one line per attempt and this reads it.

The coupling is real and worth naming: `answer-trace` and its six keys are a contract between
`LoggedAnswerTrace` and this file, asserted on the Kotlin side by `LoggedAnswerTraceTest`. A
refactor that renames them breaks a gate that runs nowhere near CI.
"""

import json
import subprocess
import time
import urllib.error
import urllib.request

import harness

MARKER = "answer-trace"

# The message key the backend answers with when the sidecar or the model is not reachable. Read
# out of the body rather than off the status code, because 503 is also what a backend with no
# answer path configured returns — and that one is not transient.
TRANSIENT = "CANNOT_ANSWER_RIGHT_NOW"

# Long enough for a sidecar under load to finish whatever it was doing, short enough that the
# whole set retrying still fits inside a deploy.
RETRY_PAUSE_SECONDS = 20

# **Sized for the research shape, which is minutes rather than seconds.** It is the one shape that
# searches and reads inside the call, at the highest effort this build asks for, and the adapter
# resumes a paused turn rather than giving up — so a single request legitimately runs far longer
# than any corpus-reading case. Measured: 300s cut one off mid-answer after every other case had
# scored, which is a red that says nothing about retrieval or grounding. Matched to the corpus
# wait, because both are this suite waiting on work it asked for rather than on a fault.
READ_TIMEOUT_SECONDS = 900


class AskError(RuntimeError):
    """The backend could not be reached, or answered in a way nothing can be scored from."""


def answer(case: dict) -> tuple[dict, list[dict], list[str]]:
    """
    [case] asked of the backend: the response, every attempt it reported, and what is worth saying
    about how the asking went.

    **The trace lines are taken as those the log grew by**, counted before the request and read
    after it. Timestamps are not used: `docker compose logs --since` resolves to the second, and
    two cases asked inside one second would each claim the other's attempts. Counting is exact,
    and it holds because nothing else calls this backend.

    **A `503` is tried once more, and the run says that it was.** That status is the backend
    naming a transient condition — the sidecar or the model is not answering *right now* — and the
    commonest cause is honest contention: a cold embeddings sidecar refuses every call for minutes,
    and a warm one on a loaded machine can still miss a socket timeout. Failing the gate on that
    would teach a reader that red means try again, which is the one thing a gate must not teach.
    A second refusal fails, because a backend that cannot answer twice is not a slow one.

    Nothing else is retried. A 400 is a malformed request and a 401 is a wrong secret; both would
    give the same answer for ever.
    """
    stated = {
        "text": case["text"],
        "command": case.get("command"),
        "subjectName": case.get("subject_name"),
    }
    before = len(_trace_lines())
    notes: list[str] = []
    try:
        response = _post(stated)
    except AskError as exc:
        if TRANSIENT not in str(exc):
            raise
        notes.append(f"the first ask was refused as {TRANSIENT} and was retried once")
        time.sleep(RETRY_PAUSE_SECONDS)
        response = _post(stated)
    attempts = [json.loads(line.split(MARKER, 1)[1].strip()) for line in _trace_lines()[before:]]
    return response, attempts, notes


def _post(body: dict) -> dict:
    # Absent rather than null, which is how the bot sends them: `explicitNulls` is off on the
    # other side, so an omitted field and an explicit null are the same request.
    stated = {key: value for key, value in body.items() if value is not None}
    payload = json.dumps(stated).encode("utf-8")
    request = urllib.request.Request(
        f"{harness.backend_url()}/answer",
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Main-Brain-Secret": harness.backend_secret(),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=READ_TIMEOUT_SECONDS) as reply:
            return json.loads(reply.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # The body carries a message key rather than a sentence, which is exactly what a report
        # needs: 401 with REQUEST_UNAUTHORISED is a wrong secret, and 503 with
        # CANNOT_ANSWER_RIGHT_NOW is the sidecar or the model being unreachable.
        raise AskError(f"{exc.code} from /answer: {exc.read().decode('utf-8', 'replace')}") from exc
    except urllib.error.URLError as exc:
        raise AskError(
            f"Could not reach {harness.backend_url()}: {exc.reason}. The `eval` profile publishes "
            f"it on the loopback address — bring it up with "
            f"`docker compose --profile eval up -d {harness.BACKEND_SERVICE}`."
        ) from exc
    # **A read timeout is an `OSError`, not a `URLError`, and that distinction cost a whole run.**
    # `urllib` wraps a connection it could not make, but a socket that goes quiet partway through a
    # reply raises `TimeoutError` bare — so it escaped this function, escaped [AskError], escaped
    # the per-case guard whose comment promises one case cannot take the run down, and errored
    # every test in every module after the earlier cases had already scored. Caught last, because
    # `URLError` is itself an `OSError` and would be swallowed by this clause if it came first.
    except (TimeoutError, OSError) as exc:
        raise AskError(
            f"No reply from /answer within {READ_TIMEOUT_SECONDS}s: {exc}. The shape that reads "
            f"the open web runs a tool loop at the highest effort this build asks for, and a "
            f"resumed turn is charged again — so this is the one case that can legitimately take "
            f"minutes. Check `docker compose --profile eval logs {harness.BACKEND_SERVICE}` for "
            f"whether the model was still answering."
        ) from exc


def _trace_lines() -> list[str]:
    """Every trace line the backend has written so far, oldest first."""
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--profile",
            "eval",
            "logs",
            "--no-log-prefix",
            "--no-color",
            harness.BACKEND_SERVICE,
        ],
        cwd=harness.compose_project_dir(),
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise AskError(f"Could not read the backend log: {result.stderr.strip()}")
    return [line for line in result.stdout.splitlines() if _is_trace(line)]


def _is_trace(line: str) -> bool:
    """
    Whether [line] is a trace line rather than a line that merely contains the marker.

    **The marker alone stopped being enough once model output could reach the same stream.** The
    backend logs an unreadable reply verbatim, bounded, so a reply that happened to contain the
    marker's own text would be selected here and then fail `json.loads` — taking down a run with a
    parse error that says nothing about the answer being scored. The payload is a JSON object, so
    requiring one is the cheap half of the contract to check, and it is the half a sentence of prose
    cannot satisfy by accident.
    """
    return MARKER in line and line.split(MARKER, 1)[1].lstrip().startswith("{")


def prose(response: dict) -> str:
    """
    Everything [response] actually says, joined into one string.

    **The answer's own text alone, because it is exactly what a reader is shown**, which is what
    the answer scores are supposed to be about. Whether the identifiers were delivered is a
    separate question, and `cited` below is what answers it.

    **A text answer arrives as parts, and every part's words count.** The wire marks which of them
    are headings so that an interface can emphasise one; what a part is does not change whether it
    was said, so the kinds are flattened here and a heading's words are scored like any others — a
    case asking whether a body of work was named is answered as readily by the heading naming it as
    by a finding under it. Read off every part rather than off a field, because a reader is shown
    all of them: a version of this that knew only one kind would score an answer's sections as
    silence, which scores a working retrieval as a total failure.

    **The two outcomes carry their assertions differently, and the scores follow the reader rather
    than the wire.** On a text answer the backend renders every assertion back into the prose in
    the place the model wrote it, so the findings are in there along with the text around them, and
    `claims` repeating them beside it would score each one twice. On a menu they are *not* in the
    prose: an alternative is a message somebody copies and sends, so it carries the composed text
    alone, and its assertions reach the reader beside it rather than in it. So a draft case must
    assert on what the draft itself says — a figure the model wrote only in a cited line is
    correctly not found here, because it is not in the thing somebody sends.

    One string because the answer checks ask whether a figure was reported, and which variant of a
    draft reported it is not something a fixture can fix: the shape promises alternatives, and
    asserting on the first would make a reordering look like a regression.
    """
    outcome = response.get("outcome", {})
    kind = outcome.get("type")
    if kind == "text":
        return "\n".join(block.get("text", "") for block in outcome.get("body", []))
    if kind == "menu":
        return "\n".join(option.get("prose", "") for option in outcome.get("options", []))
    return ""


def cited(response: dict) -> list[str]:
    """
    Every identifier [response] offers a reader, across all of its claims.

    What an interface would take somebody to the message with. Read apart from the text because a
    citation that reached the wire and a citation that was asserted in prose are different things:
    only the first can be resolved.

    **Both outcomes that carry claims, which is what makes the delivery check mean anything on a
    menu.** A text answer carries its claims beside the prose and an alternative carries its own, so
    reading only the first scored every drafting case as having delivered whatever it was given —
    including nothing at all.

    The identifier alone. A citation also carries who wrote the material and when, which is what an
    interface shows in its place; neither resolves to anything, so neither belongs in a comparison
    against what the runner says it let through.
    """
    outcome = response.get("outcome", {})
    kind = outcome.get("type")
    if kind == "text":
        claims = outcome.get("claims", [])
    elif kind == "menu":
        claims = [
            claim for option in outcome.get("options", []) for claim in option.get("claims", [])
        ]
    else:
        return []
    return [cite.get("span", "") for claim in claims for cite in claim.get("cites", [])]


def outcome_kind(response: dict) -> str:
    """Which of the three outcomes came back, or `unknown` for a body nothing can read."""
    return response.get("outcome", {}).get("type", "unknown")


def status_key(response: dict) -> str | None:
    """The message key a status outcome carries, which is why a case got no prose."""
    outcome = response.get("outcome", {})
    return outcome.get("key") if outcome.get("type") == "status" else None
