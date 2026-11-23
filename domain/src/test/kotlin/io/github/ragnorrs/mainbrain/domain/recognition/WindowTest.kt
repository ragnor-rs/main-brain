package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unit inference reads: one selected message, with its neighbours as context.
 *
 * Two groups of property decide whether this survives real data. The first is about *which* record
 * a window answers for — one window per selected message, named by it, with everything else present
 * only to be looked at — because that is what keeps a finding to a record the sweep marks.
 *
 * The second is about the labels, and the cases below are chosen around one requirement: a label is
 * compared a submission after the model answered, against a window re-derived by then, so it has to
 * be a function of the author record and not of this window. A label that could be reassigned to a
 * different party would store a fact about one person against another and produce a citation that
 * checks out.
 */
class WindowTest {

    @Test
    fun `every selected message gets a window of its own, named by it`() {
        val messages = conversation(10)
        val selected = listOf(messages[2].externalId, messages[7].externalId)

        val windows = windowsAround(CHAT, messages, selected, reach = 2)

        assertEquals(
            selected,
            windows.map { it.target },
            "A selected message with no window of its own is a record that will be marked without " +
                "anything having read it, or never marked at all.",
        )
    }

    @Test
    fun `a window reaches the same distance either side of its target`() {
        val messages = conversation(11)

        val window = windowsAround(CHAT, messages, listOf(messages[5].externalId), reach = 2).single()

        assertEquals(
            messages.subList(3, 8).map { it.externalId },
            window.messages.map { it.externalId },
            "The context is lopsided. What came before is what a pronoun in the target refers " +
                "back to, and what came after is whether the promise was taken up; dropping " +
                "either direction loses one of those.",
        )
    }

    @Test
    fun `a target near the start of a conversation gets a shorter window, not a shifted one`() {
        // The slice is clipped rather than slid along. Slid, the window would keep a constant size
        // by reaching further forward — so the first message of a conversation would be read with
        // twice the following context of any other, and the reach would stop meaning one thing.
        val messages = conversation(10)

        val window = windowsAround(CHAT, messages, listOf(messages.first().externalId), reach = 3).single()

        assertEquals(messages.take(4).map { it.externalId }, window.messages.map { it.externalId })
        assertEquals(messages.first().externalId, window.target)
    }

    @Test
    fun `a target and its neighbour are in one window together`() {
        // The reason a window is not a bare message: a commitment stated in one turn and closed
        // several turns later has to be readable whole from the turn that stated it. A reach of one
        // is the weakest setting that can still say so, so this holds for every larger one.
        val messages = conversation(6)

        messages.zipWithNext().forEach { (earlier, later) ->
            val window = windowsAround(CHAT, messages, listOf(earlier.externalId), reach = 1).single()
            assertTrue(
                later.externalId in window.messages.map { it.externalId },
                "${earlier.externalId.nativeId} cannot see the turn that answered it, so a " +
                    "commitment closed in the next message reads as open for ever.",
            )
        }
    }

    @Test
    fun `a target the conversation no longer holds gets no window`() {
        // How a collection learns that a record it paid to classify cannot be read against its
        // conversation. Nothing in this build takes a message out of one, so this is a corpus
        // nobody expects rather than the ordinary course of events — and it is still the only way
        // a window can go missing, now that a window is named by the record it reads.
        val windows = windowsAround(CHAT, conversation(5), listOf(ExternalId(Source.TELEGRAM, "gone")))

        assertEquals(emptyList(), windows)
    }

    @Test
    fun `the context is the same whichever order the messages arrive in`() {
        // Context has to be a function of the conversation and not of the order a query returned
        // it, or a re-derivation renders different text and the collection reads a window the
        // classification never saw. Two messages sharing an instant are the case that exposes it.
        val messages = conversation(12) + Message(
            externalId = ExternalId(Source.TELEGRAM, "tie"),
            sourceTime = START.plusSeconds(5),
            text = "shares an instant with the sixth message",
        )
        val selected = listOf(messages[6].externalId)

        val one = windowsAround(CHAT, messages, selected, reach = 3)
        val other = windowsAround(CHAT, messages.reversed(), selected, reach = 3)

        assertEquals(one, other)
    }

