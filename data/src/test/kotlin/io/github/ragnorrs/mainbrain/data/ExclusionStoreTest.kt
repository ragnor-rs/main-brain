package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Deriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The advisory lock, against a real Postgres.
 *
 * Asserted here rather than against a fake because every property worth having is the database's
 * and not this code's: that a second taker is refused rather than queued, that the two passes do
 * not block each other, and that a holder which failed does not keep it. A fake would assert that
 * the fake refuses.
 *
 * **Two exclusions over one [Database] stand in for two backend processes**, which is the shape
 * the lock exists for. They are two sessions because each pins its own connection out of the
 * pool, and that is the thing to be careful about: a session-level advisory lock is re-entrant
 * within the session that holds it, so a second taker landing on the *same* connection would be
 * granted the lock and the guard would be worth nothing. Nesting one inside the other is what
 * proves they do not — the outer holder is holding a connection, so the inner taker cannot be
 * handed the same one.
 *
 * **Whether the lock was released is read from [pg_locks] rather than by trying to take it
 * again**, and that is not fastidiousness. Re-entrancy makes the obvious form of the assertion
 * unsound in both directions: a leaked lock sits on a connection that goes back to the pool, so
 * the next taker is granted it whenever Hikari happens to hand out that same connection and
 * refused whenever it does not. The assertion would pass or fail on which connection came back.
 * Counting the locks the server actually holds asks the question directly, and it was a mutation
 * — dropping the release — that showed the difference: the sound form fails on it every time, and
 * the obvious one only sometimes.
 */
class ExclusionStoreTest {

    private val one = JdbcExclusion(StoreRig.database)
    private val other = JdbcExclusion(StoreRig.database)

    @Test
    fun `a second taker is refused while the first holds it`(): Unit = runBlocking {
        var second: Boolean? = null

        val first = one.whileHolding(Deriver.INDEX) {
            second = other.whileHolding(Deriver.INDEX) { error("the work of a refused taker ran") }
        }

        assertTrue(first, "The first taker did not get the exclusion.")
        assertEquals(false, second) {
            "A second process took an exclusion the first was holding. Both passes are " +
                "idempotent, so what this costs is not correctness but the model budget spent " +
                "twice on the same windows."
        }
    }

    @Test
    fun `the exclusion is held while the work runs and let go after it`(): Unit = runBlocking {
        var whileRunning = 0

        one.whileHolding(Deriver.INDEX) { whileRunning = locksOn(Deriver.INDEX) }

        assertEquals(1, whileRunning, "The work ran without the server holding the lock.")
        assertEquals(0, locksOn(Deriver.INDEX)) {
            "The exclusion was still held after its holder returned. A session-level lock " +
                "outlives a pooled connection's close — that hands the connection back rather " +
                "than ending the session — so nothing releases this but the release."
        }
    }

    @Test
    fun `a holder that failed does not keep it`(): Unit = runBlocking {
        // The case that would be permanent. A lock left behind by a failed sweep blocks that pass
        // for the life of the process — the connection goes back to the pool still holding it —
        // and the pass looks exactly like one with nothing to do.
        assertFailsWith<IllegalStateException> {
            one.whileHolding(Deriver.RECOGNITION) { error("the sidecar is down") }
        }

        assertEquals(0, locksOn(Deriver.RECOGNITION)) {
            "A failed sweep kept the exclusion. Nothing else would ever run that pass."
        }
    }

    /**
     * A sweep stopped by a signal releases what it held, which is the shutdown path.
     *
     * The release is in a `finally` and is a plain JDBC call rather than a suspending one, so it
     * runs while the coroutine is unwinding rather than being skipped as a suspension point
     * would be. Worth asserting separately from the failure case because the two arrive
     * differently: a failed sweep throws where a stopped one is cancelled, and a `finally`
     * written to catch only the first is the easy mistake.
     */
    @Test
    fun `a cancelled holder releases it`(): Unit = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val holder = launch {
            one.whileHolding(Deriver.INDEX) {
                holding.complete(Unit)
                awaitCancellation()
            }
        }
        holding.await()

        holder.cancelAndJoin()

        assertEquals(0, locksOn(Deriver.INDEX)) {
            "A sweep stopped mid-run kept its exclusion, so the pass would not run again until " +
                "the process died and Postgres dropped the session."
        }
    }

    @Test
    fun `the two passes do not block each other`(): Unit = runBlocking {
        // They are separate keys because they are separate work: indexing has no reason to wait
        // for an hour of inference, and the cadences are two orders of magnitude apart.
        var recognition: Boolean? = null

        one.whileHolding(Deriver.INDEX) {
            recognition = other.whileHolding(Deriver.RECOGNITION) { }
        }

        assertEquals(true, recognition) {
            "Holding the indexing exclusion blocked recognition, so the two keys are the same " +
                "number and one pass waits out the other."
        }
    }

    @Test
    fun `whatever the work throws reaches the caller`(): Unit = runBlocking {
        val failure = assertFailsWith<IllegalStateException> {
            one.whileHolding(Deriver.INDEX) { error("a batch failed") }
        }

        assertEquals("a batch failed", failure.message)
    }

    /**
     * One exclusion, taken and released over and over, stands in for a process left running.
     *
     * What this would catch is a hold that leaked — a connection returned to the pool still
     * locked, or a release that ran once for two takings. Either leaves a pass that worked for a
     * while and then silently stopped, which is the failure mode hardest to tell from a corpus
     * with nothing new in it. Sequential rather than nested, so the same key goes round each time.
     */
    @Test
    fun `taking and releasing the same exclusion repeatedly leaks nothing`(): Unit = runBlocking {
        repeat(SWEEPS) { sweep ->
            assertTrue(one.whileHolding(Deriver.RECOGNITION) { }) {
                "Sweep ${sweep + 1} could not take an exclusion the sweep before it released."
            }
        }

        assertEquals(0, locksOn(Deriver.RECOGNITION)) {
            "After $SWEEPS sweeps the server still holds an exclusion nobody is using. A " +
                "release that ran once for two takings counts down short, which is why this " +
                "goes round rather than taking it once."
        }
    }

    /**
     * How many advisory locks the server holds for this pass, right now.
     *
     * Read from `pg_locks` because that is the only place the answer is unambiguous — see the note
     * on the class. The `classid`/`objid` pair is the two-argument form's key, which is what
     * [JdbcExclusion] takes, and the numbers are restated here rather than reached for: a helper
     * that asked the code under test which key it uses would agree with it by construction, and a
     * mutation moving one would go unnoticed.
     */
    private suspend fun locksOn(deriver: Deriver): Int = StoreRig.database.query { connection ->
        connection.prepareStatement(
            """
            SELECT count(*) AS held
            FROM pg_locks
            WHERE locktype = 'advisory' AND classid = ? AND objid = ? AND granted
            """.trimIndent(),
        ).use { statement ->
            statement.bind {
                int(LOCK_CLASS)
                int(if (deriver == Deriver.INDEX) INDEX_KEY else RECOGNITION_KEY)
            }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt("held")
            }
        }
    }

    private companion object {
        /** Enough turns that a release counting down short would have left one behind. */
        const val SWEEPS = 8

        /** The key halves `JdbcExclusion` uses, written out independently of it. */
        const val LOCK_CLASS = 0x6D62
        const val INDEX_KEY = 1
        const val RECOGNITION_KEY = 2
    }
}
