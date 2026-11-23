package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Finding
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import java.sql.Connection
import java.time.Duration

/**
 * The collapse over Postgres: which pairs might be one fact, and the two ways one is settled.
 *
 * **One class over both derived kinds**, because the two tables differ in one column name and in
 * nothing this cares about. A commitment and an event are keyed alike, merged alike and kept apart
 * alike, so the statements below are built per kind from one shape — the alternative is the same
 * five queries written twice, free to drift in whichever of the two a change forgot.
 *
 * Nothing here decides anything. It offers the pairs no test it can make rules out, applies a merge
 * somebody else decided, and remembers a verdict somebody else bought. One rule is *applied* here
 * and stated elsewhere: which of two closure verdicts a merge keeps, which is a judgement
 * [CollapseRepository.merge] carries and a conflict clause below enforces.
 */
// Past the per-class cap, which counts a statement per write beside the port's own members.
// Folding the closure carry into the evidence carry would hide that a merge moves a verdict as well
// as the spans, which is the thing about it worth seeing.
@Suppress("TooManyFunctions")
class JdbcCollapseRepository(private val database: Database) : CollapseRepository {

    /**
     * The candidate pairs of both kinds, up to [limit] in total.
     *
     * **Two reads per kind rather than one join, and the second is the reason.** A finding joined to
     * its evidence arrives as one row per span, so a query returning pairs *and* their evidence
     * would multiply one against the other and hand back a cross product for the caller to undo.
     * So the identifiers come back first, bounded, and the findings behind them are read whole.
     *
     * The limit is shared across the kinds rather than applied to each, because what it bounds is
     * how much one sweep does, and commitments are read first.
     *
     * **That order starves nothing only because every answer is recorded**, including the cheap
     * tier's — see [CollapseRepository.candidates]. Were a vector rule-out left unrecorded, this
     * query being oldest-first over a bound would return the same page of commitment pairs on every
     * sweep, and an event pair would never be read at all. With the answers written down the page
     * moves on, and the count behind it falls.
     */
    override suspend fun candidates(
        limit: Int,
        within: Duration,
        comparedBy: String,
        adjudicatedBy: String,
    ): List<FindingPair> {
        require(limit > 0) { "A batch of $limit pairs compares nothing." }
        require(!within.isNegative) {
            "A window of $within admits no pair: two readings of one fact are stated at or after " +
                "each other, never before."
        }
        return database.query { connection ->
            FindingKind.entries.fold(emptyList<FindingPair>()) { found, kind ->
                if (found.size >= limit) {
                    found
                } else {
                    found + connection.candidatesOf(
                        kind,
                        limit - found.size,
                        within,
                        comparedBy,
                        adjudicatedBy,
                    )
                }
            }
        }
    }

