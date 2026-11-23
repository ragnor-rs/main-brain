package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Collected
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.GroupingKey
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.answer.shape.prepare.PrepareShape
import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The call brief's material, composed through the real store rather than through a double.
 *
 * **Why this is not covered by the shape's own unit tests.** Those hand the stage a fake port, so
 * they assert what the composition does with rows it was given and cannot assert that the rows
 * arrive at all, in the order the composition depends on, or that a party resolves to a name. Three
 * of the four things this card changed are decided in that seam: the sort is applied to whatever
 * `commitmentsOf` returned, the direction marker renders only if the obliged party survives the
 * read *and* the name read reaches it, and the age clause renders only where `closuresOf` withheld
 * a closure. A fake satisfies all three by construction.
 *
 * **The fixture is seeded against the store's own order on purpose.** `commitmentsOf` orders by
 * recency of evidence, so the discharged item is the newest here and the overdue one the oldest —
 * the store therefore returns them in exactly the order the brief must not use, and a composition
 * that passed the rows through untouched would fail rather than pass. That is the difference
 * between this case and one whose fixture happens to arrive already sorted.
 *
 * The rig is shared and nothing is emptied between tests, so every record here is minted by this
 * case and every assertion is scoped to it.
 */
class PrepareBriefStoreTest {

    private val retrieval = JdbcRetrieval(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val contacts = JdbcContactRepository(StoreRig.database)
    private val commitments = JdbcCommitmentRepository(StoreRig.database)
    private val closures = JdbcClosureRepository(StoreRig.database)

    /**
     * The conversation this case's rows live in, set by each case.
     *
     * A field rather than a parameter on every fixture: `StoreRig.externalId` counts, so a case
     * that minted one per row would scatter its rows across as many conversations, and a message
     * can only be written through a conversation that is already stored.
     */
    private lateinit var where: ExternalId

    @Test
    fun `the brief leads with what fell due and ends with what is discharged`(): Unit = runBlocking {
        aConversation("brief-order")
        val stater = aContact("brief-order-stater", "The Stater")
        val overdue = commitment("brief the overdue one", stater, stated(16), days(-9))
        val undated = commitment("brief the undated one", stater, stated(14))
        val ahead = commitment("brief the ahead one", stater, stated(12), days(5))
        val settled = commitment("brief the settled one", stater, stated(2))
        // Newest last in this list and newest first out of the store, which is the point: the
        // recency order the read imposes is the reverse of the order the brief needs.
        commitments.upsert(listOf(overdue, undated, ahead, settled))
        closeIt(settled)

        val claims = claimsOf(material(stater))

        assertEquals(
            listOf(
                "brief the overdue one",
                "brief the ahead one",
                "brief the undated one",
                "brief the settled one",
            ),
            claims,
            "the brief is in the order the store returned rather than the order a reader needs",
        )
    }

    @Test
    fun `a commitment names the party it obliges, resolved through the merge the store records`(): Unit =
        runBlocking {
            // **The case a fake cannot have.** The marker needs three things to line up in the
            // store: the column survives `commitmentsOf`, the stage asks the name read about it,
            // and that read resolves it. The obliged party here is an account the corpus never
            // named, merged into one that is — so a name arrives only if the real read follows the
            // link, which is the whole of what makes the marker worth rendering.
            aConversation("brief-direction")
            val stater = aContact("brief-direction-stater", "The Relayer")
            val nameless = aContact("brief-direction-obliged", null)
            val head = aContact("brief-direction-head", "The Other Party")
            contacts.merge(listOf(MergeLink(merged = nameless, canonical = head, score = 1.0, signals = emptyList())))
            commitments.upsert(
                listOf(commitment("brief the directed one", stater, stated(5), obliged = nameless)),
            )

            val text = material(stater).single { it.contains("brief the directed one") }

            assertTrue(
                text.contains("obliges The Other Party"),
                "the obliged party did not survive the read and the name read together: $text",
            )
            assertTrue(text.contains("stated by The Relayer"), "the stater is lost: $text")
        }

    @Test
    fun `a commitment the store places on nobody carries no direction marker`(): Unit = runBlocking {
        aConversation("brief-undirected")
        val stater = aContact("brief-undirected-stater", "The Stater")
        commitments.upsert(
            listOf(commitment("brief the undirected one", stater, stated(5), obliged = null)),
        )

        val text = material(stater).single { it.contains("brief the undirected one") }

        assertFalse(text.contains("obliges"), "a row placing the thing on nobody invents a party: $text")
    }

    @Test
    fun `the age clause follows what the closure read withheld`(): Unit = runBlocking {
        // Age renders only where nothing records an outcome, and what decides that here is the real
        // closure read — which returns a span for a discharged promise and nothing for one it merely
        // examined. A verdict carrying no span is the case that would read as closed if the port
        // handed it back, so both arms are seeded.
        aConversation("brief-age")
        val stater = aContact("brief-age-stater", "The Stater")
        val waiting = commitment("brief the waiting one", stater, stated(4))
        val examined = commitment("brief the examined one", stater, stated(4))
        val discharged = commitment("brief the discharged one", stater, stated(4))
        commitments.upsert(listOf(waiting, examined, discharged))
        closeIt(discharged)
        examineWithoutClosing(examined)

        val found = material(stater)

        assertTrue(
            found.single { it.contains("brief the waiting one") }.contains("last stated 4 days ago"),
            "an item with no recorded outcome does not say how old the record of it is",
        )
        assertTrue(
            found.single { it.contains("brief the examined one") }.contains("last stated 4 days ago"),
            "a commitment the closure pass examined and did not close reads as discharged, so it " +
                "loses the age clause and the reader cannot tell it from a settled one",
        )
        assertFalse(
            found.single { it.contains("brief the discharged one") }.contains("last stated"),
            "a discharged promise carries an age too, so the clause marks every item alike",
        )
    }

    // --- the shape, driven over the real port ---------------------------------------------

    /** The commitment blocks of a brief about [stater], in the order the material offers them. */
    private suspend fun material(stater: EntityId): List<String> {
        val stage = PrepareShape(
            StubModel,
            retrieval,
            StubGrouping,
            Clock.fixed(NOW, ZoneOffset.UTC),
            ZoneOffset.UTC,
        ).stages.first()
        val progress = stage.apply(
            Exchange(
                Request(text = "prepare me for the call"),
                subject = Subject(
                    focus = Candidate(referent = Referent.OfContact(stater), name = "The Stater", score = 1.0),
                ),
            ),
        )
        return (progress as Progress.Carried).exchange.retrieved
            .map { it.text }
            .filter { it.contains("Inferred commitment") }
    }

    /** Claims only, so an ordering assertion reads what the model will be shown and not a fixture. */
    private fun claimsOf(blocks: List<String>) = blocks.map { it.substringAfterLast(": ") }

    /** One stored conversation, which a message can only be written through. */
    private suspend fun aConversation(label: String) {
        where = StoreRig.externalId(label)
        conversations.upsert(
            Conversation(externalId = where, kind = ConversationKind.GROUP, name = label),
        )
    }

    private suspend fun aContact(label: String, displayName: String?): EntityId {
        val record = StoreRig.externalId(label)
        persons.upsert(Person(externalId = record, displayName = displayName))
        conversations.upsert(Conversation(externalId = record, kind = ConversationKind.DIRECT, name = label))
        val id = EntityId.mint()
        contacts.upsert(listOf(Contact(id = id, record = record, displayName = displayName)))
        return id
    }

    private fun commitment(
        claim: String,
        statedBy: EntityId,
        at: Instant,
        due: Instant? = null,
        obliged: EntityId? = null,
    ): Commitment = Commitment(
        id = EntityId.mint(),
        claim = claim,
        conversation = where,
        evidence = setOf(
            Evidence(
                span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId("brief-evidence")),
                sourceTime = at,
            ),
        ),
        hedged = false,
        due = due,
        statedBy = statedBy,
        obligedParty = obliged,
    )

