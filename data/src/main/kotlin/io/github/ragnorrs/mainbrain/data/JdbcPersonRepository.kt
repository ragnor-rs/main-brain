package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedPerson
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import java.sql.Connection
import java.sql.ResultSet

class JdbcPersonRepository(private val database: Database) : PersonRepository {

    override suspend fun upsert(person: Person) {
        database.transaction { connection ->
            val id = connection.writePerson(person)
            connection.recordIdentifiers(RecordTable.PERSON, id, person.identifiers)
        }
    }

    override suspend fun find(externalId: ExternalId): Person? = database.query { connection ->
        connection.readPerson(externalId)
    }

    override suspend fun timesOf(externalId: ExternalId): RecordTimes? = database.query { connection ->
        connection.timesOf(RecordTable.PERSON, externalId)
    }

    override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> =
        database.query { connection -> connection.observedIdentifiers(RecordTable.PERSON, externalId) }

    override suspend fun persons(externalIds: List<ExternalId>): List<ObservedPerson> {
        if (externalIds.isEmpty()) return emptyList()
        return database.query { connection -> connection.readObservedPersons(externalIds) }
    }

    override suspend fun claimants(claims: List<Identifier>): List<ObservedPerson> {
        if (claims.isEmpty()) return emptyList()
        return database.query { connection -> connection.readClaimants(claims) }
    }

    override suspend fun namesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson> {
        if (names.isEmpty() || carriedByAtMost < 1) return emptyList()
        return database.query { connection -> connection.readNamesakes(names, carriedByAtMost) }
    }

    override suspend fun nameFrequency(names: List<String>): Map<String, Int> {
        if (names.isEmpty()) return emptyMap()
        return database.query { connection -> connection.readNameFrequency(names) }
    }

    private fun Connection.writePerson(person: Person): Long =
        prepareStatement(UPSERT).use { statement ->
            statement.bind {
                externalId(person.externalId)
                string(person.displayName)
                instant(person.sourceTime)
                booleanOrUnknown(person.isSelf)
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun Connection.readPerson(externalId: ExternalId): Person? =
        prepareStatement(SELECT).use { statement ->
            statement.bind {
                table(RecordTable.PERSON)
                externalId(externalId)
            }
            statement.executeQuery().use { it.toPerson(externalId) }
        }

    private companion object {
        /**
         * **`self` is `COALESCE`d and is not part of what moves content time**, and both
         * halves are deliberate.
         *
         * Coalesced, because the offered value is null when the adapter could not report the
         * account it authenticated as — and overwriting an established mark with *nothing is known*
         * would lose the fact on the first pass that happened not to be able to ask. A pass that
         * *can* ask offers true or false for every record it sends, so a correction still lands.
         *
         * Outside the content-time comparison, because nothing is derived from this column: the
         * answer path joins it live. Including it would make the mark arriving re-derive identity
         * for every person record, buying a pass that reads nothing new.
         */
        val UPSERT = """
            INSERT INTO person (source, native_id, display_name, source_time, self)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (source, native_id) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                source_time = EXCLUDED.source_time,
                self = COALESCE(EXCLUDED.self, person.self),
                last_seen_at = now(),
                content_time = CASE
                    WHEN ROW(person.display_name, person.source_time)
                         IS DISTINCT FROM
                         ROW(EXCLUDED.display_name, EXCLUDED.source_time)
                    THEN now()
                    ELSE person.content_time
                END
            RETURNING id
        """.trimIndent()

        val SELECT = """
            SELECT p.display_name, p.source_time, p.self, i.type, i.value
            FROM person p
            LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = p.id
            WHERE p.source = ? AND p.native_id = ?
            ORDER BY i.type, i.value
        """.trimIndent()
    }
}

/**
 * A flag that may be *unknown*: `true`, `false`, or `null` where the column holds nothing.
 *
 * Read through [ResultSet.wasNull] rather than as an object, because `getBoolean` answers false for a
 * null column — so a three-valued flag read the ordinary way loses the state that matters and reports
 * it as the answer *no*.
 *
 * Beside its one caller rather than among the shared helpers, which is where the only other
 * three-valued read would go if a second one ever appeared: `Sql.kt` is at the function count detekt
 * allows a file, so putting it there would cost a reshuffle of helpers this change has no business
 * touching.
 */
private fun ResultSet.booleanOrNull(column: String): Boolean? = getBoolean(column).takeIf { !wasNull() }

/** The record from the first row, the claims from all of them. See `toConversation`. */
private fun ResultSet.toPerson(externalId: ExternalId): Person? {
    val claims = mutableListOf<Identifier>()
    var person: Person? = null
    while (next()) {
        if (person == null) {
            person = Person(
                externalId = externalId,
                displayName = getString("display_name"),
                sourceTime = instant("source_time"),
                isSelf = booleanOrNull("self"),
            )
        }
        identifierOrNull()?.let { claims += it }
    }
    return person?.copy(identifiers = claims)
}
