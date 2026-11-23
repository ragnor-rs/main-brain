package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.sql.Connection
import java.time.Instant

class JdbcDerivationStateRepository(private val database: Database) : DerivationStateRepository {

    override suspend fun pending(
        derivation: Derivation,
        kind: RecordKind,
        limit: Int,
        notBefore: Instant?,
    ): Pending {
        require(limit > 0) { "A batch of $limit records is not a batch." }
        return database.query { connection -> connection.readPending(derivation, kind, limit, notBefore) }
    }

    override suspend fun mark(
        derivation: Derivation,
        kind: RecordKind,
        records: List<ExternalId>,
        asOf: Instant,
    ) {
        if (records.isEmpty()) return
        // A transaction rather than a bare query, because this is many rows and they mean one
        // thing: a batch half-marked leaves some records settled and the rest to be derived
        // again, which is the shape `Database.query`'s own contract tells callers to avoid.
        database.transaction { connection -> connection.writeMarks(derivation, RecordTable.of(kind), records, asOf) }
    }

    override suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int =
        database.query { connection -> connection.countOwed(derivation, RecordTable.of(kind), notBefore) }

    /**
     * The records this pass still owes work on, oldest material first.
     *
     * The three `WHERE` branches are the three ways a record can be owed, and they are not
     * interchangeable. No row at all means never derived. A row at another version means the
     * pass itself changed — a different embedding model, a different prompt — and this is what
     * confines that invalidation to the pass whose version moved. A mark older than the
     * record's content time means the record changed underneath a derivation that is otherwise
     * current.
     *
     * The last one reads `content_time` and not `last_seen_at` deliberately: a resync re-sees
     * every record and advances last-seen on all of them, and keying off that would re-derive
     * the entire corpus every time ingestion ran a full pass.
     *
     * The horizon is a fourth clause and not a fifth way of being owed, which is why the three
     * are bracketed together: it narrows what is offered rather than widening it, and a record
     * excluded by it is still owed. Reading `source_time` means a record that carries none is
     * outside any horizon that is set — nothing can say whether it is recent — while with none
     * set the ordering puts it first.
     *
     * The in-flight exclusion is a fifth clause and narrows the same way: a record a submission of
     * this pass has outstanding is still owed, and is still counted by [countOwed], but is not
     * offered again while it is being waited on. See [pendingIn].
     */
    private fun Connection.readPending(
        derivation: Derivation,
        kind: RecordKind,
        limit: Int,
        notBefore: Instant?,
    ): Pending {
        val table = RecordTable.of(kind)
        return prepareStatement(pendingIn(table)).use { statement ->
            statement.bind {
                string(derivation.deriver.name.lowercase())
                table(table)
                string(derivation.version)
                // Twice, because the comparison and the test for whether there is one to make
                // both read it. A null parameter is bound with its type, so the `IS NULL` branch
                // resolves rather than leaving the planner to guess at an untyped placeholder.
                instant(notBefore)
                instant(notBefore)
                // Again, for the in-flight exclusion: a record with a submission of this pass
                // outstanding for this kind is owed but may not be selected.
                string(derivation.deriver.name.lowercase())
                string(kind.name.lowercase())
                long(limit.toLong())
            }
            statement.executeQuery().use { rows ->
                val found = rows.collect {
                    requireNotNull(it.externalId("source", "native_id")) to requireNotNull(it.instant("as_of"))
                }
                // The store's clock, projected by the same statement that chose the work. Read
                // separately it would be a second moment, and the gap between the two is
                // exactly the window an edit could hide in.
                Pending(records = found.map { it.first }, asOf = found.firstOrNull()?.second ?: selectNow())
            }
        }
    }

    /**
     * Everything this pass owes for this kind, counted instead of listed and with no limit.
     *
     * **It shares `owedFrom` with [readPending] rather than restating it, so the two cannot disagree
     * about what owed means.** A driver loops until this is zero and reads a fall in it as progress,
     * so a count standing apart from that definition would either stop a pass with work outstanding
     * or never let one finish — and a divergence introduced by editing one of two copies would look
     * like a pass that had stalled.
     *
     * What it deliberately does *not* share is the in-flight exclusion [readPending] adds on top. A
     * record a submission has been billed for is still owed and is still counted here; it is only
     * withheld from being offered again. Counting it is what keeps a sweep from reading zero,
     * reporting the pass caught up, and never collecting what it paid for.
     */
    private fun Connection.countOwed(derivation: Derivation, table: RecordTable, notBefore: Instant?): Int =
        prepareStatement(owedIn(table)).use { statement ->
            statement.bind {
                string(derivation.deriver.name.lowercase())
                table(table)
                string(derivation.version)
                instant(notBefore)
                instant(notBefore)
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt("owed")
            }
        }

