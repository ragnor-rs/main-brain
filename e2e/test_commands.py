"""The interface's own furniture, read off the message Telegram delivered.

**The command list is the one text every reader sees and the one tier nothing exercised.** A
platform sends `/start` itself when a conversation opens, so this is the first thing in front of
anybody — and until this file existed, no case anywhere drove it through a real chat. The
hermetic suites hold its *content*: `HelpTextTest` in `:bot` checks that every command and every
kind a command accepts is named, in both directions, and `DeclaredCommandsTest` in `:backend`
holds the declaration to the shapes that answer it. Neither can say the message was delivered,
arrived whole, or survived the platform's parse mode.

**So these cases are about delivery, not about wording.** What belongs here is that the reply
arrives, that it is one message rather than several, that it is the catalogue's text and not
something composed, that the kinds it names reach a reader intact, and that the formatting the
bundle asked for survived the platform's parse. Which commands ought to be listed, and which of
them the bundle sets off, is settled where the shapes are; asserting either again here would be a
second opinion that can disagree with the first.

**A command that does not exist is the same tier.** It is answered at this edge too, from two
catalogue entries, so it arrives or it does not for exactly the reasons above — and *the reply is
the catalogue's* is the one observation from outside a chat that can tell a refusal apart from the
defect it replaced, which was the word forwarded and the reply composed by a model.

**No `corpus` marker, deliberately.** The command list is answered at this interface's own edge
and reaches neither the backend nor a model, so it needs no indexed material and no configured
subject — so it runs on a cold deploy, and it costs round trips and no model call. ⚠️ It is not
the only rendered case a cold deploy runs: the researched shape accepts no subject either and
declares no `corpus` marker, and that one is the most expensive live check here rather than the
cheapest.
"""

import pytest

from conversation import (
    ask,
    catalogue_emphasis,
    catalogue_entries,
    catalogue_text,
    emphasis,
)

#: What the command list is answered for. A platform sends the second itself when a conversation
#: opens, which is why both are checked rather than only the one a person would type.
ASKING_FOR_THE_LIST = ["/help", "/start"]

#: Words that are no command, as somebody would get one wrong.
#:
#: With and without an argument, because the two produce different wrong replies when the word is
#: forwarded instead of refused: with one there is a subject to compose about, so the reply reads
#: as an answer to a question nobody asked, and without one there is not, so it reads as a refusal
#: for the wrong reason. Neither says a command was mistyped.
#:
#: The last is the fallback's own identifier, which is the one word naming a shape this build
#: answers that no command list offers. It is refused like the rest, because what this interface
#: accepts is what it lists — and it is here rather than only in the hermetic suite because the
#: alternative reading, that something answers it, is the one a live reply would show.
MISTYPED = ["/stats", "/stats Acme", "/statu", "/ask what happened"]


def delivered_list(account, typed: str) -> str:
    """The single message `typed` is answered with, asserted to be the catalogue's own text.

    Anchored on the entry's first line rather than on the whole of it: `bot.HELP` is a continued
    entry and `catalogue_text` reads one physical line, so the opening is what can be compared
    without restating the text here. That is enough to tell the command list apart from anything
    the model composed, which is the distinction these cases rest on.
    """
    opening = catalogue_text("bot.HELP")
    assert opening, (
        "No bot.HELP in the catalogue, so there is no command list to deliver and these cases "
        "would pass over the thing they exist to check."
    )

    replies = ask(account, typed)
    assert replies, (
        f"The bot sent nothing when asked {typed!r}. This reply is composed at the interface's "
        f"own edge and reaches neither the backend nor a model, so nothing about the corpus or "
        f"the model credential can explain it — the bot is not running, or it did not admit the "
        f"sender."
    )
    assert len(replies) == 1, (
        f"{typed!r} was answered in {len(replies)} messages. The command list is one message; "
        f"split into several it arrives as fragments, and a reader scrolling the first sees a "
        f"list with commands missing from it. Delivered: {replies}"
    )

    answer = replies[0]
    assert answer.startswith(opening), (
        f"{typed!r} was not answered with the catalogue's command list. It opens {answer[:80]!r} "
        f"where the catalogue opens {opening[:80]!r}. A composed answer here means the request "
        f"reached the backend, which this one must never do."
    )
    return answer


@pytest.mark.telegram
@pytest.mark.stack
@pytest.mark.parametrize("typed", ASKING_FOR_THE_LIST)
def test_the_command_list_is_delivered_whole(telegram_account, typed):
    """Both ways of asking for the list are answered with it, in one message.

    Parametrised over the two rather than asserting only `/help`, because the one a reader never
    types is the one a platform sends on their behalf — so a regression in it is invisible to
    anybody testing by hand and reaches every new conversation.
    """
    delivered_list(telegram_account, typed)


