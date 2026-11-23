package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The submission table, against a real Postgres.
 *
 * Asserted here rather than against a fake because the properties worth having are the database's.
 * **One submission in flight per pass per stage is a partial unique index**, not a check in Kotlin,
 * and a fake would only assert that the fake refuses — while the reason the rule exists is that two
 * concurrent backends must not both submit, which is exactly the case no in-process double can
 * reach. The same goes for the constraint tying a cost to a collection: it is there so that a
 * writer which got the order wrong fails loudly instead of recording a bill nothing can attribute.
 */
class SubmissionStoreTest {

    private val submissions = JdbcSubmissionRepository(StoreRig.database)

    private val selected: Instant = Instant.parse("2026-03-03T00:00:00Z")

    /** A submission identifier no other test holds, so these cases do not collide. */
    private fun id(label: String) = SubmissionId("batch-${StoreRig.externalId(label).nativeId}")

    private fun window(key: String, payload: String? = null) =
        SubmittedWindow(key, StoreRig.externalId("chat"), payload)

    /** The records a submission was selected for, which is what a collection may settle. */
    private fun records() = listOf(StoreRig.externalId("owed"))

    /**
     * A submission of [stage], recorded.
     *
     * The stage defaults because most of these cases are about a property every stage shares, and
     * naming it in each of them would read as though the property were the classification's.
     */
    private suspend fun record(
        deriver: Deriver,
        id: SubmissionId,
        windows: List<SubmittedWindow>,
        stage: SubmissionStage = SubmissionStage.CLASSIFICATION,
        version: String = "wording-1",
    ) = submissions.record(deriver, stage, RecordKind.MESSAGE, id, windows, records(), selected, version)

