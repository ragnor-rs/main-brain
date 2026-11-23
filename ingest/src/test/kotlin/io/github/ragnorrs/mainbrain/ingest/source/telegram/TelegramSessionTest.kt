package io.github.ragnorrs.mainbrain.ingest.source.telegram

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a session has been signed in, which the service waits on and must not get wrong.
 *
 * Both directions cost something real. A false *yes* opens a client that cannot read and reports
 * the platform's refusals as though the source were unreachable. A false *no* waits forever with
 * a perfectly good session sitting in the directory.
 */
class TelegramSessionTest {

    private fun session(directory: Path) = TelegramSession(
        apiId = 1,
        apiHash = "a hash",
        phoneNumber = "+99500000000",
        directory = directory,
    )

    @Test
    fun `an empty directory holds no session`(@TempDir directory: Path) {
        assertFalse(session(directory).isSignedIn())
    }

    @Test
    fun `a directory that does not exist holds no session`(@TempDir directory: Path) {
        assertFalse(session(directory.resolve("not-created-yet")).isSignedIn())
    }

    @Test
    fun `recording a sign-in is what makes it readable, and it creates the directory`(@TempDir root: Path) {
        val session = session(root.resolve("session"))

        session.recordSignedIn()

        assertTrue(session.isSignedIn())
    }

    /**
     * The library's own store is not the signal, and this is why the marker exists.
     *
     * The client library writes its database as soon as it is initialised — which happens on
     * every start, signed in or not. A service that read those files as evidence would open a
     * client, be refused, and report a source failure for the life of the process.
     */
    @Test
    fun `the client library's own files are not a sign-in`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("td.binlog"), "")
        Files.writeString(directory.resolve("db.sqlite"), "")

        assertFalse(session(directory).isSignedIn())
    }
}
