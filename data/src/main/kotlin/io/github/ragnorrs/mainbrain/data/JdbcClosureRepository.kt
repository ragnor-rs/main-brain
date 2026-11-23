package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.ClosureCandidate
import io.github.ragnorrs.mainbrain.domain.entity.ClosureRepository
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/**
 * Closure verdicts over Postgres, and the commitments still owed one.
 *
 * **The verdict row is the watermark, so selection reads this table rather than `derivation`.** The
 * port says why that is: a commitment has no source, no native identifier and no content time, and
 * what makes a closure owed again is that its conversation holds material no verdict has weighed.
 * So that table's ways of being owed are replaced by this pass's own — no verdict, a verdict from
 * another version, or a verdict that closed nothing with enough material it never weighed to be
 * worth another call — and [OWED] is where they are written down once for both the selection and
 * the count.
 */
class JdbcClosureRepository(private val database: Database) : ClosureRepository {

    @Suppress("LongParameterList")
    override suspend fun owed(
        version: String,
        commitments: Int,
        window: Int,
        examine: Duration,
        rearm: Int,
        now: Instant,
    ): List<ClosureCandidate> {
        require(commitments > 0) { "A batch of $commitments commitments examines nothing." }
        require(window > 0) { "A window of $window messages holds no material." }
        require(rearm > 0) { "A re-arm floor of $rearm messages puts no floor on re-examination." }
        return database.query { connection ->
            // The candidates first and their windows after, which is a round trip per candidate
            // against a batch bounded at a couple of dozen. **One query would have to choose
            // between the two**: joined to its evidence a commitment arrives as a row per span, and
            // joined to its window as a row per message, so one statement doing both multiplies the
            // two and a partial evidence set would make the entity say something false about itself
            // — which is the rule `JdbcCommitmentRepository` already states for the same reason.
            connection.readOwed(version, commitments, examine, rearm, now).map { owed ->
                ClosureCandidate(
                    commitment = owed.commitment,
                    later = connection.readLater(owed, window, examine),
                    previously = owed.previously,
                )
            }
        }
    }

