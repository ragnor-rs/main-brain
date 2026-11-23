package io.github.ragnorrs.mainbrain.domain

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant

/**
 * What a pass remembers about the batches of model calls it has outstanding.
 *
 * **The one piece of a sweep's state that outlives the sweep.** A submission is answered later and
 * is billed whether or not anybody is still waiting for it, so an identifier held only in memory is
 * a charge nobody can collect against: a backend that restarts between submitting and collecting
 * has paid for work it can no longer read, and the next sweep submits the same windows and pays
 * twice. Those are the two failures this exists to prevent, and they are opposite — one loses what
 * was bought, the other buys it again.
 *
 * Deliberately not the watermark. [DerivationStateRepository] records which records a pass has
 * settled, and a submission settles nothing: the work is owed until the replies are read. Putting a
 * submission on the watermark's table would mean a mark that means "paid for but not yet known",
 * which is the one state that table is built not to have.
 */
interface SubmissionRepository {

    /**
     * Remember that [id] was submitted for [requests], before anything is read back.
     *
     * **Called between submitting and returning, and the order is the whole point.** Recorded
     * after the replies were read, a crash in between would lose the identifier and the money;
     * recorded before the submission was made, a crash would leave an identifier the provider never
     * minted and block the pass behind it. So this is what a caller does the instant it has an
     * identifier and nothing else.
     *
     * [kind] is what [records] are, and a submission covers one kind because a pass asks for work by
     * kind. It is kept because a record identifier is unique only within its kind — the sources key
     * different kinds alike — so without it a submission holding a message would read as holding the
     * like-named conversation. See [DerivationStateRepository.pending], which is what reads it.
     *
     * [version] is what the stage was running under when it composed this batch, and it is written
     * here because this is the last moment anything knows it — see [Submission.version].
     */
    // Past the parameter cap, and every one of them is something the row or one of its two child
    // tables has to carry. A parameter object here would be a type whose only reader is this call,
    // and the mistake it would protect against — two values of one type swapped at a call site — is
    // not reachable, because no two of these share a type.
    @Suppress("LongParameterList")
    suspend fun record(
        deriver: Deriver,
        stage: SubmissionStage,
        kind: RecordKind,
        id: SubmissionId,
        requests: List<SubmittedWindow>,
        records: List<ExternalId>,
        selectedAt: Instant,
        version: String,
    )

    /**
     * The submission this stage of this pass has outstanding, or `null` when it has none.
     *
     * At most one per stage, which the store enforces rather than trusts: a stage with two in flight
     * could not say which of its own work it had already paid for, and would pay for it again. So a
     * stage collects what it last submitted before it submits anything itself.
     *
     * **Per stage and not per pass, because a pass's stages cannot share a slot.** A restatement
     * asks about what a classification found, so those two can only ever be submitted a collection
     * apart — and a pass allowed one submission between them could not have the second outstanding
     * at all. The adjudication stage depends on neither and would be blocked behind whichever held
     * the slot. What the rule guards against is a stage unable to account for its own
     * spend, which two stages holding one submission each never are: a reply is matched by the key it
     * was submitted under, and the stages do not share keys.
     */
    suspend fun inFlight(deriver: Deriver, stage: SubmissionStage): Submission?

    /**
     * Record that [id] has been read back, and what the provider billed for it.
     *
     * The cost is written here rather than added to a sweep's running total because the sweep that
     * pays is not the sweep that collects. A figure folded into the collector's own spend would
     * charge one sweep for what another committed, and the ceiling would stop being a bound on what
     * a sweep decides to do.
     */
    suspend fun settle(id: SubmissionId, cost: TokenUsage)

    /**
     * Give up on [id] without having read it, recording that nothing was learned from it.
     *
     * **The only other way out of being in flight, and the pass cannot run without one.** A
     * submission the provider will never answer — one whose results have passed their retention,
     * one cancelled elsewhere, one stuck in a state that never ends — would otherwise hold the
     * stage's in-flight slot for ever, and nothing in this build could clear it but an operator
     * editing the table. The work it covered stays owed, so it is read again under a new
     * submission; what is given up is the money, which was already spent.
     */
    suspend fun abandon(id: SubmissionId)
}

