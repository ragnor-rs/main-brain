"""What each request shape puts in front of a reader, read off the message Telegram delivered.

**The layer nothing else can see.** The unit suites assert what the backend puts on the wire and
the renderer's own tests assert what it does with what it is given, but no module spans the two:
the frontend depends on the wire contract alone, so the composition exists only in a running
stack. Both of the worst defects found by hand in this build lived exactly there — a draft that
could not be sent as written, and a citation that reached the wire on every claim and reached no
reader at all — and every suite was green through both.

So these cases read what the platform delivered and nothing else — the text of each message, and
for the two about formatting the entities the client will render from it, since markup crosses as
tags the platform then parses away. They are deliberately about *form*: what a reader may copy,
what they can act on, and what must not be mixed into either. Whether the prose is any good is the
evaluation set's question and is not asked here.

**One assertion reads the prose, and the bound on it is that it needs no judgement.** A reply may
not put an undertaking into a state this organisation's records cannot establish, and the wordings
that do are a short closed list in two known languages — so reading for them is a search rather
than an opinion. It rides on the status case rather than taking a case of its own, because it
wants the same reply and a second request would be a second model call. That is the whole of the
exception: anything needing a view on whether the prose is good belongs to the evaluation set, as
above.

**Each shape, held to its own rule rather than to one shared one.** An answer somebody reads
carries its findings inline, because a reader wants them where the model wrote them. An
alternative somebody sends carries the composed text alone, because the findings are in the third
person about the person being written to and go out with the message. The same marker decides
both, in opposite directions, which is why one assertion serves them.

**A case that cannot fail is worse than an absent one, because it reads as coverage — so what
each case here can and cannot see is stated in it, and a case carrying a floor on length carries
something else as well.** A floor plus a marker somewhere in the reply is satisfied by any long,
cited reply, including one that got every decision the shape makes wrong; the cases below that read
an answer somebody *reads* therefore also hold it to the material, through [names_the_parties] and
[leads_with_what_fell_due]. The rest were held to the same question and answer it without a length
bound at all: the drafting case asserts absences and a count, which are properties of what it
offers rather than of its volume; the citation case counts its block in both directions, which is a
measurement rather than a floor; and the research case reads its sources, which is the only
material that shape's answer rests on.

The `ask` shape is in `test_answer.py` — it is the one reachable without naming anybody, and it
is also the composition check this suite exists for. `research` is read here too, and its case is
unlike the rest of this file in two ways worth meeting at the top. Its cost is bounded by its
question rather than by a configured name, because the shape accepts no subject and so the
question is the only lever there is — fixed and narrow below instead of open-ended. And it is the
only case here with no relation against the store to assert, because what it answers from is not
the corpus: its readings are over features of the reply alone. So it runs wherever an account is
configured, needing neither a subject nor an indexed corpus, and it is the costliest single call
this suite makes, at the highest effort this build asks for and against the open web. What that
does to a deploy's floor is said in `docs/process.md` beside what a deploy skips.
"""

import warnings

import pytest
from telethon.tl.types import MessageEntityBold, MessageEntityItalic

import reading
import store
from conversation import (
    ASSERTION,
    LITERAL_ENOUGH,
    PROPOSED_QUESTIONS,
    READABLE_BEFORE_A_CALL_CHARS,
    RESEARCH_SECONDS,
    SUBSTANTIAL_CHARS,
    answered,
    ask,
    catalogue_entries,
    catalogue_text,
    delivered,
)

#: How many alternatives the drafting shape promises. Asserted as a floor rather than a range,
#: because the upper bound is the shape's business and a reader is not worse off for a third.
FEWEST_DRAFTS = 2

#: What the research case asks, fixed so that what the run spends is predictable.
#:
#: **Narrow, factual, and about a jurisdiction rather than anybody.** The shape accepts no subject,
#: so the question is the only thing bounding how far it reads — an open-ended one is the same case
#: at several times the cost, and a question about a counterpart would put a name out of this
#: corpus into a committed file, which is the disclosure the whole arrangement avoids.
#:
#: Russian, because the shape answers in the language it was asked in and the reader of this
#: deployment reads Russian — so what the assertions below read is the rendering somebody is
#: actually handed rather than a translation of it.
RESEARCH_QUESTION = (
    "Какая стандартная ставка НДС действует в Грузии и с какого оборота "
    "регистрация плательщиком обязательна?"
)

#: How many web sources a researched answer carries at the least.
#:
#: More than one, because a reply resting on a single authority has not researched anything and the
#: shape's instruction asks for both sides where sources disagree. Counted as distinct sources
#: rather than as occurrences — see [reading.sources], which is where that distinction is made and
#: why.
#:
#: **A floor, and the weakest of this case's three readings.** What it discriminates is a reply
#: whose sources were dropped between the model and the reader, which is the class of defect this
#: whole file exists for and is the direction this shape's sources fail in. It is far below what a
#: researched answer has measured at, so it does not discriminate a thin answer from a thorough
#: one, and nothing here can: there is no store-side expectation for a question about the open web
#: the way there is for a question about the corpus.
SOURCES_AT_LEAST = 2


