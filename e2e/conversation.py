"""Asking the bot something and reading what it sent back.

**Shared because the hard part is telling an answer from the bot's own words**, not sending the
message. The catalogue is the authority on that, and a case that listed the lines it knew about
would pass over the one it had not heard of — which is how a reply that answers nothing reads as
an answer. One reader of the catalogue, used by every case.

Everything here observes the conversation the way a person does: a message is sent from a user
account and the chat is polled for what arrives. Nothing *about the reply* is inspected from inside
the stack, so a failure says the system did not answer and the unit suites say which part of it did
not. Where a case needs to know what a correct answer would have carried, it reads the store for
that — see `store.py`, which is the expectation and never the observation.
"""

import asyncio
import re
import time
from pathlib import Path

from telethon import TelegramClient
from telethon.sessions import StringSession
from telethon.tl.types import MessageEntityBold

#: Where the bot's Russian catalogue lives, read so that a status reply can be recognised.
#:
#: Russian is the floor and every key resolves in it, so this file alone is enough to know
#: every sentence the bot can send that is not a model's prose.
CATALOGUE = (
    Path(__file__).resolve().parent.parent
    / "bot/src/main/resources/i18n/messages_ru.properties"
)

#: What separates one cited assertion from the next in a composed answer.
#:
#: Asserted on because the assertions are most of an answer: they were validated and then
#: dropped before the reply was sent once, which left a grounded answer reading as a hedge
#: while every component reported healthy.
#:
#: It is also what must *not* appear in a drafted reply. The marker is the one thing that is
#: identical in both directions, which is why the two rules can be asserted the same way.
ASSERTION = "•"

#: Long enough that a model call and a retrieval both fit, including a cold moment on a loaded
#: machine. Short enough that a bot which will never answer is reported rather than waited on.
#:
#: The window a case gets unless it asks for another, which one shape has to — see
#: [RESEARCH_SECONDS]. This figure stays sized for a question answered from the corpus, because
#: widening it for every case would make a bot that will never answer take the longest shape's
#: patience to report.
REPLY_SECONDS = 300

#: How long the shape that reads the open web may take, which is longer than it may answer in.
#:
#: **Derived from the interface's own bound rather than guessed, because the two disagreeing is a
#: defect wearing a timeout's clothes.** The bot gives an answer fifteen minutes and the evaluation
#: set reads the same path on the same figure. A window shorter than that expires while the answer
#: is still coming and reports *the bot sent nothing* — which is precisely the symptom of the shape
#: being undeliverable, the thing a case here exists to catch, so a narrow window would have failed
#: for its own clock and been read as evidence about the stack. The shape needs the room: the same
#: fixed question has taken over six minutes and barely one, so what a window can be sized to is
#: the bound and never an observation.
#:
#: **Past the bound the bot says so in its own words, and reading that sentence is why this is not
#: the bound itself.** An answer that overran and a bot that never spoke are different failures and
#: only the first is diagnostic, so the window has to outlast the moment the refusal is composed —
#: which is later than the budget by four quantities, none of them inside it:
#:
#: * filing the work happens before the budget's clock starts, under one hop ceiling;
#: * the clock is read *before* a hop rather than after, so a hop may begin just inside it and run
#:   another hop ceiling;
#: * one collect interval follows that hop before the loop re-reads the clock;
#: * and this suite settles on a poll that adds nothing, so it needs two more poll periods after
#:   the message lands.
#:
#: At the figures those four carry today that is a little over the budget plus a minute, and this is
#: sized above it with the rest as margin. Written out rather than folded into one number so that
#: raising a hop ceiling or a poll period is visibly load-bearing here: the symptom of this being
#: too small is not a timeout, it is this suite reporting an absent reply for a reply it hung up on.
RESEARCH_SECONDS = 1020

#: Polled rather than subscribed: the suite drives a user account and reads its own chat, and a
#: poll is the same observation a person makes.
POLL_SECONDS = 5

