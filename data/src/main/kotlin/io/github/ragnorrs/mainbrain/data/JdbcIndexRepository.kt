package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.index.Hit
import io.github.ragnorrs.mainbrain.domain.index.IndexEntry
import io.github.ragnorrs.mainbrain.domain.index.IndexRepository
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.sql.Connection
import java.sql.ResultSet

/**
 * The index over Postgres, with both halves of the fused read in one statement.
 *
 * [DENSE_WIDTH] and [SPARSE_WIDTH] are the embedding model's, and the `V3` columns are
 * declared at exactly these. They are constants rather than configuration because a vector
 * index cannot hold two widths: changing either is a migration, which is the honest shape of
 * what changing the model costs.
 */
class JdbcIndexRepository(private val database: Database) : IndexRepository {

    override suspend fun index(entries: List<IndexEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { entry ->
            require(entry.embedding.dense.size == DENSE_WIDTH) {
                "The index holds ${DENSE_WIDTH}-dimension vectors and this one has " +
                    "${entry.embedding.dense.size}. A model of another width needs a migration, " +
                    "because one index cannot hold both."
            }
            require(entry.embedding.sparse.size <= SPARSE_TERM_LIMIT) {
                "A span carries ${entry.embedding.sparse.size} term weights and the index holds " +
                    "at most $SPARSE_TERM_LIMIT. The provider yields the strongest weights rather " +
                    "than every one; one that does not has to be capped before it reaches here."
            }
        }
        database.transaction { connection -> connection.writeSpans(entries) }
    }

    override suspend fun search(query: Embedding, limit: Int): List<Hit> {
        require(limit > 0) { "Asking for $limit results is not a search." }
        return database.query { connection -> connection.fusedSearch(query, limit) }
    }

    override suspend fun neighbours(span: SpanId, before: Int, after: Int): List<Span> {
        require(before >= 0 && after >= 0) { "A neighbour count is never negative." }
        // Only a conversation has an ordering to expand along, and only a message sits in one.
        // Any other kind has no neighbours to find, so the span itself is the whole answer.
        if (span.kind != RecordKind.MESSAGE) {
            return database.query { connection -> connection.readSpan(span) }
        }
        return database.query { connection -> connection.readNeighbours(span, before, after) }
    }

