package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.index.IndexEntry
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * The index and the derivation watermark against a real Postgres.
 *
 * The fused read is the reason this cannot be a unit test. Reciprocal rank fusion over a
 * vector index and a sparse one is a property of the two operator classes and the rank
 * arithmetic together; a fake would assert only that the fake fuses. Several of these also
 * pin facts about pgvector itself that no Kotlin-level test could notice being wrong — the
 * term ceiling, and the one-based indexing of a sparse vector.
 *
 * **One test covers the `FULL OUTER JOIN`, and it has to work for its competition rather
 * than against an empty table.** A test that merely indexes a term-matching span and asserts
 * it comes back proves nothing about the join: the rig shares one database, the candidate
 * pool is `CANDIDATE_FACTOR` per result asked for, and with a few dozen spans in the table
 * both halves return every row — so an inner join is indistinguishable. Two such tests used
 * to sit here and an inner join left both green, which is why neither does now.
 *
 * `only one half finds it, and the fused read still returns it` crowds each half's window
 * deliberately, so the span really is outside one of them. That one fails under an inner
 * join, and it is checked rather than assumed.
 */
class IndexStoreTest {

    private val index = JdbcIndexRepository(StoreRig.database)
    private val derivations = JdbcDerivationStateRepository(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val submissions = JdbcSubmissionRepository(StoreRig.database)

    @Test
    fun `a span round-trips and the vector half finds it`(): Unit = runBlocking {
        val record = StoreRig.externalId("span")
        val direction = 11
        index.index(listOf(entry(record, "a message about rate cards", dense = direction)))

        val found = index.search(Embedding(denseAt(direction)), limit = 5)

        val hit = found.single { it.span.id.record == record }
        assertEquals("a message about rate cards", hit.span.text)
        assertEquals(SpanId(RecordKind.MESSAGE, record), hit.span.id)
    }

    @Test
    fun `re-indexing a span replaces it rather than adding a second`(): Unit = runBlocking {
        val record = StoreRig.externalId("reindexed")
        index.index(listOf(entry(record, "first text", dense = 21)))
        index.index(listOf(entry(record, "second text", dense = 21)))

        val found = index.search(Embedding(denseAt(21)), limit = 10).filter { it.span.id.record == record }

        assertEquals(1, found.size) {
            "A span indexed twice left ${found.size} entries. Re-indexing is what a changed " +
                "message and a changed model both produce, and it has to leave one."
        }
        assertEquals("second text", found.single().span.text)
    }

    @Test
    fun `only one half finds it, and the fused read still returns it`(): Unit = runBlocking {
        // The join's own test. Both halves yield CANDIDATE_FACTOR results per result asked
        // for, so the way to make a span reachable through one half only is to fill the other
        // half's window with spans that beat it there.
        //
        // Two crowds, each filling one window and excluded from the other:
        //   - the dense crowd sits exactly on the query's direction and shares no term, so it
        //     takes the whole vector window and scores zero on the lexical side;
        //   - the lexical crowd shares the query's term at half the target's weight and points
        //     somewhere else entirely, so it takes the rest of the lexical window.
        // The target is then the only span the lexical half ranks first and the vector half
        // cannot see at all. Under an inner join it is dropped and this fails.
        val pool = SEARCH_LIMIT * JdbcIndexRepository.CANDIDATE_FACTOR
        val term = 9001
        index.index(
            (0 until pool).map {
                entry(StoreRig.externalId("dense-crowd"), "crowds the vector half", dense = QUERY_AXIS)
            },
        )
        index.index(
            (0 until pool - 1).map {
                entry(
                    StoreRig.externalId("lexical-crowd"),
                    "crowds the lexical half",
                    dense = THIRD_AXIS,
                    sparse = mapOf(term to 0.5f),
                )
            },
        )
        val record = StoreRig.externalId("one-sided")
        index.index(listOf(entry(record, "invoice INV-9001", dense = FAR_AXIS, sparse = mapOf(term to 1.0f))))

        val found = index.search(
            Embedding(dense = denseAt(QUERY_AXIS), sparse = mapOf(term to 1.0f)),
            limit = SEARCH_LIMIT,
        )

        assertTrue(found.any { it.span.id.record == record }) {
            "A span the vector half cannot see was dropped from the fused ranking. $pool spans " +
                "sit on the query's own direction, so the target is outside the vector " +
                "window; it reaches the result through the lexical half or not at all, which " +
                "is what the full outer join is for."
        }
    }

    @Test
    fun `term weights survive the one-based shift a sparse vector requires`(): Unit = runBlocking {
        // Vocabulary position zero is the case that breaks if the shift is ever removed:
        // pgvector refuses index zero outright, so this fails at the write rather than here.
        //
        // The span is the unique nearest on both halves so that retrieving it does not depend
        // on how the shared rig's other spans happen to tie.
        val record = StoreRig.externalId("first-token")
        val axis = 51
        index.index(listOf(entry(record, "a text using the first token", dense = axis, sparse = mapOf(0 to 1.0f))))

        val found = index.search(Embedding(dense = denseAt(axis), sparse = mapOf(0 to 1.0f)), limit = 10)

        assertTrue(found.any { it.span.id.record == record }) {
            "A term at vocabulary position zero did not match itself. The store shifts every " +
                "index up by one because a sparse vector is numbered from one; a shift applied " +
                "on write and not on read would put every term one place out."
        }
    }

    @Test
    fun `a span at the term ceiling stores, and one past it is refused`(): Unit = runBlocking {
        val atLimit = (0 until JdbcIndexRepository.SPARSE_TERM_LIMIT).associateWith { 0.5f }
        index.index(listOf(entry(StoreRig.externalId("at-ceiling"), "a long text", sparse = atLimit)))

        val past = atLimit + (JdbcIndexRepository.SPARSE_TERM_LIMIT to 0.5f)
        assertFailsWith<IllegalArgumentException> {
            index.index(listOf(entry(StoreRig.externalId("past-ceiling"), "a longer text", sparse = past)))
        }
        Unit
    }

    @Test
    fun `an index of another width is refused rather than stored`(): Unit = runBlocking {
        val narrow = Embedding(List(8) { 0.5f })
        assertFailsWith<IllegalArgumentException> {
            index.index(
                listOf(
                    IndexEntry(
                        span = Span(
                            id = SpanId(RecordKind.MESSAGE, StoreRig.externalId("narrow")),
                            text = "a text",
                            sourceTime = Instant.parse("2024-01-01T00:00:00Z"),
                        ),
                        embedding = narrow,
                    ),
                ),
            )
        }
        Unit
    }

    @Test
    fun `neighbours expand around a hit in conversation order, the hit included`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val written = (0..4).map { position ->
            Message(
                externalId = StoreRig.externalId("turn"),
                sourceTime = Instant.parse("2024-05-01T00:00:00Z").plusSeconds(position * 60L),
                text = "turn $position",
            )
        }
        messages.upsert(conversation, written)
        index.index(written.map { entry(it.externalId, it.text.orEmpty(), dense = 61, at = it.sourceTime) })

        val around = index.neighbours(
            SpanId(RecordKind.MESSAGE, written[2].externalId),
            before = 1,
            after = 1,
        )

        assertEquals(listOf("turn 1", "turn 2", "turn 3"), around.map { it.text }) {
            "Neighbour expansion reads the conversation ordering the corpus already stores, so " +
                "the hit comes back inside its own run of turns rather than alone."
        }
    }

