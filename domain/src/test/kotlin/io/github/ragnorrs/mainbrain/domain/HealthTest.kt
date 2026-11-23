package io.github.ragnorrs.mainbrain.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The one conclusion a reader of ingestion state acts on: is a source waiting for a person.
 *
 * Tested here rather than through the store, because the store cannot produce one of the cases.
 * Two passes recorded through `:data` are two transactions and two clocks reading, so the times
 * are always orderable — which leaves the tie-break, the branch written precisely for times that
 * cannot be ordered, unreachable from there. It is a rule over four fields and no I/O, so it
 * belongs in a test over four fields and no I/O.
 */
class HealthTest {

    private val earlier = Instant.parse("2026-10-01T09:00:00Z")
    private val later = Instant.parse("2026-10-02T09:00:00Z")

    @Test
    fun `a re-authentication nobody has cleared is waiting for a person`() {
        val health = Health(lastSuccessAt = null, lastFailureAt = earlier, lastFailureKind = FailureKind.NEEDS_A_PERSON)

        assertTrue(health.blockedOnAPerson)
    }

    @Test
    fun `a pass that went through afterwards clears it`() {
        val health = Health(
            lastSuccessAt = later,
            lastFailureAt = earlier,
            lastFailureKind = FailureKind.NEEDS_A_PERSON,
        )

        assertFalse(health.blockedOnAPerson) {
            "The failure stays readable, but a later success means nobody has to act on it."
        }
    }

    @Test
    fun `a success that cannot be ordered against the failure counts as still waiting`() {
        // The tie-break, and the reason for it: when the two times cannot be separated, the safe
        // error is sending someone to look at a source that turns out to be fine, rather than
        // leaving an expired session uncleared because a clock could not split two statements.
        val health = Health(
            lastSuccessAt = earlier,
            lastFailureAt = earlier,
            lastFailureKind = FailureKind.NEEDS_A_PERSON,
        )

        assertTrue(health.blockedOnAPerson)
    }

    @Test
    fun `a failure of any other kind is nobody's to clear by hand`() {
        val waiting = Health(lastSuccessAt = null, lastFailureAt = later, lastFailureKind = FailureKind.TEMPORARY)
        val unclassified = Health(lastSuccessAt = null, lastFailureAt = later, lastFailureKind = FailureKind.UNEXPECTED)

        assertFalse(waiting.blockedOnAPerson)
        assertFalse(unclassified.blockedOnAPerson) {
            "An unclassified fault may well need a person, but nothing has established that — " +
                "and this flag is what sends one, so it says only what is known."
        }
    }

    @Test
    fun `a kind with no time behind it sends nobody anywhere`() {
        // Unreachable through the store, which stamps a time with every pass — but this is a
        // rule over four fields, and a field that can be null is a case the rule has to answer.
        val health = Health(lastSuccessAt = null, lastFailureAt = null, lastFailureKind = FailureKind.NEEDS_A_PERSON)

        assertFalse(health.blockedOnAPerson) {
            "Without a time there is nothing to order against a success, and nothing to report."
        }
    }

    @Test
    fun `a source that has only ever succeeded is waiting for nobody`() {
        val health = Health(lastSuccessAt = later, lastFailureAt = null, lastFailureKind = null)

        assertFalse(health.blockedOnAPerson)
    }
}