    private fun Connection.writeSpans(entries: List<IndexEntry>) {
        prepareStatement(UPSERT).use { statement ->
            entries.forEach { entry ->
                statement.bind {
                    table(RecordTable.of(entry.span.id.kind))
                    externalId(entry.span.id.record)
                    long(entry.span.id.ordinal.toLong())
                    string(entry.span.text)
                    instant(entry.span.sourceTime)
                    dense(entry.embedding.dense)
                    sparse(entry.embedding.sparse, SPARSE_WIDTH)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.fusedSearch(query: Embedding, limit: Int): List<Hit> {
        // A query with no term weights has no lexical half to fuse. Running the sparse side
        // anyway would not degrade gracefully: every row scores zero against an empty vector,
        // so the half returns an arbitrary set of spans carrying real ranks, and fusion then
        // lifts that noise above genuine vector matches. Vector-only is the honest answer.
        if (query.sparse.isEmpty()) return denseSearch(query, limit)
        return prepareStatement(SEARCH).use { statement ->
            // Each half is asked for more than the caller wants, because fusion only has
            // something to combine where the two rankings overlap. Asking each for exactly
            // `limit` would make the fused result the union of two short lists rather than a
            // ranking.
            val candidates = (limit.toLong() * CANDIDATE_FACTOR)
            statement.bind {
                dense(query.dense)
                long(candidates)
                sparse(query.sparse, SPARSE_WIDTH)
                long(candidates)
                long(RANK_CONSTANT)
                long(RANK_CONSTANT)
                long(limit.toLong())
            }
            statement.executeQuery().use { rows ->
                rows.collect { Hit(span = it.toSpan(), score = it.getDouble("score")) }
            }
        }
    }

    /** The vector half alone, for a query that carries no term weights. */
    private fun Connection.denseSearch(query: Embedding, limit: Int): List<Hit> =
        prepareStatement(SEARCH_DENSE_ONLY).use { statement ->
            // In the order the statement declares them: the rank constant appears in the
            // projection, which is read before the subquery it scores.
            statement.bind {
                long(RANK_CONSTANT)
                dense(query.dense)
                long(limit.toLong())
            }
            statement.executeQuery().use { rows ->
                rows.collect { Hit(span = it.toSpan(), score = it.getDouble("score")) }
            }
        }

    private fun Connection.readSpan(span: SpanId): List<Span> =
        prepareStatement(ONE_SPAN).use { statement ->
            statement.bind {
                table(RecordTable.of(span.kind))
                externalId(span.record)
                long(span.ordinal.toLong())
            }
            statement.executeQuery().use { rows -> rows.collect { it.toSpan() } }
        }

    private fun Connection.readNeighbours(span: SpanId, before: Int, after: Int): List<Span> =
        prepareStatement(NEIGHBOURS).use { statement ->
            statement.bind {
                // The anchor, which resolves only if the record is itself indexed.
                table(RecordTable.MESSAGE)
                externalId(span.record)
                table(RecordTable.MESSAGE)
                long(before.toLong())
                table(RecordTable.MESSAGE)
                // The later half includes the anchor itself, so it asks for one more than the
                // caller wanted after it. That is only correct because the anchor is now
                // guaranteed to be among them -- the `anchor` CTE requires its span to exist.
                long(after.toLong() + 1)
                table(RecordTable.MESSAGE)
            }
            statement.executeQuery().use { rows -> rows.collect { it.toSpan() } }
        }

    internal companion object {

        /** The dense width of the embedding model, and of the column `V12` declares for it. */
        const val DENSE_WIDTH = 768

        /** The model's vocabulary size, which is the declared width of the sparse column. */
        const val SPARSE_WIDTH = 250002

        /**
         * What pgvector will index in one `sparsevec`.
         *
         * Not a tuning choice — past this the write is refused outright, so a span whose text
         * produced more terms than this would fail to index rather than rank poorly.
         */
        const val SPARSE_TERM_LIMIT = 1000

        /**
         * The constant in reciprocal rank fusion, at its conventional value.
         *
         * It sets how quickly a result's contribution decays with its rank: large enough that
         * the top few places are not overwhelmingly weighted, small enough that deep results
         * still contribute almost nothing.
         */
        const val RANK_CONSTANT = 60L

        /** How many candidates each half yields per result asked for. */
        const val CANDIDATE_FACTOR = 4

        val UPSERT = """
            INSERT INTO span (record_kind, record_source, record_native_id, ordinal, body, source_time, dense, sparse)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?::sparsevec)
            ON CONFLICT (record_kind, record_source, record_native_id, ordinal) DO UPDATE SET
                body = EXCLUDED.body,
                source_time = EXCLUDED.source_time,
                dense = EXCLUDED.dense,
                sparse = EXCLUDED.sparse,
                indexed_at = now()
        """.trimIndent()

        /**
         * Lexical and vector, fused by reciprocal rank.
         *
         * Each half is ranked separately and the two are combined by summing `1/(k + rank)`.
         * Rank rather than score is what makes this work at all: a cosine distance and a
         * weighted term overlap are different quantities on different scales, and no fixed
         * weighting between them would mean anything. Ranks are comparable by construction.
         *
         * The `FULL OUTER JOIN` is the part that earns its keep. A span found by only one half
         * — an invoice number the vector side has no neighbourhood for, a paraphrase the
         * lexical side cannot see — still places in the fused ranking, scoring from the one
         * half that found it. An inner join would return only what both halves agreed on,
         * which is precisely the material neither mode is needed for.
         *
         * Each half orders inside its own subquery, where the parameter is a literal the
         * vector index can be scanned against; `row_number()` then reads that order off. A
         * query vector arriving through a join instead would leave the planner nothing
         * constant to probe the index with.
         */
        val SEARCH = """
            WITH dense_hits AS (
                SELECT id, row_number() OVER () AS rank
                FROM (SELECT id FROM span ORDER BY dense <=> ?::vector LIMIT ?) ranked
            ),
            sparse_hits AS (
                SELECT id, row_number() OVER () AS rank
                FROM (SELECT id FROM span ORDER BY sparse <#> ?::sparsevec LIMIT ?) ranked
            ),
            fused AS (
                SELECT COALESCE(d.id, s.id) AS id,
                       COALESCE(1.0 / (? + d.rank), 0) + COALESCE(1.0 / (? + s.rank), 0) AS score
                FROM dense_hits d
                FULL OUTER JOIN sparse_hits s ON d.id = s.id
            )
            SELECT sp.record_kind, sp.record_source, sp.record_native_id, sp.ordinal,
                   sp.body, sp.source_time, f.score
            FROM fused f
            JOIN span sp ON sp.id = f.id
            ORDER BY f.score DESC, sp.id
            LIMIT ?
        """.trimIndent()

        /**
         * The vector half alone, scored on the same scale as the fused read.
         *
         * The same reciprocal rank rather than the raw distance, so a caller cannot tell the
         * two paths apart by the shape of the numbers — the score means an ordering either
         * way, and nothing downstream should start depending on one being a distance.
         */
        val SEARCH_DENSE_ONLY = """
            SELECT sp.record_kind, sp.record_source, sp.record_native_id, sp.ordinal,
                   sp.body, sp.source_time,
                   1.0 / (? + row_number() OVER ()) AS score
            FROM (SELECT * FROM span ORDER BY dense <=> ?::vector LIMIT ?) sp
        """.trimIndent()

        val ONE_SPAN = """
            SELECT record_kind, record_source, record_native_id, ordinal, body, source_time
            FROM span
            WHERE record_kind = ? AND record_source = ? AND record_native_id = ? AND ordinal = ?
        """.trimIndent()

        /**
         * The spans around one message, in its own conversation.
         *
         * Ordered by the corpus's own `(source_time, id)` rather than by anything the index
         * stores, which is what "expanded at read time from the conversation ordering already
         * stored" means in practice — the index holds no adjacency of its own, so a message
         * arriving late into the middle of a history is in the right place immediately.
         *
         * The anchor requires its *span* to exist, not merely its message. Anchoring on the
         * message alone would answer for a record the index does not hold — returning its
         * neighbours while the hit itself was absent, and spending the later half's budget on
         * an extra span because the anchor it reserves a place for was never there.
         *
         * The pair comparison is what makes two messages sharing a source time deterministic;
         * the surrogate key breaks the tie the same way in both directions.
         *
         * **A withdrawn message is excluded, as it is in every read that fetches a body.**
         * Withdrawal marks the message row and leaves the span, so the index keeps a body the
         * corpus has been told not to quote — and this read is the one that hands a body back.
         * Filtered inside the two windows rather than only in the projection, so a withdrawn turn
         * does not spend a slot the caller asked to be filled with turns it may read.
         *
         * **The anchor is deliberately not filtered.** A caller holding a finding holds the span it
         * was read out of, and withdrawing that message does not retract the finding; refusing to
         * resolve the anchor would drop the surrounding turns as well, which are not withdrawn.
         * The withdrawn body still cannot come back, because the anchor CTE projects no body and
         * the windows exclude it.
         */
        val NEIGHBOURS = """
            WITH anchor AS (
                SELECT m.conversation_id, m.source_time, m.id
                FROM message m
                JOIN span anchor_span
                  ON anchor_span.record_kind = ?
                 AND anchor_span.record_source = m.source
                 AND anchor_span.record_native_id = m.native_id
                WHERE m.source = ? AND m.native_id = ?
            ),
            earlier AS (
                SELECT sp.id
                FROM span sp
                JOIN message m ON m.source = sp.record_source AND m.native_id = sp.record_native_id
                CROSS JOIN anchor a
                WHERE sp.record_kind = ?
                  AND m.conversation_id = a.conversation_id
                  AND (m.source_time, m.id) < (a.source_time, a.id)
                  AND m.withdrawn_at IS NULL
                ORDER BY m.source_time DESC, m.id DESC
                LIMIT ?
            ),
            later AS (
                SELECT sp.id
                FROM span sp
                JOIN message m ON m.source = sp.record_source AND m.native_id = sp.record_native_id
                CROSS JOIN anchor a
                WHERE sp.record_kind = ?
                  AND m.conversation_id = a.conversation_id
                  AND (m.source_time, m.id) >= (a.source_time, a.id)
                  AND m.withdrawn_at IS NULL
                ORDER BY m.source_time, m.id
                LIMIT ?
            )
            SELECT sp.record_kind, sp.record_source, sp.record_native_id, sp.ordinal,
                   sp.body, sp.source_time
            FROM span sp
            JOIN message m ON m.source = sp.record_source AND m.native_id = sp.record_native_id
            WHERE sp.record_kind = ?
              AND sp.id IN (SELECT id FROM earlier UNION SELECT id FROM later)
            ORDER BY m.source_time, m.id
        """.trimIndent()
    }
}

/** A span read from the columns every statement in this repository projects. */
private fun ResultSet.toSpan(): Span = Span(
    id = SpanId(
        kind = RecordKind.valueOf(getString("record_kind").uppercase()),
        record = requireNotNull(externalId("record_source", "record_native_id")),
        ordinal = getInt("ordinal"),
    ),
    text = getString("body"),
    sourceTime = requireNotNull(instant("source_time")),
)
