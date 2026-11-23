"""Run-level wiring: the target guard, what an unconfigured run does, and one ask per case.

Three failures, handled three different ways on purpose.

A wrong or missing target is a hard exit for the whole run. It is the same guard the end-to-end
suite uses, reading the same variable, and a guard that degrades to a skip is not a guard.

A missing credential or store password is a skip, named. A run that cannot score must say what it
could not score rather than reporting green — an unrun gate is not a satisfied one.

A stack that cannot answer is a skip, named. A sidecar still loading its weights refuses every
call while reporting itself healthy, and that is the environment rather than the system — see the
`ready` fixture, which settles it once instead of letting it arrive as a red case apiece.

A case that cannot be asked while the stack *can* answer is a failure, not a skip. The suite is up
and configured by that point, so a request that will not go through is the thing being tested.

**Every case is asked once, here**, and a transient refusal is retried once — see `ask.answer`.
Four checks read that one answer: three scores and the routing assertion. Asking per check would
quadruple the model spend to learn nothing, because they are different readings of one request
rather than four requests.
"""

import json

import pytest

import ask
import corpus
import harness
import scoring
import stack


def pytest_configure(config):
    config.addinivalue_line("markers", "retrieval: scores whether the right material came back")
    config.addinivalue_line("markers", "citations: scores whether every claim rests on it")
    config.addinivalue_line("markers", "answers: scores what the prose says")
    config.addinivalue_line("markers", "routing: asserts which shape answered")


@pytest.fixture(scope="session", autouse=True)
def _target_guard():
    """
    Fail the whole run, once, if the target is not the local stack.

    Once rather than per test: the same missing variable failing every test reads as many
    problems, and the one line that matters scrolls away.
    """
    try:
        harness.target()
    except harness.ConfigError as exc:
        pytest.exit(f"eval refuses to run: {exc}", returncode=2)


@pytest.fixture(scope="session")
def configured():
    """The settings this suite needs, or a skip that names what is missing."""
    absent = harness.missing()
    if absent:
        pytest.skip(
            "Not configured, so retrieval was not scored in this run. "
            f"Missing: {', '.join(absent)}. "
            "The first two are in the repository .env for Compose; the model credential is what "
            "the answer path spends. See eval/README.md. Skipped rather than passed: a gate that "
            "did not run is not a gate that passed."
        )
    return harness


def cases() -> list[dict]:
    """The cases, as written. Read at collection time so that each one is its own test."""
    with open(harness.CASES_FILE, encoding="utf-8") as handle:
        return json.load(handle)["cases"]


@pytest.fixture(scope="session")
def ready(configured):
    """
    The sidecar answering, or a skip that names it.

    **Established once, before anything is asked, because otherwise it is indistinguishable from
    retrieval having got worse.** A cold embeddings sidecar reports itself healthy and refuses
    every call for minutes, and more than one copy on a machine makes a warm one slow enough to
    miss the backend's socket timeout — and the backend answers *cannot answer right now* either
    way, which reaches a case as a plain failure.

    A skip rather than a failure, for the reason every other skip here is one: nothing measured
    the system, so nothing can be concluded about it. The two local-tier skills both say to read
    the skip lines and treat a skipped evaluation set as *the gate did not run*.

    The probe's cost is printed rather than only checked. Two runs whose numbers differ are only
    comparable if both were taken under comparable conditions, and this is the one measurement
    that says whether they were.
    """
    up = stack.running()
    wanted = [stack.SERVICE, harness.BACKEND_SERVICE]
    absent = [service for service in wanted if service not in up]
    if absent:
        pytest.skip(
            f"The evaluation stack is not up — {', '.join(absent)} is not running, so nothing "
            "was scored in this run. Bring it up with "
            "`docker compose --profile eval up -d --build backend-eval`, which a full dev deploy "
            "does for you. Answered at once rather than waited out: a container that does not "
            "exist is never going to load its weights."
        )
    latency = stack.await_embeddings()
    if latency is None:
        pytest.skip(
            "The embeddings sidecar never answered, so nothing was scored in this run. It loads "
            "several gigabytes of weights while reporting itself healthy, and a machine running "
            "one copy per worktree can leave it unable to answer at all — check "
            f"`docker compose --profile eval logs {stack.SERVICE}`. Skipped rather than failed: "
            "a stack that cannot answer says nothing about whether retrieval got worse."
        )
    if latency > stack.WORKABLE_SECONDS:
        pytest.skip(
            f"The sidecar embeds in {latency}s, over the {stack.WORKABLE_SECONDS}s this suite "
            "will score under. The backend's own client gives up sooner than that, so the cases "
            "would fail for the machine's reasons rather than the code's. Usually another "
            "worktree's stack is holding the processor — bring one down and re-run."
        )
    return latency


