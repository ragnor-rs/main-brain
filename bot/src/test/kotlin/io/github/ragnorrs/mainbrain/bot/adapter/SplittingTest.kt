package io.github.ragnorrs.mainbrain.bot.adapter

import io.github.ragnorrs.mainbrain.bot.core.MARKUP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * An answer too long for one message.
 *
 * Asserted against a small limit rather than the platform's, so a case is a few words instead of
 * four thousand characters. The limit is a parameter for exactly that reason.
 *
 * What matters is that nothing is lost and that the cut lands where a reader would forgive it.
 */
class SplittingTest {

    @Test
    fun `a reply that fits is one message, unchanged`() {
        assertEquals(listOf("short enough"), pieces("short enough", limit = 20))
    }

    @Test
    fun `a reply at exactly the limit is still one message`() {
        val body = "a".repeat(20)
        assertEquals(listOf(body), pieces(body, limit = 20))
    }

    @Test
    fun `a long reply is cut at a paragraph break when there is one`() {
        val body = "first part here\n\nsecond part here"
        assertEquals(listOf("first part here", "second part here"), pieces(body, limit = 20))
    }

    @Test
    fun `a line break is preferred when no paragraph break fits`() {
        val body = "first line here\nsecond line here"
        assertEquals(listOf("first line here", "second line here"), pieces(body, limit = 20))
    }

    @Test
    fun `a space is the last boundary before cutting a word`() {
        val body = "alpha beta gamma delta epsilon"
        val parts = pieces(body, limit = 12)
        // No piece may end mid-word, so every piece is whole words.
        parts.forEach { part ->
            assertTrue(part.split(' ').all { it in body.split(' ') }) { "cut a word: $part" }
        }
        assertEquals(body, parts.joinToString(" "))
    }

    @Test
    fun `an unbroken run longer than the limit is cut mid-word, because there is nothing else`() {
        val body = "a".repeat(45)
        val parts = pieces(body, limit = 20)
        assertEquals(listOf(20, 20, 5), parts.map { it.length })
        assertEquals(body, parts.joinToString(""))
    }

    @Test
    fun `nothing is lost and no piece exceeds the limit`() {
        val body = (1..40).joinToString(" ") { "word$it" }
        val parts = pieces(body, limit = 25)
        parts.forEach { assertTrue(it.length <= 25) { "too long: ${it.length}" } }
        // Rejoined on the separator that was consumed at each cut.
        assertEquals(body, parts.joinToString(" "))
    }

    @Test
    fun `the platform's own limit is what the adapter uses`() {
        // Named rather than inlined so that a change to it is a change to one constant, and so
        // that this test says which number the interface is actually sending under.
        assertEquals(4_096, TELEGRAM_MESSAGE_LIMIT)
    }

    @Test
    fun `a limit of nothing is refused rather than looping`() {
        assertFailsWith<IllegalArgumentException> { pieces("anything", limit = 0) }
    }

    // ---- pieces have to stand on their own as markup ---------------------------------------

    @Test
    fun `a cut inside an emphasised run closes it and reopens it`() {
        // The platform parses each message on its own and rejects one whose tags do not balance,
        // so a piece ending inside a bold run does not render oddly — the message is lost.
        val body = "<b>alpha beta gamma delta</b>"
        val parts = pieces(body, limit = 20)

        parts.forEach { assertBalanced(it) }
        // And the text itself survives, which a naive close-and-reopen could lose at the seam.
        assertEquals("alpha beta gamma delta", parts.joinToString(" ") { it.withoutTags() })
    }

    @Test
    fun `no piece is ever cut inside a tag`() {
        // Worse than an unbalanced pair: both halves carry a fragment that is not a tag at all,
        // and the opening bracket is shown to the reader as text.
        val body = "aaaa <b>bbbb</b> cccc dddd eeee"
        pieces(body, limit = 12).forEach { part ->
            assertFalse(part.contains(Regex("<[^>]*$"))) { "a tag was cut open: $part" }
            assertFalse(part.contains(Regex("^[^<]*>"))) { "a tag's tail was orphaned: $part" }
            assertBalanced(part)
        }
    }

    @Test
    fun `a header followed by a long draft keeps its emphasis on the first piece only`() {
        // The shape every multi-draft answer has: a bold header, a blank line, then prose long
        // enough to split. The header must not be reopened over the prose that follows it.
        val body = "<b>Variant 1</b>\n\n" + (1..12).joinToString(" ") { "word$it" }
        val parts = pieces(body, limit = 24)

        assertTrue(parts.first().startsWith("<b>Variant 1</b>")) { parts.first() }
        parts.drop(1).forEach { part ->
            assertFalse(part.contains("<b>")) { "emphasis leaked onto a later piece: $part" }
            assertBalanced(part)
        }
    }