    override suspend fun owedCount(version: String, examine: Duration, rearm: Int, now: Instant): Int {
        require(rearm > 0) { "A re-arm floor of $rearm messages puts no floor on re-examination." }
        return database.query { connection ->
            connection.prepareStatement(COUNT_OWED).use { statement ->
                statement.bind {
                    string(version)
                    seconds(examine)
                    // The floor stops the inner count; the sweep's instant and the horizon decide
                    // what that count is then compared against, which is one once the horizon has
                    // closed and the floor while it is open.
                    int(rearm)
                    instant(now)
                    seconds(examine)
                    int(rearm)
                }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt("owed")
                }
            }
        }
    }

    override suspend fun record(closures: List<Closure>) {
        if (closures.isEmpty()) return
        // A transaction because a batch of verdicts means one thing: half of them written would
        // leave the rest to be paid for again, with nothing saying which half.
        database.transaction { connection ->
            connection.prepareStatement(UPSERT).use { statement ->
                closures.forEach { closure ->
                    statement.bind {
                        uuid(closure.commitment.value)
                        closure.closedBy.let { closing ->
                            if (closing == null) {
                                // A verdict that closed nothing carries no span, and the whole span
                                // is absent rather than partly written — the table's own constraint
                                // says so, because columns carrying one meaning between them would
                                // otherwise admit a citation pointing at nothing.
                                string(null)
                                string(null)
                                string(null)
                                string(null)
                                instant(null)
                            } else {
                                table(RecordTable.of(closing.span.kind))
                                externalId(closing.span.record)
                                string(closing.span.ordinal.toString())
                                instant(closing.sourceTime)
                            }
                        }
                        instant(closure.weighed.at)
                        string(closure.weighed.record.source.name)
                        string(closure.weighed.record.nativeId)
                        string(closure.decidedBy)
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /** The commitments owed an examination, with what is known about where to read from. */
    private fun Connection.readOwed(
        version: String,
        commitments: Int,
        examine: Duration,
        rearm: Int,
        now: Instant,
    ): List<Owed> =
        prepareStatement(SELECT_OWED).use { statement ->
            statement.bind {
                // Three times, and the order is the statement's rather than the reader's: the two
                // `CASE` arms sit in the projection, which precedes the `WHERE` the third is in.
                string(version)
                string(version)
                string(version)
                seconds(examine)
                // The floor, the sweep's instant and the horizon again, in the order the clauses
                // appear in rather than the order a reader would group them — see `owedCount`.
                int(rearm)
                instant(now)
                seconds(examine)
                int(rearm)
                int(commitments)
            }
            statement.executeQuery().use { rows ->
                rows.collect { row ->
                    OwedRow(
                        conversation = row.getLong("conversation_id"),
                        statedAt = requireNotNull(row.instant("stated_at")),
                        readFrom = requireNotNull(row.instant("read_from")),
                        readFromId = requireNotNull(row.getString("read_from_id")),
                        previously = row.instant("weighed_at")?.let { at ->
                            Weighed(at, ExternalId(Source.TELEGRAM, requireNotNull(row.getString("weighed_id"))))
                        },
                        commitment = row.getString("id"),
                    ) to (row.toCommitmentClaim() to row.toEvidence())
                }
            }
        }.groupBy({ (row, _) -> row }, { (_, parts) -> parts })
            .map { (row, parts) ->
                Owed(
                    conversation = row.conversation,
                    statedAt = row.statedAt,
                    readFrom = row.readFrom,
                    readFromId = row.readFromId,
                    previously = row.previously,
                    commitment = parts.first().first.with(parts.map { (_, evidence) -> evidence }.toSet()),
                )
            }

    /**
     * The stretch of a candidate's conversation this examination weighs.
     *
     * From where the last verdict stopped — or from the promise itself where there was none — up to
     * the horizon's end, oldest first and bounded by a count. **Oldest first and not newest**,
     * unlike every other message read in the build: a closure is looked for forwards from the
     * promise, and reading the newest first would weigh last month's conversation before the reply
     * that came an hour after the promise was made.
     *
     * A withdrawn message is left out, as it is everywhere else a message is read for a model:
     * quoting one back is what the withdrawal is recorded to prevent, and citing one as the thing
     * that closed a promise is the same fault with a verdict attached to it.
     */
    private fun Connection.readLater(owed: Owed, window: Int, examine: Duration): List<Message> =
        prepareStatement(SELECT_LATER).use { statement ->
            statement.bind {
                long(owed.conversation)
                instant(owed.readFrom)
                string(owed.readFromId)
                instant(owed.statedAt)
                seconds(examine)
                int(window)
            }
            statement.executeQuery().use { rows -> rows.collect { it.toMessage() } }
        }

    /** A commitment owed an examination, and where in its conversation to read from. */
    private class Owed(
        val conversation: Long,
        val statedAt: Instant,
        val readFrom: Instant,
        val readFromId: String,
        val previously: Weighed?,
        val commitment: Commitment,
    )

    /** The same, before the evidence rows have been gathered onto the commitment. */
    private data class OwedRow(
        val conversation: Long,
        val statedAt: Instant,
        val readFrom: Instant,
        val readFromId: String,
        val previously: Weighed?,
        val commitment: String?,
    )

    private companion object {

        /**
         * What it means to be owed a closure examination, written once and read by both statements.
         *
         * This pass's own ways of being owed, which answer `DerivationStateRepository`'s without
         * being any of them:
         *
         *  * no verdict at all — never examined;
         *  * a verdict from another version, which a moved prompt or a moved model retires;
         *  * a verdict that closed nothing, with material in its conversation it never weighed —
         *    at least the floor's worth while the horizon is open, any at all once it has closed.
         *
         * **The third is narrowed to a verdict that closed nothing, and that is what keeps a settled
         * promise settled.** A closure already found is not paid for again whatever arrives
         * afterwards: a promise is discharged once, and later material cannot undo it.
         *
         * **The horizon and the floor bound the third and not the first two.** The horizon is per
         * commitment rather than a filter on which commitments are eligible: a promise whose horizon
         * is long past is still examined once if it never was — the archive is backfilled, so most
         * of the corpus is older than any sane bound — and what it stops is the re-reading. Where
         * that examination finds no material at all the pass settles the commitment without a call,
         * which is how an old promise leaves the queue. The floor is why a re-examination costs what
         * its material costs: counted rather than tested for existence, so one new message no longer
         * buys a call billed almost entirely for the instruction and the claim — and it lapses at
         * the horizon's end, so what it costs is latency and never a message nothing weighed.
         * Neither applies to a first reading, which would otherwise leave a quiet conversation with
         * no verdict at all.
         *
         * Shared between the selection and the count for the reason the port states: the driver
         * reads a fall in the count as progress, so a count standing apart from the selection would
         * stop a pass with work outstanding or never let one finish. The conversation is joined
         * rather than read from the commitment's own columns, because the message table is keyed by
         * the conversation's row and the join is what both halves need.
         */
        val OWED = """
            FROM commitment c
            JOIN conversation v
              ON v.source = c.conversation_source
             AND v.native_id = c.conversation_native_id
            CROSS JOIN LATERAL (
                -- The promise's own span, which is where the window starts. Ordered the way the
                -- window is read, so the pair this returns is the one a resume compares against.
                SELECT e.source_time AS stated_at, e.record_native_id AS stated_native_id
                FROM commitment_evidence e
                WHERE e.commitment = c.id
                ORDER BY e.source_time, e.record_native_id
                LIMIT 1
            ) s
            LEFT JOIN commitment_closure cc ON cc.commitment = c.id
            WHERE c.superseded_by IS NULL
              AND (
                    cc.commitment IS NULL
                 OR cc.decided_by <> ?
                 OR (cc.record_source IS NULL
                     AND (
                         SELECT count(*)
                         FROM (
                             SELECT 1
                             FROM message m
                             WHERE m.conversation_id = v.id
                               AND m.withdrawn_at IS NULL
                               AND m.body IS NOT NULL
                               AND m.body <> ''
                               AND (m.source_time, m.native_id) > (cc.considered_through, cc.considered_native_id)
                               AND m.source_time <= s.stated_at + make_interval(secs => ?::double precision)
                             -- Stops the count at the floor, so this asks *are there at least this
                             -- many* rather than *how many are there*. A conversation with hundreds
                             -- of unweighed messages is as cheap to judge as one with five.
                             LIMIT ?
                         ) unweighed
                     )
                     -- **The floor defers and never drops, which is what this CASE buys.** A window
                     -- is bounded by a count, so a conversation holding more than fits is read in
                     -- instalments and the last one is the remainder — under the floor one time in
                     -- every window. Gated on the floor alone that remainder would be weighed by no
                     -- sweep ever, the closing message possibly among it, which is the outcome the
                     -- watermark is an ordered pair to prevent. So the floor holds only while more
                     -- material may still arrive: once the sweep's instant is past the horizon
                     -- measured from this promise, nothing more can, and one is enough to bring the
                     -- commitment back for a last examination that settles it.
                     >= CASE
                          WHEN ? > s.stated_at + make_interval(secs => ?::double precision) THEN 1
                          ELSE ?
                        END)
              )
        """.trimIndent()

        /**
         * The next commitments to examine, oldest promise first, with all of each one's evidence.
         *
         * Oldest by when the promise was stated, so an interrupted run makes the same progress
         * rather than revisiting whatever the store returned first; the identifier breaks a tie so
         * the order is total. The bound is applied before the evidence join, because a `LIMIT` over
         * the joined rows would cut a commitment's spans in half and hand back a finding that
         * understated its own evidence.
         *
         * `read_from` is where the window starts: the last verdict's watermark where there is one,
         * and the promise itself where there is not. Projected here rather than decided by the
         * caller, so what is read next is a function of what was recorded as considered.
         */
        val SELECT_OWED = """
            SELECT owed.id, owed.conversation_id, owed.stated_at,
                   owed.read_from, owed.read_from_id, owed.weighed_at, owed.weighed_id,
                   owed.claim, owed.conversation_source, owed.conversation_native_id,
                   owed.hedged, owed.due_at, owed.stated_by, owed.obliged_party,
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            FROM (
                SELECT c.id, v.id AS conversation_id, s.stated_at,
                       -- **Where to resume, which is not the same question as whether to.** A
                       -- verdict another version reached is retired, and retiring one means reading
                       -- the commitment again from the promise: its predecessor's mark stands after
                       -- the message that closed it, so resuming there would hand the new
                       -- instruction a window with the closure cut out of it, and the only answer
                       -- available would be that nothing closed the promise. That answer would then
                       -- replace a closure this build had found and paid for.
                       CASE WHEN cc.commitment IS NULL OR cc.decided_by <> ?
                            THEN s.stated_at ELSE cc.considered_through END AS read_from,
                       CASE WHEN cc.commitment IS NULL OR cc.decided_by <> ?
                            THEN s.stated_native_id ELSE cc.considered_native_id END AS read_from_id,
                       -- The mark being replaced, carried so that the new verdict cannot claim to
                       -- have weighed less than its predecessor did.
                       cc.considered_through AS weighed_at,
                       cc.considered_native_id AS weighed_id,
                       c.claim, c.conversation_source, c.conversation_native_id,
                       c.hedged, c.due_at, c.stated_by, c.obliged_party
                $OWED
                ORDER BY s.stated_at, c.id
                LIMIT ?
            ) owed
            JOIN commitment_evidence e ON e.commitment = owed.id
            ORDER BY owed.stated_at, owed.id, e.source_time
        """.trimIndent()

        /** The same definition, counted and unbounded. */
        val COUNT_OWED = """
            SELECT count(*) AS owed
            $OWED
        """.trimIndent()

        /**
         * The window: from where the last examination stopped to the end of the horizon.
         *
         * The two bounds come from different places on purpose. The lower one is the watermark, so
         * a re-examination weighs what the last one did not; the upper is the horizon measured from
         * the promise, so the stretch read never runs past where this pass stops looking.
         */
        val SELECT_LATER = """
            SELECT m.source, m.native_id, m.author_source, m.author_native_id, m.body, m.source_time
            FROM message m
            WHERE m.conversation_id = ?
              AND m.withdrawn_at IS NULL
              AND m.body IS NOT NULL
              AND m.body <> ''
              -- **Compared as a pair, because the window is ordered as a pair.** A source's
              -- instants are coarse enough that several messages of one conversation share a
              -- second; resuming on the instant alone steps over the rest of whichever second the
              -- last window ended inside, and no later sweep ever comes back for them.
              AND (m.source_time, m.native_id) > (?, ?)
              AND m.source_time <= ? + make_interval(secs => ?::double precision)
            ORDER BY m.source_time, m.native_id
            LIMIT ?
        """.trimIndent()

        /**
         * Write the verdict, replacing whatever that commitment had.
         *
         * In place, because a history of verdicts is a second thing to read and nothing reads it.
         * Every column is replaced together: a re-examination that kept the old watermark beside a
         * new verdict would say the new one had weighed material it never saw.
         */
        val UPSERT = """
            INSERT INTO commitment_closure (
                commitment, record_kind, record_source, record_native_id, ordinal, record_source_time,
                considered_through, considered_source, considered_native_id, decided_by
            )
            VALUES (?, ?, ?, ?, ?::int, ?, ?, ?, ?, ?)
            ON CONFLICT (commitment) DO UPDATE SET
                record_kind = EXCLUDED.record_kind,
                record_source = EXCLUDED.record_source,
                record_native_id = EXCLUDED.record_native_id,
                ordinal = EXCLUDED.ordinal,
                record_source_time = EXCLUDED.record_source_time,
                considered_through = EXCLUDED.considered_through,
                considered_source = EXCLUDED.considered_source,
                considered_native_id = EXCLUDED.considered_native_id,
                decided_by = EXCLUDED.decided_by,
                decided_at = now()
        """.trimIndent()
    }
}
