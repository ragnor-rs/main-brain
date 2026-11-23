package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Cluster
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Separation
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.sql.Connection

class JdbcContactRepository(private val database: Database) : ContactRepository {

    /**
     * The identifier offered for a record that already has one is discarded, which is what the
     * `ON CONFLICT` below does by not naming `id`.
     *
     * That is the whole of re-run safety for identity. A caller cannot know which records are
     * new without asking, so it mints one for every record in its batch and relies on this to
     * ignore the ones that would renumber an entity other things are already holding.
     */
    override suspend fun upsert(contacts: List<Contact>) {
        if (contacts.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(UPSERT).use { statement ->
                contacts.forEach { contact ->
                    statement.bind {
                        uuid(contact.id.value)
                        externalId(contact.record)
                        string(contact.displayName)
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun of(records: List<ExternalId>): List<Contact> {
        if (records.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_RECORD).use { statement ->
                val wanted = records.distinct()
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeQuery().use { rows -> rows.collect { it.toContact() } }
            }
        }
    }

    /**
     * A link already stored is left exactly as it was, score and signals included.
     *
     * `DO NOTHING` rather than an update, because a stored link is a decision that was taken on
     * the evidence as it stood. Re-scoring it on every pass would rewrite the reason the merge
     * happened, and that reason is what someone undoing it reads.
     */
    override suspend fun merge(links: List<MergeLink>) {
        if (links.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(MERGE).use { statement ->
                links.forEach { link ->
                    statement.bind {
                        uuid(link.merged.value)
                        uuid(link.canonical.value)
                        double(link.score)
                        strings(link.signals.wire())
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun linkOf(contact: EntityId): MergeLink? = database.query { connection ->
        connection.readLink(contact)
    }

    /**
     * Both writes in one transaction, because either alone is wrong.
     *
     * The link going without the separation leaves the pair free to be merged again by the next
     * pass — the evidence is still in the corpus and still says what it said, so the correction
     * would survive until the pass next ran and no longer. The separation going without the link
     * leaves a pair recorded as both merged and forbidden.
     */
    override suspend fun unmerge(contact: EntityId) {
        database.transaction { connection ->
            val link = connection.readLink(contact)
            if (link != null) {
                connection.prepareStatement(UNLINK).use { statement ->
                    statement.bind { uuid(contact.value) }
                    statement.executeUpdate()
                }
                val separation = Separation.of(link.merged, link.canonical)
                connection.prepareStatement(SEPARATE).use { statement ->
                    statement.bind {
                        uuid(separation.one.value)
                        uuid(separation.other.value)
                    }
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun heads(contacts: List<EntityId>): Map<EntityId, EntityId> {
        if (contacts.isEmpty()) return emptyMap()
        val wanted = contacts.distinct()
        return database.query { connection ->
            connection.prepareStatement(HEADS).use { statement ->
                statement.bind { strings(wanted.map { it.toString() }) }
                val resolved = statement.executeQuery().use { rows ->
                    rows.collect {
                        requireNotNull(it.entityId("seed")) to requireNotNull(it.entityId("contact"))
                    }
                }.toMap()
                // The recursive term seeds itself, so every input should come back. Defaulting
                // anyway rather than trusting that: the port promises an entry per input, and a
                // caller reading this as a map would otherwise fail on a key it was told exists.
                wanted.associateWith { resolved[it] ?: it }
            }
        }
    }

    override suspend fun clusters(contacts: List<EntityId>): List<Cluster> {
        if (contacts.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(CLUSTERS).use { statement ->
                statement.bind { strings(contacts.distinct().map { it.toString() }) }
                statement.executeQuery().use { rows ->
                    rows.collect { requireNotNull(it.entityId("head")) to it.toContact() }
                        .groupBy({ (head, _) -> head }, { (_, member) -> member })
                        .map { (head, members) -> Cluster(head, members) }
                }
            }
        }
    }

    override suspend fun separations(among: List<EntityId>): List<Separation> {
        if (among.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_SEPARATIONS).use { statement ->
                val wanted = among.distinct().map { it.toString() }
                statement.bind {
                    strings(wanted)
                    strings(wanted)
                }
                statement.executeQuery().use { rows ->
                    rows.collect {
                        Separation(
                            one = requireNotNull(it.entityId("one")),
                            other = requireNotNull(it.entityId("other")),
                        )
                    }
                }
            }
        }
    }

    /**
     * A candidate already kept has its score replaced.
     *
     * The opposite of [merge]'s rule, deliberately: this is a running assessment of evidence
     * that is still growing, while a link is a decision that was taken at a moment.
     */
    override suspend fun keep(candidates: List<CandidateMerge>) {
        if (candidates.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(KEEP).use { statement ->
                candidates.forEach { candidate ->
                    statement.bind {
                        uuid(candidate.one.value)
                        uuid(candidate.other.value)
                        double(candidate.score)
                        strings(candidate.signals.wire())
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /**
     * Either side, not both: a pair is kept with its ordering, and a claim arriving for one
     * record has to find the pairs that record is in whichever side of them it sits on.
     */
    override suspend fun candidates(among: List<EntityId>): List<CandidateMerge> {
        if (among.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_CANDIDATES).use { statement ->
                val wanted = among.distinct().map { it.toString() }
                statement.bind {
                    strings(wanted)
                    strings(wanted)
                }
                statement.executeQuery().use { rows -> rows.collect { it.toCandidate() } }
            }
        }
    }

    private companion object {
        val UPSERT = """
            INSERT INTO contact (id, record_source, record_native_id, display_name)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (record_source, record_native_id) DO UPDATE SET
                display_name = EXCLUDED.display_name
        """.trimIndent()

        val SELECT_BY_RECORD = """
            SELECT c.id, c.record_source, c.record_native_id, c.display_name
            FROM contact c
            JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
              ON c.record_source = wanted.source AND c.record_native_id = wanted.native_id
        """.trimIndent()

        val MERGE = """
            INSERT INTO merge_link (merged, canonical, score, signals)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (merged) DO NOTHING
        """.trimIndent()

        val KEEP = """
            INSERT INTO candidate (one, other, score, signals)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (one, other) DO UPDATE SET
                score = EXCLUDED.score,
                signals = EXCLUDED.signals,
                scored_at = now()
        """.trimIndent()

        const val UNLINK = "DELETE FROM merge_link WHERE merged = ?"

        val SEPARATE = """
            INSERT INTO separation (one, other)
            VALUES (?, ?)
            ON CONFLICT (one, other) DO NOTHING
        """.trimIndent()

        /**
         * Follows every seed's chain of links to its head, in one pass.
         *
         * `DISTINCT ON` keeps the deepest row per seed, which is the head: the chain carries its
         * own seed down so that one query resolves a whole batch.
         *
         * Depth-capped, though nothing here can write a cycle — a pass links a non-head into a
         * head, so every arrow runs the same way. The cap is there because the cost of being
         * wrong about that is a query that never returns, and a service that hangs is harder to
         * diagnose than one that answers.
         */
        val HEADS = """
            WITH RECURSIVE chain (seed, contact, depth) AS (
                SELECT w.id, w.id, 0
                FROM unnest(?::uuid[]) AS w (id)
                UNION ALL
                SELECT chain.seed, l.canonical, chain.depth + 1
                FROM merge_link l
                JOIN chain ON l.merged = chain.contact
                WHERE chain.depth < 64
            )
            SELECT DISTINCT ON (seed) seed, contact
            FROM chain
            ORDER BY seed, depth DESC
        """.trimIndent()

        /**
         * The seeds resolved to their heads, and then every contact that resolves to one of those
         * heads — the clusters whole, rather than the inputs mapped.
         *
         * Two recursive walks in opposite directions, and both are needed. `up` follows
         * `merged -> canonical` to find each seed's head, as `HEADS` does. `down` follows
         * `canonical -> merged` from those heads back out, which is the half that returns members
         * nobody asked about — the ones a cannot-link constraint turns on.
         *
         * Both are depth-capped at the same 64 as `HEADS`, and for the same reason: a cycle in
         * `merge_link` is not supposed to be possible, and an uncapped walk would hang the pass
         * rather than report one.
         */
        val CLUSTERS = """
            WITH RECURSIVE up (seed, contact, depth) AS (
                SELECT w.id, w.id, 0
                FROM unnest(?::uuid[]) AS w (id)
                UNION ALL
                SELECT up.seed, l.canonical, up.depth + 1
                FROM merge_link l
                JOIN up ON l.merged = up.contact
                WHERE up.depth < 64
            ),
            resolved AS (
                SELECT DISTINCT ON (seed) seed, contact AS head
                FROM up
                ORDER BY seed, depth DESC
            ),
            down (member, head, depth) AS (
                SELECT DISTINCT r.head, r.head, 0
                FROM resolved r
                UNION ALL
                SELECT l.merged, down.head, down.depth + 1
                FROM merge_link l
                JOIN down ON l.canonical = down.member
                WHERE down.depth < 64
            )
            SELECT DISTINCT d.head, c.id, c.record_source, c.record_native_id, c.display_name
            FROM down d
            JOIN contact c ON c.id = d.member
        """.trimIndent()

        val SELECT_SEPARATIONS = """
            SELECT s.one, s.other
            FROM separation s
            WHERE s.one = ANY(?::uuid[]) AND s.other = ANY(?::uuid[])
        """.trimIndent()

        val SELECT_CANDIDATES = """
            SELECT c.one, c.other, c.score, c.signals
            FROM candidate c
            WHERE c.one = ANY(?::uuid[]) OR c.other = ANY(?::uuid[])
        """.trimIndent()
    }
}

/**
 * The link merging one contact into a head, read on its own and from inside `unmerge`.
 *
 * Top-level rather than a member, like the span reader in the index repository and the
 * conversation reader in the message one: the class sits at detekt's ceiling for how many
 * functions one class may hold, and a reader that uses nothing but its connection has no reason
 * to be inside it.
 */
private fun Connection.readLink(contact: EntityId): MergeLink? =
    prepareStatement(LINK_OF).use { statement ->
        statement.bind { uuid(contact.value) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.toLink() else null }
    }

private const val LINK_OF = "SELECT merged, canonical, score, signals FROM merge_link WHERE merged = ?"
