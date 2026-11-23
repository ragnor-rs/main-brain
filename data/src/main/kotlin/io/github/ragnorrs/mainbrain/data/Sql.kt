package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

// The parts of the four record repositories that are the same in all four. Everything here is
// `internal`: these are SQL-shaped helpers, and SQL does not leave this module.

/** Bind this statement's parameters, in the order the statement declares them. */
internal fun PreparedStatement.bind(parameters: Binder.() -> Unit): PreparedStatement =
    also { Binder(it).parameters() }

/** The surrogate key of the record with this external identifier, or `null`. */
internal fun Connection.idOf(table: RecordTable, externalId: ExternalId): Long? =
    prepareStatement("SELECT id FROM ${table.table} WHERE source = ? AND native_id = ?").use { statement ->
        statement.bind { externalId(externalId) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
    }

/** The three axes the store keeps for this record, or `null` if it holds no such record. */
internal fun Connection.timesOf(table: RecordTable, externalId: ExternalId): RecordTimes? =
    prepareStatement(
        """
        SELECT source_time, added_at, last_seen_at, content_time
        FROM ${table.table}
        WHERE source = ? AND native_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.bind { externalId(externalId) }
        statement.executeQuery().use { rows ->
            if (rows.next()) {
                RecordTimes(
                    sourceTime = rows.instant("source_time"),
                    addedAt = requireNotNull(rows.instant("added_at")),
                    lastSeenAt = requireNotNull(rows.instant("last_seen_at")),
                    contentTime = requireNotNull(rows.instant("content_time")),
                )
            } else {
                null
            }
        }
    }

/** This record's claims, each with the pair of times the store keeps for it. */
internal fun Connection.observedIdentifiers(
    table: RecordTable,
    externalId: ExternalId,
): List<ObservedIdentifier> =
    prepareStatement(
        """
        SELECT i.type, i.value, i.added_at, i.last_seen_at
        FROM identifier i
        JOIN ${table.table} r ON r.id = i.record_id
        WHERE i.record_kind = ? AND r.source = ? AND r.native_id = ?
        ORDER BY i.type, i.value
        """.trimIndent(),
    ).use { statement ->
        statement.bind {
            table(table)
            externalId(externalId)
        }
        statement.executeQuery().use { rows ->
            rows.collect {
                ObservedIdentifier(
                    identifier = it.identifier(),
                    addedAt = requireNotNull(it.instant("added_at")),
                    lastSeenAt = requireNotNull(it.instant("last_seen_at")),
                )
            }
        }
    }

internal fun ResultSet.instant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

/** The claim on this row, from the `type` and `value` columns the joins all project. */
internal fun ResultSet.identifier(): Identifier =
    Identifier(IdentifierType.valueOf(getString("type")), getString("value"))

/** The claim on this row, or `null` where an outer join found none. */
internal fun ResultSet.identifierOrNull(): Identifier? =
    getString("type")?.let { Identifier(IdentifierType.valueOf(it), getString("value")) }

/** An external identifier read from a pair of columns, or `null` when the pair is null. */
internal fun ResultSet.externalId(sourceColumn: String, nativeColumn: String): ExternalId? {
    val source = getString(sourceColumn)
    val nativeId = getString(nativeColumn)
    return if (source == null || nativeId == null) null else ExternalId(Source.valueOf(source), nativeId)
}

/** Every remaining row, mapped. */
internal fun <T> ResultSet.collect(row: (ResultSet) -> T): List<T> =
    buildList {
        while (next()) add(row(this@collect))
    }
