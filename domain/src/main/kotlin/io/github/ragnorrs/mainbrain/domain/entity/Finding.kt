package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.time.Duration
import java.time.Instant

/**
 * What a commitment and an event have in common: a claim, the conversation that scopes it, and the
 * spans it was read out of.
 *
 * **It exists so that the collapse is written once.** Deciding whether two readings are one fact is
 * the same question for both kinds — the same key, the same comparison, the same merge — and a
 * version of it per type would be two rules free to drift. What differs between the two is what is
 * asked *of* them later, which is why they stay separate types rather than one with a discriminator.
 *
 * **A statement is deliberately not one of these.** It is keyed by its subject and predicate with
 * the claim as the value, so two readings of it contradict rather than duplicate — which
 * [io.github.ragnorrs.mainbrain.domain.recognition.resolved] already decides, by closing a window rather
 * than merging a row. Pulling it in here would mean one interface over two different notions of
 * sameness.
 */
interface Finding {

    val id: EntityId

    /** The claim, restated to stand on its own, and what this is keyed by within [conversation]. */
    val claim: String

    /** The conversation it was read in, which scopes the claim. */
    val conversation: ExternalId

    /** Every span that states it, which is one until the same fact is read again. */
    val evidence: Set<Evidence>
}

/**
 * When a finding was first stated, which is the earliest span that evidences it.
 *
 * Derived rather than stored on the interface: a restatement adds evidence without making the fact
 * newer than the moment it was first said, so there is one definition of this and it is the
 * evidence. It is what orders two readings of one claim, and so what decides which of them survives
 * a merge.
 */
val Finding.firstStatedAt: Instant get() = evidence.firstStated.sourceTime

/**
 * Which of the two derived kinds a finding is.
 *
 * Carried beside a finding rather than read off its type, because the collapse reaches the store
 * through one port and a store write has to name the table. It is the two kinds the collapse
 * compares and not the three the extractor classifies: an attribute becomes a statement, which
 * resolves by contradiction instead.
 */
enum class FindingKind {
    COMMITMENT,
    EVENT,
}

/**
 * Two readings in one conversation that might be one fact, before anything has decided whether they
 * are.
 *
 * **Ordered, and the order is what survives.** [survivor] is the earlier-stated of the two with the
 * identifier breaking a tie, which is the rule the migration that introduced the content key used
 * to merge what was already stored — so a pair reached twice resolves the same way, and a merge
 * never depends on which row a query returned first. The earlier reading is kept because a
 * restatement is usually the second-hand one and the hedge marker says so.
 */
data class FindingPair(val kind: FindingKind, val survivor: Finding, val superseded: Finding) {
    init {
        require(survivor.id != superseded.id) {
            "A finding is not a duplicate of itself, and merging it onto itself would supersede the survivor."
        }
        require(survivor.conversation == superseded.conversation) {
            "Two findings from different conversations are not known to be one fact: a claim names " +
                "its party only where the corpus names that person, so the conversation is part of the key."
        }
    }

    companion object {

        /**
         * [one] and [other] as a pair, earliest first.
         *
         * The tie is broken on the identifier rather than left to the caller, so that two callers
         * handed the same two findings build the same pair.
         */
        fun of(kind: FindingKind, one: Finding, other: Finding): FindingPair {
            // On [EntityId] and never on the [java.util.UUID] inside it: that type orders itself
            // the way the store does, which is not the way Java orders a UUID, and this order is
            // compared against a stored one.
            val keepOne = compareValuesBy(one, other, { it.firstStatedAt }, { it.id }) <= 0
            return if (keepOne) {
                FindingPair(kind, survivor = one, superseded = other)
            } else {
                FindingPair(kind, survivor = other, superseded = one)
            }
        }
    }
}

/**
 * The store side of collapsing two readings of one fact into one.
 *
 * **One port over both kinds, for the reason [Finding] is one interface.** The alternative is the
 * same methods on [CommitmentRepository] and [EventRepository], differing only in which table
 * they name — and a collapse that had to be written twice is a rule that can hold for commitments
 * and not for events without anything failing.
 *
 * Nothing here deletes. A merge leaves both rows in place and has one name the other, which is the
 * shape a merged person record already has: the superseded row keeps everything it had — its spans
 * and any verdict about it — the survivor carries a copy, and clearing the column puts the readings
 * back. What clearing it does not restore is a verdict the copy replaced, which is re-derivable by
 * definition: it said nothing closed the promise.
 */
