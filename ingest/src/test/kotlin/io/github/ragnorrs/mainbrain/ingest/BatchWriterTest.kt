package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationUpdate
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * One batch onto the ports, and the order it reaches them in.
 *
 * The scheduler's own tests cover the loop around this. What is left for here is the writer's one
 * ordering obligation, which the loop cannot show: a withdrawal has to land after the message it
 * names, and a reversed order would be wrong only when the two share a batch.
 */
class BatchWriterTest {

    private val log = CorpusLog()

    private val writer = BatchWriter(
        conversations = LoggingConversationRepository(log),
        messages = LoggingMessageRepository(log),
        persons = LoggingPersonRepository(log),
        documents = LoggingDocumentRepository(log),
    )

    private val conversation = Conversation(
        externalId = ExternalId(Source.TELEGRAM, "900"),
        kind = ConversationKind.DIRECT,
        name = "a chat",
    )

    private fun message(nativeId: String) = Message(
        externalId = ExternalId(Source.TELEGRAM, nativeId),
        sourceTime = Instant.parse("2026-05-01T00:00:00Z"),
    )

    @Test
    fun `a batch with nothing withdrawn does not touch the withdrawal path at all`(): Unit = runBlocking {
        writer.write(Batch(conversations = listOf(ConversationUpdate(conversation, listOf(message("900/1"))))))

        assertTrue(log.withdrawn.isEmpty()) { "Nothing was withdrawn, yet a withdrawal was recorded." }
    }

    @Test
    fun `withdrawn identifiers reach the port`(): Unit = runBlocking {
        val withdrawn = listOf(ExternalId(Source.TELEGRAM, "900/1"), ExternalId(Source.TELEGRAM, "900/2"))

        writer.write(Batch(withdrawn = withdrawn))

        assertEquals(withdrawn, log.withdrawn)
    }

    /**
     * The ordering the writer exists to get right.
     *
     * A message can arrive and be withdrawn inside one batch — the change feed carries both
     * events and a pass can read them together. Advancing last-seen first would match no stored
     * row, so the observation would be silently lost, and only for batches shaped like this one.
     */
    @Test
    fun `a message withdrawn in the batch that carried it is stored first`(): Unit = runBlocking {
        val arrived = message("900/7")

        writer.write(
            Batch(
                conversations = listOf(ConversationUpdate(conversation, listOf(arrived))),
                withdrawn = listOf(arrived.externalId),
            ),
        )

        assertEquals(listOf("upsert", "markWithdrawn"), log.order) {
            "The withdrawal was recorded before the message was stored, so it matched no row."
        }
        assertEquals(listOf(conversation.externalId to listOf(arrived)), log.messages)
        assertEquals(listOf(arrived.externalId), log.withdrawn)
    }
}
