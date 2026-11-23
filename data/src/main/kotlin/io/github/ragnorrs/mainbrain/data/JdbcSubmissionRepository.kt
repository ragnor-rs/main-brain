package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Submission
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.SubmissionRepository
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.sql.Connection
import java.time.Instant

/**
 * The submission table, read and written.
 *
 * Nothing here decides anything: at most one submission may be in flight per pass per stage, and
 * that is a unique index rather than a check made here. A rule enforced in this class would hold
 * only for callers going through this class, and the reason the rule exists — a stage that cannot
 * say which of its own work it has already paid for — is not one worth leaving to that.
 */
// Six port members, each with a private reader or writer beside it, which is one function past the
// per-class cap. Splitting the file would put half a table's access in another place for a reader to
// find; the shape is one class per table and it is worth more than the count.
@Suppress("TooManyFunctions")
class JdbcSubmissionRepository(private val database: Database) : SubmissionRepository {

    /**
     * Write the submission and its requests as one thing.
     *
     * **A transaction, because a submission without its requests is worse than neither.** The row
     * alone would block the pass behind an identifier whose results nothing could attribute to any
     * window, and the index permitting one in flight would hold the pass there for good.
     */
    @Suppress("LongParameterList")
    override suspend fun record(
        deriver: Deriver,
        stage: SubmissionStage,
        kind: RecordKind,
        id: SubmissionId,
        requests: List<SubmittedWindow>,
        records: List<ExternalId>,
        selectedAt: Instant,
        version: String,
    ) {
        require(requests.isNotEmpty()) { "A submission with no requests in it asks the model nothing." }
        // A blank one would compare unequal to the stage's own for ever, so the output of this
        // batch would be owed again on every sweep after it — the whole page re-read at full price,
        // with nothing to say why. The column refuses it too; this is what names the caller.
        require(version.isNotBlank()) {
            "A blank version cannot say which wording produced this submission's answers."
        }
        // **Both halves, because the rule is not that records are optional.** A stage that reads
        // records and carries none could settle nothing when it came back, which is the original
        // check and still the one that matters. The stages whose unit is not a record read none by
        // construction — an adjudication compares two readings already written, and a closure
        // examines one commitment — so for those the error is the opposite one: records on them
        // would be a submission able to mark messages no part of it ever read.
        //
        // Asked of the stage rather than tested against the one stage that was the exception when
        // this was written. The second such stage is what made that an exclusion by name, which
        // admits a third in silence; `SubmissionStage.settlesRecords` says why it is declared there.
        val marks = stage.settlesRecords
        require(!marks || records.isNotEmpty()) {
            "A $stage submission selected for no records could settle nothing when it came back."
        }
        require(marks || records.isEmpty()) {
            "A $stage reads no record of its own, so carrying ${records.size} would let its " +
                "collection mark messages nothing in it read."
        }
        database.transaction { connection ->
            val key = connection.writeSubmission(deriver, stage, kind, id, selectedAt, version)
            connection.writeRequests(key, requests)
            connection.writeRecords(key, records)
        }
    }

    override suspend fun inFlight(deriver: Deriver, stage: SubmissionStage): Submission? =
        database.query { connection -> connection.readInFlight(deriver, stage) }

    override suspend fun settle(id: SubmissionId, cost: TokenUsage) {
        database.transaction { connection -> connection.writeCollection(id, cost) }
    }

    /**
     * Taken out of flight with nothing read off it.
     *
     * Written as a collection costing nothing, which is what the cost columns' constraint requires
     * and what is true of the reading: the money went, and nothing came back that anybody could
     * attribute. Zero rather than the figure the provider would have billed, because an abandoned
     * submission is one nothing was ever read off — what a day's spend should carry for it is
     * whatever was recorded when it was collected, and it never was.
     */
    override suspend fun abandon(id: SubmissionId) {
        database.transaction { connection -> connection.writeCollection(id, TokenUsage.NONE) }
    }

    /** The submission row, returning the key its requests hang from. */
    // One parameter per column the row carries, past the cap for the reason the port's own call is:
    // a parameter object would be a type whose only reader is this statement.
    @Suppress("LongParameterList")
    private fun Connection.writeSubmission(
        deriver: Deriver,
        stage: SubmissionStage,
        kind: RecordKind,
        id: SubmissionId,
        selectedAt: Instant,
        version: String,
    ): Long =
        prepareStatement(INSERT_SUBMISSION).use { statement ->
            statement.bind {
                string(deriver.name.lowercase())
                string(stage.name.lowercase())
                string(kind.name.lowercase())
                string(id.value)
                instant(selectedAt)
                string(version)
            }
            statement.executeQuery().use { rows ->
                check(rows.next()) { "The submission insert returned no identifier." }
                rows.getLong(1)
            }
        }