    @Test
    fun `an older message arriving widens a window's context and never moves which window it is`() {
        // What naming a window by its target buys. A stretch anchored on its own first message
        // moved wholesale when backfill landed older material, so an anchor submitted yesterday
        // named no window today and the call was paid for and lost. Here the context grows and the
        // record answered for is the one that was submitted.
        val messages = conversation(6)
        val target = messages[1].externalId
        val before = windowsAround(CHAT, messages, listOf(target), reach = 2).single()

        val older = message(-1, START.minusSeconds(60))
        val after = windowsAround(CHAT, listOf(older) + messages, listOf(target), reach = 2).single()

        assertEquals(target, after.target, "Backfill moved which record a window answers for.")
        assertEquals(messages.take(4).map { it.externalId }, before.messages.map { it.externalId })
        assertEquals(
            listOf(older.externalId) + messages.take(4).map { it.externalId },
            after.messages.map { it.externalId },
            "The older message was not taken as context, so a pronoun pointing back at it still " +
                "has nothing to point at.",
        )
    }

    @Test
    fun `whether there is anything to read is the target's own text`() {
        // A sticker surrounded by conversation is still a sticker: a finding read out of a
        // neighbour is not this window's to report, so the call would be billed for the
        // instruction and the context and reply nothing this window may use.
        val messages = listOf(
            message(0, START, text = "a message with plenty to say"),
            message(1, START.plusSeconds(1), text = null),
            message(2, START.plusSeconds(2), text = "and another"),
        )

        val silent = windowsAround(CHAT, messages, listOf(messages[1].externalId), reach = 1).single()
        val speaking = windowsAround(CHAT, messages, listOf(messages[0].externalId), reach = 1).single()

        assertFalse(silent.hasText, "A textless target was asked about because its context had text.")
        assertTrue(speaking.hasText)
        assertEquals(3, silent.messages.size, "A textless message still holds its place in the ordering.")
    }

    @Test
    fun `an author the corpus names is named on the line`() {
        // The whole point of the labels. A claim is kept only if it survives being read on its
        // own, and the model cannot name a party it was never shown.
        val authored = authoredConversation()

        val window = windowsAround(CHAT, authored, listOf(authored[1].externalId), names = NAMES).single()

        assertEquals(FIRST_NAME, window.label(FIRST_SPEAKER))
        assertEquals(SECOND_NAME, window.label(SECOND_SPEAKER))
    }

    @Test
    fun `a label survives the context growing, because it is the author's and not the window's`() {
        // **The property the subject comparison rests on.** The comparison happens a submission
        // after the model answered, against a window re-derived by then. A label computed from the
        // window — a position in it — would be reassigned when an older message by another author
        // joined the context, so the same string would mean one party when the model wrote it and
        // another when it was read: a fact about one person stored against another.
        val authored = authoredConversation()
        val target = authored[1].externalId
        val before = windowsAround(CHAT, authored, listOf(target), names = NAMES, reach = 1).single()

        val older = message(-1, START.minusSeconds(60), author = SECOND_SPEAKER)
        val after = windowsAround(CHAT, listOf(older) + authored, listOf(target), names = NAMES, reach = 1)
            .single()

        assertEquals(
            before.label(FIRST_SPEAKER),
            after.label(FIRST_SPEAKER),
            "A label moved when the context grew. The same string now means a different party " +
                "than it did when the model answered, so an attribute lands on the wrong person.",
        )
    }

    @Test
    fun `an author the corpus does not name has no label`() {
        // Nothing is invented to fill the field. A made-up label is either unstable — if it is
        // derived from this window — or an identifier the model would write into a claim, and both
        // are worse than leaving the party unnameable.
        val authored = authoredConversation()

        val window = windowsAround(CHAT, authored, listOf(authored[1].externalId)).single()

        assertNull(window.label(FIRST_SPEAKER))
        assertNull(window.label(SECOND_SPEAKER))
    }

