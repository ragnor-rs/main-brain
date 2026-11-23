package io.github.ragnorrs.mainbrain.bot.i18n

import io.github.ragnorrs.mainbrain.bot.core.BOLD_CLOSE
import io.github.ragnorrs.mainbrain.bot.core.BOLD_OPEN
import io.github.ragnorrs.mainbrain.bot.core.Commands
import io.github.ragnorrs.mainbrain.bot.core.bold
import io.github.ragnorrs.mainbrain.contract.Command
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The help text says what the build does.
 *
 * **Why this is a test and not a reviewer's job.** The text is the first thing a reader sees — a
 * platform sends it when a conversation opens — and it is prose in a resource bundle, so adding,
 * renaming or re-scoping a command changed nothing here and the text drifted in four places at
 * once. What it claimed about three of the four commands was false.
 *
 * **It asserts coverage, not wording.** Every command a reader may type appears, and every kind a
 * command accepts is named in the words the rest of this interface uses for it. What a sentence
 * reads like is a reviewer's, and a test quoting prose back at a bundle would fail on every
 * rewording while still passing on a command quietly re-scoped.
 *
 * **And it asserts the emphasis in both directions**, which is coverage of the same kind: that
 * every command is set off from its description, and that nothing else is. A list whose names are
 * the same weight as the prose around them is a paragraph, and a list with everything emphasised is
 * the same paragraph again — so the two cases are one rule, and neither half holds alone.
 *
 * The two halves come from different places on purpose: [Command] is the backend's declaration,
 * which every interface shares, and [Commands.atTheEdge] is this interface's own furniture. A help
 * text covering one and not the other is the defect that hid two commands, including the one that
 * shows this text.
 */
class HelpTextTest {

    private val help = Catalogue.loaded().text(BotKey.HELP, null)

    @Test
    fun `every command the backend answers is listed`() {
        assertFalse(Command.entries.isEmpty(), "This asserts nothing unless some command is declared.")
        Command.entries.forEach { command ->
            assertTrue(
                "/${command.word}" in help,
                "The help text does not mention /${command.word}, which the backend answers. " +
                    "A command nothing lists is reachable only by somebody who already knew it was there.",
            )
        }
    }

    @Test
    fun `every command this interface answers itself is listed`() {
        assertFalse(
            Commands.atTheEdge.isEmpty(),
            "This asserts nothing unless the interface answers some command itself.",
        )
        Commands.atTheEdge.keys.forEach { word ->
            assertTrue(
                "/$word" in help,
                "The help text does not mention /$word, which this interface answers out of its " +
                    "own catalogue. The platform sends one of these when a conversation opens, so " +
                    "a first-time reader is shown this text and not told how to get it back.",
            )
        }
    }

    @Test
    fun `every kind a command accepts is named on that command's own line`() {
        val catalogue = Catalogue.loaded()
        assertFalse(
            Command.entries.all { it.accepts.isEmpty() },
            "This asserts nothing unless some command accepts a kind.",
        )
        Command.entries.forEach { command ->
            // On the command's own line, not anywhere in the text. Containment over the whole of
            // it was measured passing the very defect this case is for: the opening sentence says
            // what the material is and carries the word for a conversation, so deleting that kind
            // from the /status line left the old assertion green.
            val lines = help.lines().filter { "/${command.word}" in it }
            assertFalse(
                lines.isEmpty(),
                "No line of the help text mentions /${command.word} at all.",
            )
            command.accepts.forEach { kind ->
                val word = catalogue.text(kind.key(), null)
                assertTrue(
                    lines.any { word in it },
                    "/${command.word} accepts $kind, which no line mentioning it names. This is " +
                        "the word this interface names that kind by where it stands on its own, " +
                        "so the two texts it shows a reader would disagree about what one " +
                        "command takes. Lines found: $lines",
                )
            }
        }
    }