    override suspend fun candidateCount(within: Duration, comparedBy: String, adjudicatedBy: String): Int =
        database.query { connection ->
            FindingKind.entries.sumOf { kind ->
                connection.prepareStatement(countSql(kind)).use { statement ->
                    statement.bind {
                        long(within.seconds)
                        string(kind.stored)
                        string(adjudicatedBy)
                        string(comparedBy)
                    }
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "A count query returned no row." }
                        rows.getInt(1)
                    }
                }
            }
        }

    override suspend fun pair(kind: FindingKind, one: EntityId, other: EntityId): FindingPair? =
        database.query { connection -> connection.pairOf(kind, one, other) }

    /**
     * Move what the superseded reading held first and mark the row afterwards.
     *
     * **The order is the one every pass here writes in, and for the same reason.** A failure between
     * the two leaves the survivor holding evidence it did not have and both rows still visible,
     * which the next sweep resolves again at no cost. Marked first, the same failure would hide a
     * reading whose spans had not yet reached the survivor — and a finding would lose the evidence
     * of where it was stated, with nothing to notice it.
     *
     * **The closure verdict travels for one kind only, and that is not an asymmetry in the
     * collapse.** Only a commitment can be closed; an event has no verdict to carry, so there is no
     * second statement that was forgotten here. It is the one write in this class that names a kind
     * rather than being built from one — see [carryClosure] for what it costs to leave undone.
     */
    override suspend fun merge(pair: FindingPair) {
        database.transaction { connection ->
            connection.carryEvidence(pair)
            if (pair.kind == FindingKind.COMMITMENT) connection.carryClosure(pair)
            connection.markSuperseded(pair)
        }
    }

    override suspend fun keepApart(kind: FindingKind, one: EntityId, other: EntityId, decidedBy: String) {
        require(one != other) { "A finding is not kept apart from itself." }
        require(decidedBy.isNotBlank()) { "A record of what kept two findings apart cannot be nameless." }
        database.transaction { connection ->
            connection.prepareStatement(INSERT_KEPT_APART).use { statement ->
                // Ordered here and not by the caller, so that one unordered pair is one row
                // whichever way round it was compared. On [EntityId], whose ordering is the store's
                // own — the constraint this satisfies is checked in SQL.
                statement.bind {
                    string(kind.stored)
                    uuid(minOf(one, other).value)
                    uuid(maxOf(one, other).value)
                    string(decidedBy)
                }
                statement.executeUpdate()
            }
        }
    }

    /** The identifier pairs of one kind the store cannot rule out, then the findings behind them. */
    private fun Connection.candidatesOf(
        kind: FindingKind,
        limit: Int,
        within: Duration,
        comparedBy: String,
        adjudicatedBy: String,
    ): List<FindingPair> {
        val pairs = prepareStatement(candidateSql(kind)).use { statement ->
            // In the order the statement declares them, which is the window, then the kind the
            // exclusion is keyed on, then the two versions whose verdicts it honours, then the
            // bound.
            statement.bind {
                // Seconds rather than an interval, because the binder has no interval type and the
                // statement casts. A window is configuration measured in hours at most, so there is
                // nothing near the edge of the type.
                long(within.seconds)
                string(kind.stored)
                string(adjudicatedBy)
                string(comparedBy)
                int(limit)
            }
            statement.executeQuery().use { rows ->
                rows.collect { row ->
                    requireNotNull(row.entityId("lower_id")) to requireNotNull(row.entityId("upper_id"))
                }
            }
        }
        if (pairs.isEmpty()) return emptyList()

        val byId = findingsOf(kind, pairs.flatMap { (lower, upper) -> listOf(lower, upper) }.distinct())
        // A pair whose either side is missing is dropped rather than failed on. The two reads are
        // not one transaction, so a merge landing in between can supersede a row the first read
        // offered — and that is a pair already settled, not an anomaly.
        return pairs.mapNotNull { (lower, upper) ->
            val one = byId[lower] ?: return@mapNotNull null
            val other = byId[upper] ?: return@mapNotNull null
            FindingPair.of(kind, one, other)
        }
    }

    private fun Connection.pairOf(kind: FindingKind, one: EntityId, other: EntityId): FindingPair? {
        // One identifier twice is not a pair, and is not asked of the store: `FindingPair` refuses
        // to be built from it, so reading the row only to throw would be a round trip for nothing.
        val byId = if (one == other) emptyMap() else findingsOf(kind, listOf(one, other))
        val first = byId[one]
        val second = byId[other]
        return if (first == null || second == null) null else FindingPair.of(kind, first, second)
    }

    /**
     * The survivors among [ids], whole, by identifier.
     *
     * Superseded rows are absent rather than returned, which is what makes a collection safe: a
     * reading merged away while an adjudication was with the provider is no longer a finding of its
     * own, and merging onto it would undo the collapse that moved it.
     */
    private fun Connection.findingsOf(kind: FindingKind, ids: List<EntityId>): Map<EntityId, Finding> =
        prepareStatement(byIdSql(kind)).use { statement ->
            statement.bind { strings(ids.map { it.value.toString() }) }
            statement.executeQuery().use { rows ->
                when (kind) {
                    FindingKind.COMMITMENT -> rows.toCommitments()
                    FindingKind.EVENT -> rows.toEvents()
                }
            }
        }.associateBy { it.id }

    private fun Connection.carryEvidence(pair: FindingPair) {
        prepareStatement(carrySql(pair.kind)).use { statement ->
            statement.bind {
                uuid(pair.survivor.id.value)
                uuid(pair.superseded.id.value)
            }
            statement.executeUpdate()
        }
    }

    /**
     * The closure the superseded reading found, onto the survivor.
     *
     * **What it saves is one model call per merged commitment whose closure had been found**, which
     * is the only case it writes in. The examination horizon is costed on a closure already found
     * never being paid for again, and a verdict every read filters out is one the survivor is
     * examined without.
     *
     * A copy and not a move: the superseded row keeps its verdict exactly as it keeps its spans, so
     * the one row this can replace — a survivor's verdict that found nothing — is the only thing a
     * merge here does not leave behind it, and re-examining reaches that answer again.
     *
     * Guarded on both rows still being survivors, as the supersede write is. A merge refused there
     * is a no-op the next sweep repeats, and this write has to be refused with it: it is the one
     * part of a merge that settles something rather than adding to it.
     */
    private fun Connection.carryClosure(pair: FindingPair) {
        prepareStatement(CARRY_CLOSURE).use { statement ->
            // In the order the statement declares them: the verdict being carried, then the
            // survivor twice — its promise, then its own verdict — and then the row written onto,
            // which is also the guard that it is still a survivor.
            statement.bind {
                uuid(pair.superseded.id.value)
                uuid(pair.survivor.id.value)
                uuid(pair.survivor.id.value)
                uuid(pair.survivor.id.value)
            }
            statement.executeUpdate()
        }
    }

    private fun Connection.markSuperseded(pair: FindingPair) {
        prepareStatement(supersedeSql(pair.kind)).use { statement ->
            // The survivor twice: once as the value written, once for the guard that it is still a
            // survivor itself.
            statement.bind {
                uuid(pair.survivor.id.value)
                uuid(pair.superseded.id.value)
                uuid(pair.survivor.id.value)
            }
            statement.executeUpdate()
        }
    }

    private companion object {

        /** What the two kinds are called in the one column that names them. */
        private val FindingKind.stored: String get() = name.lowercase()

        /** The table each kind lives in, which is the column its evidence table points back through. */
        private val FindingKind.table: String get() = name.lowercase()

        /** What each kind calls the moment it carries, which the two rows name differently. */
        private val FindingKind.dated: String
            get() = when (this) {
                FindingKind.COMMITMENT -> "due_at"
                FindingKind.EVENT -> "occurs_at"
            }

        /**
         * Which party the row obliges, for the one kind that has the column.
         *
         * A commitment obliges a party and an event obliges nobody, so the column exists on one
         * table and the mapper that names it is read for one kind — see `findingsOf`, which
         * dispatches on the kind rather than sharing a mapper. Carried as the whole clause,
         * delimiter included, because the alternative is a projected `NULL` for events that no
         * mapper ever reads: harmless, and a column in a statement with nothing to explain why it
         * is there.
         */
        private val FindingKind.obliged: String
            get() = when (this) {
                FindingKind.COMMITMENT -> ", f.obliged_party"
                FindingKind.EVENT -> ""
            }

        /**
         * Pairs of survivors in one conversation and kind, stated within the window of each other.
         *
         * **The cheap filter and not the comparison.** It returns every pair two readings of one
         * fact *could* be, which is a great many pairs that merely sit near each other in a chat;
         * deciding which of them say the same thing is a question about meaning and is answered
         * outside SQL.
         *
         * `b.id > a.id` is what makes one unordered pair appear once, and it orders the two the way
         * `finding_kept_apart` stores them, so the exclusion below is a key lookup rather than a
         * pair of them. It also rules out a row pairing with itself.
         *
         * **Both sides must be survivors.** A superseded reading is already part of another finding,
         * and offering one would merge a row onto a row that had itself been merged away.
         *
         * `source_time` is when a finding was first stated — the minimum over its evidence, kept
         * there by every write — so the window is between the two first statements and not between
         * whichever spans happen to have been read last.
         *
         * Oldest first, so that an interrupted deployment makes the same progress rather than
         * revisiting whatever the planner picked; the identifiers complete the order so that it is
         * total.
         */
        fun candidateSql(kind: FindingKind) = """
            SELECT a.id AS lower_id, b.id AS upper_id
            FROM ${kind.table} a
            JOIN ${kind.table} b
              ON b.conversation_source = a.conversation_source
             AND b.conversation_native_id = a.conversation_native_id
             AND b.id > a.id
            WHERE a.superseded_by IS NULL
              AND b.superseded_by IS NULL
              AND abs(extract(epoch FROM (b.source_time - a.source_time))) <= ?::bigint
              AND NOT EXISTS (
                  SELECT 1
                  FROM finding_kept_apart k
                  WHERE k.kind = ?
                    AND k.lower_id = a.id
                    AND k.upper_id = b.id
                    AND k.decided_by IN (?, ?)
              )
            ORDER BY a.source_time, a.id, b.id
            LIMIT ?
        """.trimIndent()

        /**
         * How many pairs of one kind the candidate query would offer, unbounded.
         *
         * The same predicate as [candidateSql] with the ordering and the limit removed, which is
         * the one thing it must stay in step with: a count over a different condition would have
         * the driver read progress where there was none, or a stall where there was progress.
         */
        fun countSql(kind: FindingKind) = """
            SELECT count(*)
            FROM ${kind.table} a
            JOIN ${kind.table} b
              ON b.conversation_source = a.conversation_source
             AND b.conversation_native_id = a.conversation_native_id
             AND b.id > a.id
            WHERE a.superseded_by IS NULL
              AND b.superseded_by IS NULL
              AND abs(extract(epoch FROM (b.source_time - a.source_time))) <= ?::bigint
              AND NOT EXISTS (
                  SELECT 1
                  FROM finding_kept_apart k
                  WHERE k.kind = ?
                    AND k.lower_id = a.id
                    AND k.upper_id = b.id
                    AND k.decided_by IN (?, ?)
              )
        """.trimIndent()

        /** One kind's survivors among the given identifiers, with every span each holds. */
        fun byIdSql(kind: FindingKind) = """
            SELECT f.id, f.conversation_source, f.conversation_native_id, f.claim, f.hedged,
                   f.${kind.dated}, f.stated_by${kind.obliged},
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM ${kind.table} f
            JOIN ${kind.table}_evidence e ON e.${kind.table} = f.id
            JOIN unnest(?::uuid[]) AS wanted (id) ON f.id = wanted.id
            WHERE f.superseded_by IS NULL
        """.trimIndent()

        /**
         * Every span of the superseded reading, attached to the survivor as well.
         *
         * `DO NOTHING` on the conflict, because the two readings may share a span — a re-derivation
         * reads the same message again — and because this has to be safe to run twice: a failure
         * before the merge is marked re-collects the same reply.
         *
         * The superseded row keeps its own evidence, which is what makes the merge reversible and
         * costs nothing: every read filters to survivors.
         */
        fun carrySql(kind: FindingKind) = """
            INSERT INTO ${kind.table}_evidence
                (${kind.table}, record_kind, record_source, record_native_id, ordinal, source_time)
            SELECT ?, e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM ${kind.table}_evidence e
            WHERE e.${kind.table} = ?
            ON CONFLICT DO NOTHING
        """.trimIndent()

        /**
         * The superseded reading's closure, written against the survivor.
         *
         * **Commitments only, and the statement is not built per kind for that reason**: an event is
         * never closed, so there is no second table this would read. It is the one write a merge
         * makes that names one kind's table rather than being shaped from both.
         *
         * **Only a verdict that found a closing message is carried**, which is what the two `WHERE`
         * clauses say between them — the first that the superseded row names one, the second that
         * the survivor does not. Copying a verdict that found nothing would write a row changing
         * nothing: its mark would be the survivor's own promise, so the survivor is owed the same
         * window it was owed with no row at all. That the survivor's own closure stands where both
         * readings found one is the same `WHERE` declining, and it is what makes this idempotent —
         * run again, the row is already there and names a message, so nothing moves.
         *
         * The rule behind those clauses is a judgement and is stated once, on
         * [CollapseRepository.merge]; this is only where it is applied.
         *
         * **`mark` is the survivor's own watermark, and its promise where it has none.** The
         * superseded row's mark is deliberately not used: its window began at its own promise, which
         * is later than the survivor's, so claiming it would record the stretch between the two
         * promises as weighed when no examination read it. The survivor's own mark never over-claims
         * and is kept rather than lowered, because `SELECT_OWED` projects it as what the next verdict
         * must not claim less than — reset to the promise, a later version move would hand back
         * ground already covered and buy the window again.
         *
         * **Two things this cannot promise, both of them bounded by the copy.** A collection already
         * in flight answers against the window it was submitted with, so a reply landing after this
         * can replace the carried closure with one that found nothing; and the survivor's horizon
         * ends earlier than the superseded reading's, by up to the pairing window, so a closure found
         * in that trailing band is one the survivor's own examination can never reach — a version
         * move that re-opens it will settle it as closed by nothing. Neither destroys anything: the
         * superseded row keeps its verdict, and the worst case is the strand this statement exists to
         * avoid.
         */
        val CARRY_CLOSURE = """
            WITH carried AS (
                SELECT * FROM commitment_closure WHERE commitment = ?
            ),
            mark AS (
                SELECT COALESCE(cc.considered_through, promise.source_time) AS at,
                       COALESCE(cc.considered_source, promise.record_source) AS source,
                       COALESCE(cc.considered_native_id, promise.record_native_id) AS native_id
                FROM (
                    SELECT e.source_time, e.record_source, e.record_native_id
                    FROM commitment_evidence e
                    WHERE e.commitment = ?
                    ORDER BY e.source_time, e.record_native_id
                    LIMIT 1
                ) promise
                LEFT JOIN commitment_closure cc ON cc.commitment = ?
            )
            INSERT INTO commitment_closure (
                commitment, record_kind, record_source, record_native_id, ordinal, record_source_time,
                considered_through, considered_source, considered_native_id, decided_by
            )
            SELECT survivor.id, carried.record_kind, carried.record_source, carried.record_native_id,
                   carried.ordinal, carried.record_source_time,
                   mark.at, mark.source, mark.native_id, carried.decided_by
            FROM carried
            CROSS JOIN mark
            JOIN commitment survivor ON survivor.id = ? AND survivor.superseded_by IS NULL
            JOIN commitment dead ON dead.id = carried.commitment AND dead.superseded_by IS NULL
            WHERE carried.record_source IS NOT NULL
            ON CONFLICT (commitment) DO UPDATE SET
                record_kind = EXCLUDED.record_kind,
                record_source = EXCLUDED.record_source,
                record_native_id = EXCLUDED.record_native_id,
                ordinal = EXCLUDED.ordinal,
                record_source_time = EXCLUDED.record_source_time,
                considered_through = EXCLUDED.considered_through,
                considered_source = EXCLUDED.considered_source,
                considered_native_id = EXCLUDED.considered_native_id,
                decided_by = EXCLUDED.decided_by,
                decided_at = now()
            WHERE commitment_closure.record_source IS NULL
        """.trimIndent()

        /**
         * Name the survivor on the superseded row, if it is still a survivor itself.
         *
         * The first `IS NULL` is what makes this idempotent and what makes it safe: a row already
         * merged into something else is left alone rather than re-pointed, so a reply collected
         * twice cannot move a reading from one finding to another. No rows updated is a success
         * here.
         *
         * **The second `IS NULL` is on the survivor, and it closes the store's half of the chain
         * hazard.** `collapsed` keeps a batch's merges flat so that none of them points at a reading
         * the same batch is moving away, but nothing outside that batch is bound by it: between the
         * read that offered this pair and this write, the survivor may itself have been superseded.
         * Pointing at it then would carry the evidence onto a row every read filters out, and the
         * spans would vanish from the answer path with nothing to notice. Refusing the write leaves
         * both rows visible, which the next sweep resolves again.
         */
        fun supersedeSql(kind: FindingKind) = """
            UPDATE ${kind.table}
            SET superseded_by = ?
            WHERE id = ?
              AND superseded_by IS NULL
              AND EXISTS (
                  SELECT 1 FROM ${kind.table} survivor
                  WHERE survivor.id = ? AND survivor.superseded_by IS NULL
              )
        """.trimIndent()

        /**
         * Record the pair, or replace what a cheaper tier had recorded about it.
         *
         * `DO UPDATE` rather than `DO NOTHING`, because the two tiers can reach one pair in either
         * order: the vectors rule it out under one vector version, the version moves, the pair
         * comes back, and the model answers. It runs the other way round too, now that a paid
         * verdict is retired by its own version moving: the model answers, the adjudication version
         * moves, the pair comes back, and the vectors rule it out.
         *
         * The later answer is the one to keep, and the row records which version gave it — so a
         * second write changes the row wherever the version has moved in between, and changes
         * nothing where it has not.
         */
        val INSERT_KEPT_APART = """
            INSERT INTO finding_kept_apart (kind, lower_id, upper_id, decided_by)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (kind, lower_id, upper_id) DO UPDATE SET
                decided_by = EXCLUDED.decided_by,
                decided_at = now()
        """.trimIndent()
    }
}