def source_literal() -> str:
    """The run the sources block opens with, read off the catalogue rather than listed here.

    The block takes who wrote the material and the span of days it falls in, so only the literal in
    front of the first argument is fixed. Read by key for the reason the freshness cases read
    theirs: a reworded entry should move this with it rather than quietly stop matching.

    Read off the named-authors, several-days entry, whose literal run is the opening all four share
    — so a block that came out in any of the four shapes is still found.
    """
    literal = catalogue_text("bot.SOURCES_BETWEEN").split("{", 1)[0].strip()
    assert literal, (
        "No bot.SOURCES_BETWEEN in the catalogue, so there is no sources block to look for and "
        "these cases would pass over the thing they exist to check."
    )
    return literal


def counted(answer: str, opening: str) -> int:
    """How many of `answer`'s lines open with `opening`."""
    return sum(1 for line in answer.splitlines() if line.strip().startswith(opening))


def names_the_parties(answer: str, placed, unasserted, shape: str) -> None:
    """Hold the prose of `answer` to naming whoever the store places the material on.

    **The prose, which is the whole of why this reads anything at all.** The block saying where the
    answer's material came from is rendered *inside* the composed reply, and what it carries is who
    wrote that material over which days — so for a shape whose findings are narrowed to what the
    subject stated, the subject's own name is in the reply whenever anything was cited, however the
    prose was written. Read over the whole answer this assertion passes on the interface's own
    furniture every single run, which is the shape of defect this case was raised for. So the block
    is cut first, by the literal the cases already read off the catalogue.

    **Two legs, and nothing here assumes they name two people.** A commitment carries the party
    that stated it and the party it obliges, and the reach that finds a brief's material narrows on
    the first — so every undertaking in front of the model was stated by the subject, and the
    obliged party is a second column that may or may not name somebody else. Where the two agree
    this does not pretend the second leg is independent evidence; where the store fills neither, it
    says so.

    **Why a name is the one token worth asserting on.** Everything else an item carries is
    restated by the composer in its own words; a name is passed through the material as the store
    spells it, whole, and the instruction tells the model to keep the form a source gave it.
    Declension is what [reading.mentions] is for, and a spelling nothing can be read of — a run of
    two or three letters that sits inside ordinary words — is reported rather than looked for.

    **What it cannot say is that the right person was named**, because a stem a name shares with
    another name matches either. What it separates is a reply that names a party from one that
    reports a relationship without naming anybody in it, which is the defect: every fact present,
    and a reader unable to tell who any of it is about.
    """
    prose = reading.before_the_sources(answer, source_literal())
    if placed.undecided:
        unasserted(
            f"which parties the {shape} names",
            f"the configured name reached {placed.humans} people in the store rather than one, so "
            f"the material behind the reply is not known to be this material — see store.py",
        )
        return

    names = reading.readable(placed.names)
    if not names:
        unasserted(
            f"that the {shape} names the person it is about",
            f"none of the {len(placed.names)} spelling(s) the store holds for them stems to a run "
            f"long enough to look for, so a reply naming nobody and one naming them read alike",
        )
    else:
        assert reading.named(prose, names), (
            f"The {shape} never names the person it is about, though the store holds "
            f"{len(names)} readable spelling(s) for them and hands the shape one on every item. A "
            f"relationship reported without naming either side is the failure this case is named "
            f"for: a reader cannot tell whose undertaking any of it is. Looked for: "
            f"{[reading.stems(name) for name in names]}. Prose, with the sources block cut: "
            f"{prose!r}"
        )

    parties = reading.readable(placed.parties)
    if not parties:
        unasserted(
            f"naming the party each unsettled undertaking in the {shape} is placed on",
            f"the store places none of this subject's {len(placed.owed)} unsettled undertaking(s) "
            f"on anybody it can name, so a reply naming nobody is the correct answer here",
        )
        return

    assert reading.named(prose, parties), (
        f"The {shape} names none of the {len(parties)} part(y/ies) the store places these "
        f"unsettled undertakings on, so what reached the reader says something was promised "
        f"without saying by whom — which is what the shape's instruction forbids where the record "
        f"does place somebody. Prose, with the sources block cut: {prose!r}"
    )


