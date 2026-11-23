package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.adapter.TELEGRAM_MESSAGE_LIMIT
import io.github.ragnorrs.mainbrain.bot.adapter.messages
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Cited
import io.github.ragnorrs.mainbrain.contract.Claimed
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * What Telegram is actually handed, for a reply long enough that the platform cuts it up.
 *
 * **The one thing neither suite beside this covers.** `OutcomeRendererTest` asserts what the
 * renderer composes and `SplittingTest` asserts what the splitter does with a body, each on inputs
 * sized for the decision under test. Nothing puts a reply long enough for the platform to cut up
 * — six sections of findings, and the one block saying what the lot rests on — through both and
 * reads the strings that would go down the wire. That composition is where this card's defects lived: the
 * marking survived the backend and the renderer and was then invisible to a reader, and the note
 * was a message of its own because of how the pieces were assembled rather than how either half
 * behaved.
 *
 * It is also the half of the acceptance check that does not need a person: *a reply long enough to
 * split* is a property of the bytes, and a short reply proves nothing about it. What still needs a
 * person is whether the result reads well, which no assertion settles.
 *
 * The content is composed here rather than taken from the archive, which carries real names and
 * real message text. What is under test is length, structure and markup, none of which depends on
 * what the sentences say.
 */
class LongReplyTest {

    private val catalogue = Catalogue.loaded()