#: Below this a reply is not an answer to a question about a corpus of thousands of messages.
#: Not a quality measure — a floor, sized well under what a real answer has measured at, so
#: that it fails on a status line or a bare sentence rather than on a terse good answer.
SUBSTANTIAL_CHARS = 200

#: Above this, a brief meant to be read in the minute before a call is not readable in it.
#:
#: The one shape with a stated ceiling rather than only a floor, which is what its own card is
#: about: the fault it was raised on was a brief of three screens, and every other assertion over
#: that shape was satisfied by it. A ceiling is therefore the only form of the complaint a test
#: can carry. Sized from the shape's bound — five items, three questions and a two-sentence lead,
#: each item carrying a citation block — with enough headroom that a richer subject than the one
#: this was reasoned over does not red it, and far enough below the three screens that produced
#: the card that it still discriminates.
READABLE_BEFORE_A_CALL_CHARS = 3500

#: How many questions the brief may propose, which is its own instruction's number.
#:
#: Asserted rather than assumed because it is the half of the bound that survives rendering: an
#: item's boundaries are a matter of how the interface lays claims out, where a question is a
#: sentence ending in a question mark however it is presented.
PROPOSED_QUESTIONS = 3

#: The shortest literal run of a templated line that is allowed to identify it.
#:
#: A line carrying an argument cannot be matched by equality, so it is matched by the literal
#: text in front of the argument instead. Some of those runs are empty or near-empty — a line
#: that opens with its argument has nothing in front of it — and a short one would match prose
#: that merely began the same way, which would read a real answer as a status line.
LITERAL_ENOUGH = 12

#: How many replies back to read. More than any one request produces, counted from the shape that
#: produces the most: a menu's prompt, an alternative and what it rests on for each of up to three,
#: and the note naming subjects that matched and lost — and a reader reads its own request too.
#: How current the corpus is rides on the first message rather than arriving as one of these.
#: Sized above the count rather than at it, because a long alternative is cut into pieces and a
#: window read exactly to the ceiling stops while messages are still arriving.
WINDOW = 16

#: How a key for one shape of the sources block is spelled.
#:
#: Trailing underscore so that it selects the block's own wordings and nothing else named for a
#: source — `SOURCE_DATE` is a pattern and `SOURCE_TELEGRAM` is a source's name, and neither is one
#: of these.
SOURCES_PREFIX = "bot.SOURCES_"

#: Keys whose value is a fragment substituted into another line rather than a message.
#:
#: By prefix, so a wording added beside the ones that exist inherits the exclusion. What they have
#: in common is position: `Age.since` and the coverage clause compose these *into* a sentence, so
#: none of them is ever the whole of anything the bot sends.
FRAGMENT_PREFIXES = ("bot.AGE_", "bot.SEARCHED_", "bot.SOURCE_")


def sources_keys() -> list[str]:
    """Every catalogue key naming one shape of the sources block.

    By prefix rather than by a list of names, for the reason `catalogue_entries` gives: a shape
    added beside the ones that exist has to inherit whatever is asked of these, and a hand-written
    list is how it would silently not.
    """
    return catalogue_keys(SOURCES_PREFIX)


def inside_an_answer() -> set[str]:
    """Catalogue keys whose text is not a message of its own.

    `is_status` tells the bot's own words from a model's prose by matching whole messages, which
    holds for every key that is a reply. These are not, for two reasons.

    The `SOURCES_*` entries are a block rendered after the answer in the same message, so they
    travel inside one — and the platform cuts a long answer at a line break, which can put the
    block at the head of a piece. Matched as a status line, that whole piece would be discarded as
    furniture, taking the findings around it with it.

    The entries under [FRAGMENT_PREFIXES] are substituted into other lines rather than sent — how
    long ago the material is, the kinds of thing a refusal looked among, the name of a source and
    the pattern a date is written with. Length is what admits an entry to the opening match, and
    one of these clears it: *more than a month ago* is eighteen characters and argument-free, so a
    model answer opening with those words would be read as the bot's own text and dropped. That is
    `answered` reporting *nothing was answered from the corpus* about a reply that answered it.
    """
    fragments = {
        key
        for prefix in FRAGMENT_PREFIXES
        for key in catalogue_keys(prefix)
    }
    return set(sources_keys()) | fragments