interface CollapseRepository {

    /**
     * Up to [limit] pairs of survivors that might be one fact, by the only test SQL can make.
     *
     * **This is the cheap filter and not the comparison.** What it returns is every pair in one
     * conversation and kind whose first statements are no more than [within] apart — which is a
     * great many pairs that merely sit near each other in a chat, and is the whole set the real
     * comparison has to be given. Narrowing it further here would mean deciding sameness in SQL.
     *
     * **A pair already decided is not returned**, and both tiers decide. [keepApart] records what
     * the model was asked, which keeps a pass from paying to adjudicate the same two sentences on
     * every sweep for the rest of the deployment; it also records what the *vectors* ruled out.
     *
     * **Each answer is excluded only under the version that gave it**, which is why there are two
     * of them here: a cheap rule-out names the vector version — [comparedBy] — and a paid
     * verdict names the adjudication version — [adjudicatedBy]. So a better embedding model or a
     * moved similarity bound retires the cheap judgements, a reworded comparison retires the paid
     * ones, and neither version moving costs the other's answers anything *that this query honours*
     * — the column is one, and a pair whose verdict has been retired is decided afresh by whichever
     * tier reaches it, which may not be the tier that decided it before.
     * A verdict whose version is absent from both is one no deployed stage would reach again, so the
     * pair comes back to be decided by the stage that is here now.
     *
     * **Each version covers everything that decides its own tier's answer**, which is the strength
     * the rule needs to be worth stating: the paid tier's names its instruction, model and effort,
     * and the cheap tier's names the embedding version together with every similarity bound that
     * reads its vectors. A bound outside it would be a behavioural change that invalidated nothing —
     * see `vectorsVersion`, which is where the cheap value is built and why. The statement side of
     * the collapse is over-covered by it, for the reason `StatementRepository.decide` states.
     *
     * **A cheap answer has to be written down too, and the reason is this query's own shape.** It is
     * oldest-first over a bound, so a pair nothing records comes back at the head of the queue on
     * every sweep and the pairs behind it are never reached at all — the stage would compare the
     * same page for ever and read like one that was keeping up.
     *
     * Only survivors, on both sides. A superseded reading must not be compared against anything —
     * it is already part of another finding — and offering one would merge a row onto a row that
     * had itself been merged away.
     */
    suspend fun candidates(
        limit: Int,
        within: Duration,
        comparedBy: String,
        adjudicatedBy: String,
    ): List<FindingPair>

    /**
     * How many candidate pairs there are in total, rather than a page of them.
     *
     * **The driver reads a fall in this as progress, so it has to be the whole count.** A bounded
     * read says whether there is work and never how much, and a stage whose count never moved would
     * be reported as stalled on every sweep it did something cheap.
     *
     * It falls for both the ways a pair leaves the set: a merge supersedes one side, and a verdict
     * is recorded against the version that gave it — [comparedBy] or [adjudicatedBy], as
     * [candidates] separates them. That is the second reason the cheap tier writes its answers
     * down — the first being that an oldest-first page would otherwise return the same pairs for
     * ever.
     *
     * **The same two versions as [candidates] and for the one reason that matters here**: counted
     * under a condition the selection does not share, the driver would read a stall where there was
     * progress, or report the stage caught up with pairs it was about to be handed.
     */
    suspend fun candidateCount(within: Duration, comparedBy: String, adjudicatedBy: String): Int

