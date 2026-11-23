package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPair
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPairs

/**
 * The advisory read of kept pairs, against the store.
 *
 * Its own class because it is its own port: a reader that can do nothing but read, handed to the
 * one surface that exists for a person rather than for a pass. It shares the `candidate` table
 * with `JdbcContactRepository` and nothing else — this never writes, and the pass never orders,
 * floors or names a pair.
 */
class JdbcUnresolvedPairs(private val database: Database) : UnresolvedPairs {

    /**
     * Strongest first, bounded, and with both sides' names joined on.
     *
     * The settled pairs are excluded in the statement rather than afterwards, so that [limit]
     * bounds the rows a reader gets rather than the rows considered — filtering after the limit
     * would return fewer than asked for and read as *there are no more*.
     */
    override suspend fun unresolved(floor: Double, limit: Int): List<UnresolvedPair> {
        if (limit < 1) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_UNRESOLVED).use { statement ->
                statement.bind {
                    double(floor)
                    int(limit)
                }
                statement.executeQuery().use { rows ->
                    rows.collect {
                        UnresolvedPair(
                            pair = it.toCandidate(),
                            oneName = it.getString("one_name"),
                            otherName = it.getString("other_name"),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * The kept pairs worth reading, named, strongest first.
         *
         * **A pair is excluded when its two sides resolve to one head**, which is the only thing
         * that means *these two are already one human*. Testing instead whether either side
         * carries a link answers a different question — whether anything was settled about that
         * side and whoever it was merged into — and excludes a pair naming a third contact that
         * nothing has decided anything about. That is a genuinely open question, and dropping it
         * would hide every open pair involving a non-canonical contact: on a phone-anchored corpus
         * those are exactly the name-only near misses hanging off the accounts a number merged, so
         * the bias would fall on the sample this surface exists to draw.
         *
         * So the chain is walked, as `heads` walks it. Depth-capped for the same reason: nothing
         * here can write a cycle, and a query that never returns is harder to diagnose than one
         * that answers. `merge_link` holds one row per absorbed contact, so the recursion is over
         * the merges rather than over the contacts.
         *
         * A pair a correction has separated is excluded too: a person has said these two are
         * different humans, which is the most resolved a pair gets. The one-sided comparison is
         * safe because `candidate` and `separation` both store the pair ordered.
         *
         * The ordering is total — score, then the pair's own identifiers — so that two pairs on the
         * same score come back in one order every time. Without the tiebreak a reader paging
         * through at one limit would see a different sample than at another, for no reason they
         * could see.
         */
        val SELECT_UNRESOLVED = """
            WITH RECURSIVE walked (contact, head, depth) AS (
                SELECT l.merged, l.canonical, 1
                FROM merge_link l
                UNION ALL
                SELECT w.contact, l.canonical, w.depth + 1
                FROM walked w
                JOIN merge_link l ON l.merged = w.head
                WHERE w.depth < 64
            ),
            resolved (contact, head) AS (
                SELECT DISTINCT ON (contact) contact, head
                FROM walked
                ORDER BY contact, depth DESC
            )
            SELECT c.one, c.other, c.score, c.signals,
                   one_side.display_name AS one_name, other_side.display_name AS other_name
            FROM candidate c
            JOIN contact one_side ON one_side.id = c.one
            JOIN contact other_side ON other_side.id = c.other
            WHERE c.score >= ?
              AND COALESCE((SELECT r.head FROM resolved r WHERE r.contact = c.one), c.one)
                  <> COALESCE((SELECT r.head FROM resolved r WHERE r.contact = c.other), c.other)
              AND NOT EXISTS (
                  SELECT 1 FROM separation s WHERE s.one = c.one AND s.other = c.other
              )
            ORDER BY c.score DESC, c.one, c.other
            LIMIT ?
        """.trimIndent()
    }
}
