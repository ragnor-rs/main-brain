package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.recognition.Inference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line the backend writes for one batch of the inference pass.
 *
 * **Asserted because the line is the only reader of most of these figures.** `:domain` logs nothing
 * and the pass is the one thing in this build that spends money, so a figure it counts and this
 * forgets to print is counted for nobody — which is what had already happened to the one saying how
 * many readings a rule had declined: collected on every batch, shown on none, and invisible from
 * anywhere else in the build.
 *
 * Not about wording. The cases below are computed from the type's own fields, so a figure added to
 * the pass and left out of the line fails here rather than going unreported for as long as it takes
 * somebody to read both files side by side.
 */
class InferenceLineTest {

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
        // **The guard on the case above**, and the reason that one is not an enumeration. A figure
        // this fixture leaves at zero is a figure the line is free to drop, because the assertion
        // there would look for a number that happens not to be there to begin with. So the fixture
        // is held against the type rather than against a list, and a figure added to the pass
        // fails here until it is given a value — and then fails above until it is printed.
        val unexercised = figuresOf(BUSY).filterValues { it == 0 }.keys

        assertEquals(
            emptySet(),
            unexercised,
            "These figures are at zero in the fixture, so nothing above asserts the line prints them.",
        )
    }

    @Test
    fun `distinct values, so one figure printed twice cannot stand in for another`() {
        // The values in the fixture are what the case above matches on, so two figures sharing one
        // would let a line that printed the first twice pass for a line that printed both.
        val values = figuresOf(BUSY).values

        assertEquals(values.size, values.toSet().size, "Two figures share a value: $values")
    }

    @Test
    fun `a batch with nothing to report says so without a row of zeroes`() {
        // The other half of suppressing at zero: a line is read by a person, and one listing a
        // dozen nothings is one whose single meaningful figure is lost in it. The two that are the
        // batch's own progress are printed at zero deliberately — a batch that settled nothing is
        // worth saying — so what this holds is that they are the only two.
        val line = Inference.NOTHING.reported()

        val zeroes = Regex("\\b0 [\\w/]+\\(s\\)").findAll(line).map { it.value }.toList()

        assertEquals(
            PROGRESS_FIGURES,
            zeroes.size,
            "A batch that did nothing printed ${zeroes.size} counts of nothing: $zeroes",
        )
        assertTrue(line.contains("spent"), "The spend is said even at zero, which is the one exception: $line")
    }

    @Test
    fun `the rows caused and the readings offered are told apart`() {
        // A batch offering more than it caused has to say both numbers, or every rate measured off
        // a sweep is read through a figure that could mean either of them.
        val collapsing = Inference(records = 1, written = 2, lost = 0, unread = 0, attempted = 9, collapsed = 7)

        val line = collapsing.reported()

        assertTrue(line.contains("2"), "The rows caused are not in the line: $line")
        assertTrue(
            line.contains("9"),
            "The readings handed over are not in the line, so a collapse and a quiet loss read " +
                "the same: $line",
        )
    }

    @Test
    fun `a collapse and an overwrite are not interchangeable clauses`() {
        // **What the figures-are-present case above cannot see.** It matches on values, so a line
        // printing the collapse count under the overwrite's words and the overwrite count under the
        // collapse's would satisfy every assertion in it — and the whole purpose of separating the
        // two is that a duplicate recognised and a window read again mean opposite things. Two
        // words are anchored and no more, because what has to hold is the distinction rather than
        // any particular wording of it.
        val folded = Inference(records = 0, written = 0, lost = 0, unread = 0, attempted = 1, collapsed = 1)
        val replaced = Inference(records = 0, written = 0, lost = 0, unread = 0, attempted = 1, overwritten = 1)

        val foldedLine = folded.reported()
        val replacedLine = replaced.reported()

        assertTrue(foldedLine.contains("collapsed"), "A folded reading is not named as one: $foldedLine")
        assertFalse(
            foldedLine.contains("overwritten"),
            "A sweep that recognised a duplicate reads as one that read the same window again: $foldedLine",
        )
        assertTrue(replacedLine.contains("overwritten"), "A replaced row is not named as one: $replacedLine")
        assertFalse(
            replacedLine.contains("collapsed"),
            "A sweep that re-read a window reads as one that recognised a duplicate: $replacedLine",
        )
    }

    @Test
    fun `the two predicate figures are named apart in the line, and apart from the rules`() {
        // **The line is where these are read, and the present-and-distinct cases above match on
        // values alone** — so a line printing any of these counts under another's words would
        // satisfy every one of them. That matters most for the two predicate figures: the refusal
        // word is what the instruction asks for where no entry fits, so it is the one that bears on
        // the list, while a word outside the list is that instruction disobeyed. Neither measures
        // the width — no figure does — but they answer different questions, and a reader who
        // reads one for the other learns nothing it can say. The rules declining a finding over
        // where it sits or whom it is about answer a third question again, and fire as a
        // conversation grows. The third answer to that field, a blank one, is drift and is
        // reported with the lines that would not parse, so it has no clause of its own to tell
        // apart. Each assertion anchors on the shortest phrase that identifies its clause,
        // because what has to hold is that the three are distinguishable and not any particular
        // wording of them.
        val outside = Inference(records = 0, written = 0, lost = 0, unread = 0, outsideVocabulary = 1)
        val unnamed = Inference(records = 0, written = 0, lost = 0, unread = 0, unnamed = 1)
        val byRule = Inference(records = 0, written = 0, lost = 0, unread = 0, refused = 1)

        val outsideLine = outside.reported()
        val unnamedLine = unnamed.reported()
        val byRuleLine = byRule.reported()

        assertTrue(
            outsideLine.contains("vocabulary"),
            "A stretched predicate is not named as being about the vocabulary: $outsideLine",
        )
        assertTrue(
            unnamedLine.contains("named no predicate"),
            "The figure that bears on the list is not named as the model naming none, so nothing " +
                "in the line can be read against the list at all: $unnamedLine",
        )
        assertTrue(
            byRuleLine.contains("refused by a rule"),
            "A finding a rule declined is not named as one, so the clause the two predicate " +
                "figures have to be told apart from is not in the line at all: $byRuleLine",
        )
        assertFalse(
            unnamedLine.contains("vocabulary"),
            "The refusal the instruction asked for is named as being about the vocabulary, so the " +
                "two predicate figures cannot be told apart by their words: $unnamedLine",
        )
        assertFalse(
            outsideLine.contains("named no predicate"),
            "A stretched predicate reads as the refusal the instruction asked for, which is the " +
                "confusion that would have a list widened against the wrong figure: $outsideLine",
        )
        assertFalse(
            byRuleLine.contains("vocabulary"),
            "A finding declined over where it sat reads as a predicate the list does not hold: $byRuleLine",
        )
        assertFalse(
            byRuleLine.contains("named no predicate"),
            "A subject or placement rule reads as the figure that bears on the list, which is the " +
                "one confusion that would have the list widened against a conversation growing: $byRuleLine",
        )
        assertFalse(
            unnamedLine.contains("refused by a rule"),
            "The figure that bears on the list reads as a rule declining a finding: $unnamedLine",
        )
        assertFalse(
            outsideLine.contains("refused by a rule"),
            "A word outside the vocabulary reads as a rule declining a finding: $outsideLine",
        )
    }

    @Test
    fun `a value the predicate does not take is named apart from the two figures blind to it`() {
        // **The third answer to the predicate field, and the one the other two cannot see.** Both
        // of those read the word in the field: one is the refusal the instruction asks for, the
        // other a word the list does not hold. This fires where the field held a legal entry and
        // the value under it was of a sort that entry does not take — so a reader who takes it for
        // either of the others reads a wrong value stored as a question about how wide the list
        // should be. Anchored on the shortest phrase identifying each clause, as the pair above is.
        val misshapen = Inference(records = 0, written = 0, lost = 0, unread = 0, misshapen = 1)
        val outside = Inference(records = 0, written = 0, lost = 0, unread = 0, outsideVocabulary = 1)
        val unnamed = Inference(records = 0, written = 0, lost = 0, unread = 0, unnamed = 1)

        val misshapenLine = misshapen.reported()

        assertTrue(
            misshapenLine.contains("does not take"),
            "A value of the wrong sort is not named as one, so the only rail that can catch a " +
                "legal predicate over a wrong value reports nothing when it fires: $misshapenLine",
        )
        assertFalse(
            misshapenLine.contains("predicate vocabulary"),
            "A wrong value reads as a predicate outside the list, which would have the vocabulary " +
                "widened against a fault that has nothing to do with its width: $misshapenLine",
        )
        assertFalse(
            misshapenLine.contains("named no predicate"),
            "A wrong value reads as the refusal the instruction asks for, which is the figure the " +
                "list is read against: $misshapenLine",
        )
        assertFalse(
            misshapenLine.contains("refused by a rule"),
            "A wrong value reads as a rule about placement or subject, which fires as a " +
                "conversation grows and would bury it: $misshapenLine",
        )
        listOf(outside, unnamed).map(Inference::reported).forEach { line ->
            assertFalse(
                line.contains("does not take"),
                "A figure read off the predicate field reads as the value test, which is the one " +
                    "thing in the line that holds whatever the instructions say: $line",
            )
        }
    }

    @Test
    fun `a fact outside the listed attributes is named, and not as a reading that went nowhere`() {
        // **The figure that exists because its absence was unreadable.** A stage that declines
        // material it cannot label and a stage that drops it in silence are the same log line
        // without this clause, which is how the attribute path writing nothing came to be read as
        // a corpus holding nothing. So the clause has to be present, and it has to not read as a
        // loss: nothing was paid beyond the classification and no reading went anywhere.
        val unlisted = Inference(records = 0, written = 0, lost = 0, unread = 0, unlisted = 1)

        val line = unlisted.reported()

        assertTrue(
            line.contains("does not cover"),
            "A fact the stage met and could not label is not in the line, so the path's silence " +
                "still cannot be told from the path working: $line",
        )
        // **Not merely a different phrase — a phrase with no word in common with its opposite.**
        // `outsideVocabulary` is the closed list being disobeyed and is acted on; this is the
        // instruction being obeyed and costs nothing. Both once read "outside", which is the one
        // way these clauses go wrong: a figure printed under another's words.
        assertFalse(
            line.contains("outside"),
            "The obeyed answer shares a word with the disobeyed one, so a reader of one line " +
                "cannot tell the figure that is acted on from the figure that is not: $line",
        )
        listOf("lost", "refused by a rule", "predicate vocabulary", "not extractable").forEach { loss ->
            assertFalse(
                line.contains(loss),
                "The one figure here that is no shortfall reads as `$loss`, so the instructed " +
                    "answer would be chased as a defect: $line",
            )
        }
    }

    @Test
    fun `the two refused-date figures are named apart in the line`() {
        // **The same gap the predicate pair closed, one field along.** The present-and-distinct
        // cases match on values alone, so a line printing these two counts under each other's
        // words satisfies every one of them — and the whole reason they are two figures is that
        // one is the record contradicting itself and rare while the other is the field being
        // copied off the message's own line. A reader who takes one for the other reads a prompt
        // to fix as a model answering from nowhere. Anchored on the shortest phrase that
        // identifies each clause, because what has to hold is that they are distinguishable.
        val before = Inference(records = 0, written = 0, lost = 0, unread = 0, backdated = 1)
        val echoed = Inference(records = 0, written = 0, lost = 0, unread = 0, sameInstant = 1)

        val beforeLine = before.reported()
        val echoedLine = echoed.reported()

        assertTrue(
            beforeLine.contains("preceding"),
            "A date before its own message is not named as preceding it: $beforeLine",
        )
        assertTrue(
            echoedLine.contains("own instant"),
            "A date copied off the message's line is not named as its instant, so nothing in the " +
                "line bears on the instruction against copying it: $echoedLine",
        )
        assertFalse(
            beforeLine.contains("own instant"),
            "A date before its own message reads as one copied off it, which sends a reader to " +
                "the prompt over a case the record settles on its own: $beforeLine",
        )
        assertFalse(
            echoedLine.contains("preceding"),
            "A copied instant reads as the record contradicting itself, which is the rare case: $echoedLine",
        )
    }

    /**
     * Every whole-number figure on an [Inference], by the name the type gives it.
     *
     * Read off the class rather than listed, which is the whole point: an enumeration written here
     * would go stale the first time the pass learnt to count something new, and it would go stale
     * silently — the figure would simply stop being covered. Synthetic fields are dropped because
     * they are the compiler's and not the pass's.
     */
    private fun figuresOf(inference: Inference): Map<String, Int> =
        Inference::class.java.declaredFields
            .filter { !it.isSynthetic && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.name to Inference::class.java
                    .getMethod("get${field.name.replaceFirstChar(Char::uppercaseChar)}")
                    .invoke(inference) as Int
            }

    private companion object {

        /**
         * How many figures the line prints whether or not they happened.
         *
         * The records settled and the rows written, which are the batch's own progress: a batch
         * that settled nothing is exactly what a reader needs told. Everything else is suppressed
         * at zero.
         */
        const val PROGRESS_FIGURES = 2

        /**
         * A batch that did a little of everything, with a distinct figure for each.
         *
         * Distinct so that the line cannot satisfy two assertions with one number, and all non-zero
         * so that every figure is actually looked for. It is not a state a real batch reaches — a
         * step of this pass does one stage — which is fine for what is being asserted: that nothing
         * the type can carry is dropped on the way to the log.
         */
        val BUSY = Inference(
            records = 11,
            written = 12,
            lost = 13,
            unread = 14,
            attempted = 15,
            overwritten = 16,
            collapsed = 17,
            superseded = 18,
            unanswered = 19,
            unextractable = 21,
            backdated = 33,
            sameInstant = 34,
            unplaced = 22,
            refused = 23,
            unnamed = 29,
            outsideVocabulary = 30,
            misshapen = 35,
            unlisted = 36,
            repeated = 28,
            held = 24,
            merged = 25,
            apart = 26,
            awaiting = 27,
            declined = listOf("a window nothing will read"),
            usage = TokenUsage(inputTokens = 31, outputTokens = 32),
            unreadable = "a stop reason this build has no reader for",
        )
    }
}
