package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.StatementRepository
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.entity.ValueSynonym
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Statements over Postgres, keyed by their content.
 *
 * Two tables and one write. A fact goes into `statement` under its content key and each of its
 * spans into `statement_evidence`, inside one transaction — a fact with no evidence is not
 * representable in the domain, so a half-finished write has to be impossible rather than merely
 * unlikely.
 */
// One function past the per-class cap, which is six port members each with a reader or writer beside
// it. The shape in this module is one class per table, and splitting it would put half of this
// table's access somewhere else for a reader to find.
@Suppress("TooManyFunctions")
class JdbcStatementRepository(private val database: Database) : StatementRepository {

    /**
     * The writes first, then the removal of what they superseded, in one transaction.
     *
     * That order is the safe one if the transaction does not complete: a crash between the two
     * leaves a superseded row in place, which is a duplicate the next pass removes, where the
     * reverse order would leave a hole until something re-derived it. It is also what lets the
     * surviving statement carry the union of the evidence — the rows removed have already had
     * their spans attached to the fact that replaced them.
     */
    override suspend fun record(statements: List<Statement>): Stored {
        if (statements.isEmpty()) return Stored.NOTHING
        return database.transaction { connection -> connection.write(statements, UPSERT_FACT) }
    }

    /**
     * The same two writes, through the one statement that lets an offered close replace a stored one.
     *
     * The whole of the difference is `UPSERT_RECOLLAPSED`'s `valid_until`, and the reason it is a
     * second statement rather than a flag is that the rule it relaxes is load-bearing everywhere
     * else — see [StatementRepository.recollapsed].
     */
    override suspend fun recollapsed(statements: List<Statement>): Stored {
        if (statements.isEmpty()) return Stored.NOTHING
        return database.transaction { connection -> connection.write(statements, UPSERT_RECOLLAPSED) }
    }

    /**
     * The two writes and the removal, and what the three of them did to the rows.
     *
     * One body for both callers because the whole of the difference between them is [upsert], which
     * was already the case before either answered with a figure: a second copy of this would be two
     * places for the accounting to be wrong in, differing in nothing but a string.
     *
     * The removal is a statement of its own rather than an argument, so that the order the two
     * callers' documentation promises — writes, then removal — is the order a reader sees here
     * instead of a consequence of where a call sits in an argument list.
     */
    private fun Connection.write(statements: List<Statement>, upsert: String): Stored {
        var written = 0
        statements.forEach { statement ->
            val row = writeFact(statement, upsert)
            if (row.inserted) written++
            writeEvidence(row.id, statement.evidence)
        }
        // The figure no caller can produce: the rows removed are the ones this port holds and was
        // not given, so they are known here and nowhere else.
        val superseded = removeSuperseded(statements)
        return Stored(written = written, overwritten = statements.size - written, superseded = superseded)
    }

