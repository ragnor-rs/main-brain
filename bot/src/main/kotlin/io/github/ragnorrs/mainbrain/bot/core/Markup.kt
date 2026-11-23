package io.github.ragnorrs.mainbrain.bot.core

/**
 * The little markup a reply carries, and the escaping that makes it safe to carry any.
 *
 * **HTML rather than a Markdown dialect, and that is a robustness decision.** The platform's
 * Markdown dialect reserves more than a dozen characters anywhere they appear, so a drafted reply
 * containing an underscore, an asterisk or a bracket — ordinary punctuation in ordinary prose —
 * fails the send outright rather than rendering oddly. HTML reserves three characters, and all
 * three are rare in the prose this build composes, which makes the escaping both smaller and
 * easier to be sure of.
 *
 * **Here rather than in the adapter, because this is not platform knowledge.** It is the escaping
 * of the characters HTML reserves and the tags this interface chooses to emit; what the adapter
 * owns is telling the platform to parse them. A second interface that renders prose would want the
 * same escaping before it decided anything about its own markup.
 *
 * **The interface decides the formatting, not the model.** The reply format a shape sends asks for
 * ordinary prose with no Markdown, so there is no model markup to honour here. What carries markup
 * is what the wire says about an answer's shape — which parts are headings, and which assertions
 * it rests on — together with what this interface says on its own account: a draft's header, and
 * the catalogue entries whose text this build owns. What any of it looks like is decided here, and
 * the model's own emphasis is not an input to it.
 *
 * **An element is emphasised only where the emphasis tells a reader what to do with it, which is a
 * decision about attention rather than a limit.** A heading is bold because the thing a reader does
 * with a long answer is look for the section they want, and a citation line is set back because the
 * thing they do with a finding is read it and only sometimes check it. A finding itself carries
 * neither: in a reply of twenty findings across six sections, emphasising every one of them would
 * mean almost every line is emphasised, which marks nothing. The bullet it already carries is what
 * separates it from the prose around it.
 *
 * **Which is why a locale bundle may hold tags and a model's prose may not.** The division is
 * ownership, not position: the catalogue is this build's own reviewed text, so a tag in it is a
 * rendering decision taken deliberately and kept under version control, while prose from a model or
 * a line from the corpus is neither. That is the same line the escaping below already draws — only
 * catalogue text is trusted to carry markup, everything else is made literal — so saying a bundle
 * may carry tags adds no new trust to anything. What it does add is the obligation to check them,
 * because the platform rejects a message whose markup it cannot parse rather than rendering it
 * oddly: see [markupFaults], which the catalogue runs over every entry as it loads.
 *
 * **Asking is not enough, which is why [plain] exists.** A model told to write no Markdown writes
 * it anyway often enough to matter: one real answer arrived with ten runs of `**bold**` as section
 * headings, and since asterisks are not markup to an HTML renderer they are not escaped, not
 * parsed, and not removed — they are simply shown. Removed here rather than translated into tags,
 * because honouring them would make what a reply looks like depend on what the model felt like
 * writing, which is the decision this file's first paragraph takes away from it.
 */

/**
 * [text] with the three characters HTML reserves made literal.
 *
 * **Everything somebody else wrote goes through this**, because the three are ordinary in the
 * material this build answers from: an ampersand in a company's name, a comparison written with a
 * less-than sign, a quoted fragment of markup. Unescaped, any one of them makes the platform
 * reject the whole message — so the failure is not a mis-rendered answer but no answer at all, and
 * it arrives only for the questions whose material happens to contain one.
 *
 * What is exempt is what this interface composed itself, and the two halves are exempt for
 * different reasons. A value built here out of a catalogue entry and digits — a date formatted
 * through a pattern is one, and carries no letter it did not get from the pattern — cannot carry
 * one of the three at all. The catalogue's own text can and is allowed to, because that is where
 * this interface's emphasis lives; what stands in for escaping it is [markupFaults], run over
 * every entry as the bundle loads. So neither is exempt for being trusted: one has nothing to
 * escape, and the other is checked instead.
 *
 * The ampersand is replaced first and must stay first: doing it after the other two would find the
 * ampersands those two had just introduced and escape them twice.
 */