    @Test
    fun `a message with no author has no label`() {
        // A channel post and a service message have none, and the corpus holds them. Labelling the
        // absence of a party would let an attribute be filed against nobody — and, since the
        // proposal's subject is the author, paid for first and dropped afterwards.
        val messages = listOf(
            message(0, START, author = FIRST_SPEAKER),
            message(1, START.plusSeconds(1), author = null),
        )

        val window = windowsAround(CHAT, messages, listOf(messages[1].externalId), names = NAMES, reach = 1)
            .single()

        assertNull(window.label(null), "An unauthored message was given a label, so a fact could be filed on it.")
        assertEquals(FIRST_NAME, window.label(FIRST_SPEAKER), "The named author lost its label too.")
    }

    @Test
    fun `a name two authors share is used for neither`() {
        // An ambiguous label is worse than none: the subject of an attribute is decided by
        // comparing labels, so one label for two people files a fact against whichever of them the
        // comparison reaches.
        val authored = authoredConversation()
        val shared = mapOf(FIRST_SPEAKER to "One Name", SECOND_SPEAKER to "One Name")

        val window = windowsAround(CHAT, authored, listOf(authored[1].externalId), names = shared).single()

        assertNull(window.label(FIRST_SPEAKER))
        assertNull(window.label(SECOND_SPEAKER))
    }

    @Test
    fun `a name carrying the field delimiter is repaired, not discarded`() {
        // **A label is rendered as one field of several, so a delimiter inside it shifts every
        // field after it and makes the line unreadable — including the identifier the model is
        // told to copy.** Taking the character out buys that guarantee as completely as refusing
        // the name does, and refusing it costs every finding about that party: a participant whose
        // display name carries punctuation is one the pass can otherwise record nothing about.
        //
        // Built from the delimiter the renderer actually uses, so the two cannot drift apart and
        // leave this green.
        val authored = authoredConversation()

        listOf(
            "Quoted${Extractor.FIELD}Name" to "Quoted Name",
            "${Extractor.FIELD}Name" to "Name",
        ).forEach { (hazard, expected) ->
            val window = windowsAround(
                CHAT,
                authored,
                listOf(authored[1].externalId),
                names = mapOf(FIRST_SPEAKER to hazard),
            ).single()

            assertEquals(
                expected,
                window.label(FIRST_SPEAKER),
                "`$hazard` was not repaired into a usable label.",
            )
            assertFalse(
                Extractor.FIELD in window.label(FIRST_SPEAKER).orEmpty(),
                "The label kept the delimiter, so the line it is on has the wrong number of fields.",
            )
        }
    }

    @Test
    fun `a name of nothing but delimiters is no name`() {
        // The bound on the repair: what it cannot do is invent a label. A name that is only the
        // characters a line cannot carry repairs to nothing, and nothing is not a label — an empty
        // field has to stay indistinguishable from an author the corpus never named.
        val authored = authoredConversation()

        listOf(Extractor.FIELD, "${Extractor.FIELD}${Extractor.FIELD}", "\n", " ${Extractor.FIELD} ")
            .forEach { empty ->
                val window = windowsAround(
                    CHAT,
                    authored,
                    listOf(authored[1].externalId),
                    names = mapOf(FIRST_SPEAKER to empty),
                ).single()

                assertNull(window.label(FIRST_SPEAKER), "`$empty` repaired into a label of nothing.")
            }
    }

