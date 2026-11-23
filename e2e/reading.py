"""Finding a fact the store holds in prose the model wrote, without pinning the model's wording.

**Every reader here looks for something the material supplied and the composer could not invent.**
A name is a name the corpus holds; a due date is a date it holds; a figure of days past is
computed before the prompt is assembled. So each of these is a *relation* between the answer and
the store rather than a vocabulary — which is the one form of assertion over composed prose that
does not go stale the next time the prompt is reworded.

**Two concessions to language, both of them bounded.** A name is declined where the answer is in
Russian, so it is matched by a stem rather than whole; and a date can reach a reader written half
a dozen ways, so every form is looked for and the earliest hit is the one reported. Neither
concession reads the model's own choice of words: the stem comes from the store's spelling of the
name and every date form is generated from the store's own date.

**What these cannot do is say which item a sentence is about.** A claim is restated by the
composer in its own words, so nothing in a rendered brief can be matched back to the row it came
from except by a token the material handed over — which is what a date and a name are, and what a
claim's text is not. A property that needs an item identified and carries no such token is
therefore not readable at this tier at all; the cases say so where that bites rather than
asserting something weaker and reading as coverage.
"""

import re
from datetime import date

#: The shortest word of a name that stands for the whole of it.
#:
#: A brief naming somebody by one of two words has named them, so any word is enough — but a short
#: one is a run of letters that ordinary prose can hold, so the short ones are passed over while a
#: longer word is present.
NAME_WORD = 4

#: The shortest stem that may be looked for, below which a name is not readable at all.
#:
#: A two-letter run occurs inside ordinary words — a month, a conjunction — so a name that stems to
#: one would make an assertion that a brief named somebody pass on a brief that named nobody. Such
#: a name is reported as unreadable rather than looked for: [stems] returns nothing for it, and the
#: case says so instead of asserting.
STEM_FLOOR = 3

#: The letters a Russian case ending replaces, which is what a stem has to stop before.
#:
#: Russian declines a name wherever it appears in a sentence, so the store's spelling is frequently
#: not the one a correct brief used. The ending attaches to the stem and replaces a final vowel
#: where there is one — so the stem is the word with a trailing vowel taken off, and the whole word
#: where it ends in a consonant, which is the case the ending is simply added to. Dropping a fixed
#: number of characters instead gets a four-letter name ending in a vowel wrong in the direction
#: that reds on correct prose.
VOWELS = "аеёиоуыэюяaeiouy"

#: Enough of each month's name to survive its own declension, in both languages a reply arrives in.
#:
#: The Russian forms are stems for the reason a name is: a date is written in the genitive and the
#: month's ending goes with it. May is spelled out rather than stemmed, its stem being two letters.
_MONTHS_RU = (
    "январ", "феврал", "март", "апрел", "ма[йяе]", "июн",
    "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
)

#: The three letters every English month is abbreviated to, which is also the prefix of its full
#: form — so one pattern reads both.
_MONTHS_EN = (
    "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
)


def mentions(answer: str, name: str) -> bool:
    """Whether `answer` names whoever the store calls `name`.

    Stem-matched per word, for the declension reason [VOWELS] gives. False for a name nothing can
    be read of — see [stems], and [readable], which is what a case asks before asserting.

    **Every word of the spelling and not any one of them.** A display name routinely carries a role
    or a company beside the person, and one word of it is then a run that ordinary prose about that
    subject matter holds — so a reply naming nobody would satisfy this on a sentence about the work.
    The material hands the model the whole spelling, in one piece, wherever it names a party; a
    brief that names the party at all therefore carries the whole of it, and a brief that names
    them by one word of it goes red. That is the direction worth being wrong in: it cannot report
    a reply that named nobody as one that did.
    """
    said = folded(answer)
    found = stems(name)
    return bool(found) and all(stem in said for stem in found)


