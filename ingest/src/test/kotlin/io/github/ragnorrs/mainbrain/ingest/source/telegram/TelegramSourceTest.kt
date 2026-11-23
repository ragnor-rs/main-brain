package io.github.ragnorrs.mainbrain.ingest.source.telegram

import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.UnknownWorkUnit
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import io.github.ragnorrs.mainbrain.ingest.IngestStateLog
import io.github.ragnorrs.mainbrain.ingest.MovableClock
import io.github.ragnorrs.mainbrain.ingest.PlanInMemory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The adapter, driven against a scripted platform.
 *
 * Every test here is one or more asks: the port's unit of work is a batch, and the contract it
 * carries — nothing is acknowledged until the next ask — is only visible across two of them. So
 * the shape of most of these is *ask, assert what was handed over, ask again, assert what was then
 * recorded*, which is also how the scheduler drives this in production.
 *
 * What is deliberately not here: the store's behaviour, which is asserted in `:data` against
 * Postgres, and the mapping rules, which are pure functions and are asserted in `RecordsTest`.
 * This is the layer between them — paging, pacing, placement, and what a failure costs.
 */
class TelegramSourceTest {

    private val clock = MovableClock()
    private val plan = PlanInMemory()
    private val state = IngestStateLog()
    private val client = ScriptedTelegramClient()

    private val unit = WorkUnitId("100")
    private val day = Instant.parse("2026-02-01T10:00:00Z")

    /** Every wait the adapter asked for, in order, and the clock moved by each. */
    private val waits = mutableListOf<Duration>()

    private fun source(
        excluded: Set<Long> = emptySet(),
        only: Set<Long> = emptySet(),
        limits: TelegramLimits = limits(),
    ) = TelegramSource(
        // Eagerly, because every test here drives a pass and so has a session by definition. The
        // one path that must not open one is the re-read, which is asserted where it is wired.
        client = lazyOf(client),
        plan = plan,
        state = state,
        excluded = ExcludedConversations(excluded, only),
        limits = limits,
        // Time passes because the adapter asked it to, which is what makes a pace assertable
        // rather than something a test has to live through.
        timing = Timing(
            clock = clock,
            pause = { asked ->
                waits += asked
                clock.advance(asked)
            },
        ),
    )

    /**
     * Pacing that does not pace, unless a test is about pacing.
     *
     * Both intervals default to nothing so that one ask is one pass: a test about placement would
     * otherwise be asserting over an interval it never meant to set.
     */
    private fun limits(
        historyPage: Int = 2,
        betweenPages: Duration = Duration.ZERO,
        failureCeiling: Int = 5,
        reportEvery: Duration = Duration.ZERO,
        reachBackTo: Instant? = null,
    ) = TelegramLimits(
        historyPage = historyPage,
        betweenPages = betweenPages,
        failureCeiling = failureCeiling,
        reportEvery = reportEvery,
        reachBackTo = reachBackTo,
    )

    private fun chat(id: Long, title: String? = null, newest: Instant? = null) =
        TelegramConversation(
            chatId = id,
            kind = TelegramConversationKind.DIRECT,
            title = title,
            lastMessageAt = newest,
        )

