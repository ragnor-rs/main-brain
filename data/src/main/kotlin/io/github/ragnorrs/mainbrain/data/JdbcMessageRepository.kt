package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageInAnotherConversation
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import io.github.ragnorrs.mainbrain.domain.record.UnknownConversation
import java.sql.Connection
import java.sql.ResultSet

class JdbcMessageRepository(private val database: Database) : MessageRepository {

    /**
     * The whole batch of messages in one transaction, so a conversation's messages never land
     * half-written. The conversation is resolved once rather than per message.
     */
    override suspend fun upsert(conversation: ExternalId, messages: List<Message>) {
        if (messages.isEmpty()) return
        database.transaction { connection ->
            val conversationId = connection.idOf(RecordTable.CONVERSATION, conversation)
                ?: throw UnknownConversation(conversation)
            messages.forEach { message ->
                val id = connection.writeMessage(conversationId, message)
                connection.recordIdentifiers(RecordTable.MESSAGE, id, message.identifiers)
            }
        }
    }

    override suspend fun messagesIn(conversation: ExternalId): List<Message> = database.query { connection ->
        connection.readMessages(conversation)
    }

    override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> {
        require(limit > 0) { "A sample of $limit messages is not a sample." }
        return database.query { connection -> connection.readLatestMessages(conversation, limit) }
    }

    override suspend fun messages(externalIds: List<ExternalId>): List<Message> {
        if (externalIds.isEmpty()) return emptyList()
        return database.query { connection -> connection.readMessagesById(externalIds) }
    }

    override suspend fun conversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId> {
        if (messages.isEmpty()) return emptyMap()
        return database.query { connection -> connection.readConversationsOf(messages) }
    }

    /**
     * One statement for the whole list, and it names `withdrawn_at` and no other column.
     *
     * Deliberately not an upsert, and deliberately not last-seen either. An upsert would reach
     * the same place by a route that has to read the corpus first, and would move content time
     * the moment its comparison ever disagreed; last-seen would say a pass found a record that a
     * pass was told is gone. Writing the one column directly says only what the source said.
     *
     * **`IS NULL` is what makes the first statement the one kept.** A re-delivered batch repeats
     * the removal, and a withdrawal re-stamped on every repeat would be a clock rather than a
     * fact — so a row that already carries one is left exactly as it is, which also makes this
     * write idempotent in the way every other write here is.
     *
     * Rows the store does not hold simply do not match, which is how an identifier it has never
     * seen is skipped without a lookup to find out.
     */
    override suspend fun markWithdrawn(externalIds: List<ExternalId>) {
        if (externalIds.isEmpty()) return
        database.query { connection ->
            connection.prepareStatement(MARK_WITHDRAWN).use { statement ->
                val wanted = externalIds.distinct()
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeUpdate()
            }
        }
    }

    override suspend fun timesOf(externalId: ExternalId): RecordTimes? = database.query { connection ->
        connection.timesOf(RecordTable.MESSAGE, externalId)
    }

    override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> =
        database.query { connection -> connection.observedIdentifiers(RecordTable.MESSAGE, externalId) }

    override suspend fun sharingAConversation(authors: List<ExternalId>): List<Pair<ExternalId, ExternalId>> {
        if (authors.isEmpty()) return emptyList()
        return database.query { connection -> connection.readSharedConversations(authors) }
    }

    /**
     * One message, and the conversation it belongs to is never changed by a write.
     *
     * The upsert's `WHERE` is what makes a mismatch loud. The native identifier is unique
     * within its source, which is the adapter's obligation to honour — but if one arrives
     * naming a different conversation than the stored row, the update matches nothing, so
     * `RETURNING` yields no row and this throws. Without that clause the upsert would set
     * `conversation_id` from the incoming row and quietly move a message between
     * conversations, which is a corpus that reads as ordinary data and is wrong.
     */
    private fun Connection.writeMessage(conversationId: Long, message: Message): Long =
        prepareStatement(UPSERT).use { statement ->
            statement.bind {
                externalId(message.externalId)
                long(conversationId)
                externalId(message.author)
                string(message.text)
                instant(message.sourceTime)
            }
            statement.executeQuery().use { rows ->
                // No row means the conflict was real and the `WHERE` rejected it: the stored
                // message belongs to another conversation. An insert and a permitted update
                // both return the identifier.
                if (!rows.next()) throw MessageInAnotherConversation(message.externalId)
                rows.getLong(1)
            }
        }

