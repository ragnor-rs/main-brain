package io.github.ragnorrs.mainbrain.domain

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant

/**
 * A [SubmissionRepository] that keeps its rows in a list.
 *
 * Ships with the port rather than with whichever caller needed it first, for the reason
 * [FakeModelProvider] does: two callers growing two doubles is how they come to disagree about what
 * the port does, and the disagreement surfaces as a passing test beside a failing deploy.
 *
 * **The one rule it does enforce is the one the store enforces with an index: at most one submission
 * per pass per stage may be in flight.** That is not decoration — a stage with two outstanding
 * cannot say which of its own work it has already paid for, and a double that permitted two would
 * let a caller be written against a world the database refuses. It is keyed on the stage as well as
 * the pass for the same reason the index is: the two stages of one pass are outstanding together in
 * the ordinary case, so a double refusing that would refuse the behaviour under test. Everything
 * else here is storage.
 */
class FakeSubmissionRepository(
    /**
     * What this fake records as the moment a submission was handed over.
     *
     * Settable so a caller that gives up on an old submission can be driven to do it: the deadline
     * is read from the difference between this and the sweep's own clock, and a test that could not
     * move either could never reach the case.
     */
    private val clock: Instant = Instant.EPOCH,
) : SubmissionRepository {

    private val rows = mutableMapOf<SubmissionId, Row>()
    private val order = mutableListOf<SubmissionId>()

    /**
     * The newest collected cost of each stage, keyed as the store keys it.
     *
     * Per stage rather than one figure for all of them, because the store's own query is: a window
     * read whole and a sentence rewritten do not cost the same, so a single figure would have
     * whichever settled last answer for the other. Only the classification's is read today — the
     * restatement stage is priced by nothing — and the key is per stage so that a reader of the
     * other cannot be given a figure the store would not have.
     */

    /** Every submission recorded through this fake, in the order it was recorded. */
    val recorded: List<Submission>
        get() = order.map { id -> rows.getValue(id).asSubmission(id) }

    /** Every submission that has been settled, with what it was billed. */
    val settled: List<Pair<SubmissionId, TokenUsage>>
        get() = order.mapNotNull { id -> rows.getValue(id).cost?.let { id to it } }

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
        // The store refuses a blank one with a check constraint, so a fake that took it would let a
        // caller pass here and fail against Postgres.
        require(version.isNotBlank()) {
            "A blank version cannot say which wording produced this submission's answers."
        }
        // The same two-sided rule the store enforces, and written here because a fake that was
        // laxer than the store would let a pass pass its own tests and fail against Postgres. A
        // stage that reads records and carries none could settle nothing; a stage whose unit is not
        // a record reads none, so records on it would let its collection mark messages nothing in
        // it read. Asked of the stage for the reason `SubmissionStage.settlesRecords` gives — and
        // this is the copy that makes it matter, because a fake keyed on the one exception by name
        // would reject a new stage's submission before any test of it could run.
        val marks = stage.settlesRecords
        require(!marks || records.isNotEmpty()) {
            "A $stage submission selected for no records could settle nothing when it came back."
        }
        require(marks || records.isEmpty()) {
            "A $stage reads no record of its own, so carrying ${records.size} would let its " +
                "collection mark messages nothing in it read."
        }
        require(rows.none { (_, row) -> row.deriver == deriver && row.stage == stage && row.cost == null }) {
            "A $deriver $stage submission is already in flight. Two would leave nothing able to " +
                "say which of that stage's work had already been paid for."
        }
        require(id !in rows) { "Submission ${id.value} has already been recorded." }
        rows[id] = Row(deriver, stage, requests, records, selectedAt, submittedAt = clock, version, cost = null)
        order += id
    }

    override suspend fun inFlight(deriver: Deriver, stage: SubmissionStage): Submission? =
        order.map { it to rows.getValue(it) }
            .firstOrNull { (_, row) -> row.deriver == deriver && row.stage == stage && row.cost == null }
            ?.let { (id, row) -> row.copy(cost = null).asSubmission(id) }

    override suspend fun settle(id: SubmissionId, cost: TokenUsage) {
        val row = rows[id] ?: return
        rows[id] = row.copy(cost = cost)
    }

    /** Out of flight with nothing read off it, which is a collection costing nothing. */
    override suspend fun abandon(id: SubmissionId) {
        val row = rows[id] ?: return
        rows[id] = row.copy(cost = TokenUsage.NONE)
    }

    /**
     * Forget what [id] was submitted under, as a row written before a version was carried has.
     *
     * **The one state the store can hold and [record] cannot reach**, which is why it is a method
     * rather than an argument: every writer states a version, so the only way a submission comes to
     * name none is that it was already in flight when the column arrived. A caller has to survive
     * it — the port's type is nullable and the fallback is the behaviour of the build before this —
     * and without this the branch is unreachable from any test of a caller, because the callers are
     * `:domain` code driven only against this fake.
     */
    fun forgetVersionOf(id: SubmissionId) {
        rows[id] = rows.getValue(id).copy(version = null)
    }

    private fun Row.asSubmission(id: SubmissionId) =
        Submission(id, deriver, stage, requests, selectedAt, submittedAt, records, version, cost)

    private data class Row(
        val deriver: Deriver,
        val stage: SubmissionStage,
        val requests: List<SubmittedWindow>,
        val records: List<ExternalId>,
        val selectedAt: Instant,
        val submittedAt: Instant,
        /**
         * What the stage was running under when it submitted, or `null` where it named none.
         *
         * Nullable because the store's column is, and a caller reading it has a branch per answer.
         * [record] refuses a null, as every writer states a version; [forgetVersionOf] is the only
         * way to one, and it says why.
         */
        val version: String?,
        val cost: TokenUsage?,
    )
}
