"""The whole system, asked a question the way a person asks one.

**This is the check the suite exists for.** Every other test here establishes that a part is
present; this one establishes that the parts compose: a question goes in over Telegram, the
bot admits it, the backend classifies it, retrieval reads an index built by the derived passes
from a corpus ingestion captured, the model answers from what was retrieved, every assertion
is checked against the spans it cites, and the reply comes back to the same chat.

Nothing is stubbed and nothing is inspected from inside. A failure here does not say which
stage broke, which is deliberate — the unit suites say that, and a composed check that knew
where to look would be testing the wiring it was given rather than the system.

**A status reply is a failure, not an outcome.** The bot answers an empty corpus, an exhausted
budget and an unanswerable question with text from its own catalogue, and all three are
legitimate at runtime. None of them is this suite passing: for most of this build's life the
bot replied that it had nothing to answer from, every component was healthy, and nothing in
any suite went red. So the catalogue's own status lines are loaded and the reply is required
to be none of them.

**This is also the case for a subjectless request reaching the shape that reads agreements.**
The question below names nobody and asks what was agreed, so routing binds it to `status` —
which accepts the inbox, the subject with nothing behind it — and the answer is drawn from
commitments and their closure verdicts across everything rather than from a ranked read of
messages. The shapes a command selects are in `test_rendered.py`, which holds each to what its
reader is shown.

**So nothing automatic exercises the free-form fallback any more**, which is worth knowing
rather than worth fixing here: every evaluation case names a command, and this was the one
question that arrived without one. A case for the fallback needs a question that genuinely
fits no shape, which is a different fixture from this one.

**And it changes what a catalogue reply here means.** Nothing scopes a request that names
nobody, so the shape reads no thread: commitments, near and undated events and attributes
across everything, plus the turns around each undischarged promise, which it reaches through
the finding rather than through a conversation. It reads the corpus, so empty retrieval settles
before any model call. A derived layer with nothing in it therefore produces the same reply as
an index with nothing in it, and the generic assertion below reports both as an empty index.
Two things to check before reading a red here as ingestion: that the derived passes have run,
and that the index pass has — with no commitments there is nothing to widen, and with no index
the widening returns nothing, so either one empties this answer on its own.
"""

import pytest

from conversation import (
    ASSERTION,
    CATALOGUE,
    LITERAL_ENOUGH,
    MARKUP,
    SUBSTANTIAL_CHARS,
    answered,
    ask,
    catalogue_entries,
    catalogue_text,
    is_status,
    sources_keys,
    status_lines,
    without_markup,
)


