"""Whether the stack can answer at all, asked before anything is scored.

**A gate that changes colour with machine load is not a gate.** The embeddings sidecar spends
minutes loading several gigabytes of weights while reporting itself healthy and refusing every
call, and more than one copy of it on a machine — one per worktree — makes a warm one slow enough
to miss the backend's socket timeout. Either way the backend answers *cannot answer right now*,
which arrives at a case as a failure indistinguishable from retrieval having got worse.

So readiness is established once, up front, and a run that cannot establish it **skips and names
the sidecar** rather than reporting a red case apiece. That keeps the distinction the whole suite
turns on: a red means the system is wrong, and a skip means nothing measured it.

**It is deliberately not a retry wrapped around the cases.** Retrying each case until it passes
would hide exactly the failure this suite exists to catch. What is retried here is a probe that
spends nothing and asserts nothing about an answer.

The probe goes through `docker compose exec` because the sidecar publishes no port — it is reached
by service name on the Compose network, and the evaluation stack shares the serving stack's copy.
"""

import subprocess
import time

import corpus
import harness

SERVICE = "embeddings"

# Short enough to be worth calling a timeout, long enough to cover a loaded machine. The backend's
# own client gives up sooner than this, so a probe answering slower than this is one the answer
# path would have failed on anyway.
PROBE_TIMEOUT_SECONDS = 120

# How long to wait for a cold sidecar. Measured rather than guessed: a first load on this machine
# runs to several minutes while the service reports healthy throughout.
READY_BUDGET_SECONDS = 900

# What a probe has to come back under for the cases to be worth running. Above it, the backend's
# own socket timeout is close enough that a case would fail for the machine's reasons rather than
# the code's.
WORKABLE_SECONDS = 60

# How many fixture messages the probe joins into one question. The draft shape's comparable read
# uses the last ten of a thread, which is the largest single text anything here embeds.
SAMPLE = 10

_PROBE = """
import json, sys, time, urllib.request
body = json.dumps({"texts": [sys.stdin.read()]}).encode()
request = urllib.request.Request(
    "http://localhost:8000/embed", data=body, headers={"Content-Type": "application/json"}
)
started = time.time()
urllib.request.urlopen(request, timeout=%d).read()
print(round(time.time() - started, 1))
"""


def embeddings_latency(payload: str) -> float | None:
    """
    How long the sidecar took to embed [payload], or `None` if it would not.

    **[payload] has to be the size the shapes send, not a word.** One short string embeds in a
    fraction of the time a shape's own call takes — the draft shape asks for a comparable read
    using ten joined messages as the question — so a probe on a token passes on a machine where
    every real call times out, which is a readiness check that reports ready and changes nothing.

    `None` covers every way of not answering — still loading, refusing, or the container being
    absent — because the suite does the same thing in all of them and a reader is told to look at
    the sidecar either way.
    """
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--profile",
            "eval",
            "exec",
            "-T",
            SERVICE,
            "python",
            "-c",
            _PROBE % PROBE_TIMEOUT_SECONDS,
        ],
        cwd=harness.compose_project_dir(),
        input=payload,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    try:
        return float(result.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return None


def running() -> list[str]:
    """
    Which of the evaluation stack's services are up.

    Asked before the probe so that a stack nobody started is answered at once. Waiting out the
    cold-load budget for a container that does not exist would make the commonest mistake — running
    the suite before bringing the profile up — cost a quarter of an hour to be told about.
    """
    result = subprocess.run(
        [
            "docker", "compose", "--profile", "eval",
            "ps", "--status", "running", "--format", "{{.Service}}",
        ],
        cwd=harness.compose_project_dir(),
        capture_output=True,
        text=True,
        check=False,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def probe_payload() -> str:
    """
    A question the size a shape asks with, built out of the fixtures themselves.

    The fixtures rather than invented filler, because the point is to measure what this suite is
    about to ask for: the same bodies, in the same language, at the length the widening read joins
    them to.
    """
    fixture = corpus.load()
    return "\n".join(message["body"] for message in fixture["messages"][:SAMPLE])


def await_embeddings(verbose: bool = True) -> float | None:
    """
    Wait for the sidecar to embed something, and say how long one embed cost.

    Returns `None` if it never answered inside the budget. The cost is returned rather than a
    boolean because it is the thing worth reporting: a run whose probe took most of a minute is
    one whose numbers were taken under contention, and a reader comparing two runs needs to know
    that before concluding anything from a difference between them.
    """
    payload = probe_payload()
    deadline = time.monotonic() + READY_BUDGET_SECONDS
    announced = False
    while time.monotonic() < deadline:
        latency = embeddings_latency(payload)
        if latency is not None:
            if verbose:
                print(f"eval stack: the sidecar embeds in {latency}s")
            return latency
        if verbose and not announced:
            print("eval stack: waiting for the embeddings sidecar to load its weights")
            announced = True
        time.sleep(15)
    return None
