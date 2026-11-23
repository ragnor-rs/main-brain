package io.github.ragnorrs.mainbrain.domain.index

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The indexing pass, against fakes.
 *
 * Fakes rather than a database because what this class does is entirely ordering and
 * selection: which records it asks for, what it sends to be embedded, and — the part that
 * cannot be recovered from if it is wrong — that nothing is ever marked derived before the
 * thing derived from it has been written. A real store would make those harder to see, not
 * easier, because the store is already tested on its own.
 */
class IndexerTest {

    @Test
    fun `a pending message becomes a span, and only then is it marked`(): Unit = runBlocking {
        val record = id("a")
        val world = World(pending = listOf(record), stored = listOf(message(record, "a line of text")))

        val written = world.indexer().indexBatch()

        assertEquals(1, written)
        assertEquals(listOf("a line of text"), world.index.written.map { it.span.text })
        assertEquals(SpanId(RecordKind.MESSAGE, record), world.index.written.single().span.id)
        assertEquals(listOf(record), world.derivations.marked)
        assertEquals(
            listOf("index", "mark"),
            world.order,
            "The marks were written before the spans. A failure between the two then leaves " +
                "records recorded as derived that were never embedded, and no re-run revisits " +
                "them — the corpus keeps a hole that reads as complete.",
        )
    }

    @Test
    fun `a message with nothing to embed is still marked, or the pass never advances`(): Unit = runBlocking {
        // A sticker, or an image with no caption. There is nothing to index and never will
        // be; leaving it unmarked hands it back on every pass for ever.
        val quiet = id("sticker")
        val world = World(pending = listOf(quiet), stored = listOf(message(quiet, null)))

        val written = world.indexer().indexBatch()

        assertEquals(0, written)
        assertTrue(world.index.written.isEmpty())
        assertEquals(listOf(quiet), world.derivations.marked)
    }

    @Test
    fun `only the texts that exist are sent to be embedded`(): Unit = runBlocking {
        val speaking = id("speaking")
        val quiet = id("quiet")
        val world = World(
            pending = listOf(speaking, quiet),
            stored = listOf(message(speaking, "said something"), message(quiet, "   ")),
        )

        world.indexer().indexBatch()

        assertEquals(
            listOf(listOf("said something")),
            world.embeddings.asked,
            "A blank text reached the model. Embedding whitespace spends a round trip to " +
                "produce a vector for a span that could never be a useful answer.",
        )
    }

    @Test
    fun `corpus spans are embedded as passages rather than as questions`(): Unit = runBlocking {
        val record = id("a")
        val world = World(pending = listOf(record), stored = listOf(message(record, "text")))

        world.indexer().indexBatch()

        assertEquals(listOf(EmbeddingKind.PASSAGE), world.embeddings.kinds)
    }

    @Test
    fun `nothing pending does no work at all`(): Unit = runBlocking {
        val world = World(pending = emptyList(), stored = emptyList())

        assertEquals(0, world.indexer().indexBatch())
        assertTrue(world.embeddings.asked.isEmpty(), "An empty batch still called the model.")
        assertTrue(world.order.isEmpty())
    }

    @Test
    fun `the model version is read per batch, so a model swapped mid-run is noticed`(): Unit = runBlocking {
        val record = id("a")
        val world = World(pending = listOf(record), stored = listOf(message(record, "text")))
        world.embeddings.version = "model-a"
        world.indexer().indexBatch()

        world.embeddings.version = "model-b"
        world.derivations.pendingIds = listOf(record)
        world.indexer().indexBatch()

        assertEquals(
            listOf("model-a", "model-b"),
            world.derivations.versionsSeen.distinct(),
            "The pass kept using the version it started with. Spans would then be filed " +
                "under a model that did not produce them, and nothing would re-derive them.",
        )
    }

    @Test
    fun `the mark is stamped with when the work was selected, not when it finished`(): Unit = runBlocking {
        // The difference decides whether an edit landing mid-batch is ever re-derived. The
        // pass reads a body, the record is written again while the pass is still working, and
        // the pass then marks it. Stamping the finish would put the mark after the new content
        // time and settle the record for ever against a body nothing embedded; stamping the
        // selection leaves it pending, which is what a changed record should be.
        val record = id("a")
        val world = World(pending = listOf(record), stored = listOf(message(record, "text")))
        val selected = Instant.parse("2024-03-01T09:00:00Z")
        world.derivations.selectedAt = selected

        world.indexer().indexBatch()

        assertEquals(
            selected,
            world.derivations.markedAsOf,
            "The mark carried some other instant than the one the work was selected at. " +
                "Anything later silently settles a record that changed while the batch ran.",
        )
    }