def folded(text: str) -> str:
    """`text` as both sides of a name comparison are read.

    Case folded, because the model chooses its own capitalisation; and **ё written as е**, because
    the two are interchangeable in ordinary Russian writing and a name carrying one is routinely
    declined into the other. Without this a stem taken from the store's spelling misses the
    delivered form on nothing a reader could see.
    """
    return text.casefold().replace("ё", "е")


def stems(name: str) -> tuple[str, ...]:
    """The runs of `name` that stand for it, longest word first, or nothing where none does.

    Exposed so a failure can say what was looked for — a case reporting only that a name was absent
    leaves the reader unable to tell a brief that did not name anybody from a stem that was wrong.

    Empty where no word of the name stems to [STEM_FLOOR] characters. That is not a name this can
    read: the run is short enough to sit inside ordinary words, so looking for it would pass on a
    brief that named nobody.
    """
    words = [word for word in re.split(r"[^\w]+", folded(name)) if word]
    long_enough = [word for word in words if len(word) >= NAME_WORD]
    chosen = sorted(long_enough or words, key=len, reverse=True)
    # One vowel and not every trailing one: an ending replaces the last letter, and a name whose
    # last two are both vowels stems to something short enough to sit inside another name.
    found = [word[:-1] if word[-1] in VOWELS else word for word in chosen]
    return tuple(stem for stem in found if len(stem) >= STEM_FLOOR)


def readable(names: tuple[str, ...]) -> tuple[str, ...]:
    """Whichever of `names` something can be looked for, which may be none of them."""
    return tuple(name for name in names if stems(name))


def named(answer: str, names: tuple[str, ...]) -> str | None:
    """Whichever of `names` `answer` names, or `None` where it names none of them.

    Several because the store holds more than one spelling for a human whose accounts were merged,
    and the shape is handed whichever the account that stated a thing carries.
    """
    return next((name for name in names if mentions(answer, name)), None)


def after_the_lead(answer: str, marker: str, lead: int = 2) -> str:
    """`answer` from where its account of the record begins, or `""` where it has none.

    **The order of a brief's items is a property of its account and not of its opening.** The lead
    is bounded by instruction to two single facts — where the relationship stands, and what the
    call turns on — and which two it picks is a judgement about the call rather than a reading of
    the standings. A brief whose lead names the second-most pressing thing and whose account is
    ordered correctly is a correct brief, so an order read over the whole reply would go red on it.

    **A heading is what separates the two where there is one, and counting assertions is the
    fallback.** The instruction puts the lead before the first section, so the first unmarked line
    after it is where the account starts — and that reading holds however many lines the lead came
    out as. Counting instead assumes the lead arrived as exactly two marked lines, and both drifts
    cost something real: a lead of one swallows the first item, which reds on a correct brief, and
    a lead of three leaves a lead line in the account, which can make a wrong order read as right.
    The count is kept for a brief with no heading at all, where nothing else marks the boundary.
    """
    lines = answer.splitlines()
    marked = [index for index, line in enumerate(lines) if line.strip().startswith(marker)]
    if not marked:
        return ""
    heading = next(
        (
            index
            for index, line in enumerate(lines)
            if index > marked[0] and line.strip() and not line.strip().startswith(marker)
        ),
        None,
    )
    if heading is not None:
        return "\n".join(lines[heading:])
    if len(marked) <= lead:
        return ""
    return "\n".join(lines[marked[lead] :])


def before_the_sources(answer: str, opening: str) -> str:
    """`answer` with the block naming where its material came from cut off the end.

    **Everything a case reads about the prose has to be read with this applied.** The block is
    rendered inside the composed answer rather than as a message of its own, and what it carries is
    *who wrote the material and over what days* — so the subject's own name and a span of dates are
    in the reply whenever anything was cited, whatever the prose did. A name looked for without
    cutting it is found there every time, and a case asserting that the answer names the party then
    passes on the interface's own furniture.
    """
    return answer.split(opening)[0] if opening and opening in answer else answer


