package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.TokenUsage

/**
 * What one request may still spend on the model.
 *
 * **A per-request bound, which is not a thing any single stage can enforce.** Each shape sizes
 * its own call, and a ceiling sized to one call is the only thing a shape is in a position to
 * know. Two multipliers sit outside that knowledge: the adapter resumes a turn that pauses, and
 * each resumption is a fresh request at the same ceiling; and the runner retries once when
 * generation comes back ungrounded. So the worst case for one request is several times any
 * shape's number, and the shape cannot see either multiplier.
 *
 * **Immutable, and carried in the exchange, because the alternative is a counter.** A mutable
 * budget held by a stage would be shared across every request that stage serves — one instance
 * serves them all, concurrently — so the bound would be global and the requests would eat each
 * other's. Threading it through the exchange makes it per-request by construction, which is the
 * one property it has to have.
 *
 * **Enforced where [remaining] is read rather than by a check after the fact.** A generation
 * stage cannot build a [io.github.ragnorrs.mainbrain.domain.ModelRequest] without a ceiling, so a stage
 * that asks this for one is bounded whether or not it remembered to be; and a check the runner
 * ran afterwards could only report a budget already spent.
 *
 * @property ceiling the most this request may spend across every call it makes, in output
 *   tokens. Output rather than total because output is what the ceiling on a single call bounds,
 *   so the two are in the same unit and a shape's number can be compared against what is left.
 * @property spent what the calls made so far actually cost, as the provider reported it.
 */
data class Budget(val ceiling: Int, val spent: Long = 0) {

    init {
        require(ceiling > 0) { "A budget that permits nothing cannot answer anything." }
        require(spent >= 0) { "Spend is what calls cost and is never negative." }
    }

    /**
     * What is left, as a ceiling a single call can be sized against.
     *
     * Floored at zero rather than going negative: a resumed turn can overshoot, since the
     * adapter's resumption is a fresh call at the same ceiling and nothing can refuse it
     * mid-flight, and a negative ceiling is not something a caller could pass on.
     */
    val remaining: Int get() = (ceiling - spent).coerceAtLeast(0).toInt()

    /** This budget, having paid for [usage]. */
    fun spending(usage: TokenUsage): Budget = copy(spent = spent + usage.outputTokens)

    /**
     * Whether there is enough left for a call worth making.
     *
     * A floor rather than any remainder at all, because thinking counts against the ceiling: a
     * call given a few hundred tokens spends them reasoning and returns a truncation, which
     * costs the tokens and produces nothing to answer with. Refusing before the call is the
     * cheaper of the two ways to arrive at the same answer.
     */
    val worthSpending: Boolean get() = remaining >= FLOOR

    companion object {

        /**
         * The smallest call worth making, in output tokens.
         *
         * Sized so that a call reaching it has room to think and still say something. Below it a
         * request is better refused than attempted, and the refusal is the same insufficient
         * evidence an empty corpus produces, because in both cases there is nothing the system
         * can stand behind.
         */
        const val FLOOR: Int = 4_096

        /**
         * The default ceiling for one request.
         *
         * Three times what a corpus-reading call asks for — see [Sizing.ANSWERING] — which is what
         * the two multipliers need: the adapter resumes a paused turn, and the runner re-rolls
         * generation once. A request that spends it all has either been resumed repeatedly or
         * retried, and in both cases stopping is better than continuing to pay.
         *
         * **It is two of the open-web shape's calls rather than three**, because that shape asks
         * for half again as much room — see [Sizing.RESEARCHING]. That is the path most likely to
         * pause and be resumed, so it is the one with the least margin here, and the number is a
         * bound on what a question may cost rather than a promise about how many calls fit.
         */
        const val DEFAULT_CEILING: Int = 3 * 64 * 1024
    }
}
