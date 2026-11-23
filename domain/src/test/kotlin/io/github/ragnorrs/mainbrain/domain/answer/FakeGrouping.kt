package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * A [Grouping] that places the records a test told it about, and records what it was asked.
 *
 * It ships beside its port for the reason [FakeRetrieval] does: every generating shape assembles
 * through this, and a fake per test would be several chances to disagree about what absence means.
 *
 * **What it is asked matters as much as what it answers**, which is why [asked] exists. Assembly is
 * meant to skip the read entirely for a shape that does not section, and a fake that only recorded
 * its answers would let that read happen on every draft with nothing to show it.
 */
class FakeGrouping(private val keys: Map<ExternalId, GroupingKey> = emptyMap()) : Grouping {

    /** The records each call asked about, in order. */
    val asked = mutableListOf<List<ExternalId>>()

    /**
     * Whatever of [keys] was asked for.
     *
     * Filtered rather than returned whole, because a record this knows nothing about has to be
     * missing from the result — that absence is the port's way of saying no body of work could be
     * determined, and a fake that answered for records nobody asked about could not express it.
     */
    override suspend fun of(records: List<ExternalId>): Map<ExternalId, GroupingKey> {
        asked += records
        return keys.filterKeys { it in records }
    }
}