def leads_with_what_fell_due(answer: str, placed, unasserted) -> None:
    """Hold `answer` to the order the store's own standings put its items in.

    **The assertion that survives a rewording is positional, and what makes a position readable is
    the figure of days past.** What fell due with nothing recorded against it leads, furthest past
    first; the five-item bound then drops from the other end. So the leading item is the one a
    brief cannot leave out, and a brief assembled in the order the store returned — by recency of
    evidence — either puts it after the rest or, where the material runs past the bound, does not
    carry it at all. Both are readable without reading the prose, because the figure is computed
    from the store's own deadline before the prompt is assembled.

    **A date is not what the order is read by, and the reason is a property of the material rather
    than a preference.** A deadline is a day, and a day is as readily the day some other item was
    *stated* on — so a date matches the wrong sentence and an order that has been broken reads as
    held. A figure of days past is tied to the standing clause it is written in. Presence is
    allowed the deadline as well, because there it costs at most a weaker pass and `reading.present`
    bounds even that.

    **The order is therefore read between two overdue items**, which is where a figure exists on
    both sides. An item still ahead or carrying no date has no such figure, so nothing locates it
    without going back to a date — and those are the comparisons this does not make.

    **Where the material cannot discriminate this, it says so.** An archive that places no
    deadline in the past on the subject gives every item the same standing, and every order of
    them is then correct.
    """
    if placed.undecided:
        unasserted(
            "that the brief leads with what fell due",
            "the configured name did not resolve to one person in the store",
        )
        return

    past = placed.owed_past
    if not past:
        unasserted(
            "that the brief leads with what fell due",
            f"the store places no past deadline on this subject's {len(placed.commitments)} "
            f"undertaking(s), so they share one standing and no order of them is wrong",
        )
        return

    # The account of the record, which is what the order is a property of. **Absent rather than
    # fallen back to the whole reply**: read over the lead as well, a brief in the store's own
    # order whose lead happens to name the furthest-past item passes, which is the order assertion
    # asserting nothing while reporting green.
    account = reading.after_the_lead(answer, ASSERTION)
    if not account:
        unasserted(
            "that the brief leads with what fell due",
            "the reply carries no account past its lead, so there is no sequence of items to read "
            "an order off",
        )
        return

    leading = past[0]
    at = _ordered_at(account, leading, placed)
    if at < 0:
        # **No position, so nothing about the order can be read — and whether that is a failure
        # depends on whether the item had to be there at all.** Where the store places no more
        # unsettled items than the brief may report, nothing was left out and an item missing by
        # both tokens is missing from the reader's brief. Past that bound the instruction keeps
        # what a longer silence sits behind rather than what fell due furthest past, so the same
        # absence is correct prose — and the figure may also simply have been worded rather than
        # written, which no reading here can tell from an omission.
        if len(placed.owed) <= store.ITEMS:
            assert _present(account, leading, placed), (
                f"The undertaking the store ranks first — due {leading.due}, "
                f"{leading.days_past} day(s) past with no outcome recorded — is in no item of "
                f"this account, by its figure of days or by its deadline, and the store places "
                f"only {len(placed.owed)} unsettled undertaking(s), which is inside the "
                f"{store.ITEMS} this shape may report. So nothing was left out to make room: the "
                f"item a call is for did not reach the reader. Account: {account!r}"
            )
        cut = (
            ", or left out under the length bound"
            if len(placed.owed) > store.ITEMS
            else ""
        )
        unasserted(
            "that the brief leads with what fell due",
            f"the item that fell due furthest past carries no figure of days in this account — "
            f"worded rather than written{cut} — so nothing locates it against the rest",
        )
        return

    compared = 0
    for item in past[1:]:
        later = _ordered_at(account, item, placed)
        if later < 0:
            continue
        compared += 1
        assert at < later, (
            f"An undertaking {item.days_past} day(s) past was put in front of one "
            f"{leading.days_past} day(s) past, both with no outcome recorded. The furthest past "
            f"leads — that is the order the standings are read in, and it is the order the store's "
            f"own by-recency ordering disagrees with — so a reader taking the top of this brief "
            f"into a call takes the wrong item. Account: {account!r}"
        )
    if not compared:
        unasserted(
            "that the brief leads with what fell due",
            f"the item that fell due furthest past is located, but none of the other "
            f"{len(past) - 1} overdue item(s) is — a figure each could be read as, or one that "
            f"never reached the account, leaves nothing to order it against",
        )


def _present(account: str, item, placed) -> bool:
    """Whether `account` reports `item`, read against the figures its neighbours carry.

    The reading is [reading.present]; what this adds is which figures count as somebody else's.
    """
    others = tuple(
        other.days_past
        for other in placed.commitments
        if other is not item and other.standing == "past" and other.days_past
    )
    deadline = item.days_past if item.standing == "past" else None
    return reading.present(account, item.due, deadline, others)


def _ordered_at(answer: str, item, placed) -> int:
    """Where `answer` places `item` by a token that can only be about this item, or `-1`.

    The figure of days past and not the deadline, for the reason [leads_with_what_fell_due] gives:
    a date can be some other item's own date and then points at the wrong sentence. And not every
    figure either — the material writes a second one of the same shape on each item, so a count
    another item could also have produced is no position at all. [store.Placed.ambiguous] is where
    that question is asked and where what it cannot answer is written down.
    """
    if item.days_past is None or item.standing != "past" or placed.ambiguous(item.days_past):
        return -1
    return reading.overdue_at(answer, item.days_past)


