"""Whether every claim rests on material that was actually retrieved.

**A claim citing something that was not retrieved is a failure, and it is invisible from outside.**
The runner degrades such an answer to the same reply an empty corpus produces, deliberately — in
both cases there is nothing the system can stand behind. That is right for a reader and useless for
a gate, so this scores the verdict the runner reached rather than the reply it sent.

Scored across every attempt, including the one a retry replaced: a first generation that invented a
citation is a finding even when the second came back clean.
"""

import pytest

import conftest


@pytest.mark.citations
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_citations(case, graded):
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked: {scored['error']}")
    if scored["citations"] is None:
        pytest.skip(
            f"{case['id']} declares that nothing of it is grounded, because its request settles "
            "before any shape runs: there is no retrieval, no generation and no claim to hold "
            "against anything. Skipped rather than passed, for the reason every other dimension "
            "here is opted out of by name — a mark credited over nothing reads like one that "
            "checked something. That no shape ran is asserted in `test_routing`."
        )
    assert scored["attempts"], (
        f"{case['id']} produced no trace line, so no grounding verdict could be read. An unrun "
        "check is not a satisfied one, so this fails rather than passing on an empty set."
    )
    assert scored["citations"] == 1.0, (
        f"{case['id']} citations {scored['citations']:.2f}: {'; '.join(scored['notes'])}"
    )


@pytest.mark.citations
@pytest.mark.parametrize("case", conftest.cases(), ids=lambda case: case["id"])
def test_citations_reach_the_wire(case, graded):
    """Every citation the runner validated is on the response the interface is handed.

    **Named for the wire because the wire is what it reads.** Whether a reader can follow one is a
    further question and a different tier's: this harness talks to the backend over HTTP and has no
    interface in it at all, so a case here cannot see a renderer that is handed citations and shows
    none — which is exactly what happened for as long as this case was named for the interface.
    `e2e/test_rendered.py` is where the rendered message is read.
    """
    scored = graded[case["id"]]
    if scored["error"]:
        pytest.fail(f"{case['id']} could not be asked: {scored['error']}")
    assert scored["delivered"], (
        f"{case['id']}: {'; '.join(scored['notes'])}. This is the one dimension that reads the "
        "wire rather than the runner's own account of itself: the grounding score is about what "
        "the model wrote, so a claim the runner validated and let through, and the mapping then "
        "lost on its way out, is invisible to it. A citation that never left the backend cannot "
        "be rendered by anything, which is the whole reason for citing rather than asserting."
    )
