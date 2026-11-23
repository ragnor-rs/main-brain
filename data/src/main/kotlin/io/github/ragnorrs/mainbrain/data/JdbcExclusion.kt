package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Exclusion
import java.sql.Connection

/**
 * The exclusion as a Postgres advisory lock, held on one pinned session.
 *
 * An advisory lock rather than a row, because the two fail differently and only one of them
 * fails safely. A row saying *this pass is running* has to be cleared by whoever wrote it, so a
 * process killed mid-sweep leaves one behind and the pass is blocked until somebody notices; the
 * usual repair is an expiry, which is a guess at how long a sweep may legitimately take. Postgres
 * releases an advisory lock when the session holding it ends, whatever ended it — so a killed
 * container releases its own lock and the next sweep proceeds, with nothing to time out and
 * nothing to clear by hand.
 *
 * **Session scope, not transaction scope**, which is why this needs a pinned connection. A sweep
 * spans many transactions through many repositories, each borrowing its own connection, so there
 * is no one transaction for a lock to live in — `pg_advisory_xact_lock` would be released by the
 * first commit, a fraction of a second into a run that lasts minutes.
 *
 * The cost is one connection held idle per running pass, which is why [Database.DEFAULT_POOL_SIZE]
 * says it covers the work and not the pins, and why whoever opens the store adds to it.
 */
class JdbcExclusion(private val database: Database) : Exclusion {

    override suspend fun whileHolding(deriver: Deriver, work: suspend () -> Unit): Boolean =
        database.pinned { session ->
            if (!session.take(deriver)) {
                false
            } else {
                try {
                    work()
                } finally {
                    // In a `finally` so that a failed sweep releases it, and a cancelled one
                    // too: this is a plain JDBC call rather than a suspending one, so it runs
                    // during cancellation unwinding rather than being skipped. A release that
                    // itself fails leaves the session carrying the lock, which `pinned` answers
                    // by evicting the connection instead of pooling it.
                    session.release(deriver)
                }
                true
            }
        }

    /** `true` if this session now holds the lock, `false` if somebody else does. */
    private fun Connection.take(deriver: Deriver): Boolean = ask(TAKE, deriver)

    private fun Connection.release(deriver: Deriver) {
        // The answer is discarded deliberately. `pg_advisory_unlock` reports `false` for a lock
        // this session did not hold, which cannot happen here — the release is in the `finally`
        // of the branch that took it — and a session that somehow did not hold it is a session
        // with nothing to release.
        ask(RELEASE, deriver)
    }

    private fun Connection.ask(sql: String, deriver: Deriver): Boolean =
        prepareStatement(sql).use { statement ->
            statement.bind {
                int(LOCK_CLASS)
                int(deriver.lockKey())
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }

    private companion object {

        /**
         * The first half of every key this build takes, so that nothing else sharing the database
         * can collide with one by accident.
         *
         * Advisory locks are one flat namespace across the whole server — no table, no schema,
         * no relation to anything — so two unrelated users of the two-argument form collide
         * whenever both halves match. The pair is this build's, and the second half names which
         * pass.
         */
        const val LOCK_CLASS = 0x6D62

        const val TAKE = "SELECT pg_try_advisory_lock(?, ?)"

        const val RELEASE = "SELECT pg_advisory_unlock(?, ?)"

        /**
         * Which pass a key names, written out rather than taken from the enum's ordinal.
         *
         * An ordinal is a property of the declaration order, so reordering the enum — or
         * inserting a pass between two of them — would silently renumber the locks. During a
         * rolling restart that means an old process and a new one holding what each thinks is the
         * same pass under two different keys, which is the double-run this exists to prevent,
         * arriving by way of a tidy-up. A `when` over the enum also fails to compile when a pass
         * is added, which is the right moment to choose its number.
         */
        // The numbers are the data here, not a quantity computed from something else: this is the
        // table mapping a pass to its lock key, and naming each one would put the same literal one
        // line further away behind an identifier that says nothing the branch does not.
        @Suppress("MagicNumber")
        fun Deriver.lockKey(): Int = when (this) {
            Deriver.INDEX -> 1
            Deriver.RECOGNITION -> 2
            Deriver.ADMISSION -> 3
        }
    }
}