@pytest.fixture(scope="session")
def graded(ready) -> dict[str, dict]:
    """
    Every case asked once, with all three dimensions scored and its routing recorded.

    Scored here rather than in each test module because every reading shares one request, and
    because the report at the end of the run has to cover every case whichever module failed
    first. A test then asserts against a number somebody else can read in the summary, which is
    what makes two runs comparable.
    """
    corpus.ensure()
    graded: dict[str, dict] = {}
    for case in cases():
        # **One case that cannot be asked must not take the run down with it.** Raising here would
        # abort the fixture, error every test in every module, and lose the scores for the cases
        # that did answer — which is the report, on the run where it is most wanted. A backend
        # refusing one request is also the likeliest single failure: `/answer` answers 503 when
        # the sidecar or the model is unreachable, and that is one case's result rather than the
        # suite's.
        try:
            response, attempts, asking = ask.answer(case)
        except ask.AskError as exc:
            # **Every dimension a case opted out of stays ungraded here too.** A case that cannot
            # be asked fails on `error` whatever these say, so what the zeroes would move is the
            # headline mean the suite exists to compare between runs — a dimension the fixture
            # declares ungraded taking a 0.0 is the same arithmetic fault as one taking a free 1.0.
            scoring.record(
                scoring.Scored(
                    case=case["id"],
                    retrieval=None if not case.get("scores_retrieval", True) else 0.0,
                    citations=None if not case.get("scores_citations", True) else 0.0,
                    answer=None if not case.get("scores_answer", True) else 0.0,
                    notes=[f"could not be asked: {exc}"],
                )
            )
            graded[case["id"]] = {
                "case": case,
                "response": {},
                "attempts": [],
                "prose": "",
                "retrieval": None if not case.get("scores_retrieval", True) else 0.0,
                "citations": None if not case.get("scores_citations", True) else 0.0,
                "answer": None if not case.get("scores_answer", True) else 0.0,
                "notes": [f"could not be asked: {exc}"],
                "delivered": False,
                "error": str(exc),
            }
            continue

        prose = ask.prose(response)
        grades_retrieval = case.get("scores_retrieval", True)
        retrieved = attempts[-1]["retrieved"] if attempts else []

        retrieval, retrieval_notes = (
            scoring.retrieval_score(
                retrieved,
                case["retrieval"]["must_include"],
                case["retrieval"]["must_exclude"],
            )
            if grades_retrieval
            else (None, [])
        )
        # Opted out the same way the other two dimensions are, for a case whose request never
        # reaches a shape. A subject that resolves to a question has retrieved nothing, generated
        # nothing and cited nothing, so there is no grounding verdict to read — and the score for
        # an unreported attempt is zero by design, which here would be a case failing for having
        # behaved correctly. The case says so in its own `note`, as the others do.
        grades_citations = case.get("scores_citations", True)
        citations, citation_notes = (
            scoring.citation_score(attempts) if grades_citations else (None, [])
        )
        # Opted out the same way retrieval is, and for the same reason. A case naming no figure to
        # find and none to avoid has nothing for `answer_score` to weigh, and scoring it 1.0 would
        # be an assertion that cannot fail feeding a free mark into the headline mean — a run that
        # graded three cases reading as one that graded five.
        grades_answer = case.get("scores_answer", True)
        answer, answer_notes = (
            scoring.answer_score(
                prose,
                case["answer"]["must_mention"],
                case["answer"]["must_not_mention"],
            )
            if grades_answer
            else (None, [])
        )

        notes = asking + retrieval_notes + citation_notes + answer_notes
        # Reported whatever else is wrong with the case, because it explains the rest: a status
        # outcome means the request settled with no prose, and every other dimension then reads
        # as a failure of its own. Which of its several causes it was is only partly here: the key
        # beside it separates the ones that have a key of their own, and the trace carries no
        # fault, so the backend's fault line is what names the rest.
        # **What the wire offers a reader, against what the runner let through.** The two are
        # produced by different code — the trace by the runner, the claims by the presentation
        # mapping — so a divergence means the mapping dropped or mangled citations on their way
        # out, which nothing else here would notice: the grounding verdict is about what the model
        # wrote, not about what survived the trip to the interface.
        #
        # **Compared against what the runner says it kept, which it reports rather than leaving to
        # be derived.** On the survivable fault — some claims ungrounded, the rest standing — the
        # runner deliberately drops those claims on the way out, so the raw cited set would read
        # that repair as a mapping defect, on exactly the answers it handled correctly. Subtracting
        # the ungrounded set does not fix it either: a claim is dropped whole rather than stripped
        # of the citation that failed, so one resting on a retrieved span and an unretrieved takes
        # the good identifier with it, and `cited` is flat — nothing here can tell which of its
        # entries shared a claim. The runner reports `survived` for that reason.
        #
        # **Asked of every outcome, where it used to be asked only of a text answer.** The
        # exemption was there because a menu carried no claims at all, so the comparison had
        # nothing to read and passed every drafting case whatever the mapping did. An alternative
        # now carries its own, and an outcome that carries none compares two empty sets — which is
        # the answer that outcome should give rather than one it is excused from.
        wire = sorted(set(ask.cited(response)))
        survived = sorted(set(attempts[-1]["survived"])) if attempts else []
        delivered = wire == survived
        if not delivered:
            notes.append(f"let {survived} through but sent {wire} to the interface")
        if case["shape"] is None:
            # A case naming no shape asserts the request settled before any ran, so the note is
            # the converse of the one below: what is wrong is that something answered at all.
            if attempts:
                notes.append(f"{attempts[-1]['shape']} answered, where no shape should have run")
        elif attempts and attempts[-1]["shape"] != case["shape"]:
            notes.append(f"bound to {attempts[-1]['shape']}, not {case['shape']}")
        if ask.outcome_kind(response) != case["answer"]["outcome"]:
            notes.append(
                f"outcome was {ask.outcome_kind(response)}"
                + (f" ({ask.status_key(response)})" if ask.status_key(response) else "")
                + f", not {case['answer']['outcome']}"
            )
        scoring.record(
            scoring.Scored(
                case=case["id"],
                retrieval=retrieval,
                citations=citations,
                answer=answer,
                notes=notes,
            )
        )
        graded[case["id"]] = {
            "case": case,
            "response": response,
            "attempts": attempts,
            "prose": prose,
            "retrieval": retrieval,
            "citations": citations,
            "answer": answer,
            "notes": notes,
            "delivered": delivered,
            "error": None,
        }
    return graded


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    """
    The run's three scores, printed whether it passed or failed.

    On failure as well, because a regression is read by comparing this run's numbers with the last
    one's — and a report that only appears on green is absent exactly when it is wanted.
    """
    lines = scoring.report()
    terminalreporter.write_line("")
    for line in lines:
        terminalreporter.write_line(line)
