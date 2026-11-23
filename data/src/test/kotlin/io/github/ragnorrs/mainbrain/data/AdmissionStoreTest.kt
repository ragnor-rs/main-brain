package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.entity.Admission
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Standing
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the admission pass decides, and what it withholds, against a real Postgres.
 *
 * **Not a unit test, because every claim here is held by the schema rather than by Kotlin.** That a
 * verdict replaces rather than accumulates is an `ON CONFLICT` on a primary key; that an unjudged
 * conversation is still a client is a `LEFT JOIN` in a view; and that a rejected client disappears
 * from subject resolution is that view reaching four statements in three adapters. A fake would
 * assert that the fake does these.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case mints its own records
 * and scopes what it reads back to them.
 */
class AdmissionStoreTest {

    private val admissions = JdbcAdmissionRepository(StoreRig.database)
    private val clients = JdbcClientRepository(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val search = JdbcSubjectSearch(StoreRig.database)
    private val retrieval = JdbcRetrieval(StoreRig.database)

    @Test
    fun `a verdict round-trips with the conversation it was reached about`(): Unit = runBlocking {
        val room = StoreRig.externalId("judged")
        val verdict = Admission(room, Standing.THEMATIC_TRACK, "admission:v1")

        admissions.record(listOf(verdict))

        assertEquals(listOf(verdict), admissions.of(listOf(room)))
    }

    @Test
    fun `a conversation nothing has judged carries no verdict`(): Unit = runBlocking {
        // The whole of what *absent means admitted* rests on: a read that invented a row here
        // would make the pass's absence indistinguishable from a verdict of admitted.
        val room = StoreRig.externalId("unjudged")

        assertEquals(emptyList<Admission>(), admissions.of(listOf(room)))
    }

    @Test
    fun `a second judgement replaces the first rather than standing beside it`(): Unit = runBlocking {
        // One verdict per conversation. Two live ones would leave the view picking between them,
        // which is a decision SQL has no business taking.
        val room = StoreRig.externalId("rejudged")
        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:v1")))

        admissions.record(listOf(Admission(room, Standing.BODY_OF_WORK, "admission:v2")))

        assertEquals(
            listOf(Admission(room, Standing.BODY_OF_WORK, "admission:v2")),
            admissions.of(listOf(room)),
        )
    }

    @Test
    fun `a verdict is written against no client row, and leaves one that exists alone`(): Unit = runBlocking {
        // **Nothing the pass rejects is deleted.** The client row is a reading of a name and
        // nothing about what the name means changes it — which is also what lets the pass judge a
        // conversation identity has not reached yet.
        val room = aConversation("kept", "A Rejected Label")
        val client = Client(EntityId.mint(), room, "A Rejected Label")
        clients.upsert(listOf(client))

        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:v1")))

        assertEquals(
            listOf(client),
            clients.of(listOf(room)),
            "The pass's verdict reached the client row. What it decides is filtered on read, and " +
                "the label it was decided about has to survive being rejected.",
        )
    }

    @Test
    fun `a rejected client is not offered as a subject`(): Unit = runBlocking {
        // The defect this card is about, end to end: a thematic track carries a label exactly as a
        // client's thread does, so before the pass runs it resolves as a body of work somebody can
        // ask about.
        val name = unique("Referrals")
        val room = aConversation("track", null)
        clients.upsert(listOf(Client(EntityId.mint(), room, name)))
        assertTrue(search.matching(name).isNotEmpty(), "the fixture does not reproduce the defect")

        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:v1")))

        assertEquals(
            emptyList<Candidate>(),
            search.matching(name),
            "A conversation judged to be a thematic track is still offered as something to ask about.",
        )
    }

    @Test
    fun `a rejected track is still offered as the conversation it is`(): Unit = runBlocking {
        // **The other half of what the verdict does, and the half easiest to take too far.** What
        // it withholds is the client — the body of work somebody's material is reported as
        // belonging to — and not the thread. A named track is still a thread a reader can ask
        // about, and making it unaskable is a second decision nothing here has taken. Measured on
        // real material: judged a track, the name resolves to one subject rather than none.
        val name = unique("Hiring")
        val room = aConversation("still-a-thread", name)
        clients.upsert(listOf(Client(EntityId.mint(), room, name)))

        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:v1")))

        val offered = search.matching(name)
        assertEquals(
            listOf(Referent.OfConversation(room)),
            offered.map { it.referent },
            "A rejected track is unreachable, where what the verdict withholds is only the client.",
        )
    }

    @Test
    fun `a client judged to be a body of work is offered exactly as before`(): Unit = runBlocking {
        val name = unique("A Judged Client")
        val room = aConversation("admitted", null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(client, room, name)))

        admissions.record(listOf(Admission(room, Standing.BODY_OF_WORK, "admission:v1")))

        assertEquals(Referent.OfClient(client), search.matching(name).single().referent)
    }

    @Test
    fun `a stale verdict goes on being honoured until it is replaced`(): Unit = runBlocking {
        // **The direction that matters.** A reworded instruction leaves every verdict naming the
        // old wording, and a filter that preferred the current version would re-admit every
        // rejected track the moment a prompt moved — so answers would be about material a judgement
        // had already excluded, for as long as the pass took to catch up.
        val name = unique("A Stale Track")
        val room = aConversation("stale", null)
        clients.upsert(listOf(Client(EntityId.mint(), room, name)))

        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:an-older-wording")))

        assertEquals(emptyList<Candidate>(), search.matching(name))
    }