    /**
     * [pair] as the store holds it now, or `null` where it is no longer a pair to decide about.
     *
     * **Re-read rather than carried across the gap, because an adjudication is answered a sweep or
     * more later.** Either side may have been merged away in between — by the cheap tier, or by
     * another pair that reached it first — and merging onto a superseded row would undo a collapse
     * that had already happened. `null` says so, and the reply that was paid for is counted rather
     * than applied.
     *
     * Identified by the two identifiers rather than by the claims, which is what makes this stable:
     * an identifier is permanent and assigned once, while a claim is rewritten in place by a
     * re-derivation under a better prompt.
     *
     * **The order the two arrive in carries nothing**, and the pair that comes back is ordered by
     * [FindingPair.of] from what the store now holds. Evidence accumulates, so which of two readings
     * was stated first can move between a submission and its collection — and a collection that
     * trusted the submitted order could merge the later reading onto the earlier one.
     */
    suspend fun pair(kind: FindingKind, one: EntityId, other: EntityId): FindingPair?

    /**
     * Merge [pair]: the survivor carries what the other held, and the other names it.
     *
     * **What travels is the evidence and, for a commitment, a closure that was found.** A verdict
     * left behind is one no read reaches, so the survivor would be examined from scratch — and the
     * closure pass is costed on a closure never being bought twice. An event has none to carry.
     *
     * **Which of two verdicts a merge keeps is a judgement, and it is this one.** A found closure
     * outranks a verdict that found none, whichever row holds it: the two readings are of one
     * obligation in one conversation, so an examination that caught a closing message the other
     * missed is new evidence rather than a contradiction. Where both found one, the survivor's own
     * stands, because its claim is the one every read returns. A verdict that found nothing is not
     * carried at all, having nothing to settle that the survivor would not read again. The carried
     * closure is recorded against the survivor's own watermark, since a mark claims everything
     * before it was weighed and the superseded reading's window began later than the survivor's.
     *
     * It is a copy, so one of those outcomes replaces a row: a survivor's verdict that found
     * nothing. That is the one thing a merge does not leave behind it, and the pass reaches that
     * answer again by examining.
     *
     * Idempotent, because the collection that applies it cannot be sure it has not already run: a
     * failure between this and the settle that follows re-collects the same replies, and a merge
     * that threw on a row already superseded would leave the submission uncollectable for good.
     */
    suspend fun merge(pair: FindingPair)

    /**
     * Record that these two are to be kept apart, so that nothing compares them again.
     *
     * [decidedBy] is the version of whichever tier answered: the adjudication version where the
     * model was paid for a verdict, the vector version where the vectors ruled the pair out.
     * **Neither is permanent, and both are retired by their own version moving** — one rule over
     * both tiers rather than one each, because a paid answer stands as long as the thing that gave
     * it stands, which is all that made it worth paying for. Each version covers everything that
     * decides its own tier's answer: for the paid tier the instruction, the model and the effort,
     * and for the cheap tier the embedding version together with every similarity bound that reads
     * its vectors. So the rule is the same shape and the same strength on both — see [candidates],
     * which is where the exclusion is made, and `vectorsVersion`, which builds the cheap value.
     *
     * Both are recorded, and the pair would come back on every sweep without it. The pair is stored
     * in the identifiers' own order whichever order it arrives in, so one unordered pair is one row
     * — which matters because evidence accumulates and so which of the two was stated first can
     * move. A pair ruled out cheaply and later asked about is one row either way: the second answer
     * replaces the first.
     *
     * **Kept apart rather than judged different, because three answers land here and only one of
     * them is a judgement.** The model saying they are different facts is the ordinary paid case. A
     * refusal is the second, and it has to be recorded for the reason a declined window is marked: a
     * refusal is deterministic, so the same pair would be submitted and refused on every idle sweep
     * for ever, at a call each. The vectors putting them apart is the third. None asserts that the
     * claims have been read and understood.
     *
     * What this cannot survive is a claim being rewritten underneath it: a re-derivation under a
     * better *extraction* prompt rewords both rows and the record stands anyway. That is a known
     * limit rather than a guarded case — the alternative keys the row on text that is expected to
     * change, which would retire every verdict the pass has ever bought whenever the reading stages
     * moved, and those are the stages whose wording this comparison says nothing about. What
     * [decidedBy] retires it on instead is the one thing that does change the answer.
     */
    suspend fun keepApart(kind: FindingKind, one: EntityId, other: EntityId, decidedBy: String)
}