    @Test
    fun `a short reply from the model fails the batch rather than marking it done`(): Unit = runBlocking {
        // `zip` would truncate silently and the mark below would still settle every record —
        // the one failure a re-run cannot heal, because nothing revisits a settled record.
        val first = id("first")
        val second = id("second")
        val world = World(
            pending = listOf(first, second),
            stored = listOf(message(first, "one"), message(second, "two")),
        )
        world.embeddings.returnOneFewer = true

        assertFailsWith<IllegalStateException> { world.indexer().indexBatch() }

        assertTrue(world.derivations.marked.isEmpty(), "Records were marked despite the failure.")
    }

    @Test
    fun `a batch size of nothing is refused at construction`() {
        val world = World(pending = emptyList(), stored = emptyList())
        assertFailsWith<IllegalArgumentException> { world.indexer(batchSize = 0) }
    }

    // --- the world the pass runs in -------------------------------------------------------

    private class World(pending: List<ExternalId>, stored: List<Message>) {
        val order = mutableListOf<String>()
        val embeddings = FakeEmbeddings()
        val derivations = FakeDerivations(pending, order)
        val messages = FakeMessages(stored)
        val index = FakeIndex(order)

        fun indexer(batchSize: Int = 128) = Indexer(embeddings, derivations, messages, index, batchSize)
    }

    private class FakeEmbeddings : EmbeddingProvider {
        var version = "a-model"
        val asked = mutableListOf<List<String>>()
        val kinds = mutableListOf<EmbeddingKind>()

        /** A provider that breaks its own contract, which the pass must not paper over. */
        var returnOneFewer = false

        override suspend fun version(): String = version

        override suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding> {
            asked += texts
            kinds += kind
            val produced = if (returnOneFewer) texts.dropLast(1) else texts
            return produced.map { Embedding(dense = listOf(1.0f), sparse = mapOf(1 to 1.0f)) }
        }
    }

    private class FakeDerivations(
        var pendingIds: List<ExternalId>,
        private val order: MutableList<String>,
    ) : DerivationStateRepository {
        val marked = mutableListOf<ExternalId>()
        val versionsSeen = mutableListOf<String>()

        /** A fixed instant per selection, so a test can tell which one reached `mark`. */
        var selectedAt: Instant = Instant.parse("2024-01-01T12:00:00Z")
        var markedAsOf: Instant? = null

        // `notBefore` is held rather than applied: this fake has records and no source times,
        // and whether the store filters by one is asserted against Postgres. Both passes here
        // pass nothing anyway — neither is bounded by a horizon.
        override suspend fun pending(
            derivation: Derivation,
            kind: RecordKind,
            limit: Int,
            notBefore: Instant?,
        ): Pending {
            versionsSeen += derivation.version
            return Pending(records = pendingIds.take(limit), asOf = selectedAt)
        }

        override suspend fun mark(
            derivation: Derivation,
            kind: RecordKind,
            records: List<ExternalId>,
            asOf: Instant,
        ) {
            order += "mark"
            marked += records
            markedAsOf = asOf
            pendingIds = pendingIds - records.toSet()
        }

        // Unbounded, as the store's own is: there is no limit to saturate against here, which is
        // the property a driver relies on to tell a batch that made progress from one that stalled.
        override suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int {
            versionsSeen += derivation.version
            return pendingIds.size
        }
    }

    private class FakeMessages(private val stored: List<Message>) : MessageRepository {
        override suspend fun upsert(conversation: ExternalId, messages: List<Message>) = Unit

        // Neither pass here asks which conversation a message is in: indexing works from a
        // list of records, and identity works from the pairs that shared a room.
        override suspend fun conversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId> =
            emptyMap()

        override suspend fun messagesIn(conversation: ExternalId): List<Message> = stored

        override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> =
            stored.takeLast(limit)

        override suspend fun messages(externalIds: List<ExternalId>): List<Message> =
            stored.filter { it.externalId in externalIds.toSet() }

        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = null

        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = emptyList()

        override suspend fun sharingAConversation(
            authors: List<ExternalId>,
        ): List<Pair<ExternalId, ExternalId>> = emptyList()

        // Ingestion's business, not the indexer's: these tests drive the derived pass, which
        // reads messages and writes none.
        override suspend fun markWithdrawn(externalIds: List<ExternalId>) = Unit
    }

    private class FakeIndex(private val order: MutableList<String>) : IndexRepository {
        val written = mutableListOf<IndexEntry>()

        override suspend fun index(entries: List<IndexEntry>) {
            // Recorded even when empty: the ordering claim is about the calls, not the rows.
            order += "index"
            written += entries
        }

        override suspend fun search(query: Embedding, limit: Int): List<Hit> = emptyList()

        override suspend fun neighbours(span: SpanId, before: Int, after: Int): List<Span> = emptyList()
    }

    private fun id(label: String) = ExternalId(Source.TELEGRAM, label)

    private fun message(externalId: ExternalId, text: String?) = Message(
        externalId = externalId,
        sourceTime = Instant.parse("2024-01-01T00:00:00Z"),
        text = text,
    )
}