#: Wordings that put an undertaking into a state, which is the one thing this organisation's
#: records cannot establish: a promise kept in silence leaves no closing message, so an outcome
#: nothing records is an outcome nobody wrote down. `Reply.STATE` says so to every shape and says
#: what to write instead; this is the only check here that reads whether the composed prose obeyed,
#: and it is much the weaker half of that pair.
#:
#: **A rule's own vocabulary is not a usable list, and what was dropped is named rather than
#: quietly absent.** The rule exempts a question, a vacancy and a source's own wording, so a form
#: that attaches to any of those cannot be scanned for: *вопрос не закрыт*, *незакрытый вопрос* and
#: *вопрос остаётся открытым* are all correct prose about a question, and each matched the obvious
#: pattern. They are out, in both their е and their ё spellings — and the cost is real, because the
#: Russian rendering of the *unclosed task* this was written for goes with them. What catches that
#: is the prompt stating the principle and `ShapesTest` asserting it reaches every shape.
#:
#: **The English entries are a judgement, not a measurement, and the two Russian ones carry the
#: run.** A Russian corpus and a Russian subject mean the shape answers in Russian, so in practice
#: this reads `ожидает действ` and the е/ё pair of one wording; the English forms are here against
#: a question asked in English and nothing more. *unclosed* and *still outstanding* are admitted
#: knowing they can attach to a question too — unlike their Russian counterparts, no corpus
#: evidence forced them out, and an English reply is rare enough that the trade favours catching
#: the defect.
#:
#: **The forms were chosen against the corpus rather than from intuition**, which is what ruled out
#: the bare adjectives: *открыт*, *висит* and *ждём* each occur in ordinary messages about a
#: question, a wait or a door, in numbers recorded in the pull request rather than here. None of
#: the forms below occurs at all, so a hit is the answer's own wording and not material it relayed.
#:
#: *outstanding commitment* is deliberately NOT a member: `Reply.STATE`'s own first sentence hands
#: the model that phrase inside a negation — *is not an outstanding commitment* — so a reply
#: correctly echoing the rule would redden a substring scan that cannot see the *not*.
#:
#: **The rule's far half — an undertaking taken as done — is scanned by nothing here, and that is a
#: decision rather than a gap.** *Done*, *completed* and *delivered* are correct prose wherever the
#: records report an outcome, which the rule expressly permits, so no substring separates the
#: permitted use from the forbidden one. The worse form is a presupposition anyway: a question
#: asking what a promised piece of work produced asserts that it happened while containing none of
#: those words. What catches it is the prompt, which is where the rule belongs.
#:
#: **A form this list is known to miss, named because the miss is the point.** A controlled probe
#: — the same material and question put to the prompt with and without `Reply.STATE` — produced
#: *от внешней стороны по записям ожидаются: …* without the rule and *на стороне клиента и
#: подрядчика записаны пять пунктов* with it. The passive *ожидаются* is the defect in that
#: sentence and no member here catches it, because the construction that makes it an assertion
#: about a party is *от X … ожидаются*, with words in between that no substring spans — and a bare
#: *ожидаются* attaches to an event just as readily, which this shape also reports, so admitting it
#: would redden correct prose about what is coming. Widening the list to the wording last observed
#: is the closed-vocabulary mistake this whole rule exists to avoid. What caught it is the prompt,
#: which is where the rule belongs.
STATE_WORDINGS = (
    "ожидает действ",
    "все еще ожида",
    "всё ещё ожида",
    "unclosed",
    "still owed",
    "awaiting action",
    "still outstanding",
)


@pytest.mark.corpus
@pytest.mark.telegram
@pytest.mark.stack
def test_a_drafted_reply_arrives_ready_to_send(telegram_account, archive_subject):
    """**The one request whose output is a message somebody sends, so form is the whole of it.**

    A reader picks an alternative, copies it and pastes it into the conversation, so what the
    message carries beside the draft goes out with it. A draft's cited asides are written in the
    third person about the person being written to, so what arrives is a note reporting to somebody
    what they themselves said. The saving the request exists for is gone either way: a draft that
    has to be trimmed before sending is slower than writing one.

    The renderer's heading naming the alternative is the one thing it deliberately puts there, and
    this does not object to it: the renderer argues for it as the part that is *not* the draft, and
    the card that prompted this case accepted it. What is asserted is the absence of the assertion
    marker, which is the material that was never meant to be in a sendable message.

    **What this cannot see, stated because a green run otherwise overstates itself.** The assertion
    is an absence, so on a run where the model wrote no cited aside for this subject the correct
    mapping and the broken one produce the same message and this passes either way. Nothing here
    can tell those apart — the wire is where the claims are, and these cases deliberately do not
    read it. The two cases below are what make this one mean something on a given run: they assert
    the marker is *present* for the same subject, so a run where they pass is a run where the
    material does yield assertions.
    """
    request = f"/draft {archive_subject}"

    messages = delivered(telegram_account, request)
    texts = [text for text, _ in messages]
    # **That markup survives the wire at all is asserted here rather than on an answer**, because
    # this is the one shape where an emphasis is unconditional: the renderer puts a numbered header
    # on every alternative and emphasises it. A sectioned answer's headings depend on the retrieved
    # material spanning more than one body of work, so a reply with one section correctly carries
    # no heading and an assertion over there would red on a working stack.
    #
    # **Asked of the type and not of its name, which is what this got wrong.** `delivered` returns
    # the entity *classes* the platform rendered — its signature says so — and a string is never an
    # element of a list of classes, so the first form of this could only ever fail. It did, on the
    # first deploy that ran it, against a reply whose three alternatives each carried bold: the
    # failure printed the classes it compared against, and so carried its own refutation.
    # The case below asserting italic had the comparison right all along, which is the form taken
    # here.
    kinds = [kind for _, entities in messages for kind in entities]
    assert MessageEntityBold in kinds, (
        f"Not one message of a drafted reply carried emphasis. Every reply goes out in the "
        f"platform's markup mode and the renderer emphasises each alternative's header "
        f"unconditionally, so this is markup not reaching the client at all rather than a shape "
        f"that happened to need none. Entities per message: {[e for _, e in messages]}"
    )
    # What an alternative rests on arrives apart from it under a heading of its own, which is the
    # only place it can go: a reader copies the draft, so anything in that message goes out with it.
    # Those messages are not alternatives and are not counted as one.
    #
    # The literal run in front of the argument rather than the whole entry: the heading names which
    # alternative it belongs to, now that it no longer sits under one, so the entry carries `{0}`
    # and matches no delivered message exactly. Read the way a templated status line is.
    heading = catalogue_text("bot.DRAFT_BASIS").split("{", 1)[0].strip()
    # Asserted, because an absent key reads as the empty string and every message starts with
    # that — so a renamed key would empty this list and fail below saying the interface offered a
    # choice of none, which points at the renderer for a catalogue rename.
    assert len(heading) >= LITERAL_ENOUGH, (
        f"The run bot.DRAFT_BASIS opens with before its argument is {heading!r}, which is "
        f"{len(heading)} characters against a floor of {LITERAL_ENOUGH}. Empty means the entry "
        f"opens with its own argument and there is nothing to match; short means it is a common "
        f"word, and a draft happening to begin with it would be filtered out of the count below "
        f"as if the interface had offered fewer alternatives than it did."
    )
    drafts = [
        text for text in answered(texts, request) if not text.strip().startswith(heading)
    ]

    assert len(drafts) >= FEWEST_DRAFTS, (
        f"The shape promises two or three alternatives and {len(drafts)} arrived as composed "
        f"messages. One is not a choice, and the interface cannot tell a menu of one from a "
        f"reply that never offered one. Replies: {[text[:120] for text in texts]}"
    )

    for draft in drafts:
        assert ASSERTION not in draft, (
            f"A drafted reply arrived with a cited assertion inside it, so copying the message "
            f"copies the assertion. The shape's own instruction puts those outside the draft, so "
            f"this is the mapping folding them back in rather than the model disobeying — the "
            f"menu option is built from the reading order instead of the prose. Draft: {draft!r}"
        )
        assert source_literal() not in draft, (
            f"A drafted reply arrived with the sources block inside it, so sending the message "
            f"sends the counterpart a note about where their own words came from. What the draft "
            f"rests on is worth showing and belongs in the message beside it. Draft: {draft!r}"
        )


