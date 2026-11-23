package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedPerson
import java.sql.Connection
import java.sql.ResultSet

// The corpus reads that exist for matching: who claims a value, how common a value is, and which
// accounts a third party was in a room with. All `internal` — these are SQL-shaped, and SQL does
// not leave this module. They live together because they are read together, by one pass, and
// because each is useless to anything else.

/** These person records with their claims and the times those were seen. */
internal fun Connection.readObservedPersons(externalIds: List<ExternalId>): List<ObservedPerson> =
    prepareStatement(PERSONS_BY_ID).use { statement ->
        val wanted = externalIds.distinct()
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
            table(RecordTable.PERSON)
        }
        statement.executeQuery().use { it.toObservedPersons() }
    }

/**
 * Every person record claiming any of [claims], with all of its own claims.
 *
 * Two passes over `identifier` rather than one: the subquery finds the records worth looking at,
 * and the outer join then collects everything those records claim. Filtering both at once would
 * return each candidate carrying only the claim that found it, and the matcher would score every
 * pair on one signal — the one it already knew they shared.
 */
internal fun Connection.readClaimants(claims: List<Identifier>): List<ObservedPerson> =
    prepareStatement(CLAIMANTS).use { statement ->
        val wanted = claims.distinct()
        statement.bind {
            table(RecordTable.PERSON)
            strings(wanted.map { it.type.name })
            strings(wanted.map { it.value })
            table(RecordTable.PERSON)
        }
        statement.executeQuery().use { it.toObservedPersons() }
    }

/**
 * Every person record carrying one of [names], for the names at most [carriedByAtMost] records
 * carry.
 *
 * The same two-pass shape as [readClaimants] and for the same reason: the inner query picks the
 * records worth looking at, and the outer join then collects everything each of them claims. A
 * record pulled in by its name still has to be scored on its claims, and one carrying only the
 * name that found it would score on that name alone.
 *
 * The bound is applied where the count is computed, which is what keeps it honest: counting in
 * one place and filtering in another is how a bound comes to mean *at most this many in the batch*
 * rather than *in the corpus*. A name over the bound contributes no rows, so nothing downstream
 * sees a partial set of its carriers and mistakes it for all of them.
 *
 * The normalisation is [NAME_FREQUENCY]'s, repeated because both statements have to read a name
 * the way `nameKey` writes one. The index this leans on normalises the same expression.
 */
internal fun Connection.readNamesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson> =
    prepareStatement(NAMESAKES).use { statement ->
        // Bound in the statement's own order: the names and the bound sit in the CTE, which is
        // written before the join that needs the record kind.
        statement.bind {
            strings(names.distinct())
            int(carriedByAtMost)
            table(RecordTable.PERSON)
        }
        statement.executeQuery().use { it.toObservedPersons() }
    }

/**
 * How many person records carry each of these names.
 *
 * The normalisation in the statement has to be the same one the caller asked in — trim, collapse
 * runs of whitespace, lowercase. If the two drift apart nothing matches, every name reads as
 * unique, and the weakest signal silently becomes one of the strongest. A left join rather than
 * a filter, so that a name nothing carries comes back as zero instead of going missing.
 */
internal fun Connection.readNameFrequency(names: List<String>): Map<String, Int> =
    prepareStatement(NAME_FREQUENCY).use { statement ->
        statement.bind { strings(names.distinct()) }
        statement.executeQuery().use { rows ->
            rows.collect { it.getString("name") to it.getInt("carriers") }.toMap()
        }
    }

/**
 * The pairs among [authors] that both wrote in one conversation — the hard negative.
 *
 * The participants are reduced to one row per author per conversation before being joined
 * against each other, because a pair who exchanged a thousand messages would otherwise be a
 * million rows on the way to one answer. The row-value comparison yields each pair once, in one
 * order, and drops the self-pairs the join produces.
 */
internal fun Connection.readSharedConversations(authors: List<ExternalId>): List<Pair<ExternalId, ExternalId>> =
    prepareStatement(SHARED_CONVERSATIONS).use { statement ->
        val wanted = authors.distinct()
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
        }
        statement.executeQuery().use { rows ->
            rows.collect {
                requireNotNull(it.externalId("one_source", "one_native_id")) to
                    requireNotNull(it.externalId("other_source", "other_native_id"))
            }
        }
    }