    @Test
    fun `a limit too small to carry a closing tag is refused`() {
        // Below this a piece could not be balanced at all, so the loop would either emit markup
        // over the limit or never terminate.
        assertFailsWith<IllegalArgumentException> { pieces("anything", limit = 6) }
    }

    @Test
    fun `a piece carrying a closing tag still fits the limit`() {
        // The room for the appended closer has to be reserved against the tags open AT THE CUT,
        // not against whether the remaining text happens to begin with one. An emphasis opening
        // anywhere after the first character otherwise pushes the piece past the limit, and the
        // platform rejects the whole message — the failure this file exists to prevent.
        val body = "x".repeat(10) + "<b>" + "y".repeat(40) + "</b>"

        pieces(body, limit = 20).forEach { part ->
            assertTrue(part.length <= 20) { "piece of ${part.length} exceeds the limit: $part" }
            assertBalanced(part)
        }
    }

    @Test
    fun `no piece is markup with nothing in it`() {
        // A body that parses to empty text is a rejected send, and one rejected send aborts the
        // loop that is still delivering the rest of the reply — so the reader gets the first few
        // messages and silently never gets the remainder.
        val body = "aaaa <b>bbbb</b> cccc dddd eeee"

        pieces(body, limit = 12).forEach { part ->
            assertTrue(part.replace(Regex("</?b>"), "").isNotBlank()) {
                "a piece carries markup and no text: $part"
            }
        }
    }

    @Test
    fun `every cut makes progress, however little room a tag leaves`() {
        // The loop drops `cut` characters and prepends the reopened tags. If a cut were ever no
        // longer than what is prepended, the remainder would stop shrinking and the loop would
        // never end — a hung coroutine rather than a failed send.
        val body = "<b>" + "ab ".repeat(40) + "</b>"

        // Near the smallest limit the function accepts, which is where progress is tightest.
        val parts = pieces(body, limit = 9)

        assertTrue(parts.isNotEmpty())
        assertEquals(
            body.replace(Regex("</?b>"), "").split(" ").filter { it.isNotBlank() },
            parts.joinToString(" ") { it.replace(Regex("</?b>"), "") }
                .split(" ").filter { it.isNotBlank() },
        ) { "text was lost or duplicated across $parts" }
    }

    // ---- the second emphasis, and the footer ------------------------------------------------

    @Test
    fun `a cut inside a set-back run closes it and reopens it too`() {
        // The renderer composes two emphases now, and this file recognised only one. An italic
        // run cut open is the same lost message a bold one is, and the cases below it are why a
        // run this long can meet a cut at all: it names every author and both ends of a span.
        val body = "<i>from a message written by somebody, three days ago</i>"
        val parts = pieces(body, limit = 20)

        parts.forEach { assertBalanced(it) }
        assertEquals(
            "from a message written by somebody, three days ago",
            parts.joinToString(" ") { it.withoutTags() },
        )
    }

    @Test
    fun `a heading and the citation under it are each balanced across a cut`() {
        // The shape the status and preparation answers actually have: a bold heading, a finding,
        // and a set-back line under it, long enough to be cut more than once. Both tags appear and
        // neither leaks onto a piece it did not open on.
        val body = "<b>Where things stand</b>\n• the price was agreed\n" +
            "<i>from a message by somebody, three days ago</i>"

        pieces(body, limit = 24).forEach { part ->
            assertBalanced(part)
            assertFalse(part.contains(Regex("<[^>]*$"))) { "a tag was cut open: $part" }
        }
    }

    @Test
    fun `a footer rides the last piece rather than becoming a message`() {
        // **The half of this card the footer field exists for.** Appending the note to the body
        // and splitting afterwards could leave it as the whole of a trailing piece, which is the
        // defect the footer was meant to end arriving one layer further down. Room for it comes
        // off the window before anything is cut, so it fits wherever the last cut lands.
        val body = (1..40).joinToString(" ") { "word$it" }
        val note = "the corpus is three hours behind"

        val parts = messages(body, footer = note, limit = 80)

        assertTrue(parts.size > 1) { "the body did not split, so this proves nothing: $parts" }
        assertTrue(parts.last().endsWith(note)) { "the note is not on the last piece: ${parts.last()}" }
        assertEquals(1, parts.count { it.contains(note) }) { "the note was said more than once: $parts" }
        parts.forEach { assertTrue(it.length <= 80) { "piece of ${it.length} exceeds the limit: $it" } }
    }

    @Test
    fun `a footer with no room to be reserved for costs its attachment and not the answer`() {
        // Unreachable in this build: four thousand characters against one catalogue sentence. The
        // branch exists because the alternative is the limit requirement throwing, which loses the
        // answer to spare the reader a detached sentence — the worse of the two failures by far.
        val note = "a note very nearly as long as the whole limit allows"

        val parts = messages("the answer itself", footer = note, limit = 60)

        assertTrue(parts.first().contains("the answer itself")) { "the answer was lost: $parts" }
        assertEquals(note, parts.last()) { "the note did not survive at all: $parts" }
    }

