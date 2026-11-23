package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.SpendLedger
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import java.sql.Connection
import java.time.Instant

/**
 * What a pass has been billed over a span, totalled out of what is already recorded.
 *
 * **A reader, not a tally.** The figures come from the submission rows the collections wrote and
 * from the charges a failure recorded, and nothing here keeps a running total of its own — so there
 * is no second number that could disagree with the bill. A pass asking twice in one span gets the
 * same answer twice, and a restarted process gets the answer the one before it would have got.
 */
class JdbcSpendLedger(private val database: Database) : SpendLedger {

    override suspend fun spentBetween(deriver: Deriver, from: Instant, until: Instant): TokenUsage {
        // Half-open and therefore non-empty, which is a caller's mistake rather than a store's. An
        // inverted span would total nothing and read as a day that has spent nothing — the one
        // wrong answer that reads as a correct one, because it is what a fresh day looks like.
        require(from < until) {
            "A span from $from until $until covers no time, so it would total nothing and read as " +
                "a span that was paid for and cost nothing."
        }
        return database.query { connection -> connection.readSpend(deriver, from, until) }
    }

    override suspend fun charge(deriver: Deriver, usage: TokenUsage) {
        database.transaction { connection -> connection.writeCharge(deriver, usage) }
    }

    /**
     * Both tables in one read, because a day's spend is the sum of them and neither alone is it.
     *
     * One statement rather than two and an addition here: two reads could straddle a write and
     * total a span that never existed, which for a bound that stops a stage spending is the
     * direction that lets it overspend.
     */
    private fun Connection.readSpend(deriver: Deriver, from: Instant, until: Instant): TokenUsage =
        prepareStatement(SELECT_SPEND).use { statement ->
            statement.bind {
                string(deriver.name.lowercase())
                instant(from)
                instant(until)
                string(deriver.name.lowercase())
                instant(from)
                instant(until)
            }
            statement.executeQuery().use { rows ->
                // An aggregate always returns a row, so no row at all is the store disagreeing
                // with itself rather than a span with nothing in it.
                check(rows.next()) { "A sum over the spend returned no row at all." }
                TokenUsage(
                    inputTokens = rows.getLong("input_tokens"),
                    outputTokens = rows.getLong("output_tokens"),
                    cacheReadTokens = rows.getLong("cache_read_tokens"),
                    cacheWriteTokens = rows.getLong("cache_write_tokens"),
                )
            }
        }

    private fun Connection.writeCharge(deriver: Deriver, usage: TokenUsage) {
        prepareStatement(INSERT_CHARGE).use { statement ->
            statement.bind {
                string(deriver.name.lowercase())
                long(usage.inputTokens)
                long(usage.outputTokens)
                long(usage.cacheReadTokens)
                long(usage.cacheWriteTokens)
            }
            statement.executeUpdate()
        }
    }

    private companion object {

        /**
         * The four counters, summed across the collections and the charges of one pass in a span.
         *
         * `coalesce` on each sum rather than on the row, because a span with nothing in it sums to
         * null on every column and a bound reading null as unknown would refuse every stage. Zero
         * is the honest total for a span nothing was billed in.
         *
         * The submission half is keyed on the collection time and not the submission time: what a
         * day is charged for is what was billed in it, and a batch allowed most of a day is billed
         * when it is read. The predicate also excludes the uncollected rows for free — an
         * uncollected submission has no cost yet, which `V14` makes structural.
         */
        val SELECT_SPEND = """
            SELECT coalesce(sum(input_tokens), 0)       AS input_tokens,
                   coalesce(sum(output_tokens), 0)      AS output_tokens,
                   coalesce(sum(cache_read_tokens), 0)  AS cache_read_tokens,
                   coalesce(sum(cache_write_tokens), 0) AS cache_write_tokens
            FROM (
                SELECT input_tokens, output_tokens, cache_read_tokens, cache_write_tokens
                FROM model_submission
                WHERE deriver = ? AND collected_at >= ? AND collected_at < ?
                UNION ALL
                SELECT input_tokens, output_tokens, cache_read_tokens, cache_write_tokens
                FROM model_charge
                WHERE deriver = ? AND charged_at >= ? AND charged_at < ?
            ) AS spend
        """.trimIndent()

        val INSERT_CHARGE = """
            INSERT INTO model_charge
                (deriver, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
    }
}
