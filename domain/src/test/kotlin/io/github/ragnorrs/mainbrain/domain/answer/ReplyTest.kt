package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading what the model wrote back.
 *
 * **The reader has to be forgiving about shape and strict about claims**, and those pull in opposite
 * directions, so the cases below are mostly about where the line falls. A model wrapping its answer
 * in a sentence of preamble has still answered; a model asserting something it cannot cite has
 * produced a claim nothing can check, and letting that through as prose would launder it past the
 * one gate the separation of claims from prose exists to provide.
 */
class ReplyTest {

    @Test
    fun `a single variant is read as one`() {
        val variants = Reply.read(
            """
            [VARIANT]
            Here is where things stand.
            """.trimIndent(),
            offer,
        )

        assertEquals(1, variants.size)
        assertEquals("Here is where things stand.", variants.single().prose)
    }

    @Test
    fun `several variants are read as alternatives rather than concatenated`() {
        val variants = Reply.read(
            """
            [VARIANT]
            The short reply.
            [VARIANT]
            The longer reply, which says more.
            """.trimIndent(),
            offer,
        )

        assertEquals(listOf("The short reply.", "The longer reply, which says more."), variants.map { it.prose })
    }

    @Test
    fun `prose runs over as many lines as it takes`() {
        val variants = Reply.read(
            """
            [VARIANT]
            First line.

            Third line, after a blank one.
            """.trimIndent(),
            offer,
        )

        assertEquals("First line.\n\nThird line, after a blank one.", variants.single().prose)
    }

