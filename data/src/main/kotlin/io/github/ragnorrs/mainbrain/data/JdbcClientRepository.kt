package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.ClientRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * The clients identity reads off conversation names.
 *
 * **The one adapter here that names the `client` table; every other reader goes through
 * `admitted_client`.** It is the write path, which is the whole of the exemption: a row is inserted
 * here and read back here, and a view that withheld the rejected would make the insert's own
 * read-back disagree with what was written. What the answer path wants from a client is the
 * opposite — the admitted ones only — and that is why the two readings are two statements rather
 * than one carrying a flag.
 *
 * Nothing here applies a verdict and nothing here reads one. The pass that judges a label reads
 * conversations and keys its verdict on the conversation, so it never comes through this file.
 */
class JdbcClientRepository(private val database: Database) : ClientRepository {

    /** As with contacts, the `ON CONFLICT` does not name `id`, so a stored identity is kept. */
    override suspend fun upsert(clients: List<Client>) {
        if (clients.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(UPSERT).use { statement ->
                clients.forEach { client ->
                    statement.bind {
                        uuid(client.id.value)
                        externalId(client.conversation)
                        string(client.name)
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun of(conversations: List<ExternalId>): List<Client> {
        if (conversations.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_RECORD).use { statement ->
                val wanted = conversations.distinct()
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeQuery().use { rows -> rows.collect { it.toClient() } }
            }
        }
    }

    private companion object {
        val UPSERT = """
            INSERT INTO client (id, record_source, record_native_id, name)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (record_source, record_native_id) DO UPDATE SET
                name = EXCLUDED.name
        """.trimIndent()

        val SELECT_BY_RECORD = """
            SELECT c.id, c.record_source, c.record_native_id, c.name
            FROM client c
            JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
              ON c.record_source = wanted.source AND c.record_native_id = wanted.native_id
        """.trimIndent()
    }
}
