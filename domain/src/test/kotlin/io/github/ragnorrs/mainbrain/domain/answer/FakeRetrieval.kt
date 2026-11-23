package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant

/**
 * A [Retrieval] that returns what a test tells it to, and records what it was asked for.
 *
 * This ships beside its port rather than inside whichever shape's test needed it first, for the
 * reason [io.github.ragnorrs.mainbrain.domain.FakeModelProvider] does: three shapes read through this port,
 * and three fakes of it would be three chances to disagree about what it does. The disagreement
 * surfaces as three passing tests beside one broken shape.
 *
 * What the tests mostly assert through it is the *asking* rather than the answering — that a shape
 * bounded its reads, and that the inbox's unscoped form really is unscoped — so every call is
 * recorded.
 */
@Suppress("LongParameterList") // One fixture per read on the port, which is what a fake of it is.
class FakeRetrieval(
    private val conversations: List<ExternalId> = emptyList(),
    private val messages: List<Message> = emptyList(),
    private val commitments: List<Commitment> = emptyList(),
    private val ahead: List<Event> = emptyList(),
    private val past: List<Event> = emptyList(),
    private val undated: List<Event> = emptyList(),
    private val statements: List<Statement> = emptyList(),
    private val names: Map<EntityId, String> = emptyMap(),
    /**
     * What the corpus holds about each message: where it was said, and what its author is called.
     *
     * Its own fixture rather than fields on the message, because that is the port's shape: the
     * corpus records who wrote a turn and the name is a read away. A case that could set the name on
     * the fixture directly would pass over a shape that never asked for it.
     *
     * Keyed by the message so that a span a test hands to the fake index can be placed and named
     * too: those carry a record identifier and nothing else, which is the whole reason the read is
     * keyed this way.
     */
    private val turns: Map<ExternalId, Turn> = emptyMap(),
    /**
     * Which commitments something is recorded as having closed, by the message that closed it.
     *
     * Keyed by commitment so a case can script one closure among several promises, which is the
     * shape that matters: an answer that worded every commitment alike would pass a fixture where
     * all of them were closed and all of them unclosed.
     */
    private val closures: Map<EntityId, Message> = emptyMap(),
    /**
     * What [turnsOf] throws instead of answering, for a case about an unreadable corpus.
     *
     * Here rather than in a second fake of this port, for the reason the note above gives: one
     * fake, so there is one account of what the port does. Null for every case that is not about
     * the store failing — which is all but one, since this read is the only one the answer path
     * makes after the model has been paid.
     */
    private val turnsFault: Exception? = null,
) : Retrieval {

    /** Every call made, in order, as what was asked rather than what came back. */
    val asked = mutableListOf<Ask>()

    override suspend fun conversationsOf(subject: Subject, threads: Int): List<ExternalId> {
        asked += Ask.Conversations(subject, threads)
        return conversations
    }

    override suspend fun recentMessages(
        conversations: List<ExternalId>,
        perConversation: Int,
    ): List<Message> {
        asked += Ask.Messages(conversations, perConversation)
        return messages
    }

    override suspend fun commitmentsOf(reach: Reach, limit: Int): List<Commitment> {
        asked += Ask.Commitments(reach, limit)
        return commitments
    }

    override suspend fun eventsAhead(reach: Reach, horizon: Horizon, limit: Int): List<Event> {
        asked += Ask.Events(reach, horizon, limit, ahead = true)
        return ahead
    }

    override suspend fun eventsPast(reach: Reach, horizon: Horizon, limit: Int): List<Event> {
        asked += Ask.Events(reach, horizon, limit, ahead = false)
        return past
    }

    override suspend fun eventsUndated(reach: Reach, limit: Int): List<Event> {
        asked += Ask.UndatedEvents(reach, limit)
        return undated
    }

    override suspend fun statementsOf(reach: Reach, limit: Int): List<Statement> {
        asked += Ask.Statements(reach, limit)
        return statements
    }

    /**
     * Only the asked-for commitments that something closed, which is what the store does.
     *
     * Filtered on [commitments] for the reason the names read is: a shape asking about the wrong
     * identifiers would otherwise be handed the right closures anyway, and the test would pass over
     * exactly the mistake this read can make.
     */
    override suspend fun closuresOf(commitments: List<EntityId>): List<Closed> {
        asked += Ask.Closures(commitments)
        return commitments.mapNotNull { commitment -> closures[commitment]?.let { Closed(commitment, it) } }
    }

    /**
     * Only the asked-for parties that have a name, which is what the store does.
     *
     * Filtered on [parties] rather than returning the whole fixture: a shape that asks for the wrong
     * identifiers would otherwise be handed the right names anyway, and the test would pass over
     * exactly the mistake this read can make.
     */
    override suspend fun namesOf(parties: List<EntityId>): Map<EntityId, String> {
        asked += Ask.Names(parties)
        return names.filterKeys { it in parties }
    }

    /**
     * Only the asked-for messages the corpus holds, which is what the store does.
     *
     * Filtered on the argument for the reason the party names are: a shape that asked about the
     * wrong records — or about none, which is the defect this read exists for — must not be handed
     * the right answer anyway.
     *
     * **Whose a turn is, is withheld where nothing was asked, which the store does too.** A fixture
     * can set the fact on a turn, so a shape that never passed the subject would otherwise be handed
     * it anyway and every case about the marker would pass against a shape that forgot the argument
     * — the one mistake this parameter can make. Blanked rather than rejected, because a caller
     * passing no contacts is the ordinary case for four of the five shapes.
     */
    override suspend fun turnsOf(messages: List<ExternalId>, subject: List<EntityId>): Map<ExternalId, Turn> {
        asked += Ask.Turns(messages, subject)
        turnsFault?.let { throw it }
        return turns.filterKeys { it in messages }
            .mapValues { (_, turn) -> if (subject.isEmpty()) turn.copy(authorIsAnotherParty = null) else turn }
    }

    /** One read a shape made. */
    sealed interface Ask {

        data class Conversations(val subject: Subject, val threads: Int) : Ask

        data class Messages(val conversations: List<ExternalId>, val perConversation: Int) : Ask

        data class Commitments(val reach: Reach, val limit: Int) : Ask

        data class Events(
            val reach: Reach,
            val horizon: Horizon,
            val limit: Int,
            val ahead: Boolean,
        ) : Ask

        /**
         * Apart from [Events] and carrying no horizon, because that is the whole of what it is.
         * Folded into that one with a nullable horizon, a case counting the dated reads could not
         * tell them from this one, and the two bounds a dated read has to carry would become
         * optional on the type that records them.
         */
        data class UndatedEvents(val reach: Reach, val limit: Int) : Ask

        data class Statements(val reach: Reach, val limit: Int) : Ask

        data class Names(val parties: List<EntityId>) : Ask

        /**
         * Apart from [Names] because the key is a different thing: a party is a contact the derived
         * layer minted, and this asks about a record the corpus holds. Folded into one, a case
         * counting the party read could not tell it from this one.
         */
        data class Turns(val messages: List<ExternalId>, val subject: List<EntityId> = emptyList()) : Ask

        data class Closures(val commitments: List<EntityId>) : Ask
    }

    companion object {

        /** When the fixtures are from. Fixed, so nothing here depends on the clock. */
        val WHEN: Instant = Instant.parse("2026-10-01T09:00:00Z")

        /**
         * One message, by nobody the corpus names unless [author] says otherwise.
         *
         * [author] is the person record it points at. It defaults to absent because that is the case
         * whose rendering asserts the most: a turn with no name has to carry none rather than
         * carrying a placeholder, and a fixture that always named somebody would never exercise it.
         *
         * Setting it does not on its own name anybody — the name read is keyed by the message, so a
         * case wanting a named turn supplies `authors` as well. That is the port's shape and not an
         * inconvenience of the fake: a shape that never asked would otherwise be handed a name.
         */
        fun message(
            nativeId: String,
            text: String?,
            at: Instant = WHEN,
            author: ExternalId? = null,
        ): Message = Message(
            externalId = ExternalId(Source.TELEGRAM, nativeId),
            sourceTime = at,
            author = author,
            text = text,
        )

        /** The record a message or an indexed span is addressed by, which the turn read is keyed by. */
        fun record(nativeId: String): ExternalId = ExternalId(Source.TELEGRAM, nativeId)

        /**
         * What the corpus holds about one turn, in the subject's own conversation unless told
         * otherwise.
         *
         * [CONVERSATION] is the default because the question most cases are about is the name, and a
         * case about where a turn was said says so. The draft shape is the one that reads it, and
         * its fixtures name the conversation on both sides.
         *
         * [isSelf] defaults to false for the reason the port gives: a turn nothing establishes as
         * the owner's is one to write *to* rather than *as*, so the default is the answer for every
         * case that is not about the sender's own words.
         *
         * [channel] defaults to the one-to-one thread because that is the case a rendering says
         * nothing about, so a fixture taking the default asserts the plain form — and a case about
         * a room has to say so, which is the decision the port refuses to default.
         */
        fun turn(
            author: String? = null,
            conversation: ExternalId = CONVERSATION,
            isSelf: Boolean = false,
            channel: Channel = Channel.OneToOne,
            /**
             * Whether somebody who is neither the subject nor the reader wrote it, defaulting to
             * nothing said either way.
             *
             * Null rather than false for the reason the port gives: the absent answer and the
             * denial are different facts, and a default of false would assert of every turn in
             * every fixture that the subject or the reader wrote it.
             */
            anotherParty: Boolean? = null,
        ): Turn = Turn(
            conversation = conversation,
            channel = channel,
            author = author,
            authorIsSelf = isSelf,
            authorIsAnotherParty = anotherParty,
        )

        /**
         * One commitment, undated unless [due] says otherwise.
         *
         * [due] defaults to null because that is the ordinary case — most promises carry no date,
         * and recognition refuses two values it used to store — so a fixture that could not express
         * it would test only the rarer half of the rendering.
         *
         * [obligedParty] defaults to null for the same reason and a stronger one: the restating
         * stage can answer it only as one of its window's labels, so a row carrying none is an
         * ordinary row, and the case a fixture must be able to express is a party on the row that
         * is **not** the one that stated it. The two defaulting to the same absence is what lets a
         * case set one and assert which marker the rendering used.
         */
        @Suppress("LongParameterList") // A commitment's own shape, and every part of it is varied by some case.
        fun commitment(
            claim: String,
            evidencedBy: String,
            hedged: Boolean = false,
            statedBy: EntityId? = null,
            due: Instant? = null,
            obligedParty: EntityId? = null,
        ): Commitment =
            Commitment(
                id = EntityId.mint(),
                claim = claim,
                conversation = CONVERSATION,
                evidence = setOf(evidence(evidencedBy)),
                hedged = hedged,
                due = due,
                statedBy = statedBy,
                obligedParty = obligedParty,
            )

        fun event(
            claim: String,
            evidencedBy: String,
            occursAt: Instant? = WHEN,
            statedBy: EntityId? = null,
        ): Event = Event(
            id = EntityId.mint(),
            claim = claim,
            conversation = CONVERSATION,
            evidence = setOf(evidence(evidencedBy)),
            hedged = false,
            occursAt = occursAt,
            statedBy = statedBy,
        )

        /**
         * One attribute, with the window it held over.
         *
         * [until] defaults to null — the ordinary case, a fact nothing has contradicted — because
         * that is the one a test forgets to set and the one whose wording asserts the most.
         */
        @Suppress("LongParameterList") // A statement's own shape: six parts, every one of them varied by some case.
        fun statement(
            predicate: String,
            value: String,
            evidencedBy: String,
            subject: EntityId = EntityId.mint(),
            from: Instant = WHEN,
            until: Instant? = null,
            origin: Origin = Origin.INFERRED,
        ): Statement = Statement(
            subject = subject,
            predicate = Predicate.of(predicate),
            value = value,
            validity = Validity(from = from, until = until),
            origin = origin,
            evidence = setOf(evidence(evidencedBy)),
        )

        fun evidence(nativeId: String): Evidence = Evidence(
            span = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, nativeId)),
            sourceTime = WHEN,
        )

        /** The conversation these findings were read in, which the answer path never narrows on. */
        val CONVERSATION: ExternalId = ExternalId(Source.TELEGRAM, "-100777")

        /** A resolved subject of one contact, which three of the shapes accept. */
        fun aContact(): Subject = Subject(
            focus = Candidate(
                referent = Referent.OfContact(EntityId.mint()),
                name = "A Person",
                score = 1.0,
            ),
        )

        /**
         * A resolved subject of one client.
         *
         * The kind that has no contact behind it, which is what made the unscoped entity read the
         * silent default before [Reach] existed.
         */
        fun aClient(): Subject = Subject(
            focus = Candidate(
                referent = Referent.OfClient(EntityId.mint()),
                name = "A Client",
                score = 1.0,
            ),
        )

        /** A resolved subject of one conversation — also no contact behind it. */
        fun aConversation(): Subject = Subject(
            focus = Candidate(
                referent = Referent.OfConversation(ExternalId(Source.TELEGRAM, "a thread")),
                name = "A Thread",
                score = 1.0,
            ),
        )

        /** The inbox: the subject with nothing behind it. */
        fun theInbox(): Subject = Subject(focus = Candidate.inbox())
    }
}
