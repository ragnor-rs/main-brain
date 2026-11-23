"""Whether the readers in `reading.py` can tell a property held from the same property broken.

**This is the half of the rendered tier that is not about the system at all.** A case over a live
stack asserts that a delivered brief is consistent with the store; if the reader behind that
assertion cannot fail, the case cannot fail either, and an assertion nobody has seen fall is not
known to work. So every reader is driven here with a passage that has the property and one that
breaks it, and the breaking arm is the one that matters.

**Hermetic on purpose, and that is why it lives in this file rather than inside the cases.** It
needs no account, no stack and no corpus, so it carries none of the markers that select the rest
of this tree — which is what lets it run in CI, where the cases it calibrates never can.

**Everything here is composed for the purpose: role labels for the parties, and figures chosen to
exercise a reading rather than taken from anything.** A party is written the way every other
fixture in this repository writes one, as what somebody does, which declines in Russian exactly as
a name does and is what the readers are actually held to. What the passages reproduce is *shape* —
a lead and then an account, two figures of days on one line, a deadline falling on a day another
item was stated — because that shape is what each reader has to survive.
"""

import ast
import pathlib
from datetime import date

import reading
from conversation import ASSERTION

#: The fixtures a case cannot take without reaching the world: a Telegram account, a name the
#: corpus holds, and what the store places on it.
OF_THE_WORLD = ("telegram_account", "archive_subject", "placed")

#: The helpers that reach it without a fixture — Compose, the store, and sending a message.
#:
#: Named beside the fixtures because the fixtures are not the whole of it: a case can import a
#: module and shell out, which is how the oldest case in this tree reaches Docker.
OF_THE_WORLD_CALLS = ("ask", "delivered", "emphasis", "states", "placed_on")

#: The markers that keep such a case out of a run that has no world to reach.
MARKERS = ("telegram", "stack", "corpus")