def test_every_wording_of_the_freshness_note_reads_as_one_of_the_bot_s_own_lines():
    """Every shape the corpus-freshness note takes is recognised as the bot's own text.

    Neither the stack nor an account: the classifier is a function of the catalogue, so this
    runs wherever the suite is collected. It guards the one thing the note's wording can break
    here — a shape `is_status` does not recognise makes a run that answered nothing read as a
    short answer, which is the failure that classifier exists to prevent.

    **The note is now a footer rather than a message**, which is what makes recognising it still
    matter and changes why. It no longer arrives alone, so this is not about a reply that is only
    the note; it is about the note being appended to one of the bot's own lines, where the pair
    has to read as the bot's. `is_status` identifies a line by what a message opens with for
    exactly that reason, and the wordings below are what it opens with.

    The age is substituted into a frame rather than each age being a whole sentence: a wording
    opening with its own argument would leave no literal run in front to match, which is the one
    shape no amount of prefix-matching can recognise.
    """
    statuses = status_lines()

    frame = catalogue_text("bot.CORPUS_COMPLETE_THROUGH")
    assert "{0}" in frame, (
        f"The freshness frame carries no argument, so there is no literal run in front of one to "
        f"identify it by: {frame!r}"
    )
    lagging = catalogue_text("bot.CORPUS_LAGGING").replace(
        "{0}", catalogue_text("bot.SOURCE_TELEGRAM")
    )
    assert is_status(lagging, statuses), f"not recognised: {lagging!r}"

    ages = catalogue_entries("bot.AGE_")
    assert ages, "no corpus-age wordings in the catalogue, so this checks nothing"

    for age in ages:
        # Eleven, because it is the count whose grammatical number the floor locale makes an
        # exception of — a wording that only renders for the teens is still a wording.
        said = frame.replace("{0}", age.replace("{0}", "11"))
        assert is_status(said, statuses), f"not recognised: {said!r}"
        assert is_status(f"{said} {lagging}", statuses), (
            f"not recognised with the lagging clause beside it: {said} {lagging}"
        )

    # **The shape the note actually arrives in now: appended to one of the bot's own lines.**
    # Every refusal is an argument-free sentence, so before the note became a footer each was
    # recognised by equality — and a footer makes the message equal to no catalogue value at all.
    # Left there, a refusal on a corpus that is behind reads as a model answer and `answered`
    # hands it back as one, which silently disables the assertion it exists to make on exactly the
    # state the note is rendered for.
    refusals = [line for line in statuses if "{" not in line and len(line) >= LITERAL_ENOUGH]
    assert refusals, "no argument-free catalogue sentence to append to, so this checks nothing"
    for refusal in refusals:
        assert is_status(f"{refusal}\n\n{lagging}", statuses), (
            f"a refusal carrying the corpus note reads as a model answer: {refusal!r}"
        )

    # Calibrated the other way: prose stays prose. Without this the test passes on a classifier
    # that called everything a status line, which would hide every answer the suite checks.
    assert not is_status("Договорились перенести встречу на пятницу.", statuses)

    # And calibrated against the hazard the prefix rule introduces, which is the reason it is
    # floored: the catalogue also holds short fragments that are arguments substituted into other
    # lines — what kind a subject is, a source's name — and an answer opening with one of those
    # words is ordinary prose. A rule matching those by prefix would discard the whole message.
    assert not is_status("контакт из этой переписки подтвердил цену в пятницу.", statuses)


def test_an_emphasised_catalogue_line_is_still_recognised_once_the_platform_has_parsed_it():
    """A catalogue line carrying emphasis is recognised from the text the platform delivers.

    **The defect this exists for is silent and points the wrong way.** The adapter sends in HTML
    mode, so a tag in an entry is parsed and gone — the message carries the words and not the
    markup. Every comparison in this suite is against delivered text, so an entry read as the
    bundle writes it matches nothing the moment emphasis is added to it. Going red would be the
    harmless outcome. What actually happens is worse: the guards that assert a particular refusal
    *did not* arrive stop being able to see it, so they pass on exactly the runs they were written
    to catch.

    Neither the stack nor an account: it is a function of the catalogue, like the cases above.
    """
    statuses = status_lines()

    raw = CATALOGUE.read_text(encoding="utf-8")
    emphasised = [
        line.split("=", 1)[1].strip().rstrip("\\")
        for line in (raw_line.strip() for raw_line in raw.splitlines())
        if "=" in line and not line.startswith("#") and MARKUP.search(line)
    ]
    assert emphasised, (
        "No catalogue entry carries markup, so this case passes over the thing it exists to "
        "check. It is calibration, not a style rule: if the emphasis was deliberately removed "
        "from every entry, remove this case with it."
    )

    for entry in emphasised:
        delivered = without_markup(entry)
        assert MARKUP.search(delivered) is None, (
            f"The markup survived stripping, so what is compared below is not what a reader "
            f"receives: {delivered!r}"
        )
        # Only the argument-free ones are matched by equality, which is the half that breaks on a
        # tag. A templated line is matched by the literal run before its first argument, and that
        # run is asserted on by the cases above.
        if "{" not in delivered:
            assert is_status(delivered, statuses), (
                f"This is one of the bot's own lines and is not recognised as one once the "
                f"platform has parsed its markup: {delivered!r}. Every comparison in this suite "
                f"is against delivered text, so the guards that watch for this reply have gone "
                f"blind rather than red."
            )