    @Test
    fun `no command's line names a kind that command does not accept`() {
        // The converse of the case above, and not implied by it. That one says every accepted kind
        // is named; without this, a kind dropped from a shape and its declaration together — which
        // is a legitimate re-scoping, so nothing else objects — leaves the word standing in the
        // text and a reader is invited to ask for a scope that no longer exists. Measured: with
        // only the forward check, both suites stayed green on exactly that edit.
        val catalogue = Catalogue.loaded()
        val everyKind = SubjectKind.entries.associateWith { catalogue.text(it.key(), null) }
        Command.entries.forEach { command ->
            val lines = help.lines().filter { "/${command.word}" in it }
            lines.forEach { line ->
                everyKind.forEach { (kind, word) ->
                    assertFalse(
                        word in line && kind !in command.accepts,
                        "A line for /${command.word} names $kind, which it does not accept: " +
                            "\"$line\". The command takes ${command.accepts}, so this offers a " +
                            "reader a scope the build refuses.",
                    )
                }
            }
        }
    }

    @Test
    fun `the command whose argument is a question is not described as taking a name`() {
        // The one command a reader cannot guess: its whole value is in what it is asked, and a
        // list that does not say so gets a company name written after it.
        val asking = Command.entries.filter { it.argument == Command.Argument.QUESTION }
        assertFalse(asking.isEmpty(), "This asserts nothing unless some command takes a question.")
        asking.forEach { command ->
            val sentences = help.lines().filter { "/${command.word}" in it }
            assertTrue(
                sentences.any { NAMES_A_QUESTION.containsMatchIn(it) },
                "No line mentioning /${command.word} says its argument is a question. It accepts " +
                    "no subject at all, so a reader following this text will write a name after it " +
                    "and never learn they could have asked something. Lines found: $sentences",
            )
        }
    }

    @Test
    fun `every command in the list is set off from its description`() {
        // The list's whole job is to be scanned, and a run of lines where the command name is
        // indistinguishable from the prose describing it is not a list. Asserted through `bold`
        // rather than against a tag spelled here, so the one declaration of the vocabulary is what
        // this reads too.
        val words = Command.entries.map { it.word } + Commands.atTheEdge.keys
        assertFalse(
            words.isEmpty(),
            "No command is declared anywhere, so this iterates nothing and passes vacuously.",
        )
        words.forEach { word ->
            // On the command's own line, not anywhere in the text, for the reason the kind case
            // above records: the parameter paragraphs name the same commands again, so emphasis
            // moved out of the list and into those would satisfy containment over the whole entry
            // while the list a reader scans went back to being flat. Measured there, on this very
            // text, which is why that case is written this way too.
            val listed = help.lines().filter { "/$word" in it && bold("/$word") in it }
            assertTrue(
                listed.isNotEmpty(),
                "No line of the help text both mentions /$word and sets it off. On the delivered " +
                    "message it then reads as more of the sentence around it. The list is the one " +
                    "text every reader sees — a platform sends it when a conversation opens — and " +
                    "scanning it for a command is the only thing anybody does with it. Lines " +
                    "mentioning it: ${help.lines().filter { "/$word" in it }}",
            )
        }
    }

    @Test
    fun `nothing but a command is emphasised`() {
        // The converse, and the half that rots. Emphasis is a budget: a text with everything
        // emphasised has none, so what the list may set off is the token a reader acts on and
        // nothing else. Without this, emphasis added to a description passes the case above while
        // taking away exactly what it was added for.
        val words = (Command.entries.map { it.word } + Commands.atTheEdge.keys).map { "/$it" }
        assertFalse(
            words.isEmpty(),
            "No command is declared anywhere, so every emphasised run would read as stray and " +
                "this would fail for the wrong reason.",
        )
        val emphasised = EMPHASISED.findAll(help).map { it.groupValues[1] }.toList()
        assertFalse(
            emphasised.isEmpty(),
            "Nothing in the help text is emphasised at all, so this asserts nothing.",
        )
        emphasised.forEach { run ->
            assertTrue(
                run in words,
                "The help text emphasises \"$run\", which is not a command a reader can type. " +
                    "Only the token they act on is set off; everything else competes with it. " +
                    "Commands are $words.",
            )
        }
    }

