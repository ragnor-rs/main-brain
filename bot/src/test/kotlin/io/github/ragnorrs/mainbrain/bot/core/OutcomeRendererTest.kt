package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Cited
import io.github.ragnorrs.mainbrain.contract.Claimed
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.MenuOption
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.Outcome
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import io.github.ragnorrs.mainbrain.contract.SubjectRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertNotNull

/**
 * How an outcome becomes messages.
 *
 * Asserted on structure and on the prose that passes through, never on what the catalogue says: a
 * test spelling out a Russian sentence would put display text in Kotlin source, and it would go
 * red on a reworded translation that broke nothing. Where a reply has to carry a particular piece
 * of catalogue text, the expectation is read from the catalogue.
 */
class OutcomeRendererTest {

    private val catalogue = Catalogue.loaded()

    private val renderer = OutcomeRenderer(catalogue, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun `one answer is one message, carrying the prose exactly`() {
        val replies = renderer.render(responseOf(answerOf(PROSE)), null)
        assertEquals(1, replies.size)
        // Exactly, not merely containing. Anything added here is something a person has to delete
        // before using what they were given.
        assertEquals(PROSE, replies.single().body)
    }

    @Test
    fun `three drafts are three messages after the prompt, each numbered`() {
        val drafts = listOf("first draft", "second draft", "third draft")
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = drafts.map { MenuOption.Draft(it) },
        )
        val replies = renderer.render(responseOf(menu), null)

        assertEquals(1 + drafts.size, replies.size)
        assertEquals(catalogue.text(MessageKey.CHOOSE_A_DRAFT, null), replies.first().body)
        drafts.forEachIndexed { index, draft ->
            val reply = replies[index + 1].body
            assertTrue(reply.startsWith(bold(catalogue.text(BotKey.VARIANT, null, "${index + 1}")))) {
                "A draft's message opens with its number, emphasised; this one is: $reply"
            }
            assertTrue(reply.contains(draft)) { "The draft's own prose has to be in it: $reply" }
        }
    }

    @Test
    fun `a draft's message carries nothing but its header and its prose`() {
        // The alternatives line is its own message and the freshness note is a footer on the
        // prompt. On a draft either would be something somebody has to cut off before sending what
        // they were given, which is the one thing a draft's message cannot ask of a reader.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("one"), MenuOption.Draft("two")),
        )
        val response = AnswerResponse(
            outcome = menu,
            // Behind rather than current, so both clauses render and the note is as long as it
            // ever gets. With a current corpus the dated clause is suppressed and this case would
            // be checking the drafts against the shorter of the two notes.
            freshness = Freshness(completeThrough = STALE, lagging = listOf("TELEGRAM")),
            notUsed = listOf(SubjectRef(SubjectKind.CLIENT, "Acme")),
        )
        val replies = renderer.render(response, null)

        // The sentence as it is actually rendered, rather than the value that goes into it: the
        // interface words the corpus date now, so searching a draft for the wire's instant would
        // pass with the whole line appended.
        val said = assertNotNull(
            replies.first().footer,
            "nothing was said about the corpus, so the drafts below prove nothing",
        )

