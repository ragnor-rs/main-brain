package io.github.ragnorrs.mainbrain.bot.i18n

import io.github.ragnorrs.mainbrain.bot.core.markupFaults
import io.github.ragnorrs.mainbrain.contract.MessageKey
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Resolves `(key, locale) → text` for this interface, and holds the only words in this process.
 *
 * **The catalogue is the resource bundles; this is what reads them.** No declaration here is the
 * catalogue and none holds a string of display text — a string in a `.kt` file cannot be
 * translated without a code change, which is the whole failure the rule exists to prevent. The
 * build asserts both halves: no Kotlin source in this repository may contain Cyrillic or Georgian
 * outside a model instruction, which renders to nobody, and no declaration may be named as
 * though it were the catalogue.
 *
 * **It belongs to this interface rather than to the wire format.** Two interfaces rendering the
 * same key differently is correct — a chat reply and a CRM field are not the same register — so a
 * second interface ships its own bundles and reads them through its own copy of this. An interface
 * that renders nothing, as the MCP server does, owns none at all.
 *
 * **A bundle holds the markup, and so a bundle is checked for it.** The little emphasis this
 * interface renders lives in the entries rather than being composed around them, because a locale
 * should be free to word a line the way that language wants it and emphasis is part of the wording;
 * composing it here would mean this interface knowing the shape of every message instead of only
 * its words. What that costs is [verifyMarkup], since the platform refuses a message whose markup
 * it cannot parse — see `Markup.kt`, which holds the vocabulary and the reasoning about why the
 * catalogue is trusted with tags where a model's prose is not.
 *
 * **Russian is the floor, and the floor is checked when this is built rather than when a key is
 * read.** Every key of both enums must resolve in Russian, so [loaded] refuses a bundle with a
 * hole in it and the process stops at startup naming what is missing. The alternative is a hole
 * that surfaces as a raw code in somebody's chat, at the moment the one key nobody translated is
 * the one being said. Because the floor is complete, a fallback always lands and nothing here can
 * return a code.
 */