/**
 * Which ask of a pass a submission was made for.
 *
 * A pass that goes to the provider more than once per unit of work has a stage per ask, and the
 * stages are independent in the only sense the store cares about: each accounts for its own spend
 * and holds its own in-flight slot. The names are the asks themselves rather than numbers, because
 * what a reader of the table wants to know is which call cost this, not which came first.
 */
enum class SubmissionStage(
    /**
     * Whether a collection of this stage settles the records it was selected for.
     *
     * **Declared per stage rather than derived from a list of exceptions, and not defaulted.** The
     * store refuses a submission whose records disagree with this in both directions, and the rule
     * used to be written there as *not the adjudication stage*. A second stage that reads no record
     * then arrives and an exclusion by name admits it in silence: the submission carries none, the
     * check that would have caught it is the one written for the stages that do, and the collection
     * marks nothing while reporting that it settled. Stated here, a stage added without deciding
     * does not compile.
     */
    val settlesRecords: Boolean,
) {

    /** What one message states — the ask that runs over every window and finds the candidates. */
    CLASSIFICATION(settlesRecords = true),

    /** One finding rewritten to stand on its own, away from the conversation it was said in. */
    RESTATEMENT(settlesRecords = true),

    /**
     * Whether two readings already written state one fact — the last tier of the collapse.
     *
     * What it compares is either two findings, whose records were settled by the restatement that
     * wrote them, or two values of one subject and predicate, which are not rows at all — a
     * statement is its own content, so what is decided there is whether two wordings mean one
     * thing. Either way nothing a collection of this stage does can mark anything, and the figures
     * the other stages keep for marking are empty here by construction rather than by omission.
     */
    ADJUDICATION(settlesRecords = false),

    /**
     * What closed a commitment, read out of the material that followed it in its own conversation.
     *
     * **Its unit is a finding and not a record**, which is why it settles none. What it examines is
     * one commitment against the messages after it; the messages were settled by the classification
     * that read them, and the commitment is an entity this build minted rather than a record a
     * source gave it. What it writes is a verdict keyed by commitment, carrying its own version, so
     * the watermark is that row rather than a mark in the derivation table — see
     * `ClosureRepository`.
     */
    CLOSURE(settlesRecords = false),
}

/**
 * One request of a submission, as what it was asking about.
 *
 * **A window-shaped request carries no messages, with one exception.** A window is named by the one
 * message it reads, so that identifier and the conversation are the whole of what finding it again
 * needs, and storing the messages would be a second copy of the corpus that could disagree with it.
 * The closure stage is the exception and for the opposite reason: what it asks about is a stretch
 * that *grows* while the submission is outstanding, so a window re-read on collection would hold
 * messages the model never saw and a reply could name one of them. There the second copy is the
 * point — it is the record of what was actually sent.
 *
 * @property key what the request was submitted under and what the provider echoes back.
 * @property conversation which conversation to re-read to find the window again. `null` for a
 *   request that is about no window — the adjudication stage compares two things already written,
 *   and re-reads them by what identifies them rather than by reading the corpus again.
 * @property payload what this request is about, where the key cannot say. For a restatement it is
 *   the classification line the candidate was read from; for an adjudication, the pair; for a
 *   closure, the window it weighed, which is the one case where the messages themselves are stored
 *   and the paragraph above says why. `null` for a request whose key is the whole of what finding
 *   its work again needs.
 */