def test_every_case_that_reaches_the_world_declares_it():
    """No case in this tree may need the world without saying so in a marker.

    **This is what the hermetic selection rests on.** CI runs what carries none of the markers,
    and a deploy whose index has not caught up drops what carries `corpus` — both select by
    marker rather than by name, so a case added later without one is picked up by a run that has
    no account, no stack and no corpus to offer it. It would fail there for want of the world,
    which reads as a defect in the build.

    **Read off two signals, because a fixture is not the only way out.** A case asking for the
    account, the subject or the store says so in its parameters; one calling the harness's own
    drivers says so in its body, which is the shape the stack case has had since before any of
    these fixtures existed. Both lists are hand-kept and that is the weakness of this guard: a
    case reaching the world some third way is invisible to it, and nothing here can say so.
    """
    unmarked = []
    for module in sorted(pathlib.Path(__file__).parent.glob("test_*.py")):
        tree = ast.parse(module.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                continue
            if not node.name.startswith("test_"):
                continue
            takes = {argument.arg for argument in node.args.args}
            calls = {
                inner.func.id
                for inner in ast.walk(node)
                if isinstance(inner, ast.Call) and isinstance(inner.func, ast.Name)
            } | {
                inner.func.attr
                for inner in ast.walk(node)
                if isinstance(inner, ast.Call) and isinstance(inner.func, ast.Attribute)
            }
            reaches = takes.intersection(OF_THE_WORLD) or calls.intersection(OF_THE_WORLD_CALLS)
            if not reaches:
                continue
            declared = {
                attribute.attr
                for decorator in node.decorator_list
                for attribute in ast.walk(decorator)
                if isinstance(attribute, ast.Attribute) and attribute.attr in MARKERS
            }
            if not declared:
                unmarked.append(f"{module.name}::{node.name} (reaches {sorted(reaches)})")
    assert not unmarked, (
        f"These case(s) reach the world and carry none of {MARKERS}, so a run with no world to "
        f"offer them selects them and they fail for want of it: {unmarked}"
    )


#: A party as the store spells one, and the same party as a Russian sentence declines it.
#:
#: Chosen so the ending actually changes — a label whose forms are identical would pass this file
#: with the stem logic deleted.
STORED = "Подрядчик по интеграции"
DECLINED = "Договорённость с подрядчиком по интеграции остаётся без подтверждения."

#: A reply about somebody else, for the arm that has to go red. Same shape, same language, same
#: length — only the party is different.
ANOTHER = "Договорённость с поставщиком оборудования остаётся без подтверждения."

#: A reply that reports on a relationship without naming anybody in it, which is the defect the
#: naming assertion exists to catch: every fact there, and the reader unable to tell who it is
#: about.
NAMELESS = "Договорённость с клиентом остаётся без подтверждения, ответа пока нет."


def test_a_declined_party_is_still_the_one_the_store_holds():
    assert reading.mentions(DECLINED, STORED), (
        f"The store's spelling {STORED!r} was not found in a sentence that declines it, so every "
        f"naming assertion over a Russian reply would be red on correct prose. Stems looked for: "
        f"{reading.stems(STORED)}"
    )


def test_a_reply_naming_nobody_is_told_from_one_naming_the_party():
    assert not reading.mentions(NAMELESS, STORED), (
        "A passage naming nobody read as naming the stored party, so the naming assertion cannot "
        "fail for the reason it exists."
    )
    assert not reading.mentions(ANOTHER, STORED), (
        "A passage naming a different party read as naming the stored one. The stem is too short "
        "to discriminate, which makes every naming assertion vacuous."
    )


def test_every_word_of_a_spelling_has_to_be_there():
    """A spelling is looked for whole, which is what keeps a common word in one from carrying it.

    A display name routinely carries a role or a company beside the person, and the material hands
    the model the whole string — so a brief that names the party at all carries the whole of it.
    Matching on any one word instead, an ordinary sentence about that company satisfies the
    assertion while the brief names nobody.
    """
    assert not reading.mentions("Обсудили интеграцию и сроки по ней.", STORED), (
        "One word of the stored spelling carried the whole assertion, so a reply about the "
        "subject matter reads as a reply that named the party."
    )
    assert reading.mentions("Подрядчик по интеграции подтвердил сроки.", STORED)


def test_a_spelling_too_short_to_read_is_reported_rather_than_looked_for():
    """A two-letter run sits inside ordinary words, so a name that stems to one is not readable.

    Both halves matter: the short spelling yields no stem, so the case stands down and says so
    rather than passing on a reply that named nobody; and a four-letter one still reads, because
    standing down on every short name would give the assertion away.
    """
    assert reading.stems("Ян") == ()
    assert reading.readable(("Ян", "Подрядчик")) == ("Подрядчик",)
    assert reading.readable(("Ян",)) == ()
    assert reading.mentions("Иван подтвердил сроки.", "Иван")
    assert not reading.mentions("Игорь подтвердил сроки.", "Иван")


def test_a_vowel_final_word_keeps_its_declension_allowance():
    """A word ending in a vowel loses that vowel to the ending, whatever its length.

    The arm a fixed number of characters got wrong: four letters less two is two, raised to a
    floor, is the whole word again — and the declined form no longer contains it.
    """
    assert reading.mentions("Договорённость с Сашей остаётся.", "Саша")
    assert reading.mentions("письмо Марии", "Мария")
    assert reading.mentions("с Петра", "Пётр"), "ё and е are one letter to a reader"


#: One day, written the ways a reply can write it. The last two are what the material itself
#: carries, so a brief that repeats the date as it was given is covered too.
DUE = date(2026, 3, 12)
WRITTEN = (
    "срок — 12 марта, подтверждения нет",
    "срок 12.03.2026, подтверждения нет",
    "срок 12.03, подтверждения нет",
    "due 12 March, nothing recorded",
    "due 12th March, nothing recorded",
    "due 2026-03-12, nothing recorded",
    "срок 12-го марта, подтверждения нет",
)


def test_a_due_date_is_found_in_every_form_a_reply_writes_it_in():
    for passage in WRITTEN:
        assert reading.day_at(passage, DUE) >= 0, (
            f"A due date the store holds was not found in {passage!r}. A form this misses is a "
            f"presence assertion that reds on a brief that reported exactly the right item."
        )


def test_a_different_day_is_not_read_as_this_one():
    for passage in WRITTEN:
        assert reading.day_at(passage, date(2026, 3, 13)) < 0, (
            f"A date one day off matched {passage!r}, so the reader cannot tell two deadlines "
            f"apart and nothing it says about an item's presence means anything."
        )
    # The neighbouring day of the month, which the optional leading zero is what risks: a pattern
    # for the 2nd must not match the 12th.
    assert reading.day_at("срок 12 марта", date(2026, 3, 2)) < 0
    assert reading.day_at("срок 12.03.2026", date(2026, 3, 1)) < 0


def test_a_figure_of_days_is_read_in_both_russian_forms():
    """The form the count itself decides, and a pattern carrying one of them reds on the other.

    A count ending in one takes *день* and every other count a form of *дн-*, so an item one,
    twenty-one or thirty-one days past is written the first way — and a reader that knows only the
    second reports it absent from a brief that reported it.
    """
    for days, word in ((1, "день"), (21, "день"), (2, "дня"), (16, "дней"), (31, "день")):
        assert reading.overdue_at(f"срок прошёл {days} {word} назад", days) >= 0, (
            f"{days} {word} was not read as a figure of {days} days."
        )
    assert reading.overdue_at("33 days past", 33) >= 0


def test_a_figure_of_days_past_is_told_from_a_bare_number():
    assert reading.overdue_at("сумма 33 000", 33) < 0, (
        "A number that measures something else read as a figure of days, so the locator can point "
        "at the wrong sentence."
    )
    assert reading.overdue_at("срок прошёл 33 дня назад", 7) < 0


#: A reply of the shape a brief arrives in, cut down to what the order is read off.
#:
#: **Three things about it are composed to exercise a reading rather than to look realistic.** The
#: item that leads carries its figure of days and *not* its deadline, which is what a brief does
#: when it reports the standing and dates the message instead. The second item was stated on the
#: very day the first one fell due, which is why an order is not read by date: matched that way,
#: the leading item is found inside the sentence about the other one. And the block naming where
#: the material came from is on the end, carrying the subject's own spelling and a span of dates —
#: the interface's furniture, which every reading here has to be taken in front of.
LEAD_AND_ACCOUNT = "\n".join((
    "• Отношения держатся на трёх обязательствах, самое давнее заявлено 40 дней назад.",
    "• Звонок упирается в смету: срок прошёл 30 дней назад.",
    "",
    "Где всё стоит",
    "",
    "• По обязательствам, названным ниже, результат в записях не зафиксирован.",
    "• 10 марта 2026 обещана смета со сроком 12 марта 2026; срок прошёл 30 дней назад, "
    "последний раз заявлено 40 дней назад.",
    "• 12 марта 2026 обещаны доступы к контуру к 20 марта 2026; срок прошёл 22 дня назад, "
    "последний раз заявлено 38 дней назад.",
    "",
    "Источники ответа: Подрядчик по интеграции — сообщения с 10.03.2026 по 12.03.2026.",
))

#: The same account with its two items swapped, which is the brief the store's own by-recency
#: ordering produces: the less overdue item first.
STORE_ORDER = "\n".join(
    LEAD_AND_ACCOUNT.splitlines()[:6]
    + [LEAD_AND_ACCOUNT.splitlines()[7], LEAD_AND_ACCOUNT.splitlines()[6]]
    + LEAD_AND_ACCOUNT.splitlines()[8:]
)

#: The leading item dropped, which is what a bound that cuts from the wrong end produces. Its
#: mention in the lead stays, because that is what made this worth a case: an item named in the
#: lead and absent from the account is still an item the brief does not report.
LEADING_CUT = "\n".join(
    line for line in LEAD_AND_ACCOUNT.splitlines() if "обещана смета" not in line
)

#: The same reply with its lead fused into one assertion, which the instruction permits — it asks
#: for two facts and not for two lines.
ONE_LINE_LEAD = "\n".join(LEAD_AND_ACCOUNT.splitlines()[1:])

LEADING_DAYS, LATER_DAYS = 30, 22
LEADING_DUE, LATER_DUE = date(2026, 3, 12), date(2026, 3, 20)

#: The literal the sources block opens with, as the cases read it off the catalogue.
SOURCES = "Источники ответа:"


def account(brief: str) -> str:
    return reading.after_the_lead(reading.before_the_sources(brief, SOURCES), ASSERTION)


def test_the_lead_is_not_read_as_the_account():
    """The two facts a brief opens with are a judgement about the call, not an ordering of items.

    Both arms matter: the account has to start after them, and it has to start somewhere — a
    reader that returned the whole brief would put the lead's own mention of the leading item in
    front of every item and make the order unfalsifiable.
    """
    assert "Отношения держатся" not in account(LEAD_AND_ACCOUNT)
    assert "обещана смета" in account(LEAD_AND_ACCOUNT)
    assert account("• один\n• два") == "", (
        "A reply with nothing past its lead returned an account, so a brief that reported no "
        "items at all would have its order asserted against nothing."
    )


def test_a_lead_of_one_assertion_does_not_eat_the_first_item():
    """The heading is what separates the lead from the account, not a count of assertions.

    Counting two, a lead that arrived as one line puts the account's first item in the lead's
    place — and the item the brief led with then reads as absent, which reds on correct prose.
    """
    opening = account(ONE_LINE_LEAD)
    assert "обещана смета" in opening, (
        "The leading item was cut out of the account by a lead shorter than two assertions, so "
        "the presence assertion would red on a brief that reported it first."
    )
    assert "Звонок упирается" not in opening


def test_the_furthest_past_leading_is_told_from_the_order_the_store_returned():
    held, later = (reading.overdue_at(account(LEAD_AND_ACCOUNT), days)
                   for days in (LEADING_DAYS, LATER_DAYS))
    assert 0 <= held < later, f"the brief as written read as out of order: {held} vs {later}"

    swapped = account(STORE_ORDER)
    out_of_order = reading.overdue_at(swapped, LEADING_DAYS)
    assert out_of_order > reading.overdue_at(swapped, LATER_DAYS) >= 0, (
        "An account in the order the store returned — the less overdue item first — read as one "
        "led by what fell due furthest past. That is the defect this assertion exists for, so it "
        "cannot pass here."
    )


def test_an_item_cut_from_the_account_is_told_from_one_that_is_in_it():
    """The reading that catches a bound cutting from the wrong end, and why it is the figure.

    The cut account still writes the dropped item's deadline, because that day is the day the
    surviving item was stated on — so by date the dropped item is found and the cut is invisible.
    By the figure it is absent, which is what the case over a live brief reads where the figure is
    unambiguous.
    """
    cut = account(LEADING_CUT)
    assert reading.overdue_at(cut, LEADING_DAYS) < 0, (
        "The item the bound dropped was still located by its figure, so a brief that cut from the "
        "wrong end reads as one that reported everything."
    )
    assert reading.overdue_at(cut, LATER_DAYS) >= 0, (
        "The surviving item carries no figure either, so this fixture says nothing about the "
        "reading — it would stand down on a real brief rather than discriminate."
    )
    assert reading.located(cut, LEADING_DUE, None) >= 0, (
        "The dropped item's deadline is no longer in the cut account, so this case has stopped "
        "being the measurement it records: the lenient reading would catch the cut here and the "
        "figure would look unnecessary."
    )
    assert reading.overdue_at(account(LEAD_AND_ACCOUNT), LEADING_DAYS) >= 0


def test_a_deadline_that_is_another_items_own_date_is_why_the_order_reads_a_figure():
    """The measurement behind the choice, kept as a case so the choice cannot be quietly undone.

    The leading item's deadline is the day the *other* item was stated on, so by date it is found
    inside that item's sentence — later in the brief than the item it belongs to. Read that way,
    the swapped account above would pass.
    """
    swapped = account(STORE_ORDER)
    by_date = reading.day_at(swapped, LEADING_DUE)
    by_figure = reading.overdue_at(swapped, LEADING_DAYS)
    assert by_date >= 0 and by_figure >= 0
    assert by_date < by_figure, (
        "The date no longer collides with the other item's own date, so this case is no longer "
        "the measurement it was written to record — and the figure may now be unnecessary. "
        "Re-measure against a live brief before simplifying it away."
    )


def test_the_sources_block_is_cut_before_anything_is_read_off_the_prose():
    """The interface's own furniture, which carries the subject's name and a span of dates.

    **Uncut, the naming assertion passes on every run.** The block is rendered inside the composed
    reply and names whoever wrote the cited material — which, for a shape whose findings are
    narrowed to what the subject stated, is the subject. So a reply that names nobody in its prose
    is indistinguishable from one that names them, and the assertion is carried by the renderer.

    The dates it carries are the same hazard one layer down: a deadline falling on a day the cited
    material was written is located inside the block, so an item the brief dropped reads as
    present.
    """
    prose = reading.before_the_sources(LEAD_AND_ACCOUNT, SOURCES)
    assert reading.mentions(LEAD_AND_ACCOUNT, STORED), "the block does carry the stored spelling"
    assert not reading.mentions(prose, STORED), (
        "The stored spelling survived the cut, so the naming assertion is satisfied by the "
        "interface's furniture rather than by the prose."
    )
    assert reading.day_at(LEAD_AND_ACCOUNT, date(2026, 3, 10)) >= 0
    cut = reading.before_the_sources(LEADING_CUT, SOURCES)
    assert reading.day_at(cut, date(2026, 3, 10)) < 0, (
        "A date the sources block carries was still found after the cut, so a dropped item can be "
        "located in the block that lists where the answer's material came from."
    )


def test_an_item_is_read_as_reported_only_where_its_token_is_its_own():
    """The reading that tells a brief which worded its figure from one that dropped the item.

    Both arms turn on the same collision: the leading item's deadline is the day the other item
    was stated on. In its own line, beside its own figure, it reports the item; in the other
    item's line, beside *that* item's figure, it reports nothing — which is what keeps a brief
    that cut from the wrong end from reading as one that reported everything.
    """
    whole, cut = account(LEAD_AND_ACCOUNT), account(LEADING_CUT)
    assert reading.present(whole, LEADING_DUE, None, (LATER_DAYS,)), (
        "An item whose line carries its own deadline and no figure read as absent, so a brief "
        "that reported the standing in words rather than in digits would red."
    )
    assert not reading.present(cut, LEADING_DUE, None, (LATER_DAYS,)), (
        "A dropped item was read as reported, off a deadline sitting inside the surviving item's "
        "own sentence — which is the collision this reading exists to survive."
    )
    assert reading.present(whole, LEADING_DUE, LEADING_DAYS, (LATER_DAYS,))
    assert not reading.present(cut, LEADING_DUE, LEADING_DAYS, (LATER_DAYS,))


def test_a_source_is_counted_once_however_often_it_is_cited():
    """The reading behind the floor on a researched answer's sources.

    **Distinct sources and not occurrences, because the floor means the first.** A reply resting on
    one authority cited beside several findings is what the floor exists to refuse, and counting
    occurrences passes it — which would leave the assertion reading as a bound on breadth while
    measuring verbosity.

    The other arm is the defect the floor is really for: sources dropped between the model and the
    reader, which is this build's unaffordable failure in the one shape whose sources are all it
    has.
    """
    one_source_thrice = (
        "Ставка 18 процентов (https://a.example/vat). "
        "Порог тот же (https://a.example/vat). "
        "И регистрация (https://a.example/vat)."
    )
    assert len(reading.sources(one_source_thrice)) == 1, (
        "One source cited three times counted as three, so the floor on how many sources a "
        "researched answer rests on is satisfied by repetition."
    )
    assert len(reading.sources("Ставка 18 процентов. Порог 100 000 лари.")) == 0, (
        "A reply carrying no source at all read as carrying one, so the floor cannot fail."
    )
    two = reading.sources("Ставка (https://a.example/x) и порог (https://b.example/y).")
    assert len(two) == 2, f"two distinct sources read as {len(two)}"


def test_a_source_written_as_a_figure_is_told_from_ordinary_prose():
    """The absence that refuses a source written the one way it may not be.

    **Both directions, because this pattern's whole value is that it does not fire on good prose.**
    A researched answer naming its sources and linking them carries no bracketed figure anywhere,
    and the interface renders none of its own — so a hit is the model having reached for the
    footnote habit. The forms below are the ones that habit produces, including a figure wearing a
    link, which is the shape a reader would least expect to be a footnote.
    """
    assert not reading.numbered_sources(
        "Ставка 18 процентов (https://a.example/x), что подтверждает и второй источник."
    ), (
        "A correctly sourced reply read as carrying a numbered source, so the absence reds on "
        "good prose."
    )
    assert reading.numbered_sources("Ставка 18 процентов [1]. Порог 100 000 лари [2]."), (
        "A footnoted source read as absent, so the assertion passes on the defect it names."
    )
    assert reading.numbered_sources("Ставка 18 процентов [1](https://a.example/x)."), (
        "A figure wearing a link read as absent — the bracket is still a reference into a list "
        "nothing renders."
    )


def test_a_web_source_in_a_claim_line_is_told_from_a_claim_about_held_material():
    """The relation that refuses a web finding written where the corpus is asserted about.

    **And the state in which it can say nothing**, which is the reason the case reporting it asks
    for the claim lines separately: a reply with none satisfies the relation without the rule having
    been obeyed anywhere, and that is the ordinary case for a question the held sample bears nothing
    on. Read without that question asked, a green here means only that there was nothing to read.
    """
    held = f"Обзор рынка (https://a.example/x).\n{ASSERTION} Регистрацию отнесли к марту."
    assert not reading.claims_carrying_a_source(held, ASSERTION), (
        "A claim line about held material read as a web finding, so the relation reds on a reply "
        "obeying the rule."
    )
    assert reading.claims(held, ASSERTION), "the fixture does carry a claim line to read"

    web = f"Обзор.\n{ASSERTION} Ставка 18 процентов (https://a.example/x)"
    assert reading.claims_carrying_a_source(web, ASSERTION), (
        "A web finding inside a claim line read as absent, so the relation passes on the defect "
        "it names."
    )

    none = "Обзор рынка (https://a.example/x) и ещё один источник (https://b.example/y)."
    assert not reading.claims_carrying_a_source(none, ASSERTION), none
    assert not reading.claims(none, ASSERTION), (
        "A reply with no claim lines reported some, so a case cannot tell *none carried a source* "
        "from *none existed* — which is the distinction that keeps the relation from reading as "
        "coverage it did not earn."
    )