    /**
     * The plan a previous process left behind: this unit walked to the end and complete to a time.
     *
     * Set through the port rather than by writing a row, so a test cannot express a plan the store
     * would refuse — the front edge only moves forward and the back edge only moves back, and a
     * fixture built around either rule would agree with an adapter that broke it.
     */
    private suspend fun finished(unit: WorkUnitId, through: Instant) {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached = day, finished = true)
        plan.reportFront(Source.TELEGRAM, unit, through)
    }

    /** A message whose time rises with its number, as the platform's own numbering does. */
    private fun message(chatId: Long, id: Long, author: Long? = null, topicId: Long? = null, text: String? = null) =
        TelegramMessage(
            chatId = chatId,
            messageId = id,
            topicId = topicId,
            authorId = author,
            sentAt = day.plusSeconds(id),
            text = text,
        )

    private fun nativeIds(conversation: io.github.ragnorrs.mainbrain.domain.record.ConversationUpdate) =
        conversation.messages.map { it.externalId.nativeId }

    @Test
    fun `a backfill yields a conversation with its messages nested and the people behind them`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2, author = 77), message(100, 3)))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty", handles = listOf("Account")))

        val batch = source().next()!!

        assertEquals(listOf("100"), batch.conversations.map { it.conversation.externalId.nativeId })
        assertEquals(listOf("100/3", "100/2"), nativeIds(batch.conversations.single()))
        assertEquals(listOf(ExternalId(Source.TELEGRAM, "77")), batch.persons.map { it.externalId })
    }

    /**
     * The contract the whole adapter is shaped around.
     *
     * Reporting the ground at the moment of handing a batch over would, after a crash in between,
     * leave the plan claiming history the corpus never received — a gap nothing detects and only
     * a re-read could close. So the first ask must record nothing, and the second must record what
     * the first handed over.
     */
    @Test
    fun `the walk goes back a page at a time and records ground only once asked again`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3)))
        val telegram = source()

        assertEquals(listOf("100/3", "100/2"), nativeIds(telegram.next()!!.conversations.single()))
        assertNull(plan.planFor(Source.TELEGRAM, unit)!!.reached)

        assertEquals(listOf("100/1"), nativeIds(telegram.next()!!.conversations.single()))
        assertEquals(day.plusSeconds(2), plan.planFor(Source.TELEGRAM, unit)!!.reached)

        assertNull(telegram.next())
        assertNull(telegram.next())
        val item = plan.planFor(Source.TELEGRAM, unit)!!
        assertTrue(item.finished)
        assertEquals(day.plusSeconds(1), item.reached)
    }

    /**
     * A restart resumes from the plan, which is the whole reason the plan holds a time.
     *
     * The cursor a walk pages by is a message number and it dies with the process. Were there
     * nothing else, every restart would walk each archive from the newest again — upserting
     * harmlessly and spending the one budget this system exists to protect.
     */
    @Test
    fun `a restart resumes below the time the plan holds rather than from the newest`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3)))
        source().let { first ->
            first.next()
            first.next()
        }
        client.historyAsks.clear()
        client.positionAsks.clear()

        source().next()

        assertEquals(listOf(100L to day.plusSeconds(2)), client.positionAsks)
        assertEquals(listOf(Triple(100L, 2L, 2)), client.historyAsks)
    }

    @Test
    fun `an incremental pass puts a new message under the conversation it belongs to`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        val telegram = source()
        telegram.next()
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77)))

        val batch = telegram.next()!!

        assertEquals(listOf("100/9"), nativeIds(batch.conversations.single()))
    }

    /**
     * An edit arrives on the same feed as an arrival, and is one to the corpus as well.
     *
     * The adapter sends the message as it now reads and says nothing about the edit: whether
     * content time moves is the store's decision, taken by comparing what it holds against what
     * arrives. An adapter that also sent an edit time would be a second opinion on it.
     */
    @Test
    fun `an edit is handed over as the message now reads, at the time it was sent`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        val telegram = source()
        telegram.next()
        val edited = message(100, 9, text = "the amended wording").copy(editedAt = day.plusSeconds(400))
        client.feed(TelegramChange.Upserted(edited))

        val record = telegram.next()!!.conversations.single().messages.single()

        assertEquals("the amended wording", record.text)
        assertEquals(day.plusSeconds(9), record.sourceTime)
    }

    /**
     * A removal is recorded and destroys nothing, so it crosses as identifiers.
     *
     * The platform has no content left to give, and the corpus removes nothing — so the narrowest
     * statement the batch can carry is *the source said these records are gone*.
     */
    @Test
    fun `a removal is handed over as identifiers with no records behind them`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        val telegram = source()
        telegram.next()
        client.feed(TelegramChange.Removed(100, listOf(4, 5)))

        val batch = telegram.next()!!

        assertEquals(listOf("100/4", "100/5"), batch.withdrawn.map { it.nativeId })
        assertTrue(batch.conversations.all { it.messages.isEmpty() })
    }

    /**
     * The two excluded chats, refused at every door at once.
     *
     * The corpus, the plan and the withdrawal path are three separate ways in, and an exclusion
     * that held at one of them would read as working while material arrived through another. The
     * withdrawal is the one worth stating: a removal carries no message to ask about, so it is
     * filtered by chat or not at all — and recording a withdrawal against a record never ingested
     * would write a statement about material the corpus does not hold.
     */
    @Test
    fun `an excluded chat reaches neither the corpus, the plan, nor a withdrawal`(): Unit = runBlocking {
        client.chats = listOf(chat(900), chat(100))
        client.archive = mapOf(900L to listOf(message(900, 1)), 100L to listOf(message(100, 7)))
        client.feed(TelegramChange.Upserted(message(900, 2)), TelegramChange.Removed(900, listOf(3)))

        val batch = source(excluded = setOf(900)).next()!!

        assertEquals(listOf("100"), batch.conversations.map { it.conversation.externalId.nativeId })
        assertTrue(batch.withdrawn.isEmpty())
        assertNull(plan.planFor(Source.TELEGRAM, WorkUnitId("900")))
        assertTrue(client.historyAsks.none { (chatId, _, _) -> chatId == 900L })
    }

    /**
     * An allowlist reads what it names and nothing else — the plan included.
     *
     * This is for a bounded first run, so the thing that would make it useless is a conversation
     * sneaking in through a route the list does not cover: the plan registering it, a feed message
     * arriving for it, or the on-demand lookup resolving it.
     */
    @Test
    fun `an allowlist admits the conversations it names and nothing else`(): Unit = runBlocking {
        client.chats = listOf(chat(100), chat(200))
        client.archive = mapOf(100L to listOf(message(100, 1)), 200L to listOf(message(200, 1)))
        client.unlisted = listOf(chat(555))
        client.feed(TelegramChange.Upserted(message(200, 9)), TelegramChange.Upserted(message(555, 9)))

        val batch = source(only = setOf(100)).next()!!

        assertEquals(listOf("100"), batch.conversations.map { it.conversation.externalId.nativeId })
        assertNull(plan.planFor(Source.TELEGRAM, WorkUnitId("200")))
        assertTrue(client.historyAsks.none { (chatId, _, _) -> chatId == 200L })
    }

    /**
     * An exclusion still wins over an allowlist, so the two need not know about each other.
     *
     * Which matters because they are set by different people for different reasons: one bounds a
     * run, the other keeps the system's own output out of the corpus for good.
     */
    @Test
    fun `an exclusion wins over an allowlist that names the same chat`(): Unit = runBlocking {
        client.chats = listOf(chat(100), chat(900))
        client.archive = mapOf(100L to listOf(message(100, 1)), 900L to listOf(message(900, 1)))

        val batch = source(excluded = setOf(900), only = setOf(100, 900)).next()!!

        assertEquals(listOf("100"), batch.conversations.map { it.conversation.externalId.nativeId })
    }

    @Test
    fun `a topic's messages land in the topic and the chat's own in the chat`(): Unit = runBlocking {
        val group = TelegramConversation(chatId = 500, kind = TelegramConversationKind.GROUP)
        val topic = TelegramConversation(
            chatId = 500,
            topicId = 11,
            topicOf = 500,
            kind = TelegramConversationKind.TOPIC,
        )
        client.chats = listOf(group, topic)
        val telegram = source()
        telegram.next()
        client.feed(
            TelegramChange.Upserted(message(500, 1, topicId = 11)),
            TelegramChange.Upserted(message(500, 2)),
        )

        val placed = telegram.next()!!.conversations
            .associate { it.conversation.externalId.nativeId to nativeIds(it) }

        assertEquals(listOf("500/1"), placed["500/11"])
        assertEquals(listOf("500/2"), placed["500"])
    }

    /**
     * One walk covers a chat, because the platform pages a chat and not a topic.
     *
     * Each topic walking its own history would pay for the same pages again — twenty topics,
     * twenty times the request budget, for messages already stored by the first walk.
     */
    @Test
    fun `one walk through a chat's history counts for every topic inside it`(): Unit = runBlocking {
        fun topic(id: Long) = TelegramConversation(
            chatId = 500,
            topicId = id,
            topicOf = 500,
            kind = TelegramConversationKind.TOPIC,
        )
        val group = TelegramConversation(chatId = 500, kind = TelegramConversationKind.GROUP)
        client.chats = listOf(group, topic(11), topic(22))
        client.archive = mapOf(500L to listOf(message(500, 1, topicId = 11)))
        val telegram = source(limits = limits(historyPage = 1))

        telegram.next()
        telegram.next()

        assertEquals(day.plusSeconds(1), plan.planFor(Source.TELEGRAM, WorkUnitId("500/22"))!!.reached)
        assertEquals(listOf(500L), client.historyAsks.map { (chatId, _, _) -> chatId }.distinct())
    }

    /**
     * How deep a walk goes is configuration, and reaching that depth finishes the unit.
     *
     * What depth bounds is request budget — fetching history is the expensive half, while
     * indexing whatever a page returned is local compute. So the page that crosses the line is
     * kept whole and the walk stops there, rather than the page being trimmed to the line.
     */
    @Test
    fun `a walk that reaches the configured depth is finished there`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3)))
        val telegram = source(limits = limits(historyPage = 2, reachBackTo = day.plusSeconds(2)))

        assertEquals(listOf("100/3", "100/2"), nativeIds(telegram.next()!!.conversations.single()))
        telegram.next()

        assertTrue(plan.planFor(Source.TELEGRAM, unit)!!.finished)
        assertEquals(1, client.historyAsks.size)
    }

    /**
     * Pacing is the adapter's answer to the limit that killed the previous tool — and it paces by
     * waiting, not by declining a turn.
     *
     * Declining looks equivalent and is thirty times slower in practice: the scheduler ends a
     * sweep as soon as a round stores nothing and then waits out its own idle interval, so an
     * adapter that answered *nothing right now* got one page per minute however short its own
     * interval was. Measured on a real archive before this was fixed.
     */
    @Test
    fun `the pace is waited out, so consecutive asks keep reading at that pace`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3)))
        val telegram = source(limits = limits(historyPage = 1, betweenPages = 10.seconds))

        telegram.next()
        telegram.next()
        telegram.next()

        assertEquals(listOf(10.seconds, 10.seconds), waits) { "Each page after the first waits." }
        assertEquals(3, client.historyAsks.size) { "And then reads, rather than skipping a turn." }
    }

    /** Nothing to walk is not something to wait for: the sweep has to be allowed to end. */
    @Test
    fun `a pass with no outstanding unit waits for nothing`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        val telegram = source(limits = limits(betweenPages = 10.seconds))
        telegram.next()
        telegram.next()

        assertNull(telegram.next())
        assertTrue(waits.isEmpty()) { "A source with nothing outstanding held a turn for $waits." }
    }

    /**
     * A wait with a stated duration is an instruction, not a fault.
     *
     * Three things are asserted together because they are one behaviour: the wait is answered with
     * nothing, it is reported as the kind that clears itself, and the feed is not touched while it
     * runs — history is read before the feed is drained precisely so that a wait costs a pass with
     * nothing consumed in it.
     */
    @Test
    fun `a stated wait is answered with nothing, reported as temporary, and consumes nothing`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1)))
        client.feed(TelegramChange.Upserted(message(100, 9)))
        client.faultOnHistory = TelegramFloodWait(30.seconds)
        val telegram = source()

        assertNull(telegram.next())
        assertEquals(FailureKind.TEMPORARY, (state.passes.single() as Pass.Failed).kind)
        assertEquals(0, client.drains)

        assertNull(telegram.next())
        assertEquals(0, client.drains)

        clock.advance(30.seconds)
        val handedOver = telegram.next()!!.conversations.single()
        assertTrue("100/9" in nativeIds(handedOver))
    }

    @Test
    fun `a session that is not signed in asks for a person and answers nothing`(): Unit = runBlocking {
        client.faultOnChats = TelegramSignInNeeded("authorization required")

        assertNull(source().next())

        assertEquals(FailureKind.NEEDS_A_PERSON, (state.passes.single() as Pass.Failed).kind)
    }

    /**
     * A conversation's own failure is counted against it and stops nothing else.
     *
     * One unreachable conversation must not stall the plan, and it must not stall incremental sync
     * either — which is why the pass carries on with what the feed gave it.
     */
    @Test
    fun `a refused page counts against its conversation while the pass carries on`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1)))
        client.feed(TelegramChange.Upserted(message(100, 9)))
        client.faultOnHistory = TelegramRequestFailed("the session cannot read that chat")

        val batch = source().next()!!

        assertEquals(listOf("100/9"), nativeIds(batch.conversations.single()))
        assertEquals(1, plan.planFor(Source.TELEGRAM, unit)!!.failures)
    }

    @Test
    fun `a conversation at the failure ceiling is left out of the walk`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1)))
        client.faultOnHistory = TelegramRequestFailed("the session cannot read that chat")
        val telegram = source(limits = limits(failureCeiling = 1))

        telegram.next()
        telegram.next()

        assertEquals(1, client.historyAsks.size)
    }

    /**
     * A conversation's header travels with its messages, and the adapter decides nothing from
     * what it believes the corpus already holds.
     *
     * The port is explicit about why: an adapter that sent a header only when it thought the
     * store lacked one would be wrong after a restore and wrong after a re-run, and untestable
     * without a populated database. So the header rides along, every time, and a pass with
     * nothing to say is nothing at all rather than a batch of headers.
     */
    @Test
    fun `a conversation's header rides with its messages, and an empty pass is nothing`(): Unit = runBlocking {
        client.chats = listOf(chat(100, title = "a counterparty"))
        val telegram = source()
        telegram.next()
        assertNull(telegram.next())

        client.feed(TelegramChange.Upserted(message(100, 9)))
        val carried = telegram.next()!!.conversations.single()

        assertEquals("a counterparty", carried.conversation.name)
        assertEquals(listOf("100/9"), nativeIds(carried))
    }

    /**
     * A rename reaches the corpus on a conversation that has nothing else to send.
     *
     * This is the whole reason the adapter compares the source against its own last reading. A
     * conversation whose history is finished and whose chat has gone quiet carries no messages for
     * a header to travel with — and the names of those conversations are the client's own
     * taxonomy, which nothing can recover by re-deriving, only by re-reading the source.
     */
    @Test
    fun `a renamed conversation sends its header with nothing else`(): Unit = runBlocking {
        client.chats = listOf(chat(100, title = "as it was"))
        val telegram = source()
        telegram.next()
        assertNull(telegram.next())

        client.chats = listOf(chat(100, title = "as it is now"))
        val batch = telegram.next()!!

        val carried = batch.conversations.single()
        assertEquals("as it is now", carried.conversation.name)
        assertTrue(carried.messages.isEmpty()) { "A rename brought messages nobody sent." }
    }

    /**
     * A rename in a batch the store refused is sent again, because nothing moves until the ask.
     *
     * The reading that decides what to send is acknowledged like every other kind of progress. Were
     * it recorded at hand-over, a header carried in a batch that never landed would be remembered
     * as sent and never offered again — and on a first pass that would leave a restored corpus
     * without headers for the life of the process.
     */
    @Test
    fun `a rename read by a pass that then died is offered again`(): Unit = runBlocking {
        // Two conversations: one that gets renamed and has nothing else to send, and one whose
        // history is being walked so that a pass can die *after* reading the rename. The dying
        // pass must carry no message for the renamed chat, or the header would ride along with it
        // and the question this asks would not arise.
        client.chats = listOf(chat(100, title = "as it was"), chat(200))
        client.archive = mapOf(200L to listOf(message(200, 1), message(200, 2)))
        val telegram = source(limits = limits(historyPage = 1))
        telegram.next()
        telegram.next()

        client.chats = listOf(chat(100, title = "as it is now"))
        client.unlisted = emptyList()
        plan.faultOnReportFailure = IllegalStateException("the plan cannot be written")
        // Out of the ask, as an unclassified failure: the scheduler records it and asks again,
        // and the batch this pass had assembled never reaches the corpus.
        assertFailsWith<IllegalStateException> { telegram.next() }

        val batch = telegram.next()!!
        assertEquals("as it is now", batch.conversations.single().conversation.name) {
            "The rename was remembered as offered by a pass that handed nothing over."
        }
    }

    /**
     * A message for a chat no list offered is placed by asking the platform about that chat.
     *
     * The enumeration cannot be the only way a conversation becomes known: a message arrives for
     * a chat that is archived, or past whatever bound the enumeration has, and a message whose
     * conversation is unknown cannot be stored at all — the store writes a message through its
     * header. Holding it instead, as this adapter once did, is a list that grows for the life of
     * the process and is never stored, which reads as ingestion working.
     */
    @Test
    fun `a message for a chat no list offered is placed by asking for that chat`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.unlisted = listOf(chat(555, title = "a conversation put away"))
        client.feed(TelegramChange.Upserted(message(555, 1)))

        val batch = source().next()!!

        val carried = batch.conversations.single { it.conversation.externalId.nativeId == "555" }
        assertEquals("a conversation put away", carried.conversation.name)
        assertEquals(listOf("555/1"), nativeIds(carried))
        assertEquals(listOf(555L), client.conversationAsks)
    }

    /**
     * A chat the platform will not describe has its messages dropped, and is asked about once.
     *
     * `null` from the platform means a conversation of a kind this build does not read, which is
     * the same answer as an exclusion. Holding such a message forever would be the silent leak;
     * asking again for every message in that chat, every pass, would be the retry storm.
     */
    @Test
    fun `a chat the platform will not describe is asked about once and its messages dropped`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.feed(
            TelegramChange.Upserted(message(555, 1)),
            TelegramChange.Upserted(message(555, 2)),
            TelegramChange.Upserted(message(100, 9)),
        )
        val telegram = source()

        val batch = telegram.next()!!

        assertEquals(listOf("100"), batch.conversations.map { it.conversation.externalId.nativeId })
        assertEquals(listOf(555L), client.conversationAsks)
        assertNull(telegram.next())
        assertEquals(listOf(555L), client.conversationAsks)
    }

    /**
     * A refused lookup keeps the message and does not end the pass.
     *
     * The platform refusing to answer about a chat says nothing about that chat — unlike the
     * platform describing one this build does not read, which is final. Letting the refusal out
     * of the pass would be far worse than losing the message: it would end every later pass on
     * the same buffered message, so ingestion would stop for good because of one chat, with the
     * buffer growing behind it.
     */
    @Test
    fun `a refused chat lookup keeps the message and lets the pass finish`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.unlisted = listOf(chat(555))
        client.feed(TelegramChange.Upserted(message(555, 1)), TelegramChange.Upserted(message(100, 9)))
        client.faultOnConversation = TelegramRequestFailed("the session cannot read that chat")
        val telegram = source()

        val first = telegram.next()!!
        assertEquals(
            listOf("100/9"),
            nativeIds(first.conversations.single { it.conversation.externalId.nativeId == "100" }),
        )

        // The fault was one-shot, so the next pass resolves the chat and hands the held message on.
        val second = telegram.next()!!
        assertEquals(
            listOf("555/1"),
            nativeIds(second.conversations.single { it.conversation.externalId.nativeId == "555" }),
        )
    }

    /**
     * A withdrawal waits for the message it names, or the corpus keeps a deleted message as live.
     *
     * The two buffers used to empty together because nothing was ever kept. Sending the withdrawal
     * while its message waits matches no row in the store, loses the statement, and lets the
     * message land later as though the source had never withdrawn it — which is exactly the
     * message a draft must not quote.
     */
    @Test
    fun `a withdrawal waits for the message it names`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.unlisted = listOf(chat(555))
        client.feed(
            TelegramChange.Upserted(message(555, 1)),
            TelegramChange.Removed(555, listOf(1)),
            TelegramChange.Upserted(message(100, 9)),
        )
        client.faultOnConversation = TelegramRequestFailed("the session cannot read that chat")
        val telegram = source()

        val first = telegram.next()!!
        assertTrue(first.withdrawn.isEmpty()) { "A withdrawal went without the message it names." }

        val second = telegram.next()!!
        assertEquals(listOf("555/1"), second.withdrawn.map { it.nativeId })
        assertEquals(
            listOf("555/1"),
            nativeIds(second.conversations.single { it.conversation.externalId.nativeId == "555" }),
        )
    }

    /**
     * One lookup per chat per pass, however many of its messages are waiting.
     *
     * The budget this adapter exists to protect is requests, and a chat the platform will not
     * answer about is exactly the one whose messages pile up — so asking per message would turn one
     * unreachable conversation into a request per message per pass.
     */
    @Test
    fun `a chat whose lookup was refused is asked about once in that pass`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.unlisted = listOf(chat(555))
        client.feed(TelegramChange.Upserted(message(555, 1)), TelegramChange.Upserted(message(555, 2)))
        client.faultOnConversation = TelegramRequestFailed("the session cannot read that chat")

        source().next()

        assertEquals(listOf(555L), client.conversationAsks)
    }

    /**
     * Freshness is claimed only once the feed has actually been emptied.
     *
     * A drain that filled its page leaves material queued, so the moment proves nothing — and the
     * count alone cannot tell that apart from a drain that took everything, because a page also
     * comes back short when one event could not be resolved. Backfill progress is reported either
     * way: how far a walk has got is a fact about the plan, and tying it to the feed made a busy
     * account's catch-up report nothing at all.
     */
    @Test
    fun `a feed with more queued defers freshness but not backfill`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.feed(TelegramChange.Upserted(message(100, 9)), TelegramChange.Upserted(message(100, 10)))
        val telegram = source(limits = limits().copy(changePage = 1))

        telegram.next()
        telegram.next()

        assertTrue(state.freshness.isEmpty()) { "Freshness was claimed while the feed still held events." }
        assertTrue(state.backfill.isNotEmpty()) { "Backfill progress waited on the feed." }

        // The feed empties, and the claim becomes available.
        telegram.next()
        telegram.next()
        assertTrue(state.freshness.isNotEmpty())
    }

    /**
     * A page that cannot be placed is counted against its conversation, or it blocks every other.
     *
     * The plan offers the least-failed unit first, so without a failure the same unit is chosen,
     * the same page read and the same page dropped — for the life of the process, with no other
     * conversation ever walked. Withholding the ground is right and is not enough on its own.
     */
    @Test
    fun `a page that could not be placed is counted against its conversation`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        val telegram = source(limits = limits(historyPage = 1))
        telegram.next()

        assertEquals(0, plan.planFor(Source.TELEGRAM, unit)!!.failures) {
            "A page that was placed perfectly well was counted as a failure."
        }

        client.chats = emptyList()
        client.unlisted = emptyList()
        telegram.next()

        assertEquals(1, plan.planFor(Source.TELEGRAM, unit)!!.failures) {
            "An unplaceable page left the unit at the head of the plan with nothing counted."
        }
    }

    /**
     * A page held back by a refused lookup is not counted against its conversation.
     *
     * The two reasons a page goes unplaced are not the same reason. The platform describing a chat
     * this build does not read is final, and counting it is what moves the walk on. A refused
     * lookup says nothing and will be tried again — counting that would retire a conversation
     * after five transient refusals, which is the opposite of why its messages are kept.
     */
    @Test
    fun `a page held back by a refused lookup is not counted against its conversation`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        val telegram = source(limits = limits(historyPage = 1))
        telegram.next()

        // The chat leaves the enumeration and the lookup for it is refused, so the page is kept
        // rather than dropped.
        client.chats = emptyList()
        client.faultOnConversation = TelegramRequestFailed("the session cannot read that chat")
        telegram.next()

        assertEquals(0, plan.planFor(Source.TELEGRAM, unit)!!.failures) {
            "A conversation was penalised for a refusal that said nothing about it."
        }
    }

    /**
     * Ground is recorded only for a page the corpus was actually offered.
     *
     * A page whose messages cannot be placed — a chat the platform will not describe — must not
     * advance the plan, or the plan claims history nothing stored and only a re-read would find
     * it. The page is read again instead, which costs a request and loses nothing.
     */
    @Test
    fun `a page whose messages could not be placed records no ground`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        // One message per page, so the second pass fetches a page that genuinely exists and
        // genuinely cannot be placed. With a larger page the second pass would come back empty,
        // and an empty page satisfies "every message reached the batch" vacuously — which is how
        // this test passed against the guard being deleted.
        val telegram = source(limits = limits(historyPage = 1))
        telegram.next()

        // The platform stops describing the chat before the next page is read, which the plan
        // port permits: a unit absent from an enumeration is not forgotten, so the walk goes on
        // asking for it. That page therefore cannot be placed under any header.
        client.chats = emptyList()
        client.unlisted = emptyList()
        assertNull(telegram.next())
        telegram.next()

        assertEquals(day.plusSeconds(2), plan.planFor(Source.TELEGRAM, unit)!!.reached) {
            "Ground was recorded for a page the corpus was never offered."
        }
        assertEquals(Triple(100L, 2L, 1), client.historyAsks.last()) {
            "The cursor moved over the unplaceable page instead of re-reading it."
        }
    }

    /**
     * Nothing between the drain and the batch may cost the drained material.
     *
     * The feed hands an event over once, so a failure after it has been drained — here a wait
     * arriving on a profile lookup — would otherwise lose whatever the pass was holding. The next
     * pass has to hand the same material over instead.
     */
    @Test
    fun `a failure after the drain costs the pass and not the material`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77)))
        client.faultOnPeople = TelegramFloodWait(5.seconds)
        val telegram = source()

        assertNull(telegram.next())

        clock.advance(5.seconds)
        val batch = telegram.next()!!
        assertEquals(listOf("100/9"), nativeIds(batch.conversations.single()))
        assertEquals(listOf(ExternalId(Source.TELEGRAM, "77")), batch.persons.map { it.externalId })
    }

    /**
     * One message twice is handed over once, as the platform last had it.
     *
     * Two copies of a message meet in the buffer in two ways: a page re-read after a failed pass,
     * and an edit arriving on the feed for a message this same pass fetched from history. They
     * disagree, and the order they happen to be in is the wrong way to settle it — history is
     * read before the feed is drained, so the stale copy is the later of the two in the list. The
     * edit time is what settles it.
     */
    @Test
    fun `a message edited since it was walked is handed over as the platform last had it`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 9, text = "the first wording")))
        client.feed(
            TelegramChange.Upserted(
                message(100, 9, text = "the amended wording").copy(editedAt = day.plusSeconds(400)),
            ),
        )

        val messages = source().next()!!.conversations.single().messages

        assertEquals(listOf("the amended wording"), messages.map { it.text })
    }

    /**
     * The buffer survives a failed pass, and what it holds is handed over on the next one.
     *
     * The feed hands an event over once, so a failure after the drain — here a wait arriving on a
     * profile lookup — would otherwise lose whatever the pass was holding.
     */
    @Test
    fun `a message held through a failed pass is handed over on the next`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77, text = "the only wording")))
        client.faultOnPeople = TelegramFloodWait(5.seconds)
        val telegram = source()
        assertNull(telegram.next())

        clock.advance(5.seconds)
        val messages = telegram.next()!!.conversations.single().messages

        assertEquals(listOf("the only wording"), messages.map { it.text })
    }

    /**
     * A refused profile lookup costs the profile and never the message.
     *
     * An author whose record has not arrived is ordinary — a message points at a person by
     * identifier and the store resolves it later — while a message waiting on a lookup that keeps
     * failing would wait indefinitely. The retry is the next pass, which is why nothing is
     * recorded as asked for.
     */
    @Test
    fun `a refused profile lookup holds no message back and is tried again`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77)))
        client.faultOnPeople = TelegramRequestFailed("the session cannot see that profile")
        val telegram = source()

        val first = telegram.next()!!
        assertEquals(listOf("100/9"), nativeIds(first.conversations.single()))
        assertTrue(first.persons.isEmpty())

        client.feed(TelegramChange.Upserted(message(100, 10, author = 77)))
        val second = telegram.next()!!
        assertEquals(listOf(ExternalId(Source.TELEGRAM, "77")), second.persons.map { it.externalId })
    }

    /**
     * An author is asked about once, and so is the account the session reads through.
     *
     * The second profile in the first ask is that account's, which every process asks for once
     * whether or not it wrote anything the pass carried — it is the record the mark has to reach.
     * It is cached like any other, which is what the absence of a third ask asserts.
     */
    @Test
    fun `an author's profile is asked for once`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        val telegram = source()
        telegram.next()
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77)))
        telegram.next()
        client.feed(TelegramChange.Upserted(message(100, 10, author = 77)))
        telegram.next()

        assertEquals(listOf(setOf(ScriptedTelegramClient.SIGNED_IN_AS), setOf(77L)), client.peopleAsks)
    }

    /**
     * The account the session reads through is marked on the record, and nobody else is.
     *
     * Read from the session rather than configured, which is what this asserts by setting it on the
     * scripted platform alone: nothing is passed to the adapter that says who it is.
     */
    @Test
    fun `the profile of the account read through is marked, and a counterparty's is not`(): Unit = runBlocking {
        client.account = 900
        client.chats = listOf(chat(100))
        client.profiles = mapOf(
            900L to TelegramPerson(900, displayName = "the account this is read through"),
            77L to TelegramPerson(77, displayName = "a counterparty"),
        )
        client.archive = mapOf(100L to listOf(message(100, 1, author = 900), message(100, 2, author = 77)))

        val persons = source().next()!!.persons.associateBy { it.externalId.nativeId }

        assertEquals(true, persons.getValue("900").isSelf)
        assertEquals(false, persons.getValue("77").isSelf)
    }

    /** One question per process: the answer cannot change while a session directory is one account's. */
    @Test
    fun `the account is asked for once however many passes run`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1, author = 77)))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        val telegram = source()
        telegram.next()
        telegram.next()
        telegram.next()

        assertEquals(1, client.accountAsks)
    }

    /**
     * A session that would not say leaves the mark unset, and the next pass marks it.
     *
     * The second half is the one worth testing. The account's own profile is sent by the same pass
     * that could not ask who it was, and a profile believed fetched is never asked for again — so
     * without forgetting that one identifier the mark would wait for a restart nothing is going to
     * ask for, and the corpus would hold the reader as an ordinary counterparty indefinitely.
     */
    @Test
    fun `a session that would not say leaves the mark unset and the mark lands once it does`(): Unit = runBlocking {
        client.account = 900
        client.chats = listOf(chat(100))
        client.profiles = mapOf(900L to TelegramPerson(900, displayName = "the account this is read through"))
        client.archive = mapOf(100L to listOf(message(100, 1, author = 900)))
        client.faultOnAccount = TelegramRequestFailed("the library would not say")
        val telegram = source()

        assertNull(telegram.next()!!.persons.single().isSelf)

        val marked = telegram.next()!!.persons.single()
        assertEquals(ExternalId(Source.TELEGRAM, "900"), marked.externalId)
        assertEquals(true, marked.isSelf)
    }

    @Test
    fun `a re-read names a unit the plan holds, and anything else is refused`(): Unit = runBlocking {
        val telegram = source()

        assertFailsWith<UnknownWorkUnit> { telegram.reread(WorkUnitId("404")) }
    }

    /**
     * A re-read opens no session, because opening one costs the material it exists to recover.
     *
     * The library starts consuming the account's difference the moment it is built, into a queue
     * the one-shot process that asked for the re-read then exits and discards — and that
     * difference is the one thing no second copy of this build's position can get back. So the
     * command written to repair lost records would lose some. Asserted here, at the seam, because
     * the composition root is where the laziness is wired and this is where it is relied on.
     */
    @Test
    fun `a re-read opens no session`(): Unit = runBlocking {
        // Finished with ground taken, so that reopening it is observable. Starting from a bare
        // enumeration would assert nothing: a fresh row is already unfinished with no ground, so
        // the premise would hold against a re-read that did nothing at all.
        finished(unit, through = day.plusSeconds(2))
        val telegram = TelegramSource(
            client = lazy { error("a re-read must not open a session") },
            plan = plan,
            state = state,
            excluded = ExcludedConversations(emptySet(), emptySet()),
            limits = limits(),
            timing = Timing(clock = clock, pause = {}),
        )

        telegram.reread(unit)

        val item = plan.planFor(Source.TELEGRAM, unit)!!
        assertNull(item.reached) { "The re-read did not reach the plan, so nothing above is proved." }
        assertFalse(item.finished) { "The re-read did not reach the plan, so nothing above is proved." }
    }

    /**
     * A re-read puts a unit back to unwalked, and the walk starts from the newest again.
     *
     * The cursor has to go with it. A re-read that reopened the plan and left the adapter paging
     * from where it had got to would report a re-read that never happened.
     */
    @Test
    fun `a re-read reopens the plan and drops the position with it`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        val telegram = source()
        telegram.next()
        telegram.next()

        telegram.reread(unit)

        val item = plan.planFor(Source.TELEGRAM, unit)!!
        assertNull(item.reached)
        assertFalse(item.finished)
        client.historyAsks.clear()
        client.positionAsks.clear()
        telegram.next()
        assertEquals(listOf(Triple(100L, null, 2)), client.historyAsks)
        assertTrue(client.positionAsks.isEmpty())
    }

    /**
     * A re-read forgets what this process thought it had already sent.
     *
     * The reason a re-read exists is a field that was never captured and is now wanted. An
     * adapter that went on believing it had already fetched every profile would re-send the
     * records and none of the claims on them, which is a re-read that did not happen.
     */
    @Test
    fun `a re-read forgets which profiles were already asked for`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.profiles = mapOf(77L to TelegramPerson(77, displayName = "a counterparty"))
        val telegram = source()
        telegram.next()
        client.feed(TelegramChange.Upserted(message(100, 9, author = 77)))
        telegram.next()
        // One more ask, so the profile is *acknowledged* as fetched before the re-read. Without
        // it only the pending acknowledgement would be under test, and the cache it feeds could be
        // left uncleared with this test still green.
        telegram.next()
        assertEquals(listOf(setOf(ScriptedTelegramClient.SIGNED_IN_AS), setOf(77L)), client.peopleAsks)

        telegram.reread(unit)
        client.feed(TelegramChange.Upserted(message(100, 10, author = 77)))
        telegram.next()

        // The counterparty asked about a second time is what a re-read is for. The account read
        // through is in that ask as well, because clearing what was fetched forgets it too — and
        // re-sending a record with the mark on it is what a re-read of this field would be.
        val own = ScriptedTelegramClient.SIGNED_IN_AS
        assertEquals(listOf(setOf(own), setOf(77L), setOf(77L, own)), client.peopleAsks)
    }

    /**
     * A conversation that moved while nothing was reading it is caught up, and the walk comes down
     * from the newest message rather than up from the back edge.
     *
     * The fault this whole mechanism exists for. The unit is finished, so the backfill walk will
     * never look at it again; what arrived during the outage is in the archive and absent from the
     * corpus, and nothing but the comparison against the source's own newest would notice.
     */
    @Test
    fun `a conversation that moved while nothing read it is caught up from the newest`(): Unit = runBlocking {
        // What the previous process left behind: a finished unit, complete up to message 2.
        finished(unit, through = day.plusSeconds(2))
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3), message(100, 4)))

        val batch = source().next()!!

        assertEquals(listOf("100/4", "100/3"), nativeIds(batch.conversations.single())) {
            "The messages that arrived during the outage did not reach the corpus."
        }
        assertEquals(listOf(Triple(100L, null, 2)), client.historyAsks) {
            "The catch-up resumed from the back edge instead of coming down from the newest."
        }
        assertTrue(client.positionAsks.isEmpty()) {
            "The catch-up turned the back edge into a position, which is the backfill walk's resume."
        }
    }

    /**
     * A gap closes when the walk meets what the corpus already holds, and not before.
     *
     * Two pages, because one page that happens to cover everything cannot tell a walk that stopped
     * at the edge from one that stopped because the archive ran out. The second assertion is the
     * one that matters most: once closed, the unit is recorded complete to what the source offered
     * — not to the newest message the walk happened to carry, which is what makes this converge for
     * a chat whose newest message is one this build does not store.
     */
    @Test
    fun `a gap closes at the front edge and records what the source offered`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(4))
        client.chats = listOf(chat(100, newest = day.plusSeconds(6)))
        client.archive = mapOf(100L to (1L..6L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 2))

        telegram.next()
        assertEquals(day.plusSeconds(4), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "The front edge moved while the stretch behind it was still being read."
        }

        // 6 and 5 came down first, whose oldest is still ahead of the edge. This page is 4 and 3,
        // and 4 is at the edge — so the two stretches meet and there is nothing between them. The
        // third ask is what acknowledges that page, which is when the closing is recorded.
        telegram.next()
        telegram.next()

        assertEquals(day.plusSeconds(6), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "A closed gap left the unit short of what the source said was newest, so the next " +
                "start will walk it again."
        }
        assertEquals(listOf(Triple(100L, null, 2), Triple(100L, 5L, 2)), client.historyAsks) {
            "The catch-up did not page down through the gap, or did not stop at its edge."
        }
    }

    /**
     * A comparison that could not be made does not count as one that was.
     *
     * It happens once per process, so the flag that records it must be spent by the read landing
     * rather than by the attempt. One refused query at startup would otherwise leave a process
     * that never looks for a gap again — for days, since the container is restarted only when
     * something stops it — and nothing else looks at a conversation's front.
     */
    @Test
    fun `a refused plan read leaves the comparison still to be made`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(2))
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to (1L..4L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 2))
        plan.faultOnPlansFor = IllegalStateException("the store would not answer")

        assertFailsWith<IllegalStateException> { telegram.next() }
        telegram.next()

        assertEquals(listOf(Triple(100L, null, 2)), client.historyAsks) {
            "The gap was never looked for again, so the refused read cost this process its one " +
                "chance to find it: ${client.historyAsks}."
        }
    }

    /**
     * A message sharing the edge's second is recovered, which a strict comparison would lose.
     *
     * This source's times are whole seconds, so several messages of one conversation routinely
     * share one — measured as ordinary on the real archive. An edge standing at the second the
     * source now offers therefore cannot be read as level: were the comparison strict, a message
     * from inside that second which never reached the corpus would be invisible for ever, because
     * nothing looks at a conversation's front twice. `V20` met the same problem on the other
     * watermark and solved it by carrying a message beside the instant; that is unavailable here,
     * since the chat list offers a time and no identifier and the plan holds a time rather than a
     * position — so the comparison is inexact in the only safe direction.
     */
    @Test
    fun `a message sharing the front edge's second is read back`(): Unit = runBlocking {
        val sameSecond = day.plusSeconds(2)
        // Two messages in one second. The corpus holds the first, so the edge stands at that
        // second; the second one is what an outage left behind.
        client.chats = listOf(chat(100, newest = sameSecond))
        client.archive = mapOf(
            100L to listOf(
                TelegramMessage(chatId = 100, messageId = 1, sentAt = day.plusSeconds(1)),
                TelegramMessage(chatId = 100, messageId = 2, sentAt = sameSecond),
                TelegramMessage(chatId = 100, messageId = 3, sentAt = sameSecond),
            ),
        )
        finished(unit, through = sameSecond)

        val batch = source(limits = limits(historyPage = 2)).next()!!

        assertTrue("100/3" in nativeIds(batch.conversations.single())) {
            "The message sharing the edge's second was never read back, and nothing will look " +
                "for it again: ${nativeIds(batch.conversations.single())}."
        }
    }

    /**
     * A unit finished without ground is read at the front like any other finished unit.
     *
     * An empty first page leaves a unit finished with nothing walked, so the backfill will never
     * offer it again — which is exactly the state where only this can reach the front. Excluding
     * it for having no position would leave a conversation that was empty when walked, and has
     * since acquired messages, unread by either walk.
     */
    @Test
    fun `a unit finished without ground is still caught up`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached = null, finished = true)
        plan.reportFront(Source.TELEGRAM, unit, day.plusSeconds(2))
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to (1L..4L).map { message(100, it) })

        val batch = source(limits = limits(historyPage = 2)).next()!!

        assertEquals(listOf("100/4", "100/3"), nativeIds(batch.conversations.single())) {
            "A conversation neither walk will read was left to neither."
        }
    }

    /**
     * A gap stops at the configured depth, because depth is configuration and not adapter policy.
     *
     * A front edge can sit below the floor — a conversation captured before the bound was set, or
     * dormant since before it — so the gap window can cross it. Without the bound this walk would
     * fetch the stretch the deployment configured out, which is the one cost depth exists to
     * decide.
     */
    @Test
    fun `a gap stops at the configured depth`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(1))
        client.chats = listOf(chat(100, newest = day.plusSeconds(6)))
        client.archive = mapOf(100L to (1L..6L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 2, reachBackTo = day.plusSeconds(3)))

        telegram.next()
        telegram.next()
        telegram.next()

        // Pages 6-5 and 4-3; the second reaches the floor, so the descent stops there rather than
        // carrying on to the edge at day+1.
        assertEquals(listOf(Triple(100L, null, 2), Triple(100L, 5L, 2)), client.historyAsks) {
            "The catch-up read past the configured depth: ${client.historyAsks}."
        }
    }

    /**
     * A conversation being caught up is reported as lagging, not as current.
     *
     * The lagging list has always come from the backfill's outstanding units, and a finished
     * conversation with a gap in front of it is absent from that. Leaving it out would report the
     * source complete through the last drain while this adapter is paging through a hole it has
     * found — which is the state the front edge exists to make sayable.
     */
    @Test
    fun `a conversation with an open gap is reported as lagging`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(2))
        client.chats = listOf(chat(100, newest = day.plusSeconds(9)))
        client.archive = mapOf(100L to (1L..9L).map { message(100, it) })

        // Two asks: freshness is claimed only through a feed drained to empty, and the report runs
        // at the head of a pass — so the first drain is read by the second pass.
        val telegram = source(limits = limits(historyPage = 2))
        telegram.next()
        telegram.next()

        assertEquals(listOf(unit), state.freshness.last().lagging) {
            "A conversation this adapter knows is behind was reported as current."
        }
    }

    /**
     * A conversation level with its source costs one page per start, and never a second.
     *
     * This is the case that proves a catch-up cannot become the loop the decision against an
     * automatic re-read guards against. The comparison is not strict — a shared second has to read
     * as a gap, because this source's times are whole seconds — so a level conversation is
     * confirmed rather than assumed. What makes that bounded is that the page meets the edge
     * immediately and the comparison happens once per process: three restarts buy three pages, not
     * three descents.
     */
    @Test
    fun `a conversation level with its source costs one page per start and no more`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(4))
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to (1L..4L).map { message(100, it) })

        // Two asks per process: one reads the confirming page, the next acknowledges and closes.
        repeat(3) {
            val telegram = source(limits = limits(historyPage = 2))
            telegram.next()
            telegram.next()
        }

        assertEquals(List(3) { Triple(100L, null, 2) }, client.historyAsks) {
            "A level conversation descended past its edge, or kept paying after the gap closed: " +
                "${client.historyAsks}."
        }
        assertEquals(day.plusSeconds(4), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "The confirming page moved the edge, which it has no new material to move it with."
        }
    }

    /**
     * A cold corpus starts no catch-up, because nothing in it is complete to any time.
     *
     * The first start of a deployment has an empty plan and an empty corpus, which reads as a
     * maximal gap to any arithmetic that treats an absent front edge as an old one. What keeps the
     * backfill walk the only thing reading history here is that an unknown edge is not a gap — a
     * second walk down the same conversation would pay twice for every page of it.
     */
    @Test
    fun `a cold corpus is read by the backfill walk and not by a catch-up`(): Unit = runBlocking {
        client.chats = listOf(chat(100, newest = day.plusSeconds(2)))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        val telegram = source()

        telegram.next()
        // The second ask is the acknowledgement, which is the only moment any of this is recorded.
        telegram.next()

        // Ground is what a backfill page records and a catch-up page never does, so this is what
        // tells the two paths apart on a corpus where both would want the same page.
        assertEquals(day.plusSeconds(1), plan.planFor(Source.TELEGRAM, unit)!!.reached) {
            "The page that was read did not come from the backfill walk."
        }
    }

    /**
     * A walked conversation with no front edge is left alone and said out loud.
     *
     * A walk needs something to stop at, and an unknown edge gives it nothing — so acting on one
     * would read a whole history back under another name. The state is what the migration that
     * introduced the column leaves behind for a conversation the corpus holds no message for.
     */
    @Test
    fun `a walked conversation with no front edge starts no catch-up`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached = day, finished = true)
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to (1L..4L).map { message(100, it) })

        source().next()

        assertTrue(client.historyAsks.isEmpty()) {
            "An unbounded catch-up was started, which is a re-backfill under another name."
        }
        assertNull(plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "An edge was invented for a conversation the corpus holds nothing for."
        }
    }

    /**
     * A conversation the walk has never read is left to the walk, which starts at the newest.
     *
     * An edge with no ground behind it is an ordinary state rather than a contrived one: the
     * migration that introduced the column seeds one for every conversation the corpus holds a
     * message for, including conversations whose history was never walked, and a reopen keeps the
     * edge while clearing the ground. It is the one shape where a catch-up would duplicate the
     * walk instead of complementing it — both would come down from the same newest message.
     */
    @Test
    fun `a conversation with a front edge and no ground is left to the backfill walk`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportFront(Source.TELEGRAM, unit, day.plusSeconds(4))
        client.chats = listOf(chat(100, newest = day.plusSeconds(6)))
        client.archive = mapOf(100L to (1L..6L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 2))

        telegram.next()
        telegram.next()

        // Ground is what the backfill walk records and a catch-up never does, and one pass reads
        // one page whichever walk it belongs to — so this is what tells them apart rather than
        // how many pages were read.
        assertEquals(day.plusSeconds(5), plan.planFor(Source.TELEGRAM, unit)!!.reached) {
            "The pages came from a catch-up, which pays a second time for what the walk reads " +
                "anyway: ${client.historyAsks}."
        }
    }

    /**
     * A gap closes to what the source offered, even when the corpus cannot hold that message.
     *
     * The convergence case. A chat's newest message may be one this build does not store, so the
     * material a walk carries stops short of what the enumeration names — and an edge recorded
     * from the messages rather than from the offer would stay behind the source for ever, leaving
     * this conversation walked again on every start for the life of the deployment.
     */
    @Test
    fun `a gap closes to the source's offer rather than to the last message stored`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(2))
        // The source names something newer than anything the archive will hand over.
        client.chats = listOf(chat(100, newest = day.plusSeconds(9)))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2), message(100, 3)))
        val telegram = source(limits = limits(historyPage = 2))

        telegram.next()
        telegram.next()

        assertEquals(day.plusSeconds(9), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "The edge stopped at the newest message stored, so this conversation is behind its " +
                "source for ever and descends further on every start."
        }
        // A restart confirms it with a single page rather than descending again, which is what
        // converging means here: the edge reaches what the source offers, so the walk meets it on
        // the first page instead of paging down towards material it already holds.
        client.historyAsks.clear()
        val restarted = source(limits = limits(historyPage = 2))
        restarted.next()
        restarted.next()
        assertEquals(listOf(Triple(100L, null, 2)), client.historyAsks) {
            "A restart descended past the edge, which is the loop this has to converge out of: " +
                "${client.historyAsks}."
        }
    }

    /**
     * A conversation still being backfilled has the same hole in front of it, and it is closed.
     *
     * The case that makes *finished* the wrong scope for this. The walk resumes from the ground it
     * has taken and carries on into the past, so it will never read what arrived at the front
     * while the process was down — and when it eventually finishes, nothing looks there again. The
     * gap would be permanent for exactly the conversations that are still being captured.
     */
    @Test
    fun `a conversation whose backfill is unfinished is still caught up at the front`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached = day.plusSeconds(3), finished = false)
        plan.reportFront(Source.TELEGRAM, unit, day.plusSeconds(4))
        client.chats = listOf(chat(100, newest = day.plusSeconds(6)))
        client.archive = mapOf(100L to (1L..6L).map { message(100, it) })

        val batch = source(limits = limits(historyPage = 2)).next()!!

        assertEquals(listOf("100/6", "100/5"), nativeIds(batch.conversations.single())) {
            "A conversation mid-backfill kept the hole an outage left in front of it."
        }
        assertEquals(listOf(Triple(100L, null, 2)), client.historyAsks) {
            "The front was read by the backfill walk's resume rather than from the newest."
        }
    }

    /**
     * A catch-up page the corpus was never offered moves nothing and gives the gap up.
     *
     * Two rules meet here. The edge must not move over a page the batch did not carry — that is
     * the unrecoverable mistake, since nothing looks at a conversation's front twice. And the gap
     * must not simply stay open, because a gap has priority over the backfill while it is open, so
     * the same unplaceable page would be read every pass with no other conversation ever walked.
     * Giving it up leaves the plan exactly as it was, and the next start measures again.
     */
    @Test
    fun `a catch-up page that could not be placed gives the gap up rather than reading it forever`(): Unit =
        runBlocking {
            finished(unit, through = day.plusSeconds(2))
            // A second conversation with history owed, so that starving the backfill is visible.
            plan.enumerated(Source.TELEGRAM, listOf(WorkUnitId("300")))
            client.chats = listOf(chat(100, newest = day.plusSeconds(4)), chat(300))
            client.archive = mapOf(
                100L to (1L..4L).map { message(100, it) },
                300L to listOf(message(300, 7)),
            )
            // One message per page, because an empty page satisfies "every message reached the
            // batch" vacuously — which is how a test like this passes against the guard deleted.
            val telegram = source(limits = limits(historyPage = 1))

            // The gap is found while the chat is listed, and its first page comes down cleanly.
            telegram.next()

            // Then the platform stops describing chat 100, which the plan permits — a unit absent
            // from an enumeration is not forgotten. Its next page reaches no header at all.
            client.chats = listOf(chat(300))
            client.unlisted = emptyList()
            telegram.next()
            client.historyAsks.clear()
            telegram.next()

            assertEquals(day.plusSeconds(2), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
                "The gap was closed, or its edge moved, over a page the corpus never received."
            }
            assertEquals(listOf(Triple(300L, null, 1)), client.historyAsks) {
                "The abandoned gap kept the walk to itself, so no other conversation was read: " +
                    "${client.historyAsks}."
            }
        }

    /**
     * A page taken from the newest message is what establishes a front edge.
     *
     * It is the only position with nothing above it, so a page starting there leaves the corpus
     * complete down from the newest message the chat has — which is a fact about material the
     * batch carried, and the only kind of fact an edge may be built from.
     */
    @Test
    fun `a page taken from the newest message records the front edge`(): Unit = runBlocking {
        client.chats = listOf(chat(100, newest = day.plusSeconds(2)))
        client.archive = mapOf(100L to listOf(message(100, 1), message(100, 2)))
        val telegram = source(limits = limits(historyPage = 1))

        telegram.next()
        telegram.next()

        assertEquals(day.plusSeconds(2), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "The page that started at the newest message established no front edge, so nothing " +
                "will ever measure a gap for this conversation."
        }
    }

    /**
     * A resumed page establishes no front edge, because material sits above it.
     *
     * A page bounded above by a cursor proves ground at the back and nothing at the front. Where
     * an edge is already stored this is invisible — the store only ever moves one forward, so a
     * lower claim is absorbed — and the case that exposes it is a unit resuming with no edge at
     * all, which is what the plan holds for a conversation walked before the column existed. An
     * edge invented from deep history there would read as a conversation complete to a time in
     * its past, and the next start would walk everything above it: the unbounded re-read this is
     * built to avoid.
     */
    @Test
    fun `a resumed page records no front edge, where there is none to absorb it`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached = day.plusSeconds(2), finished = false)
        client.chats = listOf(chat(100, newest = day.plusSeconds(4)))
        client.archive = mapOf(100L to (1L..4L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 1))

        // The walk resumes from the stored ground, so its page is bounded above by message 2.
        telegram.next()
        telegram.next()

        assertEquals(listOf(100L to day.plusSeconds(2)), client.positionAsks) {
            "The page was not a resumed one, so this test is not about the case it names."
        }
        assertNull(plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "A page with material above it established a front edge, which claims completeness " +
                "for messages the pass never saw."
        }
    }

    /**
     * What the source offers is never by itself a front edge.
     *
     * The comparison needs both halves: an enumeration names each conversation's newest message,
     * and that is what an edge is measured *against* — never what it is set from. A conversation
     * nothing has read yet must keep no edge at all, however current the source says it is, or the
     * plan ends up ahead of the records it claims and the gap becomes undetectable. One pass reads
     * one conversation's page, so the second listed conversation is exactly this case.
     */
    @Test
    fun `an enumeration alone never records a front edge`(): Unit = runBlocking {
        client.chats = listOf(chat(100, newest = day.plusSeconds(2)), chat(200, newest = day.plusSeconds(9)))
        client.archive = mapOf(
            100L to listOf(message(100, 1), message(100, 2)),
            200L to listOf(message(200, 8), message(200, 9)),
        )
        val telegram = source()
        telegram.next()
        telegram.next()

        assertEquals(day.plusSeconds(2), plan.planFor(Source.TELEGRAM, unit)!!.newest) {
            "The conversation whose page was read has no edge, so this test discriminates nothing."
        }
        assertNull(plan.planFor(Source.TELEGRAM, WorkUnitId("200"))!!.newest) {
            "A conversation no page was read for was recorded complete to what the source " +
                "offered, which is a plan ahead of the corpus and a gap nothing can find."
        }
    }

    /**
     * The two walks keep their own positions, which one chat can need at once.
     *
     * A forum is where this is reachable: progress is recorded for every unit of a chat together,
     * so its topics finish together — but a topic that appears after the chat's history is done is
     * unfinished beside siblings that are not. Then one chat has a gap at the front and a backfill
     * owed at the back, and a single cursor would hand each walk the other's position. The cost of
     * that is a re-read of everything between them, and the cost of it in the other order is a gap
     * closed from a page deep in history, which never reads the material the gap is about.
     */
    @Test
    fun `the catch-up and the backfill walk one chat without sharing a position`(): Unit = runBlocking {
        val group = TelegramConversation(chatId = 500, kind = TelegramConversationKind.GROUP)
        val topic = TelegramConversation(
            chatId = 500,
            topicId = 11,
            topicOf = 500,
            kind = TelegramConversationKind.TOPIC,
        )
        // The chat's own history is finished and complete to message 4; the topic arrived after
        // that and owes its history from message 2 back.
        finished(WorkUnitId("500"), through = day.plusSeconds(4))
        plan.enumerated(Source.TELEGRAM, listOf(WorkUnitId("500/11")))
        plan.reportReached(Source.TELEGRAM, WorkUnitId("500/11"), reached = day.plusSeconds(2), finished = false)
        client.chats = listOf(group.copy(lastMessageAt = day.plusSeconds(6)), topic)
        client.archive = mapOf(500L to (1L..6L).map { message(500, it) })
        val telegram = source(limits = limits(historyPage = 2))

        // The gap first: 6 and 5, then 4 and 3, whose oldest is at the front edge.
        telegram.next()
        telegram.next()
        // The gap is closed on this ask, so the backfill walk takes the chat from here on.
        telegram.next()
        telegram.next()

        // The third page is the backfill's first, and it comes from the topic's own back edge
        // turned into a position — message 2 — rather than from where the gap's descent ended.
        assertEquals(
            listOf(Triple(500L, null, 2), Triple(500L, 5L, 2), Triple(500L, 2L, 2), Triple(500L, 1L, 2)),
            client.historyAsks,
        ) {
            "The backfill walk resumed from the catch-up's position rather than from its own " +
                "back edge: ${client.historyAsks}."
        }
        assertEquals(listOf(500L to day.plusSeconds(2)), client.positionAsks) {
            "The backfill walk did not turn its own back edge into a position."
        }
    }

    /**
     * A re-read drops a gap rather than letting two walks down one chat.
     *
     * A reopened unit is outstanding again, so the backfill walk takes its whole history from the
     * newest — which covers everything the catch-up was going to read. Leaving the gap would pay
     * twice for the same pages and measure a front edge the re-read has made meaningless.
     */
    @Test
    fun `a re-read takes over from a gap instead of running beside it`(): Unit = runBlocking {
        finished(unit, through = day.plusSeconds(2))
        client.chats = listOf(chat(100, newest = day.plusSeconds(6)))
        client.archive = mapOf(100L to (1L..6L).map { message(100, it) })
        val telegram = source(limits = limits(historyPage = 2))
        telegram.next()

        telegram.reread(unit)
        client.historyAsks.clear()
        telegram.next()
        telegram.next()

        // Every page since the re-read belongs to the backfill walk, which records ground. A gap
        // still running beside it would have asked for a page of its own.
        assertEquals(listOf(Triple(100L, null, 2), Triple(100L, 5L, 2)), client.historyAsks) {
            "A catch-up kept reading alongside the re-read that superseded it."
        }
        assertEquals(day.plusSeconds(5), plan.planFor(Source.TELEGRAM, unit)!!.reached) {
            "The pages read after the re-read were not the backfill walk's."
        }
    }

    /**
     * A note about the reading that the store refuses must not stop the reading.
     *
     * The state row and the corpus are the same Postgres, so a store that cannot take the note is
     * usually one that cannot take the material either — but the specific failure, a constraint or
     * a drifted schema, leaves the corpus writable while every report fails. Letting that fail the
     * pass would stop ingestion to protect a report about ingestion.
     */
    @Test
    fun `a state store that refuses a report does not stop the pass`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1)))
        val telegram = source()
        telegram.next()
        state.fault = IllegalStateException("the state row cannot be written")

        client.feed(TelegramChange.Upserted(message(100, 9)))
        val batch = telegram.next()!!

        assertEquals(listOf("100/9"), nativeIds(batch.conversations.single()))
    }

    /**
     * The reporting interval is spent by a report that landed, and by nothing else.
     *
     * Two defects hide in this one line, and one of them shipped. Marking the window before the
     * writes turns a single refused write into a reporting silence for the whole interval; marking
     * it nowhere — which is what an assignment after a `throw` amounts to — makes every pass scan
     * the entire plan and rewrite both rows. Every other test here sets the interval to nothing,
     * so neither direction was held until this one.
     */
    @Test
    fun `a report spends the interval, and a refused one does not`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        val telegram = source(limits = limits(reportEvery = 1.minutes))
        telegram.next()
        telegram.next()
        assertEquals(1, state.freshness.size) { "The first report should have spent the window." }

        telegram.next()
        assertEquals(1, state.freshness.size) { "A pass inside the window reported again." }

        clock.advance(1.minutes)
        state.fault = IllegalStateException("the state row cannot be written")
        telegram.next()
        state.fault = null
        telegram.next()

        assertEquals(2, state.freshness.size) {
            "A refused report spent the window, so the next pass stayed silent."
        }
    }

    /**
     * Freshness and backfill completion, both read off the plan.
     *
     * Neither is reported before the source has been read in this process: the plan is empty then,
     * so the backfill report would say *complete* of a source nobody has enumerated, and freshness
     * would overwrite the last run's row with nothing believed complete.
     *
     * `complete` with no time reached is what a finished walk looks like — nothing bounds it. The
     * same `null` before anything is walked means the opposite, and the two fields together are
     * what tells them apart.
     */
    @Test
    fun `what the adapter reports about itself comes from the plan, once it has read anything`(): Unit = runBlocking {
        client.chats = listOf(chat(100))
        client.archive = mapOf(100L to listOf(message(100, 1)))
        val telegram = source(limits = limits(historyPage = 1))

        telegram.next()
        assertTrue(state.backfill.isEmpty())
        assertTrue(state.freshness.isEmpty())

        telegram.next()
        assertEquals(
            IngestStateLog.BackfillReport(complete = false, reached = day.plusSeconds(1)),
            state.backfill.last(),
        )
        assertEquals(IngestStateLog.FreshnessReport(clock.instant(), listOf(unit)), state.freshness.last())

        telegram.next()
        assertEquals(IngestStateLog.BackfillReport(complete = true, reached = null), state.backfill.last())
        assertTrue(state.freshness.last().lagging.isEmpty())
    }
}
