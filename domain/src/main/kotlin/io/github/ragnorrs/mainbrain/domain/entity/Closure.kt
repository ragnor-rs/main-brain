package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import java.time.Duration
import java.time.Instant

/**
 * What closed a commitment, or that the corpus holds nothing that does.
 *
 * **The absent case is a verdict and not a missing value, which is the whole shape of this type.**
 * A promise is often kept in silence, so a commitment with nothing closing it is the ordinary
 * outcome rather than an examination that failed — and it has to be written down, because without
 * it every sweep pays the model to reach the same answer over the same material. `finding_kept_apart`
 * records a negative one tier over for the same reason.
 *
 * **What it is never is a claim that the commitment is open.** [closedBy] absent means no message
 * this pass has read closes it. A promise met in silence produces no closing message at all, so no
 * amount of model spend tells *still owed* from *quietly done*, and nothing downstream may read the
 * absence as the first of those. That is the ceiling stated in the card this was built for, and
 * `Retrieval.commitmentsOf` and the shared reply format are where it is said out loud — the
 * latter to every shape that answers, since a promise read out of a message is as unestablishable
 * as one read off a label.
 *
 * @property commitment which commitment this is a verdict about.
 * @property closedBy the message that closed it, as evidence, so it is citable like anything else
 *   the corpus states. Absent where the examination found none.
 * @property weighed the last message this verdict read. The watermark: material after it has not
 *   been read, so a verdict of nothing-closed is good only this far.
 *
 *   **A message and not a moment, though a moment is what a reader thinks of.** It is a message
 *   instant rather than a wall clock because ingestion backfills, so the corpus grows into the past
 *   as well as the future and a verdict keyed on when the pass ran would never revisit a window of
 *   history that arrived afterwards. But the instant alone cannot be the mark: a source's instants
 *   are coarse — several messages of one conversation routinely share a second — and the window is
 *   read in message order, so resuming on the instant alone steps over whatever else shared the
 *   last one. Those messages would then be weighed by no sweep ever, the closing message among
 *   them. The pair resumes exactly where the reading stopped.
 * @property decidedBy the closure version that reached it, carrying the model and the prompt. What
 *   retires a verdict when either moves, which is the job a derivation mark does for the passes that
 *   read corpus records — see [ClosureRepository] for why this pass cannot use one.
 */
data class Closure(
    val commitment: EntityId,
    val closedBy: Evidence?,
    val weighed: Weighed,
    val decidedBy: String,
) {
    init {
        require(decidedBy.isNotBlank()) {
            "A closure with no version recorded could never be retired by a better prompt."
        }
    }
}

/**
 * How far a verdict read, as the message it stopped at.
 *
 * The pair the window's own ordering needs: a conversation is read by instant and then by the
 * record's own identifier, so a mark carrying only the instant cannot say which of a second's
 * messages was the last one weighed.
 */
data class Weighed(val at: Instant, val record: ExternalId)

/**
 * Commitments owed a closure examination, and the verdicts reached about them.
 *
 * **The verdict row is this pass's watermark, and that is the decision this port exists to carry.**
 * `DerivationStateRepository` is the mechanism every other derived pass uses, and it cannot serve
 * here: it keys a record by source and native identifier and calls one owed when it has no mark,
 * when the version moved, or when its content time passed the mark. A commitment is an entity this
 * build minted rather than a record a source gave it — no source, no native identifier, no content
 * time — and the thing that makes a closure owed again is none of those three. It is that the
 * commitment's own conversation holds material no verdict has weighed. So [Closure.decidedBy]
 * answers staleness and [Closure.weighed] answers the rest, and [owed] is where the two meet.
 */
interface ClosureRepository {

