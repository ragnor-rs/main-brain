"""Whether the right material came back.

**The dimension that can actually be asserted, and the reason the three are separate.** A set
comparison is deterministic: either the span a case names was retrieved or it was not. Folding
this into a judgement about the prose would let a fluent answer composed from the wrong thread
read as a pass, which is the regression a gate on retrieval exists to catch.
"""

import pytest

import conftest


@pytest.mark.retrieval
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_retrieval(case, graded):
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked: {scored['error']}")
    if scored["retrieval"] is None:
        pytest.skip(
            f"{case['id']} does not grade retrieval, by its own declaration: {case['note']} "
            "Skipped and named rather than scored as 1.0, so the mean below is taken over the "
            "cases that were actually graded."
        )
    assert scored["attempts"], (
        f"{case['id']} produced no trace line, so nothing can be said about what it retrieved. "
        "Either the backend was built without the answer trace wired, or the request settled "
        "before any attempt ran — the outcome in the notes says which."
    )
    assert scored["retrieval"] == 1.0, (
        f"{case['id']} retrieval {scored['retrieval']:.2f}: {'; '.join(scored['notes'])}"
    )