internal class Catalogue private constructor(
    private val locales: Map<String, Map<String, String>>,
    private val floor: String,
) {

    /** What the backend said, in the reader's language. */
    fun text(key: MessageKey, locale: String?, vararg arguments: String): String =
        resolve(BACKEND_PREFIX + key.name, locale, arguments)

    /** What this interface says on its own account. */
    fun text(key: BotKey, locale: String?, vararg arguments: String): String =
        resolve(BOT_PREFIX + key.name, locale, arguments)

    /**
     * The same, where what fills the entry is other entries.
     *
     * **One entry framing another is a locale's business, so resolving the arguments is this
     * class's.** A caller naming the keys says *this reply is that reply, said inside this frame*
     * and holds no text; resolving them itself would mean every such caller repeating the fallback
     * rule, and getting it wrong looks like one reader's language framing another's.
     *
     * The arguments are resolved for the locale the entry was asked for, which is what makes a
     * partial bundle fall back per key here as it does everywhere else: a frame one locale
     * translates may well be filled with a list it does not, and each lands where it lands.
     *
     * Filled in order and left alone afterwards, so an argument whose own text carries a
     * placeholder would be filled by the argument after it. Nothing passes such a key, and a
     * second argument is the point at which that stops being theoretical.
     */
    fun text(key: BotKey, locale: String?, filledWith: List<BotKey>): String =
        resolve(
            BOT_PREFIX + key.name,
            locale,
            filledWith.map { text(it, locale) }.toTypedArray(),
        )

    /**
     * The entry for [name] in the reader's language, or the floor's.
     *
     * A partial locale falls back per key rather than per bundle: an untranslated entry should
     * look like one untranslated line, not send the whole reply back to Russian.
     */
    private fun resolve(name: String, locale: String?, arguments: Array<out String>): String {
        val language = locale?.substringBefore('-')?.substringBefore('_')?.lowercase()
        val text = language?.let { locales[it]?.get(name)?.takeIf(String::isNotBlank) }
            ?: locales.getValue(floor).getValue(name)
        return fill(text, arguments)
    }

    companion object {

        /**
         * The locale every key resolves in, and the one this build ships.
         *
         * Which further locales ship is this interface's decision rather than the corpus's: the
         * material being Russian and Georgian says nothing about what language the team reads its
         * own assistant in. One locale is what was decided, and the fallback above is built and
         * exercised regardless — so a second is a file beside the first rather than a code change.
         */
        const val FLOOR = "ru"

        /** Keys the backend emits, namespaced so the two enums cannot collide in one bundle. */
        private const val BACKEND_PREFIX = "backend."

        /** And the keys this interface emits. */
        private const val BOT_PREFIX = "bot."

        private const val RESOURCE_DIRECTORY = "/i18n"

        /**
         * Read the bundles, or say what is missing from the floor.
         *
         * Both parameters exist for the tests, and for one reason: this build ships a single
         * locale, so the two behaviours that matter most — a partial locale falling back, and a
         * holed floor being refused — have no production configuration that exercises either.
         * [languages] lets a deliberately partial bundle be loaded beside the real one, and
         * [floorLanguage] lets a deliberately holed one be loaded *as* the floor, so the check
         * below is proven to bite rather than assumed to.
         */
        fun loaded(
            languages: List<String> = listOf(FLOOR),
            floorLanguage: String = FLOOR,
        ): Catalogue {
            val locales = languages.associateWith { read(it) }
            val floor = locales[floorLanguage]
                ?: error("The floor locale '$floorLanguage' has to be among $languages.")
            verifyComplete(floorLanguage, floor)
            locales.forEach { (language, entries) ->
                verifyKnown(language, entries)
                verifyMarkup(language, entries)
            }
            return Catalogue(locales, floorLanguage)
        }

        /** Every key of both enums, as a bundle spells it. */
        private fun names(): Set<String> =
            MessageKey.entries.map { BACKEND_PREFIX + it.name }.toSet() +
                BotKey.entries.map { BOT_PREFIX + it.name }.toSet()

        /**
         * The floor has an entry for every key, or the process does not start.
         *
         * Reported as the whole list rather than the first one found: whoever reads this is about
         * to write translations, and one name per restart is the slowest way to learn what four
         * are needed.
         */
        private fun verifyComplete(language: String, floor: Map<String, String>) {
            // Blank counts as missing. An entry with nothing in it would pass a presence check
            // and then fail where a reply is built, which is further from the cause than this is.
            val present = floor.filterValues { it.isNotBlank() }.keys
            val missing = (names() - present).sorted()
            check(missing.isEmpty()) {
                "The floor locale '$language' is missing ${missing.size} key(s): " +
                    "${missing.joinToString()}. Every key must resolve in it, because every " +
                    "other locale falls back to it and a key missing from both would reach " +
                    "somebody as a code."
            }
        }

        /**
         * And no bundle holds an entry that is not a key.
         *
         * A misspelled name in a bundle is how a translation silently fails to apply: the entry
         * is there, it is simply never asked for, and the locale looks complete while rendering
         * the floor's text. Cheap to catch here and invisible everywhere else.
         */
        private fun verifyKnown(language: String, entries: Map<String, String>) {
            val unknown = (entries.keys - names()).sorted()
            check(unknown.isEmpty()) {
                "The '$language' bundle holds ${unknown.size} entry/entries that are not keys: " +
                    "${unknown.joinToString()}. An entry nothing asks for is a misspelling, and " +
                    "the locale would read as translated while saying the floor's words."
            }
        }

        /**
         * And every entry carries only markup this interface can render.
         *
         * **A bundle is the one text that is not escaped, so it is the one text that can be
         * unsendable.** Everything else in a reply goes through `escaped` precisely because the
         * three characters the platform reserves are ordinary in corpus material; catalogue text is
         * exempt so that it can carry the little emphasis this interface composes, and the price of
         * the exemption is this. The platform refuses a message whose markup it cannot parse rather
         * than rendering it oddly, and a refused send abandons the replies queued behind it — so a
         * mistyped tag costs a reader the whole answer, for whichever requests happen to use the
         * entry.
         *
         * **Every locale, not only the floor.** A partial bundle's entry is what reaches a reader
         * wherever it resolves, so an entry nothing falls back to still has to be sendable.
         *
         * Checked here rather than by a reviewer because a translator cannot be expected to know
         * the platform's parse rules, and rather than at send time because a fault that surfaces
         * when the entry is first used surfaces in front of somebody.
         */
        private fun verifyMarkup(language: String, entries: Map<String, String>) {
            val faulty = entries.toSortedMap()
                .mapNotNull { (name, text) ->
                    markupFaults(text).takeIf(List<String>::isNotEmpty)?.let { name to it }
                }
            check(faulty.isEmpty()) {
                "The '$language' bundle holds ${faulty.size} entry/entries the platform would not " +
                    "render as written: " +
                    faulty.joinToString("; ") { (name, faults) -> "$name — ${faults.joinToString(" ")}" } +
                    ". Neither outcome is a mis-rendered reply: markup it cannot parse means the " +
                    "message is not delivered at all and the send that fails drops whatever was " +
                    "queued behind it, while an ampersand that begins no entity is accepted and " +
                    "the text quietly altered. Each fault above says which of the two it is."
            }
        }

        private fun read(language: String): Map<String, String> {
            val resource = "$RESOURCE_DIRECTORY/messages_$language.properties"
            val stream = Catalogue::class.java.getResourceAsStream(resource)
                ?: error("No bundle at $resource. A locale this interface claims to ship has to have one.")
            // A reader rather than the stream, because `Properties` reads a stream as
            // ISO-8859-1 — every Cyrillic entry would arrive as mojibake, which is the one
            // failure that looks like a font problem rather than a bug.
            val properties = Properties()
            stream.use { InputStreamReader(it, StandardCharsets.UTF_8).use(properties::load) }
            return properties.stringPropertyNames().associateWith(properties::getProperty)
        }

        /**
         * Positional arguments, substituted literally.
         *
         * Not `MessageFormat`, deliberately: its quoting rules make a lone apostrophe an escape
         * character, so a perfectly ordinary sentence in some locale would silently lose part of
         * itself. Every argument arrives already formatted, so nothing is given up by it: a count
         * is worded by choosing between entries and a date by a pattern that is itself an entry,
         * which is how the locale-aware part is had without the quoting rules.
         */
        private fun fill(text: String, arguments: Array<out String>): String =
            arguments.foldIndexed(text) { index, filled, argument -> filled.replace("{$index}", argument) }
    }
}
