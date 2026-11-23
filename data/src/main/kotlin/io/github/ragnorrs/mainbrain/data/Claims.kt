package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.Identifier
import java.sql.Connection

// Writing a record's claims, and the one consequence that has: a claim arriving for the first time
// is a change to what a person record *is*, so the record becomes owed its identity again. Its own
// file rather than another entry in `Sql.kt`, which is at the function count detekt allows a file —
// and these three belong together, since the count and the bump are only correct as a pair.

/**
 * Record [identifiers] as claims of this record.
 *
 * A claim already present has its last-seen advanced and keeps the time it was added; a new one
 * gets both. **Nothing is removed.** A source that stops advertising a number has not retracted
 * it — the claim stays, with its last-seen standing still while the record's advances, which is
 * the only thing that can tell a current number from one abandoned years ago.
 *
 * **A claim arriving for the first time moves a person record's content time**, which is what makes
 * the identity pass revisit it. A claim is what the matcher scores on, so a person record that
 * gains one is a record whose identity may have changed answer. Without this the claim lands in
 * its own table, the record's own columns stand still, and the watermark — which keys on content
 * time — never offers the record again. The pair that a newly shared number would have merged then
 * waits for a display name to change.
 *
 * **A re-seen claim moves nothing**, and that is the half that keeps the cost down. A resync
 * re-offers every claim of every record; advancing content time on all of them would re-derive
 * the whole corpus on each full pass, which is the mistake the column exists to avoid.
 *
 * **Person records only**, and the exclusion is the load-bearing part. Identity is the one deriver
 * over person records and it spends no model tokens, so re-deriving one is local compute. A
 * message is derived over by indexing *and* by inference, and inference is paid for in tokens — so
 * moving a message's content time because it gained a claim would buy a re-read of material whose
 * text did not change, at the one price this build cannot absorb. A claim is not content to a pass
 * that reads what was said. See [RecordTable.claimsAreIdentity].
 */
internal fun Connection.recordIdentifiers(
    table: RecordTable,
    recordId: Long,
    identifiers: List<Identifier>,
) {
    if (identifiers.isEmpty()) return
    val wanted = identifiers.distinct()
    val arrived = if (table.claimsAreIdentity) countArrivingClaims(table, recordId, wanted) else 0
    prepareStatement(
        """
        INSERT INTO identifier (record_kind, record_id, type, value)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (record_kind, record_id, type, value) DO UPDATE SET last_seen_at = now()
        """.trimIndent(),
    ).use { statement ->
        wanted.forEach { identifier ->
            statement.bind {
                table(table)
                long(recordId)
                string(identifier.type.name)
                string(identifier.value)
            }
            statement.addBatch()
        }
        statement.executeBatch()
    }
    if (arrived > 0) touchContentTime(table, recordId)
}

/**
 * How many of [identifiers] this record does not already claim.
 *
 * Counted before the write rather than derived from it, because the write is a batch and a batched
 * upsert does not say which rows it inserted. Both run in the caller's transaction, so nothing can
 * land between the two.
 */
private fun Connection.countArrivingClaims(
    table: RecordTable,
    recordId: Long,
    identifiers: List<Identifier>,
): Int =
    prepareStatement(
        """
        SELECT count(*)
        FROM unnest(?::text[], ?::text[]) AS offered (type, value)
        WHERE NOT EXISTS (
            SELECT 1 FROM identifier i
            WHERE i.record_kind = ? AND i.record_id = ?
              AND i.type = offered.type AND i.value = offered.value
        )
        """.trimIndent(),
    ).use { statement ->
        statement.bind {
            strings(identifiers.map { it.type.name })
            strings(identifiers.map { it.value })
            table(table)
            long(recordId)
        }
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

/**
 * Advance this record's content time, so that every derived pass is owed it again.
 *
 * The table is interpolated because it names a column set rather than a value, and it comes from
 * [RecordTable] — an enum of four literals this module declares, never from anything a source
 * said.
 */
private fun Connection.touchContentTime(table: RecordTable, recordId: Long) {
    prepareStatement("UPDATE ${table.table} SET content_time = now() WHERE id = ?").use { statement ->
        statement.bind { long(recordId) }
        statement.executeUpdate()
    }
}