#: A tag in a catalogue entry, which the platform consumes rather than delivers.
#:
#: **Any tag, not the ones this build happens to render.** The bot refuses at startup a bundle
#: holding a tag it cannot render, so by the time an entry is in a shipped bundle the only tags in
#: it are permitted ones — which makes a shape good enough here and leaves nothing to keep in step
#: with the Kotlin declaration. A list of tag names copied over here would be a second declaration
#: that agreed until one of them was edited, and the symptom of their disagreeing is this file
#: quietly ceasing to recognise the bot's own words.
MARKUP = re.compile(r"</?[a-z][a-z0-9-]*>")


def without_markup(value: str) -> str:
    """`value` as the platform delivers it, with the tags taken out.

    **Every comparison against a delivered message needs this.** The adapter sends in the
    platform's HTML mode, so a tag in an entry is parsed and gone: the text that arrives carries
    the words and not the markup. Compared as the bundle writes it, an entry the bot rendered
    perfectly matches nothing — and the failure is silent in the direction that matters, because a
    guard asserting *this refusal did not arrive* stops firing rather than going red.

    The entities go back to the characters they stand for, for the same reason: `&amp;` reaches a
    reader as `&`.
    """
    text = MARKUP.sub("", value)
    # In this order. Going the other way would turn `&amp;lt;` — an entry meaning the four
    # characters `&lt;` — into a `<`, which is not what any reader sees.
    for entity, character in (("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&")):
        text = text.replace(entity, character)
    return text


def _value(raw: str) -> str:
    """One properties value as the loader that reads it would, rather than as the file spells it.

    **The file is read by Java's `Properties`, which decodes escapes; reading it as text here does
    not.** A multi-line entry is written with a trailing `\\` continuing the line and `\\n` where
    the message breaks, so a value read literally carries the two characters backslash and `n`
    exactly where the delivered message carries a newline. A case comparing the two then fails on
    an entry that is perfectly correct, and the diagnostic it prints shows two strings that look
    identical — which is how it presents, and why this is shared rather than fixed at one caller.

    Only `\\n` is decoded, because it is the only escape the catalogue uses. Another one appearing
    is a reason to extend this, not to work around it at a call site.

    The tags come off here too, for the reason [without_markup] gives: what every caller of this
    compares against is a delivered message, and the platform consumed the markup on the way.
    """
    return without_markup(raw.strip().rstrip("\\").replace("\\n", "\n"))


def status_lines() -> list[str]:
    """Every sentence the bot can send that did not come from the model.

    Read from the catalogue rather than listed here, so a key added later is covered without
    this file being edited — a status line this did not know about is exactly how a reply that
    answers nothing would pass.

    Bar the lines that are not messages — see [inside_an_answer]. Those are the bot's own words
    too, and a reply made of them is not a thing that can arrive.
    """
    excluded = inside_an_answer()
    assert CATALOGUE.is_file(), (
        f"No catalogue at {CATALOGUE}. These tests recognise a status reply by its text, so a "
        f"missing catalogue has to fail rather than pass over nothing."
    )
    lines = []
    for raw in CATALOGUE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() in excluded:
            continue
        decoded = _value(value)
        if decoded:
            lines.append(decoded)
    assert lines, f"{CATALOGUE} parsed to no values at all."
    return lines


def catalogue_text(key: str) -> str:
    """The one catalogue line `key` resolves to, or `""` if the catalogue has no such key.

    Read by key rather than by position, so that naming one reply apart from the rest does not
    turn into an index into a file. Empty on a missing key rather than an assertion: callers use
    it to say *this particular reply would mean something specific*, and a key that is not there
    means that reply cannot arrive, which is not a test's business to police. `CatalogueTest` in
    `:bot` is what holds every key to resolving.
    """
    for raw in CATALOGUE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith(f"{key}="):
            return _value(line.split("=", 1)[1])
    return ""