#: A web source in the form a reader can act on: the link, beside the finding it supports.
#:
#: **Read off the visible text, which is the right side of the markup seam here and is not obviously
#: so.** Formatting does not survive the platform's parse — the tags are consumed and the characters
#: are gone — so a reader looking for markup in a delivered message looks for something correctly
#: absent. A link is the exception, and only because of what this interface renders: its markup
#: vocabulary is emphasis alone, and everything a model wrote is escaped, so a tag the model emitted
#: arrives as literal text rather than as a hyperlink whose address hides in an entity. The address
#: is therefore in the text whichever form the model chose, and a floor over this cannot red on a
#: reply whose sources are perfectly good.
_LINK = re.compile(r"https?://\S+")

#: A figure in brackets, which is the form a source may not take.
#:
#: Not one shape's rule: the shared reply format declares this same form forbidden to every shape,
#: beside the pattern it claims as its extent, and the open-web shape's instruction then says it
#: twice more in its own words. What makes it worth reading in a delivered message is what the
#: figure would mean — a reference into a list of sources that nothing renders, so a reader is left
#: with a digit leading nowhere.
_NUMBERED = re.compile(r"\[\d+\]")


def sources(answer: str) -> tuple[str, ...]:
    """Every web source `answer` carries, deduplicated.

    **Distinct sources and not occurrences, because the two answer different questions.** One
    source cited beside four findings is four occurrences and one source, and a floor meant to say
    the reply rested on more than a single authority is satisfied by the first reading while the
    reply it was written to refuse passes. Deduplicated on the address as written: two spellings of
    one address are a distinction no reader makes and this does not try to.
    """
    return tuple(dict.fromkeys(_LINK.findall(answer)))


def numbered_sources(answer: str) -> tuple[str, ...]:
    """Every bracketed figure in `answer`, which is a source written the one way it may not be."""
    return tuple(_NUMBERED.findall(answer))


def claims_carrying_a_source(answer: str, marker: str) -> tuple[str, ...]:
    """Every claim line of `answer` that carries a web source, which no claim line may.

    A claim line asserts something about material this organisation holds and is checked against it;
    a finding from the web belongs in the prose with its source beside it. A line that is both is a
    web finding written where a reader reads it as corpus-supported.

    **What this returns nothing for is as important as what it finds**, and the caller has to say
    which it got: a reply with no claim lines at all yields an empty result exactly as a correct one
    does, so a case reading this without asking whether there was anything to read reports a pass it
    did not earn.
    """
    return tuple(
        line
        for line in answer.splitlines()
        if line.strip().startswith(marker) and _LINK.search(line)
    )


def claims(answer: str, marker: str) -> tuple[str, ...]:
    """Every claim line of `answer`, so a caller can tell *none carried one* from *none existed*."""
    return tuple(line for line in answer.splitlines() if line.strip().startswith(marker))


def day_at(answer: str, day: date) -> int:
    """Where `answer` first writes `day`, or `-1`.

    Every form generated from the date itself: the material hands the model an ISO instant and the
    shared format asks for a date a reader can check, so what arrives is that day written in the
    answer's own language, in digits, or as it was given. Looking for all of them and taking the
    earliest is what keeps this a question about the date rather than about the phrasing.
    """
    return _first(answer.casefold(), _forms(day))


