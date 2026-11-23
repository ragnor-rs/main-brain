package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Channel
import io.github.ragnorrs.mainbrain.domain.answer.Closed
import io.github.ragnorrs.mainbrain.domain.answer.Horizon
import io.github.ragnorrs.mainbrain.domain.answer.Reach
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The answer path's reads, against a real Postgres.
 *
 * **What is asserted here is bounding and ordering, because those are the parts that cannot be
 * checked anywhere else.** A fake returns what it was handed; only a real query can get a window
 * function's partition wrong, drop the caller's conversation order on the way through a join, or
 * silently read the whole corpus when a limit was meant per container. Each of those produces a
 * plausible answer on a small fixture and a prompt nobody can afford on a real archive.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case mints its own records
 * and every assertion is scoped to them.
 */
class RetrievalStoreTest {

    private val retrieval = JdbcRetrieval(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val contacts = JdbcContactRepository(StoreRig.database)
    private val clients = JdbcClientRepository(StoreRig.database)
    private val commitments = JdbcCommitmentRepository(StoreRig.database)
    private val closures = JdbcClosureRepository(StoreRig.database)
    private val events = JdbcEventRepository(StoreRig.database)
    private val statements = JdbcStatementRepository(StoreRig.database)

    @Test
    fun `the tail of a conversation is read, oldest first`(): Unit = runBlocking {
        // The newest are selected and returned in conversation order: a stretch read backwards is a
        // different conversation, and reversing at the call site is a step one caller will forget.
        val conversation = aConversation("tail")
        write(conversation, (1..5).map { message(conversation, "turn $it", at = EARLIEST.plusSeconds(it * 60L)) })

        val read = retrieval.recentMessages(listOf(conversation), perConversation = 3)

        assertEquals(listOf("turn 3", "turn 4", "turn 5"), read.map { it.text })
    }

    @Test
    fun `the limit is per conversation rather than over the result`(): Unit = runBlocking {
        // A single limit over the union would let the busiest conversation consume the whole
        // allowance and return nothing for the other, which reads as a quiet thread rather than as a
        // bound applied in the wrong place.
        val busy = aConversation("busy")
        val quiet = aConversation("quiet")
        write(busy, (1..10).map { message(busy, "busy $it", at = EARLIEST.plusSeconds(it * 60L)) })
        write(quiet, listOf(message(quiet, "quiet one", at = EARLIEST)))

        val read = retrieval.recentMessages(listOf(busy, quiet), perConversation = 2)

        assertEquals(2, read.count { it.text?.startsWith("busy") == true })
        assertEquals(1, read.count { it.text == "quiet one" })
    }

    @Test
    fun `conversations come back in the order they were asked for`(): Unit = runBlocking {
        // The caller orders them — a named thread outranks one merely found — and an array's order
        // does not survive a join without being carried through it.
        val first = aConversation("ordered-first")
        val second = aConversation("ordered-second")
        write(first, listOf(message(first, "from the first", at = LATEST)))
        write(second, listOf(message(second, "from the second", at = EARLIEST)))

        val read = retrieval.recentMessages(listOf(second, first), perConversation = 5)

        assertEquals(listOf("from the second", "from the first"), read.map { it.text })
    }

    @Test
    fun `a withdrawn message is not quoted back`(): Unit = runBlocking {
        // The corpus keeps it, because nothing is ever removed. What the recorded withdrawal is for
        // is exactly this: retrieval declines to quote it while a question about history still finds
        // it through the repositories.
        val conversation = aConversation("withdrawn")
        val gone = message(conversation, "please ignore this", at = EARLIEST)
        write(conversation, listOf(gone, message(conversation, "the real one", at = LATEST)))
        messages.markWithdrawn(listOf(gone.externalId))

        val read = retrieval.recentMessages(listOf(conversation), perConversation = 5)

        assertEquals(listOf("the real one"), read.map { it.text })
    }

    @Test
    fun `nothing is read for no conversations`(): Unit = runBlocking {
        assertEquals(emptyList<Message>(), retrieval.recentMessages(emptyList(), perConversation = 5))
    }

    @Test
    fun `a contact's threads are found through the person record behind it`(): Unit = runBlocking {
        val conversation = aConversation("authored")
        val author = aPerson("author")
        write(conversation, listOf(message(conversation, "I wrote this", author = author, at = EARLIEST)))
        val contact = aContact(author)

        val found = retrieval.conversationsOf(subjectOf(Referent.OfContact(contact)), threads = 5)

        assertEquals(listOf(conversation), found)
    }

    @Test
    fun `a contact's threads are bounded per contact and ordered by recent activity`(): Unit = runBlocking {
        val author = aPerson("prolific")
        val old = aConversation("old-thread")
        val recent = aConversation("recent-thread")
        write(old, listOf(message(old, "long ago", author = author, at = EARLIEST)))
        write(recent, listOf(message(recent, "this week", author = author, at = LATEST)))
        val contact = aContact(author)

        val found = retrieval.conversationsOf(subjectOf(Referent.OfContact(contact)), threads = 1)

        assertEquals(listOf(recent), found, "A thread quiet for years outranked one that moved this week.")
    }

    @Test
    fun `a person's own direct chat is read ahead of the rooms they are busier in`(): Unit = runBlocking {
        // **The guarantee that lets resolution treat a direct chat and its contact as one subject.**
        // The thread that is *with* somebody is what a question about them is about, and under a
        // bound of a few threads it is exactly what recency pushes out: anybody active in several
        // rooms would be answered from the rooms and not from the conversation that is theirs.
        val author = aPerson("busy-elsewhere")
        val direct = aDirectChat(author)
        write(direct, listOf(message(direct, "between us", author = author, at = EARLIEST)))
        repeat(3) { n ->
            val room = aConversation("room-$n")
            write(room, listOf(message(room, "in a room", author = author, at = LATEST)))
        }
        val contact = aContact(author)

        val found = retrieval.conversationsOf(subjectOf(Referent.OfContact(contact)), threads = 1)

        assertEquals(
            listOf(direct),
            found,
            "The one thread that is with this person lost its place to rooms they also write in.",
        )
    }

    @Test
    fun `a client is reached through the conversation it was read from`(): Unit = runBlocking {
        val conversation = aConversation("a client thread")
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = conversation, name = "A Client")))

        val found = retrieval.conversationsOf(subjectOf(Referent.OfClient(client)), threads = 5)

        assertEquals(listOf(conversation), found)
    }

    @Test
    fun `a body of work is reached as the conversations of every strand`(): Unit = runBlocking {
        // The work is no entity and has no thread of its own, so what it reaches is what its
        // strands reach. They travel with it as the subject's context, which is what this read
        // already takes a list of — a reach built from the focus alone would answer a question
        // about the whole of a piece of work from nothing at all.
        val strands = (1..3).map { strand ->
            val conversation = aConversation("a strand $strand of one body of work")
            val client = EntityId.mint()
            clients.upsert(
                listOf(Client(id = client, conversation = conversation, name = "a body of work $strand")),
            )
            conversation to client
        }

        val found = retrieval.conversationsOf(
            Subject(
                focus = candidate(Referent.OfBodyOfWork("a body of work")),
                context = strands.map { (_, client) -> candidate(Referent.OfClient(client)) },
            ),
            threads = 5,
        )

        assertEquals(strands.map { (conversation, _) -> conversation }.toSet(), found.toSet())
    }

    @Test
    fun `a named conversation needs no query and outranks what was found for a contact`(): Unit = runBlocking {
        val named = aConversation("named")
        val author = aPerson("also-writes")
        val elsewhere = aConversation("elsewhere")
        write(elsewhere, listOf(message(elsewhere, "over here", author = author, at = LATEST)))
        val contact = aContact(author)

        val found = retrieval.conversationsOf(
            Subject(
                focus = candidate(Referent.OfConversation(named)),
                context = listOf(candidate(Referent.OfContact(contact))),
            ),
            threads = 5,
        )

        assertEquals(named, found.first())
        assertTrue(found.contains(elsewhere))
    }

    @Test
    fun `the inbox reaches no conversation at all`(): Unit = runBlocking {
        // Its scope rather than a failure to resolve it: a shape accepting the inbox reads entities
        // across everything instead of narrowing to a thread.
        assertEquals(
            emptyList<ExternalId>(),
            retrieval.conversationsOf(subjectOf(Referent.Inbox), threads = 5),
        )
    }

    @Test
    fun `commitments are narrowed to who stated them`(): Unit = runBlocking {
        val mine = EntityId.mint()
        val theirs = EntityId.mint()
        commitments.upsert(
            listOf(
                commitment("I will send it", statedBy = mine),
                commitment("They will send it", statedBy = theirs),
            ),
        )

        val read = retrieval.commitmentsOf(Reach.ByContacts(listOf(mine)), limit = 50)

        assertTrue(read.any { it.claim == "I will send it" })
        assertTrue(read.none { it.claim == "They will send it" })
        // Narrowed by who stated it and carrying who owes it, which are different contacts. The
        // narrowing is deliberately the author's: this read orders and selects by who said a thing,
        // and direction comes back on the row for whatever reads it later.
        assertEquals(
            listOf(OWED),
            read.filter { it.claim == "I will send it" }.map { it.obligedParty },
            "the obliged party did not survive this read, so the column is written and unreadable " +
                "through the one port an answer reaches it by",
        )
    }

    @Test
    fun `Reach Everything reads across everything, which is what the inbox means`(): Unit = runBlocking {
        val stated = EntityId.mint()
        commitments.upsert(listOf(commitment("something outstanding", statedBy = stated)))

        val read = retrieval.commitmentsOf(Reach.Everything, limit = 500)

        assertTrue(read.any { it.claim == "something outstanding" })
    }

    @Test
    fun `a narrow reach with nothing in it reads nothing, never everything`(): Unit = runBlocking {
        // The defect this guards: an empty narrowing used to mean the whole corpus, so a client with
        // no resolved thread was answered from every client's commitments.
        commitments.upsert(listOf(commitment("somebody else's promise", statedBy = EntityId.mint())))

        assertEquals(
            emptyList<String>(),
            retrieval.commitmentsOf(Reach.ByContacts(emptyList()), limit = 500).map { it.claim },
        )
        assertEquals(
            emptyList<String>(),
            retrieval.commitmentsOf(Reach.InConversations(emptyList()), limit = 500).map { it.claim },
        )
        assertEquals(
            emptyList<String>(),
            retrieval.eventsAhead(Reach.InConversations(emptyList()), aroundNow(), limit = 500).map { it.claim },
        )
    }

    @Test
    fun `commitments are narrowed to the conversation they were stated in`(): Unit = runBlocking {
        // How a client or a conversation subject is answered: the client is a reading of one
        // conversation's label, so what was promised to it is what was promised in that thread.
        val ours = aConversation("ours")
        val theirs = aConversation("theirs")
        val inOurs = message(ours, "we will ship on Friday", at = LATEST)
        val inTheirs = message(theirs, "we will ship on Monday", at = LATEST)
        write(ours, listOf(inOurs))
        write(theirs, listOf(inTheirs))
        commitments.upsert(
            listOf(
                commitmentEvidencedBy(ours, inOurs, "ship on Friday"),
                commitmentEvidencedBy(theirs, inTheirs, "ship on Monday"),
            ),
        )

        val read = retrieval.commitmentsOf(Reach.InConversations(listOf(ours)), limit = 50)

        assertEquals(listOf("ship on Friday"), read.map { it.claim })
    }

    @Test
    fun `events are narrowed to the conversation they were stated in`(): Unit = runBlocking {
        val ours = aConversation("events-ours")
        val theirs = aConversation("events-theirs")
        val inOurs = message(ours, "the review is next week", at = LATEST)
        val inTheirs = message(theirs, "their review is next week", at = LATEST)
        write(ours, listOf(inOurs))
        write(theirs, listOf(inTheirs))
        events.upsert(
            listOf(
                eventEvidencedBy(ours, inOurs, "our review", occursAt = SOON),
                eventEvidencedBy(theirs, inTheirs, "their review", occursAt = SOON),
            ),
        )

        val read = retrieval.eventsAhead(Reach.InConversations(listOf(ours)), aroundNow(), limit = 50)

        assertEquals(listOf("our review"), read.map { it.claim })
    }

    @Test
    fun `commitments come back most recently evidenced first`(): Unit = runBlocking {
        val stated = EntityId.mint()
        commitments.upsert(
            listOf(
                commitment("the older promise", statedBy = stated, at = EARLIEST),
                commitment("the newer promise", statedBy = stated, at = LATEST),
            ),
        )

        val read = retrieval.commitmentsOf(Reach.ByContacts(listOf(stated)), limit = 50)

        assertEquals(listOf("the newer promise", "the older promise"), read.map { it.claim })
    }

    @Test
    fun `the commitment limit is applied in the statement`(): Unit = runBlocking {
        val stated = EntityId.mint()
        commitments.upsert((1..5).map { commitment("promise $it", statedBy = stated) })

        assertEquals(2, retrieval.commitmentsOf(Reach.ByContacts(listOf(stated)), limit = 2).size)
    }

    @Test
    fun `events are filtered on when they happen, not on when they were mentioned`(): Unit = runBlocking {
        // A date read out of a message two years ago can be next week. Filtering on the mention
        // would miss it while surfacing every passing reference made recently.
        val stated = EntityId.mint()
        events.upsert(
            listOf(
                event(
                    "mentioned long ago, happens soon",
                    statedBy = stated,
                    mentionedAt = EARLIEST,
                    occursAt = SOON,
                ),
                event(
                    "mentioned recently, happened long ago",
                    statedBy = stated,
                    mentionedAt = LATEST,
                    occursAt = EARLIEST,
                ),
            ),
        )

        val read = retrieval.eventsAhead(Reach.ByContacts(listOf(stated)), aroundNow(), limit = 50)

        assertEquals(listOf("mentioned long ago, happens soon"), read.map { it.claim })
    }

    @Test
    fun `an event with no date is excluded from a window`(): Unit = runBlocking {
        // There is nothing to place it in time with, and admitting it to a window it cannot claim
        // would answer a question about that window with it. `eventsUndated` is what reads it
        // instead — asserted below, because excluded from both reads it is reachable through
        // nothing at all.
        val stated = EntityId.mint()
        events.upsert(listOf(event("some day perhaps", statedBy = stated, occursAt = null)))

        assertEquals(
            emptyList<String>(),
            retrieval.eventsAhead(Reach.ByContacts(listOf(stated)), aroundNow(), limit = 50).map { it.claim },
        )
        assertEquals(
            emptyList<String>(),
            retrieval.eventsPast(Reach.ByContacts(listOf(stated)), aroundNow(), limit = 50).map { it.claim },
            "The backward window admitted what the forward one excluded, so the two disagree about " +
                "what a date means.",
        )
    }

    @Test
    fun `an event with no date is read by the one statement that asks for no window`(): Unit = runBlocking {
        // The defect this closes: the dated reads select on when the event happens, so a row with
        // no moment satisfied neither and was recognised, stored, evidenced, and in no answer. The
        // sweep this was filed off had such events.
        val stated = EntityId.mint()
        events.upsert(
            listOf(
                event("some day perhaps", statedBy = stated, occursAt = null),
                event("the review, on a date", statedBy = stated, occursAt = SOON),
            ),
        )

        val read = retrieval.eventsUndated(Reach.ByContacts(listOf(stated)), limit = 50)

        assertEquals(
            listOf("some day perhaps"),
            read.map { it.claim },
            "A dated event reached the undated read, which would offer the same finding twice and " +
                "once without its date.",
        )
    }

    @Test
    fun `undated events come back most recently mentioned first`(): Unit = runBlocking {
        // The only time such a row has. Ordered by it rather than left to the planner, which is how
        // a limit quietly keeps whichever rows it reached first.
        val stated = EntityId.mint()
        events.upsert(
            listOf(
                event("mentioned first", statedBy = stated, mentionedAt = EARLIEST, occursAt = null),
                event("mentioned later", statedBy = stated, mentionedAt = LATEST, occursAt = null),
            ),
        )

        val read = retrieval.eventsUndated(Reach.ByContacts(listOf(stated)), limit = 50)

        assertEquals(listOf("mentioned later", "mentioned first"), read.map { it.claim })
    }

    @Test
    fun `an undated event is narrowed to its conversation, like a dated one`(): Unit = runBlocking {
        // What it cannot say is when, not whom it concerns or where it was said — so every narrowing
        // a dated event is reachable through has to reach this one too.
        val ours = aConversation("undated-ours")
        val theirs = aConversation("undated-theirs")
        val inOurs = message(ours, "we should meet about it", at = LATEST)
        val inTheirs = message(theirs, "they should meet about it", at = LATEST)
        write(ours, listOf(inOurs))
        write(theirs, listOf(inTheirs))
        events.upsert(
            listOf(
                eventEvidencedBy(ours, inOurs, "our undated meeting", occursAt = null),
                eventEvidencedBy(theirs, inTheirs, "their undated meeting", occursAt = null),
            ),
        )

        val read = retrieval.eventsUndated(Reach.InConversations(listOf(ours)), limit = 50)

        assertEquals(listOf("our undated meeting"), read.map { it.claim })
    }

    @Test
    fun `the undated event limit is applied in the statement`(): Unit = runBlocking {
        val stated = EntityId.mint()
        events.upsert((1..5).map { event("some day $it", statedBy = stated, occursAt = null) })

        assertEquals(2, retrieval.eventsUndated(Reach.ByContacts(listOf(stated)), limit = 2).size)
    }

    @Test
    fun `the two directions spend their limits at opposite ends of the same window`(): Unit = runBlocking {
        // The limit has to bite for this to mean anything: six dated events inside one window, read
        // three at a time from each end. Ascending must yield the three earliest and descending the
        // three latest — which is what makes a forward window return what is coming rather than the
        // oldest rows in it, and the reason the two reads are separate.
        val stated = EntityId.mint()
        val first = Instant.parse("2026-09-10T09:00:00Z")
        val dated = (0..5).map { first.plus(Duration.ofDays(it * 5L)) }
        events.upsert(dated.mapIndexed { at, on -> event("day $at", statedBy = stated, occursAt = on) })
        val window = Horizon(from = first, until = first.plus(Duration.ofDays(40)))

        val ahead = retrieval.eventsAhead(Reach.ByContacts(listOf(stated)), window, limit = 3)
        val past = retrieval.eventsPast(Reach.ByContacts(listOf(stated)), window, limit = 3)

        assertEquals(listOf("day 0", "day 1", "day 2"), ahead.map { it.claim })
        assertEquals(listOf("day 5", "day 4", "day 3"), past.map { it.claim })
    }

    @Test
    fun `past events come back most recent first`(): Unit = runBlocking {
        val stated = EntityId.mint()
        val older = Instant.parse("2026-09-05T09:00:00Z")
        val newer = Instant.parse("2026-09-25T09:00:00Z")
        events.upsert(
            listOf(
                event("the older one", statedBy = stated, occursAt = older),
                event("the more recent one", statedBy = stated, occursAt = newer),
            ),
        )

        val read = retrieval.eventsPast(
            Reach.ByContacts(listOf(stated)),
            Horizon(from = Instant.parse("2026-09-01T00:00:00Z"), until = LATEST),
            limit = 50,
        )

        assertEquals(listOf("the more recent one", "the older one"), read.map { it.claim })
    }

    @Test
    fun `events come back soonest first`(): Unit = runBlocking {
        val stated = EntityId.mint()
        events.upsert(
            listOf(
                event("the later one", statedBy = stated, occursAt = SOON.plusSeconds(3_600)),
                event("the sooner one", statedBy = stated, occursAt = SOON),
            ),
        )

        val read = retrieval.eventsAhead(Reach.ByContacts(listOf(stated)), aroundNow(), limit = 50)

        assertEquals(listOf("the sooner one", "the later one"), read.map { it.claim })
    }

    @Test
    fun `a merged contact is read as the human, not as the account asked for`(): Unit = runBlocking {
        // Two accounts linked into one head. Narrowing on either must see what both stated: a
        // contact identifier names a row, and the question is about the person.
        val head = aContact(aPerson("head"))
        val other = aContact(aPerson("other"))
        link(merged = other, into = head)
        commitments.upsert(
            listOf(
                commitment("said under the head", statedBy = head),
                commitment("said under the other account", statedBy = other),
            ),
        )

        val asHead = retrieval.commitmentsOf(Reach.ByContacts(listOf(head)), limit = 50).map { it.claim }
        val asOther = retrieval.commitmentsOf(Reach.ByContacts(listOf(other)), limit = 50).map { it.claim }

        assertEquals(setOf("said under the head", "said under the other account"), asHead.toSet())
        // Asked for by the merged account, which is the direction that needs the walk upward first.
        assertEquals(asHead.toSet(), asOther.toSet())
    }

    @Test
    fun `an unmerged contact reads exactly what it stated`(): Unit = runBlocking {
        // The widening must not reach sideways. Two contacts that were never linked are two people,
        // and a cluster walk that returned both would put another person's commitments in the answer
        // with citations that pass every check.
        val one = aContact(aPerson("unlinked one"))
        val two = aContact(aPerson("unlinked two"))
        commitments.upsert(
            listOf(commitment("only mine", statedBy = one), commitment("only theirs", statedBy = two)),
        )

        val read = retrieval.commitmentsOf(Reach.ByContacts(listOf(one)), limit = 50).map { it.claim }

        assertEquals(listOf("only mine"), read)
    }

    @Test
    fun `the thread bound stays per human when a human is several accounts`(): Unit = runBlocking {
        // The reason the head travels with each row. Partitioning by the contact row would give a
        // merged person one allowance per account, so a merge would quietly widen the prompt.
        val headRecord = aPerson("busy head")
        val otherRecord = aPerson("busy other")
        val head = aContact(headRecord)
        val other = aContact(otherRecord)
        link(merged = other, into = head)
        repeat(3) { n ->
            val conversation = aConversation("head-thread-$n")
            write(conversation, listOf(message(conversation, "x", author = headRecord)))
        }
        repeat(3) { n ->
            val conversation = aConversation("other-thread-$n")
            write(conversation, listOf(message(conversation, "x", author = otherRecord)))
        }

        val read = retrieval.conversationsOf(subjectOf(Referent.OfContact(head)), threads = 4)

        // Four, not six: one allowance for the person, drawn from both accounts' threads. Six would
        // mean the window partitioned by the row, which is the defect this shape exists to avoid.
        assertEquals(4, read.size)
    }

    // --- doubles and fixtures -------------------------------------------------------------

    @Test
    fun `a contact's attributes are read, newest window first`(): Unit = runBlocking {
        val record = aPerson("attributed")
        val contact = aContact(record)
        statements.record(
            listOf(
                anAttribute(contact, "works at", "Earlier Place", from = EARLIEST, until = LATEST),
                anAttribute(contact, "works at", "Later Place", from = LATEST),
            ),
        )

        val read = retrieval.statementsOf(Reach.ByContacts(listOf(contact)), limit = 10)

        assertEquals(
            listOf("Later Place", "Earlier Place"),
            read.map { it.value },
            "the newest window must come first, so an answer leads with what holds",
        )
    }

    @Test
    fun `the attribute limit bounds statements rather than evidence rows`(): Unit = runBlocking {
        // A statement carries one row per piece of evidence, so a limit applied after the join would
        // return a handful of well-corroborated facts and silently drop the rest. Two facts, one of
        // them corroborated twice, under a limit of two: a limit on rows would return only the first.
        val contact = aContact(aPerson("corroborated"))
        statements.record(
            listOf(
                anAttribute(contact, "works at", "Twice Evidenced", from = EARLIEST).copy(
                    evidence = setOf(evidence("ev-one", EARLIEST), evidence("ev-two", EARLIEST)),
                ),
                anAttribute(contact, "is based in", "Once Evidenced", from = LATEST),
            ),
        )

        val read = retrieval.statementsOf(Reach.ByContacts(listOf(contact)), limit = 2)

        assertEquals(2, read.size, "the limit counted evidence rows rather than facts")
        assertEquals(setOf("Twice Evidenced", "Once Evidenced"), read.map { it.value }.toSet())
    }

    @Test
    fun `an attribute under a merged-away account is reached through the human`(): Unit = runBlocking {
        // Every read on this port narrows by the whole cluster, because a derived row is attributed to
        // the account that stated it. Asking about one account and getting only its own facts would
        // answer a question about a person from a fraction of what they said.
        val merged = aContact(aPerson("second-account"))
        val head = aContact(aPerson("main-account"))
        link(merged = merged, into = head)
        statements.record(listOf(anAttribute(merged, "has the role", "A Role", from = EARLIEST)))

        val read = retrieval.statementsOf(Reach.ByContacts(listOf(head)), limit = 10)

        assertEquals(listOf("A Role"), read.map { it.value })
    }

    @Test
    fun `a conversation reaches no attribute`(): Unit = runBlocking {
        // A statement's subject is a contact, so every statement stated in a thread is about whoever
        // spoke rather than about the client the thread stands for. Read by conversation, a question
        // about a client would be answered with facts about its participants.
        val conversation = aConversation("attributeless")
        val contact = aContact(aPerson("spoke-there"))
        statements.record(listOf(anAttribute(contact, "charges", "A Rate", from = EARLIEST)))

        val read = retrieval.statementsOf(Reach.InConversations(listOf(conversation)), limit = 10)

        assertEquals(emptyList<String>(), read.map { it.value })
    }

    @Test
    fun `a party's name is what the corpus called its contact`(): Unit = runBlocking {
        val party = aContact(aPerson("named"), displayName = "A Named Person")

        assertEquals(mapOf(party to "A Named Person"), retrieval.namesOf(listOf(party)))
    }

    @Test
    fun `a contact the corpus never named has no entry rather than a blank one`(): Unit = runBlocking {
        // An absent entry is what tells the answer path to say what was undertaken without saying who
        // by. A blank string would be a name as far as a map lookup is concerned, and would print as
        // one beside a claim.
        val nameless = aContact(aPerson("nameless"), displayName = null)

        assertEquals(emptyMap<EntityId, String>(), retrieval.namesOf(listOf(nameless)))
    }

    @Test
    fun `a party nothing has ever recognised has no entry`(): Unit = runBlocking {
        assertEquals(emptyMap<EntityId, String>(), retrieval.namesOf(listOf(EntityId.mint())))
    }

    @Test
    fun `nothing is read for no parties`(): Unit = runBlocking {
        assertEquals(emptyMap<EntityId, String>(), retrieval.namesOf(emptyList()))
    }

    @Test
    fun `a nameless account takes the name of the human it was merged into`(): Unit = runBlocking {
        // The account that stated a thing often carries no profile name while another account of the
        // same human does. Naming only the account would drop a name the store holds, and withholding
        // it protects nothing: the finding is in the answer at all because the narrowing resolved the
        // subject to this whole cluster, so a wrong merge has already misattributed the claim.
        val nameless = aContact(aPerson("merged-away"), displayName = null)
        val head = aContact(aPerson("the-head"), displayName = "The Head")
        link(merged = nameless, into = head)

        assertEquals(
            mapOf(nameless to "The Head"),
            retrieval.namesOf(listOf(nameless)),
            "a nameless account was left unnamed while its own human carried a name",
        )
    }

    @Test
    fun `an account's own name wins over its human's`(): Unit = runBlocking {
        // Its own is the more specific of the two, and the head's is a stand-in rather than a
        // correction. Resolving to the head first would rename every account of a merged human to
        // whichever one the scoring happened to make canonical.
        val own = aContact(aPerson("has-its-own"), displayName = "Its Own Name")
        val head = aContact(aPerson("canonical"), displayName = "The Canonical Name")
        link(merged = own, into = head)

        assertEquals(mapOf(own to "Its Own Name"), retrieval.namesOf(listOf(own)))
    }

    @Test
    fun `a party neither it nor its human ever named has no entry`(): Unit = runBlocking {
        // Both error directions of the fallback in one case: the cluster is searched, and finding
        // nothing there still yields no entry rather than a blank that would print as a name.
        val nameless = aContact(aPerson("nameless-member"), displayName = null)
        val namelessHead = aContact(aPerson("nameless-head"), displayName = null)
        link(merged = nameless, into = namelessHead)

        assertEquals(emptyMap<EntityId, String>(), retrieval.namesOf(listOf(nameless)))
    }

    @Test
    fun `a client identifier is not named here, whatever name its conversation carries`(): Unit = runBlocking {
        // An attribute's subject is a contact or a client, and this read knows only contacts. A client
        // resolves to nothing for a structural reason rather than for want of a profile name, which is
        // why the port says so rather than leaving a caller to read it as an unnamed person.
        val conversation = aConversation("a-named-thread")
        val client = aClient(conversation)

        assertEquals(emptyMap<EntityId, String>(), retrieval.namesOf(listOf(client)))
    }

    @Test
    fun `two turns by two authors each take their own name`(): Unit = runBlocking {
        // **The case that makes the keying claim testable at all.** Every other case here asks about
        // one message, and with one row in the result set every possible keying produces the same
        // map — a statement that crossed its two columns, or a mapper that paired rows with the
        // caller's list by position, passes all of them.
        //
        // **Asked in the opposite order to the one the rows come back in**, which is what makes two
        // messages discriminate rather than merely be two. The join returns them in the table's own
        // order, so a positional mapper agrees with a keyed one for any ask that happens to be in
        // that order — measured: with the mapper broken to pair by position, this case passed until
        // the ask was reversed.
        val where = aConversation("two-authors")
        val first = aPerson("wrote-first")
        val second = aPerson("wrote-second")
        aContact(first, displayName = "The First Author")
        aContact(second, displayName = "The Second Author")
        val one = message(where, "the first words", author = first)
        val other = message(where, "the second words", author = second)
        write(where, listOf(one, other))

        val read = retrieval.turnsOf(listOf(other.externalId, one.externalId))

        assertEquals("The First Author", read[one.externalId]?.author)
        assertEquals("The Second Author", read[other.externalId]?.author)
    }

    @Test
    fun `a turn says whether its author is the account the corpus was read through`(): Unit =
        runBlocking {
            // What a composed reply needs and no other reading does: a shape writing *as* somebody
            // has to know which side of the thread it is on. Without it a reply to a counterpart
            // who wrote last comes back in that counterpart's voice, fluent and unsendable, and
            // every hermetic check passes because the material looks the same either way.
            //
            // **Asked for both in one read, and in an order the rows cannot be returning.** Two
            // authors in one conversation, the owner's record written first so that the rows come
            // back in that order, and the read asks for the counterpart's turn first — so a mapper
            // pairing results with the asked list by position hands the owner's verdict to the
            // counterpart's turn, and this case fails. Asked in the rows' own order it would pass.
            //
            // The counterpart is marked `false` rather than left unset, which is what the adapter
            // does to every sibling of a known account. Left null this would assert the
            // nothing-established case twice over and never the denial.
            val where = aConversation("self-marked")
            val owner = aPerson("the-owner", isSelf = true)
            val counterpart = aPerson("the-counterpart", isSelf = false)
            aContact(owner, displayName = "The Owner")
            aContact(counterpart, displayName = "The Counterpart")
            val mine = message(where, "I will send the figures on Friday", author = owner)
            val theirs = message(where, "any word on those figures?", author = counterpart)
            write(where, listOf(mine, theirs))

            val read = retrieval.turnsOf(listOf(theirs.externalId, mine.externalId))

            assertEquals(
                true,
                read[mine.externalId]?.authorIsSelf,
                "the owner's own turn does not say so, so nothing in the material tells a draft " +
                    "which side it is written from",
            )
            assertEquals(
                false,
                read[theirs.externalId]?.authorIsSelf,
                "the counterpart's turn claims to be the owner's, which inverts a draft the " +
                    "other way",
            )
        }

    @Test
    fun `a turn is the owner's where any account of that human is the one read through`(): Unit =
        runBlocking {
            // **The case that tells the cluster read from the row read, and the only one that
            // does.** A human is read through where any of their accounts is, so the question is
            // the cluster's — `selfClusters` says why it cannot be a row, and the subject search
            // has carried the same case since the mark was introduced.
            //
            // Here the marked account is merged *into* an unmarked head and the turn is written by
            // the head's own record. Read row by row the head is not marked, so the owner's own
            // turn comes back as somebody else's and the draft is composed in their counterpart's
            // voice — this card's defect, reopened by the merge rather than by the prompt. The
            // adapter marks a known account's siblings `false`, so the head is explicitly denied
            // rather than merely unset, which is the harder half.
            val where = aConversation("self-by-cluster")
            val head = aPerson("merged-head", isSelf = false)
            val marked = aPerson("merged-marked", isSelf = true)
            val headContact = aContact(head, displayName = "The Owner")
            link(merged = aContact(marked, displayName = "The Owner"), into = headContact)
            val turn = message(where, "I will send the figures on Friday", author = head)
            write(where, listOf(turn))

            assertEquals(
                true,
                retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.authorIsSelf,
                "the mark is read off the author's own row, so merging the owner's accounts makes " +
                    "their turns read as a counterpart's again",
            )
        }

    @Test
    fun `a turn written by a merged-away account of the owner is still the owner's`(): Unit =
        runBlocking {
            // The other direction of the same rule, and what the case above cannot see. There the
            // author was the head, so the downward walk reached the mark from the seed it was given
            // — asking about the author's own contact and asking about its human are the same
            // question. Here the author is the account the merge made subordinate, so the walk has
            // to start from the head: seeded with the member it reaches nothing, because nothing was
            // merged into it.
            //
            // Which is to say the head resolution is load-bearing on its own, and this is the case
            // that fails without it.
            val where = aConversation("self-by-member")
            val head = aPerson("owner-head", isSelf = true)
            val member = aPerson("owner-member", isSelf = false)
            val memberContact = aContact(member, displayName = "The Owner")
            link(merged = memberContact, into = aContact(head, displayName = "The Owner"))
            val turn = message(where, "I will send the figures on Friday", author = member)
            write(where, listOf(turn))

            assertEquals(
                true,
                retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.authorIsSelf,
                "the owner's subordinate account answers for itself rather than for its human, so " +
                    "its turns read as a counterpart's",
            )
        }

    @Test
    fun `a turn whose author nothing marks either way is not the account read through`(): Unit =
        runBlocking {
            // The column is nullable, and unset is what a corpus whose adapter never answered the
            // question looks like — where one has, it marks its own account true and every other
            // false. Read as anything but false, an author nothing marks either way would make
            // every counterpart the sender.
            val where = aConversation("self-unmarked")
            val writer = aPerson("unmarked-writer")
            aContact(writer, displayName = "Someone")
            val turn = message(where, "something was said", author = writer)
            write(where, listOf(turn))

            assertEquals(false, retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.authorIsSelf)
        }

    @Test
    fun `a third party's turn says so, and the subject's own says it is not`(): Unit =
        runBlocking {
            // **What tells a subject's own material from the business of the room it was said in.**
            // A person's threads are mostly rooms other people write in, so the tail of them
            // carries matters the subject took no part in — and a turn offered with a name and
            // nothing else is read as evidence of where the subject stands, which cites correctly
            // and passes the grounding gate.
            //
            // **Asked for both in one read, in an order the rows cannot be returning**, which is
            // the trap the owner's case above sets for the same mapper: the third party's record is
            // written first so the rows come back that way, and the read asks for the subject's
            // turn first. A mapper pairing results with the asked list by position hands each turn
            // the other's verdict, and this case fails.
            //
            // The third party's own-ness is marked `false` on the record rather than left unset,
            // because the denial is only stated where something has settled whose account it is —
            // which the unestablished case below is about.
            val where = aConversation("another-party")
            val third = aPerson("a-third-party", isSelf = false)
            val them = aPerson("the-subject", isSelf = false)
            aContact(third, displayName = "Another Participant")
            val subject = aContact(them, displayName = "The Subject")
            val theirs = message(where, "I will send the figures on Friday", author = them)
            val others = message(where, "we cancelled the standup, I am away", author = third)
            write(where, listOf(others, theirs))

            val read = retrieval.turnsOf(listOf(theirs.externalId, others.externalId), listOf(subject))

            assertEquals(
                true,
                read[others.externalId]?.authorIsAnotherParty,
                "a third party's turn claims to be the subject's or the reader's, which is the " +
                    "defect itself: somebody else's cancelled meeting reported as where things stand",
            )
            assertEquals(
                false,
                read[theirs.externalId]?.authorIsAnotherParty,
                "the subject's own turn reads as another participant's, so their own words are " +
                    "confined to the thread's context and the reply has nothing of theirs",
            )
        }

    @Test
    fun `the reader's own turn is not another party's`(): Unit =
        runBlocking {
            // **The half the subject comparison cannot answer on its own.** The reader is not the
            // subject either, so a field that only asked *is this the subject* would deny the
            // reader's own turns — and the instruction then orders them out of the opening and out
            // of what to do next. The reader's side of a thread is half of where things stand with
            // somebody, so that is a worse answer than the defect this field exists to fix.
            val where = aConversation("reader-not-another")
            val owner = aPerson("the-owner", isSelf = true)
            val them = aPerson("subject-beside-owner", isSelf = false)
            aContact(owner, displayName = "The Reader")
            val subject = aContact(them, displayName = "The Subject")
            val mine = message(where, "any word on those figures?", author = owner)
            write(where, listOf(mine))

            assertEquals(
                false,
                retrieval.turnsOf(listOf(mine.externalId), listOf(subject))[mine.externalId]
                    ?.authorIsAnotherParty,
                "the reader's own turn is published as another participant's business, which " +
                    "demotes their half of the relationship out of the answer",
            )
        }

    @Test
    fun `a turn says nothing where nothing establishes whose account wrote it`(): Unit =
        runBlocking {
            // **`self` is three-valued on the record and null is not false** — the column's own
            // migration says so, because a source whose adapter cannot report the account it
            // authenticated as must not be read as having denied it. `authorIsSelf` folds that
            // unknown into `false`, which is right for a reader that composes as somebody or does
            // not; a field that states the denial has a third thing to do and must do it.
            //
            // Without that, the owner's own account on a corpus whose adapter never reported itself
            // is published as another participant's. Here nothing is merged and nothing is marked,
            // so the author is neither the subject nor established as the reader.
            val where = aConversation("unestablished-self")
            val writer = aPerson("unmarked-writer")
            val them = aPerson("subject-beside-unmarked", isSelf = false)
            aContact(writer, displayName = "Somebody")
            val subject = aContact(them, displayName = "The Subject")
            val turn = message(where, "something was said", author = writer)
            write(where, listOf(turn))

            assertEquals(
                null,
                retrieval.turnsOf(listOf(turn.externalId), listOf(subject))[turn.externalId]
                    ?.authorIsAnotherParty,
                "an account nothing has placed is denied rather than left unanswered, so a turn " +
                    "that may be the reader's own is reported as somebody else's business",
            )
        }

    @Test
    fun `the subject's own turn is answered however their ownership reads`(): Unit =
        runBlocking {
            // The one case the unestablished fold must not swallow. The subject's account is
            // unmarked too, so a rule that returned null for anything unplaced would stop answering
            // for the subject themselves — and their own turns would go unmarked for the right
            // reason by accident, hiding a real regression on the third-party side.
            //
            // A question about somebody has settled that question for them: nothing is being
            // denied, so there is nothing to withhold.
            val where = aConversation("subject-unmarked-self")
            val them = aPerson("subject-with-no-self-mark")
            val subject = aContact(them, displayName = "The Subject")
            val turn = message(where, "I will send the figures on Friday", author = them)
            write(where, listOf(turn))

            assertEquals(
                false,
                retrieval.turnsOf(listOf(turn.externalId), listOf(subject))[turn.externalId]
                    ?.authorIsAnotherParty,
                "the subject's own turn goes unanswered because nothing marks whose account it " +
                    "is, which withholds a fact the question itself settled",
            )
        }

    @Test
    fun `a turn is not another party's where any account of that human is the subject`(): Unit =
        runBlocking {
            // The cluster read rather than the row read, which is the same rule the owner's mark
            // keeps and for the same reason: a subject resolved to one account, and a human writes
            // under whichever of theirs they happened to use. Here the subject is named by the head
            // and the turn is written by the account merged into it, so a comparison of identifiers
            // calls the subject's own second account another participant — on exactly the corpus
            // where a person having several accounts is why the merge exists.
            val where = aConversation("subject-by-member")
            val head = aPerson("subject-head", isSelf = false)
            val member = aPerson("subject-member", isSelf = false)
            val headContact = aContact(head, displayName = "The Subject")
            link(merged = aContact(member, displayName = "The Subject"), into = headContact)
            val turn = message(where, "I will send the figures on Friday", author = member)
            write(where, listOf(turn))

            assertEquals(
                false,
                retrieval.turnsOf(listOf(turn.externalId), listOf(headContact))[turn.externalId]
                    ?.authorIsAnotherParty,
                "the comparison is on the row, so the subject's merged-away account reads as a " +
                    "third party and their own turns are demoted to context",
            )
        }

    @Test
    fun `a turn is not another party's where the subject is named by a merged-away account`(): Unit =
        runBlocking {
            // The other direction, and what the case above cannot see. There the subject was the
            // head, so a downward walk from the seed reaches the author. Here the subject is named
            // by the account the merge made subordinate: seeded with that member the walk reaches
            // nothing, because nothing was merged into it, so the head resolution on the subject's
            // own side is what this case proves load-bearing.
            //
            // It is the ordinary case rather than a corner: a resolver hands out whichever
            // identifier matched the name a question used, which is as likely to be a merged-away
            // account as a head.
            val where = aConversation("subject-by-head")
            val head = aPerson("named-head", isSelf = false)
            val member = aPerson("named-member", isSelf = false)
            val memberContact = aContact(member, displayName = "The Subject")
            link(merged = memberContact, into = aContact(head, displayName = "The Subject"))
            val turn = message(where, "I will send the figures on Friday", author = head)
            write(where, listOf(turn))

            assertEquals(
                false,
                retrieval.turnsOf(listOf(turn.externalId), listOf(memberContact))[turn.externalId]
                    ?.authorIsAnotherParty,
                "only the subject's own identifier is resolved, so naming them by a subordinate " +
                    "account makes every turn of theirs read as a third party's",
            )
        }

    @Test
    fun `a turn says nothing about whose it is where no subject was asked about`(): Unit =
        runBlocking {
            // Four of the five shapes ask nothing here, so the absent argument has to come back as
            // the absent answer rather than as a denial. Read as `true`, every turn of every answer
            // would be marked as somebody else's — including the turns of a shape that has no
            // subject at all — and the marker would be on the whole corpus.
            val where = aConversation("subject-unasked")
            val writer = aPerson("some-writer", isSelf = false)
            aContact(writer, displayName = "Someone")
            val turn = message(where, "something was said", author = writer)
            write(where, listOf(turn))

            assertEquals(
                null,
                retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.authorIsAnotherParty,
                "asking nothing is answered as a denial, so a shape with no subject marks every " +
                    "turn it reads as another participant's",
            )
        }

    @Test
    fun `a turn whose author reached no contact says nothing about whose it is`(): Unit =
        runBlocking {
            // **Asked with a subject, which is what makes this the fold and not the case above.**
            // The question was put and the store cannot answer it: the corpus names no author for
            // this turn, so there is nothing to compare. Collapsed into `true` the way the owner's
            // mark collapses its own unknown, an unattributed turn would be marked as somebody
            // else's — asserting an author the record does not have, and overriding the rule that
            // already covers such a turn by saying it was said without saying who by.
            val where = aConversation("subject-unattributed")
            val them = aPerson("the-known-subject", isSelf = false)
            val subject = aContact(them, displayName = "The Subject")
            val turn = message(where, "something was said", author = null)
            write(where, listOf(turn))

            assertEquals(
                null,
                retrieval.turnsOf(listOf(turn.externalId), listOf(subject))[turn.externalId]
                    ?.authorIsAnotherParty,
                "a turn the corpus names nobody for is marked as another participant's, which " +
                    "asserts an author the record does not have",
            )
        }

    @Test
    fun `a turn says which conversation it was said in`(): Unit = runBlocking {
        // What tells material about a subject from material a ranked read found for its wording: the
        // index stores no conversation, so without this a shape cannot tell the counterpart's own
        // older turn from a stranger's exchange and marks both alike.
        val where = aConversation("placed-turn")
        val elsewhere = aConversation("another-thread")
        val here = message(where, "said here", author = null)
        val there = message(elsewhere, "said there", author = null)
        write(where, listOf(here))
        write(elsewhere, listOf(there))

        val read = retrieval.turnsOf(listOf(here.externalId, there.externalId))

        assertEquals(where, read[here.externalId]?.conversation)
        assertEquals(elsewhere, read[there.externalId]?.conversation)
    }

    @Test
    fun `a turn says whether a reply to it would be read by other people`(): Unit = runBlocking {
        // **A different question from which conversation, and comparing identifiers cannot answer
        // it.** A shape composing a message to send has to know whether the thread it is replying
        // into is with one person or with a room — and on this archive most counterparts have no
        // one-to-one thread at all, so a draft that assumed one is a private reply sendable only in
        // front of other people. The kind is on the conversation row the author read already joins.
        val room = aRoom("a-shared-thread", name = "The Project Room")
        val privately = aConversation("a-private-thread")
        val said = message(room, "said in the room", author = null)
        val told = message(privately, "said one to one", author = null)
        write(room, listOf(said))
        write(privately, listOf(told))

        val read = retrieval.turnsOf(listOf(said.externalId, told.externalId))

        assertEquals(Channel.Room("The Project Room"), read[said.externalId]?.channel)
        assertEquals(Channel.OneToOne, read[told.externalId]?.channel)
    }

    @Test
    fun `a room the corpus holds no name for comes back as a room`(): Unit = runBlocking {
        // Every kind but a direct exchange *may* carry a name somebody chose, and may is not does.
        // Read as a nullable name alone, a nameless room would be indistinguishable from a private
        // thread — which is the reading this read exists to prevent, arriving by the other route.
        // A blank is the absence of one wearing a name's clothes, so it is folded into the same
        // answer rather than offered as a room called nothing.
        val nameless = aRoom("a-nameless-room", name = null)
        val blank = aRoom("a-blank-named-room", name = "   ")
        val here = message(nameless, "said somewhere", author = null)
        val there = message(blank, "said somewhere else", author = null)
        write(nameless, listOf(here))
        write(blank, listOf(there))

        val read = retrieval.turnsOf(listOf(here.externalId, there.externalId))

        assertEquals(Channel.Room(null), read[here.externalId]?.channel)
        assertEquals(Channel.Room(null), read[there.externalId]?.channel)
    }

    @Test
    fun `a topic inside a supergroup is a room, because other people read it`(): Unit = runBlocking {
        // The source distinguishes more containers than the question has answers: what a draft
        // needs is who is present, and a topic and a broadcast channel are both places where
        // somebody other than the two correspondents reads what is written. Matched as *not direct*
        // rather than as each room kind, so a container this build has not seen fails safe to the
        // half that costs a draft nothing worse than an audience it wrote for and did not have.
        val topic = aRoom("a-topic", name = "A Track", kind = ConversationKind.TOPIC)
        val said = message(topic, "said in the topic", author = null)
        write(topic, listOf(said))

        assertEquals(
            Channel.Room("A Track"),
            retrieval.turnsOf(listOf(said.externalId))[said.externalId]?.channel,
        )
    }

    @Test
    fun `a turn whose author is named with nothing but spaces carries no name`(): Unit = runBlocking {
        // A blank is a name as far as a map lookup is concerned, and it renders as the label with
        // nobody after it — which is the placeholder every renderer on this path is written not to
        // print. The column admits one, so the read is where it has to be stopped.
        val where = aConversation("blank-named")
        val writer = aPerson("blank-name-writer")
        aContact(writer, displayName = "   ")
        val turn = message(where, "something was said", author = writer)
        write(where, listOf(turn))

        assertEquals(null, retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author)
    }

    @Test
    fun `a turn's author is named through the contact behind its record`(): Unit = runBlocking {
        // A message records its author as the person record a source showed, and the name is a read
        // away: the derived layer mints a contact per record and carries the profile name onto it.
        // **Keyed by the message and not by its author**, because the shapes that reach a turn
        // through the index hold a record identifier and nothing else — the name read has to be
        // askable from that alone, or those turns go on being offered unattributed.
        val where = aConversation("named-author")
        val writer = aPerson("wrote-something")
        aContact(writer, displayName = "The Author")
        val turn = message(where, "something was said", author = writer)
        write(where, listOf(turn))

        assertEquals("The Author", retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author)
    }

    @Test
    fun `the name is the contact's and not the person record's`(): Unit = runBlocking {
        // Two names exist for one account — what the source showed on the person record, and what
        // the derived layer carries on the contact — and this read goes through the contact, which
        // is what makes the merge fallback below reachable at all. A read that took the person row's
        // name directly would be correct in the ordinary case and unable to borrow a human's.
        val where = aConversation("two-names")
        val writer = aPerson("the-person-row-name")
        aContact(writer, displayName = "The Contact Name")
        val turn = message(where, "something was said", author = writer)
        write(where, listOf(turn))

        assertEquals(
            "The Contact Name",
            retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author,
        )
    }

    @Test
    fun `a nameless account's turn takes the name of the human it was merged into`(): Unit = runBlocking {
        // The same fallback a finding's party gets, reached from the other key. An account that
        // writes frequently often carries no profile name while another account of the same human
        // does, and naming only the account would drop a name the store holds.
        val where = aConversation("merged-author")
        val writer = aPerson("wrote-namelessly")
        val nameless = aContact(writer, displayName = null)
        val head = aContact(aPerson("the-same-human"), displayName = "The Same Human")
        link(merged = nameless, into = head)
        val turn = message(where, "something was said", author = writer)
        write(where, listOf(turn))

        assertEquals(
            "The Same Human",
            retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author,
            "a nameless account's turn was left unnamed while its own human carried a name",
        )
    }

    @Test
    fun `a turn whose author nothing has reconciled has no entry`(): Unit = runBlocking {
        // No contact behind the author, so nothing to resolve a name through. The turn is offered
        // without one, and the gap closes by itself: identity runs over every person record under
        // no horizon.
        val where = aConversation("unreconciled-author")
        val turn = message(where, "something was said", author = aPerson("never-reconciled"))
        write(where, listOf(turn))

        assertEquals(null, retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author)
    }

    @Test
    fun `a turn that records no author at all has no entry`(): Unit = runBlocking {
        // One of the archive's own, which the corpus stores with no author. It is the same answer as
        // the two cases above, which is the point: a reader cannot act on the difference between a
        // turn whose sender is unrecorded and one whose sender was never named.
        val where = aConversation("authorless")
        val turn = message(where, "something was said", author = null)
        write(where, listOf(turn))

        assertEquals(null, retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author)
    }

    @Test
    fun `a turn whose author neither it nor its human ever named has no entry`(): Unit = runBlocking {
        // Both error directions of the fallback at once: the cluster is walked, and finding nothing
        // there still yields no entry rather than a blank that would print as a name.
        val where = aConversation("nameless-cluster")
        val writer = aPerson("nameless-writer")
        val nameless = aContact(writer, displayName = null)
        val namelessHead = aContact(aPerson("nameless-human"), displayName = null)
        link(merged = nameless, into = namelessHead)
        val turn = message(where, "something was said", author = writer)
        write(where, listOf(turn))

        assertEquals(null, retrieval.turnsOf(listOf(turn.externalId))[turn.externalId]?.author)
    }

    @Test
    fun `a record the corpus does not hold has no entry`(): Unit = runBlocking {
        // What an index span of a record since removed would ask about. Nothing in this build removes
        // a record, so this is the shape of the answer rather than a case that happens.
        assertEquals(
            emptyMap<ExternalId, Turn>(),
            retrieval.turnsOf(listOf(StoreRig.externalId("no-such-record"))),
        )
    }

    @Test
    fun `nothing is read for no turns`(): Unit = runBlocking {
        assertEquals(emptyMap<ExternalId, Turn>(), retrieval.turnsOf(emptyList()))
    }

    @Test
    fun `only a closed commitment comes back from the closure read, with the turn that closed it`(): Unit =
        runBlocking {
            // The three states in the store are closed, examined and nothing found, and never
            // examined — and the port collapses the last two deliberately, because to a reader they
            // are one answer: nothing recorded says this was discharged. So what has to hold here is
            // that the read is an inner join on a span, and that the message comes with it.
            val where = aConversation("closure-read")
            val closing = message(where, "the invoice is away", author = aPerson("sender"))
            write(where, listOf(closing))
            val closed = commitment("the quote is sent", statedBy = EntityId.mint())
            val examined = commitment("the contract is chased", statedBy = EntityId.mint())
            val unexamined = commitment("the call is made", statedBy = EntityId.mint())
            commitments.upsert(listOf(closed, examined, unexamined))
            closures.record(
                listOf(
                    Closure(
                        commitment = closed.id,
                        closedBy = Evidence(
                            span = SpanId(kind = RecordKind.MESSAGE, record = closing.externalId),
                            sourceTime = closing.sourceTime,
                        ),
                        weighed = Weighed(closing.sourceTime, closing.externalId),
                        decidedBy = "closure-1:test@1/MEDIUM",
                    ),
                    Closure(
                        commitment = examined.id,
                        closedBy = null,
                        weighed = Weighed(closing.sourceTime, closing.externalId),
                        decidedBy = "closure-1:test@1/MEDIUM",
                    ),
                ),
            )

            val read = retrieval.closuresOf(listOf(closed.id, examined.id, unexamined.id))

            val only = assertNotNull(read.singleOrNull(), "only the closed commitment may come back: $read")
            assertEquals(closed.id, only.commitment)
            assertEquals("the invoice is away", only.by.text, "the closing turn has to travel with the verdict")
            assertEquals(closing.externalId, only.by.externalId)
        }

    @Test
    fun `a closure whose message was withdrawn is not offered as evidence`(): Unit = runBlocking {
        // The same rule every other read here follows, with a verdict attached to it: quoting a
        // withdrawn message back is what the withdrawal is recorded to prevent, and citing one as
        // the thing that discharged a promise is that fault in its most load-bearing form.
        val where = aConversation("closure-withdrawn")
        val closing = message(where, "the invoice is away", author = aPerson("sender-withdrawn"))
        write(where, listOf(closing))
        val commitment = commitment("the quote is sent", statedBy = EntityId.mint())
        commitments.upsert(listOf(commitment))
        closures.record(
            listOf(
                Closure(
                    commitment = commitment.id,
                    closedBy = Evidence(
                        span = SpanId(kind = RecordKind.MESSAGE, record = closing.externalId),
                        sourceTime = closing.sourceTime,
                    ),
                    weighed = Weighed(closing.sourceTime, closing.externalId),
                    decidedBy = "closure-1:test@1/MEDIUM",
                ),
            ),
        )
        messages.markWithdrawn(listOf(closing.externalId))

        assertEquals(emptyList<Closed>(), retrieval.closuresOf(listOf(commitment.id)))
    }

    private suspend fun aConversation(label: String): ExternalId {
        val externalId = StoreRig.externalId(label)
        conversations.upsert(
            Conversation(externalId = externalId, kind = ConversationKind.DIRECT, name = label),
        )
        return externalId
    }

    /**
     * A conversation other people are in, named [name] where the corpus holds a name for it.
     *
     * [kind] defaults to the ordinary room because that is what the archive mostly holds, and is a
     * parameter so that a case can show the match is on *not direct* rather than on one kind.
     */
    private suspend fun aRoom(
        label: String,
        name: String?,
        kind: ConversationKind = ConversationKind.GROUP,
    ): ExternalId {
        val externalId = StoreRig.externalId(label)
        conversations.upsert(Conversation(externalId = externalId, kind = kind, name = name))
        return externalId
    }

    /**
     * The one-to-one conversation with the person [record] names.
     *
     * Keyed by that person's own record identifier, which is what a source does for a direct chat
     * and the whole of what distinguishes one from a room somebody writes in.
     */
    private suspend fun aDirectChat(record: ExternalId): ExternalId {
        conversations.upsert(
            Conversation(externalId = record, kind = ConversationKind.DIRECT, name = "With Them"),
        )
        return record
    }

    private suspend fun aPerson(label: String, isSelf: Boolean? = null): ExternalId {
        val externalId = StoreRig.externalId(label)
        persons.upsert(Person(externalId = externalId, displayName = label, isSelf = isSelf))
        return externalId
    }

    private suspend fun aClient(conversation: ExternalId): EntityId {
        val id = EntityId.mint()
        clients.upsert(listOf(Client(id = id, conversation = conversation, name = "A Client")))
        return id
    }

    private suspend fun aContact(record: ExternalId, displayName: String? = "A Person"): EntityId {
        val id = EntityId.mint()
        contacts.upsert(listOf(Contact(id = id, record = record, displayName = displayName)))
        return id
    }

    private suspend fun write(conversation: ExternalId, written: List<Message>) =
        messages.upsert(conversation, written)

    /** Records that [merged] is the same human as [into], which is what the reads must resolve. */
    private suspend fun link(merged: EntityId, into: EntityId) =
        contacts.merge(listOf(MergeLink(merged = merged, canonical = into, score = 1.0, signals = emptyList())))

    private companion object {

        val EARLIEST: Instant = Instant.parse("2024-01-01T09:00:00Z")
        val LATEST: Instant = Instant.parse("2026-10-01T09:00:00Z")
        val SOON: Instant = Instant.parse("2026-10-20T09:00:00Z")

        /** The contact every fixture commitment obliges, which is never the one that stated it. */
        val OWED: EntityId = EntityId.mint()

        /** A horizon that holds [SOON] and excludes [EARLIEST], anchored on the fixtures' now. */
        fun aroundNow(): Horizon = Horizon(
            from = Instant.parse("2026-09-01T00:00:00Z"),
            until = Instant.parse("2026-11-01T00:00:00Z"),
        )

        fun message(
            conversation: ExternalId,
            text: String,
            author: ExternalId? = null,
            at: Instant = LATEST,
        ): Message = Message(
            externalId = StoreRig.externalId("${conversation.nativeId}-msg"),
            sourceTime = at,
            author = author,
            text = text,
        )

        fun commitment(
            claim: String,
            statedBy: EntityId,
            at: Instant = LATEST,
            /**
             * Who owes it, which is somebody other than the author by default.
             *
             * Non-null in every fixture so that a read dropping the column — projecting a null
             * under its name rather than omitting it, which would throw — fails a case instead of
             * passing one. The two are different contacts because that is the shape the column
             * exists for.
             */
            obligedParty: EntityId? = OWED,
        ): Commitment = Commitment(
            id = EntityId.mint(),
            claim = claim,
            conversation = StoreRig.externalId("stated-somewhere"),
            evidence = setOf(evidence(at)),
            hedged = false,
            statedBy = statedBy,
            obligedParty = obligedParty,
        )

        fun event(
            claim: String,
            statedBy: EntityId,
            mentionedAt: Instant = LATEST,
            occursAt: Instant? = SOON,
        ): Event = Event(
            id = EntityId.mint(),
            claim = claim,
            conversation = StoreRig.externalId("mentioned-somewhere"),
            evidence = setOf(evidence(mentionedAt)),
            hedged = false,
            occursAt = occursAt,
            statedBy = statedBy,
        )

        /**
         * A commitment stated in [conversation], evidenced by [message].
         *
         * The conversation-scoped read narrows on the claim row's own conversation, so a fixture
         * naming the wrong one would pass the contact-scoped tests and find nothing here. The span
         * is the message's because a citation has to name something the corpus holds.
         */
        fun commitmentEvidencedBy(conversation: ExternalId, message: Message, claim: String): Commitment =
            Commitment(
                id = EntityId.mint(),
                claim = claim,
                conversation = conversation,
                evidence = setOf(
                    Evidence(
                        span = SpanId(kind = RecordKind.MESSAGE, record = message.externalId),
                        sourceTime = message.sourceTime,
                    ),
                ),
                hedged = false,
            )

        /** An event mentioned in [conversation], evidenced by [message], for the same reason. */
        fun eventEvidencedBy(
            conversation: ExternalId,
            message: Message,
            claim: String,
            // Nullable, because the conversation-scoped read has an undated narrowing of its own and
            // a fixture that could not express one would leave it untested.
            occursAt: Instant?,
        ): Event = Event(
            id = EntityId.mint(),
            claim = claim,
            conversation = conversation,
            evidence = setOf(
                Evidence(
                    span = SpanId(kind = RecordKind.MESSAGE, record = message.externalId),
                    sourceTime = message.sourceTime,
                ),
            ),
            hedged = false,
            occursAt = occursAt,
        )

        fun evidence(at: Instant): Evidence = Evidence(
            span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId("evidence")),
            sourceTime = at,
        )

        /**
         * Evidence pointing at a named span, for the cases that need two distinct pieces.
         *
         * A piece of evidence is keyed by where it points, so corroboration can only be set up with
         * spans that differ — the one-argument form above shares a span and collapses to one piece.
         */
        fun evidence(label: String, at: Instant): Evidence = Evidence(
            span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId(label)),
            sourceTime = at,
        )

        fun anAttribute(
            subject: EntityId,
            predicate: String,
            value: String,
            from: Instant,
            until: Instant? = null,
        ): Statement = Statement(
            subject = subject,
            predicate = Predicate.of(predicate),
            value = value,
            validity = Validity(from = from, until = until),
            origin = Origin.INFERRED,
            evidence = setOf(evidence(value, from)),
        )

        fun candidate(referent: Referent): Candidate =
            Candidate(referent = referent, name = "a subject", score = 1.0)

        fun subjectOf(referent: Referent): Subject = Subject(focus = candidate(referent))
    }
}