data class SubmittedWindow(
    val key: String,
    val conversation: ExternalId? = null,
    val payload: String? = null,
) {
    init {
        require(key.isNotBlank()) { "A blank key cannot be matched back to the work that asked for it." }
        require(payload == null || payload.isNotBlank()) {
            "A blank payload says a request was about something beyond its key without saying what."
        }
    }

    /**
     * The conversation this request reads a window in, or `null` where it names none.
     *
     * **Absent rather than refused, which is the rule this build already follows for a stored row
     * it cannot read.** A reading stage writes the key and the conversation together, so one
     * without the other is a submission made by a build that keyed them differently — and the
     * settled answer to that is the record stays *owed*: a throw here would leave the submission in
     * flight, be re-collected on the next sweep and throw again, with nothing in this build able to
     * clear it. That is strictly worse than the finding being lost, because it stops the stage
     * rather than one request.
     *
     * Named as a method rather than read off the property so the two uses read differently: a
     * reading stage asks for the window it was promised, and gets nothing where another build
     * promised something else.
     */
    fun windowIn(): ExternalId? = conversation
}

/**
 * A submission as it is remembered: what it is called, and what it was asking about.
 *
 * [cost] is `null` while the submission is in flight, which is the ordinary case for the one a
 * sweep is about to collect.
 */
data class Submission(
    val id: SubmissionId,
    val deriver: Deriver,
    val stage: SubmissionStage,
    val requests: List<SubmittedWindow>,
    /**
     * The cut-off the records were selected against when this was submitted.
     *
     * **Carried rather than re-read on collection, and that is the point of keeping it.** A
     * submission is allowed most of a day, so a message can be edited between being classified and
     * being marked. Marked against the collection's own clock the mark would look current and the
     * classification of text that no longer exists would stand for good; marked against this, the
     * edited message's content is newer than its mark and it comes back owed.
     */
    val selectedAt: Instant,
    /**
     * When this was handed to the provider.
     *
     * Read so that a submission which will never be answered can be given up — see
     * [SubmissionRepository.abandon]. Without it, "in flight" and "in flight for ever" are the same
     * state and the pass has no way to tell them apart.
     */
    val submittedAt: Instant,
    /**
     * The records this submission's collection may settle.
     *
     * **It cannot be recomputed at collection time, which is the point of keeping it.** Selection
     * is oldest-first and bounded, and its answer moves as the horizon advances or as ingestion
     * lands older material — so a collection that re-ran it would get a different page, and the
     * records actually paid for would fall outside it, stay owed, and be paid for a second time.
     *
     * A window reaches outside the recency horizon for the context it needs, and nothing is read out
     * of what it finds there: a finding belongs to the one message its window was built for. So for
     * recognition's first ask this set and the records its requests name are the same, and keeping
     * it is about the clock rather than about the difference.
     *
     * **What narrowing has already been applied depends on the stage.** For the first ask of a pass
     * this is what selection returned, and the collection intersects it with what the replies turned
     * out to cover. For a later ask it is that intersection, computed when the earlier ask was read
     * and carried forward. Either way it is the most that collection may settle rather than what it
     * will: a reply that never came holds its own records back out of this, so that nothing is
     * marked against a finding nobody read.
     */
    val records: List<ExternalId>,
    /**
     * The version of itself the stage was running under when it handed this over.
     *
     * **What a collection writes its output under, in place of the version current when it runs.**
     * A batch is allowed an hour and may take most of a day, and a reword arrives by deploy — so
     * the two coincide whenever a prompt card is deployed while a pass has work with the provider,
     * which is the ordinary state of a sweeping deployment. Read at collection, the version would
     * stamp the retired wording's answers as current, and nothing could tell them from output the
     * new wording produced: a mark claims to be up to date, a verdict claims to be the standing
     * one, and the only thing that would retire either is the version each is now wrongly naming.
     * Carried here, those answers are written under the wording that produced them and are owed
     * again the moment they are read back.
     *
     * **`null` means the version current at collection**, which is what every stage did before this
     * was carried and is therefore the reading that changes nothing. Only a submission that was
     * already in flight when the column arrived has none — every writer states one — so the
     * fallback is a migration's one-off rather than a case a sweep meets twice.
     *
     * A string rather than a [Derivation], because the stages do not agree on what a version keys:
     * a reading stage puts its own on a record's mark, while a stage that marks no record carries
     * its own on the row it writes. What they share is that it is opaque and compared for equality, which is all this
     * has to preserve.
     */
    val version: String?,
    val cost: TokenUsage? = null,
)