        val draftMessages = replies.subList(1, 3)
        draftMessages.forEach { reply ->
            assertFalse(reply.body.contains(said)) { "A draft carries no freshness: ${reply.body}" }
            assertNull(reply.footer) { "A draft carries no footer at all: ${reply.footer}" }
            assertFalse(reply.body.contains("Acme")) { "A draft names no alternative: ${reply.body}" }
        }
    }

    // ---- an answer's own structure, as a reader navigates it -------------------------------

    @Test
    fun `a section heading is emphasised and the findings under it are not`() {
        // **The first half of this card.** Measured on six shapes: headings arrived as plain text
        // on their own lines, indistinguishable from the findings beneath them, so a reply of
        // twenty findings across six sections had to be read start to finish to find anything.
        // The capability was never missing — the interface emphasised exactly one element, a
        // draft's header — what was missing is the wire saying which parts are headings.
        val answer = Outcome.SendText(
            body = listOf(
                Block.Heading("Where things stand"),
                Block.Prose("$ASSERTION$FINDING"),
                Block.Heading("Still open"),
                Block.Prose("$ASSERTION$OTHER_FINDING"),
            ),
        )

        val lines = renderer.render(responseOf(answer), null).single().body.lines()

        assertEquals(
            listOf(
                bold("Where things stand"),
                // The blank line this interface composes beneath a heading, so that the heading is
                // not read as the first words of the line under it.
                "",
                "$ASSERTION$FINDING",
                // And the one above a heading; the case below owns it.
                "",
                bold("Still open"),
                "",
                "$ASSERTION$OTHER_FINDING",
            ),
            lines,
        ) { "A heading is emphasised, a finding is not: $lines" }
    }

    /**
     * One blank line between every element, whatever the model wrote.
     *
     * **The defect this fixes was intermittent, which is what made it worth a rail.** The same
     * question asked twice came back dense once and spaced once: a section arrives as its lines
     * joined by one newline, and whatever blank lines the model happened to leave inside survived
     * into the reply. So whether a fifteen-item answer could be scanned was the model's to decide
     * and nothing could fail when it decided badly.
     *
     * Asserted in both directions from one input: a part whose lines the model left bare and a part
     * where it left a blank line of its own come out identical, and neither carries a doubled blank.
     */
    @Test
    fun `items are separated by one blank line however the model spaced them`() {
        val dense = Outcome.SendText(
            body = listOf(Block.Prose("$ASSERTION$FINDING\n$ASSERTION$OTHER_FINDING")),
        )
        val spaced = Outcome.SendText(
            body = listOf(Block.Prose("$ASSERTION$FINDING\n\n$ASSERTION$OTHER_FINDING")),
        )

        val fromDense = renderer.render(responseOf(dense), null).single().body
        val fromSpaced = renderer.render(responseOf(spaced), null).single().body

        assertEquals("$ASSERTION$FINDING\n\n$ASSERTION$OTHER_FINDING", fromDense) {
            "Items the model ran together are not separated: $fromDense"
        }
        assertEquals(fromDense, fromSpaced) {
            "The same answer renders two ways depending on how the model spaced it: $fromSpaced"
        }
        assertFalse(fromSpaced.contains("\n\n\n")) { "A doubled blank line reached a reader: $fromSpaced" }
    }

    @Test
    fun `a heading is set off from what precedes it by a blank line`() {
        // **Emphasis alone does not separate a section; the space above it does.** The model used
        // to write that blank line itself, as a piece of its own, and the answer reached a reader
        // as one string in which it survived. Parts make the spacing this interface's to decide —
        // which is the point, since it no longer depends on the model having written one — but it
        // has to actually be decided, and a heading rendered flush against the finding above it is
        // the defect this whole card is about arriving one line further down.
        val answer = Outcome.SendText(
            body = listOf(
                Block.Heading("Where things stand"),
                Block.Prose("$ASSERTION$FINDING"),
                Block.Heading("Still open"),
                Block.Prose("$ASSERTION$OTHER_FINDING"),
            ),
        )

        val body = renderer.render(responseOf(answer), null).single().body

        assertTrue(body.contains("$FINDING\n\n${bold("Still open")}")) {
            "a heading sits flush against the line above it, so the sections do not separate: $body"
        }
        // And not above the first, where there is nothing to separate it from.
        assertTrue(body.startsWith(bold("Where things stand"))) { body }
    }

    @Test
    fun `a heading is escaped before it is emphasised`() {
        // The two have to happen in this order, and the wrong one is not merely untidy: escaping
        // after the tags were added would turn them into the characters a reader sees, and
        // emphasising without escaping first would let an ampersand in a section's name make the
        // platform reject the whole message.
        val answer = Outcome.SendText(body = listOf(Block.Heading("Costs & scope")))

        val line = renderer.render(responseOf(answer), null).single().body

        // Also the case that pins the blank line beneath a heading to a heading with something
        // under it: nothing follows this one, and a trailing blank line is one the platform keeps.
        assertEquals(bold("Costs &amp; scope"), line)
        assertFalse(line.contains("&lt;b&gt;")) { "the emphasis itself was escaped: $line" }
    }

    @Test
    fun `one variant is a plain answer rather than a menu of one`() {
        // The backend decides this, not the renderer — it sends `SendText` for a single variant.
        // Asserted here because a menu of one and a plain reply read identically to a person, and
        // this is the interface that would have to tell them apart if it did not.
        val replies = renderer.render(responseOf(answerOf(PROSE)), null)
        assertEquals(1, replies.size)
    }

    @Test
    fun `candidates to choose between are one message, not one each`() {
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CLIENT, "Acme")),
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "Acme Support")),
            ),
        )
        val replies = renderer.render(responseOf(menu), null)
        assertEquals(1, replies.size)
        val body = replies.single().body
        assertTrue(body.contains("Acme")) { body }
        assertTrue(body.contains("Acme Support")) { body }
        assertTrue(body.contains(catalogue.text(MessageKey.SUBJECT_AMBIGUOUS, null))) { body }
    }

    @Test
    fun `a candidate the corpus holds no name for is still listed`() {
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CONVERSATION, label = null)),
                MenuOption.Subject(SubjectRef(SubjectKind.CONVERSATION, "a titled thread")),
            ),
        )
        val body = renderer.render(responseOf(menu), null).single().body
        assertFalse(body.contains("null")) { "A missing label is said, not printed: $body" }
        assertTrue(body.contains(catalogue.text(BotKey.KIND_CONVERSATION, null))) { body }
    }

    @Test
    fun `two people of one name are told apart by their handles`() {
        // What a reader sees for the case the menu exists for. Without the handles both lines read
        // identically, and a menu answered by naming an option then has two answers that mean the
        // same thing and neither of them this one.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "One Name", handle = "the_one")),
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "One Name", handle = "the_other")),
            ),
        )

        val lines = renderer.render(responseOf(menu), null).single().body.lines()

        assertTrue(lines.any { it.contains("the_one") }) { lines.toString() }
        assertTrue(lines.any { it.contains("the_other") }) { lines.toString() }
        // The two option lines differ by more than their number, which is the whole point.
        val options = lines.filter { it.contains("One Name") }
        assertEquals(2, options.size) { options.toString() }
        assertNotEquals(
            options[0].substringAfter(". "),
            options[1].substringAfter(". "),
        ) { "Both options read the same, so naming one of them cannot pick it: $options" }
    }

    @Test
    fun `a subject with a handle and no name is listed by the handle`() {
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, label = null, handle = "nameless")),
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "a named one")),
            ),
        )

        val body = renderer.render(responseOf(menu), null).single().body

        assertTrue(body.contains("nameless")) { body }
        assertFalse(body.contains("null")) { "A missing label is said, not printed: $body" }
    }

    @Test
    fun `a handle is escaped like every other value that came from elsewhere`() {
        // It is read off a record, so the markup rule applies to it as much as to a label: a reply
        // body carries markup, and an unescaped angle bracket makes the platform refuse the message.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "a name", handle = "a<handle>&more")),
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "another")),
            ),
        )

        val body = renderer.render(responseOf(menu), null).single().body

        assertFalse(body.contains("a<handle>")) { body }
        assertTrue(body.contains("&lt;")) { body }
    }

    @Test
    fun `a status with nothing to qualify it is the key alone`() {
        val replies = renderer.render(responseOf(Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM)), null)
        assertEquals(catalogue.text(MessageKey.NOTHING_TO_ANSWER_FROM, null), replies.single().body)
    }

    @Test
    fun `an answer nobody could read is not said in the words an empty corpus is said in`() {
        // The key alone, like any unqualified status — and a different sentence from
        // nothing-to-answer-from, or the reader whose material did answer is told it holds nothing
        // and goes looking for a gap that is not there instead of asking again. The backend pins
        // which key each answer maps to; this is the half that pins what the reader is handed, and
        // the two catalogue lines collapsing into one wording would pass every other gate.
        val replies = renderer.render(responseOf(Outcome.SendStatus(MessageKey.ANSWER_UNREADABLE)), null)

        val body = replies.single().body
        assertEquals(catalogue.text(MessageKey.ANSWER_UNREADABLE, null), body)
        assertFalse(body == catalogue.text(MessageKey.NOTHING_TO_ANSWER_FROM, null)) { body }
    }

    @Test
    fun `a subject that was never read is said without a name or a list of kinds`() {
        // It carries neither, so the reply is the key alone — and the key has to be a different
        // one from nothing-found, or the reader is told their subject is absent from the corpus
        // when what is absent is the name.
        val replies = renderer.render(responseOf(Outcome.SendStatus(MessageKey.SUBJECT_NOT_READ)), null)

        val body = replies.single().body
        assertEquals(catalogue.text(MessageKey.SUBJECT_NOT_READ, null), body)
        assertFalse(body == catalogue.text(MessageKey.SUBJECT_NOT_FOUND, null)) { body }
    }

    @Test
    fun `a command given no subject is handed a reply that asks for one`() {
        // The measured defect, at the end it was read at: a bare command rendered as four words
        // saying nothing suitable was found. The reader's next action is to say the command again
        // with a name, and this reply is the only place that can tell them — so what it must not be
        // is the nothing-found wording, which reports a search and sends them after the material.
        val replies = renderer.render(responseOf(Outcome.SendStatus(MessageKey.SUBJECT_MISSING)), null)

        val body = replies.single().body
        assertEquals(catalogue.text(MessageKey.SUBJECT_MISSING, null), body)
        assertFalse(body == catalogue.text(MessageKey.SUBJECT_NOT_FOUND, null)) { body }
        assertFalse(body == catalogue.text(MessageKey.SUBJECT_NOT_READ, null)) { body }
    }

    @Test
    fun `a status naming what was searched for says the name and the kinds`() {
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            subjectName = "Acme",
            searched = listOf(SubjectKind.CLIENT, SubjectKind.CONTACT),
        )
        val body = renderer.render(responseOf(outcome), null).single().body
        assertTrue(body.contains("Acme")) { body }
        assertTrue(body.contains(catalogue.text(BotKey.SEARCHED_CLIENTS, null))) { body }
        assertTrue(body.contains(catalogue.text(BotKey.SEARCHED_CONTACTS, null))) { body }
    }

    @Test
    fun `a kind inside the coverage clause is worded apart from the same kind standing alone`() {
        // The whole reason the second set of entries exists: the floor locale inflects a member of
        // a list after its preposition differently from a kind naming a subject on its own, and the
        // clause read as broken Russian while the two shared one wording. Asserted against each
        // other rather than against a literal, which would pin this file to one locale — and
        // asserted at all because every other case reads its expected value out of the catalogue,
        // so collapsing the two wordings would leave them all green.
        listOf(
            BotKey.SEARCHED_CONTACTS to BotKey.KIND_CONTACT,
            BotKey.SEARCHED_CLIENTS to BotKey.KIND_CLIENT,
            BotKey.SEARCHED_BODIES_OF_WORK to BotKey.KIND_BODY_OF_WORK,
            BotKey.SEARCHED_CONVERSATIONS to BotKey.KIND_CONVERSATION,
        ).forEach { (inList, alone) ->
            assertNotEquals(
                catalogue.text(alone, null),
                catalogue.text(inList, null),
                "$inList and $alone resolve to one wording, so the split that exists to make the " +
                    "coverage clause grammatical has been undone.",
            )
        }
    }

    @Test
    fun `the inbox is not rendered as somewhere a name was looked for`() {
        // Unreachable from this build's backend, which refuses the claim at the domain type. Asserted
        // here because this is the sentence a reader reads, and a list that overstates its coverage
        // sends them to a conclusion they cannot check — that the subject is absent from the corpus.
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            subjectName = "Acme",
            searched = listOf(SubjectKind.CONTACT, SubjectKind.INBOX),
        )

        val body = renderer.render(responseOf(outcome), null).single().body

        // Asserted as equality against the same reply without it, rather than as the absence of one
        // word: the inbox worded through any entry at all is the fault, so a regression that reached
        // for a different one would pass a check that only looked for this one's.
        val withoutIt = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            subjectName = "Acme",
            searched = listOf(SubjectKind.CONTACT),
        )
        assertEquals(renderer.render(responseOf(withoutIt), null).single().body, body)
        assertTrue(body.contains(catalogue.text(BotKey.SEARCHED_CONTACTS, null))) {
            "Dropped the coverage that was real along with the inbox: $body"
        }
    }

    @Test
    fun `near spellings reach the reader as names to ask again with`() {
        // **The sentence this card is about.** A reader whose subject the corpus keeps under another
        // form was told nothing suitable was found, which is a statement about the corpus and sends
        // them to the one conclusion they cannot check. What they get instead is the spelling, which
        // nothing else in this interface could have shown them.
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NEAREST_NAMES,
            subjectName = TYPED_IN_CORPUS_SCRIPT,
            searched = listOf(SubjectKind.CONTACT),
            nearest = listOf(
                SubjectRef(kind = SubjectKind.CONTACT, label = "Arbour Warden", handle = "arbour"),
                SubjectRef(kind = SubjectKind.CLIENT, label = "Arbitrage Desk"),
            ),
        )

        val body = renderer.render(responseOf(outcome), null).single().body

        // One message, because a near form is a label and a kind — short, and nothing anybody
        // forwards. The reason drafts are separate messages does not reach a list of names.
        assertTrue(body.contains("Arbour Warden")) { body }
        assertTrue(body.contains("Arbitrage Desk")) { body }
        // The account of the search survives beside them: what was asked for and where it was
        // looked for are what let a reader judge the offer rather than take it.
        assertTrue(body.contains(TYPED_IN_CORPUS_SCRIPT)) { body }
        assertTrue(body.contains(catalogue.text(BotKey.SEARCHED_CONTACTS, null))) { body }
        // And it is not the absence. Asserted against that reply's own body rather than against a
        // phrase, so a near miss that reverted to the old wording fails here whatever it was worded
        // as.
        val absence = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            subjectName = TYPED_IN_CORPUS_SCRIPT,
            searched = listOf(SubjectKind.CONTACT),
        )
        assertNotEquals(renderer.render(responseOf(absence), null).single().body, body) { body }
        assertFalse(body.contains(catalogue.text(MessageKey.SUBJECT_NOT_FOUND, null))) {
            "Told the reader nothing was found and then offered them a spelling of it: $body"
        }
    }

    @Test
    fun `a near spelling is run into the sentence rather than numbered as a choice`() {
        // Numbering is how this interface says *pick one*, and these are not pickable: they were
        // found to be spelled nearly like the name, not to be what it meant. Offered as a menu they
        // would ask a reader to choose between a stranger and the person they meant, which is the
        // error the menu rule exists to refuse.
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NEAREST_NAMES,
            nearest = listOf(
                SubjectRef(kind = SubjectKind.CONTACT, label = "Arbour Warden"),
                SubjectRef(kind = SubjectKind.CONTACT, label = "Arbitrage Desk"),
            ),
        )

        val body = renderer.render(responseOf(outcome), null).single().body

        assertFalse(body.contains("\n")) { "Laid the near spellings out as a menu's lines: $body" }
        assertFalse(body.contains("1.")) { "Numbered a near spelling, which asks the reader to pick one: $body" }
    }

    @Test
    fun `the near-spelling clause is worded apart from the clause naming what was searched`() {
        // The two sit in one sentence chain and mean opposite things: one is where a name was looked
        // for, the other is what the corpus turned out to hold. Collapsing them presents the near
        // spellings to the reader as the places that were searched — the confusion the wire contract
        // spends three paragraphs refusing, and the one a reader has no way to detect.
        //
        // Pinned against each other rather than against a literal, which would tie this file to one
        // locale. Asserted at all because every other case here reads its expectation out of the
        // same catalogue, so one wording replacing the other would leave them all green.
        assertNotEquals(
            catalogue.text(BotKey.SEARCHED_AMONG, null, "x"),
            catalogue.text(BotKey.NEAREST_NAMES, null, "x"),
            "The near spellings and the kinds searched resolve to one wording, so a reader is told " +
                "the forms the corpus holds are the places their name was looked for.",
        )
    }

    @Test
    fun `a near spelling carrying a label is escaped like every other value from elsewhere`() {
        // It is a display name a source wrote, so it reaches this interface as text somebody else
        // chose. Unescaped it is markup inside a message, which the platform either refuses or
        // renders — and a refused send drops whatever was queued behind it.
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NEAREST_NAMES,
            nearest = listOf(SubjectRef(kind = SubjectKind.CONTACT, label = "<b>Arbour</b> & Co")),
        )

        val body = renderer.render(responseOf(outcome), null).single().body

        assertTrue(body.contains("&lt;b&gt;Arbour&lt;/b&gt; &amp; Co")) { body }
        assertFalse(body.contains("<b>Arbour</b>")) { "A label's own markup reached the reader: $body" }
    }

    @Test
    fun `a refusal that searched nothing says nothing about coverage`() {
        // An empty list is a refusal with nothing truthful to say about coverage — an ambiguity
        // with nothing to choose between, which holds what matched rather than what was covered.
        // The clause is omitted rather than rendered empty. The wire permits the shape whatever
        // produces it, so this holds the renderer to it rather than to the producers of the day.
        val outcome = Outcome.SendStatus(key = MessageKey.SUBJECT_NOT_FOUND, searched = emptyList())

        val body = renderer.render(responseOf(outcome), null).single().body

        assertEquals(catalogue.text(MessageKey.SUBJECT_NOT_FOUND, null), body)
    }

    @Test
    fun `the inbox alone leaves no coverage clause rather than an empty one`() {
        // The frame takes a list, so dropping the only member must drop the sentence too — a
        // "looked among:" with nothing after it is worse than silence.
        val outcome = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            searched = listOf(SubjectKind.INBOX),
        )

        val body = renderer.render(responseOf(outcome), null).single().body

        assertEquals(catalogue.text(MessageKey.SUBJECT_NOT_FOUND, null), body)
    }

    @Test
    fun `the subjects that lost are named in a message of their own`() {
        val response = AnswerResponse(
            outcome = answerOf(PROSE),
            freshness = Freshness(),
            notUsed = listOf(SubjectRef(SubjectKind.CLIENT, "Acme Holdings")),
        )
        val replies = renderer.render(response, null)
        assertEquals(2, replies.size)
        assertTrue(replies.last().body.contains("Acme Holdings")) { replies.last().body }
    }

    @Test
    fun `how current the corpus is rides on the answer rather than arriving after it`() {
        // **The second half of this card: the note was its own message on every shape measured.**
        // Detached from the answer it qualifies there is nothing for a reader to attach it to, and
        // on a reply long enough to split it arrived as a third message whose whole content was a
        // sentence about somebody else's sentence.
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = STALE, lagging = listOf("TELEGRAM")),
        )
        val replies = renderer.render(response, null)

        assertEquals(1, replies.size) { "the note is a footer, not a message: ${replies.map { it.body }}" }
        val said = assertNotNull(replies.single().footer)
        assertTrue(said.contains(catalogue.text(BotKey.AGE_HOURS_FEW, null, "3"))) { said }
        assertTrue(said.contains(catalogue.text(BotKey.SOURCE_TELEGRAM, null))) { said }
        // It is not in the body either, which is what makes the field load-bearing rather than
        // decorative: whoever splits the body is what keeps it off a message of its own.
        assertFalse(replies.single().body.contains(said)) { replies.single().body }
    }

    @Test
    fun `a current corpus is not remarked on at all`() {
        // **The first half of the note's defect, and the commonest case by far.** Every shape
        // measured ended with a sentence stating the material had been collected just now, which
        // is a thing a reader can do nothing with. The note exists to stop somebody relying on
        // stale material; on a current corpus there is nothing to stop.
        val response = AnswerResponse(
            outcome = answerOf(PROSE),
            freshness = Freshness(completeThrough = INSTANT),
        )
        val replies = renderer.render(response, null)

        assertEquals(1, replies.size)
        assertNull(replies.single().footer) {
            "a current corpus was remarked on anyway: ${replies.single().footer}"
        }
        // Calibration: the same shape of response with a corpus that *is* behind does carry one,
        // so the assertion above is about the corpus being current and not about the note never
        // being rendered.
        val behind = AnswerResponse(outcome = answerOf(PROSE), freshness = Freshness(completeThrough = STALE))
        assertNotNull(
            renderer.render(behind, null).single().footer,
            "nothing is ever said about the corpus, so the case above discriminates nothing",
        )
    }

    @Test
    fun `a reply that retrieved nothing still says the corpus is behind`() {
        // **Scoped to the corpus and not to whether anything was found, which is the distinction
        // the suppression above must not swallow.** *Nothing was found* and *nothing was found
        // yet* are the same reply to a reader, so a name missing because ingestion is behind is
        // exactly the case where saying so is the answer. What was dropped is the current corpus,
        // where neither reply had anything to qualify.
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.SUBJECT_NOT_FOUND, subjectName = "nobody by that name"),
            freshness = Freshness(lagging = listOf("TELEGRAM")),
        )
        val replies = renderer.render(response, null)

        assertEquals(1, replies.size)
        val said = assertNotNull(
            replies.single().footer,
            "a refusal on a lagging corpus does not say why it found nothing",
        )
        assertTrue(said.contains(catalogue.text(BotKey.SOURCE_TELEGRAM, null))) { said }
    }

    @Test
    fun `a refusal on a current corpus asserts no collection`() {
        // The worst instance measured: a two-line refusal where nothing resolved, nothing was
        // retrieved and no answering call was made, followed by a second message stating that the
        // material had been collected just now. Not merely vacuous — it describes work that did
        // not happen, on the one reply where the reader most needs to see the system never looked.
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.SUBJECT_NOT_READ),
            freshness = Freshness(completeThrough = INSTANT),
        )
        val replies = renderer.render(response, null)

        assertEquals(1, replies.size) { "a refusal is one message: ${replies.map { it.body }}" }
        assertNull(replies.single().footer) { "the refusal asserts a collection: ${replies.single().footer}" }
    }

    @Test
    fun `an answer that knows nothing about the corpus says nothing about it`() {
        val replies = renderer.render(responseOf(answerOf(PROSE)), null)
        assertEquals(1, replies.size)
        assertNull(replies.single().footer)
    }

    // ---- how current the corpus is, as a person reads it -----------------------------------

    @Test
    fun `the line a reader sees carries no machine instant`() {
        // The defect this issue is: the wire's instant, microseconds and timezone suffix and
        // all, interpolated into a sentence unchanged. Calibrated below by putting the raw value
        // back through the same assertion, so a green here means the shape is absent rather than
        // that the pattern never matched anything.
        val said = freshnessLine(Duration.ofHours(3))

        assertFalse(MACHINE_INSTANT.containsMatchIn(said)) { said }
        assertFalse(said.contains(INSTANT)) { said }
        assertFalse(said.contains("Z")) { "a timezone suffix reached the reader: $said" }

        assertTrue(MACHINE_INSTANT.containsMatchIn(INSTANT)) {
            "The pattern does not match the value it exists to forbid, so the case above " +
                "discriminates nothing: $INSTANT"
        }
    }

    @Test
    fun `how old the material is, said in the words the count calls for`() {
        // Every bound and every grammatical number in one table, because the plural rule has a
        // branch that only the teens reach: eleven ends in a one and takes the many form anyway,
        // so a rule tested on single digits alone passes while rendering eleven wrongly.
        //
        // The table starts at one hour because below that nothing is said at all — the bound the
        // wording used to call *just now* is now where the sentence is suppressed, which the case
        // above owns. Both readings come off one figure in `Age`, so they cannot disagree.
        val cases = listOf(
            Duration.ofHours(1) to (BotKey.AGE_HOURS_ONE to "1"),
            Duration.ofHours(3) to (BotKey.AGE_HOURS_FEW to "3"),
            Duration.ofHours(5) to (BotKey.AGE_HOURS_MANY to "5"),
            Duration.ofHours(11) to (BotKey.AGE_HOURS_MANY to "11"),
            Duration.ofHours(21) to (BotKey.AGE_HOURS_ONE to "21"),
            Duration.ofHours(23) to (BotKey.AGE_HOURS_FEW to "23"),
            Duration.ofHours(24) to (BotKey.AGE_DAYS_ONE to "1"),
            Duration.ofDays(2) to (BotKey.AGE_DAYS_FEW to "2"),
            Duration.ofDays(14) to (BotKey.AGE_DAYS_MANY to "14"),
            Duration.ofDays(21) to (BotKey.AGE_DAYS_ONE to "21"),
            Duration.ofDays(30) to (BotKey.AGE_DAYS_MANY to "30"),
            Duration.ofDays(31) to (BotKey.AGE_LONG_AGO to null),
        )

        cases.forEach { (elapsed, expected) ->
            val (key, count) = expected
            val age = count?.let { catalogue.text(key, null, it) } ?: catalogue.text(key, null)
            assertEquals(
                catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, age),
                freshnessLine(elapsed),
            ) { "$elapsed of elapsed material is not said as $key" }
        }
    }

    @Test
    fun `the days in an age are the corpus's calendar days, which are the ones a reader counts`() {
        // **A reply carries dates as well as this line, and both describe the same material.** A
        // reader shown an age beside a block of dates checks it by counting the dates — so counted
        // as whole elapsed periods the figure comes up one short of their own count, and the two
        // figures in one reply disagree with nothing to choose between them.
        //
        // Forty-one hours is the case: one whole elapsed period, two of the calendar's days. The
        // calibration is the pair below it, which is what keeps the fix from being "add a day":
        // a bound under a day is still elapsed time, because currency is the question there and
        // there is no date in front of the reader to check it against.
        assertEquals(
            catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, catalogue.text(BotKey.AGE_DAYS_FEW, null, "2")),
            freshnessLine(Duration.ofHours(41)),
        ) { "an age spanning two of the corpus's days is counted as the periods that have elapsed" }

        assertEquals(
            catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, catalogue.text(BotKey.AGE_HOURS_MANY, null, "13")),
            freshnessLine(Duration.ofHours(13)),
        ) { "material from yesterday evening is called a day old, where what was asked is how current it is" }
    }

    @Test
    fun `which calendar the days are counted on is the corpus's, not the clock's`() {
        // The same instants said two ways, which is the only form that shows the zone is read at
        // all: a deployment in UTC cannot tell a count that follows the correspondents from one
        // that follows the clock. Eastward the material falls on the next day, so the span across
        // it is one day shorter.
        val eastward = OutcomeRenderer(catalogue, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.ofHours(9))
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = NOW.minus(Duration.ofHours(41)).toString()),
        )

        assertEquals(
            catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, catalogue.text(BotKey.AGE_DAYS_ONE, null, "1")),
            eastward.render(response, null).single().footer,
        ) { "the configured zone is not the calendar the days are counted on" }
    }

    @Test
    fun `a day old where the clocks went back is still a day old, not nought days`() {
        // **A whole elapsed period that lands on the day it is read.** Where a zone puts its
        // clocks back, twenty-four real hours advance the wall clock by twenty-three — so
        // material from half past midnight is read at half past eleven the *same* local evening,
        // and the calendar between the two dates is empty. The branch is past the sub-day bounds
        // by then, so the count cannot be nought: that is no age at all, and in the floor locale
        // it takes the many form and reads as a plural of nothing.
        val backward = ZoneId.of("America/New_York")
        val read = Instant.parse("2026-11-02T04:30:00Z")
        val renderer = OutcomeRenderer(catalogue, Clock.fixed(read, ZoneOffset.UTC), backward)
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = read.minus(Duration.ofHours(24)).toString()),
        )

        assertEquals(
            catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, catalogue.text(BotKey.AGE_DAYS_ONE, null, "1")),
            renderer.render(response, null).single().footer,
        ) { "a day-old corpus is not said as a day old on the day the clocks go back" }

        // Calibration: the two dates really are one, so the case discriminates. Without it a
        // green above would only mean the zone has no such day.
        assertEquals(
            read.minus(Duration.ofHours(24)).atZone(backward).toLocalDate(),
            read.atZone(backward).toLocalDate(),
        ) { "the instants fall on two different local days, so nothing here is being clamped" }
    }

    @Test
    fun `a corpus dated ahead of the clock says nothing, rather than a negative age`() {
        // Two clocks that disagree, which is a deployment's business and not a reader's. It falls
        // under the same bound as within-the-hour, so there is no count to render a minus sign in
        // — and now no sentence either, which is the stronger version of the same guarantee.
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = NOW.plus(Duration.ofHours(2)).toString()),
        )
        assertNull(renderer.render(response, null).single().footer) {
            "a corpus dated in the future was remarked on"
        }
    }

    @Test
    fun `an instant that cannot be read costs the sentence and not the answer`() {
        // The wire promises an instant, so a value that is not one says the service is wrong
        // rather than that the corpus is old. Nothing is said about that half, and — the part
        // that matters — the answer still goes out.
        val response = AnswerResponse(
            outcome = answerOf(PROSE),
            freshness = Freshness(completeThrough = "whenever", lagging = listOf("TELEGRAM")),
        )
        val replies = renderer.render(response, null)

        assertEquals(1, replies.size)
        assertEquals(PROSE, replies.single().body)
        val said = assertNotNull(replies.single().footer)
        assertFalse(said.contains("whenever")) { "the unreadable value reached the reader: $said" }
        // The lagging half is independent and still said, so the drop is the one sentence.
        assertEquals(
            catalogue.text(BotKey.CORPUS_LAGGING, null, catalogue.text(BotKey.SOURCE_TELEGRAM, null)),
            said,
        )
    }

    @Test
    fun `a lagging source is named as a reader knows it, not as the service spells it`() {
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(lagging = listOf("TELEGRAM")),
        )
        val said = assertNotNull(renderer.render(response, null).single().footer)

        assertEquals(
            catalogue.text(BotKey.CORPUS_LAGGING, null, catalogue.text(BotKey.SOURCE_TELEGRAM, null)),
            said,
        )
        // The constant itself, which is what the wire carries and what used to be shown.
        assertFalse(said.contains("TELEGRAM")) { said }
    }

    @Test
    fun `a source this interface has no word for is named as the wire spelled it`() {
        // The contract types a source as a string rather than sharing the service's enum, so a
        // source added on one side and not the other is reachable. Showing its constant is worse
        // than showing nothing in only one way, and losing the fact that something is behind is
        // worse in several.
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(lagging = listOf("TELEGRAM", "SOMETHING_LATER")),
        )
        val said = assertNotNull(renderer.render(response, null).single().footer)

        assertTrue(said.contains(catalogue.text(BotKey.SOURCE_TELEGRAM, null))) { said }
        assertTrue(said.contains("SOMETHING_LATER")) { said }
    }

    /** What is said about the corpus, for material gathered [elapsed] before now. */
    private fun freshnessLine(elapsed: Duration): String {
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = NOW.minus(elapsed).toString()),
        )
        return assertNotNull(
            renderer.render(response, null).single().footer,
            "nothing was said about a corpus $elapsed behind",
        )
    }

    @Test
    fun `a reader's locale decides the words`() {
        val localised = OutcomeRenderer(
            Catalogue.loaded(listOf(Catalogue.FLOOR, "xx")),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ZoneOffset.UTC,
        )
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("only")),
        )
        val replies = localised.render(responseOf(menu), "xx")
        assertEquals("Pick one.", replies.first().body)
        assertTrue(replies[1].body.startsWith(bold("Option 1"))) { replies[1].body }
    }

    // ---- a body carries markup, so everything else has to be escaped -----------------------

    @Test
    fun `a model's prose is escaped, because an unescaped answer is no answer at all`() {
        // The platform rejects a message whose markup it cannot parse, so one reserved character
        // in the material costs the whole reply — and only for the questions whose material
        // happens to contain one, which is the hardest kind of failure to notice.
        val hostile = "Acme & Co. offered < 5%, and wrote <b>urgent</b>"
        val replies = renderer.render(responseOf(answerOf(hostile)), null)

        assertEquals(escaped(hostile), replies.single().body)
        // The model's own tag reaches the reader as the characters it typed. The interface decides
        // the formatting; honouring markup the model volunteered would make it a second author.
        assertFalse(replies.single().body.contains("<b>")) { replies.single().body }
    }

    @Test
    fun `a draft's prose is escaped and only the header is emphasised`() {
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("Terms & conditions < attached")),
        )
        val draft = renderer.render(responseOf(menu), null)[1].body

        assertTrue(draft.contains("Terms &amp; conditions &lt; attached")) { draft }
        // One emphasis, on the header, and nothing in the part a person forwards.
        assertEquals(1, Regex("<b>").findAll(draft).count()) { draft }
        assertTrue(draft.endsWith("attached")) { "something was appended to a draft: $draft" }
    }

    @Test
    fun `a menu emphasises whichever token tells one option from another`() {
        // **The case the ambiguity menu exists for.** Two candidates share a display name — that
        // is why the menu is offered at all — so the label is the one token that cannot be
        // answered with: naming it returns the same menu. The handle names one of them, and
        // `option`'s own KDoc says so. Emphasising the shared label would set off text that is
        // identical on both lines, which spends the emphasis to say nothing.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "Acme", handle = "acme_one")),
                MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "Acme", handle = "acme_two")),
            ),
        )

        val said = renderer.render(responseOf(menu), null).single().body
        val emphasised = Regex("${Regex.escape(BOLD_OPEN)}(.+?)${Regex.escape(BOLD_CLOSE)}")
            .findAll(said)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(2, emphasised.size) { "one emphasised run per option is what a reader scans: $said" }
        assertEquals(emphasised.toSet().size, emphasised.size) {
            "Both options emphasise the same text, so the emphasis distinguishes nothing — which " +
                "is the whole purpose of this menu: $said"
        }
        emphasised.forEach { run ->
            assertTrue(run.contains("acme_one") || run.contains("acme_two")) {
                "A menu line sets off \"$run\", which is not the token that names one candidate " +
                    "rather than the other: $said"
            }
        }
    }

    @Test
    fun `no rendered reply nests emphasis, across the one seam no bundle check can see`() {
        // **The hazard is the composition, not the entry.** `markupFaults` refuses a bundle entry
        // that nests, but it reads one entry at a time — and this interface also wraps an entry in
        // emphasis itself. An entry that gained a tag of its own would nest at that seam, where no
        // check over the bundle is looking, and the splitter would then throw on the first reply
        // long enough to cut and deliver nothing at all.
        //
        // Asserted over every shape rather than the draft alone, because which keys this interface
        // emphasises is a thing that changes: a depth counter over a rendered body holds whatever
        // the renderer does next, where naming today's keys would not. For the same reason the
        // counter walks the vocabulary rather than one tag — with a second emphasis added and the
        // counter left reading the first, `<i>…<b>…</b>…</i>` scores a depth of one and the seam
        // this case exists for is open again.
        //
        // **An answer is in the list for the same reason, and has to carry claims.** The two seams
        // the interface composes are a heading wrapped in bold and the block saying what the reply
        // rests on wrapped in the weaker emphasis — and the second is rendered only where there is
        // something to rest on, so a fixture of menus and refusals exercises neither.
        val rendered = listOf(
            Outcome.SendText(
                body = listOf(Block.Heading("Where things stand"), Block.Prose("$ASSERTION$FINDING")),
                claims = listOf(Claimed(text = FINDING, cites = listOf(aCitation(author = "who wrote it")))),
            ),
            Outcome.SendMenu(
                prompt = MessageKey.CHOOSE_A_DRAFT,
                options = listOf(
                    MenuOption.Draft(
                        prose = "a draft",
                        claims = listOf(Claimed(text = FINDING, cites = listOf(aCitation(author = "who")))),
                    ),
                    MenuOption.Draft("another"),
                ),
            ),
            Outcome.SendMenu(
                prompt = MessageKey.SUBJECT_AMBIGUOUS,
                options = listOf(
                    MenuOption.Subject(SubjectRef(SubjectKind.CLIENT, "Acme")),
                    MenuOption.Subject(SubjectRef(SubjectKind.CONTACT, "Acme", handle = "acme")),
                    MenuOption.Subject(SubjectRef(SubjectKind.INBOX, null)),
                ),
            ),
            Outcome.SendStatus(
                key = MessageKey.SUBJECT_NOT_READ,
                subjectName = "Acme",
                searched = listOf(SubjectKind.CLIENT),
            ),
            // The near-spelling reply composes one clause more than the refusal above it, from an
            // entry of this interface's own, so it is a seam of the kind this case exists for.
            Outcome.SendStatus(
                key = MessageKey.SUBJECT_NEAREST_NAMES,
                subjectName = "Acme",
                searched = listOf(SubjectKind.CLIENT),
                nearest = listOf(SubjectRef(SubjectKind.CONTACT, "Acme Holdings", handle = "acme")),
            ),
        ).flatMap { renderer.render(responseOf(it), null) }.map { it.body }

        // Calibration: both emphases really are in the fixture, so a counter that saw neither
        // would be reported here rather than passing as depth nought.
        MARKUP.keys.forEach { opener ->
            assertTrue(rendered.any { opener in it }) {
                "No rendered reply in the fixture carries '$opener', so the seam it opens is not " +
                    "covered: $rendered"
            }
        }

        rendered.forEach { body ->
            val (deepest, open) = nestingOf(body)
            assertEquals(0, open) { "emphasis does not balance in a rendered reply: $body" }
            assertTrue(deepest <= 1) {
                "a rendered reply nests emphasis $deepest deep. The splitter reserves room for " +
                    "one closing tag and refuses a piece that left more open, so this is " +
                    "delivered until a reply is long enough to cut and then not at all: $body"
            }
        }
    }

    /**
     * How deep [body]'s emphasis nests, and what it leaves open — read the way the splitter reads.
     *
     * Over the whole vocabulary and not one tag, which is what makes that claim true: the splitter
     * balances everything [MARKUP] holds, so a counter reading only the first emphasis would score
     * one emphasis wrapped in the other as depth one and miss the fault entirely.
     */
    private fun nestingOf(body: String): Pair<Int, Int> {
        var depth = 0
        var deepest = 0
        var index = 0
        while (index < body.length) {
            val tag = tags.firstOrNull { body.startsWith(it, index) }
            when {
                tag == null -> index++

                tag in MARKUP -> {
                    depth++
                    deepest = maxOf(deepest, depth)
                    index += tag.length
                }

                else -> {
                    depth--
                    index += tag.length
                }
            }
        }
        return deepest to depth
    }

    /** Longest first, so a closer is never read as the opener it contains. */
    private val tags = (MARKUP.keys + MARKUP.values).sortedByDescending { it.length }

    @Test
    fun `a subject's label is escaped wherever it is rendered`() {
        // A label comes out of the store, which holds whatever a counterparty called themselves.
        val name = "Smith & Co <Holdings>"
        val menu = Outcome.SendMenu(
            prompt = MessageKey.SUBJECT_AMBIGUOUS,
            options = listOf(
                MenuOption.Subject(SubjectRef(SubjectKind.CLIENT, name)),
                MenuOption.Subject(SubjectRef(SubjectKind.CLIENT, "other")),
            ),
        )
        val listed = renderer.render(responseOf(menu), null).single().body
        assertTrue(listed.contains(escaped(name))) { listed }
        assertFalse(listed.contains("<Holdings>")) { listed }

        // And again on the line naming what matched and lost, which is a different code path.
        val notUsed = AnswerResponse(
            outcome = answerOf(PROSE),
            freshness = Freshness(),
            notUsed = listOf(SubjectRef(SubjectKind.CLIENT, name)),
        )
        val alternatives = renderer.render(notUsed, null)[1].body
        assertTrue(alternatives.contains(escaped(name))) { alternatives }
    }

    @Test
    fun `a status's subject name is escaped`() {
        val status = Outcome.SendStatus(
            key = MessageKey.SUBJECT_NOT_FOUND,
            subjectName = "A & B <ltd>",
            searched = listOf(SubjectKind.CLIENT),
        )
        val said = renderer.render(responseOf(status), null).single().body

        assertTrue(said.contains("A &amp; B &lt;ltd&gt;")) { said }
        assertFalse(said.contains("<ltd>")) { said }
    }

    @Test
    fun `the catalogue's own text is not escaped, because its markup is checked instead`() {
        // Asserted over every key in both enums rather than spot-checked: the renderer does not
        // escape catalogue text, and that is only safe while every entry is something the platform
        // can parse. An entry with a bare ampersand in it reaches a reader as a reply that failed
        // to send — so what is required is not the absence of the three characters but that each
        // one is either an entity or part of a tag this interface renders.
        //
        // Filled with arguments, which is what makes this a check on the rendered line rather than
        // on the entry: a substituted value is escaped by the caller, so emphasis an entry puts
        // around a parameter has to survive having one put in it.
        val texts = MessageKey.entries.map { catalogue.text(it, null) } +
            // Four arguments, which is what the widest key takes. Filled short, its last
            // placeholder survives into the text under test and the check runs over a line no
            // reader would ever be sent.
            BotKey.entries.map { catalogue.text(it, null, "1", "2", "3", "4") }

        texts.forEach { text ->
            assertEquals(emptyList<String>(), markupFaults(text)) {
                "This catalogue line is not something the platform would accept, and nothing " +
                    "escapes it on the way out: $text"
            }
        }
    }

    @Test
    fun `markdown the model volunteered reaches no reader, on either prose path`() {
        // Both paths, because they escape separately: an answer and a draft are rendered by
        // different functions, and the defect this covers was visible only in the first. A
        // decision made in `plain` and wired into one of the two is the shape of bug the
        // architecture rules exist to catch, so it is asserted here rather than assumed.
        val answer = renderer.render(responseOf(answerOf("**Agreed:** Friday.")), null)
        assertEquals("Agreed: Friday.", answer.single().body)

        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("**Agreed:** Friday."), MenuOption.Draft("Or Monday.")),
        )
        val drafts = renderer.render(responseOf(menu), null)
        assertTrue(drafts.none { "**" in it.body }) {
            "A draft carried the model's markdown through: ${drafts.map { it.body }}"
        }
        assertTrue(drafts[1].body.endsWith("Agreed: Friday.")) {
            "The draft's own text was lost rather than cleaned: ${drafts[1].body}"
        }
    }

    @Test
    fun `what a reply rests on is one block after it, not a line under every finding`() {
        // **The defect this prevents is the one the whole build cannot afford.** The backend
        // validates every claim's citations and sends them; rendering none of them hands a reader
        // assertions with no way behind any of them, and every other suite stays green through it
        // — the wire carries exactly what it should.
        //
        // One block rather than a line each, which is the measured half: a line per cited message
        // put more citation in a reply than answer, and none of it identified anything.
        val replies = renderer.render(responseOf(answerCiting(aCitation(author = "who wrote it"))), null)

        val lines = replies.single().body.lines()
        val said = italic(catalogue.text(BotKey.SOURCES_ON, null, "who wrote it", dated(THREE_DAYS_AGO)))
        assertEquals(listOf(PROSE, "", "$ASSERTION$FINDING", "", said), lines) {
            "What the answer rests on belongs in one block after it: $lines"
        }
    }

    @Test
    fun `the block locates the material by date, never by how long ago it was`() {
        // **An age does not locate anything**, which is the whole reason this says a date. Past
        // the bound where `Age` stops counting, every piece of material is worded identically — so
        // a reader given an age has nothing to scroll a conversation to. The date's presence and
        // every age wording's absence are both asserted: the first alone would pass a block
        // carrying each, and the second alone would pass a block carrying neither.
        val body = renderer.render(responseOf(answerCiting(aCitation(author = "who wrote it"))), null).single().body

        assertTrue(dated(THREE_DAYS_AGO) in body) {
            "The block states no date, so nothing in it can be looked up: $body"
        }
        val ages = BotKey.entries.filter { it.name.startsWith("AGE_") }
        assertTrue(ages.none { catalogue.text(it, null, "3") in body }) {
            "An elapsed age reached the block, which locates nothing: $body"
        }
    }

    @Test
    fun `one person writing several of the cited messages is named once`() {
        // **The commonest shape in this corpus**, and the one that made the lines unreadable: a
        // finding resting on two adjacent turns by one person rendered two lines identical but for
        // nothing at all. Deduplicated by name, so the block says who the material is from rather
        // than how many times they wrote.
        val answer = answerCiting(
            aCitation(author = "the same person"),
            aCitation(author = "the same person"),
            aCitation(author = "somebody else"),
        )

        val body = renderer.render(responseOf(answer), null).single().body

        assertEquals(1, Regex(Regex.escape("the same person")).findAll(body).count()) {
            "A person writing two of the cited messages was named twice: $body"
        }
        assertTrue("somebody else" in body) { "Deduplication dropped a second author: $body" }
    }

    @Test
    fun `material written across several days is said as the span it falls in`() {
        // Two dates are a range and one date is not, which is two sentences rather than one with a
        // degenerate case: a span reading *from the fifth to the fifth* is a worse line than the
        // one it replaced.
        val spanning = answerCiting(
            aCitation(author = "who wrote it"),
            aCitation(author = "who wrote it", at = A_DAY_AGO),
        )

        val body = renderer.render(responseOf(spanning), null).single().body

        val said = catalogue.text(BotKey.SOURCES_BETWEEN, null, "who wrote it", dated(THREE_DAYS_AGO), dated(A_DAY_AGO))
        assertTrue(said in body) { "The span is stated from its earliest date to its latest: $body" }
    }

    @Test
    fun `material the corpus names nobody for keeps the block and loses the names`() {
        // A turn the corpus records no name for is ordinary — the author may be unrecorded, have no
        // contact behind them, or have one nothing ever named. All three are one answer to a
        // reader, and none of them licenses inventing a name. The dates are still worth having, so
        // what goes is the names and not the block.
        val body = renderer.render(responseOf(answerCiting(aCitation(author = null))), null).single().body

        assertTrue(catalogue.text(BotKey.SOURCES_UNNAMED_ON, null, dated(THREE_DAYS_AGO)) in body) {
            "An unnamed author cost the block its dates as well as its names: $body"
        }
    }

    @Test
    fun `the block spans every claim's material, not the first claim's`() {
        // **What the end-to-end count can no longer see.** A line per finding made a finding whose
        // sources were dropped visible as a missing line; one block for the reply cannot, since a
        // single surviving cite renders a block that looks complete. So the span covering every
        // claim is asserted here instead, which is the level that can distinguish them.
        val answer = answerOf(
            prose = "$PROSE\n$ASSERTION$FINDING\n${ASSERTION}and another",
            claims = listOf(
                Claimed(text = FINDING, cites = listOf(aCitation(author = "earliest"))),
                Claimed(text = "and another", cites = listOf(aCitation(author = "latest", at = A_DAY_AGO))),
            ),
        )

        val body = renderer.render(responseOf(answer), null).single().body

        val said = catalogue.text(
            BotKey.SOURCES_BETWEEN,
            null,
            "earliest, latest",
            dated(THREE_DAYS_AGO),
            dated(A_DAY_AGO),
        )
        assertTrue(said in body) {
            "The second claim's author and date reached no reader, so its support was dropped: $body"
        }
    }

    @Test
    fun `material the corpus names nobody for keeps the names it does have`() {
        // **A reply resting on named and unnamed material names what it can.** The names-only
        // wording is for material with no author anywhere in it; one unattributed turn beside
        // named ones costs the block nothing, because dropping the names a reader could use to
        // avoid overstating a corner of it is the worse trade. The block is then not an
        // exhaustive account of who wrote the material, which is asserted here so it is a
        // decision rather than an accident.
        val answer = answerCiting(aCitation(author = "named"), aCitation(author = null, at = A_DAY_AGO))

        val body = renderer.render(responseOf(answer), null).single().body

        val said = catalogue.text(BotKey.SOURCES_BETWEEN, null, "named", dated(THREE_DAYS_AGO), dated(A_DAY_AGO))
        assertTrue(said in body) {
            "An unnamed turn beside a named one should cost the block neither the name nor the " +
                "span it falls in: $body"
        }
    }

    @Test
    fun `an author's name is escaped, because it is somebody else's words`() {
        // The three characters HTML reserves are ordinary in a name the corpus recorded, and one of
        // them unescaped makes the platform reject the whole message — so the failure is no answer
        // rather than a mis-rendered one.
        val body = renderer.render(responseOf(answerCiting(aCitation(author = "Smith & <Co>"))), null).single().body

        assertTrue("&amp;" in body && "&lt;Co&gt;" in body) { "A name went out unescaped: $body" }
        assertFalse("& <" in body) { "A name went out unescaped: $body" }
    }

    @Test
    fun `an instant that cannot be read narrows the span rather than costing the block`() {
        // The wire promises an instant, so a value that is not one means this service is wrong
        // rather than the material being old — and the span is still true of the cites that did
        // parse. Dropping the whole block for one bad value would take support away from an answer
        // over a fault in one field.
        val answer = answerCiting(aCitation(author = "readable"), aCitation(author = "unreadable", at = NOT_AN_INSTANT))

        val body = renderer.render(responseOf(answer), null).single().body

        assertTrue(catalogue.text(BotKey.SOURCES_ON, null, "readable, unreadable", dated(THREE_DAYS_AGO)) in body) {
            "One unreadable instant should narrow the span, not remove it: $body"
        }
    }

    @Test
    fun `a block with no readable date at all is not rendered`() {
        // A list of names with no dates is exactly the information-free line this change exists to
        // stop rendering, so there is nothing to gain by keeping half of one.
        val answer = answerCiting(aCitation(author = "who wrote it", at = NOT_AN_INSTANT))

        val body = renderer.render(responseOf(answer), null).single().body

        assertEquals(listOf(PROSE, "", "$ASSERTION$FINDING"), body.lines()) {
            "A block with nothing datable in it reached a reader: $body"
        }
    }

    @Test
    fun `the corpus's own calendar decides the day, not the reader's`() {
        // **An instant has no day until a zone says so**, and the only well-defined calendar here
        // is the one the material was written in: the platform reports a reader's language and
        // never their zone. A message written late in the evening falls on the next day in UTC,
        // which is the day a reader scrolling their own conversation would not find it on.
        val late = Instant.parse("2026-10-02T23:30:00Z")
        val answer = answerCiting(aCitation(author = "who wrote it", at = late))
        val eastward = OutcomeRenderer(catalogue, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.ofHours(3))

        val here = renderer.render(responseOf(answer), null).single().body
        val there = eastward.render(responseOf(answer), null).single().body

        assertTrue(dated(late) in here) { "The configured zone was not the one used: $here" }
        assertNotEquals(here, there) {
            "The same instant dated the same in two zones, so the zone is being ignored: $here"
        }
    }

    @Test
    fun `no identifier reaches a reader`() {
        // The identifier is what resolves and what the gate reads; shown to a person it is machine
        // text, and the reason this interface renders names and dates instead. It is on the wire
        // for an interface that means to link into a client.
        val answer = answerCiting(aCitation(author = "who wrote it"))

        val replies = renderer.render(responseOf(answer), null)

        assertTrue(replies.none { SPAN in it.body }) {
            "An identifier reached the reader: ${replies.map { it.body }}"
        }
        assertTrue(replies.none { MACHINE_INSTANT.containsMatchIn(it.body) }) {
            "A citation showed the instant the wire carries: ${replies.map { it.body }}"
        }
    }

    @Test
    fun `what an alternative rests on arrives apart from it, never inside it`() {
        // **A reader copies an alternative and sends it**, so anything in that message goes out
        // with it — and a draft's assertions are in the third person about the person being
        // written to. They are still worth having, so they arrive in a message of their own, and
        // that message names the alternative it belongs to now that it does not follow it.
        val claim = Claimed(text = FINDING, cites = listOf(aCitation(author = "who wrote it")))
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft(prose = "Friday suits.", claims = listOf(claim))),
        )

        val replies = renderer.render(responseOf(menu), null)

        val draft = replies[1].body
        assertFalse(draft.contains(FINDING)) { "The assertion was folded into a sendable draft: $draft" }
        val basis = replies[2].body
        assertTrue(basis.startsWith(catalogue.text(BotKey.DRAFT_BASIS, null, "1"))) {
            "What the draft rests on arrives under its own heading, naming the draft: $basis"
        }
        assertTrue(basis.contains(FINDING)) { "The assertion reached no reader at all: $basis" }
    }

    @Test
    fun `every draft a reader might copy is contiguous, with nothing between them`() {
        // **The reason the basis moved out from under its own draft.** Measured on a real reply of
        // eight messages: the drafts are the only ones a reader copies, and a message they will not
        // copy between each pair meant reaching the third alternative by scrolling past what the
        // first two rested on. So what might be sent is contiguous, and what supports it follows.
        val drafts = listOf("Friday suits.", "Monday suits.", "Either suits.")
        // A statement of its own per alternative, so that every basis has something to head. Three
        // alternatives resting on one shared premise is the case the de-duplication owns, and it is
        // asserted in its own case below rather than here.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = drafts.mapIndexed { index, prose ->
                MenuOption.Draft(
                    prose = prose,
                    claims = listOf(
                        Claimed(
                            text = "$FINDING, reading ${index + 1}",
                            cites = listOf(aCitation(author = "who wrote it")),
                        ),
                    ),
                )
            },
        )

        val replies = renderer.render(responseOf(menu), null).map { it.body }

        assertEquals(1 + drafts.size * 2, replies.size) { replies.toString() }
        // The drafts occupy one unbroken run after the prompt.
        drafts.forEachIndexed { index, draft ->
            assertTrue(replies[index + 1].contains(draft)) {
                "Draft ${index + 1} is not the ${index + 2}th message: ${replies[index + 1]}"
            }
        }
        // And every message after them is a basis of its own, each naming which draft it belongs
        // to. **One message each rather than one block**, which was tried: collapsed together the
        // repetition between them shows, de-duplicating it leaves an alternative with nothing to
        // head, and a heading that skips a number reads as something missing.
        drafts.indices.forEach { index ->
            val basis = replies[1 + drafts.size + index]
            assertTrue(basis.startsWith(catalogue.text(BotKey.DRAFT_BASIS, null, "${index + 1}"))) {
                "The basis in position ${index + 1} does not name its draft: $basis"
            }
            assertFalse(basis.contains(drafts[index])) {
                "A draft's own words are inside its basis, which is the one thing it may not " +
                    "carry — a reader copies the draft and leaves this behind: $basis"
            }
        }
    }

    @Test
    fun `a draft's basis states its assertions and says once where they came from`() {
        // **The shape where the duplication was worst**, because it arrives multiplied: a basis per
        // draft, and inside each one a line per cited message. Two claims resting on two messages
        // written minutes apart by one person rendered two pairs of identical lines.
        //
        // The assertions are what this message is for and are still stated one by one. What goes
        // once is where they came from.
        val together = listOf(aCitation(author = "one voice"), aCitation(author = "one voice", at = A_DAY_AGO))
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(
                MenuOption.Draft(
                    prose = "Friday suits.",
                    claims = listOf(
                        Claimed(text = FINDING, cites = together),
                        Claimed(text = "and the date was confirmed", cites = together),
                    ),
                ),
            ),
        )

        val basis = renderer.render(responseOf(menu), null)[2].body.lines()

        val said = italic(
            catalogue.text(BotKey.SOURCES_BETWEEN, null, "one voice", dated(THREE_DAYS_AGO), dated(A_DAY_AGO)),
        )
        // The heading, a blank line, the statements each behind the marker the answer's own lists
        // use, a blank line, and where they came from — said once for all of them.
        assertEquals(
            listOf(
                catalogue.text(BotKey.DRAFT_BASIS, null, "1"),
                "",
                "$ASSERTION$FINDING",
                "${ASSERTION}and the date was confirmed",
                "",
                said,
            ),
            basis,
        ) { "Two claims over four cites by one person should say the names and the span once: $basis" }
    }

    @Test
    fun `an alternative that asserts nothing is followed by no message at all`() {
        // Silence rather than an empty heading. A draft composed from material that stated nothing
        // has nothing to rest on, and a heading over nothing says there is something to check.
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("Friday suits."), MenuOption.Draft("Monday suits.")),
        )

        assertEquals(3, renderer.render(responseOf(menu), null).size) {
            "The prompt and the two alternatives, with nothing added for claims nobody made."
        }
    }

    @Test
    fun `a sentence written in aggregate about the material carries no citations of its own`() {
        // **The third fault, and the one a per-finding line could not be made safe against.** A
        // shape asks the model to say once what a group of items has in common, and that sentence
        // is an assertion about the corpus like any other — so it arrives as a claim carrying the
        // spans of every item beneath it. Attached per line, those sources landed on the summary
        // and the items below it carried none.
        //
        // With one block there is no line to attach to: the summary's spans and the items' spans
        // are the same material, said once, after the whole answer.
        val summary = "none of these has a recorded outcome"
        val answer = answerOf(
            prose = "$ASSERTION$summary\n$ASSERTION$FINDING",
            claims = listOf(
                Claimed(text = summary, cites = listOf(aCitation(author = "who wrote it"))),
                Claimed(text = FINDING, cites = listOf(aCitation(author = "who wrote it"))),
            ),
        )

        val lines = renderer.render(responseOf(answer), null).single().body.lines()

        assertEquals(listOf("$ASSERTION$summary", "", "$ASSERTION$FINDING", "", lines.last()), lines) {
            "A citation was attached to a line rather than to the reply: $lines"
        }
        val said = italic(catalogue.text(BotKey.SOURCES_ON, null, "who wrote it", dated(THREE_DAYS_AGO)))
        assertEquals(said, lines.last()) {
            "The summary and the finding rest on one body of material, said once: $lines"
        }
    }

    @Test
    fun `a finding the prose never carried still reaches the reader's block`() {
        // Unreachable while the prose is built from the same claims, and deliberately not a silent
        // drop: the alternative is an assertion a reader was given with its support taken away,
        // which is the defect every case above exists to prevent. Nothing special is needed for it
        // — a block over every claim does not ask where any of them was written.
        val answer = answerOf(
            prose = PROSE,
            claims = listOf(Claimed(text = "a finding written nowhere", cites = listOf(aCitation(author = "who")))),
        )

        val body = renderer.render(responseOf(answer), null).single().body

        assertTrue(italic(catalogue.text(BotKey.SOURCES_ON, null, "who", dated(THREE_DAYS_AGO))) in body) {
            "A claim the prose never carried lost its support: $body"
        }
    }

    private fun responseOf(outcome: Outcome) = AnswerResponse(outcome = outcome, freshness = Freshness())

    /** An answer of one unsectioned part, which is what most of these cases are about. */
    private fun answerOf(prose: String, claims: List<Claimed> = emptyList()) =
        Outcome.SendText(body = listOf(Block.Prose(prose)), claims = claims)

    /** An answer whose one finding was written into the prose and rests on [cites]. */
    private fun answerCiting(vararg cites: Cited) = answerOf(
        prose = "$PROSE\n$ASSERTION$FINDING",
        claims = listOf(Claimed(text = FINDING, cites = cites.toList())),
    )

    /**
     * The day [instant] falls on, written the way the floor locale writes one.
     *
     * Formatted through the catalogue's own pattern rather than spelled here, for the reason the
     * class comment gives: a date's field order is a locale's, and a case holding one would go red
     * on a locale that writes its dates differently without anything having broken.
     */
    private fun dated(instant: Instant): String = DateTimeFormatter
        .ofPattern(catalogue.text(BotKey.SOURCE_DATE, null), Locale.ROOT)
        .format(instant.atZone(ZoneOffset.UTC))

    private companion object {

        private const val PROSE = "Good afternoon. Thank you for the documents."

        /**
         * A subject named in the language the interface answers in, which is how a reader names one.
         *
         * Invented, as every fixture here is, and an organisation rather than a person. What it has
         * to be is a name in the corpus's own script, because the clause under test echoes back what
         * was typed and a reader writing a Russian question writes a Russian name.
         *
         * Written as escapes rather than as the script itself, which is the convention the store
         * suite's cross-script cases keep: a corpus script in Kotlin source is what the layering
         * check forbids of `/src/main/`, and a test that reads differently from the rule beside it
         * invites the next reader to put one there. It is a name a request carries and never display
         * text — it is echoed back, not resolved from a catalogue.
         */
        private const val TYPED_IN_CORPUS_SCRIPT = "\u0410\u0440\u0431\u0438\u0442\u0440\u0430\u0436"

        /** One assertion, worded the way a shape's reply format asks for one. */
        private const val FINDING = "the price was agreed"

        /** A second, for the cases that need two sections to carry one each. */
        private const val OTHER_FINDING = "the schedule is not settled"

        /**
         * What the backend writes in front of an assertion in the prose.
         *
         * Punctuation, carrying no words and no locale, so it is not catalogue text — and it is
         * the backend's rather than this interface's. Spelled here because a case has to compose
         * the prose the backend would have sent; the renderer itself matches on a claim's own text
         * and holds no copy of this.
         */
        private const val ASSERTION = "• "

        /** The identifier of the material a cited finding rests on, in the form the wire carries. */
        private const val SPAN = "MESSAGE/TELEGRAM/4815162342#0"

        /** What the clock reads in every case here, so an age is a fact rather than a flake. */
        private val NOW: Instant = Instant.parse("2026-10-05T12:00:00Z")

        /**
         * When a cited finding's material was written: three whole days before [NOW].
         *
         * Declared after [NOW] because it is read from it, and a companion object initialises in
         * the order it is written.
         */
        private val THREE_DAYS_AGO: Instant = NOW.minus(Duration.ofDays(3))

        /** A second instant, one day before [NOW], so a span has two ends to state. */
        private val A_DAY_AGO: Instant = NOW.minus(Duration.ofDays(1))

        /**
         * What the wire promises never to carry.
         *
         * A string rather than an instant, because the case it drives is a field that did not
         * parse — which is the one thing a typed fixture cannot express.
         */
        private const val NOT_AN_INSTANT = "whenever"

        private fun aCitation(author: String?, at: Instant = THREE_DAYS_AGO) =
            Cited(span = SPAN, sourceTime = at.toString(), author = author)

        /** The same with a source time the wire should never have sent. */
        private fun aCitation(author: String?, at: String) =
            Cited(span = SPAN, sourceTime = at, author = author)

        /** A corpus complete up to [NOW], in the form the wire carries one. */
        private val INSTANT: String = NOW.toString()

        /**
         * A corpus complete only up to three hours before [NOW].
         *
         * Most cases about the freshness note need a corpus that is actually *behind*, because
         * that is the only state the note is said in: [INSTANT] is current, and a case built on it
         * would assert against a note that is correctly absent and pass for the wrong reason.
         */
        private val STALE: String = NOW.minus(Duration.ofHours(3)).toString()

        /**
         * Anything shaped like the instant the wire carries.
         *
         * A date and a `T`, which is the part no reader should be shown. Matched as a shape
         * rather than compared to one value, so a case that happens to render a *different*
         * instant is caught as readily as one that renders this one.
         */
        private val MACHINE_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T""")
    }
}
