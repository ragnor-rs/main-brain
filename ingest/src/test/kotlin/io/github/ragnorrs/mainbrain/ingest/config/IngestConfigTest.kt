package io.github.ragnorrs.mainbrain.ingest.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * What this process refuses to start without, and what it fills in.
 *
 * The distinction is the whole of this file. A required value that is absent has to stop the
 * process with its own name in the message, because the alternative — an empty credential handed
 * to a platform — comes back as a refusal that reads like the source being unreachable. A value
 * with a sensible default is one a reader of the deployment should not have to set.
 */
class IngestConfigTest {

    private val complete = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://postgres:5432/mainbrain",
        "DATABASE_USER" to "mainbrain",
        "DATABASE_PASSWORD" to "a password",
        "TELEGRAM_API_ID" to "12345",
        "TELEGRAM_API_HASH" to "a hash",
        "TELEGRAM_PHONE" to "+995555000000",
    )

    private fun read(environment: Map<String, String?>) = IngestConfig.fromEnv { environment[it] }

    @Test
    fun `the required values are read, and the rest have defaults`() {
        val config = read(complete)

        assertEquals("mainbrain", config.store.user)
        assertEquals(12_345, config.session.apiId)
        assertEquals(Path.of("/var/lib/mainbrain/session"), config.session.directory)
        assertTrue(config.excludedChats.isEmpty())
        assertEquals(2.seconds, config.limits.betweenPages)
        assertNull(config.limits.reachBackTo)
        assertEquals(true, config.ingesting)
    }

    /**
     * The switch, and its default is the one value here that is a product decision.
     *
     * Continuous capture is what this system is, so an installation never told about the switch
     * has to read its source as before. Off is operational and asked for explicitly: every
     * deployment pointed at one session directory competes for one session, because the client
     * library locks the directory it is given.
     */
    @Test
    fun `ingestion can be switched off, and is on unless it is`() {
        assertEquals(false, read(complete + ("INGEST_ENABLED" to "false")).ingesting)
        assertEquals(false, read(complete + ("INGEST_ENABLED" to "FALSE")).ingesting)
        assertEquals(true, read(complete + ("INGEST_ENABLED" to "true")).ingesting)
        // Empty rather than whitespace is what every real container is handed: Compose
        // interpolates a variable nobody set to `""`, which has to read as unset and so as on.
        assertEquals(true, read(complete + ("INGEST_ENABLED" to "")).ingesting)
        assertEquals(true, read(complete + ("INGEST_ENABLED" to "   ")).ingesting)
    }

    /**
     * A switch spelled some other way must not read as on.
     *
     * This is the exclusion's failure mode again, pointing the other way. `no`, `0` and `off` all
     * mean off to whoever typed them; read leniently as unset, they would leave the process
     * reading the source — and whoever set the variable would have no way to tell that from the
     * switch not working at all.
     */
    @Test
    fun `a switch that is neither true nor false stops the process`() {
        listOf("no", "0", "off", "yes").forEach { value ->
            val failure = assertFailsWith<IllegalStateException>("'$value' was accepted") {
                read(complete + ("INGEST_ENABLED" to value))
            }
            assertTrue("INGEST_ENABLED" in failure.message.orEmpty()) {
                "The failure for '$value' did not name the variable: ${failure.message}"
            }
            assertTrue(value in failure.message.orEmpty()) {
                "The failure for '$value' did not name it: ${failure.message}"
            }
        }
    }

    /**
     * Compose interpolates an unset variable to an empty string, so this is the ordinary case
     * rather than the exotic one: a `.env` with the line present and nothing after the `=`.
     */
    @Test
    fun `a blank value is missing, and the message names what was wanted`() {
        val failure = assertFailsWith<IllegalStateException> { read(complete + ("TELEGRAM_API_HASH" to "   ")) }

        assertTrue("TELEGRAM_API_HASH" in failure.message.orEmpty())
    }

    @Test
    fun `every required value is required`() {
        complete.keys.forEach { name ->
            val failure = assertFailsWith<IllegalStateException>("$name was not required") {
                read(complete - name)
            }
            assertTrue(name in failure.message.orEmpty()) {
                "The failure for $name did not name it: ${failure.message}"
            }
        }
    }

    /**
     * The application identifier is a number, and a value that is not one is refused rather than
     * defaulted. A zero would be a registration nobody holds, and the platform's answer to it is
     * a refused request — reported as the source misbehaving.
     */
    @Test
    fun `an application identifier that is not a number is refused`() {
        val failure = assertFailsWith<IllegalStateException> { read(complete + ("TELEGRAM_API_ID" to "not a number")) }

        assertTrue("TELEGRAM_API_ID" in failure.message.orEmpty())
    }

    @Test
    fun `the excluded chats are read as identifiers, spacing and empty entries aside`() {
        val config = read(complete + ("TELEGRAM_EXCLUDED_CHATS" to " 900, -1001234 ,,"))

        assertEquals(setOf(900L, -1_001_234L), config.excludedChats)
    }

    /**
     * The exclusion is the one setting whose lenient reading is a silent failure.
     *
     * Dropping an entry that does not parse would mean a typo'd identifier reads as *this chat is
     * no longer excluded* — so the assistant's own chat gets ingested and the assistant can cite
     * itself, with nothing anywhere saying why. Three comments in the codebase say this exclusion
     * must not lapse quietly; this is what makes that true rather than stated.
     */
    @Test
    fun `an excluded chat that is not an identifier stops the process`() {
        val failure = assertFailsWith<IllegalStateException> {
            read(complete + ("TELEGRAM_EXCLUDED_CHATS" to "900, a title"))
        }

        assertTrue("TELEGRAM_EXCLUDED_CHATS" in failure.message.orEmpty())
        assertTrue("a title" in failure.message.orEmpty())
    }

    /** An unreadable pace must not read as an unset one, or the default hides the mistake. */
    @Test
    fun `a pace that is not a number of seconds stops the process`() {
        val failure = assertFailsWith<IllegalStateException> {
            read(complete + ("TELEGRAM_SECONDS_BETWEEN_PAGES" to "two"))
        }

        assertTrue("TELEGRAM_SECONDS_BETWEEN_PAGES" in failure.message.orEmpty())
    }

    /** The platform's own parse failure names the text and not the setting it came from. */
    @Test
    fun `a depth that is not an instant stops the process, naming the variable`() {
        val failure = assertFailsWith<IllegalStateException> {
            read(complete + ("TELEGRAM_REACH_BACK_TO" to "2024-01-01"))
        }

        assertTrue("TELEGRAM_REACH_BACK_TO" in failure.message.orEmpty())
    }

    @Test
    fun `the pace and the depth are read as a duration and a source time`() {
        val config = read(
            complete + mapOf(
                "TELEGRAM_SECONDS_BETWEEN_PAGES" to "30",
                "TELEGRAM_REACH_BACK_TO" to "2024-01-01T00:00:00Z",
            ),
        )

        assertEquals(30.seconds, config.limits.betweenPages)
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), config.limits.reachBackTo)
    }
}