    override suspend fun about(subjects: List<EntityId>): List<Statement> {
        if (subjects.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_SUBJECT).use { statement ->
                statement.bind { strings(subjects.distinct().map { it.value.toString() }) }
                statement.executeQuery().use { rows -> rows.toStatements() }
            }
        }
    }

    override suspend fun synonymsOf(subjects: List<EntityId>): List<ValueSynonym> {
        if (subjects.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_SYNONYMS).use { statement ->
                statement.bind { strings(subjects.distinct().map { it.value.toString() }) }
                statement.executeQuery().use { rows ->
                    rows.collect { row ->
                        ValueSynonym(
                            subject = requireNotNull(row.entityId("subject")),
                            predicate = Predicate.of(row.getString("predicate")),
                            one = row.getString("lower_value"),
                            other = row.getString("upper_value"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun valueCandidates(
        limit: Int,
        comparedBy: String,
        adjudicatedBy: String,
    ): List<ValuePair> {
        require(limit > 0) { "A batch of $limit pairs compares nothing." }
        return database.query { connection ->
            connection.prepareStatement(SELECT_VALUE_CANDIDATES).use { statement ->
                // The two versions whose verdicts the exclusion honours, then the bound, in the
                // order the statement declares them.
                statement.bind {
                    string(adjudicatedBy)
                    string(comparedBy)
                    int(limit)
                }
                statement.executeQuery().use { rows ->
                    rows.collect { row ->
                        ValuePair(
                            subject = requireNotNull(row.entityId("subject")),
                            predicate = Predicate.of(row.getString("predicate")),
                            one = row.getString("lower_value"),
                            other = row.getString("upper_value"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun valueCandidateCount(comparedBy: String, adjudicatedBy: String): Int =
        database.query { connection ->
            connection.prepareStatement(COUNT_VALUE_CANDIDATES).use { statement ->
                statement.bind {
                    string(adjudicatedBy)
                    string(comparedBy)
                }
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "A count query returned no row." }
                    rows.getInt(1)
                }
            }
        }

    override suspend fun decide(pair: ValuePair, same: Boolean, decidedBy: String) {
        require(decidedBy.isNotBlank()) { "A record of what decided two values cannot be nameless." }
        database.transaction { connection ->
            connection.prepareStatement(UPSERT_VALUE_VERDICT).use { statement ->
                // The two values go over twice and SQL picks the order, because ordering text is a
                // question of collation: a caller sorting them with its own comparison would
                // disagree with the index this has to satisfy, and one pair would become two rows
                // that neither asking could find.
                statement.bind {
                    uuid(pair.subject.value)
                    string(pair.predicate.name)
                    string(pair.one)
                    string(pair.other)
                    string(pair.one)
                    string(pair.other)
                    boolean(same)
                    string(decidedBy)
                }
                statement.executeUpdate()
            }
        }
    }

    /**
     * The surrogate key of [statement]'s row, and whether this write is what inserted it.
     *
     * [upsert] decides what happens to a stored close, and nothing else differs between the two
     * callers: [UPSERT_FACT] keeps it, [UPSERT_RECOLLAPSED] takes the offered one. Which of insert
     * and update happened is read off the row rather than guessed at — see [INSERTED].
     */
    private fun Connection.writeFact(statement: Statement, upsert: String): Fresh =
        prepareStatement(upsert).use { upsert ->
            upsert.bind {
                uuid(statement.subject.value)
                string(statement.predicate.name)
                string(statement.value)
                instant(statement.validity.from)
                instant(statement.validity.until)
                string(statement.origin.name.lowercase())
            }
            upsert.executeQuery().use { rows ->
                check(rows.next()) {
                    "A statement upsert returned no row. `DO UPDATE` returns the conflicting row " +
                        "where `DO NOTHING` returns none, so this means the statement is not the " +
                        "one written above."
                }
                Fresh(rows.getLong("id"), rows.getBoolean(INSERTED))
            }
        }

    /** [Upserted] for a table keyed by a surrogate: the same two columns, read as a `bigint`. */
    private class Fresh(val id: Long, val inserted: Boolean)

    /**
     * Remove the stored statements under these subjects and predicates that [statements] replaces.
     *
     * Three plain statements rather than one clever one. The identifiers are selected first
     * because both deletes need them and because a `DELETE … RETURNING` feeding another delete
     * inside one command leaves the order the foreign key is checked in up to the planner; here
     * the evidence goes first and the facts after, which is the order the constraint requires and
     * the only order this code can be read as doing.
     *
     * Scoped to the `(subject, predicate)` pairs the argument covers, so a pass writing one
     * predicate cannot disturb another — see [StatementRepository.record], where that is the
     * contract rather than an implementation detail.
     *
     * Answers with how many rows it removed, which is the port's [Stored.superseded]. The count is
     * the selected identifiers and not an update count, because the two deletes run over the same
     * list and the second of them is the one that removes facts: summing both would report a fact
     * once per span of evidence it carried.
     */
    private fun Connection.removeSuperseded(statements: List<Statement>): Int {
        val superseded = supersededBy(statements)
        if (superseded.isEmpty()) return 0
        listOf(DELETE_SUPERSEDED_EVIDENCE, DELETE_SUPERSEDED).forEach { sql ->
            prepareStatement(sql).use { delete ->
                delete.bind { strings(superseded.map { it.toString() }) }
                delete.executeUpdate()
            }
        }
        return superseded.size
    }

    /** The surrogate keys of the rows [statements] supersedes, which may be none. */
    private fun Connection.supersededBy(statements: List<Statement>): List<Long> =
        prepareStatement(SELECT_SUPERSEDED).use { select ->
            select.bindKept(statements)
            select.executeQuery().use { rows -> rows.collect { it.getLong("id") } }
        }

    private fun Connection.writeEvidence(fact: Long, evidence: Set<Evidence>) {
        prepareStatement(INSERT_EVIDENCE).use { insert ->
            evidence.forEach { piece ->
                insert.bind {
                    long(fact)
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
         * Write the fact, or find the one already stored, and return its key either way.
         *
         * Two columns are merged rather than taken from the offered row, and both rules are
         * one-directional.
         *
         * **A stored close is never reopened, but it may move.** `valid_until` is outside the key
         * so that closing a window is an update rather than a delete, and the `COALESCE` takes
         * the offered close when there is one and keeps the stored close when there is not. So a
         * pass re-deriving an old fact in a batch that happens not to hold the later,
         * contradicting one cannot reopen a window that was correctly shut — and a backfill that
         * lands a contradiction *between* two stored windows can still narrow the earlier one,
         * which it must: resolution computes that close, and refusing it would leave two
         * statements covering the instants between them.
         *
         * **An origin is only ever strengthened.** A person's correction outranks a source's
         * structure, which outranks a model's reading, and recognition may not overwrite a
         * correction — so a re-derivation arriving with a weaker origin leaves the stored one
         * alone. The array repeats `Origin`'s declaration order and the `CHECK` constraint's
         * list; what keeps the three in step is a test that a re-derivation cannot downgrade a
         * confirmed fact.
         */
        val UPSERT_FACT = """
            INSERT INTO statement (subject, predicate, value, valid_from, valid_until, origin)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (subject, predicate, value, valid_from) DO UPDATE SET
                valid_until = COALESCE(EXCLUDED.valid_until, statement.valid_until),
                origin = CASE
                    WHEN array_position(ARRAY['inferred', 'labelled', 'confirmed'], EXCLUDED.origin) >
                         array_position(ARRAY['inferred', 'labelled', 'confirmed'], statement.origin)
                    THEN EXCLUDED.origin
                    ELSE statement.origin
                END
            RETURNING id, (xmax = 0) AS inserted
        """.trimIndent()

        /**
         * [UPSERT_FACT] with the offered close taken rather than merged.
         *
         * **The one write that may reopen a window, and it is the collapse's.** `EXCLUDED` instead
         * of `COALESCE`, so a window resolution has recomputed as still open comes back open. What
         * that gives up is the protection `UPSERT_FACT`'s `COALESCE` provides — and the caller is
         * what replaces it: a synonym verdict establishes that the two values either side of the
         * close are one wording of one fact, so the close was this layer's own reading of a
         * contradiction that was never there. See [StatementRepository.recollapsed].
         *
         * The origin rule is unchanged and deliberately so: a correction still outranks a model's
         * reading, and nothing about re-windowing two wordings bears on which of them is stronger.
         *
         * **Written out rather than derived from [UPSERT_FACT] by substitution.** A `replace` of
         * one clause for another silently does nothing once the text it looks for moves, and what
         * it would leave behind is this statement being the other one — the close kept, the fix
         * reverted, and no test failing to say so.
         */
        val UPSERT_RECOLLAPSED = """
            INSERT INTO statement (subject, predicate, value, valid_from, valid_until, origin)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (subject, predicate, value, valid_from) DO UPDATE SET
                valid_until = EXCLUDED.valid_until,
                origin = CASE
                    WHEN array_position(ARRAY['inferred', 'labelled', 'confirmed'], EXCLUDED.origin) >
                         array_position(ARRAY['inferred', 'labelled', 'confirmed'], statement.origin)
                    THEN EXCLUDED.origin
                    ELSE statement.origin
                END
            RETURNING id, (xmax = 0) AS inserted
        """.trimIndent()

        /**
         * Attach a span to a fact, or leave the attachment alone.
         *
         * `DO NOTHING` rather than an update, because there is nothing to update: the row is its
         * own key apart from the source time, and a span's source time is the corpus's to change.
         * Overlapping recognition windows read the same span repeatedly and this is where that
         * costs nothing.
         */
        val INSERT_EVIDENCE = """
            INSERT INTO statement_evidence
                (statement, record_kind, record_source, record_native_id, ordinal, source_time)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (statement, record_kind, record_source, record_native_id, ordinal) DO NOTHING
        """.trimIndent()

        /**
         * The stored statements under the written pairs that the written set does not contain.
         *
         * `kept` is the content key of everything being written, as four parallel arrays. The
         * pairs it touches come from the same arrays rather than from a second parameter, which
         * is what keeps the two from disagreeing: a pair in the scope but not in the keys would
         * delete every statement under it.
         *
         * `valid_from` is compared as a timestamp and not as text. The same instant can be
         * written more than one way — a trailing `Z` against an offset, a different number of
         * fractional digits — and a textual comparison would read two spellings of one key as two
         * keys, which here means deleting the row that is being written.
         */
        val SELECT_SUPERSEDED = """
            WITH kept AS (
                SELECT *
                FROM unnest(?::uuid[], ?::text[], ?::text[], ?::timestamptz[])
                    AS k (subject, predicate, value, valid_from)
            )
            SELECT s.id
            FROM statement s
            JOIN (SELECT DISTINCT subject, predicate FROM kept) touched
              ON s.subject = touched.subject AND s.predicate = touched.predicate
            WHERE NOT EXISTS (
                SELECT 1
                FROM kept k
                WHERE k.subject = s.subject
                  AND k.predicate = s.predicate
                  AND k.value = s.value
                  AND k.valid_from = s.valid_from
            )
        """.trimIndent()

        /** The evidence of the superseded rows, which goes first or the foreign key refuses. */
        const val DELETE_SUPERSEDED_EVIDENCE =
            "DELETE FROM statement_evidence WHERE statement = ANY(?::bigint[])"

        const val DELETE_SUPERSEDED = "DELETE FROM statement WHERE id = ANY(?::bigint[])"

        /**
         * Every statement about these subjects, with its evidence, ordered.
         *
         * An inner join, so a fact carrying no evidence is not returned — it cannot be built in
         * the domain, and the only way to have one is a write that went round this repository.
         *
         * Ordered by subject, predicate and window start so that a caller comparing windows reads
         * them in the order they held. The surrogate key breaks the tie, which only two facts
         * differing in value alone can reach.
         */
        val SELECT_BY_SUBJECT = """
            SELECT s.id, s.subject, s.predicate, s.value, s.valid_from, s.valid_until, s.origin,
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM statement s
            JOIN statement_evidence e ON e.statement = s.id
            JOIN unnest(?::uuid[]) AS wanted (subject) ON s.subject = wanted.subject
            ORDER BY s.subject, s.predicate, s.valid_from, s.id
        """.trimIndent()

        /** Only the verdicts that say two wordings are one fact; the rest tell resolution nothing. */
        val SELECT_SYNONYMS = """
            SELECT v.subject, v.predicate, v.lower_value, v.upper_value
            FROM statement_value_verdict v
            JOIN unnest(?::uuid[]) AS wanted (subject) ON v.subject = wanted.subject
            WHERE v.same
        """.trimIndent()

        /**
         * Pairs of values under one subject and predicate that no verdict covers.
         *
         * **Distinct values and not rows.** One value can hold over two separate periods, so a
         * self-join over `statement` would offer the same pair of wordings once per pair of
         * windows; what is being decided is about the two strings, so the values are reduced first.
         *
         * `b.value > a.value` makes one unordered pair appear once and orders the two the way the
         * verdict table stores them, so the exclusion is a lookup on the same collation that index
         * is built with — which is the whole reason the ordering is done here and not in Kotlin.
         *
         * Oldest subject first, so an interrupted deployment makes the same progress rather than
         * revisiting whatever the planner picked.
         */
        val SELECT_VALUE_CANDIDATES = """
            WITH held AS (
                SELECT DISTINCT subject, predicate, value, min(valid_from) AS first_held
                FROM statement
                GROUP BY subject, predicate, value
            )
            SELECT a.subject, a.predicate, a.value AS lower_value, b.value AS upper_value
            FROM held a
            JOIN held b
              ON b.subject = a.subject
             AND b.predicate = a.predicate
             AND b.value > a.value
            WHERE NOT EXISTS (
                SELECT 1
                FROM statement_value_verdict v
                WHERE v.subject = a.subject
                  AND v.predicate = a.predicate
                  AND v.lower_value = a.value
                  AND v.upper_value = b.value
                  AND v.decided_by IN (?, ?)
            )
            ORDER BY least(a.first_held, b.first_held), a.subject, a.value, b.value
            LIMIT ?
        """.trimIndent()

        /**
         * How many value pairs the candidate query would offer, unbounded.
         *
         * The same predicate as [SELECT_VALUE_CANDIDATES] with the ordering and the limit removed,
         * which is the one thing it has to stay in step with: counted over a different condition,
         * the driver would read progress where there was none.
         */
        val COUNT_VALUE_CANDIDATES = """
            WITH held AS (
                SELECT DISTINCT subject, predicate, value, min(valid_from) AS first_held
                FROM statement
                GROUP BY subject, predicate, value
            )
            SELECT count(*)
            FROM held a
            JOIN held b
              ON b.subject = a.subject
             AND b.predicate = a.predicate
             AND b.value > a.value
            WHERE NOT EXISTS (
                SELECT 1
                FROM statement_value_verdict v
                WHERE v.subject = a.subject
                  AND v.predicate = a.predicate
                  AND v.lower_value = a.value
                  AND v.upper_value = b.value
                  AND v.decided_by IN (?, ?)
            )
        """.trimIndent()

        /**
         * Record what decided the pair, replacing whatever a retired version had decided about it.
         *
         * `LEAST` and `GREATEST` are what put the pair in the order the index stores it, using the
         * database's own collation — the same comparison the `CHECK` is written with, so a row that
         * satisfies one satisfies the other.
         *
         * `DO UPDATE` rather than `DO NOTHING`, because the two tiers reach one pair in either
         * order once a version moves: the vectors rule it out, the bound they weighed it against
         * moves, the pair comes back, and the model answers — or the reverse. The later answer is
         * the one to keep, and the row records which version gave it, so a second write changes the
         * row wherever a version has moved in between and changes nothing where none has. That is
         * also what makes it safe to repeat, which a collection re-read after a failure does.
         */
        val UPSERT_VALUE_VERDICT = """
            INSERT INTO statement_value_verdict
                (subject, predicate, lower_value, upper_value, same, decided_by)
            VALUES (?, ?, LEAST(?, ?), GREATEST(?, ?), ?, ?)
            ON CONFLICT (subject, predicate, md5(lower_value), md5(upper_value)) DO UPDATE SET
                same = EXCLUDED.same,
                decided_by = EXCLUDED.decided_by,
                decided_at = now()
        """.trimIndent()
    }
}

/**
 * A fact's own columns, without the evidence the join carries beside them.
 *
 * A step between the result set and [Statement] because a statement cannot be built a row at a
 * time: it requires its evidence, and the join hands that over one span per row. This holds the
 * part that is the same on every row of a group, so the grouping below builds each fact once.
 */
private class Fact(
    val subject: EntityId,
    val predicate: Predicate,
    val value: String,
    val validity: Validity,
    val origin: Origin,
) {
    fun with(evidence: Set<Evidence>) = Statement(subject, predicate, value, validity, origin, evidence)
}

/**
 * The joined rows read back as statements, one per fact rather than one per span.
 *
 * The join multiplies a corroborated fact by its evidence, so this is where those rows collapse
 * back into the single statement they all evidence. Grouped by the surrogate key rather than by
 * the content key: it is the same grouping with less to get wrong.
 */
internal fun ResultSet.toStatements(): List<Statement> =
    collect { row -> row.getLong("id") to (row.toFact() to row.toEvidence()) }
        .groupBy({ (id, _) -> id }, { (_, parts) -> parts })
        .map { (_, rows) -> rows.first().first.with(rows.map { (_, evidence) -> evidence }.toSet()) }

/**
 * The content key of everything being written, as the four parallel arrays `SELECT_SUPERSEDED`
 * reads.
 *
 * Parallel arrays rather than a placeholder per statement, for the reason `bindSpans` gives: the
 * count is the size of the batch, so a statement built per call would be a new query plan each
 * time. The instants go over as text and the statement casts the array to `timestamptz[]`,
 * because the binder has one array type.
 */
private fun PreparedStatement.bindKept(statements: List<Statement>) = bind {
    strings(statements.map { it.subject.value.toString() })
    strings(statements.map { it.predicate.name })
    strings(statements.map { it.value })
    strings(statements.map { it.validity.from.toString() })
}

private fun ResultSet.toFact() = Fact(
    subject = requireNotNull(entityId("subject")),
    predicate = Predicate(getString("predicate")),
    value = getString("value"),
    validity = Validity(from = requireNotNull(instant("valid_from")), until = instant("valid_until")),
    origin = Origin.valueOf(getString("origin").uppercase()),
)