    @Test
    fun `nothing claims that everything sent is a draft`() {
        // The claim, not the promise around it. The old text said in one breath that nothing goes
        // out on the reader's behalf — true, and the most important sentence here — and that
        // everything it sends is a draft they send, which is false for three of the four commands:
        // a status answer, a call brief and a research result are not drafts and nobody sends them
        // anywhere. Asserted as a forbidden co-occurrence rather than as a required phrase,
        // because the two halves sit in one sentence and a check that accepted the line for
        // carrying the true half is a check the old text passes. It did.
        help.lines().forEach { line ->
            assertFalse(
                DRAFT_AS_A_NOUN.containsMatchIn(line) && UNIVERSALLY.containsMatchIn(line),
                "A line calls what this interface sends a draft and quantifies over all of it: " +
                    "\"$line\". Three of the four commands produce no draft. Scope the statement " +
                    "to /${Command.DRAFT.word}, which is the one command it is true of.",
            )
        }
    }

    @Test
    fun `the promise that nothing goes out on the reader's behalf survives`() {
        // The half that must not be lost while the false half is removed — the acceptance check
        // asks for the promise to survive, not merely to stop overstating itself.
        assertTrue(
            NOTHING_ON_YOUR_BEHALF.containsMatchIn(help),
            "The help text no longer promises that nothing is sent on the reader's behalf. That " +
                "is the most important sentence in it and is true of every command.",
        )
    }

    @Test
    fun `the unknown-command reply takes the list rather than carrying one`() {
        // **The one guard that keeps this card fixed.** Every other case here holds the help text
        // to the declaration; this holds the mistyped-command reply to the help text, so the words
        // a reader is sent to after getting a command wrong cannot be a second, older list. Both
        // halves are asserted — that it takes the argument, and that it names no command of its
        // own — because an entry could take the placeholder and restate the list beside it.
        val entry = Catalogue.loaded().text(BotKey.UNKNOWN_COMMAND, null)
        assertTrue(
            ARGUMENT in entry,
            "The unknown-command entry does not take an argument, so nothing fills the command " +
                "list into it and a reader who mistyped is told only that they did. Entry: $entry",
        )
        // And a literal of its own, which is what tells the reply apart from the command list
        // itself. An entry that were the argument alone would pass every other case here and
        // deliver a reader who mistyped the same message as `/help` — saying nothing about the
        // mistake, which is the whole of what this reply is for. It is also what the live case
        // anchors on to prove the reply was not composed.
        assertTrue(
            entry.replace(ARGUMENT, "").isNotBlank(),
            "The unknown-command entry is its argument and nothing else, so the reply says only " +
                "what the command list says and a reader is not told they mistyped. Entry: $entry",
        )
        val words = Command.entries.map { it.word } + Commands.atTheEdge.keys
        assertFalse(
            words.isEmpty(),
            "No command is declared anywhere, so this iterates nothing and passes vacuously.",
        )
        words.forEach { word ->
            assertFalse(
                "/$word" in entry,
                "The unknown-command entry names /$word itself. Two lists can disagree, and the " +
                    "one read by somebody who has just got a command wrong is the worse of the " +
                    "two to have stale. Take $ARGUMENT and let the command list fill it. Entry: $entry",
            )
        }
    }

    @Test
    fun `the unknown-command reply, as delivered, names every command`() {
        // The forward half: the entry taking an argument says nothing about what reaches a reader.
        // Filled the way the handler fills it, so what this reads is the message as sent.
        val delivered = Catalogue.loaded().text(BotKey.UNKNOWN_COMMAND, null, listOf(BotKey.HELP))
        val words = Command.entries.map { it.word } + Commands.atTheEdge.keys
        assertFalse(
            words.isEmpty(),
            "No command is declared anywhere, so this iterates nothing and passes vacuously.",
        )
        words.forEach { word ->
            assertTrue(
                "/$word" in delivered,
                "The delivered reply to a mistyped command does not name /$word. Delivered: $delivered",
            )
        }
    }

