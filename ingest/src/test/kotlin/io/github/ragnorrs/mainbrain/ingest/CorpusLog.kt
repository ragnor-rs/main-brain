package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.BackfillProgress
import io.github.ragnorrs.mainbrain.domain.Freshness
import io.github.ragnorrs.mainbrain.domain.Health
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.Document
import io.github.ragnorrs.mainbrain.domain.record.DocumentRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.UnknownConversation
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.time.Instant

/**
 * Every write the loop made through the record ports, in the order it made them.
 *
 * A log rather than a store. The store's own properties -- that re-delivering a batch upserts
 * rather than duplicates, that content time stays put where nothing changed -- are asserted
 * against a real Postgres in `:data`, and a map keyed by identifier here would only demonstrate
 * that a map behaves like a map. What these tests need instead is *what the loop did*: which
 * records reached which port, in what order, and how many times.
 */
internal class CorpusLog {

    val conversations = mutableListOf<Conversation>()
    val messages = mutableListOf<Pair<ExternalId, List<Message>>>()
    val persons = mutableListOf<Person>()
    val documents = mutableListOf<Document>()

    /** Identifiers recorded as withdrawn, flattened in the order the calls arrived. */
    val withdrawn = mutableListOf<ExternalId>()

    /**
     * The message port's calls, named, in the order they were made.
     *
     * The lists above record *what* reached a port, which cannot answer *in what order* once two
     * of them are involved: a writer that recorded a withdrawal before storing the message would
     * fill both exactly as a correct one does. One sequence is what makes that difference
     * visible. Only the message port is traced, because it is the only one with two write paths.
     */
    val order = mutableListOf<String>()

    /**
     * Thrown by the next person write, once.
     *
     * Persons are written after conversations and their messages, so a fault here interrupts a
     * batch half-written -- which is the state a restart has to recover from.
     */
    var faultOnNextPerson: Exception? = null
}

internal class LoggingConversationRepository(private val log: CorpusLog) : ConversationRepository {
    override suspend fun upsert(conversation: Conversation) {
        log.conversations += conversation
    }

    override suspend fun find(externalId: ExternalId): Conversation = unread()
    override suspend fun timesOf(externalId: ExternalId) = unread()
    override suspend fun identifiersOf(externalId: ExternalId) = unread()
    override suspend fun conversations(externalIds: List<ExternalId>) = unread()
}

internal class LoggingMessageRepository(private val log: CorpusLog) : MessageRepository {

    /**
     * Refuses a conversation it has not seen written, as the port says the store does.
     *
     * The one piece of store behaviour worth keeping in a fake, because it is the port's
     * contract rather than the store's internals -- and without it nothing here would notice a
     * writer that sent messages before the header they belong to.
     */
    override suspend fun upsert(conversation: ExternalId, messages: List<Message>) {
        if (log.conversations.none { it.externalId == conversation }) throw UnknownConversation(conversation)
        log.messages += conversation to messages
        log.order += "upsert"
    }

    /** Logged rather than refused, because the order it lands in is the thing worth asserting. */
    override suspend fun markWithdrawn(externalIds: List<ExternalId>) {
        log.withdrawn += externalIds
        log.order += "markWithdrawn"
    }

    override suspend fun messagesIn(conversation: ExternalId): List<Message> = unread()
    override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> = unread()
    override suspend fun messages(externalIds: List<ExternalId>): List<Message> = unread()
    override suspend fun conversationsOf(messages: List<ExternalId>) = unread()
    override suspend fun timesOf(externalId: ExternalId) = unread()
    override suspend fun identifiersOf(externalId: ExternalId) = unread()
    override suspend fun sharingAConversation(authors: List<ExternalId>) = unread()
}

internal class LoggingPersonRepository(private val log: CorpusLog) : PersonRepository {
    override suspend fun upsert(person: Person) {
        log.faultOnNextPerson?.let {
            log.faultOnNextPerson = null
            throw it
        }
        log.persons += person
    }

    override suspend fun find(externalId: ExternalId): Person = unread()
    override suspend fun timesOf(externalId: ExternalId) = unread()
    override suspend fun identifiersOf(externalId: ExternalId) = unread()
    override suspend fun persons(externalIds: List<ExternalId>) = unread()
    override suspend fun claimants(claims: List<Identifier>) = unread()
    override suspend fun nameFrequency(names: List<String>) = unread()

    override suspend fun namesakes(names: List<String>, carriedByAtMost: Int) = unread()
}

internal class LoggingDocumentRepository(private val log: CorpusLog) : DocumentRepository {
    override suspend fun upsert(document: Document) {
        log.documents += document
    }

    override suspend fun find(externalId: ExternalId): Document = unread()
    override suspend fun timesOf(externalId: ExternalId) = unread()
    override suspend fun identifiersOf(externalId: ExternalId) = unread()
}

/**
 * What was reported about the reading of a source, in the order it was reported.
 *
 * Two writers reach this port and they report different things, which is why all three write
 * functions are recorded here: the scheduler records the outcome of a pass, because that is what
 * it alone observes, while freshness and backfill completion are the adapter's — only it knows how
 * current it believes itself to be.
 *
 * The three readers are unread on purpose: folding passes into health here would be a second
 * implementation of a rule that already exists in one place, and these tests would then pass
 * against a summary nothing in production computes. What health amounts to is asserted in
 * `:data`, against the row it is read from.
 */
internal class IngestStateLog : IngestStateRepository {

    val passes = mutableListOf<Pass>()

    val freshness = mutableListOf<FreshnessReport>()

    val backfill = mutableListOf<BackfillReport>()

    /** One freshness report, as made rather than as summarised. */
    data class FreshnessReport(val completeThrough: Instant?, val lagging: List<WorkUnitId>)

    /** One backfill report. The store stamps when it arrived, so nothing here needs to. */
    data class BackfillReport(val complete: Boolean, val reached: Instant?)

    /**
     * Thrown by every attempt to write anything here, once set.
     *
     * The realistic failure rather than a contrived one: the state row and the corpus are the
     * same Postgres, so a store that refuses a write refuses the note about it too.
     *
     * **All three writes honour it, not just the pass.** The adapter reports freshness and
     * backfill completion on a path the scheduler never takes, so a fault that reached only
     * `recordPass` would leave the question *what does a refused report cost* unaskable — which
     * is the shape of blind spot this suite has already been caught by once.
     */
    var fault: Exception? = null

    override suspend fun reportFreshness(source: Source, completeThrough: Instant?, lagging: List<WorkUnitId>) {
        fault?.let { throw it }
        freshness += FreshnessReport(completeThrough, lagging)
    }

    override suspend fun reportBackfill(source: Source, complete: Boolean, reached: Instant?) {
        fault?.let { throw it }
        backfill += BackfillReport(complete, reached)
    }

    override suspend fun recordPass(source: Source, pass: Pass) {
        fault?.let { throw it }
        passes += pass
    }

    override suspend fun freshnessOf(source: Source): Freshness = unread()
    override suspend fun healthOf(source: Source): Health = unread()
    override suspend fun backfillOf(source: Source): BackfillProgress = unread()
}

/**
 * For the half of a port these tests do not exercise.
 *
 * Failing rather than returning something empty: a fake that answers a question nobody meant to
 * ask makes the test pass over a call it never intended, and the loop under test reads nothing
 * back out of the corpus.
 */
private fun unread(): Nothing =
    error("The ingest loop writes the corpus and reads nothing back out of it. This fake answers writes only.")