internal fun escaped(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * [text] with the model's Markdown emphasis taken out.
 *
 * **Applied before [escaped], and the order is not interchangeable.** Escaping first would leave
 * the markers sitting beside the entities it introduced, and a remover written against that text
 * would have to know what `&amp;` is to avoid cutting one in half.
 *
 * Only the doubled, closed form goes. A lone asterisk or underscore is ordinary punctuation in the
 * material this build answers from — a file name, a footnote marker, a multiplication — and a rule
 * that took those would edit the answer rather than clean it. The pair must also close on the same
 * line and wrap something non-blank, so an unclosed run and a `**` with spaces around it are both
 * left where they are.
 */
internal fun plain(text: String): String = text.replace(EMPHASIS) { match -> match.groupValues[2] }

/**
 * A doubled marker, closed by the same marker, wrapping at least one non-blank character.
 *
 * `.` does not match a newline here by design: emphasis the model opened and never closed must not
 * swallow the paragraphs after it looking for a partner.
 */
private val EMPHASIS = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")

/**
 * [text] emphasised, for a heading and for a draft's header.
 *
 * Takes text that has already been through [escaped] — the tags are the only markup in the body,
 * so whatever reaches here unescaped would be parsed as more of it.
 */
internal fun bold(text: String): String = "$BOLD_OPEN$text$BOLD_CLOSE"

/**
 * [text] set back, for a line that supports a finding rather than making one.
 *
 * The weaker of the two emphases, and the direction is the point: a citation is there to be found
 * when a reader wants it and to stay out of the way when they do not. Same escaping requirement as
 * [bold].
 */
internal fun italic(text: String): String = "$ITALIC_OPEN$text$ITALIC_CLOSE"

internal const val BOLD_OPEN = "<b>"
internal const val BOLD_CLOSE = "</b>"
internal const val ITALIC_OPEN = "<i>"
internal const val ITALIC_CLOSE = "</i>"

/**
 * Opener to closer, for every tag this interface may render. The vocabulary, and the only one.
 *
 * Read by the two things that must not disagree about the set: the splitter balances the tags it
 * names and sizes its cuts from their lengths, and [markupFaults] permits exactly these in a
 * bundle. [bold] and [italic] compose the two emphases this interface writes, both of which are in
 * here and are asserted to be. So a tag added here is balanced, validated and reserved for without
 * another edit — which is the point of one declaration rather than several literals.
 *
 * What the vocabulary holds is a decision about this interface and not about the platform, which
 * parses a good deal more: a tag is in here when something composes it and the splitter can close
 * it, and a tag that is merely legal elsewhere is not. What gets emphasis is the thing a reader is
 * looking for — the section a long answer is scanned for, the command in a list, the name a menu
 * option is known by — or, set back, the line they consult rather than read. A text with
 * everything emphasised has none.
 */
internal val MARKUP = mapOf(BOLD_OPEN to BOLD_CLOSE, ITALIC_OPEN to ITALIC_CLOSE)

/**
 * What is wrong with [text] as markup this interface could send, or nothing if it is sound.
 *
 * **For catalogue entries, and the reason it is a check rather than a convention.** The platform
 * parses a message as HTML and refuses one it cannot read, all or nothing — so a mistyped tag in a
 * bundle does not reach a reader looking wrong, it reaches them as no message at all, and the send
 * that fails abandons whatever was queued behind it. A translator editing a bundle has no way to
 * find that out, and the reply that disappears is whichever one happens to use the entry. So the
 * bundle is checked when it is read, and a fault stops the process at startup naming the key.
 *
 * **Every fault, not the first.** Whoever reads this is holding a bundle to fix, and one fault per
 * restart is the slowest way to learn how many there are.
 *
 * **What the platform does was measured rather than assumed**, and it does not do the same thing to
 * every fault. A tag left open, a closer with nothing open and a bare `<` are each refused
 * outright, so the reply is never delivered. A bare `&` is *accepted and the text silently
 * changed* — `&lt` reaches a reader as `<`, so an entry meaning one thing says another with
 * nothing anywhere reporting it, which is the worse of the two failures. A bare `>` is tolerated
 * and delivered as written.
 *
 * The three characters are held to one rule despite that, for two reasons. The tolerance is an
 * undocumented property of somebody else's parser and the platform's own guidance is to escape all
 * three; and a bundle held to exactly the spelling [escaped] produces is one a reviewer can compare
 * against rendered material without knowing which character is forgiven this year. The cost of the
 * rule is a `&gt;` somebody did not strictly need, against a class of silent corruption.
 *
 * **Two further faults are refused where the platform would accept them, and the splitter is the
 * reason for both.** A tag outside [MARKUP] is one the splitter cannot close when it cuts a long
 * message, and emphasis nested inside emphasis leaves the cut with more open elements than it
 * reserved room for — see `openTag`. Both are therefore delivered while replies are short and lost
 * the first time one is long enough to split, which is the kind of fault that ships rather than the
 * kind that is caught.
 */
internal fun markupFaults(text: String): List<String> {
    val faults = mutableListOf<String>()
    val open = ArrayDeque<String>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '<' -> index += readTag(text, index, open, faults)

            // A closing bracket reached here is one no tag accounted for, because every tag the
            // branch above recognises is stepped over whole.
            '>' -> {
                faults += "a '>' that is not the end of a tag. This one the platform does tolerate; " +
                    "it has to be '&gt;' anyway, so that a bundle spells the three the single way " +
                    "the escaping does."
                index++
            }

            '&' -> {
                val entity = ENTITIES.firstOrNull { text.startsWith(it, index) }
                if (entity == null) {
                    faults += "an '&' that begins no entity; it has to be '&amp;'. This one is not " +
                        "refused but silently misread — '&lt' reaches a reader as '<' — so the " +
                        "entry would say something other than what it reads as here."
                    index++
                } else {
                    index += entity.length
                }
            }

            else -> index++
        }
    }
    // Reported outermost first, which is the order somebody reading the entry meets them.
    open.forEach { opener ->
        faults += "'$opener' is never closed; the platform refuses a message whose markup does not " +
            "balance, so '${MARKUP.getValue(opener)}' has to appear after it."
    }
    return faults
}

