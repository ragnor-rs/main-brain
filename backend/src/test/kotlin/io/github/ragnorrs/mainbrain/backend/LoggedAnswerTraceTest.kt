package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.answer.Attempted
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trace line, as `eval/` parses it.
 *
 * **This is a format test and not a logging test, which is the useful half.** The evaluation set
 * finds the marker and reads six keys out of the JSON after it; a change to either breaks a gate
 * that runs nowhere near CI, so the string is asserted literally rather than rebuilt from the
 * same code that produces it. An expectation assembled through `buildJsonObject` would agree with
 * any reordering, any renaming and any change of type.
 *
 * The identifiers are deliberately awkward: a Telegram native identifier carries a separator
 * already, and the separator inside it is the thing a hand-rolled line format would have broken
 * on.
 */
class LoggedAnswerTraceTest {

    @Test
    fun `an attempt is one marked line of JSON`() {
        val line = LoggedAnswerTrace.line(
            Attempted(
                shape = ShapeId("research"),
                ordinal = 2,
                retrieved = listOf("MESSAGE/TELEGRAM/-100123:456#0", "MESSAGE/TELEGRAM/-100123:457#0"),
                cited = listOf("MESSAGE/TELEGRAM/-100123:456#0"),
                ungrounded = listOf("MESSAGE/TELEGRAM/-100999:1#0"),
                survived = listOf("MESSAGE/TELEGRAM/-100123:456#0"),
            ),
        )

        assertEquals(
            "answer-trace {" +
                """"shape":"research",""" +
                """"attempt":2,""" +
                """"retrieved":["MESSAGE/TELEGRAM/-100123:456#0","MESSAGE/TELEGRAM/-100123:457#0"],""" +
                """"cited":["MESSAGE/TELEGRAM/-100123:456#0"],""" +
                """"ungrounded":["MESSAGE/TELEGRAM/-100999:1#0"],""" +
                """"survived":["MESSAGE/TELEGRAM/-100123:456#0"]""" +
                "}",
            line,
        )
    }

    @Test
    fun `an attempt that retrieved nothing writes empty arrays rather than omitting them`() {
        // Scoring reads six keys and would otherwise have to tell an absent key from an empty
        // one — and a suite that treated a missing `retrieved` as nothing retrieved would read a
        // renamed key as a retrieval failure in every case at once.
        val line = LoggedAnswerTrace.line(
            Attempted(
                shape = ShapeId("status"),
                ordinal = 1,
                retrieved = emptyList(),
                cited = emptyList(),
                ungrounded = emptyList(),
                survived = emptyList(),
            ),
        )

        assertEquals(
            "answer-trace " +
                """{"shape":"status","attempt":1,"retrieved":[],"cited":[],"ungrounded":[],"survived":[]}""",
            line,
        )
    }
}
