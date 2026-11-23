package io.github.ragnorrs.mainbrain.bot.adapter

import io.github.ragnorrs.mainbrain.bot.core.MARKUP

/**
 * The platform's message-length limit, and what to do about it.
 *
 * Here rather than in the renderer because a limit is a property of the platform: another
 * interface has another one, or none. The renderer decides what the messages *are*; this decides
 * how one of them survives being too long for the wire it goes down.
 *
 * **Split at the largest boundary that fits.** A paragraph break first, then a line break, then a
 * space, and only then mid-word — because an answer cut in the middle of a sentence reads as a
 * fault in the assistant, while one cut between paragraphs reads as a long answer. The last
 * resort exists because a single unbroken run longer than the limit has no boundary to prefer.
 *
 * **A piece has to stand on its own as markup, and three things follow from that.** The platform
 * parses each message separately and rejects one it cannot read, so a piece that fails is not a
 * piece that renders oddly — it is a message the reader never sees, and the send that fails
 * abandons the pieces still queued behind it. So: an element open at the cut is closed on the
 * piece and reopened on the next, no cut falls inside a tag, and a piece that would carry markup
 * and no text is dropped rather than sent.
 *
 * **One open element at a time is all a body carries, and this file is built for exactly that.**
 * When a body carries markup at all, room for a single closing tag is reserved on every cut rather
 * than only on the cuts that turn out to need it — which element is open depends on where the cut
 * lands, and the cut is chosen first, so judging it per cut is judging it too late. Four
 * characters against four thousand is the cheapest way to make the guarantee hold wherever a tag
 * happens to open, and a body with no `<` in it reserves nothing.
 *
 * **Two things keep the depth at one, and neither is this file.** Each emphasis the renderer
 * composes wraps a whole line of its own — a heading, the line saying where the material is from —
 * so no cut can fall inside two; and emphasis nested inside emphasis is refused where markup is
 * validated, both in a bundle entry and across the seam where this interface wraps an entry in
 * emphasis of its own. A piece that somehow needs more room is a body this function was not built
 * for, and it says so rather than sending something the platform will reject.
 *
 * **A footer is reserved for before the body is cut, which is what keeps it attached.** A note
 * appended to the last piece after the cutting could overflow it and become a message of its own —
 * which is the whole defect the note being a footer exists to fix, arriving one layer further
 * down. Taking its length off the window first costs every piece a few dozen characters out of
 * four thousand and makes the attachment structural rather than lucky.
 */
internal const val TELEGRAM_MESSAGE_LIMIT = 4_096

/**
 * [body] as messages the platform will accept, with [footer] on the last of them.
 *
 * The footer rides on the final piece rather than being one, for the reason the file comment
 * gives: a sentence qualifying an answer is not something to send on its own. Room for it comes
 * off the window before anything is cut, so it fits wherever the last cut happens to land.
 *
 * The one exception is a footer too long for the limit to hold alongside any body at all, which
 * degrades to sending it separately rather than losing the answer — see the branch below, and note
 * that nothing this build composes reaches it.
 */
internal fun messages(body: String, footer: String?, limit: Int = TELEGRAM_MESSAGE_LIMIT): List<String> {
    val attached = footer?.let { PARAGRAPH + it } ?: return pieces(body, limit)
    // Nothing to attach to in two cases, and both degrade the same way. A window too narrow to
    // make progress in would fail [pieces]' own requirement, so it is not asked; and [pieces]
    // drops a cut carrying markup and no text — deliberately, for a measured reason — so it can
    // in principle return nothing, which reading the last of would throw out of the send.
    val window = limit - attached.length
    val fitted = if (window > FLOOR + LONGEST_CLOSER) pieces(body, window) else emptyList()
    val last = fitted.lastOrNull()
    // **A footer there is no room to reserve for goes on its own rather than taking the answer
    // with it.** Unreachable in this build — the platform allows four thousand characters, a
    // footer is one catalogue sentence, and every body carries escaped text — but the alternative
    // is losing the whole answer to spare the reader a detached sentence, which is much the worse
    // failure. Degrading is cheaper than relying on the ratio holding.
    return if (last == null) {
        pieces(body, limit) + attached.trim()
    } else {
        fitted.dropLast(1) + (last + attached)
    }
}

/** A blank line between an answer and the sentence qualifying it. */
private const val PARAGRAPH = "\n\n"

