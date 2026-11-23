package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import java.sql.Connection
import java.sql.ResultSet

class JdbcConversationRepository(private val database: Database) : ConversationRepository {

    /**
     * The header and its claims in one transaction, so a conversation can never be stored with
     * identifiers that were not.
     */
    override suspend fun upsert(conversation: Conversation) {
        database.transaction { connection ->
            val id = connection.writeHeader(conversation)
            connection.recordIdentifiers(RecordTable.CONVERSATION, id, conversation.identifiers)
        }
    }

    override suspend fun find(externalId: ExternalId): Conversation? = database.query { connection ->
        connection.readConversation(externalId)
    }

    override suspend fun timesOf(externalId: ExternalId): RecordTimes? = database.query { connection ->
        connection.timesOf(RecordTable.CONVERSATION, externalId)
    }

    override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> =
        database.query { connection -> connection.observedIdentifiers(RecordTable.CONVERSATION, externalId) }

    override suspend fun conversations(externalIds: List<ExternalId>): List<Conversation> {
        if (externalIds.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_ID).use { statement ->
                val wanted = externalIds.distinct()
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                    table(RecordTable.CONVERSATION)
                }
                statement.executeQuery().use { it.toConversations() }
            }
        }
    }

    private fun Connection.writeHeader(conversation: Conversation): Long =
        prepareStatement(UPSERT).use { statement ->
            statement.bind {
                externalId(conversation.externalId)
                string(conversation.kind.name)
                string(conversation.name)
                externalId(conversation.parent)
                instant(conversation.sourceTime)
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun Connection.readConversation(externalId: ExternalId): Conversation? =
        prepareStatement(SELECT).use { statement ->
            statement.bind {
                table(RecordTable.CONVERSATION)
                externalId(externalId)
            }
            statement.executeQuery().use { it.toConversation(externalId) }
        }

    private companion object {
        val UPSERT = """
            INSERT INTO conversation (source, native_id, kind, name, parent_source, parent_native_id, source_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, native_id) DO UPDATE SET
                kind = EXCLUDED.kind,
                name = EXCLUDED.name,
                parent_source = EXCLUDED.parent_source,
                parent_native_id = EXCLUDED.parent_native_id,
                source_time = EXCLUDED.source_time,
                last_seen_at = now(),
                content_time = CASE
                    WHEN ROW(conversation.kind, conversation.name, conversation.parent_source,
                             conversation.parent_native_id, conversation.source_time)
                         IS DISTINCT FROM
                         ROW(EXCLUDED.kind, EXCLUDED.name, EXCLUDED.parent_source,
                             EXCLUDED.parent_native_id, EXCLUDED.source_time)
                    THEN now()
                    ELSE conversation.content_time
                END
            RETURNING id
        """.trimIndent()

        val SELECT = """
            SELECT c.kind, c.name, c.parent_source, c.parent_native_id, c.source_time, i.type, i.value
            FROM conversation c
            LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = c.id
            WHERE c.source = ? AND c.native_id = ?
            ORDER BY i.type, i.value
        """.trimIndent()

        val SELECT_BY_ID = """
            SELECT c.source, c.native_id, c.kind, c.name, c.parent_source, c.parent_native_id,
                   c.source_time, i.type, i.value
            FROM conversation c
            JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
              ON c.source = wanted.source AND c.native_id = wanted.native_id
            LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = c.id
            ORDER BY c.id, i.type, i.value
        """.trimIndent()
    }
}

/** Every conversation the join returned, with its claims collapsed onto it. See `toMessages`. */
private fun ResultSet.toConversations(): List<Conversation> {
    val headers = LinkedHashMap<ExternalId, Conversation>()
    val claims = LinkedHashMap<ExternalId, MutableList<Identifier>>()
    while (next()) {
        val externalId = requireNotNull(externalId("source", "native_id"))
        headers.getOrPut(externalId) {
            Conversation(
                externalId = externalId,
                kind = ConversationKind.valueOf(getString("kind")),
                name = getString("name"),
                parent = externalId("parent_source", "parent_native_id"),
                sourceTime = instant("source_time"),
            )
        }
        identifierOrNull()?.let { claims.getOrPut(externalId) { mutableListOf() } += it }
    }
    return headers.map { (externalId, header) -> header.copy(identifiers = claims[externalId].orEmpty()) }
}

/**
 * The outer join carries the header on every row and one claim per row, so the header is taken
 * from the first and the claims accumulate across all of them. No rows at all means no such
 * conversation; rows with a null claim mean one with none.
 */
private fun ResultSet.toConversation(externalId: ExternalId): Conversation? {
    val claims = mutableListOf<Identifier>()
    var header: Conversation? = null
    while (next()) {
        if (header == null) {
            header = Conversation(
                externalId = externalId,
                kind = ConversationKind.valueOf(getString("kind")),
                name = getString("name"),
                parent = externalId("parent_source", "parent_native_id"),
                sourceTime = instant("source_time"),
            )
        }
        identifierOrNull()?.let { claims += it }
    }
    return header?.copy(identifiers = claims)
}
