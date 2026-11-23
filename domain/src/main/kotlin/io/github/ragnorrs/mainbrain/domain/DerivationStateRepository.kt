package io.github.ragnorrs.mainbrain.domain

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant

/**
 * How far each derived pass has got, and against which version of itself.
 *
 * **One mechanism for every pass**, which is why this sits at the top of the domain rather
 * than inside `index` or `recognition`. The passes read the same corpus independently and are
 * invalidated by the same two things, so a watermark each would be as many sets of rules as there
 * are passes, all of which would have to agree.
 *
 * A record is *owed* to a pass when any of three things holds, and the distinction between
 * the last two is the whole point of keying on a version:
 *
 *  * nothing has derived it yet;
 *  * what derived it is not the current [Derivation.version] — a changed embedding model, a
 *    changed prompt, a changed chunker. This invalidates *exactly* what that version affects
 *    and leaves every other pass alone;
 *  * the record's content has moved since it was derived. Content time, not observation time:
 *    a resync that re-sees every record unchanged must not re-derive the corpus.
 *
 * **Owed is not the same as selectable**, and [pending] is the only read that tells them apart: a
 * record a submission of the pass has outstanding is owed — nothing has been derived from it until
 * the replies are read — but it is not offered again while it is being waited on. [owed] counts it
 * throughout, because a count that fell to zero with work outstanding would have a sweep report
 * itself caught up and never collect what it had paid for.
 *
 * Nothing here removes a mark. A superseded mark is overwritten by the next one at the new
 * version, so the table holds one row per record per pass rather than a history.
 */
interface DerivationStateRepository {

    /**
     * Up to [limit] records of [kind] that [derivation] still has to do, oldest first.
     *
     * Oldest by source time, so a run that is interrupted and restarted makes the same
     * progress rather than revisiting whatever the planner happened to pick.
     *
     * **The horizon is the caller's, and [notBefore] has no default on purpose.** `null` means no
     * horizon, which is the right answer for indexing — local compute, covering the corpus back to
     * the first backfilled record — and the ruinous one for inference, which spends model tokens
     * per window and would read 2019 before it reached anything recent. A default would make the
     * expensive answer what a caller gets by forgetting the argument, so every caller states which
     * it wants. The mechanism is here and the policy is not: nothing in this port knows what a
     * horizon is or how long one should be.
     *
     * A record with no source time is outside every horizon that is set, because nothing can say
     * whether it is recent; with no horizon set it is selected first, which is where the oldest
     * material belongs.
     *
     * **A record a submission of this pass has outstanding is not offered, though it is still
     * owed.** Nothing has been derived from it until the replies are read, so [owed] goes on
     * counting it — but offering it again would have the pass pay a second time to read what it is
     * already waiting on. The two states are apart for as long as a batch's turnaround lasts, which
     * is why this is the only read that tells them apart.
     */
    suspend fun pending(
        derivation: Derivation,
        kind: RecordKind,
        limit: Int,
        notBefore: Instant?,
    ): Pending

    /**
     * Record that [derivation] has done [records], as of [Pending.asOf].
     *
     * **[derivation] has to name the version that read the record, not the one the pass is
     * running under now**, and the two come apart for a pass that submits a batch: an
     * instruction reworded while the batch is with the provider is deployed before its answers
     * are written. Marked under the new version, the record claims a wording that never read
     * it and nothing revisits it — the only thing that retires a mark is the version it names
     * moving. Where the two versions differ is settled at the caller, because only it knows
     * what it submitted under; see [SubmissionRepository.record].
     *
     * **[asOf] has to be the moment the work was selected, not the moment it finished**, and
     * passing the wrong one silently loses an edit. The two rules are the same shape over the
     * two ways a mark goes stale: this one loses a reword, that one loses an edit. A pass
     * reads a record, the record is
     * written again while the pass is still working, and the pass then marks it: stamping the
     * finishing time would put the mark *after* the new content time, settling the record for
     * ever against a body that was never derived from. Stamping the selection time leaves it
     * pending, which is correct — it has changed since the pass looked at it.
     *
     * **This is not written in the same transaction as whatever the pass produced**, and no
     * caller can arrange that: a transaction spanning two repositories is not reachable from
     * outside the module that owns them. What stands in for it is ordering — a pass writes its
     * output first and marks afterwards, so a crash between the two re-derives a record rather
     * than skipping one. Marking first would leave records recorded as derived that were never
     * derived, which no re-run revisits.
     */
    suspend fun mark(derivation: Derivation, kind: RecordKind, records: List<ExternalId>, asOf: Instant)

