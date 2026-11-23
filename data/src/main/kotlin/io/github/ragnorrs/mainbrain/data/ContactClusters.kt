package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import java.sql.Connection
import java.util.UUID

/**
 * Resolving a contact identifier through `merge_link`, which is what makes one name a human.
 *
 * **A contact row is an account, and a human can be several of them.** Recognition records that by
 * linking the non-head rows into a head rather than rewriting anything, so an identifier that was
 * correct when it was handed out keeps pointing at the row it named. Every read that narrows by
 * contact therefore has to resolve: asking for the row is asking for one of the accounts, and the
 * answer silently omits whatever the same human said under the others.
 *
 * Both directions are here, because the answer path needs both. [headsOf] maps an identifier to the
 * one that stands for the human, which is what a candidate should name. [clustersOf] expands an
 * identifier to every row that resolves to its head, which is what a narrowing has to match on —
 * the corpus is keyed by the account that wrote each message, not by the head — and names the head
 * each row resolves to, so a read that bounds per human can group by it. [selfClusters] asks one
 * question of the second walk: whether a human is the account the corpus was read through, which is
 * a property of the cluster and not of any one row in it.
 *
 * The walks are the same ones `JdbcContactRepository` uses on the recognition side, under the same
 * depth cap and for the same reason: nothing can write a cycle, because a pass links a non-head into
 * a head and every arrow runs one way, but an uncapped walk would hang rather than report if that
 * ever stopped being true.
 */

/**
 * Each of [contacts] mapped to the identifier that stands for its human.
 *
 * An identifier that is already a head maps to itself, so the result is total over the input and a
 * caller never has to decide what an absent key meant.
 */
internal fun Connection.headsOf(contacts: List<EntityId>): Map<EntityId, EntityId> {
    if (contacts.isEmpty()) return emptyMap()
    return prepareStatement(HEADS_OF).use { statement ->
        statement.bind { strings(contacts.distinct().map { it.value.toString() }) }
        statement.executeQuery().use { rows ->
            rows.collect {
                EntityId(it.getObject("seed", UUID::class.java)) to
                    EntityId(it.getObject("head", UUID::class.java))
            }.toMap()
        }
    }
}

/**
 * Every contact row that resolves to the same human as one of [contacts], mapped to its head.
 *
 * Includes the inputs themselves, so this is only ever a widening — a caller can bind the keys where
 * it used to bind the seeds and a corpus with no merges behaves exactly as before. The head is
 * carried because a read that bounds its result per human has to group by something, and the row is
 * the wrong thing: three accounts would take three allowances.
 *
 * One row per member, not per seed: the mapping is functional, since `merge_link.merged` is its own
 * primary key and so a row has at most one head.
 */
internal fun Connection.clustersOf(contacts: List<EntityId>): Map<EntityId, EntityId> {
    if (contacts.isEmpty()) return emptyMap()
    return prepareStatement(MEMBERS_OF).use { statement ->
        statement.bind { strings(contacts.distinct().map { it.value.toString() }) }
        statement.executeQuery().use { rows ->
            rows.collect {
                EntityId(it.getObject("member", UUID::class.java)) to
                    EntityId(it.getObject("head", UUID::class.java))
            }.toMap()
        }
    }
}

/**
 * Which of these humans is the account the corpus was read through.
 *
 * **Asked of a head and answered over its whole membership: a human is the account read through
 * where *any* of their accounts is.** That is what makes several accounts one owner without a
 * second concept, and it is why this cannot be a column on `contact` read row by row — merging two
 * marked accounts would then produce two answers for one human, and merging a marked account into
 * an unmarked head would produce none.
 *
 * Takes heads rather than arbitrary identifiers, so only the downward walk is needed: every caller
 * has already resolved what it holds through [headsOf] to name the human at all. A head that is
 * nobody's canonical row is a cluster of one and answers for itself.
 *
 * Absent from the result means *not the account read through*, which folds the two false answers
 * together — a record marked false and one nothing has established. Nothing downstream distinguishes
 * them: a question is about somebody or it is not, and *we do not know whether you are asking about
 * yourself* is not a state anything could act on.
 */
internal fun Connection.selfClusters(heads: List<EntityId>): Set<EntityId> {
    if (heads.isEmpty()) return emptySet()
    return prepareStatement(SELF_CLUSTERS).use { statement ->
        statement.bind { strings(heads.distinct().map { it.value.toString() }) }
        statement.executeQuery().use { rows ->
            rows.collect { EntityId(it.getObject("head", UUID::class.java)) }.toSet()
        }
    }
}