    /** The store's clock, for the batch that turned out to be empty. */
    private fun Connection.selectNow(): Instant =
        prepareStatement("SELECT now() AS as_of").use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                requireNotNull(rows.instant("as_of"))
            }
        }

    private fun Connection.writeMarks(
        derivation: Derivation,
        table: RecordTable,
        records: List<ExternalId>,
        asOf: Instant,
    ) {
        prepareStatement(MARK).use { statement ->
            records.distinct().forEach { record ->
                statement.bind {
                    string(derivation.deriver.name.lowercase())
                    table(table)
                    externalId(record)
                    string(derivation.version)
                    instant(asOf)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private companion object {

        /**
         * What may be selected now: owed, and not already with the provider.
         *
         * The record table is interpolated because a table name cannot be a bound parameter. It
         * comes from [RecordTable] and from nowhere else, which is the same rule the record
         * repositories follow — every other value in every statement here is bound.
         *
         * **The second half is not a second definition of owed, and deliberately is not in
         * [owedIn].** A record a submission has been billed for is still owed — nothing has been
         * derived from it until the replies are read — so it goes on being counted. What it must not
         * be is offered again, because the pass would classify and pay for it a second time while the
         * first submission was still outstanding. A pass whose stages submit independently has those
         * two states apart for as long as a turnaround lasts, and this is the only statement that
         * needs to tell them apart.
         *
         * Keyed on the pass and not on the stage: a record is with the provider if *any* stage of
         * this pass has it outstanding, and the stage that would re-select it is not the stage
         * holding it.
         *
         * **Keyed on the record kind as well, because an identifier is unique only within one.** The
         * sources key different kinds alike — a Telegram forum topic is named by the message that
         * created it, so a conversation and a message can carry the same identifier under the same
         * source — and `recognition` is the deriver of two passes, inference over messages and
         * identity over conversations and persons. Without the kind, an inference submission holding
         * a message would withhold the like-named conversation from identity's selection for as long
         * as it was outstanding, and identity would read as stalled while nothing was wrong with it.
         * `derivation` is keyed by kind immediately below for the same reason.
         *
         * **The source is folded, because the two tables do not store it the same way.** A record's
         * own table keeps the enum as it is named — `TELEGRAM` — and a submission's records keep it
         * lowercased, which every reader of that table undoes on the way out. Compared without the
         * fold this clause matches nothing at all and the exclusion is silently a no-op: selection
         * goes on offering records already paid for, and nothing fails. The fold is on the record
         * table's side so that the submission column stays bare and its index still serves.
         *
         * Putting it in the count instead would deadlock the pass. A sweep reads the count first and
         * stops where it is zero, so a count that excluded work in flight would reach zero with a
         * submission outstanding, report the pass as caught up, and never reach the step that
         * collects it.
         */
        fun pendingIn(table: RecordTable) = """
            SELECT r.source, r.native_id, now() AS as_of
            ${owedFrom(table)}
              AND NOT EXISTS (
                  SELECT 1
                  FROM model_submission_record msr
                  JOIN model_submission ms ON ms.id = msr.submission
                  WHERE msr.record_source = lower(r.source)
                    AND msr.record_native_id = r.native_id
                    AND ms.deriver = ?
                    AND ms.record_kind = ?
                    AND ms.collected_at IS NULL
              )
            ORDER BY r.source_time NULLS FIRST, r.id
            LIMIT ?
        """.trimIndent()

        /**
         * Everything this pass owes, counted and unbounded.
         *
         * Shares `owedFrom` with the selection so that the two cannot disagree about what owed
         * means. It does *not* share the in-flight exclusion, which is a narrowing of what may be
         * selected rather than of what is owed — see [pendingIn].
         */
        fun owedIn(table: RecordTable) = """
            SELECT count(*) AS owed
            ${owedFrom(table)}
        """.trimIndent()

        /**
         * What it means to be owed, written once and read by both statements above.
         *
         * The three `WHERE` branches are the three ways a record can be owed and the fourth
         * clause is the horizon; the reasoning for each is on [readPending]. Shared rather than
         * repeated because the count exists to be compared against the selection: two copies
         * would be two definitions of owed, and the second of them to be edited would turn a
         * working pass into one that looks stalled.
         */
        fun owedFrom(table: RecordTable) = """
            FROM ${table.table} r
            LEFT JOIN derivation d
                   ON d.deriver = ?
                  AND d.record_kind = ?
                  AND d.record_source = r.source
                  AND d.record_native_id = r.native_id
            WHERE (d.id IS NULL
                OR d.version <> ?
                OR d.derived_at < r.content_time)
              AND (?::timestamptz IS NULL OR r.source_time >= ?::timestamptz)
        """.trimIndent()

        val MARK = """
            INSERT INTO derivation (deriver, record_kind, record_source, record_native_id, version, derived_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (deriver, record_kind, record_source, record_native_id) DO UPDATE SET
                version = EXCLUDED.version,
                derived_at = EXCLUDED.derived_at
        """.trimIndent()
    }
}