/** [body] as pieces the platform will accept, in order, losing no text and balancing its tags. */
internal fun pieces(body: String, limit: Int = TELEGRAM_MESSAGE_LIMIT): List<String> {
    require(limit > FLOOR + LONGEST_CLOSER) {
        "A limit of $limit leaves no room for a piece that carries markup and makes progress."
    }
    if (body.length <= limit) return listOf(body)

    // Nothing to balance and nothing to reserve. Most replies are this: a body with no `<` in it
    // at all cannot open an element, so the reservation below would spend four characters of
    // every message to guarantee something that was never in question.
    val reserve = if (body.contains('<')) LONGEST_CLOSER else 0

    val pieces = mutableListOf<String>()
    var rest = body
    while (rest.length > limit) {
        val cut = boundary(rest, limit - reserve)
        val piece = rest.take(cut).trimEnd()
        val open = unclosed(piece)
        check(open.size <= 1) {
            "A piece left ${open.size} elements open, and only one closing tag's worth of room " +
                "is reserved. Nothing should be able to reach here: each emphasis the renderer " +
                "composes wraps a whole line of its own, and nested emphasis is refused where " +
                "markup is validated, in a bundle entry and across the seam where an entry is " +
                "wrapped in emphasis. If that is no longer true, the reservation has to be " +
                "computed from the cut instead of fixed at one tag."
        }
        val balanced = piece + open.joinToString("") { closing(it) }
        // Markup with nothing inside it parses to an empty message, which the platform refuses —
        // and a refused send abandons whatever was still queued behind it, so the reader would
        // get the first few pieces of an answer and silently never get the rest.
        if (carriesText(balanced)) pieces += balanced
        // Reopened at the head of the remainder, which is also what the progress floor accounts
        // for: a cut no longer than what is prepended here would leave the remainder the length
        // it already was, and the loop would never end.
        rest = open.joinToString("") + rest.drop(cut).trimStart()
    }
    if (rest.isNotEmpty() && carriesText(rest)) pieces += rest
    return pieces
}

/**
 * Where to cut, preferring the largest boundary within [window].
 *
 * Never inside a tag — a cut between the angle brackets leaves a fragment on both sides, and the
 * opening bracket becomes text the platform shows rather than markup it reads — and never so
 * early that the piece would be shorter than what gets reopened after it, which is what makes
 * each turn of the loop shrink the remainder.
 *
 * Falls back to [window] itself when no boundary is found, which cuts mid-word: correct for a run
 * of characters with nothing to break on, and the only case that reaches it.
 */
private fun boundary(text: String, window: Int): Int {
    val preferred = BOUNDARIES.firstNotNullOfOrNull { separator ->
        candidates(text, window, separator).firstOrNull { !strands(text, it) }
            ?: candidates(text, window, separator).firstOrNull()
    } ?: window
    val clear = if (insideTag(text, preferred)) outOfTag(text, preferred) else preferred
    // The floor wins over the preference, because a prettier cut that does not advance is not a
    // cut at all. `window` is above the floor by the requirement in `pieces`.
    return if (clear >= FLOOR) clear else window
}

/**
 * Every cut at [separator] inside [window], latest first.
 *
 * Latest first because the latest is the preference: a cut that fills a message wastes none of it.
 * The sequence exists so that a cut can be *declined* — [boundary] walks back from the latest when
 * the piece it would leave ends badly — and it is lazy so that the ordinary case computes one
 * index and stops.
 */
private fun candidates(text: String, window: Int, separator: String): Sequence<Int> = sequence {
    var at = text.take(window).lastIndexOf(separator)
    while (at > 0) {
        yield(at + separator.length)
        at = text.lastIndexOf(separator, at - 1)
    }
}

/**
 * Whether cutting at [at] would strand a line that is nothing but a whole emphasised run.
 *
 * **Two arms, and each was a defect read off a real reply.** This interface emphasises a whole line
 * and nothing else — a section's heading, and the line saying what the answer rests on — so a line
 * that is one emphasised run is one of those, and a line with emphasis *inside* it is prose and is
 * left alone. Both arms are about the same mistake from opposite ends: a message that carries a
 * label and not the thing it labels.
 *
 * **Ending on one.** The interface sets a heading off from its section with a blank line beneath it,
 * which made the gap between a heading and its first item a paragraph boundary like any other — and
 * the splitter prefers the latest boundary that fits, so on a reply a little over the limit that gap
 * is exactly the one it takes. Measured on a six-section reply at the platform's own limit: one
 * piece in two ended on a heading.
 *
 * **Leaving one alone.** The same arithmetic put the cut just before the block saying where the
 * material came from, which is itself one emphasised run — so a reader got the answer and then a
 * second message that was nothing but a list of names and dates. The freshness note is already held
 * to this by being reserved for before the body is cut; the sources block is part of the body and
 * had nothing holding it.
 */