/**
 * Which of [heads] the corpus has answered the ownership question for, either way.
 *
 * **The fold [selfClusters] makes is right for its own reader and wrong for one that states the
 * denial.** That read folds *marked false* together with *nothing established*, because a reply is
 * composed as this person or it is not and there is no third thing to do. A caller that renders the
 * negative as a claim about a turn has a third thing to do — say nothing — and needs the two apart,
 * or an account whose adapter never reported itself is published as somebody else's.
 *
 * So this answers the prior question: has anything established whose account this is? Paired with
 * [selfClusters], a head in neither is unknown, a head in both is the reader's, and a head in this
 * alone is established as somebody else. `self` is three-valued on the record for exactly that
 * reason — see the column's own migration.
 *
 * Asked of a head and answered over its whole membership, as [selfClusters] is and for the same
 * reason: a human is one account answering for several, so a sibling row left unset must not deny
 * what another row of the same cluster establishes.
 */
internal fun Connection.selfKnownClusters(heads: List<EntityId>): Set<EntityId> {
    if (heads.isEmpty()) return emptySet()
    return prepareStatement(SELF_KNOWN_CLUSTERS).use { statement ->
        statement.bind { strings(heads.distinct().map { it.value.toString() }) }
        statement.executeQuery().use { rows ->
            rows.collect { EntityId(it.getObject("head", UUID::class.java)) }.toSet()
        }
    }
}

/**
 * Follows `merged -> canonical` from every seed to its head, in one pass.
 *
 * `DISTINCT ON` keeps the deepest row per seed, which is the head: the chain carries its own seed
 * down so that one query resolves a whole batch.
 */
private val HEADS_OF = """
    WITH RECURSIVE up (seed, contact, depth) AS (
        SELECT w.id, w.id, 0
        FROM unnest(?::uuid[]) AS w (id)
        UNION ALL
        SELECT up.seed, l.canonical, up.depth + 1
        FROM merge_link l
        JOIN up ON l.merged = up.contact
        WHERE up.depth < 64
    )
    SELECT DISTINCT ON (seed) seed, contact AS head
    FROM up
    ORDER BY seed, depth DESC
""".trimIndent()

/**
 * The seeds resolved to their heads, then every row that resolves back to one of those heads.
 *
 * Two walks in opposite directions, and both are needed. Up finds the head, which the seed may not
 * be. Down returns the siblings — the accounts nobody asked about, which are exactly the ones whose
 * messages a narrowing on the seed alone would miss.
 */
/**
 * Walks down from each head and keeps the heads whose membership reaches a marked person record.
 *
 * The join to `person` is on the source's own identifier pair, which is what a contact row holds —
 * and it is an inner join on purpose: a contact whose person record the corpus no longer holds
 * cannot answer this question, and an outer join would have it answer *no* instead.
 *
 * `self` is three-valued, so the predicate is the column itself rather than a comparison
 * against false: null is *nothing established* and must not be read as a denial.
 */
private val SELF_CLUSTERS = """
    WITH RECURSIVE down (member, head, depth) AS (
        SELECT w.id, w.id, 0
        FROM unnest(?::uuid[]) AS w (id)
        UNION ALL
        SELECT l.merged, down.head, down.depth + 1
        FROM merge_link l
        JOIN down ON l.canonical = down.member
        WHERE down.depth < 64
    )
    SELECT DISTINCT down.head
    FROM down
    JOIN contact c ON c.id = down.member
    JOIN person p ON p.source = c.record_source
                 AND p.native_id = c.record_native_id
    WHERE p.self
""".trimIndent()

/**
 * The same walk as [SELF_CLUSTERS] asking whether the column is set rather than whether it is true.
 *
 * The inner join to `person` carries the same meaning it does there: a contact whose person record
 * the corpus no longer holds cannot answer the question, so it is absent rather than answering.
 */
private val SELF_KNOWN_CLUSTERS = """
    WITH RECURSIVE down (member, head, depth) AS (
        SELECT w.id, w.id, 0
        FROM unnest(?::uuid[]) AS w (id)
        UNION ALL
        SELECT l.merged, down.head, down.depth + 1
        FROM merge_link l
        JOIN down ON l.canonical = down.member
        WHERE down.depth < 64
    )
    SELECT DISTINCT down.head
    FROM down
    JOIN contact c ON c.id = down.member
    JOIN person p ON p.source = c.record_source
                 AND p.native_id = c.record_native_id
    WHERE p.self IS NOT NULL
""".trimIndent()

private val MEMBERS_OF = """
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
    SELECT DISTINCT member, head
    FROM down
""".trimIndent()
