package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

// What the derived writes share: the rows that point at a span — a statement's evidence and the two
// things a model read out of one — and what a content-keyed upsert answers with. Everything here is
// `internal`, like every other SQL-shaped thing in this module.

/**
 * The column every content-keyed upsert in this module returns beside its key, and how it is read.
 *
 * **`xmax = 0` is how a row the statement inserted is told from one it replaced**, and it is read
 * off the row rather than inferred from the caller's argument. `RETURNING` yields the new tuple: on
 * the insert path nothing has locked it and its `xmax` is zero, while on the `ON CONFLICT DO UPDATE`
 * path this transaction locked the conflicting row before updating it and the lock travels onto the
 * version returned. The alternative — comparing the returned key against the one the caller offered
 * — would be a figure that depended on the caller having minted a fresh identifier, which is a rule
 * nothing states and no store could enforce.
 *
 * Written out in each statement rather than substituted in from here, for the reason
 * `JdbcStatementRepository.UPSERT_RECOLLAPSED` gives about deriving one statement from another: a
 * clause assembled from a constant is one a reader cannot see in the SQL they are reading. So this
 * names the column for the readers and nothing couples it to the four statements that write it —
 * what holds the two in step is the store tests, which read the figures back against real Postgres
 * and fail on the column being absent.
 */
internal const val INSERTED = "inserted"

/**
 * A row a content-keyed upsert returned: which one it is, and whether this write inserted it.
 *
 * Shared by the two finding tables, which are keyed by a claim and identified by a UUID. Statements
 * carry a surrogate key instead and read the same column into their own pair, so this is not
 * generic over the key type — one type parameter to spare one small class is a worse trade than the
 * second reader.
 */
internal class Upserted(val id: UUID, val inserted: Boolean)

/**
 * The four columns a span's address occupies, bound in the order every statement here declares
 * them.
 *
 * One function rather than four calls at each site, because the tuple appears in three tables and
 * a reader that has seen it once should not have to check the order again. The ordinal is bound as
 * a long against an `integer` column, which the driver narrows — the values are span positions, so
 * there is nothing near the edge of either type.
 */
internal fun Binder.span(span: SpanId) {
    table(RecordTable.of(span.kind))
    externalId(span.record)
    long(span.ordinal.toLong())
}

/**
 * The spans of [evidence] as four parallel arrays, for a statement joining against `unnest`.
 *
 * Parallel arrays rather than a placeholder per span, because the number of them is the size of
 * the batch: a statement built per call would be a new query plan each time. The ordinals go over
 * as text and the statement casts the array to `int[]`, since the binder has one array type and
 * adding a second for a column that is always small would be a mapping to keep in step for
 * nothing.
 */
internal fun PreparedStatement.bindSpans(evidence: List<Evidence>) = bind {
    val spans = evidence.map { it.span }.distinct()
    strings(spans.map { RecordTable.of(it.kind).table })
    strings(spans.map { it.record.source.name })
    strings(spans.map { it.record.nativeId })
    strings(spans.map { it.ordinal.toString() })
}

/** A span's address and its source time, from the columns these tables share. */
internal fun ResultSet.toEvidence() = Evidence(
    span = toSpanId(),
    sourceTime = requireNotNull(instant("source_time")),
)

/**
 * The findings these rows describe, one per identity with all of its evidence gathered onto it.
 *
 * A finding joined to its evidence arrives as one row per span, so the rows are grouped by the
 * finding's own identifier and the spans collected into the set the entity requires. The same shape
 * `toStatements` uses, and for the same reason: the join is what makes it one query rather than
 * one per finding.
 */
internal fun ResultSet.toCommitments(): List<Commitment> =
    collect { row -> row.getString("id") to (row.toCommitmentClaim() to row.toEvidence()) }
        .groupBy({ (id, _) -> id }, { (_, parts) -> parts })
        .map { (_, rows) -> rows.first().first.with(rows.map { (_, evidence) -> evidence }.toSet()) }

internal fun ResultSet.toEvents(): List<Event> =
    collect { row -> row.getString("id") to (row.toEventClaim() to row.toEvidence()) }
        .groupBy({ (id, _) -> id }, { (_, parts) -> parts })
        .map { (_, rows) -> rows.first().first.with(rows.map { (_, evidence) -> evidence }.toSet()) }

/**
 * Everything on a commitment's own row, before its evidence has been gathered.
 *
 * Internal rather than private because the closure pass's selection reads the same columns and
 * gathers the evidence itself: its statement bounds the commitments before joining their spans, so
 * it cannot use [toCommitments] and would otherwise need a second mapper of the same columns.
 */
// One parameter per column of the row, which is the whole of what this is. Grouping some of them
// would be a second shape to keep in step with the projection, for a constructor nothing calls by
// hand.
@Suppress("LongParameterList")
internal class CommitmentClaim(
    val id: EntityId,
    val claim: String,
    val conversation: ExternalId,
    val hedged: Boolean,
    val due: Instant?,
    val statedBy: EntityId?,
    val obligedParty: EntityId?,
) {
    fun with(evidence: Set<Evidence>) = Commitment(
        id = id,
        claim = claim,
        conversation = conversation,
        evidence = evidence,
        hedged = hedged,
        due = due,
        statedBy = statedBy,
        obligedParty = obligedParty,
    )
}

/** Everything on an event's own row, before its evidence has been gathered. */
private class EventClaim(
    val id: EntityId,
    val claim: String,
    val conversation: ExternalId,
    val hedged: Boolean,
    val occursAt: Instant?,
    val statedBy: EntityId?,
) {
    fun with(evidence: Set<Evidence>) = Event(
        id = id,
        claim = claim,
        conversation = conversation,
        evidence = evidence,
        hedged = hedged,
        occursAt = occursAt,
        statedBy = statedBy,
    )
}

internal fun ResultSet.toCommitmentClaim() = CommitmentClaim(
    id = requireNotNull(entityId("id")),
    claim = getString("claim"),
    conversation = requireNotNull(externalId("conversation_source", "conversation_native_id")),
    hedged = getBoolean("hedged"),
    due = instant("due_at"),
    statedBy = entityId("stated_by"),
    obligedParty = entityId("obliged_party"),
)

private fun ResultSet.toEventClaim() = EventClaim(
    id = requireNotNull(entityId("id")),
    claim = getString("claim"),
    conversation = requireNotNull(externalId("conversation_source", "conversation_native_id")),
    hedged = getBoolean("hedged"),
    occursAt = instant("occurs_at"),
    statedBy = entityId("stated_by"),
)

/**
 * The span address on this row.
 *
 * The kind is stored as the table's name and read back as the record kind, which is the one
 * mapping [RecordTable] exists to keep from drifting.
 */
private fun ResultSet.toSpanId() = SpanId(
    kind = RecordKind.valueOf(getString("record_kind").uppercase()),
    record = requireNotNull(externalId("record_source", "record_native_id")),
    ordinal = getInt("ordinal"),
)