    /**
     * Up to [commitments] commitments still owed an examination at [version], oldest first.
     *
     * A commitment is owed when it has no verdict, when its verdict was reached by another version,
     * or when its conversation holds material the verdict did not weigh — at least [rearm] of it
     * while the horizon is still open, and any at all once it has closed. The last of those applies
     * only to a verdict that closed nothing: a closure already found is not paid for again, which
     * is what keeps a settled promise settled. **A merge does not undo that**, because the collapse
     * carries a found closure onto the survivor — see [CollapseRepository.merge], which states
     * which of two verdicts it keeps and what it does with the watermark.
     *
     * **[rearm] is the floor on what re-arms a commitment, and it is a count of messages because
     * that is the shape of the cost.** An examination is billed for its instruction and its claim
     * whether it weighs one new message or twenty, so without a floor a conversation trickling in a
     * message at a time buys a near-empty call per message — the overhead is most of what such a
     * call costs. With one, the cost of re-examining scales with how much new material there is to
     * weigh, which is the property this pass is supposed to have.
     *
     * **It defers a re-examination and never drops one, and that is why [now] is taken.** The floor
     * alone would be unsafe for a reason that has nothing to do with a trickle: [window] bounds one
     * examination, so a conversation with more material than fits is read in instalments and the
     * last instalment is the remainder — under the floor one time in every [window]. Gated on the
     * floor alone, that remainder would be weighed by no sweep ever, which is the outcome
     * [Closure.weighed] is an ordered pair to prevent, and the closing message could be in it. So
     * the floor holds only while more material may still arrive. Once [now] is past the horizon
     * measured from the promise nothing more can, and whatever is left is read in one last
     * examination and settled. What the floor costs is therefore latency and never a finding.
     *
     * Neither bound applies to a commitment nothing has examined, nor to one whose version moved.
     * Those are read regardless of how little followed the promise — the first examination is what
     * produces a verdict at all, and a floor on it would leave a quiet conversation with none.
     *
     * **[examine] is a closed horizon per commitment and it is what bounds the spending.** Without
     * it an unclosed promise is re-examined for as long as its conversation goes on growing, which
     * on a live source is for ever. With it, the pass reads forward from the commitment only as far
     * as this, and once the conversation has been weighed that far the commitment is settled for
     * good — closed or not. It is configuration rather than a constant here for the reason the
     * recency horizon is: what it should be is a deployment's judgement about how long after a
     * promise its closure is still worth paying to find.
     *
     * **[window] bounds one examination and not the whole horizon**, so a commitment with more
     * material than fits walks forward over several sweeps rather than arriving in one unaffordable
     * prompt. It is a count of messages and not a span of time, because what a prompt holds is
     * messages rather than hours: a busy thread puts hundreds of them into a week and a quiet one
     * puts none, so a bound shaped like a span would send windows of unpredictable size to a stage
     * costed per call.
     *
     * Oldest first, by when the commitment was stated, so an interrupted run makes the same
     * progress rather than revisiting whatever the store happened to return.
     */
    @Suppress("LongParameterList")
    suspend fun owed(
        version: String,
        commitments: Int,
        window: Int,
        examine: Duration,
        rearm: Int,
        now: Instant,
    ): List<ClosureCandidate>

    /**
     * How many commitments are owed an examination at [version] — all of them, not a page.
     *
     * The same definition [owed] selects by, counted rather than listed, for the reason
     * `DerivationStateRepository.owed` gives: a bounded read says whether there is work and never
     * how much, and the driver reads a fall in this as progress. A count standing apart from the
     * selection would stop a pass with work outstanding or never let one finish. **Which is why
     * [rearm] and [now] are taken here too**: a count that read the floor differently would report
     * commitments the selection then declined to return, and the driver would read a stage that
     * never drained.
     */
    suspend fun owedCount(version: String, examine: Duration, rearm: Int, now: Instant): Int

    /**
     * Write [closures], replacing any verdict those commitments already had.
     *
     * In place, because a history of verdicts is a second thing to read and nothing reads it: what
     * the last sweep concluded before more of the conversation arrived is not interesting once it
     * has been revised. **No commitment row is touched** — a closure is a link beside the finding,
     * so removing these rows restores the prior reading exactly.
     */
    suspend fun record(closures: List<Closure>)

    // No read of a verdict here, deliberately. The answer path reads closures through
    // `Retrieval.closuresOf`, which returns the closing message with them because that is what a
    // reader has to be shown; a second read on this port would be production code with no caller.
}

/**
 * One commitment and the stretch of its conversation this examination is to read.
 *
 * **The window comes from the store with the commitment rather than being fetched per candidate**,
 * because the bound that makes it affordable is a `LIMIT` and belongs in the query that knows what
 * has already been weighed. A pass reading the conversation itself would either read it whole — a
 * thread is unbounded and a prompt is not — or re-derive the exclusion the store already applied.
 *
 * [later] is in conversation order and holds only messages with something to read: a sticker or a
 * service notice closes nothing, and a window of them would be a call billed for the instruction
 * and the claim with no material in it. Empty is possible and means the material inside the horizon
 * has been weighed; such a candidate is not asked about and is settled where it stands.
 */
data class ClosureCandidate(
    val commitment: Commitment,
    val later: List<Message>,
    /**
     * How far the verdict this replaces had read, where there was one.
     *
     * Carried so that a new verdict cannot claim to have weighed less than its predecessor did —
     * see [weighed]. `null` for a commitment nothing has examined.
     */
    val previously: Weighed? = null,
) {
    /**
     * The last message this examination will have weighed.
     *
     * **Computed from what was sent, and never allowed to go backwards.** Taking it from the window
     * alone is what makes it honest — a mark read off a clock, or off the horizon's end, would
     * settle material the model never saw. But a window can also be *empty* while a previous
     * verdict read a long way, which happens when a moved version re-opens a commitment whose
     * material has since been withdrawn; writing the promise's own instant there would hand back
     * ground the pass had already covered and buy the whole window again on the next sweep.
     *
     * With nothing weighed and nothing read before, the promise's own message stands: it is the one
     * span a commitment is guaranteed to have, and the window is everything after it.
     */
    val weighed: Weighed
        get() {
            val stated = commitment.evidence.firstStated
            val last = later.maxByOrNull { it.sourceTime }
            val here = last?.let { Weighed(it.sourceTime, it.externalId) }
                ?: Weighed(stated.sourceTime, stated.span.record)
            return listOfNotNull(here, previously).maxBy { it.at }
        }
}