def overdue_at(answer: str, days: int) -> int:
    """Where `answer` first writes a figure of `days` days, or `-1`.

    The second locator a dated item has, and it is wanted because the material renders the standing
    as a computed figure beside the date — a brief may carry the figure and leave the date out.
    Bounded to a figure followed by a word for days, so that a number appearing for any other
    reason is not read as this one.

    **Both Russian forms of the word, which the figure itself decides.** A count ending in one
    takes *день* and every other count takes a form of *дн-*; a pattern carrying only the second
    finds nothing in a brief about something one, twenty-one or thirty-one days past, which is a
    red on correct prose rather than a miss.

    **What this cannot do is say which figure it found.** The material renders a second one of the
    same shape — how long ago the thing was last stated — so a count that is one item's distance
    past and another's silence is one string here. The caller is what keeps that from being read as
    a position: see `test_rendered.leads_with_what_fell_due`, which stands the order down where two
    figures in play collide.
    """
    if days < 1:
        return -1
    return _first(folded(answer), (rf"\b{days}\s*(?:календарн\w*\s*)?(?:день|дн|day)",))


def located(answer: str, day: date | None, days_past: int | None) -> int:
    """Where `answer` first places an item carrying `day` as a deadline and `days_past` as a
    standing, by whichever token it wrote, or `-1`.

    Both, because the material renders both and a brief carries one or the other — an item
    reported with its standing and dated by the message that stated it carries the figure and
    not the deadline, and looking for one alone would read it as never reported.
    """
    found = []
    if day is not None:
        found.append(day_at(answer, day))
    if days_past is not None:
        found.append(overdue_at(answer, days_past))
    live = [at for at in found if at >= 0]
    return min(live) if live else -1


def present(account: str, day: date | None, days_past: int | None, others: tuple[int, ...]) -> bool:
    """Whether one item of `account` is the one `day` and `days_past` belong to.

    **Read line by line, and a line carrying one of `others` does not count.** Either token alone
    is ambiguous in the way that matters here: a deadline can be the day another item was *stated*
    on, so an item a brief dropped is found inside the sentence about its neighbour and the drop is
    invisible. What separates the two is where the token sits — in an item of its own, or inside one
    demonstrably about something else. A figure of days past is what demonstrates it, being the one
    token tied to a particular standing.

    So a brief that wrote this item's deadline and no figure still reads as reporting it, which is
    the lenient half; and a brief that dropped the item does not borrow its neighbour's date, which
    is the strict half.
    """
    for line in account.splitlines():
        if located(line, day, days_past) < 0:
            continue
        if any(overdue_at(line, days) >= 0 for days in others):
            continue
        return True
    return False


def _forms(day: date) -> tuple[str, ...]:
    """Every spelling of `day` this looks for, as patterns."""
    one = day.day
    return (
        # The day and its month, in either order and in either language. `\b` in front of the
        # optional zero so that a day of the month is not matched inside a longer number.
        rf"\b0?{one}\s*(?:-?го)?\s*{_MONTHS_RU[day.month - 1]}",
        rf"{_MONTHS_RU[day.month - 1]}\w*\s*0?{one}\b",
        # The English day may carry an ordinal suffix, which is how a date is ordinarily written in
        # it — and the suffix sits between the figure and the month, so a pattern without it misses
        # the whole date rather than part of one.
        rf"\b0?{one}(?:st|nd|rd|th)?\s*(?:of\s+)?{_MONTHS_EN[day.month - 1]}\w*",
        rf"\b{_MONTHS_EN[day.month - 1]}\w*\s*0?{one}(?:st|nd|rd|th)?\b",
        # As the material itself writes it, and as digits with and without the year. The dotted
        # form is the one a Russian reply writes a date in when it writes one in digits at all.
        re.escape(day.isoformat()),
        rf"\b0?{one}\.0?{day.month}\.{day.year}\b",
        rf"\b0?{one}\.0?{day.month}\.{day.year % 100}\b",
        rf"\b0?{one}\.0?{day.month}(?!\.?\d)",
        rf"\b0?{one}/0?{day.month}/{day.year}\b",
    )


def _first(said: str, patterns: tuple[str, ...]) -> int:
    """The earliest position any of `patterns` matches in `said`, or `-1`."""
    found = [match.start() for match in (re.search(pattern, said) for pattern in patterns) if match]
    return min(found) if found else -1
