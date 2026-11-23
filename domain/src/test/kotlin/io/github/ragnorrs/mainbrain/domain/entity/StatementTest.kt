package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The statement, and the three parts of it that decide whether "what was true when we last spoke"
 * has one answer: the half-open window, the content key, and the evidence a fact cannot be stored
 * without.
 *
 * None of this reaches a store. What is asserted here is the arithmetic of validity and the
 * invariants that make a fact storable at all — the store's own rules about accumulating evidence
 * and never reopening a closed window are asserted against Postgres, because they are properties
 * of the write rather than of the value.
 */
class StatementTest {

    @Test
    fun `a window covers its start and stops short of its end`() {
        val window = Validity(from = MARCH, until = JUNE)

        assertTrue(window.covers(MARCH), "A window that does not cover its own start covers nothing.")
        assertTrue(window.covers(APRIL))
        assertFalse(
            window.covers(JUNE),
            "The instant a window closes belongs to whatever opens there. Covering it at both " +
                "ends makes a question about that instant answerable two ways, which is the one " +
                "thing adjacent windows exist to prevent.",
        )
    }

    @Test
    fun `closing a window where the next one opens leaves the shared instant one answer`() {
        // The conflict rule, as arithmetic: a later reading of the same predicate closes the
        // earlier window at its own start, and nothing is deleted.
        val earlier = statement(value = "one rate", validity = Validity(from = MARCH, until = JUNE))
        val later = statement(value = "another rate", validity = Validity(from = JUNE))

        assertTrue(earlier.heldAt(APRIL) && !later.heldAt(APRIL), "April is the earlier reading's.")
        assertTrue(later.heldAt(JUNE) && !earlier.heldAt(JUNE), "June is the later reading's.")
        assertEquals(
            JUNE,
            earlier.validity.until,
            "The earlier statement is kept with a closed window rather than removed: it was true " +
                "until June, which is a different answer from never having been true.",
        )
    }

    @Test
    fun `a window that ends where it begins is refused`() {
        // It would store a statement that held over no instant at all, which reads as a fact
        // nobody can find rather than as a write that should not have happened.
        assertFailsWith<IllegalArgumentException> { Validity(from = JUNE, until = JUNE) }
        assertFailsWith<IllegalArgumentException> { Validity(from = JUNE, until = MARCH) }
    }

    @Test
    fun `a predicate written two ways is one key`() {
        // The dedupe key is the content, so a predicate that reaches the store unnormalised keys
        // a fact under a name nothing else queries — present, correct and invisible.
        assertEquals(Predicate.of("works at"), Predicate.of("  Works   At "))
    }

    @Test
    fun `an unnormalised predicate cannot be constructed directly`() {
        assertFailsWith<IllegalArgumentException> { Predicate("Works At") }
        assertFailsWith<IllegalArgumentException> { Predicate(" ") }
    }

    @Test
    fun `a statement with no evidence is not a statement`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            statement(evidence = emptySet())
        }
        assertTrue(
            failure.message.orEmpty().isNotBlank(),
            "The refusal says why: an uncited derived fact cannot be checked by anyone who " +
                "doubts it, and ordering it against a contradiction needs its evidence's time.",
        )
    }

    @Test
    fun `a blank value asserts nothing`() {
        assertFailsWith<IllegalArgumentException> { statement(value = " ") }
    }

    @Test
    fun `origins rank by authority, weakest first`() {
        // The order the store's upsert repeats when it decides whether an arriving origin may
        // replace a stored one. A correction outranks re-recognition, and this is the only
        // statement of that in the domain.
        assertEquals(
            listOf(Origin.INFERRED, Origin.LABELLED, Origin.CONFIRMED),
            Origin.entries.sorted(),
            "Origin is compared by declaration order, so reordering it silently lets a model's " +
                "reading overwrite a person's correction.",
        )
    }

    private fun statement(
        value: String = "a value",
        validity: Validity = Validity(from = MARCH),
        evidence: Set<Evidence> = setOf(evidence("m1", MARCH)),
    ) = Statement(
        subject = EntityId.mint(),
        predicate = Predicate.of("works at"),
        value = value,
        validity = validity,
        origin = Origin.INFERRED,
        evidence = evidence,
    )

    private fun evidence(message: String, at: Instant) = Evidence(
        span = SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, message)),
        sourceTime = at,
    )

    private companion object {
        val MARCH: Instant = Instant.parse("2026-03-01T00:00:00Z")
        val APRIL: Instant = Instant.parse("2026-04-01T00:00:00Z")
        val JUNE: Instant = Instant.parse("2026-06-01T00:00:00Z")
    }
}