    @Test
    fun `a claim is lifted out of the prose with its citations`() {
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] delivery was promised for Friday << 1
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals("delivery was promised for Friday", claim.text)
        assertEquals(setOf(span("42")), claim.cites)
        // The claim line is not also prose. Leaving it in both would show the reader the assertion
        // twice and make the prose contain its own machine-readable markup.
        assertEquals("The account.", variants.single().prose)
    }

    @Test
    fun `a claim lifted from between blank lines does not leave a doubled gap`() {
        val variants = Reply.read(
            """
            [VARIANT]
            What was agreed, in summary.

            [CLAIM] delivery was promised for Friday << 1

            Ask again naming one of them to narrow it.
            """.trimIndent(),
            offer,
        )

        // The claim line is removed and the blank lines that surrounded it become adjacent, so
        // the prose carries a two-line gap the model never wrote. Measured on a real reply: a
        // summary, then a hole, then the rest of the answer. Collapsed rather than forbidden,
        // because interleaving claims with prose is exactly what the format asks the model to do.
        assertEquals(
            "What was agreed, in summary.\n\nAsk again naming one of them to narrow it.",
            variants.single().prose,
        )
    }

    @Test
    fun `a claim may rest on several spans`() {
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] the date moved twice << 1, 2
            """.trimIndent(),
            offer,
        )

        assertEquals(2, variants.single().claims.single().cites.size)
    }

    @Test
    fun `preamble before the first marker is ignored`() {
        // A model that opens with a sentence has still answered, and spending the retry on that
        // would be rejecting a good reply over its manners.
        val variants = Reply.read(
            """
            Certainly — here are two options.

            [VARIANT]
            The reply.
            """.trimIndent(),
            offer,
        )

        assertEquals("The reply.", variants.single().prose)
    }

    @Test
    fun `a marker closing a line of preamble still opens the answer`() {
        // **The reply that cost a full answer at the highest effort.** The tolerance above was
        // implemented as skipping lines until one *is* the marker, so a preamble ending in a newline
        // was skipped as intended and the same sentence running straight into the marker took the
        // whole reply with it: no block opened, no variant read, and a request that had retrieved
        // material answered as though the corpus were empty. The research shape surfaced it because
        // it is the one that narrates its own process before answering.
        val variants = Reply.read(
            """
            I searched the open web and compared what I found. Here is the answer: [VARIANT]
            The reply.
            """.trimIndent(),
            offer,
        )

        assertEquals("The reply.", variants.single().prose)
    }

    @Test
    fun `a marker closing a line of the answer does not open a second alternative`() {
        // **The bound that keeps the tolerance from costing more than it buys.** Everything before
        // the *first* marker is what is ignored; once a block is open, a line ending in the marker
        // is the model's own prose and stays where it was written. Opened as a block instead, this
        // reply carries two alternatives where a shape promising a single one rejects it whole —
        // re-rolled, and answered as *nothing to answer from* for a reply that answered the
        // question. The shape that offers several would have one draft split mid sentence, with
        // every block still parsing and nothing able to see it.
        // **The first block has to carry prose of its own for this to discriminate.** Written with
        // the inline marker on the answer's opening line, the block before it is empty, which the
        // reader drops — so the count comes back to one either way and the case passes with the
        // bound gone. A calibration run caught exactly that.
        val variants = Reply.read(
            """
            [VARIANT]
            The answer begins.
            Each one opens with [VARIANT]
            More of it.
            """.trimIndent(),
            offer,
        )

        assertEquals(
            1,
            variants.size,
            "A line of the answer ending in the marker opened a second alternative: " +
                variants.map { it.prose },
        )
        // And nothing after it was lost, which is the other way this could go wrong: the line is
        // prose rather than a marker, so the lines on both sides of it belong to the one block.
        assertTrue(variants.single().prose.endsWith("More of it."), variants.single().prose)
    }

    @Test
    fun `narration into the first of several markers opens no alternative of its own`() {
        // **The second bound, and the one an open block cannot stand in for.** A model that runs
        // into its first marker and then writes the rest of the reply the way the format asks opens
        // a block for the narration — and that block has prose in it, so the reader cannot drop it
        // as empty. The reply comes back carrying one alternative more than the model wrote: a shape
        // promising a single one rejects it whole and spends a re-roll, which is this card's own
        // failure arriving through the other door, and the shape that offers several renders the
        // narration as a draft somebody could send. With a marker of its own present, everything
        // before it is preamble by the ordinary rule and the run-up needs no tolerance at all.
        val variants = Reply.read(
            """
            Two options, each marked: [VARIANT]
            Adjust the tone to suit you.
            [VARIANT]
            The first.
            [VARIANT]
            The second.
            """.trimIndent(),
            offer,
        )

        assertEquals(
            listOf("The first.", "The second."),
            variants.map { it.prose },
            "The run-up opened an alternative of its own, carrying the model's narration into it",
        )
    }

    @Test
    fun `a marker the answer ran into is not left in the prose`() {
        // The literal is this format's machinery. The line's words are the model's answer and stay,
        // but a reader handed the marker with them is being shown the seam — and nothing downstream
        // takes it out, so it comes off here.
        val variants = Reply.read(
            """
            [VARIANT]
            The answer begins.
            Each one opens with [VARIANT]
            """.trimIndent(),
            offer,
        )

        val prose = variants.single().prose
        assertFalse(prose.contains("[VARIANT]"), prose)
        assertTrue(prose.endsWith("Each one opens with"), prose)
    }

    @Test
    fun `a reply that opens on a claim and never writes the marker is one block`() {
        val variants = Reply.read("[CLAIM] Something was agreed. << 1\n[CLAIM] So was this. << 1", offer)

        assertEquals(1, variants.size)
        assertEquals(2, variants.single().claims.size)
    }

    @Test
    fun `a reply that opens on a heading and never writes the marker is one block`() {
        assertEquals(1, Reply.read("[HEADING] A name\n[CLAIM] Something was agreed. << 1", offer).size)
    }

    @Test
    fun `a reply that opens on prose and never writes the marker is not read`() {
        assertEquals(emptyList(), Reply.read("Some prose.\n[CLAIM] Something was agreed. << 1", offer))
    }

    @Test
    fun `an unmarked opening claim with no citations is still refused`() {
        assertEquals(emptyList(), Reply.read("[CLAIM] Something was agreed.", offer))
    }

    @Test
    fun `a marker with anything written after it opens nothing`() {
        // The bound on the tolerance, stated as a case because it is the half a reader would
        // otherwise assume. What follows a marker on its own line would have to be guessed at as the
        // new block's first line or as more of the narration, and prose mentioning the marker
        // mid-sentence would open a block — so what is tolerated is a model running up to the marker
        // and not one writing past it.
        assertEquals(emptyList(), Reply.read("[VARIANT] 1\nThe reply.", offer))
    }

    @Test
    fun `a block the model opened and wrote nothing after is not a variant`() {
        val variants = Reply.read(
            """
            [VARIANT]
            [VARIANT]
            The only real one.
            """.trimIndent(),
            offer,
        )

        assertEquals(listOf("The only real one."), variants.map { it.prose })
    }

    @Test
    fun `a variant that is all assertions and no prose is still an answer`() {
        // The status shape asks for exactly this — "a list of dated, cited items is the answer; a
        // narrative is not" — so the shape most likely to produce a claims-only reply was the one
        // whose best reply was discarded. Measured on the evaluation set: retrieval 1.00 and
        // citations 1.00 on a case whose answer scored 0.00, the model having written 898 tokens
        // and nothing reaching the reader.
        val variants = Reply.read(
            """
            [VARIANT]
            [CLAIM] the certificate renewal was filed << 1
            [CLAIM] the test results came back in order << 2
            """.trimIndent(),
            offer,
        )

        val variant = variants.single()
        assertEquals("", variant.prose, "There was no prose, and none is invented.")
        assertEquals(2, variant.claims.size, "Both assertions survive and stay checkable.")
        assertEquals(
            2,
            variant.body.count { it is Piece.Cited },
            "They are read back in the order written, which is what a reader is shown.",
        )
    }

    @Test
    fun `a block with neither prose nor assertions is still dropped`() {
        // The other half of the rule above, and the reason it is not simply "keep every block": a
        // model that emits a marker and writes nothing after it has produced no answer, and
        // admitting that block would render as an empty reply rather than as nothing to answer
        // from — which tells a reader the corpus held nothing when it was never asked.
        val variants = Reply.read(
            """
            [VARIANT]

            [VARIANT]
            [CLAIM] the only real one << 1
            """.trimIndent(),
            offer,
        )

        assertEquals(1, variants.size, "The empty block is not an alternative.")
        assertEquals("the only real one", variants.single().claims.single().text)
    }

    @Test
    fun `a claim whose citations cannot be read costs itself and not its whole variant`() {
        // Not folded into the prose: that would launder an unverifiable assertion past the grounding
        // gate as composed text. Not dropped here either, which would take the variant with it, fall
        // short of the count the shape promised, and spend the retry to arrive at nothing to answer
        // from. Carried instead, so the gate below can refuse it on the survivable path.
        val variants = Reply.read(
            """
            [VARIANT]
            The first.
            [CLAIM] something was agreed << not an identifier
            [VARIANT]
            The second.
            """.trimIndent(),
            offer,
        )

        assertEquals(
            listOf("The first.", "The second."),
            variants.map { it.prose },
            "A citation nobody could read cost the variant it was in, which falls short of the " +
                "count the shape promised and is the fatal fault rather than the survivable one.",
        )
        val claim = variants.first().claims.single()
        assertEquals(setOf("not an identifier"), claim.unreadable)
        assertTrue(claim.cites.isEmpty())
        assertFalse(
            claim.grounded(setOf(span("42"), span("43"))),
            "A claim resting on nothing but text that is not a citation passed the gate.",
        )
    }

    @Test
    fun `a claim with no citation marker drops its variant too`() {
        val variants = Reply.read(
            """
            [VARIANT]
            The first.
            [CLAIM] something was agreed
            """.trimIndent(),
            offer,
        )

        assertTrue(variants.isEmpty())
    }

    @Test
    fun `a claim whose marker has nothing after it drops its variant too`() {
        // The one case the reader still refuses outright, and the line between it and an unreadable
        // citation: there is nothing written where the citations go, so there is nothing to carry
        // and nothing for the gate to refuse. A claim carrying an empty citation would reach the
        // fault report naming nothing at all.
        val variants = Reply.read(
            """
            [VARIANT]
            The first.
            [CLAIM] something was agreed <<
            """.trimIndent(),
            offer,
        )

        assertTrue(variants.isEmpty())
    }

    @Test
    fun `a claim with no text drops its variant`() {
        val variants = Reply.read(
            """
            [VARIANT]
            The first.
            [CLAIM]  << 1
            """.trimIndent(),
            offer,
        )

        assertTrue(variants.isEmpty())
    }

    @Test
    fun `a word between two citations is prose, not a citation that failed`() {
        // **The cost of making an unresolvable citation drop its claim, and the line that bounds it.**
        // The tail is cut on the comma because that is what the format asks the model to separate
        // citations with; a word sitting beside a number that resolved is prose. Cut on whitespace as
        // well, `and` would be a citation nothing could read, and this finding — resting on two pieces
        // of material that were both retrieved — would be dropped and never shown to anyone.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] the date moved twice << 1 and 2
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals(setOf(span("42"), span("43")), claim.cites)
        assertEquals(emptySet(), claim.unreadable, "A word beside a resolved number was read as a citation.")
        assertTrue(
            claim.grounded(setOf(span("42"), span("43"))),
            "A sound finding was dropped over a word the model wrote between two good citations.",
        )
    }

    @Test
    fun `one unreadable citation among good ones costs the claim it was on`() {
        // **The claim must not reach a reader as checked.** It was asserted on two things and only
        // one of them was ever compared against anything, so passing it on the strength of the token
        // that happened to read would make the gate's strictness depend on how the model got the
        // citation wrong — a different number is caught, a word is not. The readable half is kept on
        // the claim all the same: the gate drops a claim whole, and what it rested on is what the
        // fault report has to be able to name.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] it was agreed << 1, nonsense
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals(setOf(span("42")), claim.cites)
        assertEquals(setOf("nonsense"), claim.unreadable)
        assertFalse(
            claim.grounded(setOf(span("42"), span("43"))),
            "A claim half of whose citations nothing could read passed the gate on the strength " +
                "of the half that did.",
        )
    }

    @Test
    fun `a number that was never offered costs its claim and not its variant`() {
        // The number read, so the model followed the format and named material that does not exist —
        // which is what the grounding gate answers. Refusing it here would take the variant with it,
        // fall short of the count the shape promised, and spend the retry to arrive at nothing to
        // answer from, for a reply whose other findings were sound.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] it was agreed << 1, 9
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals(setOf(span("42")), claim.cites)
        assertEquals(setOf(9), claim.unoffered)
        assertFalse(
            claim.grounded(setOf(span("42"), span("43"))),
            "A claim resting on a number nobody offered passed the gate on the strength of the " +
                "citation that did read.",
        )
    }

    @Test
    fun `a claim citing nothing but a number that was never offered is still a claim`() {
        // It has to be: dropped at this point it would take the variant, and folded into the prose it
        // would reach the reader as composed text that nothing checks. Carried, it fails the gate.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] it was agreed << 9
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertTrue(claim.cites.isEmpty())
        assertEquals(setOf(9), claim.unoffered)
    }

    @Test
    fun `a number copied back with the brackets the prompt printed is the same citation`() {
        // The format asks for the bare number and the material block labels each piece `[1]`, so a
        // model copying what it sees rather than what it was told writes the brackets. Rejecting
        // that would retry a reply that cited correctly.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] it was agreed << [1]
            """.trimIndent(),
            offer,
        )

        assertEquals(setOf(span("42")), variants.single().claims.single().cites)
    }

    @Test
    fun `a bracketed list of numbers is read, rather than costing the whole variant`() {
        // **Measured: this took the variant, the retry and the answer.** The prompt labels material
        // `[1]` and the format's example writes `1, 4`, so the model is shown two spellings and
        // `[1, 2]` is the compaction of both. Cut apart on the comma, that is `[1` and `2]`, and
        // while a citation had to carry a matching pair of brackets neither read — so a marked claim
        // was unreadable, its variant was dropped whole, the count fell short of the one the shape
        // promised, and that is fatal: two model calls to report nothing to answer from, for a reply
        // that had cited correctly.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] the date moved twice << [1, 2]
            """.trimIndent(),
            offer,
        )

        assertEquals(setOf(span("42"), span("43")), variants.single().claims.single().cites)
    }

    @Test
    fun `citations separated by a space are read, not handed over as one unreadable piece`() {
        // The format asks for a comma and a model writes a space instead often enough to matter. Read
        // on the comma alone, `[1] [2]` is one piece that resolves to nothing — which costs the claim
        // it was on, so the reader loses a finding over a separator. Whitespace is therefore read
        // inside a piece, and the two citations are two.
        val variants = Reply.read(
            """
            [VARIANT]
            The account.
            [CLAIM] the date moved twice << [1] [2]
            """.trimIndent(),
            offer,
        )

        assertEquals(setOf(span("42"), span("43")), variants.single().claims.single().cites)
    }

    @Test
    fun `an unmarked cited line ending the way a sentence ends is still read as a claim`() {
        // **The leak, reached through the guard that was added to close it.** An unmarked line is a
        // claim only when its whole tail is citations, and a line of prose ends with a full stop — so
        // the last citation arrives as `2.`, which read as nothing makes the whole line fail the test
        // and go to the reader verbatim, cite marker and numbers included. Sentence punctuation is
        // therefore not part of a citation, which is what keeps the rule from undoing itself.
        val variants = Reply.read(
            """
            [VARIANT]
            What was agreed, in summary.

            They settled the date in the end << 1, 2.
            """.trimIndent(),
            offer,
        )

        val variant = variants.single()
        assertFalse(variant.prose.contains(CITE_MARKER), "The machinery reached the reader: ${variant.prose}")
        assertEquals(setOf(span("42"), span("43")), variant.claims.single().cites)
    }

    @Test
    fun `a reply with no markers at all yields nothing`() {
        // Which settles as insufficient evidence rather than as an error — there is no answer in it.
        assertTrue(Reply.read("I could not work out what you meant.", Offer.NONE).isEmpty())
    }

    @Test
    fun `an assertion is kept in the place the model made it`() {
        // Measured on a real reply from the shape that answers in sections: the model wrote a
        // heading, the findings under it, then the next heading. Every finding was an assertion,
        // so lifting them all out left three headings with nothing beneath them and the findings
        // in a block at the end — and nothing in that block said which heading each had come
        // from. The split the grounding gate needs is kept; the order is kept beside it.
        val variants = Reply.read(
            """
            [VARIANT]
            Where things stand:
            [CLAIM] the migration was agreed << 1

            Still open:
            [CLAIM] nobody owns the rollout << 2
            """.trimIndent(),
            offer,
        )

        val variant = variants.single()
        assertEquals(
            listOf(
                "prose:Where things stand:",
                "claim:the migration was agreed",
                "prose:",
                "prose:Still open:",
                "claim:nobody owns the rollout",
            ),
            variant.body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
        // The split is unchanged: both halves are still what they were, so nothing the grounding
        // check reads has moved.
        assertEquals(2, variant.claims.size)
        assertEquals("Where things stand:\n\nStill open:", variant.prose)
    }

    @Test
    fun `a variant assembled by hand still reads as prose then assertions`() {
        // The default ordering, which is what every caller that builds a variant from the two
        // fields means — and what composing one used to produce for everybody.
        val cited = Claim("they agreed", setOf(span("42")))
        val variant = Variant("the summary", listOf(cited))

        assertEquals(
            listOf("prose:the summary", "prose:", "claim:they agreed"),
            variant.body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
    }

    @Test
    fun `a marked heading is read as a heading and not as prose`() {
        // What makes the grounding gate able to take a heading away with the findings under it.
        // Read as its own kind rather than inferred from the shape of the line, because a line of
        // words is what prose is too and guessing would delete composed text.
        val variants = Reply.read(
            """
            [VARIANT]
            [HEADING] the first body of work
            [CLAIM] the migration was agreed << 1
            """.trimIndent(),
            offer,
        )

        val variant = variants.single()
        assertEquals(
            listOf("heading:the first body of work", "claim:the migration was agreed"),
            variant.body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
        // Kept out of the prose half on purpose: it carries nothing, so counting it would keep a
        // variant alive whose findings were all dropped.
        assertEquals("", variant.prose)
    }

    @Test
    fun `an unmarked heading is still prose`() {
        // The negative half, and the limit of this design stated as a test: the marker is what
        // carries the structure, so a heading the model wrote as a plain line is indistinguishable
        // from the answer's prose and nothing here recovers it.
        val variants = Reply.read(
            """
            [VARIANT]
            the first body of work
            [CLAIM] the migration was agreed << 1
            """.trimIndent(),
            offer,
        )

        assertEquals(
            listOf("prose:the first body of work", "claim:the migration was agreed"),
            variants.single().body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
    }

    @Test
    fun `a heading marker with nothing after it is dropped rather than left blank`() {
        // The type rejects a blank label, so the reader must not build one. Contributing nothing
        // rather than a blank line, because the marker line was never content.
        val variants = Reply.read(
            """
            [VARIANT]
            [HEADING]
            [CLAIM] the migration was agreed << 1
            """.trimIndent(),
            offer,
        )

        assertEquals(
            listOf("claim:the migration was agreed"),
            variants.single().body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
    }

    @Test
    fun `a heading's citations are machinery and do not reach the reader`() {
        // A heading carries no citations by rule, so the marker on one of these lines is the model
        // disregarding that rather than words it composed — and kept, what reaches the reader is the
        // machinery in the middle of a label. Both forms, because they fail differently: the readable
        // one is a number nothing resolves for a heading, and the malformed one is the span identifier
        // in front of a reader that the unmarked-line arm already exists to stop.
        val variants = Reply.read(
            """
            [VARIANT]
            [HEADING] the first body of work << 1
            [CLAIM] the migration was agreed << 1
            [HEADING] the second << MESSAGE/TELEGRAM/43
            [CLAIM] and so was the date << 2
            """.trimIndent(),
            offer,
        )

        assertEquals(
            listOf(
                "heading:the first body of work",
                "claim:the migration was agreed",
                "heading:the second",
                "claim:and so was the date",
            ),
            variants.single().body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
    }

    @Test
    fun `a heading the model left with nothing under it is dropped by the reader`() {
        // The gate is not reached by a reply whose every citation resolved, so an orphan the model
        // wrote itself — two headings in a row, or one at the end — travelled all the way to the
        // reader. The rule is that the section is empty, not that the gate emptied it, so it is
        // applied where a heading is built as well as where findings are taken away.
        val variants = Reply.read(
            """
            [VARIANT]
            [HEADING] the first body of work
            [CLAIM] the migration was agreed << 1

            [HEADING] the second
            [HEADING] the third
            [CLAIM] and so was the date << 2

            [HEADING] the fourth
            """.trimIndent(),
            offer,
        )

        // The second has nothing but another heading under it and the fourth stands at the end;
        // the third keeps its finding, so one pass over adjacent orphans takes both of them.
        assertEquals(
            listOf(
                "heading:the first body of work",
                "claim:the migration was agreed",
                "prose:",
                "heading:the third",
                "claim:and so was the date",
            ),
            variants.single().body.map {
                when (it) {
                    is Piece.Prose -> "prose:${it.text}"
                    is Piece.Cited -> "claim:${it.claim.text}"
                    is Piece.Heading -> "heading:${it.text}"
                }
            },
        )
    }

    @Test
    fun `an empty reply yields nothing`() {
        assertTrue(Reply.read("", Offer.NONE).isEmpty())
    }

    @Test
    fun `a citation trailing a prose line is read as a claim rather than shown`() {
        // Measured on a real reply: the model closed a paragraph of prose with a citation but
        // without the marker in front of it, so the line went into the prose entire and somebody
        // was shown the cite marker and the machinery after it at the end of a sentence.
        // Recognising the line by its citations rather than only by its prefix removes the leak and
        // puts the assertion through the grounding check — which is the opposite of laundering it
        // into prose, since prose is the half nothing validates.
        val variants = Reply.read(
            """
            [VARIANT]
            What was agreed, in summary.

            Nothing in the sample bears on the second question << 1, 2
            """.trimIndent(),
            offer,
        )

        val variant = variants.single()
        assertFalse(
            variant.prose.contains("<<"),
            "The cite marker reached the reader: ${variant.prose}",
        )
        assertEquals("What was agreed, in summary.", variant.prose)

        val claim = variant.claims.single()
        assertEquals("Nothing in the sample bears on the second question", claim.text)
        assertEquals(
            setOf(span("42"), span("43")),
            claim.cites,
        )
    }

    @Test
    fun `an unmarked line whose tail is not a citation stays prose`() {
        // The boundary of the rule above, and a deliberate asymmetry with the marked form. A
        // line the model marked `[CLAIM]` is an assertion whatever follows, so a citation that
        // resolves to nothing is carried and costs that claim at the gate. A line it did not mark is
        // only recognisable *by* its citations, so when they do not resolve there is nothing to say
        // it was ever a claim —
        // and dropping the variant would answer a usable draft with *nothing to answer from*
        // because a composed message happened to contain two angle brackets.
        val variants = Reply.read(
            """
            [VARIANT]
            Shall we say Friday? The window is 10:00 << 12:00, if that suits.
            """.trimIndent(),
            offer,
        )

        assertEquals(
            "Shall we say Friday? The window is 10:00 << 12:00, if that suits.",
            variants.single().prose,
        )
        assertTrue(variants.single().claims.isEmpty())
    }

    @Test
    fun `an unmarked line citing only a number nobody offered stays prose too`() {
        // **The guard the short form needs and the long one did not.** A forty-character identifier
        // turning up in composed prose was evidence in itself, so resolving it was enough to call the
        // line a claim. A small number is not: a sentence closing on one is far likelier to be a
        // sentence, and read as an assertion it would fail the gate and be taken out of the reply —
        // so a draft would lose a line somebody wrote. An unmarked line therefore has to name
        // material that was actually offered before it is read as a claim at all.
        val variants = Reply.read(
            """
            [VARIANT]
            Let us hold the slot and confirm by Friday << 9
            """.trimIndent(),
            offer,
        )

        assertEquals(
            "Let us hold the slot and confirm by Friday << 9",
            variants.single().prose,
        )
        assertTrue(variants.single().claims.isEmpty())
    }

    @Test
    fun `an unmarked line whose tail is only partly citations stays prose`() {
        // **Measured: this deleted half a sentence from a draft.** Russian prose is quoted with a
        // doubled angle bracket, so a drafted message that quotes something and goes on to a figure
        // leaves a bare number where a citation would be. Read as a claim, the line was lifted out of
        // the prose and cut back to the words before the marker, and what reached the reader was
        // `Srok` — the first word of a sentence somebody meant to send, with the rest silently gone.
        //
        // So an unmarked line is a claim only when the whole of its tail is citations. Requiring one
        // of them to resolve is not enough: here one does.
        val variants = Reply.read(
            """
            [VARIANT]
            Here is a draft.

            Srok <<hard>>, 2
            """.trimIndent(),
            offer,
        )

        assertEquals("Here is a draft.\n\nSrok <<hard>>, 2", variants.single().prose)
        assertTrue(variants.single().claims.isEmpty())
    }

    @Test
    fun `an unmarked line stays prose when its tail is partly citations and carries no comma`() {
        // **The same defect as above with the comma taken out, which is the form no fixture covered.**
        // The reader cuts the tail on commas and reads whitespace inside each piece, so a tail
        // carrying no comma is a single piece; a piece counts as a citation when anything in it
        // resolves. One bare number therefore made the surrounding words prose *beside* a citation
        // and carried the whole line off as an assertion — the leak the test above exists to close,
        // reached again through the piece that closes a different one.
        //
        // Nothing about the format requires the comma: it is shown in the example and never asked
        // for. So the guard cannot rest on it. An unmarked line is a claim only when every token
        // after the marker reads as a number.
        val variants = Reply.read(
            """
            [VARIANT]
            Here is a draft.

            Srok <<hard>> 2
            """.trimIndent(),
            offer,
        )

        assertEquals("Here is a draft.\n\nSrok <<hard>> 2", variants.single().prose)
        assertTrue(variants.single().claims.isEmpty())
    }

    @Test
    fun `a marked claim keeps a word written beside a citation that resolved`() {
        // The residue the guard above does not remove, asserted so that it is a decision rather than
        // an accident: on a line the model marked, a piece holding one number that read is a citation
        // and the rest of that piece is prose. So `<< 1 nonsense` costs the claim nothing where
        // `<< 1, nonsense` costs it — which is a strictness that turns on the separator, and is the
        // price of not dropping `<< 1 and 2`. Narrowing it means telling a citation-shaped token from
        // an ordinary word, which the parser does not attempt.
        val variants = Reply.read(
            """
            [VARIANT]
            [CLAIM] it was agreed << 1 nonsense
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals(setOf(span("42")), claim.cites)
        assertTrue(claim.unreadable.isEmpty(), "a word beside a resolved citation is prose: $claim")
        assertTrue(claim.grounded(setOf(span("42"))))
    }

    @Test
    fun `an unmarked line whose tail is all citations is read as a claim, bad ones included`() {
        // The other side of the guard above: once everything after the marker is a citation and one
        // of them names material that was offered, the line is an assertion, and a second citation
        // nobody offered costs it the way it costs a marked one. Leaving it as prose would launder an
        // assertion the model made past the gate.
        val variants = Reply.read(
            """
            [VARIANT]
            What was agreed, in summary.

            They settled the date in the end << 1, 9
            """.trimIndent(),
            offer,
        )

        val claim = variants.single().claims.single()
        assertEquals(setOf(span("42")), claim.cites)
        assertEquals(setOf(9), claim.unoffered)
    }

    @Test
    fun `the marker is recognised despite surrounding whitespace`() {
        val variants = Reply.read("  [VARIANT]  \nThe reply.", Offer.NONE)

        assertEquals("The reply.", variants.single().prose)
    }

    @Test
    fun `the format block tells the model the markers the reader looks for`() {
        // The one coupling worth asserting: a format described in the prompt and parsed here will
        // disagree the moment one of them is edited alone.
        assertTrue(Reply.format(1..1, sections = true).contains("[VARIANT]"))
        assertTrue(Reply.format(1..1, sections = true).contains("[CLAIM]"))
        assertTrue(Reply.format(1..1, sections = true).contains("<<"))
    }

    @Test
    fun `the format block asks for the numbers the prompt offered, not an identifier`() {
        // The two halves that must not disagree. The last time the format text and the parser did,
        // every shape promising one answer got several and no request could be answered at all — so
        // the instruction naming what a citation is belongs beside the reader that resolves one.
        val format = Reply.format(1..1, sections = true)

        assertTrue(format.contains("numbers"), format)
        assertFalse(
            format.contains("identifier"),
            "The format still asks for an identifier, which nothing in the prompt prints: $format",
        )
    }

    @Test
    fun `the format forbids a citation in the prose without forbidding figures`() {
        // **An instruction that reads as *never write a number in the prose* zeroes most of the
        // evaluation set.** A figure in the answer — a price, a document number — is the part that is
        // wrong in a way that matters, and it is what the answer dimension is scored on. So the
        // prohibition has to name the citation form rather than numbers, and has to say outright that
        // a figure belongs in the prose.
        //
        // How many cases that is, is not written down here. The count that used to be was true when
        // it was written and false once a parallel branch grew the fixture under it, which is the
        // way a count in a comment fails: not gradually, and not where anyone is looking.
        // `PromptProseTest` reads it off the fixture instead and names the cases when it fails,
        // which is the half this cannot do.
        val format = Reply.format(1..1, sections = true)

        assertTrue(format.contains("Citations go after the $CITE_MARKER marker"), format)
        assertTrue(
            format.contains("are ordinary prose"),
            "Nothing tells the model a figure belongs in the answer, so an instruction about " +
                "citations can be read as one about numbers: $format",
        )
    }

    @Test
    fun `the rule about naming a finding's subject exempts the reply a shape drafts`() {
        // **The exclusion rather than the rule, which `ShapesTest` holds over all five.** What is
        // worth a pin of its own here is the carve-out, because losing it is the one way this rule
        // becomes a defect rather than an absence: the draft's reader is the other party to the
        // thread the shorthand belongs to, and a thing spelled out in words nobody there uses reads
        // as a message from somebody who was not in the conversation. It is the same exemption the
        // relative-time rule and the hedge rule each make, for the same reason, and all three are in
        // the format where a draft cannot opt out of them.
        //
        // **Pinned on the thread's vocabulary rather than the counterpart's, which is the wording
        // that matters.** The drafting shape composes as the sender, so an exemption worded as
        // "as they write it" would contradict it — and that shape's own rule was the subject of an
        // earlier card, so the clash would have been a real one rather than a reading.
        //
        // Both halves of the carve-out, because the second is what keeps it from swallowing the rule:
        // a claim line a draft carries is a report about the material and is held to it.
        //
        // **Each on a fragment the state rule does not already carry**, which cost a pass here: that
        // rule makes the same exemption and closes it with the same words, so the obvious wording for
        // the second assertion was green with this whole block deleted. Both fragments below are this
        // block's alone.
        val format = Reply.format(1..1, sections = true).replace(WHITESPACE, " ")

        assertTrue(
            format.contains("the shorthand the thread already uses belongs in it as the thread uses it"),
            "A drafted reply is no longer exempt from naming a finding's subject, so a draft " +
                "paraphrases the thread's own words back at the person who wrote them: $format",
        )
        assertTrue(
            format.contains("a claim line in a draft included"),
            "The exemption now covers what a draft reports about the material as well as its " +
                "own voice, which is the half that is still a finding: $format",
        )
    }

    @Test
    fun `a shape promising one answer is told to write one and only one`() {
        // The failure this prevents is silent and total. The marker is described as what starts
        // each alternative, so a prompt that never says how many invites more than one — and the
        // runner then rejects every answer a single-answer shape produces, retries, gets the same,
        // and degrades to nothing-to-answer-from. Which is a shape that can never answer at all.
        val format = Reply.format(1..1, sections = true)

        assertTrue(format.contains("one answer and one only"), format)
        assertFalse(format.contains("between"), format)
    }

    @Test
    fun `a shape promising a range is told both ends of it`() {
        val format = Reply.format(2..3, sections = true)

        assertTrue(format.contains("between 2 and 3 alternatives"), format)
        assertFalse(format.contains("one answer and one only"), format)
    }

    // --- fixtures -------------------------------------------------------------------------

    private companion object {

        /** What the reply format puts in front of a line's citations, which must never reach a reader. */
        const val CITE_MARKER = "<<"

        /** Runs of whitespace as one space, so a quoted sentence survives the prompt's wrapping. */
        val WHITESPACE = Regex("\\s+")

        /**
         * Two pieces of material, numbered as a prompt would have numbered them.
         *
         * Every case that cites something cites against this one, so the numbers in a reply mean the
         * same thing throughout: 1 is the first span and 2 is the second, and anything above that
         * was never offered.
         */
        val offer = Offer(listOf(span("42"), span("43")))

        fun span(nativeId: String) = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, nativeId))
    }

    @Test
    fun `a shape that never sections is told not to write a heading`() {
        // The whole of what stops a drafted reply arriving divided under headings nobody can send.
        // It has to be in the format rather than in the shape's own prose, because the shape that
        // must not have them is not the shape that knows what a heading is.
        val format = Reply.format(1..1, sections = false)

        // The whole sentence, not the words "no headings": the Markdown paragraph below it forbids
        // the `#` form to every shape, so a substring match would hold with the ban itself gone.
        //
        // The one assertion here, because it is the only one that distinguishes this case. That the
        // marker is absent from the format is true of both settings and is asserted once, below.
        assertTrue(format.contains("No preamble, no code fences, no headings."), format)
    }

    @Test
    fun `a shape that may section is neither forbidden a heading nor offered one`() {
        // Not forbidden, because it may be asked for sections; not told how to write one either,
        // because whether this request has sections is a fact about its material. The permission
        // travels with the asking, which is the next case but one.
        val format = Reply.format(1..1, sections = true)

        assertTrue(format.contains("No preamble and no code fences."), format)
        assertFalse(format.contains("No preamble, no code fences, no headings."), format)
    }

    @Test
    fun `the standing format never shows the heading marker, to either kind of shape`() {
        // **Asserted over both settings, because it holds for both and belongs to neither case.**
        // Written into the non-sectioning case it read as that case's rule and was not: the marker
        // lives in the sectioning block, so its absence from the format is green whichever way
        // `sections` is set, and the two cases above each carried the same line. What it does catch
        // is the heading instructions being moved into the standing format, which would hand the
        // permission to every request of every shape — including the shape that must never see one.
        //
        // Anchored on the marker rather than on a sentence about it, which is what the case below is
        // anchored on too: the marker is the half the reader depends on, where a wording is free to
        // move.
        listOf(true, false).forEach { sections ->
            val format = Reply.format(1..1, sections = sections)

            assertFalse(format.contains("[HEADING]"), "sections = $sections: $format")
        }
    }

    @Test
    fun `a shape whose answer is its findings is told that an absence is an assertion too`() {
        // **The sentence that makes the runner's refusal obeyable.** A reply from such a shape
        // carrying no claim at all is rejected, and the statement a bad answer reaches for — that
        // the material holds nothing about what was asked — is exactly the one a model writes as
        // prose, where nothing checks it and it costs the reply its only assertion. So the
        // instruction names that case rather than leaving it to follow from the paragraph saying
        // composed prose carries no claims.
        //
        // Both the clause and its absence, because the pair is the check: a paragraph added to
        // every shape's format would read as this case passing while refusing a draft the
        // claims-only scope exists to protect.
        val asserting = Reply.format(1..1, sections = true, asserts = true).replace(WHITESPACE, " ")
        val composing = Reply.format(1..1, sections = true, asserts = false).replace(WHITESPACE, " ")

        assertTrue(asserting.contains("a statement about the material is one of them"), asserting)
        assertTrue(asserting.contains("holds nothing about what was asked"), asserting)
        assertFalse(
            composing.contains("a statement about the material is one of them"),
            "Every shape is told its answer is assertions, including the ones whose answer is a " +
                "message somebody sends: $composing",
        )
        assertTrue(
            composing.contains("Prose you composed"),
            "The paragraph every shape gets about composed prose is gone: $composing",
        )
    }

    @Test
    fun `what a heading looks like is told to the shapes that are asked for sections`() {
        // Where the permission lives. That it reaches a request with sections and no other is
        // asserted over assembly, which is the half that decides; this one is that the marker is
        // in the asking rather than in the standing format.
        //
        // Anchored on the marker rather than on a sentence about it, because the marker is the half
        // the reader depends on: a reworded instruction is a rewording, but a marker that stops
        // matching the one [Reply.read] looks for is a heading the model announces and nothing
        // recognises — which is the defect this exists to prevent, silently restored.
        val sectioning = Reply.sectioning()

        assertTrue(sectioning.contains("[HEADING]"), sectioning)
        assertTrue(sectioning.contains("Section the answer by them"), sectioning)
    }

    @Test
    fun `the sectioning ask carries its own exception for a shape held to a count of items`() {
        // **The ask and the exception are one block, because split across two they contradict.**
        // This paragraph is imperative and a shape bounded to a handful of items cannot obey it
        // and the bound together; a request carrying the two from two places leaves the model to
        // pick, and what it picks is to merge the bodies of work and head nothing — every claim
        // still resolving, so nothing downstream sees it. Measured that way against the live model
        // before this existed.
        //
        // The exception has to preserve what sectioning is for, which is why the label moving onto
        // the item is asserted and not only the permission to drop the heading: a flat list with
        // no labels is the merge this rule exists to prevent, reached by a shorter route.
        val sectioning = Reply.sectioning()

        assertTrue(
            sectioning.contains("do not section the answer"),
            "a bounded shape is told to section and to obey a count it cannot: $sectioning",
        )
        // Matched short of the line wrap: this block is asserted raw, where a shape's instruction
        // is read through a whitespace collapse, so a phrase spanning two source lines is a pin
        // that fails on where the paragraph happens to break.
        assertTrue(
            sectioning.contains("name on each item the body of work"),
            "dropping the headings is permitted without keeping the attribution: $sectioning",
        )
        // The condition, not only the consequence. Widened to any bounded answer it would stop
        // every sectioned brief from sectioning.
        assertTrue(
            sectioning.contains("would leave a section with fewer than two"),
            "the exception states no threshold, so it reads as permission to never section: $sectioning",
        )
        // And that it stays in the per-request ask rather than migrating to the standing format,
        // where it would reach the shape that must never be offered a heading at all.
        listOf(true, false).forEach { sections ->
            assertFalse(
                Reply.format(1..1, sections = sections).contains("do not section the answer"),
                "the exception reached the standing format, sections = $sections",
            )
        }
    }
}