def catalogue_keys(prefix: str) -> list[str]:
    """Every catalogue key starting with `prefix`, for a caller that needs the names.

    The sibling below returns the values, which is what a caller matching delivered text wants.
    A caller asking something of each wording — that it carries an argument, that its literal run
    is long enough — needs the key as well, to say which one failed.
    """
    found = []
    for raw in CATALOGUE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith(prefix) and "=" in line:
            found.append(line.split("=", 1)[0].strip())
    return found


def catalogue_entries(prefix: str) -> list[str]:
    """Every catalogue value whose key starts with `prefix`.

    By prefix rather than by a list of key names, so that a wording added beside the ones that
    exist is covered without this file being edited — the same reason `status_lines` reads the
    whole file. A caller asserts the result is non-empty; a prefix that matches nothing is a
    renamed key, and a loop over nothing passes.
    """
    found = []
    for raw in CATALOGUE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith(prefix) and "=" in line:
            found.append(_value(line.split("=", 1)[1]))
    return found


def catalogue_emphasis(key: str) -> list[str]:
    """The runs the entry for `key` marks as emphasised, as the bundle writes them.

    **The whole logical entry, not one physical line.** A bundle continues a line with a trailing
    backslash and the command list is written that way, so the emphasis is on lines two to six of
    it — a reader of a single physical line would find none and conclude there was none to find.

    Returned so that a delivery case can hold the platform's parse against what the bundle asked
    for, without holding an opinion about which commands exist: that belongs where the shapes are
    declared, and `HelpTextTest` is what asserts it.
    """
    joined: list[str] = []
    buffer = ""
    for raw in CATALOGUE.read_text(encoding="utf-8").splitlines():
        line = buffer + raw.lstrip() if buffer else raw
        # An *odd* number of trailing backslashes continues the entry, which is what Properties
        # does. Treating any trailing backslash as a continuation would swallow the following line
        # of an entry whose value legitimately ends in one.
        if (len(line) - len(line.rstrip("\\"))) % 2:
            buffer = line[:-1]
            continue
        buffer = ""
        joined.append(line)
    for line in joined:
        if line.strip().startswith(f"{key}="):
            # The entities are decoded, because what these are compared against is the text the
            # platform parsed — an entry writing `&amp;` means one character to a reader, and a
            # run compared as written would be a permanent red blaming the emphasis for it.
            return [without_markup(run) for run in EMPHASISED.findall(line.split("=", 1)[1])]
    return []


#: One emphasised run in a catalogue entry. Non-greedy, so two runs on a line read as two.
#:
#: Bold specifically, and not the vocabulary: this is the half of a pair, read against the bold
#: entities the platform reports, so the tag named here and the entity type named there have to be
#: the same one. A second emphasis would want its own pair rather than widening this.
EMPHASISED = re.compile(r"<b>(.+?)</b>")


def is_status(text: str, statuses: list[str]) -> bool:
    """Whether `text` is one of the bot's own lines rather than a model's prose.

    **Matched on what a message opens with, not on the whole of it, because the bot appends to
    its own lines.** The note about the corpus is a footer on the first message, so a refusal
    arrives as the catalogue sentence followed by that note and equals no catalogue value at all.
    Matched by equality, every argument-free refusal then reads as a model answer — which
    silently disables the one thing `answered` exists to assert, on exactly the corpus state the
    footer is rendered for. A line is identified by its opening because that is the part the bot
    composed; what follows it is more of the bot.

    Two kinds of entry need the opening read rather than compared. One carries an argument, so
    how old the material is gets substituted in and it matches nothing exactly. The other is a
    whole sentence that something was appended to.

    [LITERAL_ENOUGH] guards both, and for the second kind it is not enough on its own. The
    catalogue also holds fragments that are arguments substituted into other lines — the kinds a
    subject can be, the name of a source, how long ago something was — and a model's answer
    opening with one of those words is ordinary. Most are short enough that the floor excludes
    them, and at least one is not: they are therefore excluded by what they are rather than by how
    long they happen to be, which is [inside_an_answer]'s job and not this one's.
    """
    said = text.strip()
    for line in statuses:
        if "{" not in line:
            if said == line:
                return True
            if len(line) >= LITERAL_ENOUGH and said.startswith(line):
                return True
            continue
        literal = line.split("{", 1)[0].strip()
        if len(literal) >= LITERAL_ENOUGH and said.startswith(literal):
            return True
    return False