    @Test
    fun `two names that differ only in what the repair removes are used for neither`() {
        // **The repair must not create the ambiguity the uniqueness rule exists to prevent.** Two
        // names that are different strings in the corpus and the same string after repair would,
        // tested before it, both look unique — and an ambiguous label is a fact filed against
        // whichever party the comparison reaches first. So the test runs after the repair.
        val authored = authoredConversation()

        val window = windowsAround(
            CHAT,
            authored,
            listOf(authored[1].externalId),
            names = mapOf(
                FIRST_SPEAKER to "Quoted${Extractor.FIELD}Name",
                SECOND_SPEAKER to "Quoted Name",
            ),
        ).single()

        assertNull(window.label(FIRST_SPEAKER), "An ambiguous repaired label was used for the first party.")
        assertNull(window.label(SECOND_SPEAKER), "An ambiguous repaired label was used for the second party.")
    }

    @Test
    fun `a name carrying a space no pattern matches is repaired by the predicate`() {
        // **The repair reads [Char.isWhitespace] and not a pattern, and every other case here is
        // inside the ASCII six — so without this one the predicate could be rewritten as `\s+`
        // and they would all stay green.** A non-breaking space is the form that separates the
        // two: it is whitespace to the predicate and not to the pattern, it renders invisibly, and
        // it is what the resolution of an obliged party composes the repair for.
        val authored = authoredConversation()

        listOf("Quoted\u00A0Name", "Quoted\u2007Name", "Quoted\u202FName").forEach { hazard ->
            val window = windowsAround(
                CHAT,
                authored,
                listOf(authored[1].externalId),
                names = mapOf(FIRST_SPEAKER to hazard),
            ).single()

            assertEquals(
                "Quoted Name",
                window.label(FIRST_SPEAKER),
                "`$hazard` kept a space a pattern cannot see, so two labels that look alike are not.",
            )
        }
    }

    @Test
    fun `a label re-spaced on the way back still names its party`() {
        // **[Window.party] read as the inverse of [Window.label], which is the direction an
        // obliged party is stored in.** The label in the prompt is repaired, so what has to be
        // tolerated is an answer that is not one of those outputs — a space the model wrote itself
        // or took from a message's text. The interior non-breaking space is the discriminating
        // form: the key trims by the predicate and then collapses runs with a pattern, so this one
        // survives in the middle of a name and keys to a string the stored label cannot produce.
        //
        // Here rather than only through a restatement reply, so that the seam is pinned where it
        // lives instead of three stages away from it.
        val authored = authoredConversation()
        val window = windowsAround(CHAT, authored, listOf(authored[1].externalId), names = NAMES).single()

        listOf(
            FIRST_NAME,
            FIRST_NAME.uppercase(),
            FIRST_NAME.replace(" ", "  "),
            FIRST_NAME.replace(" ", "\u00A0"),
        ).forEach { echoed ->
            assertEquals(
                FIRST_SPEAKER,
                window.party(echoed),
                "`$echoed` named no party, so the obligation it carries would lose its direction.",
            )
        }
    }

    @Test
    fun `a string no author carries names no party`() {
        // The other direction, and the one that must not be bought with the tolerance above: the
        // repair may widen what resolves to an author the window holds, and may never invent one.
        // The reserved word is included because the labels withdraw it from every author, so it
        // has to stay unresolvable however it is spaced.
        val authored = authoredConversation()
        val window = windowsAround(CHAT, authored, listOf(authored[1].externalId), names = NAMES).single()

        listOf("The Supplier", Extractor.NOTHING, " ", "\u00A0", "", Extractor.FIELD).forEach { stranger ->
            assertNull(
                window.party(stranger),
                "`$stranger` resolved to a party the window never showed the model.",
            )
        }
    }

    @Test
    fun `a name carrying a line break is repaired, not discarded`() {
        // A separate case from the delimiter, because it fails differently: a line break splits the
        // line in two rather than shifting its fields, so one message becomes two malformed ones.
        val authored = authoredConversation()

        listOf("Quoted\nName", "Quoted\rName").forEach { hazard ->
            val window = windowsAround(
                CHAT,
                authored,
                listOf(authored[1].externalId),
                names = mapOf(FIRST_SPEAKER to hazard),
            ).single()

            assertEquals(
                "Quoted Name",
                window.label(FIRST_SPEAKER),
                "`$hazard` was not repaired, so the party it names can carry no finding.",
            )
        }
    }

