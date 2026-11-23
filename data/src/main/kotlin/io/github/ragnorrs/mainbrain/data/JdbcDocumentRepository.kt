package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.Document
import io.github.ragnorrs.mainbrain.domain.record.DocumentRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import java.sql.Connection
import java.sql.ResultSet

class JdbcDocumentRepository(private val database: Database) : DocumentRepository {

    override suspend fun upsert(document: Document) {
        database.transaction { connection ->
            val id = connection.writeDocument(document)
            connection.recordIdentifiers(RecordTable.DOCUMENT, id, document.identifiers)
        }
    }

    override suspend fun find(externalId: ExternalId): Document? = database.query { connection ->
        connection.readDocument(externalId)
    }

    override suspend fun timesOf(externalId: ExternalId): RecordTimes? = database.query { connection ->
        connection.timesOf(RecordTable.DOCUMENT, externalId)
    }

    override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> =
        database.query { connection -> connection.observedIdentifiers(RecordTable.DOCUMENT, externalId) }

    private fun Connection.writeDocument(document: Document): Long =
        prepareStatement(UPSERT).use { statement ->
            statement.bind {
                externalId(document.externalId)
                string(document.name)
                string(document.mediaType)
                string(document.text)
                externalId(document.messageRef)
                instant(document.sourceTime)
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun Connection.readDocument(externalId: ExternalId): Document? =
        prepareStatement(SELECT).use { statement ->
            statement.bind {
                table(RecordTable.DOCUMENT)
                externalId(externalId)
            }
            statement.executeQuery().use { it.toDocument(externalId) }
        }

    private companion object {
        val UPSERT = """
            INSERT INTO document (source, native_id, name, media_type, body,
                                  message_source, message_native_id, source_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, native_id) DO UPDATE SET
                name = EXCLUDED.name,
                media_type = EXCLUDED.media_type,
                body = EXCLUDED.body,
                message_source = EXCLUDED.message_source,
                message_native_id = EXCLUDED.message_native_id,
                source_time = EXCLUDED.source_time,
                last_seen_at = now(),
                content_time = CASE
                    WHEN ROW(document.name, document.media_type, document.body,
                             document.message_source, document.message_native_id, document.source_time)
                         IS DISTINCT FROM
                         ROW(EXCLUDED.name, EXCLUDED.media_type, EXCLUDED.body,
                             EXCLUDED.message_source, EXCLUDED.message_native_id, EXCLUDED.source_time)
                    THEN now()
                    ELSE document.content_time
                END
            RETURNING id
        """.trimIndent()

        val SELECT = """
            SELECT d.name, d.media_type, d.body, d.message_source, d.message_native_id, d.source_time,
                   i.type, i.value
            FROM document d
            LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = d.id
            WHERE d.source = ? AND d.native_id = ?
            ORDER BY i.type, i.value
        """.trimIndent()
    }
}

/** The record from the first row, the claims from all of them. See `toConversation`. */
private fun ResultSet.toDocument(externalId: ExternalId): Document? {
    val claims = mutableListOf<Identifier>()
    var document: Document? = null
    while (next()) {
        if (document == null) {
            document = Document(
                externalId = externalId,
                name = getString("name"),
                mediaType = getString("media_type"),
                text = getString("body"),
                messageRef = externalId("message_source", "message_native_id"),
                sourceTime = instant("source_time"),
            )
        }
        identifierOrNull()?.let { claims += it }
    }
    return document?.copy(identifiers = claims)
}
