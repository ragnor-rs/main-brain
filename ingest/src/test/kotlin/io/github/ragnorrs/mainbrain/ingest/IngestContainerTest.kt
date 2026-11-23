package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.ingest.config.IngestConfig
import io.github.ragnorrs.mainbrain.ingest.config.StoreConfig
import io.github.ragnorrs.mainbrain.ingest.source.telegram.TelegramLimits
import io.github.ragnorrs.mainbrain.ingest.source.telegram.TelegramSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a switched-off deployment does, asserted without a Telegram account and without a store.
 *
 * Both absences are deliberate, and they are absent for different reasons.
 *
 * **No Telegram account, because what the process does not touch is the claim.** The client
 * library locks its own session directory, so the one thing a switched-off deployment owes the
 * machine is that directory — and a test handed a real session could not tell a process that left
 * it alone from one that took it.
 *
 * **No store, because the store is how both paths are told apart.** It is handed in as a value
 * that records being asked for and then fails, which gives every case here a loud answer instead
 * of a slow one: the switched-on path throws where the switched-off path parks, and a switch that
 * were ignored, or read after the store was opened, fails rather than times out.
 *
 * The limit worth naming: a store that refuses to open cannot say what was written through it, so
 * these assert that the report was *attempted*, not what it said. The kind and the note are read
 * off the real store by `:data`'s own tests.
 */
class IngestContainerTest {

    /** Long enough for the switch to be answered, short enough that a parked process is quick. */
    private val brief = 200.milliseconds

    /** Set by the store's initialiser, so a test can see that something asked for a connection. */
    private val asked = AtomicBoolean(false)

    private fun container(ingesting: Boolean, sessionDirectory: Path) = IngestContainer(
        IngestConfig(
            store = StoreConfig(
                url = "jdbc:postgresql://nowhere:5432/mainbrain",
                user = "mainbrain",
                password = "a password",
            ),
            ingesting = ingesting,
            session = TelegramSession(
                apiId = 12_345,
                apiHash = "a hash",
                phoneNumber = "+995555000000",
                directory = sessionDirectory,
            ),
            excludedChats = setOf(900L),
            onlyChats = emptySet(),
            limits = TelegramLimits(),
        ),
        lazy {
            asked.set(true)
            error(OPENED)
        },
    )

    /**
     * The one assertion the feature is for: no session is taken, the state is reported, and the
     * process is still there.
     *
     * Three things are pinned at once, and each has its own failure to catch.
     *
     * That `run` does not return keeps the container up. The stack is restarted unless stopped, so
     * a process that exited on a deliberate switch would sit in a restart loop, and the check that
     * every service is `running` fails on any state but that one.
     *
     * That the session directory is untouched is the lock left alone — the whole point of reading
     * the switch ahead of the wait and ahead of the client.
     *
     * That the store was *asked for* is the report. A switched-off worker that only wrote to its
     * log would leave a reader of the store with the last run's row, which says nothing is wrong
     * where it means nothing is being read. The store here refuses, which is also the case worth
     * covering: a refused report must leave the process up and quiet rather than crash it.
     */
    @Test
    fun `switched off, it reports and parks without touching the session`(@TempDir session: Path) {
        val returned = runBlocking { withTimeoutOrNull(brief) { container(ingesting = false, session).run() } }

        assertNull(returned, "A switched-off process returned instead of staying up.")
        assertTrue(session.listDirectoryEntries().isEmpty()) {
            "A switched-off process wrote into the session directory: ${session.listDirectoryEntries()}"
        }
        assertTrue(asked.get(), "A switched-off process reported to its log only, and to no reader of the store.")
    }

    /**
     * The other half of the decision, which is what makes the first half mean anything.
     *
     * A check that always returned early would pass the test above. This one says the default path
     * really does go on to the store — and, because the sign-in wait would park rather than throw,
     * that it reaches the store *before* the wait. That ordering is deliberate: the store brings
     * the schema up to date as it opens, and a schema problem found after somebody has been asked
     * for a telephone code is found too late.
     *
     * It throws where the switched-off path swallows, which is the other difference between them:
     * a deployment that means to read and cannot reach its store has not started, and saying so by
     * exiting is right where staying up quietly would be wrong.
     */
    @Test
    fun `switched on, it opens the store before it waits for a session`(@TempDir session: Path) {
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { withTimeoutOrNull(brief) { container(ingesting = true, session).run() } }
        }

        assertTrue(OPENED in failure.message.orEmpty()) {
            "Expected the store to have been opened, and instead: ${failure.message}"
        }
    }

    /**
     * Shutting down must not be what finally opens the store.
     *
     * The process is stopped by a signal and closed from a shutdown hook, so `close` runs whether
     * or not anything was ever read — including after a report the store refused, which leaves it
     * unopened. A close that reached for the pool unconditionally would make one, migrate a schema
     * and close it again, on the way out of a process that read nothing.
     */
    @Test
    fun `closing a process that never opened the store opens nothing`(@TempDir session: Path) {
        assertDoesNotThrow { container(ingesting = false, session).close() }

        assertFalse(asked.get(), "Closing asked for a connection that nothing had opened.")
    }

    private companion object {
        /** What the store says if anything opens it, and the only reason this test knows it did. */
        const val OPENED = "the store was opened"
    }
}