    private companion object {
        val UPSERT = """
            INSERT INTO message (source, native_id, conversation_id, author_source, author_native_id, body, source_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, native_id) DO UPDATE SET
                author_source = EXCLUDED.author_source,
                author_native_id = EXCLUDED.author_native_id,
                body = EXCLUDED.body,
                source_time = EXCLUDED.source_time,
                last_seen_at = now(),
                content_time = CASE
                    WHEN ROW(message.author_source, message.author_native_id, message.body, message.source_time)
                         IS DISTINCT FROM
                         ROW(EXCLUDED.author_source, EXCLUDED.author_native_id, EXCLUDED.body, EXCLUDED.source_time)
                    THEN now()
                    ELSE message.content_time
                END
            WHERE message.conversation_id = EXCLUDED.conversation_id
            RETURNING id
        """.trimIndent()

        val MARK_WITHDRAWN = """
            UPDATE message m
            SET withdrawn_at = now()
            FROM unnest(?::text[], ?::text[]) AS withdrawn (source, native_id)
            WHERE m.source = withdrawn.source AND m.native_id = withdrawn.native_id
              AND m.withdrawn_at IS NULL
        """.trimIndent()
    }
}

/**
 * The identifier pairs are sent as two arrays and joined against, rather than expanded
 * into one placeholder pair per element.
 *
 * Top-level rather than a member, with its statement beside it. detekt objects at eleven functions
 * in one class, so the private runners that use nothing but their connection sit outside and leave
 * the room inside for what the port itself declares. The write statements stay in the companion,
 * with the members that run them.
 *
 * A statement assembled from the size of its own input is a different statement for every
 * batch size, which gives the driver nothing to cache and makes the SQL text depend on the
 * data. Two arrays keep one statement for every batch.
 */
private fun Connection.readMessagesById(externalIds: List<ExternalId>): List<Message> =
    prepareStatement(SELECT_BY_ID).use { statement ->
        // In the order the statement declares them: the two arrays feed the `unnest`
        // join, and the record kind reaches the claim join after it.
        // Distinct, as every other batch here is. `unnest` yields a row per input pair,
        // so a repeated identifier would return a message once -- the mapper collapses it
        // -- while appending its claims twice.
        val wanted = externalIds.distinct()
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
            table(RecordTable.MESSAGE)
        }
        statement.executeQuery().use { it.toMessages() }
    }

private fun Connection.readMessages(conversation: ExternalId): List<Message> =
    prepareStatement(SELECT).use { statement ->
        statement.bind {
            table(RecordTable.MESSAGE)
            externalId(conversation)
        }
        statement.executeQuery().use { it.toMessages() }
    }

private fun Connection.readLatestMessages(conversation: ExternalId, limit: Int): List<Message> =
    prepareStatement(SELECT_LATEST).use { statement ->
        statement.bind {
            externalId(conversation)
            int(limit)
            table(RecordTable.MESSAGE)
        }
        statement.executeQuery().use { it.toMessages() }
    }

private val SELECT_BY_ID = """
    SELECT m.source, m.native_id, m.author_source, m.author_native_id, m.body, m.source_time,
           i.type, i.value
    FROM message m
    JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
      ON m.source = wanted.source AND m.native_id = wanted.native_id
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = m.id
    ORDER BY m.source_time, m.id, i.type, i.value
""".trimIndent()

private val SELECT = """
    SELECT m.source, m.native_id, m.author_source, m.author_native_id, m.body, m.source_time,
           i.type, i.value
    FROM message m
    JOIN conversation c ON c.id = m.conversation_id
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = m.id
    WHERE c.source = ? AND c.native_id = ?
    ORDER BY m.source_time, m.id, i.type, i.value
""".trimIndent()