    @Test
    fun `a withdrawn turn is left out of an expansion, and does not spend its slot`(): Unit = runBlocking {
        // **Withdrawal marks the message and leaves the span**, so the index keeps a body the corpus
        // has been told not to quote, and this is the read that hands a body back. The two reads
        // that fetch a message exclude it; so must this one, or a turn the corpus refuses to the
        // thread read arrives through the index instead — in one request, for one message.
        //
        // **Both halves, because they are two assertions and the second is the one a projection-only
        // filter would fail.** Excluding it in the projection alone would let it consume one of the
        // slots the caller asked to be filled with turns it may read, so a window of two would come
        // back holding one.
        val conversation = StoreRig.externalId("withdrawn-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val written = (0..3).map { position ->
            Message(
                externalId = StoreRig.externalId("withdrawn-turn"),
                sourceTime = Instant.parse("2024-08-01T00:00:00Z").plusSeconds(position * 60L),
                text = "said $position",
            )
        }
        messages.upsert(conversation, written)
        index.index(written.map { entry(it.externalId, it.text.orEmpty(), dense = 81, at = it.sourceTime) })
        messages.markWithdrawn(listOf(written[1].externalId))

        val around = index.neighbours(
            SpanId(RecordKind.MESSAGE, written[0].externalId),
            before = 0,
            after = 2,
        )

        assertEquals(listOf("said 0", "said 2", "said 3"), around.map { it.text }) {
            "A withdrawn body came back from the index, or it spent a slot the caller asked to " +
                "be filled with turns it may read."
        }
    }