/**
 * Every person the join returned, with its claims collapsed onto it.
 *
 * The same shape as `toMessages`: the outer join carries each record's claims on its own rows,
 * and the consecutive rows belonging to one record are gathered here. A record with no claims
 * still appears, which matters — a person offering nothing but a display name is an ordinary
 * record and the name signal is the only thing that can match it.
 */
private fun ResultSet.toObservedPersons(): List<ObservedPerson> {
    val persons = LinkedHashMap<ExternalId, ObservedPerson>()
    val claims = LinkedHashMap<ExternalId, MutableList<ObservedIdentifier>>()
    while (next()) {
        val externalId = requireNotNull(externalId("source", "native_id"))
        persons.getOrPut(externalId) {
            ObservedPerson(externalId = externalId, displayName = getString("display_name"))
        }
        identifierOrNull()?.let {
            claims.getOrPut(externalId) { mutableListOf() } += ObservedIdentifier(
                identifier = it,
                addedAt = requireNotNull(instant("added_at")),
                lastSeenAt = requireNotNull(instant("last_seen_at")),
            )
        }
    }
    return persons.map { (externalId, person) -> person.copy(claims = claims[externalId].orEmpty()) }
}

private val PERSONS_BY_ID = """
    SELECT p.source, p.native_id, p.display_name, i.type, i.value, i.added_at, i.last_seen_at
    FROM person p
    JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
      ON p.source = wanted.source AND p.native_id = wanted.native_id
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = p.id
    ORDER BY p.id, i.type, i.value
""".trimIndent()

private val CLAIMANTS = """
    SELECT p.source, p.native_id, p.display_name, i.type, i.value, i.added_at, i.last_seen_at
    FROM person p
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = p.id
    WHERE p.id IN (
        SELECT c.record_id
        FROM identifier c
        JOIN unnest(?::text[], ?::text[]) AS wanted (type, value)
          ON c.type = wanted.type AND c.value = wanted.value
        WHERE c.record_kind = ?
    )
    ORDER BY p.id, i.type, i.value
""".trimIndent()

/**
 * The records carrying a wanted name, for the names few enough records carry.
 *
 * `rare` counts the carriers of each wanted name over the whole `person` table and keeps the names
 * inside the bound; the outer query then takes every record carrying one of those. The count and
 * the bound are one expression, so neither can drift from the other.
 */
private val NAMESAKES = """
    WITH rare AS (
        SELECT wanted.name AS name
        FROM unnest(?::text[]) AS wanted (name)
        JOIN person carrier
          ON lower(btrim(regexp_replace(carrier.display_name, '\s+', ' ', 'g'))) = wanted.name
        GROUP BY wanted.name
        HAVING count(carrier.id) <= ?
    )
    SELECT p.source, p.native_id, p.display_name, i.type, i.value, i.added_at, i.last_seen_at
    FROM person p
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = p.id
    WHERE lower(btrim(regexp_replace(p.display_name, '\s+', ' ', 'g'))) IN (SELECT name FROM rare)
    ORDER BY p.id, i.type, i.value
""".trimIndent()

private val NAME_FREQUENCY = """
    SELECT wanted.name AS name, count(p.id) AS carriers
    FROM unnest(?::text[]) AS wanted (name)
    LEFT JOIN person p
           ON lower(btrim(regexp_replace(p.display_name, '\s+', ' ', 'g'))) = wanted.name
    GROUP BY wanted.name
""".trimIndent()

private val SHARED_CONVERSATIONS = """
    WITH participant AS (
        SELECT DISTINCT m.conversation_id, m.author_source AS source, m.author_native_id AS native_id
        FROM message m
        JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
          ON m.author_source = wanted.source AND m.author_native_id = wanted.native_id
    )
    SELECT DISTINCT a.source AS one_source, a.native_id AS one_native_id,
                    b.source AS other_source, b.native_id AS other_native_id
    FROM participant a
    JOIN participant b ON b.conversation_id = a.conversation_id
    WHERE (a.source, a.native_id) < (b.source, b.native_id)
""".trimIndent()