@pytest.mark.corpus
@pytest.mark.telegram
@pytest.mark.stack
def test_a_status_answer_carries_its_findings_where_they_were_written(
    telegram_account, archive_subject, placed, unasserted
):
    """**The opposite rule, and the reason the draft case above cannot be applied everywhere.**

    This answer is read rather than sent, and its findings *are* the answer. Lifting them out left
    a reader with headings and nothing under them, so they stay where the model wrote them. A case
    asserting their absence everywhere would have made that regression look like the fix.

    **It also reads the prose for a forbidden state, which is this file's one exception to form.**
    Folded in here rather than given a case of its own: the two assertions want the same reply, and
    a second `/status` would be another model call per run against a budget already once exhausted
    — `docs/process.md` puts the suite's cost at one call per shape a command selects, and three
    `/status` cases would make that untrue. The cost of folding is that one failure masks the
    other, which the messages below distinguish by naming which assertion fell.

    **Why it is scoped to this shape.** `status` and `prepare` are the two that retrieve
    commitments and render the unrecorded-outcome label, and this is the cheaper of them to ask.
    Nothing excuses `prepare` — it is simply unchecked here, which is a gap and not a decision
    about the rule. The drafting shape is a decision: a draft is correspondence somebody sends, so
    following something up is what it is for, though a claim line a draft carries is still held to
    the rule and is unchecked too.

    **What this cannot see, stated because a green run otherwise overstates itself.** It is an
    absence over composed prose, so a reply with little to say satisfies it without the rule having
    been obeyed anywhere; the finding assertion above is what makes a pass mean something, since a
    run reaching here is one where the answer did report items. And a model reaching for a wording
    outside the list passes this and still fails the reader — which is why the prompt states the
    principle rather than this vocabulary.

    **The shared format's rule that a finding names what it is about is scannable by nothing
    here, and that is a decision rather than an omission.** Whether a sentence names a subject a
    reader can identify is not a property of any string in it: a pronoun whose antecedent is in
    the same sentence and one pointing at a conversation the reader cannot see are
    indistinguishable to a pattern, and a check keyed on the prompt's own wording would re-pin
    the instruction rather than read the answer. So it is held hermetically by the prose pins in
    `ShapesTest`, and behaviourally by a control-against-treatment probe recorded on the card —
    neither of which is a rendered read, which is what this file otherwise exists to be.

    **One half of that rule is readable, and it is read here for the same reason the brief's is.**
    Whether a *name* the store holds reached the answer is a relation against the material rather
    than a judgement about prose, and a reply about a counterpart's open threads that names
    nobody in them is the defect in its plainest form. Without it this case's quantitative half
    is a floor on length and a marker somewhere in the reply, which any long cited answer
    satisfies — including one that got every decision the shape makes wrong.
    """
    request = f"/status {archive_subject}"

    texts = ask(telegram_account, request)
    # Every composed message together, not the longest. An answer over the platform's limit is cut
    # into pieces, so the longest piece is a fraction of the reply and a marker in another piece
    # would read as a marker nowhere.
    answer = "\n".join(answered(texts, request))

    assert len(answer) >= SUBSTANTIAL_CHARS, (
        f"The answer was {len(answer)} characters, under the {SUBSTANTIAL_CHARS} this requires. "
        f"A reply that short about a counterpart with history is a hedge: {answer!r}"
    )

    assert ASSERTION in answer, (
        "A status answer reached the reader with no cited assertion in it. This shape's answer is "
        "its findings, so they were validated and then dropped on the way out — which leaves a "
        "grounded answer reading as a summary with every specific removed."
    )

    # **Read over every delivered message rather than over the composed ones, because this is an
    # absence.** `answered` drops a piece it reads as the bot's own — a templated opening of twelve
    # characters or more is enough — and a piece it drops is a piece a forbidden wording could hide
    # in. Widening cannot cost a false failure here: no entry of the catalogue the bot renders from
    # contains any of these forms, so anything matched was written by the model. Case-folded
    # because the model chooses its own capitalisation, and a wording opening a sentence would
    # otherwise be one this misses.
    delivered = "\n".join(texts).casefold()
    for wording in STATE_WORDINGS:
        assert wording.casefold() not in delivered, (
            f"The answer puts an undertaking into a state by calling it {wording!r}, which is the "
            f"state this build cannot establish: a promise is frequently kept in silence, so "
            f"nothing recorded means nothing recorded. The material's own labels say only that the "
            f"outcome is unrecorded, so this is the prose composed around them reaching for the "
            f"ordinary word. Delivered: {'|'.join(texts)!r}"
        )

    names_the_parties(answer, placed, unasserted, shape="status answer")