def test_a_drafted_alternative_is_not_mistaken_for_one_of_the_bot_s_own_lines():
    """The heading on a drafted alternative must not make the whole message read as catalogue text.

    **The inverse of the case above, and it is live rather than hypothetical.** The renderer puts a
    heading naming the alternative in front of every draft, so each draft message *begins* with a
    catalogue line. `is_status` matches a templated line by the literal run before its argument, and
    that heading's run is short enough not to match — which is the only reason the drafted-reply
    case sees drafts at all rather than discarding every one as the bot's own words. Reworded past
    the threshold, that case would fail saying the interface offered a choice of one, sending the
    reader to the index or to the configured subject.

    Neither the stack nor an account: it is a function of the catalogue, like the case above.
    """
    statuses = status_lines()

    heading = catalogue_text("bot.VARIANT")
    assert "{0}" in heading, (
        f"The alternative's heading carries no argument, so it is matched by equality and this "
        f"case checks nothing about the threshold: {heading!r}"
    )

    drafted = f"{heading.replace('{0}', '2')}\n\nDear sir, the fifth suits us."
    assert not is_status(drafted, statuses), (
        f"A drafted alternative reads as one of the bot's own lines, so every draft would be "
        f"discarded before any case could read one. The heading's literal run has grown past what "
        f"the classifier tolerates: {drafted!r}"
    )


def test_a_sources_block_is_not_mistaken_for_one_of_the_bot_s_own_lines():
    """A piece of an answer that opens with the sources block must not read as catalogue text.

    **The same hazard as the case above, reached the other way: by a line that is not a message.**
    The block is rendered after the answer in the same message, so it travels inside one — and the
    platform cuts a long answer at a line break, which can put the block at the head of a piece.
    Its literal run is comfortably past the threshold, so matched as a status line that whole piece
    would be dropped as furniture, taking the findings around it with it. The case counting one
    block per reply would then fail blaming the interface for discarding citations, which is the
    defect it exists to detect — a timing-and-classification miss wearing a real defect's costume.

    What keeps it out is `INSIDE_AN_ANSWER` rather than a short wording, because the wording is a
    reader's and should not have to stay under a threshold this suite happens to use.

    **Every shape of the block, not the one the archive happens to produce.** Which one is rendered
    depends on whether the corpus named the authors and whether the material falls on a single day,
    so checking one would leave the rest able to swallow a piece of an answer.

    **Found by prefix rather than by a list of names**, for the reason `catalogue_entries` gives:
    a shape added beside the ones that exist has to inherit this check, and a hand-written list is
    how it would silently not — reacquiring the defect this case exists to prevent. The exclusion
    list in `conversation` is read the same way, so the two cannot drift apart.

    Neither the stack nor an account: it is a function of the catalogue, like the cases above.
    """
    statuses = status_lines()

    keys = sources_keys()
    assert keys, (
        "No bot.SOURCES_* keys in the catalogue, so there is no block to check and this case "
        "would pass over the thing it exists to check."
    )
    for key in keys:
        cited = catalogue_text(key)
        assert "{0}" in cited, (
            f"{key} carries no argument, so it is matched by equality and this case checks "
            f"nothing about the threshold: {cited!r}"
        )
        # The precondition that makes this case mean something: were the run short, exclusion would
        # be doing no work and a green here would say nothing about it.
        literal = cited.split("{", 1)[0].strip()
        assert len(literal) >= LITERAL_ENOUGH, (
            f"{key}'s literal run is {len(literal)} characters, under the {LITERAL_ENOUGH} the "
            f"classifier needs to match on — so it could not read as a status line whatever this "
            f"suite excluded, and this case discriminates nothing: {literal!r}"
        )

        # Filled with what the renderer passes this shape — a name first only where the shape names
        # authors — so the line reads like the one it composes rather than merely starting like it.
        dates = ("02.10.2026", "05.10.2026") if "BETWEEN" in key else ("02.10.2026",)
        arguments = dates if "UNNAMED" in key else ("Кто-то", *dates)
        filled = cited
        for index, value in enumerate(arguments):
            filled = filled.replace(f"{{{index}}}", value)
        piece = f"{filled}\nОстальное по срокам."
        assert not is_status(piece, statuses), (
            f"A piece of an answer opening with {key} reads as one of the bot's own lines, so "
            f"every finding in it would be discarded before a case could read one: {piece!r}"
        )


