package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationUpdate
import io.github.ragnorrs.mainbrain.domain.record.Document
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.SourceStream
import io.github.ragnorrs.mainbrain.domain.record.UnknownWorkUnit
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ingest loop, driven by a scripted adapter.
 *
 * What is asserted here is the loop rather than the store: which records reached which port, in
 * what order, and that an interrupted batch comes round again. The store's own guarantees are
 * asserted in `:data` against a real Postgres, and the two halves meet at the ports.
 *
 * One source is in scope, so most of these drive one stream. Several drive two anyway -- stated
 * without a count, because a count is the thing that goes stale. The scheduler keys a held batch
 * by the stream that handed it over rather than by its source, so a second stream is expressible
 * without a second `Source` constant, and it has to be: how two streams share a sweep is not a
 * property one stream can demonstrate.
 */
class SchedulerTest {

    private val unit = WorkUnitId("a-conversation")
    private val log = CorpusLog()
    private val state = IngestStateLog()

    private fun writer() = BatchWriter(
        conversations = LoggingConversationRepository(log),
        messages = LoggingMessageRepository(log),
        persons = LoggingPersonRepository(log),
        documents = LoggingDocumentRepository(log),
    )

    private fun scheduler(stream: ScriptedSource, idle: Duration = Scheduler.DEFAULT_IDLE) = Scheduler(
        streams = listOf(stream),
        corpus = writer(),
        state = state,
        idle = idle,
    )