@pytest.mark.corpus
@pytest.mark.telegram
@pytest.mark.stack
def test_a_preparation_answer_names_both_parties_rather_than_speaking_as_one(
    telegram_account, archive_subject, placed, unasserted
):
    """**The shape that is read before a call, which must not adopt anybody's voice.**

    It reports on a relationship in the third person. A draft is written *as* the sender and that
    is what made the voice wrong there; this one is written *to* that person instead, so it knows
    who is reading without speaking as them — so the parties are named rather than fused into one
    voice, and the findings are present and inline, as a thing being read.

    **Every assertion here is a relation against the store, bar the two bounds on size.** What the
    brief decides — which items it raises, in what order, and whose undertaking each is — is asked
    for by instruction and composed in the model's own words, so a scan for a phrasing would be a
    flaky check dressed as a strict one. What does not vary is the material: a name is the store's
    name and a deadline is the store's date. [names_the_parties] and [leads_with_what_fell_due]
    are the two readings that follow from that, and each says so in the run's own output where the
    archive holds nothing that could tell a right answer from a wrong one.

    **One property this shape decides is not readable here at all, and it is named rather than
    left to look covered.** How stale each undertaking is reaches the model as a computed figure
    and is asked for as a *judgement* — it decides what is worth raising, and the instruction
    never asks for it to be printed. So a brief that cannot tell an undertaking three weeks cold
    from one made yesterday is caught by what it chose to raise, not by any string in it: the
    order read below is the half of that visible from outside, and the rest belongs to the shape's
    own suite and the evaluation set.

    **The floor is the weakest assertion in the file and is kept for the one thing it does see.** A
    reply far under it is a hedge, whatever else is true of it. It is not the only measurement:
    the ceiling below bounds the direction this shape's length actually fails in, and the
    readings above bound the material rather than the volume.
    """
    request = f"/prepare {archive_subject}"

    texts = ask(telegram_account, request)
    # Joined for the reason the case above joins: the platform cuts a long answer into pieces.
    answer = "\n".join(answered(texts, request))

    assert len(answer) >= SUBSTANTIAL_CHARS, (
        f"The answer was {len(answer)} characters, under the {SUBSTANTIAL_CHARS} this requires. "
        f"Walking into a call with this is walking in with nothing: {answer!r}"
    )

    assert ASSERTION in answer, (
        "A preparation answer reached the reader with no cited assertion in it. Every open thread "
        "it names is a claim about the corpus, so a reply carrying none either asserted nothing "
        "or had its assertions dropped between the check and the chat."
    )

    # **The ceiling, which is the half of this shape's acceptance a floor cannot carry.** The
    # brief that raised the card was accurate, cited, named both parties and ran to three screens;
    # every other assertion here passed on it. So the length is asserted in the direction the
    # complaint was in.
    assert len(answer) <= READABLE_BEFORE_A_CALL_CHARS, (
        f"The brief was {len(answer)} characters, over the {READABLE_BEFORE_A_CALL_CHARS} somebody "
        f"reads in the minute before a call. Being accurate does not make it readable in the time "
        f"there is to read it: {answer!r}"
    )

    proposed = [line for line in answer.splitlines() if line.strip().endswith("?")]
    assert len(proposed) <= PROPOSED_QUESTIONS, (
        f"The brief proposed {len(proposed)} questions, over the {PROPOSED_QUESTIONS} its "
        f"instruction allows. A question per item reported is the shape this bound exists to "
        f"stop: {proposed!r}"
    )

    # The two properties in this case's own name, and the one the card it was raised on is about.
    # Last because they are the expensive readings to diagnose: a failure above is about the reply
    # as a whole and should be read first.
    names_the_parties(answer, placed, unasserted, shape="brief")
    leads_with_what_fell_due(answer, placed, unasserted)