    private val renderer = OutcomeRenderer(catalogue, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun `a reply long enough to split arrives as pieces a reader can navigate`() {
        val answer = aLongSectionedAnswer()
        val stale = Freshness(completeThrough = NOW.minus(Duration.ofHours(3)).toString())

        val replies = renderer.render(AnswerResponse(outcome = answer, freshness = stale), null)
        val reply = replies.single()
        val pieces = messages(reply.body, reply.footer, TELEGRAM_MESSAGE_LIMIT)

        assertTrue(pieces.size > 1) {
            "The reply fit in one message at ${reply.body.length} characters, so this case did not " +
                "exercise splitting at all and proves nothing about a long answer."
        }

        pieces.forEachIndexed { index, piece ->
            assertTrue(piece.length <= TELEGRAM_MESSAGE_LIMIT) {
                "Piece ${index + 1} is ${piece.length} characters; the platform refuses it, and a " +
                    "refused send abandons every piece queued behind it."
            }
            assertBalanced(piece, index)
            assertTrue(piece.replace(Regex("</?[bi]>"), "").isNotBlank()) {
                "Piece ${index + 1} carries markup and no text, which the platform refuses."
            }
        }
    }

    @Test
    fun `the note about the corpus is the tail of the last piece and appears once`() {
        val answer = aLongSectionedAnswer()
        val stale = Freshness(completeThrough = NOW.minus(Duration.ofHours(3)).toString())

        val reply = renderer.render(AnswerResponse(outcome = answer, freshness = stale), null).single()
        val note = requireNotNull(reply.footer) { "nothing was said about a corpus three hours behind" }
        val pieces = messages(reply.body, reply.footer, TELEGRAM_MESSAGE_LIMIT)

        assertTrue(pieces.last().endsWith(note)) {
            "The note is not at the end of the last piece, so it did not travel with the answer."
        }
        assertEquals(1, pieces.count { it.contains(note) }) {
            "The note appears in ${pieces.count { it.contains(note) }} pieces rather than one."
        }
        assertNotEquals(note, pieces.last().trim()) {
            "The last piece is nothing but the note, which is the defect this card is about."
        }
    }

    @Test
    fun `every section's heading reaches a reader emphasised and separated`() {
        val answer = aLongSectionedAnswer()

        val reply = renderer.render(AnswerResponse(outcome = answer, freshness = Freshness()), null).single()
        val pieces = messages(reply.body, reply.footer, TELEGRAM_MESSAGE_LIMIT)

        // **Separated by a blank line or by being the top of a message, and either will do.** The
        // splitter prefers to cut at a paragraph break, so a cut lands on the gap above a heading
        // and that heading opens the next message with the gap consumed. A message boundary
        // separates at least as well as a blank line, so the property is the disjunction — and
        // asserting only the blank line reads a working split as a missing separator.
        // Counted over the pieces after the first, because piece one opens with heading one
        // whatever the splitter does — the answer's first block is a heading and nothing is
        // composed above it. Counting it would make the calibration below unconditionally true,
        // and it is the heading the separation rule exempts in any case.
        val opening = pieces.drop(1).count { piece -> HEADINGS.any { piece.trimStart().startsWith(bold(it)) } }
        HEADINGS.forEachIndexed { index, heading ->
            val emphasised = pieces.any { it.contains(bold(heading)) }
            assertTrue(emphasised) { "Heading ${index + 1} is not emphasised: $heading" }
            val separated = index == 0 ||
                pieces.any { it.contains("\n\n${bold(heading)}") } ||
                pieces.any { it.trimStart().startsWith(bold(heading)) }
            assertTrue(separated) {
                "Heading ${index + 1} sits flush against the line above it and does not open a " +
                    "message either, so nothing separates its section: $heading"
            }
        }

        // **No cut leaves a heading as the last line of a message, at any limit.** This
        // replaces a calibration that recorded the opposite arm — that some cut leaves a heading
        // opening a piece — which was true of this body until the interface began separating every
        // element, and is now true of none: the blank line beneath a heading made that gap a
        // paragraph boundary, the splitter prefers the latest boundary that fits, and so the cut it
        // wanted was exactly the one between a heading and its first item. Measured at the
        // platform's own limit, one piece in two ended with a heading.
        //
        // The splitter declines that cut now, which is why the arm the old calibration guarded is
        // unreachable rather than merely unobserved: a heading travels with its section by
        // construction, so it is the blank line that separates it and never a message boundary.
        // What is asserted here is the rail that made that so, over the cut positions rather than
        // at one of them. The platform's own limit is named because the step does not land on it
        // and it is the limit every real reply is cut at.
        val limits = (SHORT_LIMIT..TELEGRAM_MESSAGE_LIMIT step LIMIT_STEP) + TELEGRAM_MESSAGE_LIMIT
        limits.forEach { limit ->
            val cut = messages(reply.body, reply.footer, limit)
            HEADINGS.forEach { heading ->
                val separated = cut.any { it.contains("\n\n${bold(heading)}") } ||
                    cut.any { it.trimStart().startsWith(bold(heading)) }
                assertTrue(separated) {
                    "At a limit of $limit, $heading is neither preceded by a blank line nor the " +
                        "top of a message, so nothing separates its section."
                }
            }
            val orphaned = cut.filter { piece -> HEADINGS.any { piece.trimEnd().endsWith(bold(it)) } }
            assertTrue(orphaned.isEmpty()) {
                "At a limit of $limit, ${orphaned.size} message(s) end on a heading, so a reader " +
                    "gets a section's title and has to open the next message for anything under it."
            }
            // **And the other end of the same mistake, read off a real reply.** The block saying
            // where the material came from is itself one emphasised run, so the arithmetic that
            // stranded a heading at the end of a message put this one alone in a message of its
            // own: the answer, and then a second message that is a list of names and dates. The
            // freshness note is held to this already, by being reserved for before the body is
            // cut; this block is part of the body and had nothing holding it.
            cut.forEachIndexed { index, piece ->
                val only = piece.trim().let { it.startsWith(ITALIC_OPEN) && it.endsWith(ITALIC_CLOSE) }
                assertFalse(only) {
                    "At a limit of $limit, piece ${index + 1} of ${cut.size} is nothing but what " +
                        "the answer rests on, so it arrives as a message of its own: $piece"
                }
            }
        }
        assertTrue(opening >= 0) { "The count at the platform's own limit is read, not asserted." }

        // What the whole reply rests on, set back, and on the last piece since it follows the
        // answer. Asserted per piece rather than as a total over them: a cut landing inside the
        // block closes the emphasis and reopens it on the next piece, so a total of two is the
        // splitter working rather than a second run — and a case counting the total would red on
        // a body a few hundred characters longer. One run per piece is the property either way,
        // and it is what says nothing else in the reply took this emphasis.
        pieces.forEachIndexed { index, piece ->
            assertTrue(Regex("<i>").findAll(piece).count() <= 1) {
                "Piece ${index + 1} carries more than one set-back run, so something other than " +
                    "the block the reply rests on was set back: $piece"
            }
        }
        assertTrue(Regex("<i>").containsMatchIn(pieces.last())) {
            "What the reply rests on did not reach the last piece, so it is not after the answer."
        }
    }

    @Test
    fun `no text of the answer is lost or duplicated by the cutting`() {
        val answer = aLongSectionedAnswer()

        val reply = renderer.render(AnswerResponse(outcome = answer, freshness = Freshness()), null).single()
        val pieces = messages(reply.body, reply.footer, TELEGRAM_MESSAGE_LIMIT)

        assertTrue(pieces.size > 1) {
            "The reply fit in one message, so comparing the pieces against the body compares a " +
                "string with itself and says nothing about the cutting."
        }

        // Compared on words rather than characters: the splitter trims at each cut and reopens a
        // tag at the head of the remainder, so the whitespace and the markup legitimately differ.
        val sent = pieces.joinToString(" ").replace(Regex("</?[bi]>"), "").split(Regex("\\s+"))
        val composed = reply.body.replace(Regex("</?[bi]>"), "").split(Regex("\\s+"))
        assertEquals(composed.filter { it.isNotBlank() }, sent.filter { it.isNotBlank() }) {
            "The cutting changed the words a reader receives."
        }
    }

    /**
     * An answer long enough that the platform cuts it up, in the shape the probes measured.
     *
     * Six sections of findings, each resting on a cited message, and enough prose that the whole
     * thing is over the platform's limit — which is what makes any of the cases above mean
     * something. [FINDINGS_PER_SECTION] holds the reason the count is what it is.
     */
    private fun aLongSectionedAnswer(): Outcome.SendText {
        val claims = mutableListOf<Claimed>()
        val body = mutableListOf<Block>()
        HEADINGS.forEachIndexed { section, heading ->
            body += Block.Heading(heading)
            val lines = (1..FINDINGS_PER_SECTION).map { finding ->
                val text = "$FINDING_PREFIX ${section + 1}.$finding, $PADDING"
                claims += Claimed(
                    text = text,
                    cites = listOf(
                        Cited(
                            span = "MESSAGE/TELEGRAM/${section}0$finding#0",
                            sourceTime = NOW.minus(Duration.ofDays(3)).toString(),
                            author = "a counterpart",
                        ),
                    ),
                )
                "$ASSERTION$text"
            }
            body += Block.Prose((listOf(LEAD_IN) + lines).joinToString("\n"))
        }
        return Outcome.SendText(body = body, claims = claims)
    }

    private fun assertBalanced(piece: String, index: Int) {
        listOf("b", "i").forEach { tag ->
            assertEquals(
                Regex("<$tag>").findAll(piece).count(),
                Regex("</$tag>").findAll(piece).count(),
            ) { "Piece ${index + 1} leaves <$tag> unbalanced, so the platform refuses the message." }
        }
    }

    private companion object {

        private val NOW: Instant = Instant.parse("2026-10-15T12:00:00Z")

        /** Six, which is what the free-text and status shapes produced on the real archive. */
        private val HEADINGS = listOf(
            "Where things stand",
            "Still open",
            "Agreed and settled",
            "Waiting on the other side",
            "Costs and scope",
            "Next steps",
        )

        /**
         * Six per section, which is what puts the rendered reply past the platform's limit.
         *
         * The shape the probes measured is six sections of roughly twenty findings. That no
         * longer reaches four thousand characters now that what a reply rests on is one block
         * rather than a line under each finding — so the count here is sized for the cut rather
         * than for the measurement, because a reply that does not split says nothing about
         * splitting. [assertTrue] on the piece count is what holds it to that.
         */
        private const val FINDINGS_PER_SECTION = 6

        private const val ASSERTION = "• "

        /**
         * The narrowest limit the separation rule is asserted at, and the step between limits.
         *
         * Narrow enough that a cut falls inside every section rather than only between them, which
         * is what puts a heading at the top of a piece; wide enough that a piece still holds a
         * heading and something under it. The step keeps the sweep to a few dozen renders.
         */
        private const val SHORT_LIMIT = 600

        private const val LIMIT_STEP = 200

        private const val LEAD_IN = "What the material shows about this, in the order it was said:"

        private const val FINDING_PREFIX = "the point settled in section"

        /** Enough words per finding that six sections of them exceed the platform's limit. */
        private const val PADDING =
            "recorded against the thread it was agreed in and carrying the figure that was quoted"
    }
}