    @Test
    fun `a rejected client cannot be resolved to the conversation behind it`(): Unit = runBlocking {
        // The read the answer path makes once a subject is settled. Resolution cannot produce a
        // rejected client any more, so this is the second lock on the same door — and the one that
        // decides what an identifier held from before a verdict was reached resolves to.
        val room = aConversation("unreachable", "A Rejected Label")
        val client = EntityId.mint()
        clients.upsert(listOf(Client(client, room, unique("A Rejected Label"))))
        val asked = Subject(
            focus = Candidate(referent = Referent.OfClient(client), name = "a rejected label", score = 1.0),
        )
        assertEquals(listOf(room), retrieval.conversationsOf(asked, threads = 4))

        admissions.record(listOf(Admission(room, Standing.THEMATIC_TRACK, "admission:v1")))

        assertEquals(emptyList<ExternalId>(), retrieval.conversationsOf(asked, threads = 4))
    }

    @Test
    fun `the newest messages of a conversation are read, oldest first and no more than asked`(): Unit = runBlocking {
        // What the judgement is made on. The bound is in the statement rather than at the caller,
        // because a conversation of a real archive runs to tens of thousands of messages and the
        // sample is the size of a screen.
        val room = aConversation("sampled", "A Client")
        val written = (1..5).map { nth ->
            Message(
                externalId = StoreRig.externalId("line-$nth"),
                sourceTime = WRITTEN.plusSeconds(nth.toLong()),
                text = "line $nth",
            )
        }
        messages.upsert(room, written)

        val sample = messages.latestIn(room, limit = 3)

        assertEquals(listOf("line 3", "line 4", "line 5"), sample.map { it.text })
    }

    @Test
    fun `a sample asked for more than there is returns everything there is`(): Unit = runBlocking {
        val room = aConversation("short", "A Client")
        messages.upsert(
            room,
            listOf(Message(StoreRig.externalId("only-line"), WRITTEN, text = "the only line")),
        )

        assertEquals(1, messages.latestIn(room, limit = 40).size)
    }

    @Test
    fun `a conversation with nothing in it samples nothing rather than failing`(): Unit = runBlocking {
        val room = aConversation("empty", "A Client")

        assertEquals(emptyList<Message>(), messages.latestIn(room, limit = 40))
    }

    @Test
    fun `a sampled message keeps the claims it carries`(): Unit = runBlocking {
        // The claim join multiplies rows, so a bound applied to the joined result would cut a
        // sample off mid-message and hand back a message with some of its identifiers. Nothing
        // downstream of a sample reads them, which is exactly why a truncation here would be
        // invisible until something did.
        val room = aConversation("claimed", "A Client")
        val line = Message(
            externalId = StoreRig.externalId("claimed-line"),
            sourceTime = WRITTEN,
            text = "a line with claims",
            identifiers = listOfNotNull(Identifier.of(IdentifierType.CONTENT_HASH, "a-content-hash-of-the-line")),
        )
        messages.upsert(room, listOf(line))

        val sampled = messages.latestIn(room, limit = 1).single()

        assertEquals(line.identifiers, sampled.identifiers)
    }

    @Test
    fun `the view carries every column the client table does`(): Unit = runBlocking {
        // A view expands a star once, when it is created, so a column added to `client` later would
        // be invisible through the view with nothing failing to say so. Asserted against the
        // catalogue rather than by reading a row, because what has to hold is the shape and not
        // whichever columns a mapper happens to want today.
        val table = columnsOf("client")
        val view = columnsOf("admitted_client")

        assertEquals(table, view, "The view and the table it filters have drifted apart.")
    }

    @Test
    fun `nothing asked about is nothing read`(): Unit = runBlocking {
        assertEquals(emptyList<Admission>(), admissions.of(emptyList()))
    }

    @Test
    fun `an unknown standing cannot be stored`(): Unit = runBlocking {
        // The read filter has a branch for each verdict the vocabulary admits, and an unrecognised
        // value would fall through it as admitted — which is the direction that silently does
        // nothing. The constraint is what makes that unreachable rather than merely unlikely.
        val room = StoreRig.externalId("bad-standing")
        val refused = runCatching {
            StoreRig.database.transaction { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO client_admission (record_source, record_native_id, standing, version) " +
                            "VALUES (?, ?, 'PROBABLY', 'admission:v1')",
                    )
                    .use { statement ->
                        statement.bind { externalId(room) }
                        statement.executeUpdate()
                    }
            }
        }

        assertTrue(refused.isFailure, "the store accepted a verdict nothing can read")
        assertFalse(
            admissions.of(listOf(room)).isNotEmpty(),
            "a verdict outside the vocabulary was stored",
        )
    }

    /** The columns of a relation, in order, as the catalogue holds them. */
    private suspend fun columnsOf(relation: String): List<Pair<String, String>> =
        StoreRig.database.query { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT column_name, data_type
                    FROM information_schema.columns
                    WHERE table_name = ?
                    ORDER BY ordinal_position
                    """.trimIndent(),
                )
                .use { statement ->
                    statement.bind { string(relation) }
                    statement.executeQuery().use { rows ->
                        rows.collect { it.getString("column_name") to it.getString("data_type") }
                    }
                }
        }

    private suspend fun aConversation(label: String, name: String?): ExternalId {
        val externalId = StoreRig.externalId(label)
        conversations.upsert(Conversation(externalId = externalId, kind = ConversationKind.GROUP, name = name))
        return externalId
    }

    private companion object {

        val WRITTEN: Instant = Instant.parse("2026-10-01T09:00:00Z")

        private var minted = 0

        /**
         * A name no other case holds.
         *
         * The rig is shared and nothing is emptied, so a name reused across cases would be matched
         * by another case's rows — and a case asserting that a name resolves to nothing would then
         * fail for a reason that has nothing to do with the verdict it wrote.
         */
        fun unique(label: String): String = "$label ${++minted}"
    }
}
