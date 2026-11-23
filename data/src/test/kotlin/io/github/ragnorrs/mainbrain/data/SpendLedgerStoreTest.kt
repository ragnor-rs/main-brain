package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertFailsWith

/**
 * A day's spend, against a real Postgres.
 *
 * Asserted here rather than against a fake because what is worth asserting is the arithmetic the
 * database does: that two tables total as one, that a half-open span takes each collection exactly
 * once, and that a span nothing was billed in comes back as zero rather than as unknown. A double
 * would only assert that the double adds up.
 *
 * **Spans are chosen far from the present, and the one case that cannot be reads a difference.**
 * The rig is one container for the whole suite, so every other store test's submissions are also
 * rows in these tables — a span around now would total them too and the assertion would depend on
 * which tests had run. A span in a year nothing else writes in is this test's alone.
 */
class SpendLedgerStoreTest {

    private val ledger = JdbcSpendLedger(StoreRig.database)

    private val submissions = JdbcSubmissionRepository(StoreRig.database)

    /** A year no other case writes into, so what falls in these spans is only what this put there. */
    private val epoch: Instant = Instant.parse("2031-01-01T00:00:00Z")

    private fun at(hours: Long): Instant = epoch.plus(hours, ChronoUnit.HOURS)

    /**
     * A charge at a chosen instant, written past the port.
     *
     * The port charges to the clock, which is right for the one caller it has and useless for
     * asserting where a boundary falls. Inserted directly so that the instant is this test's to
     * choose — the thing under test is the reading, and a fixture it cannot place is one it cannot
     * read a boundary off.
     */
    private suspend fun chargeAt(moment: Instant, output: Long, deriver: Deriver = Deriver.RECOGNITION) {
        StoreRig.database.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO model_charge
                    (deriver, charged_at, input_tokens, output_tokens,
                     cache_read_tokens, cache_write_tokens)
                VALUES (?, ?, 0, ?, 0, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.bind {
                    string(deriver.name.lowercase())
                    instant(moment)
                    long(output)
                }
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `a span nothing was billed in has spent nothing`(): Unit = runBlocking {
        val spend = ledger.spentBetween(Deriver.RECOGNITION, at(0), at(1))

        assertEquals(TokenUsage.NONE, spend) {
            "A span with no collection and no charge in it totals to null on every column. Read " +
                "as unknown rather than as zero, a bound would refuse a stage that had its whole " +
                "allowance left — which is what a fresh day looks like."
        }
    }

    @Test
    fun `a charge is counted and one outside the span is not`(): Unit = runBlocking {
        // Inside, and on each side of the span — the boundary cases are the point.
        chargeAt(at(100), output = 7)
        chargeAt(at(99), output = 500)
        chargeAt(at(102), output = 900)

        val spend = ledger.spentBetween(Deriver.RECOGNITION, at(100), at(102))

        assertEquals(7, spend.outputTokens) {
            "The span is half-open, so the charge at its start is in and the one at its end is " +
                "out. Closed at both ends, the instant a day ends would be charged to that day " +
                "and to the next, and a bound read twice would refuse a stage that had room."
        }
    }

    @Test
    fun `another pass's charge is not this pass's spend`(): Unit = runBlocking {
        chargeAt(at(200), output = 11, deriver = Deriver.RECOGNITION)
        chargeAt(at(200), output = 400, deriver = Deriver.INDEX)

        val spend = ledger.spentBetween(Deriver.RECOGNITION, at(200), at(201))

        assertEquals(11, spend.outputTokens) {
            "A bound is per pass, so one pass's spend must not refuse another's stage. Indexing " +
                "spends nothing on the model today, which is exactly why a leak here would be " +
                "invisible until something else did."
        }
    }

    @Test
    fun `a collection and a charge total as one figure`(): Unit = runBlocking {
        // The one case whose instant cannot be chosen: a collection is billed to the clock. So it
        // is read as a difference across a span that covers the present, which is independent of
        // whatever else the suite has written into it.
        val wide = Instant.now().minus(1, ChronoUnit.HOURS)
        val until = Instant.now().plus(1, ChronoUnit.HOURS)
        val before = ledger.spentBetween(Deriver.INDEX, wide, until)

        val submission = SubmissionId("spend-${StoreRig.externalId("ledger").nativeId}")
        submissions.record(
            Deriver.INDEX,
            SubmissionStage.CLASSIFICATION,
            RecordKind.MESSAGE,
            submission,
            listOf(SubmittedWindow("m1", StoreRig.externalId("chat"))),
            listOf(StoreRig.externalId("owed")),
            epoch,
            "embeddings-ledger",
        )
        submissions.settle(submission, TokenUsage(inputTokens = 13, outputTokens = 3))
        ledger.charge(Deriver.INDEX, TokenUsage(inputTokens = 2, outputTokens = 5))

        val after = ledger.spentBetween(Deriver.INDEX, wide, until)

        assertEquals(15, after.inputTokens - before.inputTokens) {
            "A settled submission and a charge are both money the provider took, and neither is " +
                "in the other's record. A bound reading one of them would let the other be spent " +
                "without ever being counted."
        }
        assertEquals(8, after.outputTokens - before.outputTokens) {
            "Both axes are totalled across both tables, not just the one the bound is read on."
        }
    }

    @Test
    fun `a span covering no time is refused rather than totalled`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { ledger.spentBetween(Deriver.RECOGNITION, at(10), at(10)) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { ledger.spentBetween(Deriver.RECOGNITION, at(11), at(10)) }
        }
    }
}