def ask(account, request: str, window: int = REPLY_SECONDS) -> list[str]:
    """Send `request` to the bot and return every message it sent back, in order.

    **A case widening [window] passes the same figure to [answered]**, which is what keeps the
    seconds named in a failure message the seconds that actually elapsed. A case that widens one
    and not the other gets a true failure with a wrong number in it.

    **Every message, not the longest or the first.** A request that offers alternatives answers
    in several, and which of them carries what is the thing a rendering case is about — so the
    reply is handed over whole and each case decides what to hold it to.

    **Read once the count stops growing, rather than one poll after the first arrival.** A reply is
    several messages, and the shape producing the most sends the menu's prompt, an alternative and
    what it rests on for each of up to three, and the note naming subjects that matched and lost —
    which is what [WINDOW] is sized against. Taking a single
    extra poll as the whole margin makes a late alternative arrive after the read — and a case
    counting alternatives then fails saying the interface offered a choice of one, which is a
    rendering defect this suite also tests for. A timing miss must not be able to wear that costume,
    so this waits for a poll that adds nothing.

    The text of what `delivered` returns, so that the sending, the waiting and the reasoning for
    both live in one place: a second copy of this loop would have to be kept in step by hand, and
    the justification for [WINDOW] and for waiting on a quiet poll would sit on only one of them.
    """
    return [text for text, _ in delivered(account, request, window)]


async def _replies(client, bot, after: int) -> list[tuple[str, list[type]]]:
    """What the bot has said since message `after`, oldest first, as a reader receives it.

    Each message as its text and the kinds of emphasis the platform will render for it.

    **`raw_text` and not `text` for the words.** The second re-encodes the formatting the bot sent
    as markdown, so a bold heading comes back wrapped in literal asterisks. Most cases here are
    about what reaches a person, and a length or a marker measured over re-encoded markup is
    measured over something nobody is shown.

    **The entities and not the characters for the formatting.** The markup goes down the wire as
    tags, the platform parses them into entities and the characters are gone — so a case searching
    the text for a tag would be searching for something correctly absent whether the formatting is
    there or not. The entity list is what the client renders, which is as close to *what a person
    is shown* as a test can get. Read here beside the text rather than by a second request, because
    a request costs a model call.

    **The classes themselves and not their names**, which is the rule `_bold_runs` already states:
    a renamed or re-parented entity class makes a comparison against the class fail loudly, where
    one against its name quietly reports that nothing was emphasised — the answer a formatting case
    is asking for, arriving for the wrong reason.
    """
    return [
        (message.raw_text, [type(entity) for entity in (message.entities or [])])
        for message in reversed(await client.get_messages(bot, limit=WINDOW))
        if message.id > after and not message.out and (message.raw_text or "").strip()
    ]


def delivered(
    account, request: str, window: int = REPLY_SECONDS
) -> list[tuple[str, list[type]]]:
    """Send `request` and return each message as its text and the emphasis the platform renders.

    What `ask` is the text of. A case that needs the formatting reads this; one that needs only
    the words reads `ask`, and both pay for the one request.

    [window] is how long to wait, which is this function's because the loop is. One shape needs
    more than the default — see [RESEARCH_SECONDS].
    """

    async def conversation():
        client = TelegramClient(
            StringSession(account.session_string()),
            account.api_id(),
            account.api_hash(),
        )
        await client.connect()
        try:
            assert await client.is_user_authorized(), (
                "The user session is not authorized, so nothing was asked. `test_session.py` "
                "reports this on its own; it is repeated here because a bot that never answers "
                "reads as a defect in the bot."
            )
            bot = account.bot_username()
            sent = await client.send_message(bot, request)

            deadline = time.time() + window
            seen: list[tuple[str, list[type]]] = []
            while time.time() < deadline:
                await asyncio.sleep(POLL_SECONDS)
                replies = await _replies(client, bot, sent.id)
                if replies and len(replies) == len(seen):
                    break
                seen = replies
            return seen
        finally:
            await client.disconnect()

    return asyncio.run(conversation())


