package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.SourceStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Drains every source stream at the pace it sets, and knows no source.
 *
 * It is handed [SourceStream] and nothing else, which is the whole of why the re-read port is
 * separate: there is no route from here to a re-read, so a crash loop cannot become a
 * re-backfill loop walking into the limits this system exists to stay under.
 *
 * **A batch is held until the corpus has it.** The stream port carries no acknowledgement, so
 * asking for another batch is the acknowledgement -- and an adapter advances its own position
 * when it is next asked. Asking after a failed write would therefore acknowledge material that
 * was never stored, which is the one way this loop could lose something. So a failed write keeps
 * the batch and retries it, and the only thing an interruption costs is the batch being written
 * twice, which upserts.
 *
 * **One scheduler, one thread of control.** Streams are asked one at a time rather than
 * concurrently: the budget this exists to protect is the source's request limit, not the local
 * machine's.
 *
 * **A stream that has nothing right now keeps its place in the sweep; one that failed does
 * not.** The two are different answers and were worth separating. An adapter backfilling years
 * of history has material almost every time it is asked, so dropping a stream for answering
 * nothing once would leave it waiting for that backfill to end -- days -- while a source with
 * today's messages sat unread.
 *
 * Both halves of that have a cost, and neither is bounded by [idle]. A quiet stream is asked
 * once per round for as long as any other stream keeps delivering, so an adapter that spends a
 * request to discover it has nothing spends one per round. A failed stream waits out the rest of
 * the sweep *and then* [idle], which is a lower bound and not the interval. Pacing each stream
 * separately is what bounds both, and it needs more than one duration to do it -- a wait with a
 * stated duration, a refused request and an expired page token are not the same wait. With one
 * source in scope neither cost is reachable: a lone stream answering nothing ends the sweep.
 */
