package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.recognition.Closing
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line the backend writes for one batch of the closure pass.
 *
 * **Asserted for the reason the inference line is**: `:domain` logs nothing, so a figure this stage
 * counts and the line forgets to print is counted for nobody. It matters more here than for a
 * cheaper stage, because every one of this stage's quiet failures is a count rather than an
 * exception — a reply nobody could read and a window nobody could parse both leave a commitment
 * owed and say nothing otherwise.
 *
 * Computed from the type's own fields, so a figure added to the pass fails here rather than going
 * unreported for as long as it takes somebody to read both files side by side.
 */
class ClosureLineTest {

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
        // The guard on the case above: a figure left at zero in the fixture is one the line is free
        // to drop, because the assertion there would look for a number that is not in it anyway.
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
    fun `how long a submission waited is in the line, since it is not a count`() {
        // It is the one thing the type carries that the computed case above cannot see, and it is
        // spending that bought nothing — so a silent zero is exactly what it must not be.
        val gave = BUSY.copy(abandoned = Duration.ofHours(26))

        val line = gave.reported()

        assertTrue(line.contains("PT26H"), "a submission given up on says nothing about how long it waited: $line")
    }

    @Test
    fun `a batch with nothing to report says so without a row of zeroes`() {
        val line = Closing.NOTHING.reported()

        // **The whole line, for the empty case only.** Everything else here anchors on one clause,
        // because wording is not what those cases are about; this one is, and nothing but an
        // equality can hold it: what must be true is that a batch which did nothing prints its own
        // progress and its bill and *not one figure more*, since a line listing a dozen nothings is
        // one whose single meaningful figure is lost in it. The spend is the named exception — a
        // batch that cost nothing has to be distinguishable from one whose cost nobody recorded.
        assertEquals(
            "0 commitment(s) examined, 0 closed, " +
                "spent 0 in / 0 out (nothing read from or written to cache)",
            line,
        )
    }

    @Test
    fun `the spend is in the line, and the cache counters with it`() {
        // **The only place a bill is observable in this build.** A second stage spending the day's
        // allowance that printed no tokens would make its own cache behaviour unreadable. For this
        // stage the counters are known to sit at zero — its instruction is measured under the
        // model's minimum cacheable prefix — so what the line is carrying them for is the day that
        // stops being true, rather than a defect nobody has looked for yet.
        val line = BUSY.reported()

        assertTrue(line.contains("spent 17 in / 18 out"), "the bill is not in the line: $line")
        assertTrue(line.contains("19 cached"), "the cache read is not in the line: $line")
        assertTrue(line.contains("20 written to cache"), "the cache write is not in the line: $line")
    }

    @Test
    fun `a window the provider would not read is named apart from a verdict`() {
        // The division the reading stages already make: a refusal fires on the provider's own terms,
        // while a verdict of nothing-closed is what the corpus says. Added together, a provider
        // refusing every window would read as a corpus closing its promises in silence.
        val refused = Closing(examined = 1, declined = listOf("the examination was declined as other"))

        val line = refused.reported()

        assertTrue(line.contains("declined"), "a refusal is not named: $line")
        assertTrue(line.contains("as other"), "the provider's category is dropped: $line")
        assertFalse(line.contains("no closure"), "a refusal reads as a corpus that closes nothing: $line")
    }

    @Test
    fun `nothing in the line calls an unclosed commitment open`() {
        // **The ceiling the whole card turns on, in the one place it is easiest to lose.** A promise
        // is often kept in silence, so a verdict of nothing-closed does not establish that anything
        // is still owed — and a log line calling it open would be this build asserting, in its own
        // voice, the one thing it has no evidence for. The answer path carries the same caution for
        // the same reason.
        val line = BUSY.reported()

        listOf("open", "outstanding", "still owed", "pending", "unfinished").forEach { overclaim ->
            assertFalse(
                line.contains(overclaim, ignoreCase = true),
                "The line reads a commitment with no recorded closure as '$overclaim', which is a " +
                    "state nothing in this build establishes: $line",
            )
        }
    }

    @Test
    fun `a reply that bought nothing is named apart from a verdict of nothing-closed`() {
        // The two figures a reader is most likely to conflate, and they mean opposite things: one is
        // a corpus that closes promises in silence, which is this stage working, and the other is
        // calls paid for that decided nothing, which is this stage failing. The shortest phrase that
        // identifies each clause is anchored, because what has to hold is that they are
        // distinguishable rather than any particular wording.
        val silent = Closing(examined = 1, unclosed = 1)
        val wasted = Closing(examined = 1, unread = 1)

        val silentLine = silent.reported()
        val wastedLine = wasted.reported()

        assertTrue(silentLine.contains("no closure"), "a verdict of nothing-closed is not named: $silentLine")
        assertTrue(wastedLine.contains("bought no answer"), "a wasted call is not named: $wastedLine")
        assertFalse(
            silentLine.contains("bought no answer"),
            "a corpus that closes nothing reads as calls that decided nothing: $silentLine",
        )
        assertFalse(
            wastedLine.contains("no closure"),
            "calls that decided nothing read as a corpus that closes nothing: $wastedLine",
        )
    }

    private fun figuresOf(closing: Closing): Map<String, Int> =
        Closing::class.java.declaredFields
            .filter { !it.isSynthetic && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.name to Closing::class.java
                    .getMethod("get${field.name.replaceFirstChar(Char::uppercaseChar)}")
                    .invoke(closing) as Int
            }

    private companion object {

        /**
         * How many figures the line prints whether or not they happened.
         *
         * The commitments examined and the closures found, which are the batch's own progress.
         * Everything else is suppressed at zero, so that a line read by a person is not a row of
         * nothings with one meaningful number in it.
         */
        const val PROGRESS_FIGURES = 2

        /**
         * A batch that did a little of everything, with a distinct figure for each.
         *
         * Not a state a real batch reaches — a step of this stage either asks or collects — which is
         * beside the point: what is asserted is that nothing the type can carry is dropped on the
         * way to the log.
         */
        val BUSY = Closing(
            examined = 11,
            closed = 12,
            unclosed = 13,
            unread = 14,
            unreadable = 15,
            declined = listOf("the examination was declined as whatever"),
            awaiting = 16,
            usage = TokenUsage(inputTokens = 17, outputTokens = 18, cacheReadTokens = 19, cacheWriteTokens = 20),
        )
    }
}