@pytest.mark.corpus
@pytest.mark.telegram
@pytest.mark.stack
def test_a_finding_tells_the_reader_where_it_came_from(telegram_account, archive_subject):
    """**The proposition is that an answer is checkable, and here it is either true or it is not.**

    The backend validates every claim's citations and puts them on the wire; for a long while the
    interface rendered none of them, so a reader was handed assertions with no way behind any of
    them. Nothing saw it: the unit suites assert the wire, and the evaluation case named for this
    read the wire too.

    What a reader is given is not the identifier — machine text, and nothing anybody can act on —
    but who wrote the material and the days it was written on, which is what somebody searches a
    conversation by. So this looks for the block rather than for an identifier.

    **Exactly one block, and that is the assertion in both directions.** At least one, or an answer
    reached the reader with no way behind any of it; at most one, because a line under every
    finding is what put more citation than answer in front of a reader, and counting them is the
    measurement this is held to.

    **Both directions are reachable and neither has been calibrated here against a live stack.**
    The nought case is what the per-reply form was built to make impossible to reach silently; the
    more-than-one case is what a return to a line per finding would produce. The renderer's own
    suite drives both, which is the weaker proof — it is the level that was green throughout the
    defect this case exists for — so a deploy is what actually closes it.
    """
    request = f"/status {archive_subject}"

    messages = delivered(telegram_account, request)
    texts = [text for text, _ in messages]
    # Joined for the reason the cases above join: the platform cuts a long answer into pieces, and
    # the block can land at the head of any of them.
    answer = "\n".join(answered(texts, request))

    findings = counted(answer, ASSERTION)
    assert findings, (
        f"A status answer arrived with no cited assertion in it, so there is nothing whose source "
        f"could be checked. That is the case above failing for its own reasons rather than this "
        f"one finding anything: {answer[:200]!r}"
    )

    sources = counted(answer, source_literal())
    assert sources == 1, (
        f"{findings} finding(s) reached the reader under {sources} sources block(s), and there "
        f"should be exactly one. None means the material behind the answer was discarded by the "
        f"interface, which the backend sent in full — the one failure this build cannot afford. "
        f"More than one means the block is being rendered per finding again, which is the volume "
        f"this reply was measured as unreadable for."
    )

    navigable(messages, request)


def corpus_note_openings() -> list[str]:
    """The literal each wording of the corpus note opens with, before its first argument.

    By prefix rather than by naming the two keys, for the reason `status_lines` reads the whole
    file: a third clause added beside them is covered without editing this. Narrowed to the corpus
    keys and not taken from `status_lines`, which is every line the bot composes about itself — the
    note naming the subjects that matched and lost is one of those and *is* legitimately its own
    message, so the broader list would fail this case on correct behaviour.
    """
    openings = [value.split("{", 1)[0].strip() for value in catalogue_entries("bot.CORPUS_")]
    found = [opening for opening in openings if opening]
    assert found, (
        "No bot.CORPUS_* wording in the catalogue, so there is no note to look for and this case "
        "would pass over the thing it exists to check."
    )
    return found


def navigable(messages, request: str) -> None:
    """Hold what the reader is shown of `messages` to being navigable, with content in each.

    **Read off the same request the block above read**, rather than from one of its own. A request
    is a model call, and the suite's cost is a call per question it puts to the stack — so two
    assertions about one answer are made on one answer. The two halves belong together in any
    case: they are one defect, measured together by hand across six shapes. Nothing bold, nothing
    set back, headings sitting on their own lines as ordinary text, and a last message carrying
    only a sentence about when the material was collected.

    **The formatting is asked of the entities rather than of the text, which is what makes this
    possible at all.** The markup crosses the wire as tags, the platform parses them and the
    characters are gone, so searching the delivered text for a tag searches for something
    correctly absent either way. That is also why the defect survived a green suite: every other
    case in this file reads `raw_text`, which discards formatting deliberately.

    **What is asserted is the sources half, and the heading half deliberately is not.** A shape is
    only instructed to section when the retrieved material spans more than one body of work, so a
    counterpart whose history is one body of work correctly yields a reply with no heading in it —
    and an assertion over emphasis would then red on a working stack and say the interface was at
    fault. That is an environment reading as a finding. What a reply rests on is not conditional
    in that way: one block is rendered after the answer whenever anything it rests on carries a
    readable date, which the caller above has just established this reply does. That markup
    reaches the client at all is asserted on the drafting shape, where an emphasis is
    unconditional, and that a heading is emphasised when there is one is `OutcomeRendererTest`'s,
    where the input is given rather than retrieved.
    """
    kinds = [kind for _, entities in messages for kind in entities]
    assert MessageEntityItalic in kinds, (
        f"What the reply to {request!r} rests on was not set back. It is one block after the "
        f"answer, naming who wrote the material and when; at the same weight as the findings it "
        f"supports it reads as another finding rather than as the line a reader consults. What "
        f"arrived: {[kind.__name__ for kind in kinds]}"
    )

    # **The note half, which can only be asserted as an absence — and says so when that absence
    # proves nothing.** The note renders only on a corpus that is behind, so on a current one
    # there is correctly nothing to find and the loop below passes over an answer it has not
    # examined. That is most runs, and the demonstration's. Reported rather than passed in
    # silence, because a green that measured nothing reads exactly like one that measured this.
    openings = corpus_note_openings()
    carried = [
        text for text, _ in messages
        if any(opening in (text or "") for opening in openings)
    ]
    if not carried:
        warnings.warn(
            f"The corpus was current for {request!r}, so no reply carried the note and the rule "
            f"that it never arrives as a message of its own was not exercised. Not a failure — "
            f"the note is correctly absent — but this run does not stand as evidence for it.",
            stacklevel=2,
        )

    for index, (text, _) in enumerate(messages):
        said = (text or "").strip()
        assert not any(said.startswith(opening) for opening in openings), (
            f"Message {index + 1} of {len(messages)} opens with the note about the corpus, so it "
            f"is a message *about* the answer rather than part of it: {said[:160]!r}. The note is "
            f"a footer; detached there is nothing for a reader to attach it to, and it costs them "
            f"a message whose whole content is a sentence about somebody else's sentence."
        )