@pytest.mark.corpus
@pytest.mark.telegram
@pytest.mark.stack
def test_a_question_comes_back_answered_from_the_corpus(telegram_account):
    question = "о чем договаривались"

    texts = ask(telegram_account, question)

    # Named before the generic assertion below, because this one reply has a cause of its own and
    # that assertion would report it as an empty index instead.
    #
    # The question above should never produce it, and what makes it reachable at all is worth
    # stating: this question routes to the shape that reads agreements, which accepts the inbox,
    # so the request *is* resolved for. The guard is therefore live rather than theoretical — it
    # fires when the routing call bound a shape and then failed to say what the request was about.
    #
    # That is a fault on the routing call's second line, not on its first. The request names
    # nobody, so the honest reading is the agreed word for nobody, which the inbox answers
    # corpus-wide; a reply of one line establishes nothing instead, and a subject not established
    # is refused rather than widened. So a red here means the classifier wrote one line where it
    # was asked for two, and never that routing chose the wrong shape.
    # Matched on what the message opens with rather than on the whole of it: the note about the
    # corpus is a footer on the first message, so on a corpus that is behind this refusal arrives
    # with a sentence appended and an equality test stops firing on exactly the state the note is
    # rendered for. The guard would then pass silently, which is the failure it exists to name.
    not_read = catalogue_text("backend.SUBJECT_NOT_READ")
    assert not any(not_read and text.strip().startswith(not_read) for text in texts), (
        "The bot asked who was meant, to a question that names nobody. Routing was right to bind "
        "this to the shape that reads agreements, which accepts the inbox — so what failed is the "
        "second line of the routing reply: it established no subject where it should have said "
        "nobody was named. Read the backend's routing line, which logs the shape and the subject."
    )

    # Named apart for the same reason as the reply above, and it is the most informative red this
    # suite can produce: two model calls, paid for in full, and the answer the request ended on not
    # read back. Reachable from this very question — the shape it routes to calls the model like
    # any other. Left to the assertion below it would report as an empty index,
    # sending the next reader to ingestion for a cause that is in the backend's own fault lines.
    unreadable = catalogue_text("backend.ANSWER_UNREADABLE")
    # Opening rather than equality, for the reason given above the guard before this one.
    assert not any(unreadable and text.strip().startswith(unreadable) for text in texts), (
        "The model answered, a re-roll was spent, and the reply the request ended on could not be "
        "read back, so it was given up after two calls. The backend wrote a fault line per attempt "
        "saying how each one came back and quoting the reply — read those rather than the index."
    )

    answers = answered(texts, question)
    answer = max(answers, key=len)

    assert len(answer) >= SUBSTANTIAL_CHARS, (
        f"The answer was {len(answer)} characters, under the {SUBSTANTIAL_CHARS} this requires. "
        f"A reply this short to a question about the whole corpus is a hedge rather than an "
        f"answer: {answer!r}"
    )

    assert ASSERTION in answer, (
        "The answer carries no cited assertions. They are most of an answer and they are what "
        "the grounding check validates, so a reply made only of summary prose means they were "
        "checked and then dropped on the way out."
    )
