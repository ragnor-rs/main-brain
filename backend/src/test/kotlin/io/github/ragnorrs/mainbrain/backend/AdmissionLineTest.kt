package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.recognition.Admissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line the backend writes for one batch of the admission pass.
 *
 * **Asserted for the reason the other pass lines are**: `:domain` logs nothing, so a figure this
 * stage counts and the line forgets to print is counted for nobody. It matters particularly here,
 * because the figure the pass exists for is one a reader has to be able to see at zero — on a
 * source where every named conversation is already a body of work, rejecting nothing is the correct
 * outcome and is indistinguishable from a pass that never ran.
 *
 * Computed from the type's own fields, so a figure added to the pass fails here rather than going
 * unreported for as long as it takes somebody to read both files side by side.
 */
class AdmissionLineTest {

    @Test
    fun `every figure a batch counts reaches the line that reports it`() {
        val line = BUSY.reported()

        figuresOf(BUSY).forEach { (figure, value) ->
            assertTrue(
                line.contains(value.toString()),
                "The pass counts $figure and the line does not say it, so the only thing that " +
                    "reads this figure is nothing: $line",
            )
        }
    }

    @Test
    fun `the fixture exercises every figure, so a new one cannot be added untested`() {
        val unexercised = figuresOf(BUSY).filterValues { it == 0 }.keys

        assertEquals(
            emptySet(),
            unexercised,
            "These figures are at zero in the fixture, so nothing above asserts the line prints them.",
        )
    }

    @Test
    fun `distinct values, so one figure printed twice cannot stand in for another`() {
        val values = figuresOf(BUSY).values

        assertEquals(values.size, values.toSet().size, "Two figures share a value: $values")
    }

    @Test
    fun `a batch that rejected nothing says so, rather than leaving it out`() {
        // **The case this pass is deferred for.** Where every named conversation is a body of work
        // the pass rejects nothing, which is the right answer and the one a reader most needs
        // distinguished from a pass that is switched off. A zero suppressed here would make the two
        // the same line.
        val line = Admissions(settled = 7, judged = 7).reported()

        assertTrue(line.contains("0 not a body of work"), "a sweep that rejected nothing does not say so: $line")
        assertTrue(line.contains("7 judged"), "what the sweep did judge is not in the line: $line")
    }

    @Test
    fun `a batch with nothing to report says so without a row of zeroes`() {
        val line = Admissions.NOTHING.reported()

        // **The whole line, for the empty case only**, as the sibling lines do it: what must be
        // true is that a batch which did nothing prints its own progress and its bill and not one
        // figure more. The spend is the named exception — a batch that cost nothing has to be
        // distinguishable from one whose cost nobody recorded.
        assertEquals(
            "0 conversation(s) settled, 0 judged, 0 not a body of work, " +
                "spent 0 in / 0 out (nothing read from or written to cache)",
            line,
        )
    }

    @Test
    fun `the spend is in the line, and the cache counters with it`() {
        // The pass calls the provider outside any submission, so this line and the charge rows are
        // the only places its bill is readable at all.
        val line = BUSY.reported()

        assertTrue(line.contains("spent 16 in / 17 out"), "the bill is not in the line: $line")
        assertTrue(line.contains("18 cached"), "the cache read is not in the line: $line")
        assertTrue(line.contains("19 written to cache"), "the cache write is not in the line: $line")
    }

    @Test
    fun `what was settled without asking anybody is not reported as a judgement`() {
        // The two figures a reader is likeliest to conflate. A sweep that settled thirty two-person
        // chats for nothing and a sweep that paid to judge thirty named conversations are the same
        // number of records and opposite amounts of money.
        val free = Admissions(settled = 30, judged = 0)

        val line = free.reported()

        assertTrue(line.contains("30 conversation(s) settled"), "progress is not in the line: $line")
        assertTrue(line.contains("0 judged"), "a sweep that judged nothing does not say so: $line")
    }

    @Test
    fun `a reply nobody could read is named apart from one that was cut short`() {
        // Both leave the conversation owed and both are the build's problem, but they are cleared by
        // different things: a verdict nobody can read is a wording or a parser to fix, and a reply
        // that ran out of room is a ceiling.
        val unreadable = Admissions(unreadable = 1).reported()
        val unread = Admissions(unread = 1).reported()

        assertTrue(unreadable.contains("carried no verdict"), "an unreadable reply is not named: $unreadable")
        assertTrue(unread.contains("unfinished"), "a reply cut short is not named: $unread")
        assertFalse(unread.contains("carried no verdict"), "the two reads alike: $unread")
        assertFalse(unreadable.contains("unfinished"), "the two reads alike: $unreadable")
    }

    @Test
    fun `a refusal carries the provider's own category`() {
        val line = Admissions(settled = 1, declined = listOf("as other")).reported()

        assertTrue(line.contains("declined"), "a refusal is not named: $line")
        assertTrue(line.contains("as other"), "the provider's category is dropped: $line")
    }

    @Test
    fun `nothing in the line calls a rejected conversation deleted or removed`() {
        // The ceiling the whole card turns on. Nothing is deleted: a verdict withholds a client on
        // read and is undone by a better instruction, and a line saying otherwise would be this
        // build describing itself as doing the one thing this layer may not do.
        val line = Admissions(settled = 1, judged = 1, rejected = 1).reported()

        listOf("deleted", "removed", "dropped", "discarded").forEach { overclaim ->
            assertFalse(
                line.contains(overclaim, ignoreCase = true),
                "The line reads a rejected conversation as '$overclaim', which is not what a " +
                    "verdict does: $line",
            )
        }
    }

    private fun figuresOf(admissions: Admissions): Map<String, Int> =
        Admissions::class.java.declaredFields
            .filter { !it.isSynthetic && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.name to Admissions::class.java
                    .getMethod("get${field.name.replaceFirstChar(Char::uppercaseChar)}")
                    .invoke(admissions) as Int
            }

    private companion object {

        /**
         * A batch that did a little of everything, with a distinct figure for each.
         *
         * Not a state a real batch reaches — the counts would have to add up — which is beside the
         * point: what is asserted is that nothing the type can carry is dropped on the way to the
         * log.
         */
        val BUSY = Admissions(
            settled = 11,
            judged = 12,
            rejected = 13,
            unreadable = 14,
            unread = 15,
            declined = listOf("the judgement was declined as whatever"),
            usage = TokenUsage(inputTokens = 16, outputTokens = 17, cacheReadTokens = 18, cacheWriteTokens = 19),
        )
    }
}