    @Test
    fun `a scripted adapter drives the whole loop, and sweeping again changes nothing`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch(), peopleBatch())))
        val scheduler = scheduler(stream)

        scheduler.sweep()

        assertEquals(listOf(conversation().externalId), log.conversations.map { it.externalId })
        assertEquals(listOf(conversation().externalId to listOf(message())), log.messages)
        assertEquals(listOf(person()), log.persons)
        assertEquals(listOf(document()), log.documents)
        assertEquals(listOf<Pass>(Pass.Succeeded, Pass.Succeeded), state.passes)

        // The script is read to the end, so a second sweep finds nothing available. Nothing is
        // recorded for it either: a pass that moved no material says nothing about the source.
        scheduler.sweep()

        assertEquals(1, log.conversations.size)
        assertEquals(2, state.passes.size)
    }

    /**
     * The scheduler records that a pass happened and claims nothing about the source itself.
     *
     * Freshness and backfill completion are the adapter's to report: only it knows how current it
     * believes itself to be, and the scheduler knows no source. This was enforced by the fixture
     * refusing those two calls outright, until the adapter needed them recorded — so it is
     * asserted here instead, because a division of labour that lives only in prose is one a later
     * change can cross without anything going red.
     */
    @Test
    fun `the scheduler reports passes and nothing about how current a source is`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))

        scheduler(stream).sweep()

        assertEquals(listOf<Pass>(Pass.Succeeded), state.passes)
        assertTrue(state.freshness.isEmpty()) { "Freshness is the adapter's to report, not the loop's." }
        assertTrue(state.backfill.isEmpty()) { "Backfill completion is the adapter's to report, not the loop's." }
    }

    @Test
    fun `a conversation header with no messages behind it writes a header and nothing else`(): Unit = runBlocking {
        // The ordinary case, not an edge one: every batch carries its conversation headers
        // whether the conversation is new or years old, so most of them have nothing nested.
        val header = Batch(conversations = listOf(ConversationUpdate(conversation())))
        scheduler(ScriptedSource(script = mapOf(unit to listOf(header)))).sweep()

        assertEquals(1, log.conversations.size)
        assertTrue(log.messages.isEmpty()) { "There were no messages, so no write should claim there were." }
    }

    @Test
    fun `nothing available right now is not a source that has finished`(): Unit = runBlocking {
        val stream = ScriptedSource()
        val scheduler = scheduler(stream)

        scheduler.sweep()

        assertTrue(log.conversations.isEmpty()) { "Nothing was scripted, so nothing should have been written." }

        // The same stream, asked again after material appeared. An adapter is never finished: it
        // stays on its change feed, and what it had nothing of a minute ago it may have now.
        stream.offer(unit, conversationBatch())
        scheduler.sweep()

        assertEquals(listOf(conversation().externalId), log.conversations.map { it.externalId })
    }

    @Test
    fun `an interrupted batch is held and re-delivered whole, and nothing is skipped`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(wholeBatch())))
        val scheduler = scheduler(stream)
        log.faultOnNextPerson = IllegalStateException("the store went away mid-batch")

        scheduler.sweep()

        // Half-written: the conversation and its messages landed, the person write failed, and
        // the documents behind it were never reached.
        assertEquals(1, log.conversations.size)
        assertTrue(log.persons.isEmpty()) { "The person write was the one made to fail." }
        assertTrue(log.documents.isEmpty()) { "Documents are written after persons, so none was reached." }
        assertEquals(1, stream.asks) { "A failed write must not be followed by another ask." }
        // Unclassified, not temporary: a write that failed for an unexamined reason supports no
        // claim that waiting will fix it.
        assertEquals(listOf(Pass.Failed(FailureKind.UNEXPECTED, "the store went away mid-batch")), state.passes)

        scheduler.sweep()

        // Written again from the top rather than resumed from where it broke. That is what makes
        // delivery at-least-once rather than exactly-once: the conversation is written twice,
        // and in the real store the second write is an upsert that changes nothing.
        assertEquals(2, log.conversations.size)
        assertEquals(listOf(person()), log.persons)
        assertEquals(listOf(document()), log.documents)
        assertEquals(
            listOf(Pass.Failed(FailureKind.UNEXPECTED, "the store went away mid-batch"), Pass.Succeeded),
            state.passes,
        )

        scheduler.sweep()

        // And the position did move past it once it was stored, so the batch is not re-delivered
        // forever.
        assertEquals(2, log.conversations.size)
    }

    @Test
    fun `a stream with nothing right now is not left behind by one that keeps delivering`(): Unit = runBlocking {
        // The backfilling adapter: material almost every time it is asked, which is its
        // documented steady state while it walks history backwards.
        val backfilling = ScriptedSource(
            script = mapOf(unit to List(3) { index -> conversationBatch("history-$index") }),
        )
        // The incremental one: quiet on its first ask, with today's messages behind that.
        val incremental = ScriptedSource(
            script = mapOf(WorkUnitId("another-conversation") to listOf(conversationBatch("today"))),
            quietAsks = 1,
        )
        val scheduler = Scheduler(
            streams = listOf(backfilling, incremental),
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )

        scheduler.sweep()

        // The interleaving, not a membership test and not a count: this order is only
        // producible if the first ask really was quiet and the stream kept its place anyway.
        // Asserting that "today" appears somewhere would hold even if nothing was ever quiet,
        // which would leave the whole test resting on a fixture knob nothing checks.
        assertEquals(
            listOf("history-0", "history-1", "today", "history-2"),
            log.conversations.map { it.externalId.nativeId },
        )
    }

    @Test
    fun `a stream that failed is left alone for the rest of the sweep`(): Unit = runBlocking {
        val failing = ScriptedSource(script = mapOf(unit to listOf(conversationBatch("refused"))))
        val delivering = ScriptedSource(
            script = mapOf(WorkUnitId("another-conversation") to List(3) { index -> conversationBatch("ok-$index") }),
        )
        failing.fault = IllegalStateException("the session went away")
        val scheduler = Scheduler(
            streams = listOf(failing, delivering),
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )

        scheduler.sweep()

        // One ask, not one per round: `idle` is the backoff, so a stream that is erroring is not
        // hammered while another keeps the sweep alive.
        assertEquals(1, failing.asks) { "A failed stream drops out rather than being re-asked." }
        assertEquals(listOf("ok-0", "ok-1", "ok-2"), log.conversations.map { it.externalId.nativeId }) {
            "The other stream was not held up, and the refused batch was not written."
        }
    }

    @Test
    fun `a held batch is never handed to another stream`(): Unit = runBlocking {
        // Two streams naming one source. The port says one adapter per source and the
        // composition root wires it that way, but nothing here enforces it, and the cost of
        // getting it wrong is one stream's material written on another's turn — then
        // acknowledged against a position that never handed it over.
        val failing = ScriptedSource(script = mapOf(unit to listOf(peopleBatch())))
        val other = ScriptedSource(
            script = mapOf(WorkUnitId("another-conversation") to listOf(conversationBatch("today"))),
        )
        log.faultOnNextPerson = IllegalStateException("the store refused it")
        val scheduler = Scheduler(
            streams = listOf(failing, other),
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )

        scheduler.sweep()

        assertEquals(listOf("today"), log.conversations.map { it.externalId.nativeId })
        assertTrue(log.persons.isEmpty()) {
            "The refused batch was handed over by `failing` and is still held against it, so it " +
                "must not have been written on `other`'s turn."
        }
    }

    @Test
    fun `two adapters that compare equal do not share a held batch`(): Unit = runBlocking {
        // `data class` is how this codebase writes types, so an adapter will eventually be one,
        // and two instances built from the same configuration then compare equal while reading
        // from different positions. A held batch keyed by equality merges them — one stream's
        // material written on another's turn — which an ordinary map would do and identity does
        // not. Nothing but this test stands between the two.
        // A batch with no conversation in it, so a refused write leaves nothing of its own in
        // the log and the assertions below can tell the two streams' material apart.
        val refused = ValueEqualSource().also { it.batches += peopleBatch() }
        val delivering = ValueEqualSource().also { it.batches += conversationBatch("today") }
        log.faultOnNextPerson = IllegalStateException("the store refused it")
        // The fixture's premise, asserted rather than assumed: drop `data` from its declaration
        // and these two stop comparing equal, which would leave this test green over nothing
        // while the keying defect it exists to pin came back. That is the same shape as the
        // quiet-ask knob the previous pass had to make load-bearing.
        assertEquals(refused, delivering) {
            "These must compare equal, or the test asserts nothing about keying by identity."
        }
        val scheduler = Scheduler(
            streams = listOf(refused, delivering),
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )

        scheduler.sweep()

        assertEquals(listOf("today"), log.conversations.map { it.externalId.nativeId })
        assertTrue(log.persons.isEmpty()) {
            "The refused batch belongs to the stream that handed it over, not to its equal twin."
        }
    }

    @Test
    fun `a batch the store keeps refusing is kept, and the second failure asks for a person`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(wholeBatch())))
        val scheduler = scheduler(stream)
        log.faultOnNextPerson = IllegalStateException("the first refusal")

        scheduler.sweep()
        log.faultOnNextPerson = IllegalStateException("the same refusal again")
        scheduler.sweep()

        // Nothing is given up on: dropping the batch would let the next ask acknowledge material
        // that was never stored, so what escalates is the report rather than the handling.
        assertEquals(
            listOf(
                Pass.Failed(FailureKind.UNEXPECTED, "the first refusal"),
                Pass.Failed(FailureKind.NEEDS_A_PERSON, "the same refusal again"),
            ),
            state.passes,
        )
        assertEquals(1, stream.asks) { "The stream is still not asked again while its batch is held." }
        assertTrue(log.persons.isEmpty())

        // And a success clears it, which is why no threshold is needed. Without this, an
        // escalation that latched per stream would pass every assertion above.
        scheduler.sweep()
        log.faultOnNextPerson = IllegalStateException("a later, unrelated refusal")
        stream.offer(unit, wholeBatch())
        scheduler.sweep()

        assertEquals(
            listOf(
                Pass.Failed(FailureKind.UNEXPECTED, "the first refusal"),
                Pass.Failed(FailureKind.NEEDS_A_PERSON, "the same refusal again"),
                Pass.Succeeded,
                Pass.Failed(FailureKind.UNEXPECTED, "a later, unrelated refusal"),
            ),
            state.passes,
        )
    }

    @Test
    fun `a write that fails while the note about it also fails does not stop the loop`(): Unit = runBlocking {
        // The path that actually crashed: recording a pass from inside the failure handler,
        // when the thing being recorded to is the thing that just failed. The state row and the
        // corpus are one Postgres, so these two arrive together rather than separately.
        val stream = ScriptedSource(script = mapOf(unit to listOf(wholeBatch())))
        val scheduler = scheduler(stream)
        log.faultOnNextPerson = IllegalStateException("the store went away")
        state.fault = IllegalStateException("and so did the note about it")

        scheduler.sweep()

        assertEquals(1, stream.asks) { "The sweep returned instead of propagating, and asked no more." }
        assertTrue(log.persons.isEmpty())

        state.fault = null
        scheduler.sweep()

        // Nothing was lost: the batch was held through a failure that could not even be
        // recorded, and comes round again.
        assertEquals(listOf(person()), log.persons)
        assertEquals(listOf<Pass>(Pass.Succeeded), state.passes)
    }

    @Test
    fun `a note about a success that cannot be written does not stop the loop`(): Unit = runBlocking {
        // The third call site, and the one that runs on every stored batch. The previous pass
        // covered the two failure handlers and left this one uncovered, which is where the
        // original defect — recording a pass with nothing around it — was still reachable.
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch(), peopleBatch())))
        val scheduler = scheduler(stream)
        state.fault = IllegalStateException("the state row is unreachable")

        scheduler.sweep()

        // Both batches stored: the failure to record the first success did not end the sweep,
        // let alone the loop.
        assertEquals(listOf(conversation().externalId), log.conversations.map { it.externalId })
        assertEquals(listOf(person()), log.persons)

        state.fault = null
        stream.offer(unit, conversationBatch("later"))
        scheduler.sweep()

        assertEquals(listOf<Pass>(Pass.Succeeded), state.passes) { "And recording works again after." }
    }

    @Test
    fun `a client library's own timeout is reported rather than ending the loop`(): Unit = runBlocking {
        // A library raising its own cancellation inside `next()` throws the same type the
        // scheduler's own cancellation does. Rethrowing it would end `run()` with no report and
        // no trace, so it is honoured only while this coroutine is still active.
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))
        val scheduler = scheduler(stream)
        stream.fault = CancellationException("the client library's own timeout")

        scheduler.sweep()

        assertEquals(
            listOf(Pass.Failed(FailureKind.UNEXPECTED, "the client library's own timeout")),
            state.passes,
        )
    }

    @Test
    fun `a cancellation of this scheduler is honoured rather than filed against the source`(): Unit = runBlocking {
        // The other direction: a cancellation that really is ours must not be swallowed and
        // recorded as a source failure, or the process cannot be stopped and the failure it
        // reports never happened.
        val scheduler = Scheduler(
            streams = listOf(CancellingSource()),
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )

        val job = launch { scheduler.sweep() }
        job.join()

        assertTrue(state.passes.isEmpty()) {
            "Our own cancellation is honoured, not filed against the source: ${state.passes}"
        }
    }

    @Test
    fun `the scheduler sweeps the list it was handed, not the caller's`(): Unit = runBlocking {
        val given = mutableListOf<SourceStream>(ScriptedSource(script = mapOf(unit to listOf(conversationBatch()))))
        val scheduler = Scheduler(
            streams = given,
            corpus = writer(),
            state = state,
            idle = Scheduler.DEFAULT_IDLE,
        )
        val added = ScriptedSource(script = mapOf(WorkUnitId("added-later") to listOf(conversationBatch("later"))))

        given += added
        scheduler.sweep()

        assertEquals(0, added.asks) {
            "A caller mutating its own list must not change what a sweep is iterating."
        }
    }

    @Test
    fun `an ask that fails while the note about it also fails does not stop the loop`(): Unit = runBlocking {
        // The same hole, on the other handler.
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))
        val scheduler = scheduler(stream)
        stream.fault = IllegalStateException("the session went away")
        state.fault = IllegalStateException("and so did the note about it")

        scheduler.sweep()

        state.fault = null
        scheduler.sweep()

        assertEquals(listOf(conversation().externalId), log.conversations.map { it.externalId })
        assertEquals(listOf<Pass>(Pass.Succeeded), state.passes)
    }

    @Test
    fun `a run interrupted mid-batch and restarted loses nothing`(): Unit = runBlocking {
        // The adapter's own position store, which outlives the process in a real one.
        val position = mutableMapOf<WorkUnitId, Int>()
        val script = mapOf(unit to listOf(wholeBatch()))
        log.faultOnNextPerson = IllegalStateException("the process went away mid-batch")

        scheduler(ScriptedSource(script = script, position = position)).sweep()

        assertEquals(1, log.conversations.size)
        assertTrue(log.persons.isEmpty()) { "The batch was interrupted before the person write." }

        // Restarted: the scheduler's held batch is gone with the process it was held in, and the
        // adapter resumes from the position it persisted. That position never advanced, because
        // the acknowledgement is the next ask and the next ask never came.
        scheduler(ScriptedSource(script = script, position = position)).sweep()

        assertEquals(2, log.conversations.size) { "The interrupted batch is handed over again." }
        assertEquals(listOf(person()), log.persons)
        assertEquals(listOf(document()), log.documents)
    }

    @Test
    fun `a stream that fails is recorded unclassified and does not wedge the sweep`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))
        val scheduler = scheduler(stream)
        stream.fault = IllegalStateException("the session went away")

        scheduler.sweep()

        // An adapter reports the failures only it can tell apart. One that reached the scheduler
        // as an exception is not one of those, so it is not recorded as something that will
        // clear by itself.
        assertEquals(listOf(Pass.Failed(FailureKind.UNEXPECTED, "the session went away")), state.passes)
        assertTrue(log.conversations.isEmpty()) { "The ask failed, so there was no batch to write." }

        scheduler.sweep()

        assertEquals(listOf(conversation().externalId), log.conversations.map { it.externalId })
    }

    @Test
    fun `a re-read is scoped to a unit the adapter reported, and one it never reported is refused`(): Unit =
        runBlocking {
            val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))
            val scheduler = scheduler(stream)
            scheduler.sweep()

            assertFailsWith<UnknownWorkUnit> { stream.reread(WorkUnitId("a-unit-from-somewhere-else")) }

            // The host's request, not the scheduler's: the scheduler holds no reference able to make
            // this call, which is the point of the second port.
            stream.reread(unit)
            scheduler.sweep()

            assertEquals(2, log.conversations.size) {
                "A re-read hands the same material over again. Nothing is removed and the records upsert."
            }
        }

    @Test
    fun `the loop sweeps, waits, and ends only by cancellation`(): Unit = runBlocking {
        val stream = ScriptedSource(script = mapOf(unit to listOf(conversationBatch())))
        val scheduler = scheduler(stream, idle = 10.milliseconds)

        // The first sweep happens before the first wait, so the material is written whatever the
        // machine's timing; what the timeout decides is only how many empty sweeps follow it.
        val returned = withTimeoutOrNull(2.seconds) { scheduler.run() }

        assertNull(returned) { "The loop has no exit of its own: it runs until the process stops it." }
        assertEquals(1, log.conversations.size)
    }

    private fun conversationBatch() =
        Batch(conversations = listOf(ConversationUpdate(conversation(), listOf(message()))))

    /**
     * A source whose own job is cancelled by the time it throws.
     *
     * What the scheduler's shutdown looks like from inside an ask: the cancellation is this
     * coroutine's, so it has to be honoured rather than recorded as something the source did.
     */
    private class CancellingSource(override val source: Source = Source.TELEGRAM) : SourceStream {

        override suspend fun next(): Batch? {
            currentCoroutineContext().cancel(CancellationException("the scheduler is stopping"))
            throw CancellationException("the scheduler is stopping")
        }
    }

    /**
     * An adapter that compares by value, with its material held outside the equality contract.
     *
     * Two of these are equal and read different things, which is the shape a `data class`
     * adapter built twice from one configuration would have.
     */
    private data class ValueEqualSource(override val source: Source = Source.TELEGRAM) : SourceStream {

        val batches: MutableList<Batch> = mutableListOf()

        override suspend fun next(): Batch? = batches.removeFirstOrNull()
    }

    /** A batch whose conversation is named, for a test that has to tell two streams apart. */
    private fun conversationBatch(nativeId: String) = Batch(
        conversations = listOf(
            ConversationUpdate(conversation().copy(externalId = ExternalId(Source.TELEGRAM, nativeId))),
        ),
    )

    private fun peopleBatch() = Batch(persons = listOf(person()), documents = listOf(document()))

    /** Every record kind at once, so a fault partway through it leaves the batch half-written. */
    private fun wholeBatch() = Batch(
        conversations = listOf(ConversationUpdate(conversation(), listOf(message()))),
        persons = listOf(person()),
        documents = listOf(document()),
    )

    // DIRECT rather than TOPIC: a topic is the one conversation kind whose parent is always
    // set, and nothing here needs a parent.
    private fun conversation() = Conversation(
        externalId = ExternalId(Source.TELEGRAM, "conversation-1"),
        kind = ConversationKind.DIRECT,
        name = "A conversation",
    )

    private fun message() = Message(
        externalId = ExternalId(Source.TELEGRAM, "conversation-1/message-1"),
        sourceTime = Instant.parse("2026-09-29T10:00:00Z"),
        author = ExternalId(Source.TELEGRAM, "person-1"),
        text = "A line of a conversation",
    )

    private fun person() = Person(
        externalId = ExternalId(Source.TELEGRAM, "person-1"),
        displayName = "A participant",
    )

    private fun document() = Document(
        externalId = ExternalId(Source.TELEGRAM, "document-1"),
        name = "An attachment",
    )
}
