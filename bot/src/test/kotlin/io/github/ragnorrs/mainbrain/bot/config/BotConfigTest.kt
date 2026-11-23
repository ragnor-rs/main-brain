package io.github.ragnorrs.mainbrain.bot.config

import io.github.ragnorrs.mainbrain.bot.port.SenderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertFailsWith

/**
 * What this process is told, and every way of being told it badly.
 *
 * The cases worth having are the refusals. A process that starts misconfigured answers nothing and
 * looks from outside exactly like one that is down, so each absence has to stop it by naming the
 * variable it wanted.
 */
class BotConfigTest {

    @Test
    fun `a complete environment is read`() {
        val config = BotConfig.fromEnv(reading(complete))

        assertEquals("http://backend:8080", config.backend.url)
        assertEquals("a-secret", config.backend.secret)
        assertEquals("a-token", config.telegram.token)
        assertEquals(setOf(SenderId("421"), SenderId("7")), config.telegram.allowed)
    }

    @Test
    fun `the corpus calendar defaults rather than stopping the process`() {
        // **Deliberately not in `complete`**, which the two cases below iterate expecting a
        // refusal for every entry. This is the one variable with a meaningful absence: UTC is the
        // zone the material has always been dated in, so an unset value keeps that behaviour
        // instead of guessing at one.
        assertEquals(ZoneOffset.UTC, BotConfig.fromEnv(reading(complete)).zone)
        assertEquals(ZoneOffset.UTC, BotConfig.fromEnv(reading(complete + (ZONE to "  "))).zone)
    }

    @Test
    fun `a named region is read, and so is a bare offset`() {
        // A region is what a deployment should name — a fixed offset dates material wrongly
        // wherever summer time is observed, in the direction that moves a day boundary. Both are
        // accepted, since a deployment that knows it wants an offset may say so.
        assertEquals(ZoneId.of("Europe/Lisbon"), BotConfig.fromEnv(reading(complete + (ZONE to "Europe/Lisbon"))).zone)
        assertEquals(ZoneOffset.ofHours(3), BotConfig.fromEnv(reading(complete + (ZONE to "+03:00"))).zone)
    }

    @Test
    fun `a misspelt zone stops the process rather than restoring the default`() {
        // Restoring UTC silently shows a reader a date that is a day out on some material and not
        // on other, which reads as the answer being wrong rather than as a variable being
        // misspelt.
        val failure = assertFailsWith<IllegalStateException> {
            BotConfig.fromEnv(reading(complete + (ZONE to "Europe/Lisbonn")))
        }
        assertTrue(failure.message.orEmpty().contains(ZONE)) {
            "The refusal has to name $ZONE; it said: ${failure.message}"
        }
    }

    @Test
    fun `each variable is named when it is not set`() {
        complete.keys.forEach { name ->
            val failure = assertFailsWith<IllegalStateException> {
                BotConfig.fromEnv(reading(complete - name))
            }
            assertTrue(failure.message.orEmpty().contains(name)) {
                "The refusal has to name $name; it said: ${failure.message}"
            }
        }
    }

    @Test
    fun `blank counts as missing, because that is what an unset variable interpolates to`() {
        complete.keys.forEach { name ->
            val failure = assertFailsWith<IllegalStateException> {
                BotConfig.fromEnv(reading(complete + (name to "   ")))
            }
            assertTrue(failure.message.orEmpty().contains(name)) {
                "The refusal has to name $name; it said: ${failure.message}"
            }
        }
    }

    @Test
    fun `an allowlist admitting nobody is refused rather than defaulted`() {
        // A bot that ignores every message reads from outside exactly like one that is down.
        val failure = assertFailsWith<IllegalStateException> {
            BotConfig.fromEnv(reading(complete + (ALLOWED to ",  , ,")))
        }
        assertTrue(failure.message.orEmpty().contains("admits nobody")) { "It said: ${failure.message}" }
    }

    @Test
    fun `an entry that is not an identifier stops the process and is named`() {
        // Taken leniently it would mean *this person is no longer admitted* — which is the owner
        // locked out by a typo, with the same symptom as an empty list and a different cause.
        val failure = assertFailsWith<IllegalStateException> {
            BotConfig.fromEnv(reading(complete + (ALLOWED to "421,four-two-one")))
        }
        assertTrue(failure.message.orEmpty().contains("four-two-one")) { "It said: ${failure.message}" }
    }

    @Test
    fun `surrounding space in the allowlist is normalised away`() {
        val config = BotConfig.fromEnv(reading(complete + (ALLOWED to " 421 , 7 ")))

        assertEquals(setOf(SenderId("421"), SenderId("7")), config.telegram.allowed)
    }

    @Test
    fun `a negative identifier is admitted, because a chat can have one`() {
        val config = BotConfig.fromEnv(reading(complete + (ALLOWED to "-100421")))

        assertEquals(setOf(SenderId("-100421")), config.telegram.allowed)
    }

    private fun reading(values: Map<String, String>): (String) -> String? = values::get

    private companion object {

        private const val ALLOWED = "ALLOWED_TELEGRAM_IDS"

        /**
         * The calendar the corpus's material is dated in.
         *
         * Named here and kept out of [complete], because every entry of that is asserted to stop
         * the process when it is missing and this one defaults instead.
         */
        private const val ZONE = "CORPUS_TIME_ZONE"

        private val complete = mapOf(
            "BACKEND_URL" to "http://backend:8080",
            "BACKEND_SECRET" to "a-secret",
            "TELEGRAM_BOT_TOKEN" to "a-token",
            ALLOWED to "421,7",
        )
    }
}
