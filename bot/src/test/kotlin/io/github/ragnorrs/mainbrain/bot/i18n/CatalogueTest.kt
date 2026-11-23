package io.github.ragnorrs.mainbrain.bot.i18n

import io.github.ragnorrs.mainbrain.bot.core.BOLD_OPEN
import io.github.ragnorrs.mainbrain.contract.MessageKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertFailsWith

/**
 * The floor, the fallback, and the two ways a bundle can be wrong.
 *
 * **This is the check `docs/architecture.md` says was waiting on there being an interface to
 * check.** Every key resolving in Russian cannot be asserted over source text — it is a property
 * of an enum and a properties file together — so it lives here, where both are on the classpath
 * and the enums can be iterated rather than transcribed.
 *
 * Nothing here asserts on Russian text. A test spelling out what a key says in Russian would put
 * display text in Kotlin source, which is the rule the catalogue exists to keep; so the floor is
 * asserted by iterating keys, and the fallback by comparing one locale's answer against another's.
 */
class CatalogueTest {

    @Test
    fun `the shipped bundle resolves every key of both enums`() {
        // Loading is itself the assertion — a holed floor throws here — and reading every key
        // afterwards is what would fail if the completeness check were ever removed.
        val catalogue = Catalogue.loaded()
        val unresolved = MessageKey.entries.filter { catalogue.text(it, null).isBlank() }.map { it.name } +
            BotKey.entries.filter { catalogue.text(it, null).isBlank() }.map { it.name }
        assertTrue(unresolved.isEmpty()) {
            "These keys do not resolve in the floor locale: ${unresolved.joinToString()}. " +
                "Every key must, because every other locale falls back to it."
        }
    }

    @Test
    fun `every shipped date pattern compiles and renders digits alone`() {
        // **The one entry that is executed rather than substituted into**, which makes it the one
        // whose failure is not a worse sentence but no reply at all: `ofPattern` throws on a
        // pattern letter it does not know, and a translator writing the field names in their own
        // alphabet is the obvious way to produce one. The loader cannot catch it — a bad pattern
        // is present and non-blank, which is all the floor check asks — so it is caught here,
        // where every shipped bundle is on the classpath.
        //
        // Digits and separators alone, which the key's own documentation requires: a pattern
        // naming the month in words would need the platform to hold locale data for the reader's
        // language, and would put an English month inside a reply in another one. Asserted as the
        // absence of letters rather than a match against one spelling, so any way of asking for a
        // name is caught.
        val catalogue = Catalogue.loaded(languages = SHIPPED)
        SHIPPED.forEach { language ->
            val pattern = catalogue.text(BotKey.SOURCE_DATE, language)
            val written = runCatching {
                DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(LocalDate.of(2026, 10, 15))
            }
            assertTrue(written.isSuccess) {
                "The '$language' date pattern does not compile and render a date, so every reply " +
                    "citing anything would be lost rather than worded worse: " +
                    "${written.exceptionOrNull()?.message}"
            }
            assertTrue(written.getOrThrow().none { it.isLetter() }) {
                "The '$language' date pattern renders a letter, so it depends on the platform's " +
                    "locale data and can word a month in the wrong language: ${written.getOrThrow()}"
            }
        }
    }

    @Test
    fun `a floor with a hole in it is refused rather than loaded`() {
        val failure = assertFailsWith<IllegalStateException> {
            Catalogue.loaded(languages = listOf(HOLED), floorLanguage = HOLED)
        }
        assertTrue(failure.message.orEmpty().contains("missing")) {
            "A holed floor has to be refused by naming what is missing; it said: ${failure.message}"
        }
    }

