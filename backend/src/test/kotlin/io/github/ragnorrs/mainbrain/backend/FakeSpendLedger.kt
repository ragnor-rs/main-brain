package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.SpendLedger
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import java.time.Instant

/**
 * A day's spend, held in memory and placed by whoever is testing against it.
 *
 * **The real one totals rows in two tables, and the arithmetic is asserted against a real Postgres
 * where it belongs.** What the driver's own tests are about is the decision taken from the figure —
 * which stage is stopped, which keeps running, and what is charged on the way out of a failure — so
 * this answers with whatever a case put in it and records what the driver wrote.
 *
 * **Keyed by the span it is asked for, not just by the pass.** The driver cuts a day and asks for
 * that range, so a fake that ignored the range could not tell a case about a fresh day from one
 * about an exhausted one: both would read the same total. Keying on it is what lets a test place
 * spend inside one day and assert that the next day starts clear.
 */
class FakeSpendLedger(
    /** What every span reads as, before anything is placed or charged. */
    private val everywhere: TokenUsage = TokenUsage.NONE,
) : SpendLedger {

    private val placed = mutableMapOf<Triple<Deriver, Instant, Instant>, TokenUsage>()

    /** Every charge the driver wrote, in the order it wrote them. */
    val charges = mutableListOf<Pair<Deriver, TokenUsage>>()

    /** How many times the driver read a span, which is how a per-batch gate is told from a per-stage one. */
    var reads = 0
        private set

    /** Say what [deriver] has spent in `[from, until)`, as though the store held it. */
    fun place(deriver: Deriver, from: Instant, until: Instant, spent: TokenUsage) {
        placed[Triple(deriver, from, until)] = spent
    }

    override suspend fun spentBetween(deriver: Deriver, from: Instant, until: Instant): TokenUsage {
        // **The real store refuses an empty span and this has to refuse it too.** An inverted or
        // zero-width span totals nothing, which reads as a day that has spent nothing — so a driver
        // that cut one would be exempted from the bound, and against a fake that accepted anything
        // it would pass every case here and be refused only in production.
        require(from < until) {
            "A span from $from until $until covers no time, so it would read as a day that has " +
                "spent nothing."
        }
        reads++
        // The charges the driver wrote land in whichever span covers them, which is how a case
        // about a failure charging the day can read the figure back the way a later sweep would.
        val written = charges
            .filter { charge -> charge.first == deriver }
            .fold(TokenUsage.NONE) { total, charge -> total + charge.second }
        return (placed[Triple(deriver, from, until)] ?: everywhere) + written
    }

    override suspend fun charge(deriver: Deriver, usage: TokenUsage) {
        charges += deriver to usage
    }
}
