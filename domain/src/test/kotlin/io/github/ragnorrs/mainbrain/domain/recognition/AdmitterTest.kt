package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider.Reply
import io.github.ragnorrs.mainbrain.domain.FakeSpendLedger
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.Admission
import io.github.ragnorrs.mainbrain.domain.entity.AdmissionRepository
import io.github.ragnorrs.mainbrain.domain.entity.Standing
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pass that judges which named conversations are bodies of work, against fakes.
 *
 * Fakes rather than a store, for the reason the identity pass is tested against them: what this
 * class decides is which conversations it asks about, what it writes for each answer, and what it
 * settles — none of which a real Postgres makes more convincing, while the answers it has to be
 * driven to (a refusal, a truncation, a reply carrying no verdict) are unreachable against a live
 * provider on demand.
 *
 * **What is deliberately not asserted here is the quality of the judgement.** Whether the
 * instruction separates a client's thread from a thematic track is a measurement against real
 * material and a live model, not something a scripted reply can show. What these cases hold is
 * everything around it: that the verdict reaches the store, that the record is marked only when the
 * answer settles it, that the spend is charged, and that an unjudged conversation is left exactly
 * as it was.
 */
class AdmitterTest {

    @Test
    fun `a named conversation is judged and then marked`(): Unit = runBlocking {
        val room = id("client-thread")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            replies = listOf(Reply.of("BODY_OF_WORK")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.judged)
        assertEquals(0, did.rejected)
        assertEquals(
            Admission(room, Standing.BODY_OF_WORK, world.admitter().version()),
            world.admissions.stored.getValue(room),
        )
        assertEquals(
            listOf("verdict", "mark"),
            world.order,
            "A conversation was marked before its verdict was written. A failure between the two " +
                "then settles it against a judgement nobody recorded, and no later sweep revisits it.",
        )
    }