    /**
     * How many records of [kind] [derivation] still owes work on — all of them, not a page.
     *
     * The same three ways of being owed that [pending] reads, and the same horizon, counted rather
     * than listed — but *not* narrowed by what a submission has outstanding, which [pending] excludes
     * and this deliberately counts. **The absence of a limit is the whole point of it**, and it is what a
     * bounded read cannot answer: `pending(…, limit = 128)` returns 128 whether the backlog is
     * 128 records or a million, so it says whether there is work and never how much. Two things
     * need the number itself.
     *
     * A driver needs it to tell progress from a stall. A pass whose batch settles nothing leaves
     * a bounded read saying exactly what it said before, so a loop watching one would either spin
     * for ever or stop on the first batch that wrote nothing; watching this, it sees the count
     * fail to fall and stops.
     *
     * A reader needs it because lag is the only thing that distinguishes a pass that has stopped
     * from a corpus with nothing new in it. Both look like no new spans appearing. One of them
     * has a count that climbs.
     *
     * It costs an aggregate over the record table joined against the watermark, and the driver asks
     * it once per batch — so a backfill drained in batches of 128 pays it a few thousand times
     * before it is caught up. That is deliberate and not an oversight: it is the only reading that
     * tells a stalled batch from a working one, and paying for it per batch is what stops a pass
     * re-reading and re-paying for the same windows for the whole of a sweep. Kept separate from
     * [pending] rather than folded into it so that the cost is asked for where it is wanted,
     * instead of every selection losing its limit.
     */
    suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int
}

/**
 * Work a pass still owes, and the instant the store selected it.
 *
 * The two travel together because the second is only meaningful for the first. [asOf] is read
 * from the store's own clock in the same query that chose [records], so it is the one moment
 * both halves of the staleness comparison are measured against — the pass's own clock never
 * enters it, and a skew between containers cannot settle a record that has moved.
 */
data class Pending(val records: List<ExternalId>, val asOf: Instant)

/**
 * A derived pass together with a version of itself.
 *
 * **Which version depends on what the caller is doing with it.** A selection or a count asks
 * under the one the pass is running now, because what it wants is the work that version still
 * owes. A [mark] names the one that actually read the record, which for a pass that submits a
 * batch is the version its submission was made under rather than the one deployed by the time
 * the reply comes back — see [SubmissionRepository.record].
 *
 * [version] is opaque to this port and compared only for equality. What has to be true is
 * that it changes whenever the pass's output would: for indexing it identifies the embedding
 * model, and anything else that would make two runs disagree belongs in it too. A change the
 * string does not capture is a change nothing re-derives.
 */
data class Derivation(val deriver: Deriver, val version: String) {
    init {
        require(version.isNotBlank()) {
            "A derivation version cannot be blank: it is what tells a stale mark from a current one."
        }
    }
}

/** The passes that read the corpus and make something of it. */
enum class Deriver {
    INDEX,
    RECOGNITION,

    /**
     * Judging which named conversations stand for a body of work.
     *
     * **Its own pass rather than a stage of [RECOGNITION], and the watermark is what forces it.** A
     * mark is one row per pass, record kind and record, with the version compared rather than keyed
     * on — so this and identity, both reading conversations, would overwrite one mark with each
     * other's version and both would re-derive every conversation on every sweep for ever, one of
     * them at the model's prices.
     */
    ADMISSION,
}
