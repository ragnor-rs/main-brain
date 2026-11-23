package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Signal
import java.sql.ResultSet
import java.util.UUID

// Reading the identity tables back. `internal`, like every other SQL-shaped thing here.

internal fun ResultSet.entityId(column: String): EntityId? =
    getObject(column, UUID::class.java)?.let { EntityId(it) }

internal fun ResultSet.toContact() = Contact(
    id = requireNotNull(entityId("id")),
    record = requireNotNull(externalId("record_source", "record_native_id")),
    displayName = getString("display_name"),
)

internal fun ResultSet.toClient() = Client(
    id = requireNotNull(entityId("id")),
    conversation = requireNotNull(externalId("record_source", "record_native_id")),
    name = getString("name"),
)

internal fun ResultSet.toLink() = MergeLink(
    merged = requireNotNull(entityId("merged")),
    canonical = requireNotNull(entityId("canonical")),
    score = getDouble("score"),
    signals = storedSignals("signals"),
)

/**
 * A kept pair, read back in the order it was stored.
 *
 * Through the constructor rather than through `CandidateMerge.of`: the row is already ordered —
 * the table has a `CHECK` saying so — and re-ordering it here would hide a row that had got in
 * the wrong way round rather than failing on it.
 */
internal fun ResultSet.toCandidate() = CandidateMerge(
    one = requireNotNull(entityId("one")),
    other = requireNotNull(entityId("other")),
    score = getDouble("score"),
    signals = storedSignals("signals"),
)

/** A `text[]` column read back as the signals it holds. */
private fun ResultSet.storedSignals(column: String): List<Signal> {
    val stored = getArray(column)?.array as? Array<*> ?: return emptyList()
    return stored.filterIsInstance<String>().toSignals()
}
