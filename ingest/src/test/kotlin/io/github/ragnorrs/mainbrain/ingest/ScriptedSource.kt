package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.SourceReread
import io.github.ragnorrs.mainbrain.domain.record.SourceStream
import io.github.ragnorrs.mainbrain.domain.record.UnknownWorkUnit
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId

/**
 * A source adapter made of a script, satisfying both ports as a real one does.
 *
 * This is the fixture the source-adapter tests reuse, so what it models is the *contract* rather
 * than any one platform:
 *
 * **It owns its position, and advances it only when asked again.** A batch handed over and not
 * acknowledged is handed over again, which is what makes an interrupted run lose nothing. A
 * fixture that advanced on handing over would make the scheduler's holding of a batch untestable
 * and would quietly agree with a scheduler that skipped one.
 *
 * **It never reports itself finished.** An empty script, or one read to the end, answers nothing
 * available right now -- the state a real adapter is in between change-feed events.
 *
 * **Its work units are its own.** A re-read is scoped to a unit it reported, and one it never
 * reported is refused rather than accepted and ignored.
 */
internal class ScriptedSource(
    override val source: Source = Source.TELEGRAM,
    script: Map<WorkUnitId, List<Batch>> = emptyMap(),
    /**
     * How far through each unit this adapter has got. Its own, and not exposed through a port.
     *
     * Taken as a parameter because a real position outlives the process holding it -- a client
     * library's own store, a token on disk. Handing the same one to a second instance is how a
     * test models a restart, and it is what makes the acknowledgement contract assertable: an
     * adapter that advanced this on handing a batch over rather than on being asked again would
     * skip the batch an interrupted run never stored.
     */
    private val position: MutableMap<WorkUnitId, Int> = mutableMapOf(),
    /**
     * How many of the first asks answer nothing before the script begins.
     *
     * A change feed with nothing on it right now, which is the ordinary state of an incremental
     * stream. It is what lets a test tell "nothing available" apart from "finished" at the
     * scheduler's level, and what makes starvation visible: a stream that is quiet on its first
     * ask and has material after it is exactly the one a sweep can leave behind.
     */
    private val quietAsks: Int = 0,
) : SourceStream, SourceReread {

    private val script: MutableMap<WorkUnitId, MutableList<Batch>> =
        script.mapValuesTo(mutableMapOf()) { (_, batches) -> batches.toMutableList() }

    /** The unit whose last batch is out with the scheduler and not yet acknowledged. */
    private var unacknowledged: WorkUnitId? = null

    /** Thrown by the next ask, once, and then cleared. Nothing else makes this fixture fail. */
    var fault: Exception? = null

    /** How many times the scheduler has asked, which is how a test sees that it did not skip. */
    var asks: Int = 0
        private set

    override suspend fun next(): Batch? {
        acknowledge()
        asks++
        fault?.let {
            fault = null
            throw it
        }
        val unit = if (asks <= quietAsks) {
            null
        } else {
            script.keys.firstOrNull { reached(it) < script.getValue(it).size }
        }
        // Null when there is nothing to hand over, which is also nothing to acknowledge.
        unacknowledged = unit
        return unit?.let { script.getValue(it)[reached(it)] }
    }

    override suspend fun reread(unit: WorkUnitId?) {
        if (unit == null) {
            position.clear()
            unacknowledged = null
            return
        }
        if (unit !in script) throw UnknownWorkUnit(unit)
        position -= unit
        if (unacknowledged == unit) unacknowledged = null
    }

    /** Make new material available on this unit, as a change feed does between asks. */
    fun offer(unit: WorkUnitId, batch: Batch) {
        script.getOrPut(unit) { mutableListOf() } += batch
    }

    /**
     * Being asked again is the acknowledgement that the last batch was stored, so this is where
     * the position moves.
     */
    private fun acknowledge() {
        unacknowledged?.let { position[it] = reached(it) + 1 }
        unacknowledged = null
    }

    private fun reached(unit: WorkUnitId): Int = position[unit] ?: 0
}
