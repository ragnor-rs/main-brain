package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.EventRepository
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import java.sql.Connection
import java.util.UUID

/**
 * Events over Postgres, keyed by what they say.
 *
 * The same two tables and one write as [JdbcCommitmentRepository], which is where the rules the two
 * share are recorded.
 */
class JdbcEventRepository(private val database: Database) : EventRepository {

    override suspend fun upsert(events: List<Event>): Stored {
        if (events.isEmpty()) return Stored.NOTHING
        return database.transaction { connection ->
            var written = 0
            events.forEach { event ->
                val row = connection.writeClaim(event)
                if (row.inserted) written++
                connection.writeEvidence(row.id, event.evidence)
            }
            Stored(written = written, overwritten = events.size - written)
        }
    }

    override suspend fun of(evidence: List<Evidence>): List<Event> {
        if (evidence.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_EVIDENCE).use { statement ->
                statement.bindSpans(evidence)
                statement.executeQuery().use { rows -> rows.toEvents() }
            }
        }
    }

    private fun Connection.writeClaim(event: Event): Upserted =
        prepareStatement(UPSERT_CLAIM).use { upsert ->
            upsert.bind {
                uuid(event.id.value)
                externalId(event.conversation)
                instant(event.evidence.minOf { it.sourceTime })
                string(event.claim)
                boolean(event.hedged)
                instant(event.occursAt)
                entityId(event.statedBy)
            }
            upsert.executeQuery().use { rows ->
                check(rows.next()) {
                    "An event upsert returned no row. `DO UPDATE` returns the conflicting row " +
                        "where `DO NOTHING` returns none, so this means the event is not the one " +
                        "written above."
                }
                Upserted(UUID.fromString(rows.getString("id")), rows.getBoolean(INSERTED))
            }
        }

    private fun Connection.writeEvidence(event: UUID, evidence: Set<Evidence>) {
        prepareStatement(INSERT_EVIDENCE).use { insert ->
            evidence.forEach { piece ->
                insert.bind {
                    uuid(event)
                    span(piece.span)
                    instant(piece.sourceTime)
                }
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private companion object {

        /** The earliest reading decides, for the reason `JdbcCommitmentRepository.UPSERT_CLAIM` records. */
        val UPSERT_CLAIM = """
            INSERT INTO event (
                id, conversation_source, conversation_native_id, source_time, claim, hedged, occurs_at, stated_by
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid)
            ON CONFLICT (conversation_source, conversation_native_id, claim)
                WHERE superseded_by IS NULL
            DO UPDATE SET
                source_time = LEAST(event.source_time, EXCLUDED.source_time),
                hedged = CASE
                    WHEN EXCLUDED.source_time <= event.source_time THEN EXCLUDED.hedged
                    ELSE event.hedged
                END,
                occurs_at = CASE
                    WHEN EXCLUDED.source_time <= event.source_time THEN EXCLUDED.occurs_at
                    ELSE event.occurs_at
                END,
                stated_by = CASE
                    WHEN EXCLUDED.source_time <= event.source_time THEN EXCLUDED.stated_by
                    ELSE event.stated_by
                END
            RETURNING id, (xmax = 0) AS inserted
        """.trimIndent()

        val INSERT_EVIDENCE = """
            INSERT INTO event_evidence
                (event, record_kind, record_source, record_native_id, ordinal, source_time)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (event, record_kind, record_source, record_native_id, ordinal) DO NOTHING
        """.trimIndent()

        val SELECT_BY_EVIDENCE = """
            SELECT c.id, c.conversation_source, c.conversation_native_id, c.claim, c.hedged, c.occurs_at, c.stated_by,
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM event c
            JOIN event_evidence e ON e.event = c.id
            WHERE c.id IN (
                SELECT w.event
                FROM event_evidence w
                JOIN unnest(?::text[], ?::text[], ?::text[], ?::int[])
                     AS wanted (record_kind, record_source, record_native_id, ordinal)
                  ON w.record_kind = wanted.record_kind
                 AND w.record_source = wanted.record_source
                 AND w.record_native_id = wanted.record_native_id
                 AND w.ordinal = wanted.ordinal
            )
            AND c.superseded_by IS NULL
        """.trimIndent()
    }
}
