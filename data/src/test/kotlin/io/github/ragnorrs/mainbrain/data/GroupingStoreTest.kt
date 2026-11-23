package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.GroupingKey
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which body of work a record belongs to, against a real Postgres.
 *
 * **Every leg of this is a join, which is why none of it can be checked against a fake.** Whether a
 * one-to-one really is identified by the person on the other side, whether *exactly one* body of
 * work is counted over the whole of what somebody wrote rather than over the first thread found,
 * and whether an unnamed topic reaches its container are all statements about SQL. Each of them
 * fails by returning a plausible label rather than by failing, and a label that is plausible and
 * wrong is what sections an answer under the wrong heading.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case mints its own records
 * and asks only about them.
 */
class GroupingStoreTest {

    private val grouping = JdbcGrouping(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val clients = JdbcClientRepository(StoreRig.database)

    @Test
    fun `a conversation people share is its own body of work`(): Unit = runBlocking {
        val room = aRoom("Northwind Foods")
        aClient(room, "Northwind Foods")
        val said = write(room)

        assertEquals(GroupingKey.OfBodyOfWork("Northwind Foods"), key(said))
    }

    @Test
    fun `a conversation with no name of its own takes its container's`(): Unit = runBlocking {
        // A forum's traffic hangs off the topics inside it, and an unnamed one is part of the thing
        // that is named. Without this leg the material is simply unplaceable.
        val container = aRoom("Orchard Mills")
        aClient(container, "Orchard Mills")
        val topic = aTopic(inside = container, name = null)
        val said = write(topic)

        assertEquals(GroupingKey.OfBodyOfWork("Orchard Mills"), key(said))
    }

    @Test
    fun `a topic with a name of its own is read as its own body of work`(): Unit = runBlocking {
        // A topic is where the client's own taxonomy lives, so a container that is also a body of
        // work must not swallow it.
        val container = aRoom("A Supergroup")
        aClient(container, "A Supergroup")
        val topic = aTopic(inside = container, name = "A Strand Of It")
        aClient(topic, "A Strand Of It")
        val said = write(topic)

        assertEquals(GroupingKey.OfBodyOfWork("A Strand Of It"), key(said))
    }

    @Test
    fun `a named topic with no body of work of its own does not take its container's`(): Unit = runBlocking {
        // The container leg is for a conversation carrying no label, not for one whose label has
        // simply not been read yet. Without that condition a topic would be labelled with the thing
        // around it until the next sweep and with its own name afterwards, so the same material
        // would change heading on a schedule.
        val container = aRoom("A Supergroup")
        aClient(container, "A Supergroup")
        val said = write(aTopic(inside = container, name = "Named But Unrecognised"))

        assertNull(key(said))
    }

    @Test
    fun `a one-to-one takes the body of work of the person on the other side`(): Unit = runBlocking {
        // The counterpart is the person the chat is keyed by, which is what a platform numbering a
        // two-person chat by the other account makes true.
        val counterpart = aPerson("A Buyer")
        val chat = aDirectChat(with = counterpart)
        val room = aRoom("Kestrel Logistics")
        aClient(room, "Kestrel Logistics")
        write(room, author = counterpart)
        val said = write(chat)

        assertEquals(GroupingKey.OfBodyOfWork("Kestrel Logistics"), key(said))
    }

    @Test
    fun `a counterpart who works across two bodies of work is headed by that person`(): Unit = runBlocking {
        // `HAVING count(DISTINCT ...) = 1` doing its work. Choosing one of the two would assert a
        // hierarchy the corpus does not state, and it would be invisible in the answer.
        val counterpart = aPerson("A Consultant")
        val chat = aDirectChat(with = counterpart)
        listOf("One Client", "Another Client").forEach { label ->
            val room = aRoom(label)
            aClient(room, label)
            write(room, author = counterpart)
        }
        val said = write(chat)

        assertEquals(GroupingKey.OfPerson("A Consultant"), key(said))
    }

    @Test
    fun `a counterpart writing in two strands of one body of work is headed by that work`(): Unit = runBlocking {
        // The count is in bodies of work and not in clients, which is the whole of what the leading
        // name buys: the strands are two clients, so counted as clients this material would be
        // headed by the person on the strength of how their work is filed rather than of anything
        // about it.
        val counterpart = aPerson("Across Two Strands")
        val chat = aDirectChat(with = counterpart)
        listOf("Kestrel Logistics — supply", "Kestrel Logistics — packing").forEach { label ->
            val room = aRoom(label)
            aClient(room, label)
            write(room, author = counterpart)
        }
        val said = write(chat)

        // **A label the corpus carries, and not the leading name the count was taken over.** Which
        // of the group's labels it is does not matter, so it is not what this asserts: `min` settles
        // it and a change of mind there means nothing to a reader. What does matter is that the
        // heading is one of the labels, because the reduced name belongs to no conversation and
        // could not be looked up.
        assertTrue(
            key(said) in setOf(
                GroupingKey.OfBodyOfWork("Kestrel Logistics — supply"),
                GroupingKey.OfBodyOfWork("Kestrel Logistics — packing"),
            ),
            "Headed by ${key(said)}, which is no conversation's label.",
        )
    }

    @Test
    fun `two unrelated bodies of work sharing a leading name are read as one`(): Unit = runBlocking {
        // **The cost of a rule rather than a judgement, pinned rather than left to be discovered.**
        // Two labels built on one leading word are one body of work to this read, so a counterpart
        // writing in both is placed under it instead of under their own name. That is the opposite
        // of the direction this build errs in everywhere else a label is read, and it is the price
        // of counting strands correctly without asking the model what a label means — which is the
        // narrowing pass's job and not this read's.
        val counterpart = aPerson("Across Two Namesakes")
        val chat = aDirectChat(with = counterpart)
        listOf("Kestrel Logistics", "Kestrel Dairies").forEach { label ->
            val room = aRoom(label)
            aClient(room, label)
            write(room, author = counterpart)
        }
        val said = write(chat)

        // The kind is the claim: a body of work rather than the person. Which of the two labels
        // heads it is `min`'s to settle. This is also the one remaining case where the reduction
        // *unit* matters — a two-word rule would keep these apart and answer `OfPerson` — so it
        // should not be weakened past this.
        assertIs<GroupingKey.OfBodyOfWork>(key(said))
    }

    @Test
    fun `a label that reduces to nothing cannot become the heading`(): Unit = runBlocking {
        // `min` passes over a null name but not over a name that *reduces* to nothing, and
        // punctuation sorts first — so unfiltered such a label does not merely compete for the
        // heading, it wins it, and the section naming a body of work is headed by something no
        // reader could look up while the real name is lost. The predicate already ignores it, so
        // the projection has to as well.
        val counterpart = aPerson("Beside An Unnamed Room")
        val chat = aDirectChat(with = counterpart)
        val real = aRoom("Kestrel Logistics")
        aClient(real, "Kestrel Logistics")
        write(real, author = counterpart)
        val unnamed = aRoom("punctuation only")
        aClient(unnamed, " - . ")
        write(unnamed, author = counterpart)
        val said = write(chat)

        assertEquals(GroupingKey.OfBodyOfWork("Kestrel Logistics"), key(said))
    }

    @Test
    fun `a counterpart in one thread is headed by its whole label`(): Unit = runBlocking {
        // The reduction decides the count and never the label, so a body of work kept in a single
        // conversation is headed by what that conversation is called.
        val counterpart = aPerson("Inside One Thread Only")
        val chat = aDirectChat(with = counterpart)
        val room = aRoom("Kestrel Logistics — supply")
        aClient(room, "Kestrel Logistics — supply")
        write(room, author = counterpart)
        val said = write(chat)

        assertEquals(GroupingKey.OfBodyOfWork("Kestrel Logistics — supply"), key(said))
    }

    @Test
    fun `a counterpart writing in an unnamed topic takes the body of work containing it`(): Unit = runBlocking {
        // The container rule applies wherever somebody writes, not only to the record being placed.
        // Without it a counterpart whose threads are all unnamed topics of one recognised forum
        // counts no bodies of work at all and loses the one the material plainly belongs to.
        val counterpart = aPerson("Inside One Forum")
        val chat = aDirectChat(with = counterpart)
        val container = aRoom("A Recognised Forum")
        aClient(container, "A Recognised Forum")
        write(aTopic(inside = container, name = null), author = counterpart)
        val said = write(chat)

        assertEquals(GroupingKey.OfBodyOfWork("A Recognised Forum"), key(said))
    }

    @Test
    fun `writing somewhere with no body of work does not count against a counterpart`(): Unit = runBlocking {
        // `count` passes over a null, which is what makes this true: a thread nobody named is not
        // evidence of working across several bodies of work, so the one that is named still wins.
        val counterpart = aPerson("One Labelled Thread")
        val chat = aDirectChat(with = counterpart)
        val room = aRoom("The Only Body Of Work")
        aClient(room, "The Only Body Of Work")
        write(room, author = counterpart)
        write(aRoom("Nobody Read This One"), author = counterpart)
        val said = write(chat)

        assertEquals(GroupingKey.OfBodyOfWork("The Only Body Of Work"), key(said))
    }

    @Test
    fun `a counterpart in no body of work at all is headed by that person`(): Unit = runBlocking {
        val counterpart = aPerson("Somebody Else")
        val said = write(aDirectChat(with = counterpart))

        assertEquals(GroupingKey.OfPerson("Somebody Else"), key(said))
    }

    @Test
    fun `a one-to-one whose person was never captured takes the name the chat carries`(): Unit = runBlocking {
        // What a two-person chat displays is the counterparty, so the name says who the material is
        // with — which is the only thing left to say it when no person record was captured.
        val chat = StoreRig.externalId("uncaptured")
        conversations.upsert(
            Conversation(externalId = chat, kind = ConversationKind.DIRECT, name = "A Counterparty"),
        )
        val said = write(chat)

        assertEquals(GroupingKey.OfPerson("A Counterparty"), key(said))
    }

    @Test
    fun `a blank name on the person does not stop the chat's own name placing the material`(): Unit = runBlocking {
        // Each leg is guarded on its own. A person row carrying an empty display name would otherwise
        // satisfy the leg above and leave the material unplaced, with a usable name one line below.
        val counterpart = aPerson("")
        val said = write(aDirectChat(with = counterpart))

        assertEquals(GroupingKey.OfPerson("Displayed Name"), key(said))
    }

    @Test
    fun `a named room with no body of work read from it yet places nothing`(): Unit = runBlocking {
        // A name with no client behind it means recognition has not been over the conversation.
        // Answering from the name would assert a body of work this system has not read, and the
        // same label would then appear and disappear depending on when a sweep last ran.
        val said = write(aRoom("Not Recognised Yet"))

        assertNull(key(said))
    }

    @Test
    fun `a room is never headed by a person, whatever its name`(): Unit = runBlocking {
        // The counterpart legs are for a one-to-one and nothing else. A group named after somebody
        // is still a room, and heading its material with that person would file a shared thread
        // under a human.
        val room = aRoom("A Person's Room")
        val said = write(room)

        assertNull(key(said))
    }

    @Test
    fun `a record the corpus does not hold places nothing`(): Unit = runBlocking {
        // Absence is ordinary: material from the open web was never a record here.
        assertNull(key(StoreRig.externalId("never-written")))
    }

    @Test
    fun `every record asked about is answered in one read`(): Unit = runBlocking {
        // One statement rather than one per record: the labels are read on the path where somebody
        // is waiting, and a query per retrieved span would be forty of them.
        val room = aRoom("One Body Of Work")
        aClient(room, "One Body Of Work")
        val counterpart = aPerson("A Second Heading")
        val chat = aDirectChat(with = counterpart)
        val fromRoom = write(room)
        val fromChat = write(chat)

        val read = grouping.of(listOf(fromRoom, fromChat, StoreRig.externalId("absent")))

        assertEquals(
            mapOf(
                fromRoom to GroupingKey.OfBodyOfWork("One Body Of Work"),
                fromChat to GroupingKey.OfPerson("A Second Heading"),
            ),
            read,
        )
    }

    @Test
    fun `two counterparts in one read each take their own body of work`(): Unit = runBlocking {
        // **Two of them, which is what makes the counterpart's own key load-bearing.** The bodies of
        // work behind the counterparts are resolved for the whole request at once and then matched
        // back to the records, so the match has to be on the person rather than on the source they
        // are from — every record in one corpus shares that. With one counterpart in the read there
        // is only ever one row to match and a half-written match looks correct; with two, matching
        // on the source alone hands each record the other one's body of work.
        val first = aPerson("The First Counterpart")
        val second = aPerson("The Second Counterpart")
        val firstChat = aDirectChat(with = first)
        val secondChat = aDirectChat(with = second)
        val firstRoom = aRoom("Kestrel Logistics")
        aClient(firstRoom, "Kestrel Logistics")
        write(firstRoom, author = first)
        val secondRoom = aRoom("Orchard Mills")
        aClient(secondRoom, "Orchard Mills")
        write(secondRoom, author = second)
        val fromFirst = write(firstChat)
        val fromSecond = write(secondChat)

        val read = grouping.of(listOf(fromFirst, fromSecond))

        assertEquals(
            mapOf(
                fromFirst to GroupingKey.OfBodyOfWork("Kestrel Logistics"),
                fromSecond to GroupingKey.OfBodyOfWork("Orchard Mills"),
            ),
            read,
        )
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private suspend fun key(record: ExternalId): GroupingKey? = grouping.of(listOf(record))[record]

    /** A conversation people share, named but not yet read as anything. */
    private suspend fun aRoom(name: String): ExternalId {
        val externalId = StoreRig.externalId("room")
        conversations.upsert(
            Conversation(externalId = externalId, kind = ConversationKind.GROUP, name = name),
        )
        return externalId
    }

    private suspend fun aTopic(inside: ExternalId, name: String?): ExternalId {
        val externalId = StoreRig.externalId("topic")
        conversations.upsert(
            Conversation(
                externalId = externalId,
                kind = ConversationKind.TOPIC,
                name = name,
                parent = inside,
            ),
        )
        return externalId
    }

    /**
     * The one-to-one conversation with the person [with] names.
     *
     * Keyed by that person's own record identifier, which is what a source does for a direct chat
     * and the whole of what lets the counterpart be found at all.
     */
    private suspend fun aDirectChat(with: ExternalId): ExternalId {
        conversations.upsert(
            Conversation(externalId = with, kind = ConversationKind.DIRECT, name = "Displayed Name"),
        )
        return with
    }

    private suspend fun aPerson(displayName: String): ExternalId {
        val externalId = StoreRig.externalId("person")
        persons.upsert(Person(externalId = externalId, displayName = displayName))
        return externalId
    }

    private suspend fun aClient(conversation: ExternalId, name: String) =
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = name)))

    /** One message in [conversation], and the record identifier it is addressed by. */
    private suspend fun write(conversation: ExternalId, author: ExternalId? = null): ExternalId {
        val externalId = StoreRig.externalId("message")
        messages.upsert(
            conversation,
            listOf(
                Message(
                    externalId = externalId,
                    sourceTime = SAID_AT,
                    author = author,
                    text = "something was said",
                ),
            ),
        )
        return externalId
    }

    private companion object {
        val SAID_AT: Instant = Instant.parse("2026-10-01T09:00:00Z")
    }
}
