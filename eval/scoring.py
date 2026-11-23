"""The three scores, and the report that makes two runs comparable.

**Three numbers rather than one, and that is the whole design.** Whether the right records came
back is a set comparison and deterministic; whether every claim rests on one of them is a subset
test; whether the prose is good is neither. Reporting one figure over all three would let a fluent
answer hide a retrieval regression, which is the failure this exists to catch.

Each score is a fraction between 0 and 1 so that the three are read on the same scale, and each is
reported with the denominator it was taken over — a mean over four cases and a mean over forty are
not the same evidence, and a number alone does not say which it is.
"""

from dataclasses import dataclass, field


@dataclass
class Scored:
    """One case's three scores, and what went wrong where something did."""

    case: str
    retrieval: float | None
    citations: float | None
    answer: float | None
    notes: list[str] = field(default_factory=list)


_RUN: list[Scored] = []


def record(scored: Scored) -> None:
    """Keep [scored] for the run's report."""
    _RUN.append(scored)


def recorded() -> list[Scored]:
    return list(_RUN)


def retrieval_score(
    retrieved: list[str],
    must_include: list[str],
    must_exclude: list[str],
) -> tuple[float, list[str]]:
    """
    How much of what a case needs came back, and what came back that should not have.

    **Recall over the required set, not precision over everything.** A fused read returns more
    than a case names and is supposed to: a fixture cannot enumerate every span that belongs in a
    good answer, so grading on what else appeared would punish retrieval for working. What a
    fixture *can* state is material that must be there and material from another subject that must
    not, and the second is where a scoping failure shows.

    An empty required set scores 1: there is nothing to recall, which is a case that does not grade
    retrieval rather than one that failed it.
    """
    found = [span for span in must_include if span in retrieved]
    leaked = [span for span in must_exclude if span in retrieved]
    score = 1.0 if not must_include else len(found) / len(must_include)
    notes = []
    missing = [span for span in must_include if span not in retrieved]
    if missing:
        notes.append(f"not retrieved: {', '.join(missing)}")
    if leaked:
        notes.append(f"retrieved from another subject: {', '.join(leaked)}")
        # Zero rather than a deduction. A span from a thread the question was not about is a
        # scoping failure, and an answer composed from it is wrong however much else was right.
        score = 0.0
    return score, notes


def citation_score(attempts: list[dict]) -> tuple[float, list[str]]:
    """
    Whether every claim rested on something retrieved, across every attempt.

    **Scored over all of a request's attempts, not only the one that held.** A first generation
    that came back ungrounded and a retry that saved it is a real finding: grading the surviving
    attempt would report a clean run for a request that invented a citation and got lucky.

    Two things are checked, because the trace can show a failure the runner's own check cannot.
    `ungrounded` is the runner's verdict. The subset test is this suite's own, and it holds the
    report itself honest — a trace reporting a cited span that is not in its own retrieved set
    while calling nothing ungrounded would mean the check and the report disagree.

    **An attempt that cited nothing at all scores clean, and is noted.** There is no claim to hold
    against the retrieved set, so the subset test is satisfied by having nothing to test — which is
    correct for a shape whose deliverable is composed text, and is the whole of what went wrong for
    one whose answer is its assertions. The score cannot tell those apart, since the trace carries
    no fault and this suite holds no shape. The note is so that a mark credited over nothing appears
    in the run's own report instead of reading like one that checked something.
    """
    if not attempts:
        return 0.0, ["no attempt was reported, so nothing could be scored"]
    notes = []
    clean = 0
    for attempt in attempts:
        ungrounded = attempt.get("ungrounded", [])
        cited, retrieved = attempt.get("cited", []), attempt.get("retrieved", [])
        outside = [span for span in cited if span not in retrieved]
        if ungrounded:
            # Quoted per entry, because this list is not all identifiers: a citation that read as no
            # number at all arrives as the text the model wrote, cut to a length, and may carry
            # spaces and commas of its own. Joined bare it would read as more failed citations than
            # there were -- the ambiguity the fault line quotes to avoid.
            failed = ", ".join(f'"{entry}"' for entry in ungrounded)
            notes.append(
                f"attempt {attempt.get('attempt')} had citations that named nothing retrieved, "
                f"or nothing at all: {failed}"
            )
        if outside and not ungrounded:
            notes.append(
                f"attempt {attempt.get('attempt')} reported {', '.join(outside)} as cited and not "
                "retrieved, while reporting nothing ungrounded — the check and the report disagree"
            )
        if not ungrounded and not cited:
            notes.append(
                f"attempt {attempt.get('attempt')} cited nothing at all, so its mark here was "
                f"credited over no claims — against {len(retrieved)} span(s) retrieved"
            )
        if not ungrounded and not outside:
            clean += 1
    return clean / len(attempts), notes


def answer_score(
    prose: str,
    must_mention: list[str],
    must_not_mention: list[str],
) -> tuple[float, list[str]]:
    """
    Whether the prose says what the corpus supports.

    **Asserted on figures and organisation names, never on wording.** A paraphrase check fails on
    a better answer, and a corpus in Russian makes substring matching on prose a test of
    morphology. A price, a batch number and a company name survive any rephrasing the model
    reaches for, and they are also the part of an answer that is wrong in a way that matters.

    An empty required set scores 1 for a case that only asserts an outcome. Anything forbidden
    appearing is zero: a figure from another thread in the answer means the wrong material reached
    the prompt, and the rest of the answer being good does not redeem it.
    """
    if not prose.strip():
        return 0.0, ["no prose came back, so nothing could be scored"]
    found = [token for token in must_mention if token in prose]
    leaked = [token for token in must_not_mention if token in prose]
    score = 1.0 if not must_mention else len(found) / len(must_mention)
    notes = []
    missing = [token for token in must_mention if token not in prose]
    if missing:
        notes.append(f"not mentioned: {', '.join(missing)}")
    if leaked:
        notes.append(f"mentioned material from another subject: {', '.join(leaked)}")
        score = 0.0
    return score, notes


def report(lines: list[str] | None = None) -> list[str]:
    """
    The run's three scores, as lines to print.

    **Every dimension states how many cases it was taken over**, and a dimension nothing graded
    says so rather than reporting 1.0 for an empty mean. A suite that measured nothing must not
    read like one that measured everything.
    """
    out = lines if lines is not None else []
    scored = recorded()
    if not scored:
        out.append("evaluation set: nothing was scored in this run.")
        return out
    out.append(f"evaluation set: {len(scored)} case(s)")
    for label, pick in (
        ("retrieval", lambda item: item.retrieval),
        ("citations", lambda item: item.citations),
        ("answers  ", lambda item: item.answer),
    ):
        values = [value for value in (pick(item) for item in scored) if value is not None]
        if not values:
            out.append(f"  {label}  not graded in this run")
        else:
            out.append(f"  {label}  {sum(values) / len(values):.2f}  over {len(values)} case(s)")
    for item in scored:
        for note in item.notes:
            out.append(f"  - {item.case}: {note}")
    return out
