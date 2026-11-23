package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.CommitmentRepository
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import java.sql.Connection
import java.util.UUID

/**
 * Commitments over Postgres, keyed by what they say.
 *
 * Two tables and one write, as statements are: the claim goes into `commitment` under its content
 * key and each of its spans into `commitment_evidence`, inside one transaction. A commitment with
 * no evidence is not representable in the domain, so a half-finished write has to be impossible
 * rather than merely unlikely.
 */
class JdbcCommitmentRepository(private val database: Database) : CommitmentRepository {

    override suspend fun upsert(commitments: List<Commitment>): Stored {
        if (commitments.isEmpty()) return Stored.NOTHING
        return database.transaction { connection ->
            var written = 0
            commitments.forEach { commitment ->
                val row = connection.writeClaim(commitment)
                if (row.inserted) written++
                connection.writeEvidence(row.id, commitment.evidence)
            }
            // Counted over the writes rather than over the argument, so two offers of one claim in
            // a single batch are one row written and one overwritten — which is what the store did.
            // Nothing is superseded here: a claim replacing another is the collapse's doing.
            Stored(written = written, overwritten = commitments.size - written)
        }
    }

    override suspend fun of(evidence: List<Evidence>): List<Commitment> {
        if (evidence.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_EVIDENCE).use { statement ->
                statement.bindSpans(evidence)
                statement.executeQuery().use { rows -> rows.toCommitments() }
            }
        }
    }

    /**
     * The identity of [commitment]'s row, which is the stored one wherever a claim is already held,
     * and whether this write is what put it there.
     *
     * `DO UPDATE` returns the conflicting row, so the identifier the caller offered is discarded
     * where one exists — the same rule contacts follow, and what keeps a re-derivation from
     * renumbering something another table points at. Which of the two happened is read off the row
     * as well, because the port answers with the rows it caused: see [INSERTED].
     */
    private fun Connection.writeClaim(commitment: Commitment): Upserted =
        prepareStatement(UPSERT_CLAIM).use { upsert ->
            upsert.bind {
                uuid(commitment.id.value)
                externalId(commitment.conversation)
                instant(commitment.evidence.minOf { it.sourceTime })
                string(commitment.claim)
                boolean(commitment.hedged)
                instant(commitment.due)
                entityId(commitment.statedBy)
                entityId(commitment.obligedParty)
            }
            upsert.executeQuery().use { rows ->
                check(rows.next()) {
                    "A commitment upsert returned no row. `DO UPDATE` returns the conflicting row " +
                        "where `DO NOTHING` returns none, so this means the commitment is not the " +
                        "one written above."
                }
                Upserted(UUID.fromString(rows.getString("id")), rows.getBoolean(INSERTED))
            }
        }

    private fun Connection.writeEvidence(commitment: UUID, evidence: Set<Evidence>) {
        prepareStatement(INSERT_EVIDENCE).use { insert ->
            evidence.forEach { piece ->
                insert.bind {
                    uuid(commitment)
                    span(piece.span)
                    instant(piece.sourceTime)
                }
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private companion object {

        /**
         * Write the claim, or find the one already stored, and return its identity either way.
         *
         * **The earliest reading decides what is stored about the obligation.** `source_time` is
         * when the thing was first stated, so a restatement arriving later keeps the promise's own
         * instant rather than making it look newer than it is. Every column that describes the
         * reading follows the same rule: a restatement is usually second-hand, and the author of the
         * message relaying a promise is neither the party who made it nor the party who owes it.
         *
         * **A re-derivation of the same evidence is still a better reading.** Its spans carry the
         * instants they already carried, so the comparison is an equality and the offered reading
         * wins — which is what makes a better prompt worth running over a window already done.
         */
        val UPSERT_CLAIM = """
            INSERT INTO commitment (
                id, conversation_source, conversation_native_id, source_time, claim, hedged, due_at, stated_by,
                obliged_party
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid)
            ON CONFLICT (conversation_source, conversation_native_id, claim)
                WHERE superseded_by IS NULL
            DO UPDATE SET
                source_time = LEAST(commitment.source_time, EXCLUDED.source_time),
                hedged = CASE
                    WHEN EXCLUDED.source_time <= commitment.source_time THEN EXCLUDED.hedged
                    ELSE commitment.hedged
                END,
                due_at = CASE
                    WHEN EXCLUDED.source_time <= commitment.source_time THEN EXCLUDED.due_at
                    ELSE commitment.due_at
                END,
                stated_by = CASE
                    WHEN EXCLUDED.source_time <= commitment.source_time THEN EXCLUDED.stated_by
                    ELSE commitment.stated_by
                END,
                obliged_party = CASE
                    WHEN EXCLUDED.source_time <= commitment.source_time THEN EXCLUDED.obliged_party
                    ELSE commitment.obliged_party
                END
            RETURNING id, (xmax = 0) AS inserted
        """.trimIndent()

        /**
         * Attach a span, or leave the one already attached alone.
         *
         * `DO NOTHING` rather than an update: a span that stated this claim states it still, and
         * its source time is the record's own and cannot have changed. This is what makes
         * re-deriving a window already read cost nothing here.
         */
        val INSERT_EVIDENCE = """
            INSERT INTO commitment_evidence
                (commitment, record_kind, record_source, record_native_id, ordinal, source_time)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (commitment, record_kind, record_source, record_native_id, ordinal) DO NOTHING
        """.trimIndent()

        /**
         * Every commitment any of these spans evidences, with all of its evidence.
         *
         * The inner select finds the commitments and the join then reads every span each holds, so
         * what comes back is the whole finding rather than the part of it that was asked for. A
         * commitment carrying two pointers is one row of the derived layer either way, and handing
         * back a partial evidence set would make the entity say something false about itself.
         */
        val SELECT_BY_EVIDENCE = """
            SELECT c.id, c.conversation_source, c.conversation_native_id, c.claim, c.hedged, c.due_at, c.stated_by,
                   c.obliged_party,
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM commitment c
            JOIN commitment_evidence e ON e.commitment = c.id
            WHERE c.id IN (
                SELECT w.commitment
                FROM commitment_evidence w
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