    @Test
    fun `a bundle holding an entry that is not a key is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            Catalogue.loaded(languages = listOf(Catalogue.FLOOR, MISSPELLED))
        }
        assertTrue(failure.message.orEmpty().contains("VARIANTT")) {
            "The refusal has to name the entry nothing asks for; it said: ${failure.message}"
        }
    }

    @Test
    fun `a bundle the platform would not render as written is not loaded`() {
        val failure = assertFailsWith<IllegalStateException> {
            Catalogue.loaded(languages = listOf(Catalogue.FLOOR, BAD_MARKUP))
        }
        val said = failure.message.orEmpty()
        // Every fault, each named by its key, and asserted one key at a time rather than by
        // counting them: a check that stopped at the first fails here naming the ones it did not
        // reach. The refusal exists to hand somebody a whole bundle to fix, and one fault per
        // restart is the slowest way to learn how many there are.
        //
        // The keys are read from the fixture rather than listed here, so a fault class added to it
        // is covered without this being edited — a fixture entry nothing checks for is how the
        // calibration would quietly shrink.
        val carried = faultyKeys()
        assertTrue(carried.isNotEmpty()) {
            "The $BAD_MARKUP fixture holds no entries, so this case would assert nothing."
        }
        carried.forEach { key ->
            assertTrue(key in said) {
                "The refusal does not name $key, whose entry in the $BAD_MARKUP fixture carries " +
                    "a fault. It said: $said"
            }
        }
    }

    @Test
    fun `the shipped bundle's own markup is sendable`() {
        // The positive half, and not implied by the case above: that one proves the check bites,
        // this proves it is not biting on the text this build ships. `loaded` is the assertion —
        // it throws on a fault — and the emphasis in the floor locale is what makes it a real one
        // rather than a check over text with no tags in it.
        val catalogue = Catalogue.loaded()
        assertTrue(BotKey.entries.any { BOLD_OPEN in catalogue.text(it, null) }) {
            "No entry this interface says on its own account carries emphasis, so the markup " +
                "check above passes over text that has no markup in it and asserts nothing."
        }
    }

    /** Every key the deliberately broken fixture holds, read from the fixture itself. */
    private fun faultyKeys(): List<String> {
        val resource = CatalogueTest::class.java.getResourceAsStream("/i18n/messages_$BAD_MARKUP.properties")
        return checkNotNull(resource) { "The $BAD_MARKUP fixture is missing." }
            .bufferedReader()
            .readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { it.substringBefore("=").trim() }
    }

    @Test
    fun `a key the second locale translates comes back in that locale`() {
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))
        assertEquals("Option 2", catalogue.text(BotKey.VARIANT, PARTIAL, "2"))
    }

    @Test
    fun `an entry filled with another resolves the argument in the reader's own locale`() {
        // **The forward half of what the filling overload claims**, and the half no other case
        // reaches: the one argument this build passes is a key the partial fixture does not
        // translate, so every case that exercises the overload sees the argument fall back. With
        // only those, resolving every argument at the floor instead would keep the whole suite
        // green — and a reader of a fully translated locale would get their own sentence around
        // the floor's words.
        //
        // `VARIANT` is used as the argument because the fixture translates it, which is the only
        // thing this needs of it. Its own `{0}` goes unfilled and is not what is asserted.
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))

        val filled = catalogue.text(BotKey.UNKNOWN_COMMAND, PARTIAL, listOf(BotKey.VARIANT))

        assertTrue(catalogue.text(BotKey.VARIANT, PARTIAL) in filled) {
            "The argument did not resolve in the reader's locale: $filled"
        }
        assertFalse(catalogue.text(BotKey.VARIANT, Catalogue.FLOOR) in filled) {
            "The argument resolved at the floor although the reader's locale translates it: $filled"
        }
    }

    @Test
    fun `a key the second locale omits falls back to the floor rather than to a code`() {
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))
        // Compared against the floor's own answer rather than against a literal, so this asserts
        // the fallback without naming what the floor says.
        assertEquals(
            catalogue.text(BotKey.KIND_CONTACT, Catalogue.FLOOR),
            catalogue.text(BotKey.KIND_CONTACT, PARTIAL),
        )
    }

    @Test
    fun `a region on the tag still finds the language`() {
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))
        assertEquals("Option 1", catalogue.text(BotKey.VARIANT, "$PARTIAL-YZ", "1"))
        assertEquals("Option 1", catalogue.text(BotKey.VARIANT, "${PARTIAL.uppercase()}_YZ", "1"))
    }

    @Test
    fun `a locale nothing ships and no locale at all both read the floor`() {
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))
        val floor = catalogue.text(BotKey.VARIANT, Catalogue.FLOOR, "3")
        assertEquals(floor, catalogue.text(BotKey.VARIANT, "fr", "3"))
        assertEquals(floor, catalogue.text(BotKey.VARIANT, null, "3"))
    }

    @Test
    fun `an apostrophe survives substitution`() {
        // MessageFormat would read the apostrophe as an escape and drop the braces with it, which
        // is why the substitution is literal. The whole sentence has to come back.
        val catalogue = Catalogue.loaded(languages = listOf(Catalogue.FLOOR, PARTIAL))
        assertEquals("It's Acme, isn't it", catalogue.text(BotKey.SUBJECT_NAMED, PARTIAL, "Acme"))
    }

    @Test
    fun `a floor not among the languages is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            Catalogue.loaded(languages = listOf(PARTIAL), floorLanguage = Catalogue.FLOOR)
        }
        assertTrue(failure.message.orEmpty().contains("has to be among")) {
            "It said: ${failure.message}"
        }
    }

    @Test
    fun `a locale with no bundle at all is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            Catalogue.loaded(languages = listOf(Catalogue.FLOOR, "zz"))
        }
        assertTrue(failure.message.orEmpty().contains("messages_zz")) {
            "The refusal has to name the bundle it looked for; it said: ${failure.message}"
        }
    }

    private companion object {

        /**
         * The locales this build ships, which is what the production loader is given.
         *
         * The floor alone today. A second one joins this and is held to the same pattern rule by
         * the case above, rather than shipping a date nobody compiled.
         */
        private val SHIPPED = listOf(Catalogue.FLOOR)

        /** A handful of keys and no more; everything else has to fall back. */
        private const val PARTIAL = "xx"

        /** One key, loaded as the floor. */
        private const val HOLED = "holed"

        /** One key and one name that is not a key. */
        private const val MISSPELLED = "misspelled"

        /** One key per way an entry's markup can be wrong. */
        private const val BAD_MARKUP = "badmarkup"
    }
}