class Scheduler(
    streams: List<SourceStream>,
    private val corpus: BatchWriter,
    private val state: IngestStateRepository,
    private val idle: Duration = DEFAULT_IDLE,
) {

    /** Copied, so a caller mutating its own list cannot change what a sweep is iterating. */
    private val streams = streams.toList()

    /** For the one thing this loop drops rather than acts on: a report the store refused. */
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /**
     * Batches handed over and not yet stored, keyed by the stream that handed each one over.
     *
     * By the stream rather than by its source: a batch belongs to whoever is owed an
     * acknowledgement for it, and nothing stops two streams naming one source. Keying by source
     * would merge them, and a merged entry is one stream's material retried against the other's
     * acknowledgement.
     *
     * An [IdentityHashMap] rather than the ordinary kind, because `data class` is the house
     * style for types in this build and two equal instances of such an adapter are still two
     * readers at two positions. A map keyed by equality would merge them again -- the very
     * defect this keying exists to prevent -- and if such an adapter's hash moved while a batch
     * was held, the lookup would miss it, the stream would be asked again, and the position
     * would advance over material that was never written.
     *
     * What this does not fix is the reporting: [IngestStateRepository] keeps one row per source
     * by design, so two streams of one source still overwrite each other's health. The held
     * batch is per-stream; the report is per-source, and the port's one-adapter-per-source rule
     * is what keeps that from mattering.
     *
     * At most one batch per stream, because a stream is not asked again until its last batch is
     * stored. Held in memory on purpose: an interruption loses it and the adapter, whose
     * position never advanced, hands it over again.
     *
     * A batch the store will never accept therefore stays here and stops that stream entirely —
     * which is the right trade against the alternative, because the next ask would acknowledge
     * material that was never stored. What this costs is paid in reporting rather than in
     * material: the second failure of a held batch is recorded as needing a person, since
     * nothing else will clear it.
     */
    private val held: MutableMap<SourceStream, Batch> = IdentityHashMap()

    /** Sweep, wait, sweep again, until cancelled. */
    suspend fun run() {
        while (true) {
            sweep()
            delay(idle)
        }
    }

    /**
     * One round after another, until a whole round stores nothing.
     *
     * Every stream still in the sweep gets one turn per round — a turn being an ask, or a
     * retry of the batch it is still owed — so a stream that answered nothing is asked again
     * while any other is still delivering. That is the fairness above, and the cost of it is in
     * the class comment. A stream that failed leaves the sweep for the rest of it.
     *
     * The sweep ends when a round stores nothing, rather than when every stream has dropped
     * out. Nothing here obliges a stream to answer nothing eventually, and [SourceStream] does
     * not promise it: an adapter is never finished. A stream with material every time it is
     * asked keeps this sweep running, which is a pace the adapter sets and this loop does not
     * police.
     */
    suspend fun sweep() {
        var asking = streams
        while (asking.isNotEmpty()) {
            // `map` is inline, so the suspending turn below runs inside it.
            val round = asking.map { stream -> stream to deliver(stream) }
            if (round.none { (_, turn) -> turn == Turn.STORED }) return
            asking = round.filterNot { (_, turn) -> turn == Turn.FAILED }.map { (stream, _) -> stream }
        }
    }

    /**
     * One turn for one stream: the batch it is still owed, or a new one.
     *
     * Whether a batch was already held is the whole of what the escalation in [store] needs to
     * know — no counter, because a batch stays held while it is failing and a success clears
     * both it and the report.
     */
    private suspend fun deliver(stream: SourceStream): Turn {
        val pending = held[stream]
        return if (pending == null) askAndStore(stream) else store(stream, pending, heldAlready = true)
    }

    /**
     * Ask a stream with nothing outstanding, and store whatever it hands over.
     *
     * An adapter reports its own failures, in the kinds only it can tell apart; an exception
     * reaching here is the case it did not, so it is recorded as unclassified rather than as
     * something that will clear by itself.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun askAndStore(stream: SourceStream): Turn {
        val batch = try {
            stream.next()
        } catch (failure: Exception) {
            rethrowIfCancelled(failure)
            report(stream.source, Pass.Failed(FailureKind.UNEXPECTED, failure.message))
            return Turn.FAILED
        }
        return if (batch == null) Turn.EMPTY else store(stream, batch, heldAlready = false)
    }

    /**
     * Write one batch, keeping it if the write fails.
     *
     * The failure of a batch not already held is recorded as unclassified, for the same reason
     * a failed ask is: a write that failed for an unexamined reason supports no claim that
     * waiting will fix it. The failure of one that *was* already held is recorded as needing a
     * person, because waiting has been tried — the material cannot be given up on without
     * losing it, so the batch stays and the report escalates instead. A success clears the batch
     * and, through [Health], the conclusion drawn from the report; that is why neither needs a
     * threshold.
     *
     * Nothing at all is recorded for a stream that had nothing to hand over: a pass that moved
     * no material says nothing about whether the source can be reached, and a success recorded
     * for one would make an adapter that has quietly stopped reading look current.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun store(stream: SourceStream, batch: Batch, heldAlready: Boolean): Turn {
        try {
            corpus.write(batch)
        } catch (failure: Exception) {
            // Held before anything else, and whatever the failure was. A cancellation says
            // nothing about whether the write landed: re-writing a batch that did land costs an
            // upsert, while dropping one that did not loses it. This ordering is not asserted —
            // it only shows itself if a caller catches a cancellation and reuses the same
            // scheduler, which nothing does, and a test for it would have to invent that caller.
            held[stream] = batch
            rethrowIfCancelled(failure)
            val kind = if (heldAlready) FailureKind.NEEDS_A_PERSON else FailureKind.UNEXPECTED
            report(stream.source, Pass.Failed(kind, failure.message))
            return Turn.FAILED
        }
        // Past the write, and only the write is in the guard above: a cancellation while
        // reporting a success must not put a stored batch back into `held`.
        held -= stream
        report(stream.source, Pass.Succeeded)
        return Turn.STORED
    }

    /**
     * Record a pass, and carry on if the recording itself fails.
     *
     * The state row and the corpus are the same Postgres, so a store that cannot be written is
     * also a store that cannot be told about it — which makes this the likely case rather than
     * the exotic one. Letting it out would end [run] for the life of the process: the reading of
     * every source abandoned to protect a note about one of them. No material is lost by
     * swallowing it, because a batch that failed is already held and a batch that succeeded is
     * already written.
     *
     * **What is lost is the report, and it is said out loud.** When the store is wholly
     * unavailable the corpus writes stop too, so a reader sees freshness standing still. But a
     * failure specific to the state write — a constraint, a drifted schema — leaves the corpus
     * filling while every report is dropped, and nothing else in the system would mention it: the
     * adapter's own warning covers the reports it makes, not this one.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun report(source: Source, pass: Pass) {
        try {
            state.recordPass(source, pass)
        } catch (failure: Exception) {
            rethrowIfCancelled(failure)
            log.warn("Could not record a {} pass over {}; the reading continues.", pass, source, failure)
        }
    }

    /**
     * Honour a cancellation that is this scheduler's, and treat one that is not as a failure.
     *
     * A client library raising its own timeout inside `next()` or a write throws the same type,
     * and rethrowing that would end [run] with no report and no trace — a process that stopped
     * and said nothing. Our own cancellation is the one that leaves this coroutine inactive:
     * `withTimeout` cancels a child, so at this catch our job is still active and the timeout is
     * reported, while a cancellation of our own job reads inactive and is honoured.
     *
     * Two things it does not do. It reads the job's state rather than the exception's
     * provenance, so a library timeout arriving exactly as the process is stopping is rethrown
     * as if it were ours — harmless, since everything is stopping either way. And
     * `CoroutineContext.isActive` answers `true` when there is no job at all, which
     * `NonCancellable` also does: a caller that wrapped a sweep in `withContext(NonCancellable)`
     * would have a real cancellation reported as a source failure. Nothing does: the entry point
     * launches this loop into an ordinary job and cancels it from the shutdown hook.
     */
    private suspend fun rethrowIfCancelled(failure: Throwable) {
        if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
    }

    /**
     * What one turn did, which is what decides whether the stream keeps its place in the round.
     *
     * Three outcomes rather than a Boolean, because two of them used to be the same answer and
     * are not the same thing: a stream with nothing right now is fine and should be asked again,
     * while one that failed wants leaving alone until the next sweep.
     */
    private enum class Turn {
        /** A batch reached the corpus, so there may be more behind it. */
        STORED,

        /** Nothing was available right now, which is not the same as finished. */
        EMPTY,

        /** The ask or the write failed, and [idle] is the backoff. */
        FAILED,
    }

    companion object {
        /**
         * How long to wait between sweeps.
         *
         * The cadence that matters is the adapter's own -- it stays current on its change feed
         * and spends what is left of its budget on history -- so this mostly decides how often
         * it is offered the chance. It is also the floor under a failed stream's next turn,
         * which is a floor and not an interval: see the class comment.
         */
        val DEFAULT_IDLE: Duration = 1.minutes
    }
}
