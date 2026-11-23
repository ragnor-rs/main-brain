package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.Document
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageInAnotherConversation
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.UnknownConversation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The corpus store against a real Postgres.
 *
 * Everything here is an acceptance check of the store rather than a unit test of a function:
 * what is being asserted is that a record survives a round trip unchanged, that re-delivery
 * changes nothing it should not, and that the three time axes stay apart. None of that is
 * provable against a mock, which would only demonstrate that the mock behaves like the mock.
 *
 * Two assertions come from `kotlin.test` rather than JUnit, for what JUnit's cannot do here:
 * `assertNotNull` hands back the non-null value instead of returning nothing, and
 * `assertFailsWith` is inline, so the suspending call under assertion is reachable without a
 * nested `runBlocking`.
 */
class CorpusStoreTest {

    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val documents = JdbcDocumentRepository(StoreRig.database)

    @Test
    fun `a conversation round-trips unchanged, parent and all`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("conversation")
        val conversation = Conversation(
            externalId = externalId,
            kind = ConversationKind.TOPIC,
            name = "A thematic track",
            parent = StoreRig.externalId("supergroup"),
            identifiers = listOfNotNull(Identifier.of(IdentifierType.HANDLE, "@ATrack")),
            sourceTime = Instant.parse("2024-03-04T05:06:07Z"),
        )

        conversations.upsert(conversation)