/**
 * Account for the tag at [index], recording what it opened or what was wrong with it.
 *
 * Returns how far to advance, which for an unrecognised run is the whole of it: a single mistyped
 * tag should read as one fault rather than as a fault for the bracket and another for every
 * character after it.
 */
private fun readTag(
    text: String,
    index: Int,
    open: ArrayDeque<String>,
    faults: MutableList<String>,
): Int {
    val tag = VOCABULARY.firstOrNull { text.startsWith(it, index) }
    // A recognised tag is its own run; anything else runs to the closing bracket, or to the end of
    // the text when it never arrives.
    val run = tag
        ?: text.substring(index, (text.indexOf('>', index) + 1).takeIf { it > 0 } ?: text.length)
    when {
        tag == null ->
            faults += "'${run.take(SNIPPET)}' is not a tag this interface renders. It renders " +
                "${MARKUP.keys.joinToString()} and nothing else — the platform parses more than " +
                "that, but the splitter can only close what it recognises, so a long message " +
                "carrying this one loses a piece. A '<' meant as itself has to be '&lt;'."

        tag in MARKUP -> openTag(tag, open, faults)

        else -> closeTag(tag, open, faults)
    }
    return run.length
}

/**
 * Record [tag] as open, or say why it may not be.
 *
 * **Nesting is refused, and the reason is the splitter rather than the platform.** The platform
 * parses nested emphasis perfectly well. What cannot take it is the cut: a long body reserves room
 * for exactly one closing tag on every cut — chosen before anything knows which element the cut
 * lands inside — and `pieces` asserts in its own `check` that no piece left more than one open. So
 * a nested entry is delivered for as long as the reply is short, and the first time one is long
 * enough to split it is not delivered at all, which makes this the kind of fault that ships.
 *
 * Nothing wants it either: emphasis inside emphasis renders as the same emphasis.
 */
private fun openTag(tag: String, open: ArrayDeque<String>, faults: MutableList<String>) {
    if (open.isEmpty()) {
        open.addLast(tag)
        return
    }
    faults += "'$tag' opens inside '${open.last()}', which is already open. Nesting renders as no " +
        "more emphasis than one tag does, and whatever splits a long message reserves room for a " +
        "single closing tag — so a nested entry is delivered until a reply using it is long " +
        "enough to cut, and then is not delivered at all."
}

/** Match [tag] against what is open, recording what is wrong if it closes nothing it may. */
private fun closeTag(tag: String, open: ArrayDeque<String>, faults: MutableList<String>) {
    val owner = MARKUP.entries.first { it.value == tag }.key
    when {
        open.isEmpty() ->
            faults += "'$tag' closes nothing that is open at that point."

        // Left open rather than popped, so the unclosed report names it too: text that overlaps
        // its tags is wrong in both directions, and saying so once understates it.
        open.last() != owner ->
            faults += "'$tag' closes '$owner', but '${open.last()}' is the innermost tag still " +
                "open, so the two overlap where they have to nest."

        else -> open.removeLast()
    }
}

/**
 * Longest first, so a closing tag is never read as something shorter that it starts with.
 *
 * **Declared after [MARKUP], and that ordering is load-bearing.** A file's top-level properties
 * initialise in the order they are written, so this above it would read an empty map at class load
 * and nothing would ever be recognised as a tag — every entry in every bundle would be refused for
 * carrying markup this interface does not render, which is a process that will not start rather
 * than a fault anybody has to find.
 */
private val VOCABULARY = (MARKUP.keys + MARKUP.values).sortedByDescending { it.length }

/** What [escaped] produces, and so the only entities a bundle may spell out. */
private val ENTITIES = listOf("&amp;", "&lt;", "&gt;")

/** Enough of a bad tag to recognise it by, short enough that the fault is not a paragraph. */
private const val SNIPPET = 24
