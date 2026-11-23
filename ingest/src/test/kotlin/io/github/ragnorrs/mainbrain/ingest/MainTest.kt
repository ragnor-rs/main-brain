package io.github.ragnorrs.mainbrain.ingest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * What the entry point does with its arguments, which is all of it that can be asserted here.
 *
 * Neither mode can be driven from a test: one reads a source over a live session and the other
 * reads a console. What is left is the decision in front of both — and it is the one worth
 * pinning, because the sign-in mode is typed by hand on a machine, from a document, so the
 * likely wrong argument is a mistyped one.
 */
class MainTest {

    @Test
    fun `an argument that is not the sign-in mode is refused rather than started`() {
        val failure = assertFailsWith<IllegalArgumentException> { main(arrayOf("logn")) }

        assertTrue("logn" in failure.message.orEmpty())
        assertTrue("login" in failure.message.orEmpty()) {
            "The refusal has to name the one argument that works: ${failure.message}"
        }
    }

    /**
     * Refused before the configuration is read, which is what makes this testable at all — and
     * is also the right order: a mistyped argument is not a reason to report a missing variable.
     */
    @Test
    fun `too many arguments are refused, whatever they say`() {
        assertFailsWith<IllegalArgumentException> { main(arrayOf("login", "now")) }
    }
}
