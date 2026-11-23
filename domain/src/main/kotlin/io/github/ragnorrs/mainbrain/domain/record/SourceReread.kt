package io.github.ragnorrs.mainbrain.domain.record

/**
 * The only way a re-read is requested, and the host is its only caller.
 *
 * Separate from [SourceStream] rather than a third method on it, because **the scheduler must
 * not be able to trigger one**: a port carrying a method only one of its callers uses is two
 * ports wearing one name, and here the consequence of merging them is a crash loop that becomes
 * a re-backfill loop walking into the limits this system exists to stay under. One adapter
 * satisfies both ports; the scheduler is handed only the first.
 *
 * Re-reading has to stay available because none of what makes it necessary is detectable from
 * inside an adapter: a field it never captured and now needs, a synthesised position that
 * skipped a page, a conversation excluded yesterday and wanted today. A corpus that has simply
 * fallen behind the source is **not** on that list — an adapter detects that one against the
 * source itself and closes it without being asked, which is a different operation under a
 * different bound and never reaches this port.
 *
 * **Named for reading, because that is the whole of what it does.** It adds: re-read records
 * upsert onto what is there and content time stays put where nothing changed, so the only cost
 * is request budget — which is the expensive one, and the reason this is never automatic. A
 * name suggesting it forgets or discards would be a name nobody reaches for to repair a hole,
 * which is the one thing it is for.
 */
interface SourceReread {

    /**
     * Read [unit] again from its newest message back, or the whole source when it is `null`.
     *
     * The adapter honours this by forgetting where it had got to in that unit, not by removing
     * anything. It refuses a unit it never reported with [UnknownWorkUnit], because the
     * alternative — accepting a name it cannot act on — reports a re-read that will not happen.
     */
    suspend fun reread(unit: WorkUnitId?)
}

/**
 * An adapter's own name for one unit of its work: for a conversational source, one conversation.
 *
 * Deliberately the adapter's own, never a scope invented for all sources. A conversation means
 * nothing to a document-only source, so a shared vocabulary would force implementations to
 * reject arguments they cannot honour. What makes this usable from outside is that the units an
 * adapter reports are the units it accepts back — a caller names one of those, or nothing at
 * all, and needs to know nothing else about how the source is shaped.
 */
@JvmInline
value class WorkUnitId(val value: String) {
    init {
        require(value.isNotBlank()) { "A work unit's identifier cannot be blank: it is how a re-read is scoped." }
    }
}

/**
 * Thrown when a re-read names a unit its adapter never reported.
 *
 * Part of the port's contract rather than an implementation detail. A caller that passed a name
 * from somewhere else — a stale list, another source's units, a conversation the adapter has
 * never enumerated — has to be told, because the one thing that must not happen is a re-read
 * reported as requested and never performed.
 */
class UnknownWorkUnit(val unit: WorkUnitId) : IllegalArgumentException(
    "No work unit ${unit.value} was reported by this source. A re-read is scoped to a unit the " +
        "adapter itself reported, or to the whole source when no unit is named.",
)