def emphasis(account, request: str) -> list[list[str]]:
    """The runs the platform itself says are emphasised, per reply, in order.

    **The one thing about formatting that cannot be read off delivered text.** The markup crosses
    the wire as tags, the platform parses them, and the characters are gone — so a case searching
    a message for a tag searches for something correctly absent whether the formatting worked or
    not. What survives the parse is the platform's entity model, and that is its own report of
    what a reader is shown: if it says a range is bold, it renders bold.

    Offsets are counted in UTF-16 code units, which is what the platform counts in, so the text is
    sliced there rather than by character index. The two agree on Cyrillic and Latin alike and part
    company on anything outside the basic plane — one emoji earlier in a message would shift every
    run after it.
    """

    async def conversation():
        client = TelegramClient(
            StringSession(account.session_string()),
            account.api_id(),
            account.api_hash(),
        )
        await client.connect()
        try:
            assert await client.is_user_authorized(), (
                "The user session is not authorized, so nothing was asked."
            )
            bot = account.bot_username()
            sent = await client.send_message(bot, request)

            deadline = time.time() + REPLY_SECONDS
            seen = []
            while time.time() < deadline:
                await asyncio.sleep(POLL_SECONDS)
                found = [
                    _bold_runs(message)
                    for message in reversed(await client.get_messages(bot, limit=WINDOW))
                    if message.id > sent.id
                    and not message.out
                    and (message.raw_text or "").strip()
                ]
                if found and len(found) == len(seen):
                    break
                seen = found
            return seen
        finally:
            await client.disconnect()

    return asyncio.run(conversation())


def _bold_runs(message) -> list[str]:
    """The substrings `message` carries as bold entities, read in the platform's own units.

    Matched by type rather than by that type's name: a renamed or re-parented entity class makes
    an `isinstance` check fail loudly where a name comparison would quietly report that nothing
    was emphasised, which is the answer a formatting case is asking for.
    """
    raw = (message.raw_text or "").encode("utf-16-le")
    return [
        raw[entity.offset * 2 : (entity.offset + entity.length) * 2].decode("utf-16-le")
        for entity in (message.entities or [])
        if isinstance(entity, MessageEntityBold)
    ]


def answered(texts: list[str], request: str, window: int = REPLY_SECONDS) -> list[str]:
    """The replies to `request` that came from the model, with the bot's own lines removed.

    Asserts that something arrived and that not all of it was catalogue text, because those two
    failures have the same shape from outside — nothing to read — and different causes.

    [window] is only ever reported, never waited on: the waiting happened in [delivered], and this
    says how long it was. A case that widened the window there passes the same figure here.
    """
    assert texts, (
        f"The bot sent nothing within {window}s of being asked {request!r}. It is "
        f"running and it admitted the sender, or an earlier test would have failed — so this is "
        f"the answer path, and the backend's log says where."
    )
    statuses = status_lines()
    composed = [text for text in texts if not is_status(text, statuses)]
    assert composed, (
        f"Every reply to {request!r} was a line from the bot's own catalogue, so nothing was "
        f"answered from the corpus. That is what an empty index looks like from outside, it is "
        f"what most rejected answers look like, and for a request naming somebody it is also what "
        f"a name the corpus does not hold looks like — all the same reply by design. Some say so "
        f"in their own words and are not that: an answer nothing could read; one the bot stopped "
        f"waiting for, which is its own clock rather than anything about the corpus; and a name "
        f"that matched nothing while the corpus holds one spelled nearly like it, which carries "
        f"those spellings. Read "
        f"the reply below to tell the rest apart, and check MB_E2E_SUBJECT before the stack. "
        f"Replies: "
        f"{[text[:120] for text in texts]}"
    )
    return composed