@pytest.mark.telegram
@pytest.mark.stack
@pytest.mark.parametrize("typed", MISTYPED)
def test_a_command_that_does_not_exist_is_answered_with_the_list(telegram_account, typed):
    """A mistyped command gets the catalogue's refusal and the command list, in one message.

    **What makes this a live case is that the reply is the catalogue's.** The defect this exists for
    is a mistyped command forwarded whole and answered as a question — a composed answer about
    whatever followed the word, opening with a sentence the model wrote about there being no
    recognisable command in the request. Such a sentence may be accurate and nothing makes it so. So
    what has to be shown is not that the reply says the right thing but that nothing composed it,
    and the only way to show that from outside is that what arrives is text written in a bundle.

    **Which is what also shows nothing was spent.** A reply that opens with the catalogue's own
    refusal and carries every command the list sets off is not something a model produced, so this
    is the live half of the acceptance that an unknown command reaches no model call. `HandlerTest`
    holds the other half — that the backend was not called at all — which no observer of a chat can
    see. It is a prefix and a set of words rather than an equality because the whole of a continued
    entry cannot be read back from the bundle one line at a time; `HelpTextTest` is where the full
    body is compared.

    Free text that merely carries a slash inside it is deliberately not driven here: it is a real
    question and answering it spends, which would cost this file the property that makes it worth
    having. `CommandsTest` holds that case, where a slash in a sentence costs nothing.
    """
    frame = catalogue_text("bot.UNKNOWN_COMMAND").split("{0}")[0].strip()
    assert frame, (
        "No bot.UNKNOWN_COMMAND in the catalogue, or it carries no literal of its own, so there "
        "is nothing to tell its reply apart from something composed and this case would pass over "
        "the thing it exists to check."
    )
    # The commands, read off the list entry's own emphasis rather than listed here — the same
    # reason the kind case below reads its words by key. `catalogue_text` returns one physical
    # line, so the entry's opening cannot stand in for the list: it is the first sentence, and a
    # reply carrying it has not thereby been shown to carry a single command.
    listed = catalogue_emphasis("bot.HELP")
    assert listed, (
        "The command list entry marks nothing as emphasised, so there is no set of commands to "
        "look for and this case would pass over half of what it exists to check."
    )

    replies = ask(telegram_account, typed)
    assert replies, (
        f"The bot sent nothing when asked {typed!r}. This reply is composed at the interface's own "
        f"edge and reaches neither the backend nor a model, so nothing about the corpus or the "
        f"model credential can explain it — the bot is not running, or it did not admit the sender."
    )
    assert len(replies) == 1, (
        f"{typed!r} was answered in {len(replies)} messages. What a mistyped command is told and "
        f"the list it is told to read are one thing to say; split up, the list arrives as a "
        f"fragment behind a refusal. Delivered: {replies}"
    )

    answer = replies[0]
    assert answer.startswith(frame), (
        f"{typed!r} was not answered with the catalogue's unrecognised-command reply. It opens "
        f"{answer[:120]!r} where the catalogue opens {frame[:120]!r}. Anything else here means the "
        f"word was forwarded and the reply composed — which is the defect, and it reads as a "
        f"plausible answer rather than as a failure."
    )
    unnamed = [command for command in listed if command not in answer]
    assert not unnamed, (
        f"The reply to {typed!r} does not name {unnamed}, which the command list sets off as "
        f"things a reader may type. Saying a command does not exist without saying which do "
        f"leaves a reader with no way to find out what they can type. Delivered: {answer!r}"
    )


@pytest.mark.telegram
@pytest.mark.stack
def test_the_command_list_sets_every_command_off_and_nothing_else(telegram_account):
    """Each command in the delivered list is emphasised, and only the commands are.

    **Read off the platform's entity model, because nothing else can see this.** The markup
    crosses the wire as tags and the platform consumes them, so the delivered text is identical
    whether the list is formatted or flat — which is why a suite reading `raw_text` everywhere
    else was green while every message the interface sent was unformatted.

    **Both directions, because each half rots without the other.** A list whose names are the same
    weight as the prose describing them is a paragraph; a list with everything emphasised is the
    same paragraph again. `HelpTextTest` holds the bundle to both, and this holds the round trip to
    them — the hermetic case cannot say the platform parsed what the bundle wrote.

    Costs a round trip and no model call, like the rest of this file.
    """
    asked_for = catalogue_emphasis("bot.HELP")
    assert asked_for, (
        "The command list's entry marks nothing as emphasised, so this case would pass over the "
        "thing it exists to check. Which commands are listed is `HelpTextTest`'s; that the bundle "
        "sets them off at all is its too. This one is only about the round trip."
    )

    runs = emphasis(telegram_account, "/help")
    assert runs, "The bot sent nothing when asked for the command list."
    assert len(runs) == 1, (
        f"The command list arrived in {len(runs)} messages; it is one. Delivered runs: {runs}"
    )

    assert runs[0] == asked_for, (
        f"What the platform emphasised is not what the entry asked for. The bundle marks "
        f"{asked_for}; the delivered message carries {runs[0]}. Equal rather than contained, so "
        f"this catches both halves — emphasis that did not survive the send, and emphasis the "
        f"message gained on the way. A delivered list with none at all means the parse mode or "
        f"the tags, not the wording."
    )


@pytest.mark.telegram
@pytest.mark.stack
def test_every_kind_the_list_names_reaches_the_reader(telegram_account):
    """The words for what a command can be asked about survive the round-trip.

    **Read from the catalogue rather than listed here**, so a reworded kind moves this with it
    instead of quietly ceasing to match — the same reason the citation cases read their literal by
    key. What is checked is only that each word arrives: *which* command names which kind is
    `HelpTextTest`'s, where the shapes it is held against are in reach.

    The reason this is worth a live case at all is that the adapter sends in the platform's HTML
    mode and catalogue text is passed through unescaped, so what an entry writes is parsed rather
    than shown. A bundle that would not parse no longer reaches a deploy — the catalogue refuses
    one at startup — so what remains for a live case is the half a hermetic check cannot have: that
    the words came out the other side of the parse, in a real message.
    """
    kinds = catalogue_entries("bot.KIND_")
    assert kinds, (
        "No bot.KIND_* entries in the catalogue, so there are no kind words to look for and this "
        "case would pass over the thing it exists to check."
    )

    answer = delivered_list(telegram_account, "/help")
    missing = [kind for kind in kinds if kind not in answer]
    assert not missing, (
        f"The delivered command list does not name {missing}, though the catalogue has a word for "
        f"each. Every one of these is a kind some command accepts, so a reader is not told they "
        f"can ask about it. Delivered: {answer!r}"
    )