    @Test
    fun `a footer never arrives as a piece of its own, at any body length`() {
        // The stranding is a straddle: it needs the body to end just short of a cut, so one length
        // reproduces it and the next does not. Swept rather than sampled, because a case that
        // happened to pick a safe length would pass while the defect was still there.
        val note = "a sentence about the answer"

        (1..200).forEach { length ->
            val parts = messages("x".repeat(length), footer = note, limit = 80)
            assertFalse(parts.last().trim() == note) {
                "a body of $length characters left the note alone in a message: $parts"
            }
        }
    }

    @Test
    fun `no footer leaves the pieces exactly as they were`() {
        val body = (1..40).joinToString(" ") { "word$it" }

        assertEquals(pieces(body, limit = 40), messages(body, footer = null, limit = 40))
    }

    @Test
    fun `a body whose tags came from the catalogue splits as safely as a composed one`() {
        // **A new shape of input, and the reason it is worth its own case.** Every other markup
        // case here is built from a body the renderer composed, where the emphasis wraps a whole
        // line of its own — a header. A catalogue entry puts emphasis *inside* a line and several
        // such lines in one message, which is what a subject menu now is, so a cut can land
        // between two emphasised runs or inside one of them with text on both sides of it.
        val menu = (1..40).joinToString("\n") { "$it. <b>Acme ${"Holdings ".repeat(6)}</b> (client)" }

        val parts = pieces(menu, limit = 200)

        parts.forEach { piece ->
            assertBalanced(piece)
            assertTrue(piece.length <= 200) { "a piece overran the limit at ${piece.length}: $piece" }
            assertTrue(piece.withoutTags().isNotBlank()) { "a piece carries only markup: $piece" }
        }
        assertEquals(
            menu.withoutTags().split(Regex("\\s+")).filter { it.isNotBlank() },
            parts.joinToString(" ") { it.withoutTags() }.split(Regex("\\s+")).filter { it.isNotBlank() },
        ) { "text was lost or duplicated across ${parts.size} pieces" }
    }

    @Test
    fun `the room a cut reserves covers the longest closer the vocabulary holds`() {
        // Sizing read from the vocabulary rather than from one tag, which is what lets a tag be
        // added by one edit. Asserted as a property so that adding a longer tag either keeps this
        // green or fails here — rather than failing as pieces that overrun the platform's limit,
        // or as a cut that prepends a tag to a remainder which never shrinks and never terminates.
        assertTrue(MARKUP.isNotEmpty()) { "an empty vocabulary would make this assert nothing" }
        val longestOpener = MARKUP.keys.maxOf { it.length }
        val longestCloser = MARKUP.values.maxOf { it.length }

        // One opener's worth of text per line, repeated past the limit, for every tag there is.
        MARKUP.forEach { (opener, closer) ->
            val body = (1..30).joinToString("\n") { "$it $opener${"word ".repeat(8)}$closer" }
            val parts = pieces(body, limit = longestOpener + longestCloser + 60)
            parts.forEach { piece ->
                assertBalanced(piece)
                assertTrue(piece.length <= longestOpener + longestCloser + 60) {
                    "a piece carrying '$opener' overran the limit at ${piece.length}: $piece"
                }
            }
            assertTrue(parts.isNotEmpty()) { "'$opener' produced no pieces at all" }
        }
    }

    private companion object {

        /**
         * Every opening tag in [text] is closed, in order, and no closing tag stands alone.
         *
         * Walked rather than counted. Counting passes on `</b>x<b>`, which is balanced by tally
         * and unparseable to the platform — and a body whose emphasis came from a catalogue entry
         * is exactly where that ordering can go wrong, because the tags are no longer written by
         * the one place that always writes them in pairs.
         */
        fun assertBalanced(text: String) {
            val open = ArrayDeque<String>()
            var index = 0
            while (index < text.length) {
                val tag = TAGS.firstOrNull { text.startsWith(it, index) }
                when {
                    tag == null -> index++

                    tag in MARKUP -> {
                        open.addLast(tag)
                        index += tag.length
                    }

                    else -> {
                        val owner = MARKUP.entries.first { it.value == tag }.key
                        assertEquals(owner, open.removeLastOrNull()) {
                            "'$tag' closes nothing that is open at that point: $text"
                        }
                        index += tag.length
                    }
                }
            }
            assertEquals(emptyList<String>(), open.toList()) { "unbalanced emphasis: $text" }
        }

        /** Longest first, so a closer is never read as the opener it contains. */
        private val TAGS = (MARKUP.keys + MARKUP.values).sortedByDescending { it.length }

        fun String.withoutTags(): String = replace(Regex("</?[bi]>"), "").trim()
    }
}