    @Test
    fun `a thematic track is recorded as one and counted`(): Unit = runBlocking {
        val room = id("track")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "Referrals")),
            replies = listOf(Reply.of("THEMATIC_TRACK")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.rejected)
        assertEquals(Standing.THEMATIC_TRACK, world.admissions.stored.getValue(room).standing)
    }

    @Test
    fun `the name and a bounded sample of the conversation are what the model is shown`(): Unit = runBlocking {
        // The plan this card follows: a name alone does not separate a body of work from a track,
        // so the judgement is made on the name together with what is being said under it. A
        // request carrying only the name would be the word list this pass exists to replace.
        val room = id("sampled")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            messages = mapOf(room to (1..10).map { line(room, "line $it") }),
            replies = listOf(Reply.of("BODY_OF_WORK")),
            bounds = AdmissionBounds(sample = 3),
        )

        world.admitter().admitBatch()

        val asked = world.model.requests.single().prompt
        assertTrue(asked.contains("A Client"), "the name is not in front of the model: $asked")
        assertTrue(asked.contains("line 10"), "the newest message is not in the sample: $asked")
        assertFalse(asked.contains("line 7"), "the sample reaches past the bound it was given: $asked")
        assertEquals(3, world.messages.asked.single().second, "the bound did not reach the store")
    }

    @Test
    fun `a two-person exchange and an unnamed conversation are settled without a call`(): Unit = runBlocking {
        // Identity reads no client from either, so there is nothing here to narrow — and paying to
        // answer a question nobody asked is the one thing a deferred pass must not do. Marking them
        // is what keeps them from being offered on every sweep for ever.
        val direct = id("direct")
        val unnamed = id("unnamed")
        val world = World(
            pending = listOf(direct, unnamed),
            stored = listOf(
                Conversation(direct, ConversationKind.DIRECT, "Someone's Name"),
                Conversation(unnamed, ConversationKind.GROUP, null),
            ),
        )

        val did = world.admitter().admitBatch()

        assertEquals(0, world.model.callCount, "a conversation nothing reads a client from was paid for")
        assertEquals(2, did.settled)
        assertEquals(0, did.judged)
        assertEquals(setOf(direct, unnamed), world.derivations.marked.toSet())
        assertEquals(TokenUsage.NONE, world.spend.charged)
    }

    @Test
    fun `an exhausted day still settles what costs nothing and judges nothing`(): Unit = runBlocking {
        // The driver runs one more batch with the spending withheld when the day's allowance is
        // gone. The free half is honest progress, so the lag falls and the driver reads a pass that
        // is waiting for tomorrow rather than one that has stalled.
        val direct = id("direct")
        val room = id("named")
        val world = World(
            pending = listOf(direct, room),
            stored = listOf(
                Conversation(direct, ConversationKind.DIRECT, "Someone's Name"),
                named(room, "A Client"),
            ),
            replies = listOf(Reply.of("THEMATIC_TRACK")),
        )

        val did = world.admitter().admitBatch(maySpend = false)

        assertEquals(0, world.model.callCount, "the pass spent on a day whose allowance is gone")
        assertEquals(1, did.settled)
        assertEquals(listOf(direct), world.derivations.marked)
        assertTrue(world.admissions.stored.isEmpty())
    }

    @Test
    fun `every call is charged to the day, under this pass and not another`(): Unit = runBlocking {
        // **The only place this pass's spending is written down.** Every other model call in the
        // build is billed against a stored submission, which is what a day's total is read from; a
        // pass calling the provider directly and charging nothing would spend against a bound that
        // could not see it.
        val one = id("one")
        val other = id("other")
        val cost = TokenUsage(inputTokens = 300, outputTokens = 7)
        val world = World(
            pending = listOf(one, other),
            stored = listOf(named(one, "A Client"), named(other, "Another Client")),
            replies = listOf(Reply.of("BODY_OF_WORK", cost)),
        )

        val did = world.admitter().admitBatch()

        assertEquals(listOf(Deriver.ADMISSION, Deriver.ADMISSION), world.spend.charges.map { it.first })
        assertEquals(TokenUsage(inputTokens = 600, outputTokens = 14), world.spend.charged)
        assertEquals(TokenUsage(inputTokens = 600, outputTokens = 14), did.usage)
    }

    @Test
    fun `a failure part-way through a batch keeps what the earlier calls bought`(): Unit = runBlocking {
        // Without the per-conversation write, a transport failure on the second call would discard
        // the first conversation's verdict and the next sweep would pay for it again.
        val one = id("one")
        val other = id("other")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(named(one, "A Client"), named(other, "Another Client")),
            replies = listOf(
                Reply.of("BODY_OF_WORK"),
                Reply.Fails(ModelUnavailable("the provider gave up")),
            ),
        )

        assertFailsWith<ModelUnavailable> { world.admitter().admitBatch() }

        assertEquals(setOf(one), world.admissions.stored.keys)
        assertEquals(listOf(one), world.derivations.marked)
    }

    @Test
    fun `a reply carrying no verdict leaves the conversation owed`(): Unit = runBlocking {
        val room = id("unreadable")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            replies = listOf(Reply.of("I am not sure what this conversation is for.")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.unreadable)
        assertEquals(0, did.settled)
        assertTrue(world.derivations.marked.isEmpty(), "a conversation was settled on an answer nobody read")
        assertTrue(world.admissions.stored.isEmpty())
    }

    @Test
    fun `a reply naming both verdicts is no verdict`(): Unit = runBlocking {
        // The reading is by which word is present rather than by position, so a reply that hedges
        // across both has to be refused rather than resolved to whichever came first.
        val room = id("hedged")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            replies = listOf(Reply.of("This is a BODY_OF_WORK, though it could be a THEMATIC_TRACK.")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.unreadable)
        assertTrue(world.admissions.stored.isEmpty())
    }

    @Test
    fun `a verdict surrounded by prose is still read`(): Unit = runBlocking {
        // The instruction asks for the word alone, and a reply that adds a sentence around the
        // right word is an answer worth keeping rather than one to throw away and pay for again.
        val room = id("chatty")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "Banks")),
            replies = listOf(Reply.of("Looking at the material, the answer is THEMATIC_TRACK.")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.rejected)
        assertEquals(Standing.THEMATIC_TRACK, world.admissions.stored.getValue(room).standing)
    }

    @Test
    fun `a truncated reply leaves the conversation owed, and a refusal settles it`(): Unit = runBlocking {
        // The split every stage that calls the model makes: the provider refuses the same request
        // again, so asking twice buys the same nothing, while a reply that ran out of room is
        // answerable by a build that asks for more. A settled refusal carries no verdict, which
        // reads as admitted — the direction that leaves the corpus as it was.
        val cut = id("cut")
        val refused = id("refused")
        val world = World(
            pending = listOf(cut, refused),
            stored = listOf(named(cut, "A Client"), named(refused, "Another Client")),
            replies = listOf(Reply.truncated("BODY"), Reply.refused("as other")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.unread)
        assertEquals(listOf("as other"), did.declined)
        assertEquals(listOf(refused), world.derivations.marked)
        assertTrue(world.admissions.stored.isEmpty(), "a refusal wrote a verdict nobody reached")
    }

    @Test
    fun `nothing pending is nothing done, and nothing asked`(): Unit = runBlocking {
        val world = World(pending = emptyList(), stored = emptyList())

        val did = world.admitter().admitBatch()

        assertEquals(Admissions.NOTHING, did)
        assertEquals(0, world.model.callCount)
    }

    @Test
    fun `the batch is bounded, and what it leaves stays owed`(): Unit = runBlocking {
        val rooms = (1..5).map { id("room-$it") }
        val world = World(
            pending = rooms,
            stored = rooms.map { named(it, "Client $it") },
            replies = listOf(Reply.of("BODY_OF_WORK")),
            bounds = AdmissionBounds(batchSize = 2),
        )

        val did = world.admitter().admitBatch()

        assertEquals(2, did.judged)
        assertEquals(3, world.admitter().owed())
    }

    @Test
    fun `the version moves with the model, the sample and nothing else`() {
        val world = World(pending = emptyList(), stored = emptyList())
        val first = world.admitter().version()

        assertEquals(first, world.admitter().version(), "the version is not stable between two reads")

        world.model.version = "another-model"
        assertTrue(world.admitter().version() != first, "a changed model does not retire a verdict")

        world.model.version = "fake-model"
        val wider = world.admitter(bounds = AdmissionBounds(sample = AdmissionBounds().sample + 1)).version()
        assertTrue(
            wider != first,
            "A conversation judged on more of itself is judged differently, and a version that " +
                "did not move would leave the corpus holding both readings keyed alike.",
        )
    }

    @Test
    fun `the version names its own stage`() {
        // Two stages' versions are opaque strings built the same way, and this one shares no column
        // with another — but a figure identifying itself by a model and a hash alone is one nobody
        // reading a row can place.
        val world = World(pending = emptyList(), stored = emptyList())

        assertTrue(world.admitter().version().startsWith("admission:"))
    }

    @Test
    fun `the pass marks under its own deriver, and never recognition's`(): Unit = runBlocking {
        // **The reason this is a pass of its own.** A mark is one row per pass, record kind and
        // record, so judging conversations under recognition's identifier would have this and the
        // identity stage overwrite each other's marks — and both would re-read the whole
        // conversation table on every sweep for ever, one of them at the model's prices.
        val room = id("room")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            replies = listOf(Reply.of("BODY_OF_WORK")),
        )

        world.admitter().admitBatch()
        world.admitter().owed()

        assertEquals(setOf(Deriver.ADMISSION), world.derivations.derivers)
    }

    @Test
    fun `it reaches back over the whole corpus, with no horizon`(): Unit = runBlocking {
        // A body of work discussed years ago is still one, and the conversations of an archive are a
        // handful against its messages. A horizon here would leave the oldest labels unjudged for
        // ever while the answer path went on offering them.
        val room = id("old")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            replies = listOf(Reply.of("BODY_OF_WORK")),
        )

        world.admitter().admitBatch()
        world.admitter().owed()

        assertEquals(listOf<Instant?>(null, null), world.derivations.horizons)
    }

    @Test
    fun `a conversation with nothing readable under it is still asked about and settled`(): Unit = runBlocking {
        // Otherwise it is offered on every sweep for ever and the lag never falls to zero, which the
        // driver reads as a stall. The instruction's default answer covers the case, and the safe
        // direction is the one it errs in.
        val room = id("silent")
        val world = World(
            pending = listOf(room),
            stored = listOf(named(room, "A Client")),
            messages = mapOf(room to listOf(line(room, null), line(room, "   "))),
            replies = listOf(Reply.of("BODY_OF_WORK")),
        )

        val did = world.admitter().admitBatch()

        assertEquals(1, did.settled)
        val asked = world.model.requests.single().prompt
        assertFalse(asked.contains("-    "), "a blank line was sent as material: $asked")
        assertEquals(0, world.admitter().owed())
    }

    @Test
    fun `the batch and the sample cannot be nothing`() {
        assertFailsWith<IllegalArgumentException> { AdmissionBounds(batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { AdmissionBounds(sample = 0) }
    }

    @Test
    fun `the instruction states the default, and does not list labels to match`() {
        // **The one property of the wording worth pinning here.** What separates the two kinds is
        // not a word list — the same label is a client in one archive and a category in another —
        // and the asymmetry of the two mistakes is why an unsettled sample has to answer the way
        // that changes nothing. Both are in the instruction and neither is observable anywhere else
        // without a live model.
        val instruction = Admitter.JUDGE_SYSTEM

        assertTrue(
            instruction.contains("Answer BODY_OF_WORK whenever the material does not settle"),
            "the instruction does not state which way an unsettled judgement falls",
        )
        assertTrue(
            instruction.contains("by way of example only", ignoreCase = true),
            "the instruction's labels are not marked as examples, so they read as a list to match",
        )
    }

    private fun id(label: String) = ExternalId(Source.TELEGRAM, label)

    private fun named(room: ExternalId, name: String) = Conversation(room, ConversationKind.GROUP, name)

    private fun line(room: ExternalId, text: String?) = Message(
        externalId = ExternalId(room.source, "${room.nativeId}-${text?.hashCode() ?: 0}"),
        sourceTime = Instant.parse("2026-01-01T00:00:00Z"),
        text = text,
    )

    /**
     * The pass and its fakes, assembled.
     *
     * One object rather than six locals per case, because every case needs the same five ports and
     * what differs between them is the scripted reply and what the store holds.
     */
    private class World(
        pending: List<ExternalId>,
        stored: List<Conversation>,
        messages: Map<ExternalId, List<Message>> = emptyMap(),
        replies: List<Reply> = listOf(Reply.of("BODY_OF_WORK")),
        private val bounds: AdmissionBounds = AdmissionBounds(),
    ) {
        val order = mutableListOf<String>()
        val derivations = FakeDerivations(pending, order)
        val conversations = FakeConversations(stored)
        val messages = FakeMessages(messages)
        val admissions = FakeAdmissions(order)
        val model = FakeModelProvider(replies)
        val spend = FakeSpendLedger()

        fun admitter(bounds: AdmissionBounds = this.bounds) = Admitter(
            derivations = derivations,
            conversations = conversations,
            messages = messages,
            admissions = admissions,
            model = model,
            spend = spend,
            bounds = bounds,
        )
    }

    private class FakeDerivations(
        private var owed: List<ExternalId>,
        private val order: MutableList<String>,
    ) : DerivationStateRepository {
        val marked = mutableListOf<ExternalId>()
        val derivers = mutableSetOf<Deriver>()

        /** Every horizon the pass asked under, so that *no horizon* is a property and not a hope. */
        val horizons = mutableListOf<Instant?>()

        override suspend fun pending(
            derivation: Derivation,
            kind: RecordKind,
            limit: Int,
            notBefore: Instant?,
        ): Pending {
            derivers += derivation.deriver
            horizons += notBefore
            require(kind == RecordKind.CONVERSATION) { "This pass reads conversations and nothing else." }
            return Pending(records = owed.take(limit), asOf = SELECTED_AT)
        }

        override suspend fun mark(
            derivation: Derivation,
            kind: RecordKind,
            records: List<ExternalId>,
            asOf: Instant,
        ) {
            order += "mark"
            derivers += derivation.deriver
            require(asOf == SELECTED_AT) {
                "A mark stamped with anything but the moment the work was selected settles a " +
                    "record against material nothing read."
            }
            marked += records
            owed = owed - records.toSet()
        }

        override suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int {
            derivers += derivation.deriver
            horizons += notBefore
            return owed.size
        }

        private companion object {
            val SELECTED_AT: Instant = Instant.parse("2026-01-01T12:00:00Z")
        }
    }

    private class FakeConversations(stored: List<Conversation>) : ConversationRepository {
        private val rooms = stored.associateBy { it.externalId }

        override suspend fun conversations(externalIds: List<ExternalId>): List<Conversation> =
            externalIds.mapNotNull { rooms[it] }

        override suspend fun upsert(conversation: Conversation) = Unit
        override suspend fun find(externalId: ExternalId): Conversation? = rooms[externalId]
        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = null
        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = emptyList()
    }

    /**
     * The corpus's messages, with the tail read the way the store reads it.
     *
     * The real [MessageRepository.latestIn] selects the newest and returns them oldest first, and
     * this keeps that: a fake that returned the head instead would make the sample case pass while
     * the pass showed the model the start of every conversation.
     */
    private class FakeMessages(private val stored: Map<ExternalId, List<Message>>) : MessageRepository {
        val asked = mutableListOf<Pair<ExternalId, Int>>()

        override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> {
            asked += conversation to limit
            return stored[conversation].orEmpty().takeLast(limit)
        }

        override suspend fun upsert(conversation: ExternalId, messages: List<Message>) = unexpected()
        override suspend fun messagesIn(conversation: ExternalId): List<Message> = unexpected()
        override suspend fun messages(externalIds: List<ExternalId>): List<Message> = unexpected()
        override suspend fun conversationsOf(messages: List<ExternalId>) = unexpected()
        override suspend fun markWithdrawn(externalIds: List<ExternalId>) = unexpected()
        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = unexpected()
        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = unexpected()
        override suspend fun sharingAConversation(authors: List<ExternalId>) = unexpected()

        /**
         * The unbounded read is the one thing this pass must not make.
         *
         * A conversation of a real archive runs to tens of thousands of messages, and reading all
         * of them to show a model the last few of them is the cost the bounded read exists to
         * avoid — a cost nothing else here would notice.
         */
        private fun unexpected(): Nothing =
            error("The admission pass reads a bounded tail and nothing else of the corpus.")
    }

    private class FakeAdmissions(private val order: MutableList<String>) : AdmissionRepository {
        val stored = mutableMapOf<ExternalId, Admission>()

        override suspend fun record(admissions: List<Admission>) {
            order += "verdict"
            admissions.forEach { stored[it.conversation] = it }
        }

        override suspend fun of(conversations: List<ExternalId>): List<Admission> =
            conversations.mapNotNull { stored[it] }
    }

    @Test
    fun `a verdict carries a version, so a blank one cannot be stored`() {
        // The version is what tells a verdict a better instruction has replaced from one it has not,
        // and it is opaque — so the only thing the type can refuse is nothing at all.
        assertFailsWith<IllegalArgumentException> {
            Admission(id("room"), Standing.BODY_OF_WORK, " ")
        }
    }
}
