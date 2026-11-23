package io.github.ragnorrs.mainbrain.domain

import java.time.Instant

/**
 * Where a billed call that is not a submission is recorded, held in memory.
 *
 * The totalling this stands in for is the store's, and is asserted against a real Postgres where
 * that belongs. What a pass needs a double for is the other half of the port: that a call it makes
 * outside a submission is written down somewhere a day's bound can read it. So this keeps the
 * charges and answers nothing of its own.
 */
class FakeSpendLedger : SpendLedger {

    /** Every charge recorded, in the order it was made. */
    val charges = mutableListOf<Pair<Deriver, TokenUsage>>()

    /** The four counters, summed across every charge — what a day would read back. */
    val charged: TokenUsage get() = charges.fold(TokenUsage.NONE) { total, charge -> total + charge.second }

    override suspend fun spentBetween(deriver: Deriver, from: Instant, until: Instant): TokenUsage {
        require(from < until) { "A span from $from until $until covers no time." }
        return charges.filter { it.first == deriver }.fold(TokenUsage.NONE) { total, c -> total + c.second }
    }

    override suspend fun charge(deriver: Deriver, usage: TokenUsage) {
        charges += deriver to usage
    }
}