    @Test
    fun `a name the corpus holds as blank is no name`() {
        // The absent-name case rather than a hazard: a person record that carries an empty or
        // whitespace display name has told us nothing, and an empty label would leave the field
        // indistinguishable from one that was never filled.
        val authored = authoredConversation()

        listOf("", "   ").forEach { blank ->
            val window = windowsAround(
                CHAT,
                authored,
                listOf(authored[1].externalId),
                names = mapOf(FIRST_SPEAKER to blank),
            ).single()

            assertNull(window.label(FIRST_SPEAKER), "`$blank` was used as a label.")
        }
    }

    @Test
    fun `a name equal to the reserved word is not used as a label`() {
        // The instructions reserve one word for "this attribute is about no participant". A person
        // actually named that word would match it, and a fact about a company would be stored
        // against them — which is the defect the subject comparison exists to prevent, arriving
        // through the comparison itself.
        val authored = authoredConversation()

        val window = windowsAround(
            CHAT,
            authored,
            listOf(authored[1].externalId),
            names = mapOf(FIRST_SPEAKER to Extractor.NOTHING.uppercase()),
        ).single()

        assertNull(window.label(FIRST_SPEAKER))
    }

    @Test
    fun `a window's target has to be one of its messages`() {
        val messages = conversation(3)

        assertFailsWith<IllegalArgumentException> {
            Window(CHAT, messages, ExternalId(Source.TELEGRAM, "elsewhere"))
        }
        assertFailsWith<IllegalArgumentException> {
            Window(CHAT, emptyList(), messages.first().externalId)
        }
    }

    @Test
    fun `a negative context is refused`() {
        assertFailsWith<IllegalArgumentException> {
            windowsAround(CHAT, conversation(5), listOf(ExternalId(Source.TELEGRAM, "m1")), reach = -1)
        }
    }

    @Test
    fun `nothing selected is no windows, and no messages is no windows`() {
        assertEquals(emptyList(), windowsAround(CHAT, conversation(5), emptyList()))
        assertEquals(emptyList(), windowsAround(CHAT, emptyList(), listOf(ExternalId(Source.TELEGRAM, "m1"))))
    }

    private fun conversation(count: Int) = List(count) { message(it, START.plusSeconds(it.toLong())) }

    /** Two authors, alternating, so a label is about somebody rather than about everybody. */
    private fun authoredConversation() = List(4) {
        message(
            it,
            START.plusSeconds(it.toLong()),
            author = if (it % 2 == 0) FIRST_SPEAKER else SECOND_SPEAKER,
        )
    }

    private fun message(
        number: Int,
        at: Instant,
        text: String? = "message $number",
        author: ExternalId? = null,
    ) = Message(
        externalId = ExternalId(Source.TELEGRAM, "m$number"),
        sourceTime = at,
        author = author,
        text = text,
    )

    private companion object {
        val CHAT = ExternalId(Source.TELEGRAM, "chat")
        val START: Instant = Instant.parse("2026-03-01T00:00:00Z")

        /**
         * Two person records, identified by their place in the conversation rather than by anybody.
         *
         * The names are invented strings standing for whatever a source supplied. What is asserted
         * is that a name reaches the line, that it belongs to the record rather than to the window,
         * and that a collision does not reach the line at all — which any two distinct strings say.
         */
        val FIRST_SPEAKER = ExternalId(Source.TELEGRAM, "author-even")
        val SECOND_SPEAKER = ExternalId(Source.TELEGRAM, "author-odd")
        const val FIRST_NAME = "A Named Party"
        const val SECOND_NAME = "Another Named Party"
        val NAMES = mapOf(FIRST_SPEAKER to FIRST_NAME, SECOND_SPEAKER to SECOND_NAME)
    }
}
