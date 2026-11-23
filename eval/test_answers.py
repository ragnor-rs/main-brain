"""What the prose says, and which outcome it came back as.

**The dimension that is a judgement rather than a measurement**, which is why it is scored apart
from retrieval. It is asserted on figures and organisation names rather than on wording: a price or
a batch number survives any rephrasing, while a paraphrase check fails on a better answer and, over
a Russian corpus, mostly tests morphology.

The outcome kind is asserted beside it because the two fail together and for different reasons. A
shape promising selectable drafts that returns one is a defect no reader can see — the interface
renders what it is given, and a menu of one looks exactly like a shape that never offered a choice.
"""

import pytest

import ask
import conftest


@pytest.mark.answers
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_outcome(case, graded):
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked: {scored['error']}")
    response = scored["response"]
    assert ask.outcome_kind(response) == case["answer"]["outcome"], (
        f"{case['id']} came back as {ask.outcome_kind(response)}"
        + (f" ({ask.status_key(response)})" if ask.status_key(response) else "")
        + f", not {case['answer']['outcome']}. A status outcome here means the request settled "
        "with no prose at all. Several unrelated things do that — nothing retrieved, a shape whose "
        "answer is its assertions refusing one that made none, and a reply the format reader "
        "rejected on an attempt that was not the last — and those arrive under one key, because "
        "the trace carries no fault. The key beside the outcome above separates the rest: a name "
        "that did not resolve, and a request given up on a reply nobody could read, each say so in "
        "a key of their own. The backend's own fault line names what is left."
    )
    # **A case expecting a status outcome says which one, because the kind alone is several
    # unrelated things.** The sentence above lists them: nothing retrieved, an answer that asserted
    # nothing, a reply nobody could read, a name that did not resolve. A case whose whole subject is
    # what a name resolves to would pass on any of those while asserting none of them. Read only
    # where the case names a key, since every other status outcome in this set is a failure rather
    # than an expectation and has no key to name.
    wanted = case["answer"].get("status_key")
    if wanted:
        assert ask.status_key(response) == wanted, (
            f"{case['id']} settled under {ask.status_key(response)}, not {wanted}. The outcome "
            "kind matched, so the request did settle with no prose — what differs is why, and for "
            "this case that is the whole of what it checks."
        )


@pytest.mark.answers
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_answer(case, graded):
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked: {scored['error']}")
    if scored["answer"] is None:
        pytest.skip(
            f"{case['id']} names no figure to find and none to avoid, so its prose is not graded. "
            "Skipped rather than passed: an assertion with nothing to assert reports the same "
            "green as one that checked something, and this dimension is a judgement already."
        )
    assert scored["answer"] == 1.0, (
        f"{case['id']} answer {scored['answer']:.2f}: {'; '.join(scored['notes'])}"
    )
