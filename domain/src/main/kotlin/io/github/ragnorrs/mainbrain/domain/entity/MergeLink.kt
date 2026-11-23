package io.github.ragnorrs.mainbrain.domain.entity

/**
 * A decision that two contacts are one human, held as a link rather than as a rewrite.
 *
 * **Merging destroys nothing.** Both contacts keep their rows and their claims; this says that
 * [merged] now resolves to [canonical], and retrieval follows links to the head. Unmerging
 * removes the link and both sides are as they were, which is only possible because nothing was
 * fused in the first place. Internal identifiers never change, so nothing that already referred
 * to either side has to be found and rewritten.
 *
 * **[score] and [signals] travel with the link, not with either contact.** The uncertainty is in
 * the join and not in either human, so a contact assembled from four records has three links and
 * its reliability is the weakest of them — exposed rather than averaged into something that
 * looks better than any part of it. They are also what makes the decision reviewable: a wrong
 * join can be read back years later and undone on the evidence that carried it, rather than
 * guessed at.
 */
data class MergeLink(
    val merged: EntityId,
    val canonical: EntityId,
    val score: Double,
    val signals: List<Signal>,
) {
    init {
        require(merged != canonical) {
            "A contact cannot be merged into itself: the link's head would be its own tail, and " +
                "resolving it would not terminate."
        }
    }
}

/**
 * Two contacts that may not be merged, however strongly they score.
 *
 * Two things produce one, and only one of them is stored. **A hard negative** — two person
 * records that are simultaneously distinct members of one conversation — is computed from the
 * corpus on every pass, because the corpus is where it is visible and it cannot go stale. **A
 * correction** is stored, because nothing in the corpus records it: a human who unmerged a pair
 * has said something the evidence does not say, and a pass that only read the evidence would
 * merge them again on the next run. A correction outranks re-recognition and is never
 * overwritten.
 *
 * Constructed through [of], which orders the pair. The same separation discovered from either
 * side has to be one value, or the constraint that is supposed to forbid a merge is stored twice
 * and checked under whichever ordering the caller happened to hold.
 */
data class Separation(val one: EntityId, val other: EntityId) {
    init {
        require(one != other) { "A contact cannot be separated from itself." }
        require(one < other) {
            "A separation is stored with its pair ordered, so that one discovered from either " +
                "side is one value. Construct it through `Separation.of`."
        }
    }

    companion object {
        fun of(one: EntityId, other: EntityId): Separation =
            if (one < other) Separation(one, other) else Separation(other, one)
    }
}

/**
 * A pair that scored but did not reach the threshold, kept with what it scored.
 *
 * Kept rather than discarded, and not queued for anyone to approve — a queue of these would
 * never be worked. The corpus grows, so a pair scoring halfway today crosses the line the moment
 * a number appears on one side, and keeping the score is what makes that a re-evaluation of one
 * pair rather than a re-scoring of the whole corpus. Re-evaluation is triggered by a new claim
 * arriving for either record, not by every pass.
 *
 * Ordered like [Separation] and for the same reason: one pair, one row, whichever side it was
 * discovered from.
 */
data class CandidateMerge(
    val one: EntityId,
    val other: EntityId,
    val score: Double,
    val signals: List<Signal>,
) {
    init {
        require(one != other) { "A contact is not a candidate to merge with itself." }
        require(one < other) {
            "A candidate pair is stored with its pair ordered. Construct it through " +
                "`CandidateMerge.of`."
        }
    }

    companion object {
        fun of(one: EntityId, other: EntityId, score: Double, signals: List<Signal>): CandidateMerge =
            if (one < other) {
                CandidateMerge(one, other, score, signals)
            } else {
                CandidateMerge(other, one, score, signals)
            }
    }
}

/**
 * A kept pair as somebody sampling them reads it: the two contacts, named, and what they scored.
 *
 * [CandidateMerge] with the display names joined on, and the distinction is the point. That one is
 * what the pass writes — two identities and an assessment, which is all a pass has or needs. This
 * one is what a *reader* needs: a pair of identifiers tells nobody whether two accounts are one
 * human, so a surface for calibrating the threshold that returned only them would send its reader
 * to the store to look up every row by hand.
 *
 * **Advisory, and not a work queue.** Nothing waits on one of these being read, nothing is
 * discarded for not being reported, and reading one changes nothing. The architecture puts the
 * accuracy of the threshold in the evaluation set rather than in a constant, and sampling pairs is
 * how that is done — this is the read that makes it possible, not a step in the pass.
 *
 * A name is whatever a human typed into a profile field and may be absent, which is why both are
 * nullable: a pair of unnamed accounts can still score on their claims.
 */
data class UnresolvedPair(
    val pair: CandidateMerge,
    val oneName: String?,
    val otherName: String?,
)