    private fun SubjectKind.key(): BotKey = when (this) {
        // Exhaustive, so a kind added on the wire stops this compiling rather than going
        // unmentioned in the one text that tells a reader what can be asked.
        SubjectKind.CONTACT -> BotKey.KIND_CONTACT
        SubjectKind.CLIENT -> BotKey.KIND_CLIENT
        SubjectKind.BODY_OF_WORK -> BotKey.KIND_BODY_OF_WORK
        SubjectKind.CONVERSATION -> BotKey.KIND_CONVERSATION
        SubjectKind.INBOX -> BotKey.KIND_INBOX
    }

    private companion object {

        // Three probes into the floor locale, written as escapes rather than as characters.
        //
        // Escapes because no Kotlin file in this repository holds a corpus script, test source
        // included. The architecture rule reading `/src/main/` only is a limit of that check
        // rather than a licence, and an escape is the sanctioned way to name the script
        // deliberately — anything spelled this way is a review question rather than something a
        // grep has to adjudicate. The gloss above each one says what it means.
        //
        // In the bundle's own language rather than an English stand-in, because the text under
        // test is Russian: a probe written against a translation would pass on a bundle that says
        // nothing of the kind, which is the failure a check over prose exists to catch.
        //
        // They are stems, not sentences. What is asserted is that the text addresses the subject
        // at all — a check quoting a whole sentence would fail on every rewording while still
        // passing on a command quietly re-scoped.

        /** *question* — what follows the one command that takes no name. */
        val NAMES_A_QUESTION = Regex("\u0432\u043E\u043F\u0440\u043E\u0441", RegexOption.IGNORE_CASE)

        /** *draft*, the noun — what the old text called everything it sends. */
        val DRAFT_AS_A_NOUN = Regex("\u0447\u0435\u0440\u043D\u043E\u0432\u0438\u043A", RegexOption.IGNORE_CASE)

        /**
         * *all*, *every*, *any* — the stems that turn a true statement about one command into a
         * false one about four.
         *
         * Stems rather than whole words because each inflects, and the conjunction with
         * [DRAFT_AS_A_NOUN] is what keeps them from firing on an ordinary sentence: the corrected
         * text says *you always send it yourself*, which carries one of these and is true.
         */
        val UNIVERSALLY = Regex(
            "\u0432\u0441|\u043A\u0430\u0436\u0434|\u043B\u044E\u0431\u043E",
            RegexOption.IGNORE_CASE,
        )

        /**
         * What one emphasised run contains, so the text's whole emphasis budget can be read off.
         *
         * Built from the vocabulary rather than written out, so a tag added to the interface is a
         * tag this sees. Lazy and non-greedy, because two emphasised runs on one line must read as
         * two rather than as everything between the first opener and the last closer.
         */
        val EMPHASISED = Regex("${Regex.escape(BOLD_OPEN)}(.+?)${Regex.escape(BOLD_CLOSE)}")

        /**
         * The first positional argument, as the catalogue spells one.
         *
         * Not read off the catalogue, which substitutes literally and has no declaration of the
         * form to borrow. The bundle's own header states it, and it is spelled here exactly as
         * every other parameterised entry spells it.
         */
        const val ARGUMENT = "{0}"

        /** *on your behalf* — the half of the promise that is true of every command. */
        val NOTHING_ON_YOUR_BEHALF = Regex(
            "\u043E\u0442 \u0432\u0430\u0448\u0435\u0433\u043E \u0438\u043C\u0435\u043D\u0438",
            RegexOption.IGNORE_CASE,
        )
    }
}