@pytest.mark.telegram
@pytest.mark.stack
def test_researched_findings_carry_their_sources_in_the_prose(telegram_account, unasserted):
    """**The shape that could not be delivered at all, read through the thing that delivers it.**

    It is answered by the HTTP route and scored by the evaluation set, and neither of those waits
    under the ceiling the interface imposes — so a reader asking this got nothing back while every
    other route reported the shape working. That is the class of defect this whole file exists for,
    a composition only a running stack can see, and it is why the first thing asserted here is
    simply that the answer arrived.

    **Which is [answered]'s assertion rather than one written out below.** A reply that never came
    and a reply that was all the bot's own words are the same thing to a reader, and telling them
    apart is what that helper is for. Nothing is added to it here because nothing needs to be: the
    window this case waits exceeds what the interface itself will wait, so a failure there is the
    interface's verdict and not this suite's clock — see `RESEARCH_SECONDS`, which is where that
    arithmetic lives.

    **Then the form, which is this shape's own rule.** The cases above read answers whose findings
    are about the corpus, so each holds the claim marker to its own shape's rule — present where the
    findings *are* the answer, absent from a draft somebody sends. This shape's findings are from
    the web: they carry their source in the prose, as a name and a link beside the finding, and a
    claim line here is only ever about the sample of held material offered alongside. So the marker
    is held to neither, and the sources block is not counted — a reply about something the corpus
    has never heard of correctly has no claim line and no block.

    **Three readings: a floor on the sources, an absence, and a relation between two features of
    the reply.** A reply carrying no links is one whose sources were dropped between the model and
    the reader, which is this build's unaffordable failure in the one shape whose sources are all
    it has. A bracketed figure is a source written the one way it may not be, and reaches a reader
    as a reference into a list that nothing renders. A claim line carrying a link is a web finding
    written where a reader reads it as corpus-supported.

    **The third has material behind it and says so when it has none.** A reply with no claim lines
    satisfies it without the rule having been obeyed anywhere, and that is the ordinary case for
    this question rather than a corner: the shape's instruction routes a sample bearing on nothing
    into uncited prose, and the question here is deliberately about nobody the corpus knows. A
    non-empty sample does not imply a claim line. So where the reply carries none, the run reports
    that nothing was read instead of passing green.

    **What no reading here can see.** The worse form of that same defect is invisible: a web
    finding that *drops* its link and takes a corpus number instead renders as an ordinary claim
    line, indistinguishable from a true assertion about the held sample. Separating those needs the
    wire, where a citation either resolves to retrieved material or does not, and this tier
    deliberately does not read the wire. What stands in its place is `ResearchShapeTest`, which
    pins the instruction's sentences about where a web source goes — a prose pin, so it keeps the
    rule from being deleted silently and says nothing about the model having obeyed it.

    **Nor does it judge the research.** Whether the findings are true, whether the sources are any
    good, and whether the answer addressed the question are the evaluation set's three numbers. A
    confidently wrong answer with a dozen sources passes everything here, and should: this reads
    what a reader is handed, not whether it was worth handing them.
    """
    request = f"/research {RESEARCH_QUESTION}"

    # The interface's own bound rather than this suite's ordinary one, passed to both calls so that
    # a failure names the seconds that actually elapsed.
    texts = ask(telegram_account, request, window=RESEARCH_SECONDS)
    # Joined for the reason every case above joins: a researched answer runs past the platform's
    # limit and arrives in pieces, and a source in the second piece is a source nowhere to a
    # reading of the first.
    answer = "\n".join(answered(texts, request, window=RESEARCH_SECONDS))

    found = reading.sources(answer)
    assert len(found) >= SOURCES_AT_LEAST, (
        f"A researched answer reached the reader carrying {len(found)} distinct source(s), under "
        f"the {SOURCES_AT_LEAST} this requires. Every finding this shape reports is supposed to "
        f"arrive beside the source it rests on, and those sources are the whole of what makes it "
        f"checkable — a reply without them is an unsourced assertion about the open web, which is "
        f"the one thing this shape must not hand anybody. Answer: {answer!r}"
    )

    numbered = reading.numbered_sources(answer)
    assert not numbered, (
        f"The answer writes a source as a bracketed figure — {numbered} — which the shared reply "
        f"format forbids every shape and this shape's own instruction forbids twice more. Nothing "
        f"renders a list of sources under this reply, so the figure points nowhere; and it is also "
        f"the form a citation of held material takes, so a reader cannot tell which of the two "
        f"they are looking at. Answer: {answer!r}"
    )

    cited = reading.claims(answer, ASSERTION)
    if not cited:
        unasserted(
            "that no web finding was written into a claim line",
            "the reply carries no claim lines at all, which is the correct answer where the held "
            "sample bears on nothing — so a reply obeying the rule and one with no occasion to "
            "obey it read alike here",
        )
        return

    carrying = reading.claims_carrying_a_source(answer, ASSERTION)
    assert not carrying, (
        f"A web finding arrived inside a claim line, carrying its source with it: {carrying!r}. A "
        f"claim line asserts something about the material this organisation holds and is checked "
        f"against it; a finding from the web belongs in the prose with its source beside it. "
        f"Written as a claim it reaches a reader as something the corpus supports, which is the "
        f"one way this answer can be wrong without looking wrong."
    )