    @Test
    fun `a recorded submission is found in flight with everything it asked about`(): Unit = runBlocking {
        val submission = id("in-flight")
        val windows = listOf(window("m1"), window("m2"), window("m3"))

        record(Deriver.INDEX, submission, windows)

        val found = assertNotNull(submissions.inFlight(Deriver.INDEX, SubmissionStage.CLASSIFICATION))
        assertEquals(submission, found.id)
        assertEquals(windows.map { it.key }.sorted(), found.requests.map { it.key }.sorted())
        assertEquals(
            windows.associate { it.key to it.conversation },
            found.requests.associate { it.key to it.conversation },
            "A reply is matched back by its key, so the conversation behind each one has to " +
                "survive the round trip — it is what the window is re-derived from.",
        )
        // Truncated to the microsecond Postgres keeps, not compared to the nanosecond Java holds.
        assertEquals(
            selected.truncatedTo(ChronoUnit.MICROS),
            found.selectedAt.truncatedTo(ChronoUnit.MICROS),
            "The cut-off the records were selected under is what the collection marks with.",
        )
        assertNull(found.cost, "A submission in flight has not been billed yet.")

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a second submission of one stage is refused while the first is outstanding`(): Unit = runBlocking {
        // The rule the index exists for, and the one no in-process double can assert: two backends
        // both submitting would leave nothing able to say which windows had already been paid for.
        val first = id("one-at-a-time")
        record(Deriver.RECOGNITION, first, listOf(window("m1")))

        assertFailsWith<Exception> {
            runBlocking { record(Deriver.RECOGNITION, id("second"), listOf(window("m2"))) }
        }

        // And permitted again once the first has been read back.
        submissions.settle(first, TokenUsage(inputTokens = 10, outputTokens = 20))
        val second = id("after")
        record(Deriver.RECOGNITION, second, listOf(window("m3")))
        assertEquals(
            second,
            assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION)).id,
        )
        submissions.settle(second, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `the two stages of one pass are outstanding together`(): Unit = runBlocking {
        // The whole point of keying the index on the stage as well as the pass. A restatement asks
        // about what a classification found, so the two can only ever be submitted a collection
        // apart — and keyed on the pass alone, the second could never be outstanding at all.
        val classifying = id("both-classify")
        val restating = id("both-restate")

        record(Deriver.RECOGNITION, classifying, listOf(window("m1")))
        record(
            Deriver.RECOGNITION,
            restating,
            listOf(window("m1#0", payload = "commitment|m1|plain")),
            stage = SubmissionStage.RESTATEMENT,
        )

        assertEquals(
            classifying,
            assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION)).id,
            "Neither stage may be prevented from submitting because the other has work in flight.",
        )
        assertEquals(
            restating,
            assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.RESTATEMENT)).id,
        )

        submissions.settle(classifying, TokenUsage(inputTokens = 1, outputTokens = 1))
        submissions.settle(restating, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a restatement carries the line it was asked about`(): Unit = runBlocking {
        // The candidate is not re-derivable: it is what the classification replied, and that reply
        // is read once. So the line travels with the request and is parsed again at collection.
        val submission = id("payload")
        val line = "commitment|m7|hedged"
        record(
            Deriver.RECOGNITION,
            submission,
            listOf(window("m7#2", payload = line)),
            stage = SubmissionStage.RESTATEMENT,
        )

        val found = assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.RESTATEMENT))
        assertEquals(line, found.requests.single().payload)
        assertNull(
            submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION),
            "The row was written under the wrong stage, or is answering both — which would have " +
                "one stage's collection read the other's requests.",
        )

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a classification request carries no line`(): Unit = runBlocking {
        // The negative half: a classification asks about a window as a whole, so a line here would
        // be a restatement recorded under the wrong stage — which the collection counts as lost.
        val submission = id("no-payload")
        record(Deriver.INDEX, submission, listOf(window("m1")))

        val found = assertNotNull(submissions.inFlight(Deriver.INDEX, SubmissionStage.CLASSIFICATION))
        assertNull(found.requests.single().payload)

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `settling takes a submission out of flight and keeps what it cost`(): Unit = runBlocking {
        val submission = id("settled")
        record(Deriver.INDEX, submission, listOf(window("m1"), window("m2")))

        submissions.settle(submission, TokenUsage(inputTokens = 300, outputTokens = 80, cacheReadTokens = 7))

        assertNull(
            submissions.inFlight(Deriver.INDEX, SubmissionStage.CLASSIFICATION),
            "A collected submission is no longer outstanding, or the pass would read it for ever.",
        )
        // What it cost is asserted where it is read: a day's spend totals the collected rows, and
        // `SpendLedgerStoreTest` is what reads them back. Here the point is that collecting takes
        // the submission out of flight.
    }

    @Test
    fun `nothing is outstanding once a pass's submissions are all collected`(): Unit = runBlocking {
        // A deployment's first sweep finds exactly this. Asserted on the pass this case recorded
        // under rather than on the table: these cases share one Postgres, so what else has been
        // collected by now depends on the order they ran in.
        val submission = id("quiet")
        record(Deriver.INDEX, submission, listOf(window("m1")))
        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))

        assertNull(
            submissions.inFlight(Deriver.INDEX, SubmissionStage.CLASSIFICATION),
            "Everything this case recorded was settled, so nothing of it is outstanding.",
        )
    }

    @Test
    fun `a submission carries the version it was made under, and gives it back`(): Unit = runBlocking {
        // **The thing a collection writes its output under.** A batch is allowed most of a day and
        // a reword arrives by deploy, so a stage reading its version at collection stamps the
        // retired wording's answers as current — and nothing can tell them from answers the new
        // wording produced, because the only thing that would retire them is the version they are
        // now wrongly claiming. Kept here, the collection can name the wording that was paid for.
        val submission = id("versioned")
        record(Deriver.RECOGNITION, submission, listOf(window("m1")), version = "inference-2:wording-before")

        val found = assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION))
        assertEquals("inference-2:wording-before", found.version)

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a submission made before the column existed reads as naming no version`(): Unit = runBlocking {
        // The migration adds the column nullable, so a batch already with the provider when it
        // landed has none — and a null has to mean "the version current at collection", which is
        // exactly what every stage did before this was carried. Written by hand because the port
        // refuses a blank and requires a version of every writer, so no build that has the column
        // can produce this row.
        val submission = id("pre-migration")
        StoreRig.database.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO model_submission (deriver, stage, record_kind, provider_ref, selected_at)
                VALUES ('recognition', 'classification', 'message', ?, now())
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, submission.value)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val key = rows.getLong(1)
                    connection.prepareStatement(
                        "INSERT INTO model_submission_request (submission, request_key) VALUES (?, 'm1')",
                    ).use { request ->
                        request.setLong(1, key)
                        request.executeUpdate()
                    }
                }
            }
        }

        val found = assertNotNull(submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION))
        assertNull(
            found.version,
            "A row written before the column existed has to read as naming no version, or a " +
                "caller is handed a string nothing produced.",
        )

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a blank version is refused rather than stored`(): Unit = runBlocking {
        // It would compare unequal to the stage's own for ever, so everything this batch wrote
        // would be owed again on every sweep after it — the whole page re-read at full price, with
        // nothing to say why. Refused by the port before the column's own check sees it, so the
        // message names the caller rather than the constraint.
        assertFailsWith<IllegalArgumentException> {
            runBlocking { record(Deriver.INDEX, id("blank-version"), listOf(window("m1")), version = " ") }
        }
    }

    @Test
    fun `a submission with no requests is refused before it reaches the table`(): Unit = runBlocking {
        // Nothing to ask the model, and — worse — nothing a later collection could attribute a
        // reply to, so the pass would be blocked behind an identifier that explains nothing.
        assertFailsWith<IllegalArgumentException> {
            runBlocking { record(Deriver.INDEX, id("empty"), emptyList()) }
        }
    }
}