/**
 * The newest messages of one conversation, returned oldest first.
 *
 * **The limit is applied in a subselect and not to the join, which is the whole shape of this
 * statement.** The claim join carries a message's identifiers on rows of their own, so a `LIMIT`
 * over the joined result would cut a sample off mid-message and hand the mapper a message with some
 * of its claims. Choosing the message rows first makes the bound a bound on messages.
 *
 * **Ordered descending to select and ascending to return**, which is the difference between *which*
 * messages and *in what order* — and the reason the outer `ORDER BY` repeats [SELECT]'s exactly:
 * both feed one mapper, and a sample that arrived newest-first would read backwards.
 *
 * The tie-break on `id` is load-bearing in the inner ordering and not decoration: a conversation
 * frequently holds several messages at one source time, and without it which of them the sample
 * ends at is whatever the plan happened to produce — so two runs could judge one conversation on
 * different material with nothing recording why.
 *
 * **And it does not cost the bound, which is the thing worth having measured.** A second ordering
 * key the index does not carry could have turned the subselect into a sort of the whole
 * conversation, which is the cost it exists to avoid. Against the largest conversation of a real
 * archive the planner answers it as an incremental sort over the presorted time key: it reads a
 * little over a hundred rows of a thousand-message thread, a few dozen buffers, under two
 * milliseconds. The `LIMIT` reaches the scan rather than being applied after it.
 */
private val SELECT_LATEST = """
    WITH latest AS (
        SELECT m.id
        FROM message m
        JOIN conversation c ON c.id = m.conversation_id
        WHERE c.source = ? AND c.native_id = ?
        ORDER BY m.source_time DESC, m.id DESC
        LIMIT ?
    )
    SELECT m.source, m.native_id, m.author_source, m.author_native_id, m.body, m.source_time,
           i.type, i.value
    FROM message m
    JOIN latest ON latest.id = m.id
    LEFT JOIN identifier i ON i.record_kind = ? AND i.record_id = m.id
    ORDER BY m.source_time, m.id, i.type, i.value
""".trimIndent()

/**
 * Every message the join returned, in the order it returned them.
 *
 * One query rather than one per message: the outer join carries each message's claims on its own
 * rows, and the consecutive rows belonging to one message are collapsed here. The map preserves
 * insertion order, so the `ORDER BY` in the statement is what orders the result.
 */
private fun ResultSet.toMessages(): List<Message> {
    val messages = LinkedHashMap<ExternalId, Message>()
    val claims = LinkedHashMap<ExternalId, MutableList<Identifier>>()
    while (next()) {
        val externalId = requireNotNull(externalId("source", "native_id"))
        messages.getOrPut(externalId) {
            Message(
                externalId = externalId,
                sourceTime = requireNotNull(instant("source_time")),
                author = externalId("author_source", "author_native_id"),
                text = getString("body"),
            )
        }
        identifierOrNull()?.let { claims.getOrPut(externalId) { mutableListOf() } += it }
    }
    return messages.map { (externalId, message) -> message.copy(identifiers = claims[externalId].orEmpty()) }
}

/**
 * Each message's conversation, by joining the message's own parent key.
 *
 * The surrogate parent key is the one thing a message row holds about its conversation, and this
 * comes back out as the source's own pair — the surrogate is the store's and does not leave it.
 */
private fun Connection.readConversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId> =
    prepareStatement(CONVERSATIONS_OF).use { statement ->
        val wanted = messages.distinct()
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
        }
        statement.executeQuery().use { rows ->
            rows.collect {
                requireNotNull(it.externalId("source", "native_id")) to
                    requireNotNull(it.externalId("conversation_source", "conversation_native_id"))
            }.toMap()
        }
    }

private val CONVERSATIONS_OF = """
    SELECT m.source, m.native_id,
           c.source AS conversation_source, c.native_id AS conversation_native_id
    FROM message m
    JOIN conversation c ON c.id = m.conversation_id
    JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
      ON m.source = wanted.source AND m.native_id = wanted.native_id
""".trimIndent()
