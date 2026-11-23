package io.github.ragnorrs.mainbrain.contract

import kotlinx.serialization.Serializable

/**
 * The advisory report of contact pairs that scored without being merged.
 *
 * **Not part of what the interface asks for**, unlike everything in `Api.kt`. The bot never reads
 * this: the reader is whoever is placing the merge threshold, which the architecture makes the
 * evaluation set's work rather than an interface's. It is here because this is the module that
 * carries the backend's wire format — all of it, not the answer exchange alone — and a type served
 * over HTTP belongs where the serialising is done.
 *
 * **Read-only and advisory, which is a property of the surface and not a disclaimer on it.** Nothing
 * waits on this being read, no pass blocks on it, and a pair is kept whether or not it ever appears
 * here. There is deliberately no shape for acting on one: approving pairs from a list is the review
 * queue the architecture rejects twice over, on the grounds that nobody would work it. A correction
 * is made where corrections are made.
 *
 * [floor] and [limit] come back with the rows because a sample means nothing without the rule that
 * drew it: a reader who does not know what was excluded cannot tell a quiet corpus from a tight
 * floor. [more] says the limit truncated the result, so a caller can tell *that is all of them*
 * from *that is as many as you asked for* — the distinction a silent cap destroys.
 */
@Serializable
data class UnresolvedReport(
    val floor: Double,
    val limit: Int,
    val more: Boolean,
    val pairs: List<UnresolvedPairView> = emptyList(),
)

/**
 * One kept pair, as a reader judging it needs to see it.
 *
 * The names are what make a pair judgeable and the scores are what make it calibratable. A pair of
 * identifiers tells nobody whether two accounts are one human, so a surface that returned only
 * those would send its reader to the store to look up every row by hand.
 *
 * The identifiers are strings because an entity identifier is this build's own bookkeeping and
 * opaque to everything outside it — and because this module may not reach the domain to name the
 * type properly.
 *
 * [signals] carries each agreement and its weight rather than the total alone, because the total is
 * the thing in question: a pair at half the threshold on one rare name and a pair at half of it on
 * two weak agreements are different cases, and somebody placing the threshold has to tell them
 * apart.
 *
 * A display name is whatever a human typed into a profile field and may be absent, which is why
 * both are nullable — a pair of unnamed accounts can still score on their claims.
 */
@Serializable
data class UnresolvedPairView(
    val one: String,
    val other: String,
    val score: Double,
    val signals: List<ScoredSignal> = emptyList(),
    val oneName: String? = null,
    val otherName: String? = null,
)

/** One agreement and what it was worth, named as the matcher names it. */
@Serializable
data class ScoredSignal(val kind: String, val weight: Double)