        assertEquals(conversation, conversations.find(externalId))
    }

    @Test
    fun `an unresolvable parent survives the round trip`(): Unit = runBlocking {
        // The supergroup is never written. A topic whose container has not been captured yet is
        // an ordinary state, and the reference has to come back as the source gave it.
        val externalId = StoreRig.externalId("conversation")
        val parent = StoreRig.externalId("uncaptured-supergroup")
        conversations.upsert(
            Conversation(externalId = externalId, kind = ConversationKind.TOPIC, name = null, parent = parent),
        )

        assertEquals(parent, conversations.find(externalId)?.parent)
    }

    @Test
    fun `a person's claims are stored typed and normalised`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        val claims = listOfNotNull(
            Identifier.of(IdentifierType.PHONE, "+995 (555) 12-34-56"),
            Identifier.of(IdentifierType.EMAIL, "Someone@Example.COM"),
            Identifier.of(IdentifierType.HANDLE, "@SomeOne"),
        )
        persons.upsert(Person(externalId = externalId, displayName = "A participant", identifiers = claims))

        val found = assertNotNull(persons.find(externalId))
        assertEquals(
            // Read back ordered by type then value, which is the order these three sort into.
            listOf(
                Identifier(IdentifierType.EMAIL, "someone@example.com"),
                Identifier(IdentifierType.HANDLE, "someone"),
                Identifier(IdentifierType.PHONE, "+995555123456"),
            ),
            found.identifiers,
        )
    }

    /**
     * The three states of the mark survive a round trip, and *unknown* is one of them.
     *
     * `false` and `null` read back the same way from a column read with `getBoolean`, which is
     * exactly the mistake this asserts against: a corpus that could not be asked and one that was
     * asked and said nobody must stay distinguishable in the record.
     */
    @Test
    fun `whether a person is the account read through round-trips, unset included`(): Unit = runBlocking {
        val own = StoreRig.externalId("person")
        val counterparty = StoreRig.externalId("person")
        val unasked = StoreRig.externalId("person")
        persons.upsert(Person(externalId = own, displayName = "The account", isSelf = true))
        persons.upsert(Person(externalId = counterparty, displayName = "A counterparty", isSelf = false))
        persons.upsert(Person(externalId = unasked, displayName = "Nobody asked"))

        assertEquals(true, assertNotNull(persons.find(own)).isSelf)
        assertEquals(false, assertNotNull(persons.find(counterparty)).isSelf)
        assertNull(assertNotNull(persons.find(unasked)).isSelf)
    }

    /**
     * A pass that could not ask does not erase what an earlier one established.
     *
     * The adapter offers the mark on every profile it sends, so a session that will not say who it
     * is would otherwise walk the whole corpus back to *unknown* — and a question about the reader
     * would start competing with everyone again, silently.
     */
    @Test
    fun `an unset mark leaves an established one standing`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        persons.upsert(Person(externalId = externalId, displayName = "The account", isSelf = true))

        persons.upsert(Person(externalId = externalId, displayName = "The account"))

        assertEquals(true, assertNotNull(persons.find(externalId)).isSelf)
    }

    /** A pass that *can* ask still corrects it, which is what keeps the coalesce from being a latch. */
    @Test
    fun `a known mark overwrites what was established before it`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        persons.upsert(Person(externalId = externalId, displayName = "The account", isSelf = true))

        persons.upsert(Person(externalId = externalId, displayName = "The account", isSelf = false))

        assertEquals(false, assertNotNull(persons.find(externalId)).isSelf)
    }

    /**
     * The mark is not a claim, and nothing about the record advertises it.
     *
     * A claim shared by two records is scored as evidence that they are one human, weighted by how
     * few records carry the value — so a mark that reached the `identifier` table would be the
     * rarest signal there is, shared by exactly the accounts read through and merging them for a
     * reason that is not evidence about who they are.
     */
    @Test
    fun `marking a person adds nothing to its claims`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        val claims = listOfNotNull(Identifier.of(IdentifierType.HANDLE, "@TheAccount"))
        persons.upsert(Person(externalId = externalId, identifiers = claims, isSelf = true))

        assertEquals(
            listOf(Identifier(IdentifierType.HANDLE, "theaccount")),
            assertNotNull(persons.find(externalId)).identifiers,
        )
        assertEquals(
            listOf(Identifier(IdentifierType.HANDLE, "theaccount")),
            persons.identifiersOf(externalId).map { it.identifier },
        )
    }

    /**
     * **Two accounts both read through offer the matcher nothing to score them on.**
     *
     * Asserted at the read the matcher actually makes, which is the only place it could leak: the
     * scorer compares the claims a record advertises, weighted by how few records carry each value.
     * A mark that reached that read as a claim would be shared by exactly the reader's own accounts
     * and by nobody else — the strongest signal the scorer has, merging them at a weight nobody
     * chose and for a reason that is not evidence about who they are.
     */
    @Test
    fun `two accounts both read through advertise nothing the matcher could match on`(): Unit = runBlocking {
        val one = StoreRig.externalId("person")
        val another = StoreRig.externalId("person")
        persons.upsert(Person(externalId = one, displayName = "One account", isSelf = true))
        persons.upsert(Person(externalId = another, displayName = "Another account", isSelf = true))

        val observed = persons.persons(listOf(one, another))

        assertEquals(2, observed.size)
        assertTrue(observed.all { it.claims.isEmpty() }) {
            "A record read for matching carries the mark as a claim, so two accounts of the reader " +
                "would be scored as one human for being the reader."
        }
    }

    /**
     * The mark arriving does not make a record look freshly modified.
     *
     * Nothing is derived from it — the answer path joins it live — so moving content time would buy
     * a re-derivation of identity for every person in the corpus that reads nothing new.
     */
    @Test
    fun `the mark arriving does not move content time`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        persons.upsert(Person(externalId = externalId, displayName = "The account"))
        val before = assertNotNull(persons.timesOf(externalId))

        persons.upsert(Person(externalId = externalId, displayName = "The account", isSelf = true))

        val after = assertNotNull(persons.timesOf(externalId))
        assertEquals(before.contentTime, after.contentTime)
        assertEquals(true, assertNotNull(persons.find(externalId)).isSelf)
    }

    @Test
    fun `a document round-trips with a reference to a message that was never captured`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("document")
        val document = Document(
            externalId = externalId,
            name = "A file",
            mediaType = "application/pdf",
            text = "Extracted nothing yet",
            messageRef = StoreRig.externalId("uncaptured-message"),
            identifiers = listOfNotNull(Identifier.of(IdentifierType.CONTENT_HASH, "ABCDEF0123")),
            sourceTime = Instant.parse("2025-06-07T08:09:10Z"),
        )

        documents.upsert(document)

        assertEquals(
            document.copy(identifiers = listOf(Identifier(IdentifierType.CONTENT_HASH, "abcdef0123"))),
            documents.find(externalId),
        )
    }

    @Test
    fun `a message cannot be stored without its conversation`(): Unit = runBlocking {
        val conversation = StoreRig.externalId("never-written")
        val message = Message(externalId = StoreRig.externalId("message"), sourceTime = Instant.now())

        val refused = assertFailsWith<UnknownConversation> { messages.upsert(conversation, listOf(message)) }

        assertEquals(conversation, refused.conversation)
        // And nothing was written on the way to refusing.
        assertNull(messages.timesOf(message.externalId))
    }

    @Test
    fun `a message is never moved between conversations`(): Unit = runBlocking {
        // What an adapter handing over a bare per-chat number would produce: the same native
        // identifier arriving under a second conversation. Accepting it would move the stored
        // message, fusing two unrelated ones into a corpus that reads as ordinary data.
        val first = writtenConversation()
        val second = writtenConversation()
        val message = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2026-03-03T00:00:00Z"),
            text = "Said in the first conversation",
        )
        messages.upsert(first, listOf(message))

        val refused = assertFailsWith<MessageInAnotherConversation> {
            messages.upsert(second, listOf(message))
        }

        assertEquals(message.externalId, refused.messageId)
        assertEquals(listOf(message), messages.messagesIn(first)) { "The stored message moved." }
        assertTrue(messages.messagesIn(second).isEmpty()) { "The message was written into the second conversation." }
    }

    @Test
    fun `re-delivering a message into the same conversation is still an upsert`(): Unit = runBlocking {
        // The guard refuses a different conversation, not a repeat of the same one.
        val conversation = writtenConversation()
        val message = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2026-04-04T00:00:00Z"),
            text = "Said once",
        )
        messages.upsert(conversation, listOf(message))
        val first = assertNotNull(messages.timesOf(message.externalId))

        messages.upsert(conversation, listOf(message))
        val second = assertNotNull(messages.timesOf(message.externalId))

        assertEquals(listOf(message), messages.messagesIn(conversation))
        assertEquals(first.contentTime, second.contentTime)
        assertTrue(second.lastSeenAt > first.lastSeenAt)
    }

    /**
     * What a withdrawal at the source amounts to: its own column, and nothing else moves.
     *
     * **Last-seen must not move, and that is the assertion worth having.** It means a pass still
     * found the record, and a pass told the record is gone did not — so folding the two together
     * would make the one message explicitly withdrawn look like the freshest in the corpus, and
     * leave the weak signal of *unseen for several passes* firing for everything except the case
     * where removal is a fact.
     *
     * The message is not re-sent here, which is the other point. A caller re-sending the stored
     * body to reach the same place would have to read the corpus to know the body, and would move
     * content time the moment its copy ever disagreed.
     */
    @Test
    fun `a withdrawal is recorded on its own column and the record stays whole`(): Unit = runBlocking {
        val conversation = writtenConversation()
        val message = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2026-04-05T00:00:00Z"),
            text = "Said once, then withdrawn",
        )
        messages.upsert(conversation, listOf(message))
        val before = assertNotNull(messages.timesOf(message.externalId))

        messages.markWithdrawn(listOf(message.externalId))

        val after = assertNotNull(messages.timesOf(message.externalId))
        assertNotNull(withdrawnAt(message.externalId)) { "The withdrawal was not recorded." }
        assertEquals(before.lastSeenAt, after.lastSeenAt) { "A withdrawal moved last-seen." }
        assertEquals(before.contentTime, after.contentTime) { "Nothing was restated; content time moved." }
        assertEquals(before.sourceTime, after.sourceTime)
        assertEquals(before.addedAt, after.addedAt)
        assertEquals(listOf(message), messages.messagesIn(conversation)) { "The message did not survive." }
    }

    @Test
    fun `a withdrawal for a message the store does not hold is not an error`(): Unit = runBlocking {
        // A source can report a withdrawal for material never captured, and an incomplete
        // capture is indistinguishable from it — so this is ordinary rather than exceptional.
        messages.markWithdrawn(listOf(StoreRig.externalId("never-captured")))
    }

    @Test
    fun `a withdrawal touches every message named and no others`(): Unit = runBlocking {
        val conversation = writtenConversation()
        val named = Message(StoreRig.externalId("named"), Instant.parse("2026-04-06T00:00:00Z"))
        val bystander = Message(StoreRig.externalId("bystander"), Instant.parse("2026-04-06T00:00:00Z"))
        messages.upsert(conversation, listOf(named, bystander))

        messages.markWithdrawn(listOf(named.externalId, named.externalId))

        assertNotNull(withdrawnAt(named.externalId))
        assertNull(withdrawnAt(bystander.externalId)) { "A message nobody named was withdrawn." }
    }

    /**
     * The first statement is the one kept, which is what makes a re-delivered batch harmless.
     *
     * Every other write here is an upsert and says the same thing twice without consequence. A
     * withdrawal re-stamped on each repeat would be the one exception — a clock rather than a
     * fact, and one that would drift every time the scheduler retried a batch.
     */
    @Test
    fun `a repeated withdrawal keeps the moment the source first said so`(): Unit = runBlocking {
        val conversation = writtenConversation()
        val message = Message(StoreRig.externalId("said-twice"), Instant.parse("2026-04-07T00:00:00Z"))
        messages.upsert(conversation, listOf(message))
        messages.markWithdrawn(listOf(message.externalId))
        val first = assertNotNull(withdrawnAt(message.externalId))

        messages.markWithdrawn(listOf(message.externalId))

        assertEquals(first, withdrawnAt(message.externalId)) { "The withdrawal was re-stamped." }
    }

    /**
     * Read straight out of the column, because no port offers it yet.
     *
     * The reader that will want it is retrieval deciding whether quoting a message is appropriate
     * for the request in front of it, and that is not written — so a port method today would be
     * surface with no caller, shaped by a guess at what the caller will ask for.
     */
    private suspend fun withdrawnAt(externalId: ExternalId): Instant? = StoreRig.database.query { connection ->
        connection.prepareStatement(
            "SELECT withdrawn_at FROM message WHERE source = ? AND native_id = ?",
        ).use { statement ->
            statement.setString(1, externalId.source.name)
            statement.setString(2, externalId.nativeId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getTimestamp("withdrawn_at")?.toInstant() else null
            }
        }
    }

    @Test
    fun `messages read back in source order`(): Unit = runBlocking {
        val conversation = writtenConversation()
        val later = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2026-02-02T00:00:00Z"),
            text = "The second thing said",
        )
        val earlier = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2026-01-01T00:00:00Z"),
            author = StoreRig.externalId("person"),
            text = "The first thing said",
            identifiers = listOfNotNull(Identifier.of(IdentifierType.CONTENT_HASH, "0f0f")),
        )

        // Written out of order deliberately: the read orders by source time, not by insertion.
        messages.upsert(conversation, listOf(later, earlier))

        assertEquals(listOf(earlier, later), messages.messagesIn(conversation))
    }

    @Test
    fun `re-delivering an unchanged record advances last seen and leaves content time alone`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        val person = Person(externalId = externalId, displayName = "A participant")
        persons.upsert(person)
        val first = assertNotNull(persons.timesOf(externalId))

        persons.upsert(person)
        val second = assertNotNull(persons.timesOf(externalId))

        assertEquals(first.addedAt, second.addedAt) { "A record is added once." }
        assertEquals(first.contentTime, second.contentTime) {
            "Content time moved on a re-read that changed nothing. A full resync would make the " +
                "whole corpus look freshly modified, and every recency filter built on it wrong."
        }
        assertTrue(second.lastSeenAt > first.lastSeenAt) {
            "Last seen did not advance. It is the only honest weak form of removal there is."
        }
    }

    @Test
    fun `changed content moves content time and nothing else`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        persons.upsert(Person(externalId = externalId, displayName = "A participant"))
        val first = assertNotNull(persons.timesOf(externalId))

        persons.upsert(Person(externalId = externalId, displayName = "A participant, under a new label"))
        val second = assertNotNull(persons.timesOf(externalId))

        assertEquals(first.addedAt, second.addedAt)
        assertTrue(second.contentTime > first.contentTime) { "The display name changed; content time did not." }
    }

    @Test
    fun `source time and observation time are kept apart`(): Unit = runBlocking {
        // The backfill case: a five-year-old message captured today. Filtering on when it was
        // captured would make old history look current, which is what the three axes prevent.
        val conversation = writtenConversation()
        val message = Message(
            externalId = StoreRig.externalId("message"),
            sourceTime = Instant.parse("2021-05-06T07:08:09Z"),
            text = "Said years ago",
        )
        messages.upsert(conversation, listOf(message))

        val times = assertNotNull(messages.timesOf(message.externalId))

        assertEquals(message.sourceTime, times.sourceTime)
        assertTrue(times.addedAt > message.sourceTime) {
            "Observation time is not later than source time, so the two are not being stored " +
                "as different things."
        }
        assertEquals(times.addedAt, times.lastSeenAt) { "First capture is also the latest sighting." }
    }

    @Test
    fun `a claim keeps when it was added and advances when it was last seen`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        val claim = assertNotNull(Identifier.of(IdentifierType.PHONE, "+995555000111"))
        persons.upsert(Person(externalId = externalId, identifiers = listOf(claim)))
        val first = persons.identifiersOf(externalId).single()

        persons.upsert(Person(externalId = externalId, identifiers = listOf(claim)))
        val second = persons.identifiersOf(externalId).single()

        assertEquals(claim, second.identifier)
        assertEquals(first.addedAt, second.addedAt)
        assertTrue(second.lastSeenAt > first.lastSeenAt)
    }

    @Test
    fun `a claim the source stopped advertising stays, with its last seen standing still`(): Unit = runBlocking {
        val externalId = StoreRig.externalId("person")
        val claim = assertNotNull(Identifier.of(IdentifierType.PHONE, "+995555000222"))
        persons.upsert(Person(externalId = externalId, displayName = "A participant", identifiers = listOf(claim)))
        val whenSeen = persons.identifiersOf(externalId).single().lastSeenAt

        // The same record, observed again without the number.
        persons.upsert(Person(externalId = externalId, displayName = "A participant"))

        val kept = persons.identifiersOf(externalId).single()
        assertEquals(claim, kept.identifier) { "The claim was removed. Nothing here removes anything." }
        assertEquals(whenSeen, kept.lastSeenAt) {
            "The claim's last seen advanced on a pass that did not carry it, which is the one " +
                "thing able to tell a current number from one abandoned years ago."
        }
        assertTrue(assertNotNull(persons.timesOf(externalId)).lastSeenAt > whenSeen) {
            "The record's own last seen should have advanced even though the claim's did not."
        }
    }

    @Test
    fun `two person records may claim one phone number`(): Unit = runBlocking {
        // The evidence reconciliation scores on. A uniqueness constraint on the value would turn
        // the strongest available signal into a write error.
        val shared = assertNotNull(Identifier.of(IdentifierType.PHONE, "+995555000333"))
        val one = StoreRig.externalId("person")
        val other = StoreRig.externalId("person")

        persons.upsert(Person(externalId = one, identifiers = listOf(shared)))
        persons.upsert(Person(externalId = other, identifiers = listOf(shared)))

        assertEquals(shared, persons.identifiersOf(one).single().identifier)
        assertEquals(shared, persons.identifiersOf(other).single().identifier)
    }

    @Test
    fun `an unknown record has no times and no claims`(): Unit = runBlocking {
        val missing = StoreRig.externalId("never-written")

        assertNull(persons.find(missing))
        assertNull(persons.timesOf(missing))
        assertTrue(persons.identifiersOf(missing).isEmpty())
    }

    @Test
    fun `messages are readable by their own identifiers, across conversations`(): Unit = runBlocking {
        // The read a derived pass makes: it holds a list of records to revisit and does not
        // know or care which conversations they fall in.
        val first = writtenConversation()
        val second = writtenConversation()
        val here = StoreRig.externalId("here")
        val there = StoreRig.externalId("there")
        val at = Instant.parse("2024-11-01T00:00:00Z")
        messages.upsert(first, listOf(Message(externalId = here, sourceTime = at, text = "in the first")))
        messages.upsert(
            second,
            listOf(Message(externalId = there, sourceTime = at.plusSeconds(60), text = "in the second")),
        )

        val found = messages.messages(listOf(there, here))

        assertEquals(listOf("in the first", "in the second"), found.map { it.text }) {
            "Read back by identifier and ordered by source time, not by the order asked for."
        }
    }

    @Test
    fun `an identifier the store does not hold is absent rather than an error`(): Unit = runBlocking {
        // A pass reads a watermark and then the records; between the two, anything may have
        // happened. A missing one is an ordinary outcome.
        val conversation = writtenConversation()
        val known = StoreRig.externalId("known")
        messages.upsert(
            conversation,
            listOf(Message(externalId = known, sourceTime = Instant.parse("2024-11-02T00:00:00Z"), text = "here")),
        )

        val found = messages.messages(listOf(known, StoreRig.externalId("never-written")))

        assertEquals(listOf(known), found.map { it.externalId })
    }

    @Test
    fun `asking for no messages reads nothing`(): Unit = runBlocking {
        assertTrue(messages.messages(emptyList()).isEmpty())
    }

    @Test
    fun `a message names the conversation it is in, in bulk`(): Unit = runBlocking {
        // The other direction of reading a conversation, and the one inference needs: a message
        // carries no conversation of its own, so a pass holding a watermark's worth of
        // identifiers cannot find the stretch of conversation around them without asking.
        val first = writtenConversation()
        val second = writtenConversation()
        val here = StoreRig.externalId("in-first")
        val there = StoreRig.externalId("in-second")
        val at = Instant.parse("2024-12-01T00:00:00Z")
        messages.upsert(first, listOf(Message(externalId = here, sourceTime = at, text = "one")))
        messages.upsert(second, listOf(Message(externalId = there, sourceTime = at, text = "two")))

        val found = messages.conversationsOf(listOf(here, there, StoreRig.externalId("never-written")))

        assertEquals(mapOf(here to first, there to second), found) {
            "Each message maps to its own conversation, and one the store does not hold is " +
                "absent rather than an error: a pass reads a watermark before it reads records."
        }
    }

    @Test
    fun `asking for no conversations reads nothing`(): Unit = runBlocking {
        assertTrue(messages.conversationsOf(emptyList()).isEmpty())
    }

    private suspend fun writtenConversation() = StoreRig.externalId("conversation").also {
        conversations.upsert(Conversation(externalId = it, kind = ConversationKind.DIRECT, name = null))
    }
}