    private fun Connection.writeRequests(submission: Long, requests: List<SubmittedWindow>) {
        prepareStatement(INSERT_REQUEST).use { statement ->
            requests.forEach { request ->
                statement.bind {
                    long(submission)
                    string(request.key)
                    // Null together or not at all: a request is about a conversation or it is
                    // not, and half an address would read as one the corpus does not hold.
                    string(request.conversation?.source?.name?.lowercase())
                    string(request.conversation?.nativeId)
                    string(request.payload)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /**
     * The uncollected submission and everything it was asking about, in one read.
     *
     * A join rather than two queries: the row is of no use without its requests, and reading them
     * separately would leave a window in which the second read saw a submission the first had not.
     */
    private fun Connection.readInFlight(deriver: Deriver, stage: SubmissionStage): Submission? =
        prepareStatement(SELECT_IN_FLIGHT).use { statement ->
            statement.bind {
                string(deriver.name.lowercase())
                string(stage.name.lowercase())
            }
            statement.executeQuery().use { rows ->
                var key: Long? = null
                var id: String? = null
                var selectedAt: Instant? = null
                var submittedAt: Instant? = null
                // Absent for a submission made before the column existed, which is what the port
                // reads as "the version current at collection" — see `Submission.version`.
                var version: String? = null
                val requests = buildList {
                    while (rows.next()) {
                        key = rows.getLong("id")
                        id = rows.getString("provider_ref")
                        selectedAt = rows.getTimestamp("selected_at").toInstant()
                        submittedAt = rows.getTimestamp("submitted_at").toInstant()
                        version = rows.getString("version")
                        add(
                            SubmittedWindow(
                                key = rows.getString("request_key"),
                                // Absent for a request about no window, which the adjudication
                                // stage's are. Read off the source rather than the native
                                // identifier, since the two are written together.
                                conversation = rows.getString("conversation_source")?.let { source ->
                                    ExternalId(
                                        source = Source.valueOf(source.uppercase()),
                                        nativeId = rows.getString("conversation_native_id"),
                                    )
                                },
                                payload = rows.getString("payload"),
                            ),
                        )
                    }
                }
                id?.let {
                    Submission(
                        id = SubmissionId(it),
                        deriver = deriver,
                        stage = stage,
                        requests = requests,
                        selectedAt = selectedAt!!,
                        submittedAt = submittedAt!!,
                        records = readRecords(key!!),
                        version = version,
                    )
                }
            }
        }

    /**
     * The records the submission was selected for.
     *
     * A second read rather than a third table in the join above, because joining both child tables
     * would multiply their rows against each other and leave the caller de-duplicating a cross
     * product. Same connection, and every row it reads is written once and never updated.
     */
    private fun Connection.readRecords(submission: Long): List<ExternalId> =
        prepareStatement(SELECT_RECORDS).use { statement ->
            statement.bind { long(submission) }
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ExternalId(
                                source = Source.valueOf(rows.getString("record_source").uppercase()),
                                nativeId = rows.getString("record_native_id"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.writeRecords(submission: Long, records: List<ExternalId>) {
        prepareStatement(INSERT_RECORD).use { statement ->
            records.distinct().forEach { record ->
                statement.bind {
                    long(submission)
                    string(record.source.name.lowercase())
                    string(record.nativeId)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.writeCollection(id: SubmissionId, cost: TokenUsage) {
        prepareStatement(UPDATE_COLLECTED).use { statement ->
            statement.bind {
                long(cost.inputTokens)
                long(cost.outputTokens)
                long(cost.cacheReadTokens)
                long(cost.cacheWriteTokens)
                string(id.value)
            }
            statement.executeUpdate()
        }
    }

    private companion object {

        private val INSERT_SUBMISSION = """
            INSERT INTO model_submission
                (deriver, stage, record_kind, provider_ref, selected_at, version)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
        """.trimIndent()

        private val INSERT_RECORD = """
            INSERT INTO model_submission_record (submission, record_source, record_native_id)
            VALUES (?, ?, ?)
        """.trimIndent()

        private val INSERT_REQUEST = """
            INSERT INTO model_submission_request
                (submission, request_key, conversation_source, conversation_native_id, payload)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()

        private val SELECT_IN_FLIGHT = """
            SELECT s.id, s.provider_ref, s.selected_at, s.submitted_at, s.version,
                   r.request_key, r.conversation_source, r.conversation_native_id, r.payload
            FROM model_submission s
            JOIN model_submission_request r ON r.submission = s.id
            WHERE s.deriver = ? AND s.stage = ? AND s.collected_at IS NULL
            ORDER BY r.request_key
        """.trimIndent()

        private val SELECT_RECORDS = """
            SELECT record_source, record_native_id
            FROM model_submission_record
            WHERE submission = ?
        """.trimIndent()

        private val UPDATE_COLLECTED = """
            UPDATE model_submission
            SET collected_at = now(),
                input_tokens = ?,
                output_tokens = ?,
                cache_read_tokens = ?,
                cache_write_tokens = ?
            WHERE provider_ref = ? AND collected_at IS NULL
        """.trimIndent()
    }
}
