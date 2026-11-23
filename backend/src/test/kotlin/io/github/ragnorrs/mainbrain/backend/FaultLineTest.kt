package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.answer.Fault
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line the backend writes when an answer did not hold.
 *
 * **Asserted because it is the only account of failures that are identical from outside.** Every
 * fault reaches whoever asked as an ordinary reply, and most of them as *nothing to answer from* —
 * a variant count the shape did not promise, an answer that asserted nothing, a reply resting
 * entirely on material nothing retrieved, a dropped claim, and a call that came back carrying
 * nothing readable. The one reply that names itself is a complete answer the reader rejected, and
 * even there the line is what says which of the ways it came back that way. So this is where the
 * difference between them is, and a line that said less than it looked like it said would leave the
 * next reader of a failed run exactly where one was before it existed: guessing between causes from
 * a token count.
 *
 * Not about wording. What each case asserts is that the fact distinguishing it from the others
 * is in the string.
 */
class FaultLineTest {

    @Test
    fun `an unreadable reply says the model was called, which an empty corpus does not`() {
        val line = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered("[CLAIM] a finding cited to nothing")))

        assertTrue(line.contains("not the same as an empty corpus"))
        assertTrue(
            line.contains("[CLAIM] a finding cited to nothing"),
            "The reply is not in the line, so which of the format's rules dropped it is a guess. " +
                "That guess is the whole cost this line exists to remove: $line",
        )
    }

    @Test
    fun `a complete reply the reader rejected does not read as one that was answered`() {
        // **The one fault whose two halves end the attempt differently, so the line has to too.** A
        // reply the provider reported complete is discarded and generation rolled again; a
        // truncation, a refusal and a pause are answered as they stand. One wording for both would
        // have whoever holds the log reading a re-rolled attempt as a request that stopped there —
        // and reading the attempt that *was* answered as one more call than was made.
        val rejected = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered("no markers at all")))
        val stood = BackendContainer.fault(
            SHAPE,
            1,
            Fault.Unreadable(ModelOutcome.Truncated(partial = "[VARIANT]\nHalf an ans", usage = TokenUsage.NONE)),
        )

        assertTrue(rejected.startsWith("Rejected attempt 1"), rejected)
        assertFalse(
            rejected.contains("as nothing to answer from"),
            "A discarded attempt reads as the request's own answer, which it is not: $rejected",
        )
        assertTrue(stood.contains("as nothing to answer from"), stood)
        assertFalse(stood.startsWith("Rejected"), stood)
    }

    @Test
    fun `a long reply is cut to the bound and says how much there was`() {
        // A reply is model output sized by a shape's ceiling. Printed whole, one degraded request
        // writes thousands of characters into the log for every answer that could not be given.
        //
        // Markerless rather than long-with-a-marker: a reply opening on [VARIANT] and continuing into
        // prose is one the reader accepts, so reporting it as unreadable is a state `Fault.Unreadable`
        // now refuses to be built in. The fixture has to be a reply that genuinely does not parse.
        val reply = "x".repeat(5_010)

        val line = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered(reply)))

        assertTrue(line.contains("5010 character(s)"), "The line does not say how much was left out: $line")
        assertTrue(line.contains("x".repeat(600)), "Less than the bound reached the line: $line")
        assertFalse(
            line.contains("x".repeat(601)),
            "More than the bound reached the line, so one degraded request can write a wall of it.",
        )
    }

    @Test
    fun `a reply short enough to read whole is not cut`() {
        val line = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered("no markers at all")))

        assertTrue(line.contains("It read: no markers at all"))
        assertFalse(line.contains("character(s)"), "A reply given whole should not also be counted: $line")
    }

    @Test
    fun `a refusal and an unparsed answer do not read alike`() {
        val refused = BackendContainer.fault(
            SHAPE,
            1,
            Fault.Unreadable(ModelOutcome.Refused(category = "some_category", usage = TokenUsage.NONE)),
        )
        val unparsed = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered("no markers at all")))

        assertTrue(refused.contains("some_category"))
        assertTrue(refused.contains("declined"))
        assertTrue(unparsed.contains("the format was not followed"))
        assertFalse(
            refused.contains("the format was not followed"),
            "A refusal reads as a format failure. The two are repaired in different places, and " +
                "conflating them sends the next reader to rewrite a prompt that was never the " +
                "problem: $refused",
        )
    }

    @Test
    fun `a truncation says what it managed and a pause says it stopped`() {
        val truncated = BackendContainer.fault(
            SHAPE,
            1,
            Fault.Unreadable(ModelOutcome.Truncated(partial = "[VARIANT]\nHalf an ans", usage = TokenUsage.NONE)),
        )
        val paused = BackendContainer.fault(
            SHAPE,
            1,
            Fault.Unreadable(ModelOutcome.Paused(partial = "[VARIANT]\nmid-flight", usage = TokenUsage.NONE)),
        )

        assertTrue(truncated.contains("ran out of room"))
        assertTrue(truncated.contains("Half an ans"))
        assertTrue(paused.contains("stopped mid-flight"))
        assertTrue(paused.contains("mid-flight"))
    }

    @Test
    fun `a reply that came back empty says so rather than trailing off`() {
        val line = BackendContainer.fault(SHAPE, 1, Fault.Unreadable(answered("   ")))

        assertTrue(line.contains("It came back empty."), line)
    }

    @Test
    fun `a citation that could not be read is named as written and apart from the identifiers`() {
        // Apart for the reason `Fault.Ungrounded.unreadable` gives. Quoted because a citation is a
        // comma-delimited piece and so may carry spaces: unquoted, one the model wrote as several
        // words would read as several citations.
        val line = BackendContainer.fault(
            SHAPE,
            1,
            Fault.ClaimsDropped(
                cited = listOf("MESSAGE/TELEGRAM/42#0"),
                of = 3,
                claims = 2,
                dropped = 1,
                unreadable = listOf("the thread with finance"),
            ),
        )

        assertTrue(
            line.contains("\"the thread with finance\""),
            "The citation is not quoted, so the words the model wrote read as several: $line",
        )
        assertTrue(line.contains("Unreadable"), line)
        assertTrue(
            line.indexOf("MESSAGE/TELEGRAM/42#0") < line.indexOf("the thread with finance"),
            "The unreadable citation is mixed in with the identifiers rather than named apart: $line",
        )
    }

    @Test
    fun `a fault with nothing unreadable says nothing about it`() {
        // The ordinary case is every citation reading and some naming material that was not
        // retrieved. A line trailing an empty list there would have the next reader looking for a
        // format failure that did not happen.
        val line = BackendContainer.fault(
            SHAPE,
            1,
            Fault.ClaimsDropped(cited = listOf("MESSAGE/TELEGRAM/42#0"), of = 3, claims = 2, dropped = 1),
        )

        assertFalse(line.contains("Unreadable"), line)
    }

    @Test
    fun `a fault naming no identifier does not promise a list it has not got`() {
        // **The state the widened guard newly admits, and the one the formatter had not been told
        // about.** Either half of the offender report may be empty, so a line printing both
        // unconditionally reads `Mistyped or invented:` with nothing after it — which has whoever
        // holds the log looking for a citation nobody wrote.
        //
        // The arms below are computed rather than keyed on wordings. An earlier version of this test
        // forbade the literal `0 identifier`, which only the clause removed in the same commit could
        // ever have printed: it matched nothing in the tree but itself and could not fail. Asking
        // instead what shape a malformed line has survives the next rewording.
        val dropped = BackendContainer.fault(
            SHAPE,
            1,
            Fault.ClaimsDropped(
                cited = emptyList(),
                of = 3,
                claims = 2,
                dropped = 1,
                unreadable = listOf("two"),
            ),
        )
        val refused = BackendContainer.fault(
            SHAPE,
            1,
            Fault.NothingGrounded(cited = emptyList(), of = 1, claims = 1, unreadable = listOf("two")),
        )

        listOf(dropped, refused).forEach { line ->
            assertFalse(line.contains("invented:"), "An empty identifier list was still promised: $line")
            // Any label whose list is empty leaves its colon with nothing but the sentence's full
            // stop after it, however the label comes to be worded.
            assertFalse(
                line.contains(Regex(":\\s*(\\.|$)")),
                "An offender label was printed with nothing after it: $line",
            )
            // Every count in this codebase is written `N thing(s)`, so this catches a zero of any of
            // them rather than of one named noun. Sound for the two citation faults only: a wrong
            // variant count legitimately reports nought variants.
            assertFalse(
                line.contains(Regex("\\b0 \\w+\\(s\\)")),
                "A count of something that never happened was reported: $line",
            )
            assertTrue(line.contains("\"two\""), "The citation that did fail is not named: $line")
        }
    }

    @Test
    fun `a reply that asserted nothing says how much material it had`() {
        // The one rejection with no offender to name, so the line has to say what it had instead.
        // Without the count it reads as a reply that could not have asserted anything, which is
        // the case the runner settles before any model call — and the reader would be looking for
        // an empty corpus rather than for an answer that went into the prose.
        val line = BackendContainer.fault(SHAPE, 1, Fault.NothingAsserted(of = 12))

        assertTrue(line.contains("12 span(s)"), "The line does not say what material was there: $line")
        assertFalse(
            line.contains("invented") || line.contains("Unreadable"),
            "It reads as a citation fault, which sends the reader looking for a citation nobody " +
                "wrote: $line",
        )
    }

    @Test
    fun `a dropped alternative says how many were dropped, of how many it chose from`() {
        // Both counts, and both over the set the drop was taken from. The arithmetic is the point
        // rather than the wording: a reply whose alternatives were mostly assertions loses them to
        // the citation check first, so a line counting against what the model originally wrote
        // would say one of three was empty about a reply whose other two had already gone — a model
        // that ignored the format reported as one that complied twice.
        // The drafting shape, because it is the only one that offers a choice and so the only one
        // this fault can arise from. Built with `status` the arrangement could not have happened,
        // and a line asserted over an impossible input says nothing about the one that occurs.
        val line = BackendContainer.fault(OFFERS_A_CHOICE, 1, Fault.AlternativesDropped(offered = 3, dropped = 1))

        assertTrue(line.contains("1 of the 3 alternative(s)"), "The line does not carry both counts: $line")
        assertTrue(
            line.contains("offered the rest"),
            "The line does not say the rest were given, so it reads as a rejection: $line",
        )
        assertFalse(
            line.startsWith("Rejected"),
            "A dropped alternative is a repair and the answer was given. Opening as a rejection " +
                "puts it with the faults that cost the request its answer: $line",
        )
    }

    @Test
    fun `a dropped alternative cannot be reported as the whole reply being empty`() {
        // The arithmetic the wording above rests on, asserted where it is decided rather than
        // trusted. `offered` is the set after the citation check, so `dropped` can never equal it:
        // an answer with nothing left to offer is the count's fault and not this one.
        val refused = assertFailsWith<IllegalArgumentException> {
            Fault.AlternativesDropped(offered = 3, dropped = 3)
        }

        assertTrue(
            refused.message.orEmpty().contains("3 of 3"),
            "The refusal does not name the counts it refused: ${refused.message}",
        )
    }

    @Test
    fun `every fault names the attempt and the shape that produced it`() {
        // Two attempts are reported per failed request and five shapes can produce one, so a line
        // missing either is a line that cannot be attributed to a request.
        val reasons = listOf(
            Fault.WrongVariantCount(wanted = 1..1, got = 0),
            Fault.NothingAsserted(of = 3),
            Fault.AlternativesDropped(offered = 3, dropped = 1),
            Fault.NothingGrounded(cited = listOf("MESSAGE/TELEGRAM/42#0"), of = 3, claims = 2),
            Fault.ClaimsDropped(cited = listOf("MESSAGE/TELEGRAM/42#0"), of = 3, claims = 2, dropped = 1),
            Fault.Unreadable(answered("no markers at all")),
        )

        reasons.forEach { reason ->
            val line = BackendContainer.fault(SHAPE, 2, reason)
            val named = reason::class.simpleName
            assertTrue(line.contains("attempt 2"), "$named does not say which attempt: $line")
            assertTrue(line.contains("status"), "$named does not say which shape: $line")
            // A case worded by falling through to the object's own `toString` is how a new fault
            // reaches a log as a class name. Exhaustiveness the compiler already keeps; this is the
            // part it cannot see.
            assertFalse(line.contains("$named("), "$named reached the line as its own class: $line")
        }
    }

    private companion object {

        val SHAPE = ShapeId("status")

        /** The one shape that offers alternatives, so the one a dropped alternative can come from. */
        val OFFERS_A_CHOICE = ShapeId("draft")

        fun answered(text: String): ModelOutcome = ModelOutcome.Answered(text = text, usage = TokenUsage.NONE)
    }
}