    @Test
    fun `expansion stops at the start of a conversation rather than reaching into another`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("short-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.DIRECT, name = null))
        val written = (0..1).map { position ->
            Message(
                externalId = StoreRig.externalId("short-turn"),
                sourceTime = Instant.parse("2024-06-01T00:00:00Z").plusSeconds(position * 60L),
                text = "short $position",
            )
        }
        messages.upsert(conversation, written)
        index.index(written.map { entry(it.externalId, it.text.orEmpty(), dense = 71, at = it.sourceTime) })

        val around = index.neighbours(SpanId(RecordKind.MESSAGE, written[0].externalId), before = 5, after = 5)

        assertEquals(listOf("short 0", "short 1"), around.map { it.text })
    }

    @Test
    fun `a record nothing has derived is pending, and marking it is what settles it`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("pending-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val message = Message(
            externalId = StoreRig.externalId("pending-turn"),
            sourceTime = Instant.parse("2024-07-01T00:00:00Z"),
            text = "not yet indexed",
        )
        messages.upsert(conversation, listOf(message))
        val derivation = Derivation(Deriver.INDEX, "model-a")

        assertTrue(message.externalId in pendingNow(derivation))

        derivations.mark(derivation, RecordKind.MESSAGE, listOf(message.externalId), nowFrom(derivation))