private fun strands(text: String, at: Int): Boolean {
    // Local, because the file is at the function-count ceiling and this is one expression used
    // twice in one predicate — a reader looking for what counts as a label looks here.
    fun whole(line: String): Boolean = MARKUP.any { (opener, closer) ->
        line.startsWith(opener) && line.endsWith(closer) &&
            line.removeSurrounding(opener, closer).none { it == '\n' } &&
            !line.removeSurrounding(opener, closer).contains(opener)
    }
    return whole(text.take(at).trimEnd().substringAfterLast('\n')) || whole(text.drop(at).trim())
}

/**
 * Whether cutting at [at] would fall between a `<` and its `>`.
 *
 * Read backwards from the cut: an opening bracket nearer than any closing one means the cut is
 * inside a tag that has not finished.
 */
private fun insideTag(text: String, at: Int): Boolean {
    val before = text.take(at)
    return before.lastIndexOf('<') > before.lastIndexOf('>')
}

/**
 * The same cut moved clear of the tag it landed inside — before it, or past it.
 *
 * Before, normally. Past it when the tag begins at the very start of the remainder, where moving
 * back would cut nothing at all and the loop would stop advancing; a tag is a few characters, so
 * stepping over it stays well inside the window.
 */
private fun outOfTag(text: String, at: Int): Int {
    val opening = text.take(at).lastIndexOf('<')
    if (opening >= FLOOR) return opening
    val closing = text.indexOf('>', opening)
    return if (closing < 0) at else closing + 1
}

/** Whether [piece] says anything once its markup is taken off. */
private fun carriesText(piece: String): Boolean =
    TAGS.fold(piece) { text, tag -> text.replace(tag, "") }.isNotBlank()

/**
 * The tags [piece] opened and did not close, outermost first.
 *
 * Only the tags this interface declares are recognised, which is the whole set a reply can carry —
 * though not because one place writes them. The renderer composes some and the catalogue's own
 * entries carry others, and what makes the set closed is that a bundle is checked against the same
 * vocabulary before it loads. Every other `<` in a body has already been escaped into text by the
 * time it reaches this file.
 */
private fun unclosed(piece: String): List<String> {
    val open = mutableListOf<String>()
    var index = 0
    while (index < piece.length) {
        val tag = TAGS.firstOrNull { piece.startsWith(it, index) }
        when {
            tag == null -> index++
            tag in OPENERS -> {
                open += tag
                index += tag.length
            }
            else -> {
                // A close with nothing open cannot happen from this build's renderer, and
                // dropping it rather than failing keeps a mismatch to a missing tag rather than
                // to a lost answer.
                open.removeLastOrNull()
                index += tag.length
            }
        }
    }
    return open
}

/** The tag that closes [opener]. */
private fun closing(opener: String): String = requireNotNull(CLOSERS[opener]) {
    "No closing tag is known for '$opener', so a piece carrying it could not be balanced."
}

/** Largest first, so a paragraph break is preferred to a line break and that to a space. */
private val BOUNDARIES = listOf("\n\n", "\n", " ")

/**
 * Which opener each closer answers.
 *
 * The interface's own vocabulary rather than a second copy of it: a tag added there is a tag this
 * file must balance, and two literal lists would agree until the day one of them was edited.
 */
private val CLOSERS = MARKUP

private val OPENERS = CLOSERS.keys

/** Longest first, so a closing tag is never read as the opener it contains. */
private val TAGS = (CLOSERS.keys + CLOSERS.values).sortedByDescending { it.length }

/**
 * The shortest cut that still advances.
 *
 * One more than the longest opening tag, so that a piece is always longer than the tag reopened at
 * the head of the remainder after it. **Measured over the vocabulary rather than written from one
 * tag's length, and declared after it** — with a longer tag added, a floor still sized for the
 * shortest lets a cut land inside the leading tag, emit a piece that is only that tag, and prepend
 * the same tag to a remainder that never shrinks. That is a reply that hangs rather than one that
 * arrives badly, and it is why these two read the vocabulary instead of `BOLD_*`.
 */
private val FLOOR = OPENERS.maxOf { it.length } + 1

/**
 * The room a cut holds back for whatever it may have to close.
 *
 * The longest closer and not one tag's worth: which element is open depends on where the cut lands
 * and the cut is chosen first, so the reservation has to cover the worst case rather than the
 * common one. Sized short, every piece overruns the platform's limit — which is a refused send,
 * and a refused send abandons the pieces queued behind it.
 */
private val LONGEST_CLOSER = CLOSERS.values.maxOf { it.length }