    /** Records that a later message discharged [commitment], which is what withholds the age clause. */
    private suspend fun closeIt(commitment: Commitment) {
        val closing = Message(
            externalId = StoreRig.externalId("brief-closing"),
            sourceTime = NOW.minusSeconds(3600),
            text = "that one is done",
        )
        messages.upsert(where, listOf(closing))
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
    }

    /** A verdict that nothing closed it, which must not read as a closure. */
    private suspend fun examineWithoutClosing(commitment: Commitment) {
        closures.record(
            listOf(
                Closure(
                    commitment = commitment.id,
                    closedBy = null,
                    weighed = Weighed(NOW.minusSeconds(3600), StoreRig.externalId("brief-considered")),
                    decidedBy = "closure-1:test@1/MEDIUM",
                ),
            ),
        )
    }

    private companion object {

        /** Fixed, so a deadline is ahead or behind by the fixture's choice and not the day's. */
        val NOW: Instant = Instant.parse("2026-10-15T12:00:00Z")

        /** A deadline [offset] days from today, aligned to a day boundary as a stated date is. */
        fun days(offset: Long): Instant = NOW.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            .plus(java.time.Duration.ofDays(offset))

        /** An evidence instant [ago] days old, which is what the age clause counts. */
        fun stated(ago: Long): Instant = NOW.minus(java.time.Duration.ofDays(ago))

        object StubGrouping : Grouping {
            override suspend fun of(records: List<ExternalId>): Map<ExternalId, GroupingKey> = emptyMap()
        }

        object StubModel : ModelProvider {
            override fun version() = "stub"
            override suspend fun respond(request: ModelRequest): ModelOutcome =
                error("the retrieval stage must not reach the model")
            override suspend fun submit(requests: Map<String, ModelRequest>): SubmissionId =
                error("the retrieval stage must not reach the model")
            override suspend fun collect(submission: SubmissionId): Collected =
                error("the retrieval stage must not reach the model")
        }
    }
}