        assertTrue(message.externalId !in pendingNow(derivation))
    }

    @Test
    fun `a record with a submission outstanding is owed but not offered`(): Unit = runBlocking {
        // **The two halves of this are opposite, and both are the point.** A record a submission has
        // been billed for is still owed — nothing has been derived from it until the replies are
        // read — so the count has to keep seeing it, or a sweep reads zero, reports the pass caught
        // up, and never reaches the step that collects what it paid for. But it must not be offered
        // again, or the pass classifies and pays for the same stretch twice while waiting on itself.
        //
        // Asserted here rather than against a fake because it is one clause of the selection
        // statement, and a double would only assert that the double skips.
        val conversation = StoreRig.externalId("in-flight-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val message = Message(
            externalId = StoreRig.externalId("in-flight-turn"),
            sourceTime = Instant.parse("2024-10-01T00:00:00Z"),
            text = "classified and waiting",
        )
        messages.upsert(conversation, listOf(message))
        val derivation = Derivation(Deriver.RECOGNITION, "prompt-in-flight")

        assertTrue(message.externalId in pendingNow(derivation))
        val owedBefore = derivations.owed(derivation, RecordKind.MESSAGE, notBefore = null)

        val submission = SubmissionId("batch-${StoreRig.externalId("in-flight-batch").nativeId}")
        submissions.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.RESTATEMENT,
            kind = RecordKind.MESSAGE,
            id = submission,
            requests = listOf(SubmittedWindow("anchor#0", conversation, "commitment|x|plain")),
            records = listOf(message.externalId),
            selectedAt = Instant.parse("2024-10-01T00:00:00Z"),
            version = "prompt-in-flight",
        )

        assertTrue(message.externalId !in pendingNow(derivation)) {
            "A record already paid for was offered again, so the pass would classify it a second " +
                "time while the first reading was still outstanding."
        }
        assertEquals(owedBefore, derivations.owed(derivation, RecordKind.MESSAGE, notBefore = null)) {
            "The count stopped seeing a record that is still owed. A sweep reads the count first " +
                "and stops at zero, so this is how the pass comes to report itself caught up with " +
                "a submission outstanding and never collects it."
        }

        // And offered again once the submission has been read back, because nothing marked it.
        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
        assertTrue(message.externalId in pendingNow(derivation)) {
            "A collected submission that marked nothing has to leave its records selectable, or " +
                "the work is lost with no trace."
        }
    }

    @Test
    fun `a submission of another pass does not hold back this one's selection`(): Unit = runBlocking {
        // Keyed on the pass, so indexing holding a record cannot stop recognition reading it. The
        // two passes derive different things from the same record and neither waits on the other.
        val conversation = StoreRig.externalId("other-pass-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val message = Message(
            externalId = StoreRig.externalId("other-pass-turn"),
            sourceTime = Instant.parse("2024-10-02T00:00:00Z"),
            text = "held by the other pass",
        )
        messages.upsert(conversation, listOf(message))

        val submission = SubmissionId("batch-${StoreRig.externalId("other-pass-batch").nativeId}")
        submissions.record(
            deriver = Deriver.INDEX,
            stage = SubmissionStage.CLASSIFICATION,
            kind = RecordKind.MESSAGE,
            id = submission,
            requests = listOf(SubmittedWindow("anchor", conversation)),
            records = listOf(message.externalId),
            selectedAt = Instant.parse("2024-10-02T00:00:00Z"),
            version = "embeddings-other-pass",
        )

        assertTrue(message.externalId in pendingNow(Derivation(Deriver.RECOGNITION, "prompt-other-pass"))) {
            "An indexing submission held back recognition's selection. The exclusion is per pass, " +
                "because what a record is owed to one pass says nothing about the other."
        }

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `a submission over one kind does not hold back a like-named record of another`(): Unit = runBlocking {
        // **The sources key different kinds alike.** A Telegram forum topic is named by the message
        // that created it, so a conversation and a message carry the same identifier under the same
        // source — and `recognition` is the deriver of two passes, inference over messages and
        // identity over conversations. Without the kind on the submission, an inference submission
        // holding the message would withhold the topic from identity's selection for as long as it
        // was outstanding, and identity would read as stalled with nothing wrong with it.
        val shared = StoreRig.externalId("kind-collision")
        conversations.upsert(Conversation(externalId = shared, kind = ConversationKind.GROUP, name = null))
        messages.upsert(
            shared,
            listOf(Message(externalId = shared, sourceTime = Instant.parse("2024-10-03T00:00:00Z"), text = "topic")),
        )

        val submission = SubmissionId("batch-${StoreRig.externalId("kind-collision-batch").nativeId}")
        submissions.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.CLASSIFICATION,
            kind = RecordKind.MESSAGE,
            id = submission,
            requests = listOf(SubmittedWindow("anchor", shared)),
            records = listOf(shared),
            selectedAt = Instant.parse("2024-10-03T00:00:00Z"),
            version = "prompt-kind-collision",
        )
        val derivation = Derivation(Deriver.RECOGNITION, "prompt-kind-collision")

        assertTrue(shared !in pendingNow(derivation)) {
            "The message the submission holds has to be withheld, or this case cannot tell the " +
                "kinds apart from the exclusion being broken altogether."
        }
        assertTrue(shared in pendingNow(derivation, RecordKind.CONVERSATION)) {
            "A message submission withheld the like-named conversation. The identity pass shares " +
                "this deriver and would read as stalled for as long as the submission was in flight."
        }

        submissions.settle(submission, TokenUsage(inputTokens = 1, outputTokens = 1))
    }

    @Test
    fun `changing the version makes the record pending again, and the other pass is untouched`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("version-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val message = Message(
            externalId = StoreRig.externalId("version-turn"),
            sourceTime = Instant.parse("2024-08-01T00:00:00Z"),
            text = "indexed once",
        )
        messages.upsert(conversation, listOf(message))

        val first = Derivation(Deriver.INDEX, "model-a")
        val recognition = Derivation(Deriver.RECOGNITION, "prompt-a")
        derivations.mark(first, RecordKind.MESSAGE, listOf(message.externalId), nowFrom(first))
        derivations.mark(recognition, RecordKind.MESSAGE, listOf(message.externalId), nowFrom(recognition))

        val second = Derivation(Deriver.INDEX, "model-b")
        assertTrue(message.externalId in pendingNow(second)) {
            "A record marked at one model version has to come back as pending under another: " +
                "that is what makes a model change re-index the corpus."
        }
        assertTrue(message.externalId !in pendingNow(recognition)) {
            "Changing the indexing version invalidated recognition's mark as well. The " +
                "watermark is keyed per pass precisely so a change invalidates only its own."
        }
    }

    @Test
    fun `changed content makes a derived record pending again`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("edited-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val externalId = StoreRig.externalId("edited-turn")
        val at = Instant.parse("2024-09-01T00:00:00Z")
        messages.upsert(conversation, listOf(Message(externalId = externalId, sourceTime = at, text = "before")))
        val derivation = Derivation(Deriver.INDEX, "model-a")
        derivations.mark(derivation, RecordKind.MESSAGE, listOf(externalId), nowFrom(derivation))
        assertTrue(externalId !in pendingNow(derivation))

        messages.upsert(conversation, listOf(Message(externalId = externalId, sourceTime = at, text = "after")))

        assertTrue(externalId in pendingNow(derivation)) {
            "An edited message stayed settled. Content time is what invalidates a mark, and a " +
                "record whose text moved under a current derivation has to be derived again."
        }
    }

    @Test
    fun `re-seeing a record unchanged leaves its mark alone`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("resync-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val externalId = StoreRig.externalId("resync-turn")
        val at = Instant.parse("2024-10-01T00:00:00Z")
        val message = Message(externalId = externalId, sourceTime = at, text = "unchanged")
        messages.upsert(conversation, listOf(message))
        val derivation = Derivation(Deriver.INDEX, "model-a")
        derivations.mark(derivation, RecordKind.MESSAGE, listOf(externalId), nowFrom(derivation))

        // The same record delivered again, as a full resync would deliver the whole corpus.
        messages.upsert(conversation, listOf(message))

        assertTrue(externalId !in pendingNow(derivation)) {
            "A resync re-derived the corpus. Last-seen advances on every pass and content time " +
                "does not; keying invalidation off the wrong one re-derives everything each run."
        }
    }

    @Test
    fun `a horizon excludes older material, and no horizon excludes nothing`(): Unit = runBlocking {
        // The mechanism inference bounds itself with. Indexing is local compute and reaches back
        // to the first backfilled record, so it passes nothing; inference spends model tokens per
        // window and asks only for material inside its recency horizon. Without this, a pass
        // wanting recent records would read the oldest ones first — the order everything else
        // here depends on — and never reach the horizon at all.
        val conversation = StoreRig.externalId("horizon-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val old = StoreRig.externalId("horizon-old")
        val recent = StoreRig.externalId("horizon-recent")
        messages.upsert(
            conversation,
            listOf(
                Message(externalId = old, sourceTime = Instant.parse("2019-01-01T00:00:00Z"), text = "old"),
                Message(externalId = recent, sourceTime = Instant.parse("2026-01-01T00:00:00Z"), text = "recent"),
            ),
        )
        val derivation = Derivation(Deriver.RECOGNITION, "inference-a")

        val bounded = derivations.pending(
            derivation,
            RecordKind.MESSAGE,
            limit = 10_000,
            notBefore = Instant.parse("2025-01-01T00:00:00Z"),
        ).records

        assertTrue(recent in bounded, "A record inside the horizon was not offered.")
        assertTrue(old !in bounded, "A record from 2019 was offered under a 2025 horizon.")
        val unbounded = derivations.pending(derivation, RecordKind.MESSAGE, limit = 10_000, notBefore = null).records
        assertTrue(old in unbounded) {
            "With no horizon the same record has to be offered, or indexing would silently stop " +
                "covering the corpus the moment inference gained a bound."
        }
    }

    /**
     * The lag, which is what a bounded read cannot answer.
     *
     * Both halves of this assertion matter and they are the same statement seen twice: the count
     * rises with the backlog while a page of it stops at the limit. A driver reads a fall in the
     * count as progress and a reader reads the count as how far behind a pass is, and a saturating
     * figure serves neither — it says whether there is work and never how much.
     */
    @Test
    fun `the lag counts the whole backlog where a page of it saturates`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("lag-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        // A version of this pass nothing has ever run, so every message in the shared rig is owed
        // to it — which is why these assertions are on the difference three more records make
        // rather than on an absolute number.
        val derivation = Derivation(Deriver.INDEX, "model-for-the-lag")
        val before = derivations.owed(derivation, RecordKind.MESSAGE, notBefore = null)
        val added = (1..3).map { StoreRig.externalId("lag-turn-$it") }
        messages.upsert(
            conversation,
            added.map { Message(externalId = it, sourceTime = Instant.parse("2024-11-01T00:00:00Z"), text = "owed") },
        )

        val owed = derivations.owed(derivation, RecordKind.MESSAGE, notBefore = null)

        assertEquals(before + added.size, owed, "The lag did not count the records that were added.")
        assertEquals(2, derivations.pending(derivation, RecordKind.MESSAGE, limit = 2, notBefore = null).records.size) {
            "A page of the backlog did not stop at its limit, which is the premise this test " +
                "rests on: with $owed owed, a bounded read of two has to answer two."
        }

        derivations.mark(derivation, RecordKind.MESSAGE, added, nowFrom(derivation))

        assertEquals(before, derivations.owed(derivation, RecordKind.MESSAGE, notBefore = null)) {
            "Marking the records did not bring the lag back down, so a driver watching it would " +
                "never see a batch make progress."
        }
    }

    @Test
    fun `the lag is bounded by the horizon exactly as selection is`(): Unit = runBlocking {
        // The two have to agree. Inference loops until the lag is zero while selecting under its
        // horizon, so a count that ignored the horizon would never reach zero and the pass would
        // sweep on material it is not allowed to read.
        val conversation = StoreRig.externalId("lag-horizon-thread")
        conversations.upsert(Conversation(externalId = conversation, kind = ConversationKind.GROUP, name = null))
        val derivation = Derivation(Deriver.RECOGNITION, "inference-for-the-lag")
        val horizon = Instant.parse("2025-01-01T00:00:00Z")
        val before = derivations.owed(derivation, RecordKind.MESSAGE, notBefore = horizon)
        messages.upsert(
            conversation,
            listOf(
                Message(
                    externalId = StoreRig.externalId("lag-old"),
                    sourceTime = Instant.parse("2019-01-01T00:00:00Z"),
                    text = "outside",
                ),
                Message(
                    externalId = StoreRig.externalId("lag-recent"),
                    sourceTime = Instant.parse("2026-01-01T00:00:00Z"),
                    text = "inside",
                ),
            ),
        )

        assertEquals(before + 1, derivations.owed(derivation, RecordKind.MESSAGE, notBefore = horizon)) {
            "Two records were added and only one is inside the horizon, so the bounded lag has " +
                "to rise by one. A lag counting both would hold a pass at a number it can never " +
                "work down."
        }
    }

    /**
     * Every record of [kind] currently owed to this pass, messages unless another is named. Paged
     * generously: the rig shares one database.
     */
    private suspend fun pendingNow(
        derivation: Derivation,
        kind: RecordKind = RecordKind.MESSAGE,
    ): List<ExternalId> = derivations.pending(derivation, kind, limit = 10_000, notBefore = null).records

    /**
     * The store's clock, as the pass would have taken it when selecting the work.
     *
     * Read through `pending` rather than from this process, because that is where the real
     * caller gets it and because the comparison it feeds is against the store's own
     * `content_time`. A clock from here would be a different clock.
     */
    private suspend fun nowFrom(derivation: Derivation): Instant =
        derivations.pending(derivation, RecordKind.MESSAGE, limit = 1, notBefore = null).asOf

    private fun entry(
        record: ExternalId,
        text: String,
        dense: Int = 1,
        sparse: Map<Int, Float> = emptyMap(),
        at: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ) = IndexEntry(
        span = Span(id = SpanId(RecordKind.MESSAGE, record), text = text, sourceTime = at),
        embedding = Embedding(dense = denseAt(dense), sparse = sparse),
    )

    /**
     * A dense vector pointing along one axis.
     *
     * Deliberately not a realistic embedding. What the search tests need is directions whose
     * cosine distance is known exactly — identical axes are zero apart, different axes are
     * maximally apart — so a ranking assertion is about the fusion rather than about whether
     * two made-up vectors happened to be near each other.
     */
    private fun denseAt(axis: Int): List<Float> =
        List(JdbcIndexRepository.DENSE_WIDTH) { if (it == axis) 1.0f else 0.0f }

    private companion object {
        /** Small enough that a crowd of a realistic size fills a half's candidate window. */
        const val SEARCH_LIMIT = 5

        /** The direction a one-sided query points along, and the dense crowd sits on. */
        const val QUERY_AXIS = 101

        /** Sharing no component with [QUERY_AXIS]: cosine distance one. */
        const val FAR_AXIS = 202

        /** A third direction, so the lexical crowd competes on terms and not on direction. */
        const val THIRD_AXIS = 303
    }
}
