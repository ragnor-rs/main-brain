"""Which pipeline answered.

**Asserted rather than scored, because it is not a matter of degree.** A request either bound to
the shape it names or it did not, and a fraction over the set would hide one wrong answer behind
the ones beside it.

**A case may name no shape, and then this asserts the converse.** A subject that resolves to a
question settles the request in resolution, so nothing is bound, nothing is retrieved and nothing
is spent — and an attempt reported for such a case means a name the build must ask about was
answered from one of the things it might have meant. Both directions are the same check: what
answered is what the case says answered.

Worth its own check because the failure is invisible in every other dimension. A request routed to
the wrong shape retrieves well — the wrong shape's retrieval works perfectly — cites what it
retrieved, and composes fluent prose answering a question nobody asked. Retrieval, citations and
the answer check can all pass on it.
"""

import pytest

import conftest


@pytest.mark.routing
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_routing(case, graded):
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked, so nothing routed: {scored['error']}")
    if case["shape"] is None:
        # **A case naming no shape is asserted the other way round, not excused.** Its subject
        # resolves to a question, so the request settles before anything is retrieved, generated or
        # spent — and an attempt reported for it would mean a name the build must ask about was
        # answered instead, which is the defect such a case exists to catch.
        assert not scored["attempts"], (
            f"{case['id']} reached {scored['attempts'][-1]['shape']}, and this case names no "
            "shape: its subject reads as two different bodies of work, so the request is a "
            "question to put back to the reader rather than something to answer from one of them."
        )
        return
    assert scored["attempts"], (
        f"{case['id']} produced no trace line, so which shape answered cannot be read. Either the "
        "answer trace is not wired into the backend, or the request settled before any attempt ran."
    )
    bound = scored["attempts"][-1]["shape"]
    assert bound == case["shape"], (
        f"{case['id']} bound to {bound}, not {case['shape']}. Every case states a command, so this "
        "is direct binding rather than a classifier verdict — a mismatch means the command no "
        "longer names the shape it used to."
    )
