package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider.Batched
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider.Reply
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The extraction stage: two model calls, a delimited reply, and the several ways a model answers
 * something that cannot be used.
 *
 * What is asserted here is not that a prompt produces good findings — that is the evaluation
 * set's, against a live model. It is everything around the call that decides whether a bad answer
 * becomes bad data: that a reply nobody could read is not treated as an empty window, that an
 * identifier the model was not asked about is refused rather than cited, that a window the model
 * declined is settled rather than retried for ever, and that the only clock in the pass is the
 * corpus's own.
 */
class ExtractorTest {

    @Test
    fun `a window with no text is asked nothing`(): Unit = runBlocking {
        val silent = Window(
            conversation = CHAT,
            messages = List(3) { message("m$it", "") },
            target = ExternalId(Source.TELEGRAM, "m0"),
        )

        // Nothing to ask is how the pass learns there is nothing to pay for: a null request is what
        // it checks before it submits. What it settles afterwards is `InferrerTest`'s to assert, in
        // `a window with no text to classify settles its records without a call`.
        //
        // No call-count assertion here: `classification` builds a request and never makes one, so a
        // count would read zero whatever the function did.
        assertNull(
            Extractor(FakeModelProvider()).classification(silent),
            "A window of stickers produced a request, so the pass would submit and be billed for it.",
        )
    }

    @Test
    fun `a window holding nothing is one call and no proposals`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(Reply.of("none")))

        // Read the whole way through rather than stopping at the classification, because that is
        // what makes the call count discriminate: a `none` line wrongly turned into a candidate
        // would be restated, and the second call is the only visible trace of it.
        val read = readWindow(model)

        assertEquals(emptyList(), read.proposals)
        assertEquals(0, read.lost)
        assertEquals(0, read.refused)
        assertEquals(1, model.callCount, "Nothing was found and the restating stage ran anyway.")
    }

    @Test
    fun `a commitment is read with its evidence, its hedge and its date`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|hedged|none"),
                Reply.of("2026-03-06T12:00:00Z|none|none|The supplier will send the revised quote by Friday."),
            ),
        )

        val extraction = readWindow(model, window("m2"))

        val proposal = extraction.proposals.single()
        assertEquals(ProposalKind.COMMITMENT, proposal.kind)
        assertEquals("The supplier will send the revised quote by Friday.", proposal.claim)
        assertTrue(proposal.hedged, "The hedge marker was dropped, so a maybe would be shown as a promise.")
        assertEquals(Instant.parse("2026-03-06T12:00:00Z"), proposal.dated)
        assertEquals(
            SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, "m2")),
            proposal.evidence.span,
            "The evidence points at the message the commitment was stated in, by the same " +
                "address the index holds — which is what lets a citation be checked.",
        )
        assertEquals(SECOND, proposal.evidence.sourceTime)
        assertEquals(
            ExternalId(Source.TELEGRAM, "author-m2"),
            proposal.about,
            "A proposal references the record whose author stated it, and never an entity.",
        )
    }

    @Test
    fun `an event is read as an event`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("event|m1|plain|none"),
                Reply.of("2026-04-01T09:00:00Z|none|none|A review happens on 1 April."),
            ),
        )

        val proposal = readWindow(model).proposals.single()

        assertEquals(ProposalKind.EVENT, proposal.kind)
        assertEquals(false, proposal.hedged)
    }

    @Test
    fun `an attribute is read with the predicate it is keyed by`(): Unit = runBlocking {
        // The kind that becomes a statement rather than an entity. The predicate is what the
        // statement is keyed by, and the claim is the value alone — "works at" and "Acme Ltd"
        // rather than one sentence holding both, because a value nothing can compare is a fact
        // that will never be found to contradict anything.
        val model = FakeModelProvider(
            listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|Works   At|none|Acme Ltd")),
        )

        val proposal = readWindow(model).proposals.single()

        assertEquals(ProposalKind.ATTRIBUTE, proposal.kind)
        assertEquals(Predicate.of("works at"), proposal.predicate, "The predicate reached the store unnormalised.")
        assertEquals("Acme Ltd", proposal.claim)
        assertNull(proposal.dated, "An attribute has no date; its period is a validity window.")
    }

    @Test
    fun `an attribute with no predicate is not stored under nothing`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|none|none|Acme Ltd")),
        )

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(
            listOf(Restatement.Unused.UNNAMED),
            extraction.unused,
            "The refusal word is what the instruction asks for where no entry fits, so this is " +
                "the figure that bears on the list — not one saying the build misread anything.",
        )
    }

    /**
     * A predicate outside the vocabulary is lost, not stored under its own name.
     *
     * **This is a keying defect and not a tidiness one.** Resolution groups readings by subject
     * and predicate to find the ones that contradict each other, so a rate recorded once as
     * `charges` and later under any other spelling is two series that never meet — the superseded
     * value keeps its open window for ever, and nothing in the system reports a fact that is
     * present, plausible and never contradicted.
     *
     * **What the parser tests is membership, not language**, and the cases below are chosen to
     * say so. The leak that prompted this rule was the model answering in the conversation's own
     * language — which the instruction to write the *claim* in that language invites — but that is
     * one instance of a predicate not on the list rather than a separate kind of fault. So these
     * are English synonyms and near-misses: no corpus-script string appears here, because a rule
     * checking for a script is not the rule being asserted. The instructions themselves do carry
     * the corpus's script, which is the one place Kotlin here is allowed to.
     *
     * Either way the call has already been paid for, which is why losing the finding is the right
     * trade rather than a cheap one.
     */
    @Test
    fun `a predicate outside the vocabulary is lost rather than keyed under a synonym`(): Unit = runBlocking {
        listOf("employed by", "works for", "is located in", "charges a fee of").forEach { offered ->
            val model = FakeModelProvider(
                listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|$offered|none|Acme Ltd")),
            )

            val extraction = readWindow(model)

            assertEquals(
                emptyList(),
                extraction.proposals,
                "`$offered` was stored as a predicate. Nothing else will ever query that key, so " +
                    "the fact it names cannot be contradicted by a later reading.",
            )
            assertEquals(
                listOf(Restatement.Unused.OUTSIDE),
                extraction.unused,
                "A stretched predicate has to be counted as one, not swallowed and not booked as " +
                    "the refusal the instruction actually asked for.",
            )
        }
    }

    @Test
    fun `every predicate in the vocabulary is accepted, normalised`(): Unit = runBlocking {
        // The other direction of the rule above, and the reason it is a separate case: a parser
        // that rejected everything would satisfy the test before this one completely.
        Extractor.PREDICATES.forEach { offered ->
            val model = FakeModelProvider(
                listOf(
                    Reply.of("attribute|m1|plain|$AUTHOR_NAME"),
                    Reply.of("none|  ${offered.uppercase()} |none|Acme Ltd"),
                ),
            )

            val proposal = readWindow(model).proposals.single()

            assertEquals(
                Predicate.of(offered),
                proposal.predicate,
                "`$offered` is in the vocabulary and was not accepted, so the prompt offers the " +
                    "model a word the parser throws away — a call paid for and discarded.",
            )
        }
    }

    /**
     * A value of a sort no entry takes is refused, whatever the predicate field said.
     *
     * **The one route into the store that no rule about the model's answer can close.** Every other
     * check here reads what came back for its own evidence — a word, a shape, a label echoed from
     * the prompt — so each of them can be satisfied by an answer that is well formed and wrong. The
     * subject rule asks which party was named, and a fact about a machine attributed to the author
     * passes it; the vocabulary figures read the predicate, and `is based in` is on the list. What
     * was left is a legal name over a value of a sort that name does not take, and that reached the
     * store with an open validity window and a real message citing it — which on the answer path
     * reads exactly as a fact that is true.
     *
     * **Each case is a different shape rather than four hostnames**, because the rule is a shape
     * test and not a list of known hosts: one of them would pass if the test had been written
     * against the one value that prompted it.
     */
    @Test
    fun `a value no predicate takes is refused, apart from the answers read off the field`(): Unit = runBlocking {
        listOf(
            "https://example.test/health",
            "/var/lib/postgresql",
            "10.0.0.5:5432",
        ).forEach { shaped ->
            val model = FakeModelProvider(
                listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|is based in|none|$shaped")),
            )

            val extraction = readWindow(model)

            assertEquals(
                emptyList(),
                extraction.proposals,
                "`$shaped` was stored as somewhere a person is based. Nothing downstream can tell " +
                    "it from a true fact: the predicate parses, the window is open, and the " +
                    "citation checks out.",
            )
            assertEquals(
                listOf(Restatement.Unused.MISSHAPEN),
                extraction.unused,
                "`$shaped` was refused and booked as something else. The two figures read off the " +
                    "predicate field are blind to this by construction — the name is legal and only " +
                    "the value is wrong — so a reading counted there would be read against the " +
                    "width of the list.",
            )
        }
    }

    /**
     * A dotted token of letters is kept, whichever entry it arrived under.
     *
     * **The shapes a value test must not police here, pinned as cases rather than asserted in
     * prose.** A rail over dotted hosts was tried on this corpus and measured wrong in both
     * directions: written in the corpus's own script a host passed it untouched, while an
     * abbreviated Latin place name and an abbreviated job title were refused and lost. The two
     * classes coincide — a host and a place are both a dotted token of letters — so neither
     * widening nor narrowing the pattern separates them, and the rail was removed.
     *
     * These are the values that refusal cost, named in the review that removed it. A case rather
     * than a comment, so that re-adding such a pattern fails here instead of being argued about
     * again. Latin only: the corpus's own script belongs in the instructions, which are the one
     * place this package's Kotlin may carry it, and the rule being asserted is about shape rather
     * than about any script.
     */
    @Test
    fun `a dotted token of letters is kept, because a host and a place are one shape`(): Unit = runBlocking {
        mapOf(
            "is based in" to "St.Petersburg",
            "has the role" to "Sr.Developer",
            "works at" to "Corp.Berlin",
        ).forEach { (predicate, value) ->
            val model = FakeModelProvider(
                listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|$predicate|none|$value")),
            )

            val extraction = readWindow(model)

            assertEquals(
                listOf(value),
                extraction.proposals.map { it.claim },
                "`$value` was refused under `$predicate`. A dotted-token rail cannot tell a place " +
                    "or a title from a host, and it misses every host in the corpus's own script, " +
                    "so what it buys is this loss and nothing else.",
            )
        }
    }

    /**
     * The values each entry really takes are not touched by the shape test.
     *
     * **The direction the case above cannot assert**, and the one that matters more: a test that
     * refused everything would satisfy the refusal case completely. A rate carries a decimal point
     * and a solidus, a place carries spaces, a title carries neither — and every one of them would
     * be lost in silence, counted under a figure that reads as the model answering badly.
     *
     * Latin throughout, deliberately: a place name in the corpus's own script cannot match a test
     * written in ASCII, so a case in that script would assert the character class rather than the
     * rule. The instructions are the one place in this package's Kotlin that carries that script.
     */
    @Test
    fun `a value of the sort its predicate takes is kept, whatever punctuation it carries`(): Unit = runBlocking {
        mapOf(
            "charges" to "1500.50",
            "is based in" to "Tbilisi, Georgia",
            "has the role" to "CTO",
            "works at" to "Westvale Freight",
        ).forEach { (predicate, value) ->
            val model = FakeModelProvider(
                listOf(Reply.of("attribute|m1|plain|$AUTHOR_NAME"), Reply.of("none|$predicate|none|$value")),
            )

            val extraction = readWindow(model)

            assertEquals(
                listOf(value),
                extraction.proposals.map { it.claim },
                "`$value` is what `$predicate` takes and the value test refused it, so a true " +
                    "attribute is lost and counted as the model having answered badly.",
            )
        }
    }

    /**
     * A fact the stage cannot label is reported as such, and asks for nothing.
     *
     * **The figure that was missing, and the reason a fourth kind exists at all.** The three kinds
     * that become rows are decided with the closed vocabulary in view, so a fact outside it left the
     * stage two moves and no third: call it an attribute anyway and let the next stage key it under
     * whichever entry parsed, or report nothing. The second is byte-identical to a window that held
     * nothing — no line, no figure, no clause — so a sweep writing no attributes could be read
     * either way, and this build read it as a corpus with none in it.
     *
     * The asks are asserted empty as well as the figure counted: nothing is stored from one of
     * these, so the whole of what it may cost is the classification already paid for.
     */
    @Test
    fun `a fact outside the vocabulary is counted as its own kind and restated by nobody`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(Reply.of("${Extractor.UNLISTED}|m1|plain|none")))

        val found = assertIs<Candidacy.Found>(classify(model))

        assertEquals(
            1,
            found.unlisted,
            "A fact named as outside the list was not counted, so a stage declining material it " +
                "cannot label still reads exactly like a stage dropping it in silence.",
        )
        assertEquals(emptyList(), found.asks, "A kind nothing stores was sent to the stage that restates.")
        assertEquals(0, found.lost, "The instructed answer was reported as a line this build could not read.")
        assertEquals(0, found.refused, "The instructed answer was booked as a rule declining a finding.")
    }

    @Test
    fun `a fact outside the vocabulary placed on a context message is refused, not counted`(): Unit = runBlocking {
        // The fourth kind does not escape the placement rule, and the figure is the reason it must
        // not: a fact the model took from a context line is about a record this sweep neither
        // selected nor marks, so counting it would fill the one measurement of the attribute path
        // with findings from messages that were never being read.
        val model = FakeModelProvider(listOf(Reply.of("${Extractor.UNLISTED}|m2|plain|none")))

        val found = assertIs<Candidacy.Found>(classify(model))

        assertEquals(0, found.unlisted, "A fact placed on a context line was counted as one the target stated.")
        assertEquals(1, found.refused, "A misplaced line of the fourth kind was not refused as every other one is.")
    }

    /**
     * The instruction that produces the fourth kind is held in place, in both stages.
     *
     * **A figure is read as measuring an instruction, so deleting the instruction has to go red.**
     * `unlisted` is documented as the only instrument that says whether the attribute path meets
     * material it cannot label — but the behaviour comes from two paragraphs of prose, and without
     * this every case in the suite stays green when they are deleted: each writes its own replies,
     * so they agree with the parser rather than with the prompt. The rate would then read as a
     * corpus that had gone quiet.
     *
     * **Quoted, because polarity is not derivable.** "A value of another sort is the fourth kind"
     * and its reversal are the same words in a different order, so nothing computed off the prose
     * can tell them apart. Each anchor is the shortest phrase carrying its half, so the wording can
     * be improved without this going red while deleting a half takes the assertion with it.
     */
    @Test
    fun `both instructions tie the predicate to the sort of value it takes`() {
        val classify = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")
        val rewrite = Extractor.REWRITE_SYSTEM.replace(Regex("\\s+"), " ")

        // The classification half: a value of the wrong sort is the fourth kind, and is reported
        // rather than passed over. This is the sentence the figure measures.
        assertTrue(
            "of another sort" in classify && Extractor.UNLISTED in classify,
            "The classification instruction no longer routes a value of the wrong sort to the " +
                "fourth kind, so the figure counting it measures an instruction nobody gave.",
        )
        // And it is bounded to facts of that shape. Unbounded, the figure counts whatever a
        // message happens to say and stops measuring the attribute path at all.
        assertTrue(
            "nothing else a message happens to say" in classify,
            "The fourth kind is offered without a bound, so it is the answer for every fact in " +
                "every message and the figure no longer measures material the list cannot cover.",
        )
        // The restatement half: the predicate and the claim are one answer, so a value of the
        // wrong sort cannot be filed under a name that parses.
        assertTrue(
            "stand or fall together" in rewrite,
            "The restatement instruction no longer ties the entry to the sort of value it takes, " +
                "so the stage may key whichever entry parses over whatever value it answered.",
        )
    }

    /**
     * The classification instruction offers the fourth kind as a value of the field that carries it.
     *
     * **Named in the prose is not the same as offered as an answer**, and the figure measures
     * whether the model takes it. The kind field's own line is what the model reads to know which
     * words are permitted there, so a fourth kind described in a paragraph and left out of that
     * line is one the model has no reason to believe it may write — and the figure would then read
     * as a corpus holding no such facts.
     *
     * Found by the line's own opening rather than quoted whole, so the prose around it can be
     * reworded without this going red.
     */
    @Test
    fun `the classification instruction offers the fourth kind where the kinds are enumerated`() {
        val enumerated = Extractor.CLASSIFY_SYSTEM.lines()
            .map(String::trim)
            .singleOrNull { it.startsWith("kind is") }

        assertNotNull(enumerated, "The instruction never states which words the kind field takes.")
        assertTrue(
            Extractor.UNLISTED in enumerated,
            "The fourth kind is not among the words the kind field offers, so the model has no " +
                "reason to write it and the figure counting it reads as a corpus with none: $enumerated",
        )
        ProposalKind.entries.forEach { kind ->
            assertTrue(
                kind.name.lowercase() in enumerated,
                "The field offers no word for $kind, which is a kind the pass stores: $enumerated",
            )
        }
    }

    /**
     * Both instructions say what each entry of the vocabulary takes, and not only its name.
     *
     * **A relation named and not described leaves the sort of thing it takes to be inferred**, which
     * is how a value of one sort came to be filed under a name that parsed. The gloss is one
     * declaration with the list, so an entry added to the vocabulary cannot reach the prompts
     * undescribed — and this is what holds the interpolation in place in both of them, since a
     * stage that decides whether a thing is an attribute without the glosses in view is the half of
     * the fault that was never covered.
     */
    @Test
    fun `both instructions say what each entry of the vocabulary takes`() {
        Extractor.PREDICATES.forEach { predicate ->
            assertTrue(
                "$predicate takes " in Extractor.VALUES,
                "`$predicate` reaches the instructions as a bare name, so the sort of value it " +
                    "takes is left to be inferred: ${Extractor.VALUES}",
            )
        }

        mapOf(
            "classification" to Extractor.CLASSIFY_SYSTEM,
            "restatement" to Extractor.REWRITE_SYSTEM,
        ).forEach { (stage, instruction) ->
            assertTrue(
                Extractor.VALUES in instruction,
                "The $stage instruction carries the vocabulary without the glosses, so the model " +
                    "chooses among four names and nothing says what any of them is for.",
            )
        }
    }

    /**
     * Every instruction reaches the model as prose, with nothing left indented.
     *
     * **The hazard is interpolation rather than formatting.** The indentation is stripped from the
     * string after its values have been substituted into it, so a value carrying newlines of its
     * own contributes lines with no indentation at all, the common prefix to be stripped falls to
     * nothing, and every other line keeps the indentation of this source file. The model is then
     * handed what reads as a block of quoted code, silently and on every call.
     *
     * Nothing else here would notice: every case in this suite writes its own replies, so they
     * agree with the parser by construction rather than with the prompt.
     */
    @Test
    fun `every instruction reaches the model with nothing indented`() {
        mapOf(
            "classification" to Extractor.CLASSIFY_SYSTEM,
            "restatement" to Extractor.REWRITE_SYSTEM,
            "adjudication" to Extractor.ADJUDICATE_SYSTEM,
        ).forEach { (stage, instruction) ->
            val indented = instruction.lines().filter { it.isNotBlank() && it != it.trimStart() }

            assertEquals(
                emptyList(),
                indented,
                "The $stage instruction keeps this file's indentation on ${indented.size} line(s), " +
                    "which the model reads as quoted code. The likeliest cause is a value " +
                    "interpolated into it that carries newlines of its own.",
            )
        }
    }

    /**
     * The prompt offers exactly the vocabulary the parser enforces.
     *
     * One decision held in three places, any of which can be edited alone: both instructions and
     * the parser. A prompt naming a word the parser drops spends a call on a finding that cannot
     * be stored; a parser accepting one the prompt never offered is the open vocabulary returning
     * by the back door; and a list named to the stage that restates a finding but not to the one
     * that decides whether it is an attribute at all is the fault this case was extended for.
     *
     * **What each half can actually catch is worth being exact about.** Both instructions
     * interpolate the list, so the presence half cannot fail on a predicate being added — it
     * arrives in the prompts by construction. What it does catch is the interpolation being
     * dropped from either instruction, which is the regression that matters here, since one stage
     * carrying the list and the other not is the fault above. The absence half is the one that
     * bites on content: it fails if a near-synonym is left in the prose beside the list, where a
     * model would read it as a further entry the parser then refuses.
     */
    @Test
    fun `the standing instructions name every predicate and no other`() {
        // Both stages, not only the one that answers a predicate. The classification stage
        // decides whether a thing is an attribute at all, and it decided that without the
        // vocabulary in view — so it reached for the nearest of four words it had never been
        // shown. A list named in one instruction and not the other is that fault returning.
        mapOf(
            "classification" to Extractor.CLASSIFY_SYSTEM,
            "restatement" to Extractor.REWRITE_SYSTEM,
        ).forEach { (stage, instruction) ->
            Extractor.PREDICATES.forEach { predicate ->
                assertTrue(
                    predicate in instruction,
                    "The vocabulary holds `$predicate` and the $stage instruction never mentions " +
                        "it, so the model has no way to know it may answer that.",
                )
            }

            // Nothing else that looks like one. The instructions tell the model the list is
            // closed, so an example left in the prose beside it reads as a further entry the
            // parser refuses.
            listOf("employed by", "lives in", "costs", "is called").forEach { absent ->
                assertFalse(
                    absent in instruction,
                    "The $stage instruction offers `$absent`, which is not in the vocabulary. The " +
                        "model would answer it and the parser would drop the finding.",
                )
            }
        }
    }

    @Test
    fun `the restatement instruction rules out both values the parser refuses`() {
        // **The figures measure an instruction, so the instruction has to be held in place.** The
        // parser refuses two values, and a sweep reads the rate of each as whether the prose
        // against them is working — but prose is asserted by nothing here, so deleting the
        // paragraph that rules them out would leave both rates reading as a model that had stopped
        // obeying an instruction it was no longer given.
        //
        // Driven off the enumeration rather than a list, so a third refusal added to the parser
        // fails to compile here until the instruction names it too. Each phrase is the shortest
        // that carries the rule, because what has to hold is that the model is told, not any
        // particular wording of the telling.
        Restatement.DateRefusal.entries.forEach { refusal ->
            val ruledOutBy = when (refusal) {
                // Scoped to the party who is to do the thing, because the parser is: an obligation
                // on somebody else keeps a date already behind, so an instruction ruling the value
                // out for every commitment would buy a fabricated date or a lost one on exactly
                // the material the refusal was narrowed to admit.
                Restatement.DateRefusal.BEFORE_MESSAGE -> "never due before its own message"
                Restatement.DateRefusal.SAME_INSTANT -> "send time"
            }

            assertTrue(
                ruledOutBy in Extractor.REWRITE_SYSTEM,
                "The parser refuses $refusal and the restatement instruction does not rule it " +
                    "out, so the model is billed for a date that is discarded and the figure " +
                    "counting it measures nothing anybody asked for.",
            )
        }
    }

    @Test
    fun `the restatement instruction asks for another party's overdue deadline rather than refusing it`() {
        // **The half of the narrowing no figure can catch.** The parser now keeps a past date on an
        // obligation the author states about somebody else, but the model only writes one if it is
        // told to: an instruction still saying a commitment is never due before its message would
        // have it answer the sentinel, and the store would hold no date exactly as before — with
        // the refusal counting nothing, so every figure would read as the fix working.
        //
        // Both halves are quoted because a rule and its reversal are the same words bar one: the
        // scope says which obligations this is about and the action says what to do with the
        // timing, and either alone reads the same whichever way the instruction runs.
        //
        // Matched against the instruction with its whitespace collapsed, because the prompt is hard
        // wrapped: a phrase that happens to straddle a line break would otherwise fail here, and
        // the fix for that would be to reflow a prompt to suit a test rather than the model.
        val instruction = Extractor.REWRITE_SYSTEM.replace(Regex("\\s+"), " ")

        assertTrue(
            "Where the party field names another participant" in instruction,
            "The instruction does not scope the past-date rule by who is obliged, so the model is " +
                "given one rule for readings the parser now treats differently.",
        )
        assertTrue(
            "write the time the message states" in instruction,
            "Nothing tells the model to keep a deadline that had already been missed, so the " +
                "material the refusal was narrowed to admit still arrives with no date.",
        )
        // The third case, which the instruction itself manufactures: the party field is also the
        // sentinel wherever no line carries that party's label. Left unsaid, the nearest rule is
        // the one above — the party is not the sender, so keep the past date — and the parser
        // refuses exactly that, booking a date the model was told to give under the figure read as
        // the record contradicting itself.
        assertTrue(
            "Where the party field is none" in instruction,
            "The instruction covers the sentinel for the party nowhere, so the one answer it " +
                "leaves for an unplaceable party is the one the parser throws away.",
        )
        assertTrue(
            "party is who a commitment obliges" in instruction,
            "The party field is in the answer format and nowhere described, so what the model " +
                "writes there is a guess — and the field count makes a wrong guess unreadable.",
        )
    }

    @Test
    fun `the restatement instruction offers the sentinel as an answer, not only as a field rule`() {
        // Step three of the card: an unstated date is the expected answer rather than a gap to
        // fill, shown worked through. Both halves are asserted because the sentinel was always
        // named and still reached only part of the time — what was missing was an example of it
        // being the answer, beside the example of a date that resolves.
        val instruction = Extractor.REWRITE_SYSTEM

        assertTrue(Extractor.NOTHING in instruction, "The sentinel is not named at all: $instruction")
        assertTrue(
            instruction.contains("has the date ${Extractor.NOTHING}"),
            "No worked example shows the sentinel as the date of something real, so it reads as " +
                "the word for a field that does not apply rather than as the ordinary answer.",
        )
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").containsMatchIn(instruction),
            "No worked example shows a date that does resolve, so the pair the example exists to " +
                "contrast is only half there.",
        )
    }

    /**
     * Each instruction asks for exactly as many fields as its parser reads.
     *
     * **Computed from both sides, because a disagreement here is silent and total.** The parser
     * takes a line of the right field count and nothing else, so a field added to an instruction
     * without the constant moving — or removed from it — makes every line of every reply
     * unreadable. The pass then reports a corpus with nothing in it, having paid for every call:
     * no exception, no warning, and the records settle or stay owed depending on which stage it
     * happened to. Nothing else in this suite would notice, because every case here writes its own
     * replies and so agrees with the parser by construction rather than with the prompt.
     *
     * The format line is found by its delimiter rather than by quoting it, so rewording the prose
     * around it cannot make this fail — and renaming a field cannot make it pass by accident,
     * since what is compared is the count the parser enforces.
     */
    @Test
    fun `each instruction asks for as many fields as its parser reads`() {
        mapOf(
            Extractor.CLASSIFY_SYSTEM to Extractor.CLASSIFY_FIELDS,
            Extractor.REWRITE_SYSTEM to Extractor.REWRITE_FIELDS,
        ).forEach { (instruction, fields) ->
            // The answer format is the one line that is only field names and delimiters. Anything
            // with a space in it is prose about the fields rather than the format itself.
            val formats = instruction.lines()
                .map(String::trim)
                .filter { it.contains(Extractor.FIELD) && it.none { character -> character.isWhitespace() } }

            val format = formats.singleOrNull()
            assertNotNull(
                format,
                "The instruction states its answer format ${formats.size} times, so this cannot say " +
                    "which one the parser has to agree with: $formats",
            )
            assertEquals(
                fields,
                format.split(Extractor.FIELD).size,
                "`$format` asks the model for a different number of fields than the parser reads " +
                    "($fields). Every reply would be dropped, and the pass would report a corpus " +
                    "with nothing new in it after paying for every call.",
            )
        }
    }

    /**
     * The hedge clause carries cues in the corpus's own script, not in English alone.
     *
     * **Computed rather than quoted, because the wording is not the property worth pinning.** An
     * assertion that the clause contains a given sentence goes red when the sentence is improved
     * and green when its meaning is reversed, so it protects the phrasing and not the behaviour.
     * What is checkable here is that non-Latin cues are present at all: the marker tracked
     * grammatical person precisely because the only examples it had were English, and an
     * ASCII-only tidy-up of this prompt would restore that silently.
     *
     * Whether the marker is *correct* cannot be asserted hermetically — the model is a fake here,
     * so nothing in this suite exercises the judgement. That check needs a real sweep and belongs
     * with the card that re-measures recognition.
     *
     * **The computed half is checked over the hedge clause and not over the whole instruction,
     * because the instruction now carries the script in more than one place.** The rule refusing
     * work nobody undertook shows the sentences it refuses and one it keeps, all three in the
     * corpus's own script — so a test satisfied by a character anywhere in the instruction would
     * stay green with these cues taken out, which is the single deletion it exists to catch. The
     * clause is found by the sentence that introduces it rather than by its own wording, so the
     * cues can be improved freely and only their removal reds this.
     */
    @Test
    fun `the hedge clause offers cues in the corpus's own script`() {
        val corpusScript = Regex("[\\u0400-\\u04FF\\u10A0-\\u10FF\\u1C90-\\u1CBF\\u2D00-\\u2D2F]")
        val clause = Extractor.CLASSIFY_SYSTEM
            .substringAfter("The speaker is unsure of it", "")
            .substringBefore("Or the speaker does not have it first hand")

        assertTrue(
            corpusScript.containsMatchIn(clause),
            "The hedge clause carries no cue in the corpus's own script. Asked for tentativeness " +
                "with English examples only, the model substituted the cue it could see, which " +
                "was grammatical person. The instruction as a whole carries the script elsewhere, " +
                "so this is read over the clause: $clause",
        )
    }

    /**
     * The kinds are told apart by who is to do the thing, not by whether a time is attached.
     *
     * **Quoted rather than computed, because polarity is the property and polarity is not
     * derivable**: the rule and its reversal are the same words in a different order, so nothing
     * read off the prose can tell "an obligation is a commitment" from "an obligation is an
     * event". Each phrase is the shortest that carries its half, so the wording can be improved
     * without this going red, while deleting a half takes the assertion with it.
     *
     * **Whether the model then obeys it is not assertable here** — the provider is a fake, so no
     * case in this suite exercises the judgement. The figures are in the issue this was filed as,
     * which is where a rate belongs; and what settles it is not a figure a sweep reports but the
     * corpus being searched for obligations on another party, since nothing in the store tells an
     * occasion apart from an undertaking recorded as one.
     *
     * **Each site is anchored separately.** The rule is stated twice on purpose — once in the kind
     * list and once in the paragraph that carries the operative case — and one assertion over both
     * would be satisfied by either, so deleting the paragraph that is the whole point of the change
     * would leave this green.
     */
    @Test
    fun `the classification instruction makes an obligation on another party a commitment`() {
        // Line breaks collapsed, because the instruction is a wrapped block and a phrase that
        // happens to straddle a wrap is not a phrase the model reads differently. Matching the raw
        // string would pin this suite to one paragraph's line endings.
        val instruction = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")

        // The commitment definition carried no first-person requirement and did not need to grow
        // one: what was missing is the statement that it does not matter who performs the thing.
        assertTrue(
            "will be done, whoever" in instruction,
            "The commitment definition does not say it holds whoever performs the thing, so an " +
                "obligation the speaker places on somebody else reads as neither kind until the " +
                "time attached to it sends it to the event read.",
        )

        // The operative case, in its own paragraph and anchored on its own phrase.
        assertTrue(
            "A commitment is a commitment" in instruction,
            "The instruction states the kind holds whoever performs the thing but never says so " +
                "of the case that matters — an obligation the speaker places on another " +
                "participant — which is the reading that was going to the event kind.",
        )

        // The exclusion is the other half, and the cheaper one: the event kind was earning
        // obligations because its definition admits anything with a time in it.
        assertTrue(
            "not something somebody undertook" in instruction,
            "The event definition never says what an event is not, so an obligation carrying a " +
                "deadline still satisfies it — and is then read as an occurrence, selected on a " +
                "window of the moment it was never due to happen in.",
        )

        // And the kind still has to exist. An exclusion wide enough to empty it would read as a
        // fix here while costing every genuine occasion its record. Anchored on what the kind is
        // for rather than on the timing it used to require, a time no longer being one of its
        // conditions — see the rule below, which is where that is asserted.
        assertTrue(
            "happened or is to happen" in instruction,
            "The event kind no longer states what it is for, so something that genuinely happens " +
                "has nowhere to go.",
        )
    }

    /**
     * A time is not required of an event, and what is not reported as having happened is still not
     * one.
     *
     * **Quoted rather than computed, for the same reason as the rule above it**: what the prose has
     * to carry is a polarity, and no property read off the text distinguishes "a time is not
     * required" from its reversal. Each phrase is unique in the instruction, so an assertion cannot
     * be satisfied by a sentence it was not written for.
     *
     * **Each phrase carries its clause's polarity and not merely its subject**, which is the
     * failure this case was first written with: pinning `says when` passes just as well on *only
     * where the message says when*, the wording that reinstates the defect. A pin a reversal
     * satisfies cannot fail for the reason it names, so the break this is calibrated against is
     * the reversal and not the deletion — deleting the clause takes the substring with it and
     * proves only that the words were there.
     *
     * **Both arms, because the recall this adds is bought with precision if the exclusion goes.**
     * Widening the kind to anything the model reads as having occurred would take the questions,
     * opinions, fragments and preferences with it — most of what the issue sampled, and correct to
     * produce nothing — and the figure that would move is the one nobody is watching. So the
     * exclusion is pinned beside the inclusion rather than left to the paragraph above it, which
     * excludes an undertaking and says nothing about the rest.
     *
     * **The undertaking clause is pinned because the two paragraphs otherwise overlap.** The
     * paragraph above excludes what somebody undertook to do; this one admits what a message
     * reports as done. A report that an undertaking was carried out satisfies both readings, and
     * the clause saying which it is — the occurrence, not the undertaking — is what keeps the pair
     * from being a second ambiguity of the kind this case exists to close.
     *
     * **Whether the model then obeys it is not assertable here** — the provider is a fake, so no
     * case in this suite exercises the judgement. It was measured against the live model on the
     * pair the issue names, both arms over the same windows rebuilt out of the archive, and the
     * rates are in the issue, which is where a rate belongs. What they establish is that the kind
     * was read inconsistently rather than not at all: the same construction scored far apart on
     * two messages a turn apart, which is why a rate and not a single run of each arm is what
     * shows it.
     */
    @Test
    fun `the classification instruction requires no time of an event`() {
        // Line breaks collapsed, as in the rule above: a phrase that straddles a wrap is not a
        // phrase the model reads differently.
        val instruction = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")

        // The operative half, anchored on the negation rather than on the subject it governs.
        assertTrue(
            "A time is not required" in instruction,
            "The instruction does not say that an event needs no stated time, so an occurrence " +
                "reported without one satisfies the kind only by the reader's choice — which is " +
                "the latitude the kind was read inconsistently in.",
        )

        // The same polarity at the clause that applies it, so reinstating the requirement on
        // either site fails here rather than passing on the other's wording.
        assertTrue(
            "whether or not the message says when" in instruction,
            "The instruction admits a reported occurrence but leaves a stated time a condition " +
                "of it, so the two readings of the kind are back and the model settles which " +
                "wins per call.",
        )

        // The overlap with the paragraph above, which admits and excludes the same message.
        assertTrue(
            "is the occurrence and not the undertaking" in instruction,
            "A report that an undertaking was carried out is admitted here and excluded in the " +
                "paragraph above, and nothing says which reading governs it.",
        )

        // The exclusion, which is what keeps the recall from being bought by loosening the kind.
        assertTrue(
            "does not report as having happened" in instruction,
            "The event kind admits a reported occurrence without saying what that excludes, so " +
                "anything a reader could call an occurrence qualifies — and the questions, " +
                "preferences and opinions that are correct to produce nothing start producing " +
                "events.",
        )

        // And the commonest of those shapes in the material the issue sampled.
        assertTrue(
            "being asked about" in instruction,
            "Nothing excludes a thing merely asked about, which is the commonest shape among the " +
                "messages that correctly produce nothing.",
        )

        // The fence, which was measured into existence. An exclusion that only says what is not
        // an event leaves the model looking for another home for the thing, and the nearest one
        // is a commitment — a kind this rule has no measurement to move, and one a sibling card
        // is at work on, so the two would compound unseen.
        assertTrue(
            "no reason to read it as another kind" in instruction,
            "The exclusion says what is not an event without saying that this reclassifies " +
                "nothing, so the kinds it withholds are read as commitments instead — the " +
                "over-reading a sibling card exists to remove.",
        )
    }

    /**
     * A commitment needs somebody who is to do the thing, and the rule says which somebody.
     *
     * **The half the kind's own definition cannot carry.** Saying a commitment holds whoever
     * performs the thing is what lets an obligation the speaker places on another participant be
     * read as one; read alone it also admits a sentence placing the work on nobody — work stated
     * impersonally as needing doing, and a decision to defer with nothing said about who then
     * acts. Those reach a status answer as obligations somebody owes, which is worse than an
     * omission.
     *
     * **Quoted rather than computed, for the reason the sibling case above gives**: polarity is the
     * property, and "a commitment needs a party" and "a commitment needs no party" are the same
     * words differently arranged. Each clause is anchored on the shortest phrase that carries it,
     * every one of them unique in the instruction, so a rewording survives and a deletion reds
     * exactly the clause deleted.
     *
     * **One of the pins is there for a conflict rather than for the rule.** The clause refusing a
     * deferral however firm a time it carries is what settles this against the deadline rule below,
     * which sends a date something is wanted by to a commitment. It reads as a qualification and
     * is the operative text, so it is pinned on its own; what a refused sentence becomes instead is
     * the case below.
     *
     * **What the rule must not cost is pinned by the case below**, which fails independently: a
     * guard written as a prohibition alone would take the ordinary terse undertaking with it, and
     * one keyed on the party being nameable would refuse a sound reading whose party the corpus
     * cannot name. Both shapes refused here are therefore scoped to there being nobody to act
     * rather than to the words they are put in.
     *
     * **Whether the model obeys it is not assertable here** — the provider is a fake — so the rates
     * live on the card this was filed as, measured by putting the real archive's windows through
     * both wordings of this instruction.
     */
    @Test
    fun `the classification instruction requires a commitment to have a party who is to do it`() {
        // Collapsed for the reason the sibling cases are: the instruction is a wrapped block, and a
        // phrase straddling a wrap is not a phrase the model reads differently.
        val instruction = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")

        // The requirement, and then its consequence. The first alone reads as a description of what
        // a commitment usually has; what refuses a reading is the second.
        assertTrue(
            "a commitment has a party who is to do the thing" in instruction,
            "The instruction never requires a commitment to have a party who is to do the thing, " +
                "so a sentence stating work and obliging nobody satisfies the kind as defined: " +
                "$instruction",
        )
        assertTrue(
            "a message that states none has no commitment in it" in instruction,
            "The party is described and not required, so a message naming nobody to act is left " +
                "to whichever kind the work resembles: $instruction",
        )

        // The two shapes measured, each pinned on its own scoping rather than on the whole clause,
        // so that a rewording of either survives. The deferral's scoping is the load-bearing half:
        // keyed on the deferring itself it would refuse a speaker who puts something off and says
        // who then does it, which is an undertaking.
        assertTrue(
            "impersonally as needing doing" in instruction,
            "The instruction names no shape for work stated with nobody to do it, so the rule is " +
                "an abstraction the model has to recognise the case from: $instruction",
        )
        assertTrue(
            "taking it on afterwards" in instruction,
            "The deferral shape is either absent or stated over the deferring rather than over " +
                "there being nobody to act, which costs a deferral whose next actor is named: " +
                "$instruction",
        )

        // The clause that settles this rule against the deadline rule below it, which sends a date
        // something is wanted by to a commitment. Unpinned, it is deletable with every case green,
        // and the refused example carries a firm time.
        assertTrue(
            "however firm a time it carries" in instruction,
            "Nothing says a stated time does not make unowned work a commitment, so the deadline " +
                "rule below reaches the sentence this one refuses: $instruction",
        )

        // The worked refusals, for the reason the hedge cues and the label rule both carry one: a
        // rule stated in the abstract reaches part of the time, and the cues the model can see are
        // the ones it follows. Asserted as the words they are, because what each shows is a
        // specific sentence being refused — and written as escapes, which is how this repository
        // names the corpus's script in Kotlin, test source included.
        listOf(
            "\u041D\u0443\u0436\u043D\u043E \u043E\u0431\u043D\u043E\u0432\u0438\u0442\u044C " +
                "\u0441\u0435\u0440\u0442\u0438\u0444\u0438\u043A\u0430\u0442",
            "\u0420\u0430\u0441\u043A\u0430\u0442\u043A\u0443 \u043F\u0435\u0440\u0435" +
                "\u043D\u0435\u0441\u0442\u0438 \u043D\u0430 \u0441\u043B\u0435\u0434" +
                "\u0443\u044E\u0449\u0443\u044E \u043D\u0435\u0434\u0435\u043B\u044E",
        )
            .forEach { shown ->
                assertTrue(
                    shown in instruction,
                    "The instruction shows no sentence of the shape it refuses, so the rule is " +
                        "stated in English descriptions only: $instruction",
                )
            }
    }

    /**
     * A sentence refused as a commitment is told what it becomes, and what it does not become.
     *
     * **A case of its own because the two routes fail differently.** Leaving the event route
     * unnamed costs an occasion reported in the same breath as unowned work; leaving the fourth
     * kind open costs no finding at all and instead spends [Extractor.UNLISTED]'s figure, which is
     * the only measure this build has of how often material outside the closed vocabulary arrives.
     * A figure collecting unowned work would stop answering that question, and nothing downstream
     * would show it.
     *
     * **What the rule cannot do is give the refusal a report of its own**, which is the shape the
     * fourth kind's own wording calls the strongest an instruction can take. Nothing is stored
     * from a refused sentence and no counter sees it: the window comes back holding none of the
     * four and is settled as one. So the instruction closes the routes instead, and that is what
     * these pins hold.
     */
    @Test
    fun `a sentence refused as a commitment is told what it becomes and what it does not`() {
        val instruction = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")

        assertTrue(
            "Each is none of the four" in instruction,
            "The rule says what these sentences are not and leaves the model to work out what to " +
                "answer instead: $instruction",
        )
        // Named in the terms the event paragraph states and not narrower ones: a condition given
        // twice and unequally is what makes a kind read one way on one call and another on the
        // next, which is the fault that paragraph exists to have removed.
        assertTrue(
            "an occurrence the message reports as having happened is an event" in instruction,
            "Nothing says a message refused as a commitment may still state one of the others, so " +
                "an occurrence reported in the same breath as unowned work is lost with it: " +
                "$instruction",
        )
        assertTrue(
            "is not ${Extractor.UNLISTED} either" in instruction,
            "The route to the fourth kind is left open, so work nobody undertook can be filed as " +
                "a fact outside the vocabulary and counted in the one figure that measures how " +
                "often such material arrives: $instruction",
        )
        assertTrue(
            "work waiting to be done is not a fact of that sort" in instruction,
            "The fourth kind is closed to this shape without saying why, which is the form of " +
                "instruction the fourth kind's own wording was written to avoid: $instruction",
        )
    }

    /**
     * The requirement is keyed on nobody acting, and never on nobody being named.
     *
     * **A case of its own because it is the over-correction and not the rule**, and the two fail
     * independently: the clauses above can all stand while every one of these is deleted, and a
     * rule refusing unowned work with none of these beside it reaches the ordinary terse
     * undertaking as well. Asserted as the positives they are, since a prohibition cannot carry a
     * direction to fall in.
     *
     * **The naming half is where this meets the card that resolves an obliged party.** The
     * restating stage answers `none` for a party no line labels and nothing downstream requires a
     * commitment to resolve one, so a commitment whose party cannot be named is a commitment
     * still; a rule refusing every unnamed party would discard a sound reading of that shape along
     * with the spurious ones. The instruction says so rather than leaving it to be inferred, which
     * is what these pins hold in place.
     */
    @Test
    fun `the party requirement is about nobody acting rather than about nobody being named`() {
        val instruction = Extractor.CLASSIFY_SYSTEM.replace(Regex("\\s+"), " ")

        // The bound itself, on each of the two halves it has to exclude, pinned separately: how
        // briefly the thing is put, and whether the party is one the model can name.
        assertTrue(
            "how briefly the message puts it" in instruction,
            "The bound does not reach brevity, so a terse undertaking reads as stating no party: " +
                "$instruction",
        )
        assertTrue(
            "whether the party is one you can name" in instruction,
            "The bound does not reach naming, so an undertaking by a sender the corpus names " +
                "nothing reads as stating no party: $instruction",
        )
        assertTrue(
            "anybody at all who is to do the thing" in instruction,
            "Nothing says what the refused shapes actually lack, so the rule reads as being about " +
                "how definitely a thing is put: $instruction",
        )

        // The readings that have to survive, each one kept by its own clause. The accepting clause
        // is scoped so that agreeing with a remark which obliges the sender to nothing is not an
        // undertaking.
        //
        // **It does not reach past the message being read, and saying so in the instruction was
        // measured and refused.** What the sender accepted is the target's own statement, and the
        // line that proposed it is context saying what the acceptance refers to — which is what
        // the context paragraph has context there for. Spelled out beside this clause, that
        // restriction read as a reason to refuse an acceptance outright and cost three sound
        // readings of forty-eight. What enforces it is not prose but [Extractor]'s own placement
        // rule, which refuses a finding the model attributes to a context line.
        assertTrue(
            "a thing the sender tells another participant to do" in instruction,
            "Work the sender puts on another participant is not named as having a party, so the " +
                "requirement reaches the reading the kind list was widened to admit: $instruction",
        )
        assertTrue(
            "Accepting a way of doing something that was put to the sender" in instruction,
            "Nothing says that accepting what was proposed is undertaking it, which is the " +
                "reading the party requirement costs first: $instruction",
        )
        assertTrue(
            "the thing accepted is theirs to do" in instruction,
            "The accepting clause is unscoped, so agreeing with a remark that obliges the sender " +
                "to nothing is read as an undertaking: $instruction",
        )

        // The worked keeping, which is the counterpart of the two refused sentences: a terse
        // first-person undertaking in the corpus's own script, so that the only worked examples
        // the model has in front of it are not both refusals. Escaped, as the refusals are.
        assertTrue(
            "\u043F\u043E\u0441\u043C\u043E\u0442\u0440\u044E is a commitment" in instruction,
            "Nothing shows a terse undertaking being kept, so the refused examples are the only " +
                "ones the model has in front of it: $instruction",
        )
    }

    /**
     * A party's label survives a claim written in another language.
     *
     * **The claim prose is the reader's only account of who owes what to whom**, so a translated
     * label is not a cosmetic defect: it leaves that account naming a party no label in the store
     * corresponds to. The obliged party is also recorded as a reference, and a translated label
     * costs that too — it is resolved by matching the label the window carried, so a translated one
     * resolves to no party, which gives up the direction and, on a commitment whose deadline has
     * already passed, the date along with it. Nothing joins claim prose to a contact either way.
     *
     * Two instructions were in conflict for a participant whose profile name is in another script
     * — the claim is asked for in the conversation's language, the label is asked for exactly —
     * and the model resolved it by translating the name, which makes this a prompt that could not
     * be obeyed as written.
     *
     * Quoted for the same reason as the case above. The counter-example is asserted by script
     * rather than by wording, because "copied exactly" was already there and was not enough: what
     * was missing is the prompt *showing* a label it must not produce, and an ASCII-only tidy-up
     * of this paragraph would take that away silently.
     */
    @Test
    fun `the restatement instruction forbids translating a party's label`() {
        // Collapsed for the same reason as the case above: `character for character` straddled a
        // line wrap, and a raw match read that as the rule being absent.
        val instruction = Extractor.REWRITE_SYSTEM.replace(Regex("\\s+"), " ")

        listOf("character for character", "never transliterate", "shorten it to a first name")
            .forEach { rule ->
                assertTrue(
                    rule in instruction,
                    "The restatement instruction does not say `$rule`, so a label in one script " +
                        "inside a claim in another is left to the conflict that translated it.",
                )
            }

        // The worked example is the part that was missing rather than the rule, and the half that
        // carries it is the label the model must NOT write. Computed off the script rather than
        // the wording: a count of mentions goes red when the example is improved and stays green
        // when it is reversed, and the property here is that a non-Latin rendering is shown at all.
        assertTrue(
            Regex("[\\u0400-\\u04FF]").containsMatchIn(instruction),
            "The restatement instruction shows no label in the corpus's own script, so the rule " +
                "against translating one is stated in English examples only — which is how the " +
                "hedge marker came to track grammatical person.",
        )
        assertTrue(
            "Westvale Freight" in instruction,
            "The worked example no longer carries a Latin-script label, so nothing shows the " +
                "label surviving into a sentence in another script.",
        )
    }

    @Test
    fun `the restatement instruction keeps what bounds a claim, and prefers the narrower reading`() {
        // **The widening this stage causes by doing what it is asked.** A restatement has to stand
        // without the conversation in front of the reader, so a qualifier that bounds the thing
        // reads like a dependence on context — and dropped, what is stored is fluent, correctly
        // attributed, correctly dated and broader than anything anybody said. Nothing downstream
        // recovers it: the answer path is handed the claim and not the window, and the turns
        // carrying the bound are routinely older than the tail a shape retrieves.
        //
        // Collapsed for the reason the cases above are: the prompt is hard wrapped, and a phrase
        // straddling a break would otherwise read as the rule being absent.
        val instruction = Extractor.REWRITE_SYSTEM.replace(Regex("\\s+"), " ")

        // The distinction itself, quoted. A paraphrase cannot carry it: self-containment is what
        // this stage asks for two paragraphs earlier, so a check looking only for the subject
        // would pass the instruction that produces the defect.
        assertTrue(
            "standing on its own is not the same as being general" in instruction,
            "Nothing separates making a claim self-contained from making it general, so the " +
                "instruction to stand alone is the whole of what the model is told: $instruction",
        )
        assertTrue(
            "Keep whatever bounds the thing" in instruction,
            "The instruction names no duty to carry the qualifier, so dropping one is within " +
                "what it asks for: $instruction",
        )
        // Where the bound is to be found, which is the clause that makes this a rule rather than
        // a prohibition — and the one the architecture document names as the fix. A pin on the
        // consequence alone leaves this deletable with the suite green, which is the shape of
        // defect this file's cases have been corrected for before.
        assertTrue(
            "Where the bound is what the surrounding lines supply, write it in" in instruction,
            "Nothing says the window is where a missing bound comes from, so the rule forbids " +
                "widening and offers nowhere to get the qualifier: $instruction",
        )
        // The direction to fall in, which is the half a prohibition cannot carry. Told only not to
        // widen, a model with an unclear bound has no answer to give; and the two errors are not
        // symmetrical — a claim too narrow cites evidence that supports it, a claim too wide does
        // not and still reads as settled.
        assertTrue(
            "restate the narrower reading rather than the wider one" in instruction,
            "The rule says what not to do and leaves an unclear bound unanswered, which is the " +
                "case it was written for: $instruction",
        )
        // Scoped to the two kinds it was measured on. An attribute's claim is the value alone and
        // each predicate's value is of one declared sort, so a qualifier written into one is a
        // value the parser refuses — a rule inviting it would cost the finding outright.
        // Joined to the sentence it scopes rather than asserted alone: the phrase already opens
        // the paragraph describing what a claim is, so a bare pin on it passes with this rule's
        // scoping removed — which is what the calibration run found it doing.
        assertTrue(
            "For a commitment or an event, standing on its own" in instruction,
            "The bound rule is stated over every kind, so it reaches an attribute, whose claim " +
                "is the value alone: $instruction",
        )
        assertTrue(
            "None of this reaches an attribute" in instruction,
            "Nothing excludes the kind whose claim cannot carry a qualifier: $instruction",
        )
        // The worked example, for the reason the sentinel and the label rules both carry one: a
        // rule stated in the abstract reaches part of the time, and what the measured failure
        // turned on was a bound that reads as incidental rather than as part of the promise.
        assertTrue(
            "has not agreed to use it with people" in instruction,
            "No worked example shows a bound being dropped, so the rule reads as a general " +
                "caution about accuracy rather than as the distinction the error is made on: " +
                "$instruction",
        )
        // **The polarity half, and it is a positive assertion on purpose.** The hazard is that
        // this rule is taken as licence to drop the context the paragraph above it exists to
        // supply, and the way that lands is the paragraph above being weakened or removed rather
        // than a phrase appearing here — no wording of a prompt whose point is self-containment
        // would ever contain a verbatim instruction to abandon it, so an `assertFalse` keyed on
        // one is a case that cannot fire. What a real regression here looks like is the two
        // paragraphs no longer both standing, so that is what is asserted.
        assertTrue(
            "understandable with as little else in front of the reader" in instruction,
            "The self-containment rule this one qualifies is gone, so the claim may now be a " +
                "fragment that reads only beside its conversation: $instruction",
        )
    }

    @Test
    fun `a restatement the build cannot read names why`(): Unit = runBlocking {
        // **The failure that arrives as an outcome rather than as a throw**, because the collection
        // reads it off a batch result the provider had already billed for. Only the stop reason
        // could not be read, so the candidate is given up — and what it was given up for has to
        // travel, since a build taught that value is the only thing that clears it.
        //
        // Driven straight at the stage with the outcome a collection would hand it. Of the two
        // outcomes `restated` can be given that no synchronous reply produces, this is the one it
        // actually sees: the pass short-circuits an unanswered request before calling it.
        val model = FakeModelProvider(listOf(Reply.of("commitment|m1|plain|none")))
        val extractor = Extractor(model)
        val found = assertIs<Candidacy.Found>(classify(model, extractor = extractor))

        val restated = extractor.restated(
            window(),
            found.asks.single().line,
            BatchOutcome.Unreadable("a stop reason this build cannot name", Reply.SAMPLE_USAGE),
        )

        assertEquals(emptyList(), restated.proposals, "A candidate whose restatement could not be read is lost.")
        // What the call cost is not read back here: it stays on the outcome, which is where the pass
        // totals it. Asserted in `InferrerTest`, against the figures a sweep actually reports.
        //
        // The reason is the claim of this case, and it is asserted nowhere else in the repository: a
        // candidate dropped for something nobody can read is what an operator has to look up.
        assertEquals("a stop reason this build cannot name", restated.unreadable)
    }

    @Test
    fun `a predicate offered for a commitment is ignored rather than refused`(): Unit = runBlocking {
        // The field has no meaning for a commitment, and refusing the whole reading over a
        // surplus word would lose a promise to a formatting slip.
        val model = FakeModelProvider(
            listOf(Reply.of("commitment|m1|plain|none"), Reply.of("none|works at|none|The quote gets sent.")),
        )

        val proposal = readWindow(model).proposals.single()

        assertNull(proposal.predicate)
        assertEquals("The quote gets sent.", proposal.claim)
    }

    @Test
    fun `every message's instant is put in front of the model, and nothing here reads a clock`(): Unit = runBlocking {
        // How a relative date is resolved. The extractor cannot do it — "next Friday" needs to
        // know which Friday, and the only honest anchor is the instant of the message it was said
        // in. So the prompt carries it, and the date in the answer is taken as given.
        val model = FakeModelProvider(listOf(Reply.of("none")))

        classify(model)

        val prompt = model.requests.single().prompt
        assertTrue(FIRST.toString() in prompt, "The first message's instant is not in the prompt: $prompt")
        assertTrue(SECOND.toString() in prompt, "The second message's instant is not in the prompt: $prompt")
    }

    @Test
    fun `a classification that ran out of room leaves the window owed`(): Unit = runBlocking {
        // The partial is a list of findings that stops somewhere. Reading it would settle the
        // window against a reply that was cut off, and no re-run revisits a settled record.
        val model = FakeModelProvider(listOf(Reply.truncated("commitment|m1|plain|none\ncommitm")))

        assertIs<Candidacy.NotRead>(classify(model))
        assertEquals(1, model.callCount, "A truncated classification was restated as though it were an answer.")
    }

    @Test
    fun `a declined classification is reported as settled rather than owed`(): Unit = runBlocking {
        // The one failure that must not be retried. The port documents a refusal as not
        // retryable — the same request refuses again — so a window held owed for it would be
        // re-selected and re-refused on every sweep, at a model call each, and because selection
        // takes the oldest owed records first the pass would never advance past it.
        val declined = assertIs<Candidacy.Declined>(classify(FakeModelProvider(listOf(Reply.refused("a_category")))))
        assertTrue(
            "a_category" in declined.reason,
            "The category the provider named is what tells one refusal from another, and the " +
                "window's content is given up on the strength of it: ${declined.reason}",
        )
    }

    @Test
    fun `a classification that came back empty is owed rather than read as empty`(): Unit = runBlocking {
        // The model is told to answer the single word `none` when a window holds nothing, so a
        // reply with no content is not that answer — it is an answer nobody received. Reading it
        // as an empty finding list settles the window for ever against a call that said nothing.
        assertIs<Candidacy.NotRead>(classify(FakeModelProvider(listOf(Reply.of("   ")))))
    }

    @Test
    fun `a classification that never finished leaves the window owed`(): Unit = runBlocking {
        assertIs<Candidacy.NotRead>(classify(FakeModelProvider(listOf(Reply.paused("commitment|m1|plain")))))
    }

    @Test
    fun `an invented message identifier is refused rather than cited`(): Unit = runBlocking {
        // A proposal whose evidence points at nothing would be an uncheckable citation, which is
        // the one property the whole derived layer rests on. An invented identifier is one way to
        // get there; `a finding placed on a context message` is the other, and both are refused by
        // the same check, which is why this case asserts the counter rather than the mechanism.
        val model = FakeModelProvider(listOf(Reply.of("commitment|m99|plain|none")))

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(
            1,
            extraction.refused,
            "An identifier that is not the one asked about was dropped without being counted.",
        )
        assertEquals(0, extraction.lost, "A line read perfectly and refused is not a line this build misread.")
        assertEquals(1, model.callCount, "A candidate that cannot be cited was restated anyway, and billed for.")
    }

    @Test
    fun `a finding placed on a context message is refused rather than read`(): Unit = runBlocking {
        // **The context is in front of the model to be looked at and not to be reported from.** A
        // finding attributed to a neighbouring line is a finding about a record this sweep neither
        // selected nor marks, so nothing would record that the message had been read and every
        // later window reaching over it would produce the same finding again. The instruction says
        // so; this is what makes it a rule rather than a request.
        //
        // The identifier is a real message in the window rather than an invented one, which is
        // what makes this a case of its own: a check that accepted any message present would pass
        // this line and write a finding against a record nothing marks.
        val model = FakeModelProvider(listOf(Reply.of("commitment|m2|plain|none")))

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(1, extraction.refused, "A finding read out of a context message was not counted.")
        assertEquals(1, model.callCount, "A finding that may not be written was restated anyway, and billed for.")
    }

    @Test
    fun `an attribute whose subject is not the author's label is refused`(): Unit = runBlocking {
        // The subject of a stored attribute is the author of the message it was read from, filled
        // in from the window and never from the model — so a fact about a third party used to be
        // asserted of whoever mentioned it. Anything other than the author's own label is a subject
        // this build cannot place, and dropping it at the classification is what keeps it cheap.
        //
        // The other participant's label, `none`, and a label naming nobody in the window: all three
        // are subjects that are not the author, which is the one thing that may be stored.
        listOf(OTHER_NAME, "none", "participant 9").forEach { subject ->
            val model = FakeModelProvider(listOf(Reply.of("attribute|m1|plain|$subject")))

            val extraction = readWindow(model)

            assertEquals(
                emptyList(),
                extraction.proposals,
                "A fact whose subject is `$subject` was kept, and its subject would be the author.",
            )
            assertEquals(1, extraction.refused, "A reading given up for `$subject` was not counted.")
            // One call is the classification this helper makes; a second would be the restatement.
            assertEquals(
                1,
                model.callCount,
                "`$subject` reached the restatement stage, which is the one that costs the money.",
            )
        }
    }

    @Test
    fun `an attribute stated by an author the corpus does not name is refused, unpaid`(): Unit = runBlocking {
        // **The case that made the cheap rail not cheap.** A window built for an authorless message
        // — a channel post, a service notice — once labelled that absence, so the model could echo
        // the label as the subject, the comparison passed, a restatement was bought at the higher
        // effort, and only then was the proposal dropped for having no subject to store against.
        //
        // The same holds for an author the corpus names nothing: no label, so nothing to match.
        val unnamed = Window(
            conversation = CHAT,
            messages = listOf(message("m1", "I work at Acme Ltd", FIRST, author = null)),
            target = ExternalId(Source.TELEGRAM, "m1"),
        )
        listOf("", "none", "unidentified sender", AUTHOR_NAME).forEach { subject ->
            val model = FakeModelProvider(listOf(Reply.of("attribute|m1|plain|$subject")))

            val extraction = readWindow(model, unnamed)

            assertEquals(
                emptyList(),
                extraction.proposals,
                "`$subject` was accepted as the subject of a message with no author at all.",
            )
            assertEquals(1, extraction.refused, "The reading was dropped for `$subject` without being counted.")
            // One call is the classification; a second would be the restatement this must not buy.
            assertEquals(
                1,
                model.callCount,
                "`$subject` bought a restatement at the higher effort for a reading that can " +
                    "never be stored.",
            )
        }
    }

    @Test
    fun `a subject is matched without case, as every other field is`(): Unit = runBlocking {
        // The subject is free text echoed back from the prompt, so a model that recapitalises the
        // name it was shown would otherwise lose the finding to a difference that means nothing —
        // and lose it silently, into a counter shared with every other refusal.
        val model = FakeModelProvider(
            listOf(
                Reply.of("attribute|m1|plain|${AUTHOR_NAME.uppercase()}"),
                Reply.of("none|works at|none|Acme Ltd"),
            ),
        )

        val proposal = readWindow(model).proposals.single()

        assertEquals("Acme Ltd", proposal.claim)
    }

    @Test
    fun `a stored line from another build is reported as such, not as a lost finding`(): Unit = runBlocking {
        // **The one that costs records rather than findings.** A restatement submission outlives a
        // deploy — a batch is allowed most of a day — and the line it carries is this build's own
        // data, written by the classification that found the candidate. A build that changed the
        // reply shape cannot parse it, and the pass must not read that as a finding it declined:
        // the record has to stay owed, because marking it would settle a message against a reading
        // no build ever got to make, under the cut-off it was selected with.
        //
        // A three-field line is what every build before the subject field wrote.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "commitment|m1|plain",
            BatchOutcome.Replied(
                ModelOutcome.Answered("none|none|none|A claim nothing can place.", Reply.SAMPLE_USAGE),
            ),
        )

        assertEquals(emptyList(), restated.proposals)
        assertEquals(
            Restatement.Unused.FOREIGN,
            restated.unused,
            "A line this build cannot parse was reported as an ordinary lost finding, so the pass " +
                "would mark the record and the finding would be gone for good.",
        )
    }

    @Test
    fun `a predicate outside the vocabulary is its own figure, not a parser that drifted`(): Unit = runBlocking {
        // **The routine failure of this stage, and the one that must not read as alarming.** The
        // instruction asks for the claim in the corpus's own language, which invites the relation in
        // that language too — so a predicate outside the closed vocabulary is expected on every
        // sweep over a corpus that is not English. Counted with the lines that would not parse, it
        // would have a deployment chasing a parser that is working as designed.
        //
        // **And it is the instruction not being followed rather than a list too narrow**, the
        // refusal word being what that instruction asks for where no entry fits — which is why it
        // does not share a figure with any of the cases below either.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "attribute|m1|plain|$AUTHOR_NAME",
            BatchOutcome.Replied(ModelOutcome.Answered("none|employed by|none|Acme Ltd", Reply.SAMPLE_USAGE)),
        )

        assertEquals(emptyList(), restated.proposals, "A predicate nobody can query was stored rather than refused.")
        assertEquals(Restatement.Unused.OUTSIDE, restated.unused)
    }

    @Test
    fun `the refusal word is told from a word outside the vocabulary`(): Unit = runBlocking {
        // **The answer the instruction asks for where no entry fits**, so it is the figure that
        // bears on the list: the classification called this an attribute and the restatement found
        // no entry for it, which is the two stages disagreeing. Not a measure of the width — both
        // stages are given the same list, so a classification that was wrong lands here too.
        // Counted with the
        // case above — the same judgement reached by stretching an entry instead — a sweep could
        // not tell a narrow list from an instruction going unfollowed.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "attribute|m1|plain|$AUTHOR_NAME",
            BatchOutcome.Replied(ModelOutcome.Answered("none|none|none|Acme Ltd", Reply.SAMPLE_USAGE)),
        )

        assertEquals(emptyList(), restated.proposals, "A statement cannot be keyed by a predicate nobody gave.")
        assertEquals(Restatement.Unused.UNNAMED, restated.unused)
    }

    @Test
    fun `a blank predicate field is drift and not a refusal`(): Unit = runBlocking {
        // The third answer the parser returns alongside the two above, and the one that does not
        // belong with them: no instruction asks for an empty field, so a model leaving one is
        // this build's prompt and parser out of step. It belongs with the lines that would not
        // parse, which is the figure a deployment acts on.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "attribute|m1|plain|$AUTHOR_NAME",
            BatchOutcome.Replied(ModelOutcome.Answered("none||none|Acme Ltd", Reply.SAMPLE_USAGE)),
        )

        assertEquals(emptyList(), restated.proposals, "A statement cannot be keyed by an empty predicate.")
        assertEquals(
            Restatement.Unused.UNEXTRACTABLE,
            restated.unused,
            "A blank field read as a rule declining the line, so the one figure that says this " +
                "build has something to fix stayed silent about it.",
        )
    }

    @Test
    fun `a stored line whose subject its window no longer bears out is refused`(): Unit = runBlocking {
        // **The rule that reaches `REFUSED` here and can change its answer**, at this stage's own
        // seam — `InferrerTest` drives it through the pass, this drives it through the reader.
        // A window is re-derived on collection, so a context that has grown since the submission can
        // withdraw the label the subject field was matched against — and that costs the finding. It
        // is decided before the reply is looked at, which is why a readable answer below is still
        // refused. Without a case here the branch could go dead and every neighbouring assertion of
        // `refused == 0` would go on passing.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "attribute|m1|plain|Somebody The Window Does Not Label",
            BatchOutcome.Replied(ModelOutcome.Answered("none|works at|none|Acme Ltd", Reply.SAMPLE_USAGE)),
        )

        assertEquals(emptyList(), restated.proposals, "An attribute was stored about a party its window does not name.")
        assertEquals(
            Restatement.Unused.REFUSED,
            restated.unused,
            "A withdrawn label read as something other than a rule declining the line, so the " +
                "figure that is meant to stay routine would move or stay silent wrongly.",
        )
    }

    @Test
    fun `a line that will not parse is told from the answers that are a word`(): Unit = runBlocking {
        // The other side of the four cases above. A line with the wrong field count says a prompt
        // and a parser have drifted apart, which is this build's to fix — so it cannot share a
        // figure with an answer the model was asked for. It does share one with the blank field
        // above, deliberately: an empty field is the same drift reached a different way.
        val extractor = Extractor(FakeModelProvider())

        val restated = extractor.restated(
            window(),
            "commitment|m1|plain|none",
            BatchOutcome.Replied(
                ModelOutcome.Answered("not the three fields this expects", Reply.SAMPLE_USAGE),
            ),
        )

        assertEquals(emptyList(), restated.proposals)
        assertEquals(Restatement.Unused.UNEXTRACTABLE, restated.unused)
    }

    @Test
    fun `a narrated refusal is nothing found, not a parser that drifted`(): Unit = runBlocking {
        // Measured live: asked about a window whose sender the corpus names nothing, the model
        // explains the rule it is applying and then answers `none` on a line of its own. Counting
        // the sentence as unreadable puts a rule working correctly into the figure reserved for
        // this build being wrong, which inverts the division `lost` and `refused` exist to hold.
        val model = FakeModelProvider(
            listOf(Reply.of("The sender of m1 has no label, so no attribute can be recorded.\n\nnone")),
        )

        val found = assertIs<Candidacy.Found>(classify(model))

        assertEquals(emptyList(), found.asks, "A sentence was restated as though it were a finding.")
        assertEquals(0, found.lost, "A narrated refusal was counted as a prompt and a parser out of step.")
        assertEquals(0, found.refused)
        assertEquals(1, model.callCount, "The prose bought a restatement.")
    }

    @Test
    fun `a reply of prose alone settles its record, counted as lost`(): Unit = runBlocking {
        // The companion to the case above, and where the two part. A reply carrying neither a
        // finding nor the refusal is one nobody can act on, and it is deterministic: asking again
        // buys the same prose at a call each, and held owed it would sit at the head of every
        // sweep's selection for ever. So it is settled, and counted as lost — the figure for a
        // reply this build could not use, which is the only thing standing between a prose-only
        // answer and a window that genuinely held nothing.
        val model = FakeModelProvider(listOf(Reply.of("I am not able to classify this message.")))

        val found = assertIs<Candidacy.Found>(
            classify(model),
            "A reply nobody can act on left its record owed, to be re-selected and re-asked on " +
                "every sweep for the rest of the deployment.",
        )

        assertEquals(emptyList(), found.asks, "A sentence was restated as though it were a finding.")
        assertEquals(
            1,
            found.lost,
            "Prose with no refusal beside it settled the record for free, so a reply the build " +
                "cannot use reads exactly like a window that held no finding.",
        )
        assertEquals(1, model.callCount, "The prose bought a restatement.")
    }

    @Test
    fun `a repeated line is counted and still asked about`(): Unit = runBlocking {
        // Measured live: one reply carried the same event line six times. Every copy is asked about
        // all the same, because a classification line carries no claim — a kind, a message, a hedge
        // and a subject — so two genuinely different findings of one kind on one message produce
        // the same line, and dropping the second would lose a finding the derived layer is built to
        // keep. What a repeat costs is a duplicate restatement the content-keyed write folds away:
        // the spend is real, and the figure is what makes it visible rather than what avoids it.
        val line = "event|m1|plain|none"
        val model = FakeModelProvider(
            listOf(Reply.of(listOf(line, line, line).joinToString("\n")), Reply.of("none|none|none|The call happens.")),
        )

        val read = readWindow(model)

        assertEquals(
            3,
            read.proposals.size,
            "A copy was dropped before the ask, which on a line that was two real findings of one " +
                "kind loses the second of them outright.",
        )
        assertEquals(2, read.repeated, "The copies were asked about and no figure says the spend was doubled.")
        assertEquals(0, read.lost, "The model repeating itself was reported as a parser that drifted.")
        assertEquals(
            4,
            model.callCount,
            "One classification and a restatement per copy. The figure accounts for spend really " +
                "made, so counting it without the calls behind it would mean the opposite thing.",
        )
    }

    @Test
    fun `a repeat is told from a second finding on the same message`(): Unit = runBlocking {
        // The bound on the case above: one message really can state two things, and a window that
        // does must still be asked twice. Deduplicating by line rather than by message is what
        // keeps the two apart — these differ in their kind, so neither is the other's copy.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m1|plain|none\nevent|m1|plain|none"),
                Reply.of("none|none|none|The quote gets sent."),
                Reply.of("none|none|none|The call happens."),
            ),
        )

        val read = readWindow(model)

        assertEquals(2, read.proposals.size, "Two findings on one message were folded into one.")
        assertEquals(0, read.repeated)
    }

    @Test
    fun `a subject offered for a commitment is ignored rather than refused`(): Unit = runBlocking {
        // A commitment asserts nothing about a subject, so the field has no meaning for it and a
        // label offered there is surplus — as a predicate is. Refusing the whole reading over it
        // would lose a promise to a formatting slip.
        val model = FakeModelProvider(
            listOf(Reply.of("commitment|m1|plain|$OTHER_NAME"), Reply.of("none|none|none|The quote gets sent.")),
        )

        val proposal = readWindow(model).proposals.single()

        assertEquals("The quote gets sent.", proposal.claim)
    }

    @Test
    fun `every line put in front of the model says who sent it, as a label and not an identifier`(): Unit =
        runBlocking {
            // **Without this no claim can name its party.** The restatement instruction requires a
            // claim understandable with nothing else in front of the reader and forbids a pronoun
            // standing for a person named elsewhere — unsatisfiable where the only agents available
            // are "the sender" and a bare first-person verb.
            //
            // A label and not the author's record identifier, because a model shown identifiers
            // writes identifiers into claims, and minting identity belongs to one stage only.
            val model = FakeModelProvider(listOf(Reply.of("none")))

            classify(model)

            val prompt = model.requests.single().prompt
            assertTrue(AUTHOR_NAME in prompt, "The message's own author is not in front of the model: $prompt")
            assertTrue(OTHER_NAME in prompt, "A context line's author is not named, so a party in it is unnameable.")
            assertFalse(
                author("m1").nativeId in prompt,
                "The author's record identifier is in the prompt. A model shown one echoes it into " +
                    "a claim, which puts identity assignment in the model: $prompt",
            )
        }

    @Test
    fun `a rendered line has one field per part, with the text last`(): Unit = runBlocking {
        // The format the model is told to read, asserted as the model sees it. The text is last so
        // that a delimiter inside a message cannot shift a field — and the participant label goes
        // third rather than last for the same reason, which only holds while a label cannot carry
        // the delimiter either.
        val model = FakeModelProvider(listOf(Reply.of("none")))

        classify(model)

        // The first line is the identifier of the message to read, then a blank, then the messages.
        val lines = model.requests.single().prompt.lines().drop(2)
        assertEquals(
            listOf(
                listOf("m1", FIRST.toString(), AUTHOR_NAME, "we should look at the quote"),
                listOf("m2", SECOND.toString(), OTHER_NAME, "I might get the revised one over by Friday"),
            ),
            lines.map { it.split("|") },
            "The rendered window is not four fields per line with the text last.",
        )
    }

    /**
     * The window a restatement reads comes before the finding that selects it, and not in the prompt.
     *
     * **The order is what makes the caching possible, and nothing else in this suite would notice
     * it moving.** Every finding of one window is restated by its own request, so the window is by
     * far the largest thing those requests have in common — and caching matches a prefix, so a
     * shared body only pays while it precedes what varies. Put the kind and the identifier first
     * and the requests share only the instruction: no error, no warning, and the window paid for in
     * full once per finding.
     *
     * Asserted on the shared region rather than on where a breakpoint sits, which is the adapter's
     * to place. What this fixes is that the window is the part held out as shared and the
     * discriminator is not.
     */
    @Test
    fun `the window a restatement reads comes before the finding it selects`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(Reply.of("commitment|m1|plain|none")))

        val found = assertIs<Candidacy.Found>(classify(model))
        val request = found.asks.single().request

        val shared = assertNotNull(
            request.shared,
            "The restatement holds nothing out as shared, so every finding of one window pays for " +
                "that window again.",
        )
        assertTrue(
            "we should look at the quote" in shared,
            "The conversation is what repeats across a window's findings, so it is what belongs in " +
                "the shared region: $shared",
        )
        assertEquals(
            "commitment|m1",
            request.prompt,
            "The prompt is the one thing that distinguishes this request from its siblings — the " +
                "kind and the message to restate, and nothing else.",
        )
        assertFalse(
            "we should look at the quote" in request.prompt,
            "The window is in the prompt as well as the shared region. Sent twice it is billed " +
                "twice, and the prefix two siblings share still ends before either of them.",
        )
    }

    @Test
    fun `the message to read is named to the model, before the conversation around it`(): Unit = runBlocking {
        // How the model knows which of the lines it may report from. Named out of band rather than
        // marked on its line. The restatement stage names it out of band too, at the other end:
        // there the conversation comes first so that sibling requests share it as a prefix.
        val model = FakeModelProvider(listOf(Reply.of("none")))

        classify(model, window("m2"))

        assertEquals(
            "m2",
            model.requests.single().prompt.lines().first(),
            "The model is not told which message to read, so every line reads as reportable.",
        )
    }

    @Test
    fun `a line in a shape this cannot read is counted rather than guessed at`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(Reply.of("commitment|m1\nsomething|m1|plain\ncommitment|m1|perhaps")),
        )

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(
            3,
            extraction.lost,
            "A missing field, an unknown kind and an unknown hedge are three findings dropped, " +
                "and a pass reporting zero would say the window held nothing.",
        )
    }

    @Test
    fun `a restatement the model will not give loses the candidate and not the window`(): Unit = runBlocking {
        // The asymmetry with a refused classification, and it is deliberate. A refusal is
        // deterministic, so a window held open for one candidate the model will not restate would
        // be retried on every pass for ever. The window is done; the loss is counted.
        val model = FakeModelProvider(listOf(Reply.of("commitment|m1|plain|none"), Reply.refused()))

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(1, extraction.lost)
    }

    @Test
    fun `a claim with no readable date keeps the claim`(): Unit = runBlocking {
        // Most commitments are stated without a date, and a date this cannot parse is the same
        // case as one that was never given. Dropping the proposal over the detail would lose the
        // thing itself.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m1|plain|none"),
                Reply.of("sometime next quarter|none|none|The work gets finished."),
            ),
        )

        val proposal = readWindow(model).proposals.single()

        assertNull(proposal.dated)
        assertEquals("The work gets finished.", proposal.claim)
    }

    @Test
    fun `a date before a promise its own author owes is refused, and the claim kept`(): Unit = runBlocking {
        // Measured on the real archive: a due date eighteen seconds before the message making the
        // promise. Nothing is promised for a time already past by the party who is to do it, so this
        // is the one case the record can settle on its own — and it costs the date rather than the
        // claim, for the reason an unreadable date does.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:04:42Z|none|$OTHER_NAME|$OTHER_NAME will send the revised quote."),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertNull(proposal.dated, "a date before its own message was stored as though it were a reading")
        assertEquals(
            "$OTHER_NAME will send the revised quote.",
            proposal.claim,
            "the claim was lost with the date",
        )
        assertEquals(
            author("m2"),
            proposal.obliged,
            "a promise the message's own author made did not record them as the party it obliges, " +
                "which is the reading the refusal below is scoped to",
        )
        assertEquals(
            listOf(Restatement.DateRefusal.BEFORE_MESSAGE),
            read.datesRefused,
            "the refusal was not counted under the case the record itself settles, so the rate of " +
                "it is unreadable",
        )
        assertEquals(0, read.lost, "a refused date must not be booked as a finding lost")
    }

    @Test
    fun `an overdue obligation on another participant keeps the timing the message stated`(): Unit = runBlocking {
        // **The commonest shape somebody else's deadline arrives in**: it is mentioned once it has
        // been missed, so a time already behind is the ordinary reading there and not the record
        // contradicting itself. The refusal consults who is obliged precisely so that this material
        // keeps the timing the corpus states — without it the store holds no date for every
        // obligation on another party, which is the half of the material a due-ahead and due-behind
        // separation then cannot see at all.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:04:42Z|none|$AUTHOR_NAME|$AUTHOR_NAME owed the revised quote."),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertEquals(
            Instant.parse("2026-03-02T10:04:42Z"),
            proposal.dated,
            "an obligation stated about another party lost the deadline the message stated",
        )
        assertEquals(
            author("m1"),
            proposal.obliged,
            "the party the window labelled was not recorded, so nothing can tell this from a " +
                "promise the author made themselves",
        )
        assertEquals(
            emptyList(),
            read.datesRefused,
            "a deadline reported after it had been missed was counted as the record contradicting " +
                "itself, which is what that figure is read as meaning",
        )
    }

    @Test
    fun `a party no line labels records no party and keeps no overdue date`(): Unit = runBlocking {
        // **What the field cannot reach, and the conservative half of the trade.** A label exists
        // only for a participant the corpus names who wrote a line in this window, so an obligation
        // placed on anybody else carries none — and a past date on one of those is refused as the
        // author's own would be, that being the reading the record can contradict. So the figure
        // counting the refusal is a ceiling on the record contradicting itself rather than a measure
        // of it, and what bounds the difference is how often a window names the party at all.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:04:42Z|none|The Supplier|The supplier owed the revised quote."),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertNull(
            proposal.obliged,
            "a label no line carries resolved to a party the window never showed the model, which " +
                "is the identifier-minting the labels exist to prevent",
        )
        assertNull(proposal.dated, "an unplaceable party kept a timing nothing can check against the record")
        assertEquals(listOf(Restatement.DateRefusal.BEFORE_MESSAGE), read.datesRefused)
    }

    @Test
    fun `the obliged party is matched in the form the label rule withdrew a shared name in`(): Unit = runBlocking {
        // **The relation has to be the one uniqueness was tested under, not merely a looser one
        // that agrees on this corpus.** A label survives the withdrawal when no other author's
        // normalises to it, so matching through the same normalisation can answer at most one
        // party; a comparison that holds two surviving labels equal would answer whichever author
        // the window listed first, which is the obligation placed on the wrong participant. The
        // normalisation also collapses whitespace and case, which is why a label the model
        // re-spaced or recapitalised still names its party instead of losing it to nothing.
        //
        // **The third form is the one the key cannot answer on its own.** The label in the prompt
        // never carries it — a rendered label has been repaired — so what this covers is an answer
        // that is not one of those outputs: a space the model wrote itself, or took from a
        // message's text, which nothing repairs. The key trims by the Unicode predicate and then
        // collapses runs with a pattern, whose class is the ASCII six, so such a space survives
        // *inside* the name and keys to a string no stored label produces. Without the repair on
        // this side the direction is lost to a character nobody can see, and the form is here
        // rather than argued because an assertion nobody has watched fail is not known to work.
        listOf(
            AUTHOR_NAME.uppercase(),
            AUTHOR_NAME.replace(" ", "  "),
            AUTHOR_NAME.replace(" ", "\u00A0"),
        ).forEach { echoed ->
            val model = FakeModelProvider(
                listOf(
                    Reply.of("commitment|m2|plain|none"),
                    Reply.of("none|none|$echoed|$AUTHOR_NAME owes the revised quote."),
                ),
            )

            val proposal = readWindow(model, window("m2")).proposals.single()

            assertEquals(author("m1"), proposal.obliged, "`$echoed` named no party, so direction was lost")
        }
    }

    @Test
    fun `a commitment read from a message with no author refuses an overdue date`(): Unit = runBlocking {
        // **Every way of not knowing falls on the same side**, and this is the one that is not the
        // party: the corpus names no author for the message, so the labelled party the model gave
        // cannot be compared against one. Read as somebody else's obligation the date would stand,
        // which is the opposite of what the rule says for a direction the window cannot settle —
        // and a message with no author is an ordinary state of this corpus.
        val authorless = Window(
            conversation = CHAT,
            messages = listOf(
                message("m1", "we should look at the quote", FIRST),
                message("m2", "the revised one was due last week", SECOND, author = null),
            ),
            target = ExternalId(Source.TELEGRAM, "m2"),
            names = mapOf(author("m1") to AUTHOR_NAME),
        )
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:04:42Z|none|$AUTHOR_NAME|$AUTHOR_NAME owed the revised quote."),
            ),
        )

        val read = readWindow(model, authorless)

        val proposal = read.proposals.single()
        assertEquals(author("m1"), proposal.obliged, "the party the window labelled was not recorded")
        assertNull(
            proposal.dated,
            "a date before a message whose author is unknown was kept as another party's, so the " +
                "one case the comparison has nothing to stand on took the permissive branch",
        )
        assertEquals(listOf(Restatement.DateRefusal.BEFORE_MESSAGE), read.datesRefused)
    }

    @Test
    fun `a party offered for an event is ignored rather than refused`(): Unit = runBlocking {
        // An event obliges nobody, so the field has no meaning there — and giving up the whole
        // reading over a surplus label would lose an occasion to a formatting slip, which is the
        // rule a predicate offered for a commitment already follows.
        val model = FakeModelProvider(
            listOf(
                Reply.of("event|m2|plain|none"),
                Reply.of("2026-03-01T15:00:00Z|none|$AUTHOR_NAME|A review happened on 1 March."),
            ),
        )

        val proposal = readWindow(model, window("m2")).proposals.single()

        assertNull(proposal.obliged, "an event carried an obliged party, which nothing downstream reads")
        assertEquals(Instant.parse("2026-03-01T15:00:00Z"), proposal.dated, "the occasion lost its moment")
    }

    /**
     * A sentence binding two participants produces one commitment per party.
     *
     * **The second obligation had nowhere to go, and the seat was the reply's shape rather than the
     * store's.** A row carries one obliged party and the key already admits two claims from one
     * message, so what lost the second obligation was a stage that read one line and stopped. The
     * split is here and not a stage earlier because a classification line is a kind, a message, a
     * hedge and a subject: two commitment lines on one message are the same four fields and build
     * the identical request, so two candidates could never have been asked different questions.
     *
     * Both findings cite the message they were read from, which is what makes the second one
     * checkable rather than merely present.
     */
    @Test
    fun `a commitment binding two parties is one finding per party`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                    none|none|$OTHER_NAME|$OTHER_NAME will review the tasks.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            listOf(author("m1"), author("m2")),
            read.proposals.map { it.obliged },
            "one sentence binding two participants left one of them with no row at all, which is " +
                "an obligation the corpus states and nothing can answer about",
        )
        assertEquals(
            listOf("$AUTHOR_NAME will prepare the tasks.", "$OTHER_NAME will review the tasks."),
            read.proposals.map { it.claim },
            "each line's claim has to be its own party's obligation, or the two rows are two " +
                "wordings of one thing under two parties",
        )
        assertEquals(
            setOf(SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, "m2"))),
            read.proposals.map { it.evidence.span }.toSet(),
            "a finding whose evidence does not name the message it was read from cannot be checked",
        )
        assertEquals(0, read.lost, "nothing was lost, and one call bought both findings")
    }

    /**
     * One participant's undertakings are one finding however many lines the model writes.
     *
     * **The excess an instruction to divide something invites**, and the half of this change that
     * can make the corpus worse rather than better: told to write a line per obliged party, the
     * cheapest way to obey on a sentence listing two things one person will do is to write two
     * lines. Both would oblige that person, and two rows for one party are two readings of one
     * obligation with nothing in the store to tell them apart — the shape the collapse exists to
     * settle and not one to manufacture here.
     *
     * Held by the party key rather than by the prose, because prose is what the model may disobey.
     */
    @Test
    fun `a line naming a party another line already obliged adds nothing`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will review the tasks.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertEquals(author("m1"), proposal.obliged)
        assertEquals("$AUTHOR_NAME will prepare the tasks.", proposal.claim, "the opening line is the answer")
        assertEquals(
            1,
            read.repeatedLines,
            "the dropped line was not counted, so the one excess this change can introduce is " +
                "invisible to every figure a sweep reports",
        )
    }

    /**
     * Two lines saying the same thing in the same words are one finding, whoever each obliges.
     *
     * **The half the party rule cannot see, and the store is where it bites.** A finding is keyed
     * by its conversation together with its claim, so two rows worded alike are one row there
     * however they differ in party — and both readings carry the one message's instant, so the
     * write takes the later line's party and the opening line's obligation is lost under a figure
     * that reads as an ordinary better reading arriving. Refused here, where it is visible and the
     * opening line still wins.
     *
     * Only the prompt stands between the model and this, and a prompt is a request: the rule that
     * each line's claim is its own party's obligation is exactly the one a model disobeys by
     * restating both obligations in both claims.
     */
    @Test
    fun `two lines worded alike are one finding, whichever parties they name`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    none|none|$AUTHOR_NAME|The tasks get prepared and reviewed.
                    none|none|$OTHER_NAME|The tasks get prepared and reviewed.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertEquals(
            author("m1"),
            proposal.obliged,
            "the store keys these two as one row, so the second would have taken the first's " +
                "party rather than standing beside it",
        )
        assertEquals(1, read.repeatedLines)
    }

    /**
     * A remark after the answer adds nothing, which is what the opening-line rail used to buy
     * outright.
     *
     * **The case the fixed-line rail was written for, now that the lines after the first are
     * read.** A model narrating around its answer is the ordinary failure of this format and is
     * measured on the classification prompt, so it is not hypothetical here; what carries the rail
     * instead is that prose holds no delimiter and so parses into nothing. That is an assumption
     * until something fires on it.
     */
    @Test
    fun `a remark after the answer is not a second finding`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                    I have restated the commitment above as asked.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            listOf("$AUTHOR_NAME will prepare the tasks."),
            read.proposals.map { it.claim },
            "a closing remark was read as a finding",
        )
        assertEquals(0, read.repeatedLines, "a line that did not parse is not a line that repeated")
    }

    /**
     * Two obligations neither of which any label could place are one reading, not two rows.
     *
     * The sentinel is a party like any other to the key, and that is the conservative half of the
     * trade: two rows obliging nobody carry nothing to tell them apart, so what a second one buys
     * is a duplicate the store cannot key away. It gives up the rarer case of a genuinely two-party
     * sentence neither of whose parties wrote in the conversation, which is the direction a reader
     * recovers from — the evidence stays in the corpus either way.
     */
    @Test
    fun `two obligations the window cannot place are one reading`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    none|none|none|The supplier prepares the tasks.
                    none|none|none|The reviewer checks the tasks.
                    """.trimIndent(),
                ),
            ),
        )

        val proposal = readWindow(model, window("m2")).proposals.single()

        assertNull(proposal.obliged)
        assertEquals("The supplier prepares the tasks.", proposal.claim)
    }

    /**
     * An event keeps the single reading it has always had, whatever follows the opening line.
     *
     * **The rail that stops the split reaching the two kinds that oblige nobody.** A model adding a
     * closing remark after its answer is the ordinary failure of this format, and the opening line
     * is fixed rather than taking whichever one parses — so a second line that happens to parse
     * must not become a second occasion. There is no party to tell two of them apart with.
     */
    @Test
    fun `an event is one finding however many lines come back`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("event|m2|plain|none"),
                Reply.of(
                    """
                    2026-03-01T15:00:00Z|none|none|A review happened on 1 March.
                    2026-03-01T16:00:00Z|none|none|A second review happened on 1 March.
                    """.trimIndent(),
                ),
            ),
        )

        val proposal = readWindow(model, window("m2")).proposals.single()

        assertEquals("A review happened on 1 March.", proposal.claim)
        assertEquals(Instant.parse("2026-03-01T15:00:00Z"), proposal.dated)
    }

    /**
     * A reply whose opening line will not parse is lost, whatever parses after it.
     *
     * **The opening line still decides whether there is a reading at all**, which is what keeps the
     * figures meaning what they meant: the lines after it exist to add a party, so a reply that
     * opens unreadably is this build's prompt and parser out of step exactly as it was before a
     * candidate could come back as more than one finding. Reading past it would make the loss
     * depend on whether a later line happened to parse.
     */
    @Test
    fun `a reply whose opening line will not parse is lost whatever follows`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(Reply.of("commitment|m2|plain|none")))
        val extractor = Extractor(model)
        val found = assertIs<Candidacy.Found>(classify(model, window("m2"), extractor))

        val restated = extractor.restated(
            window("m2"),
            found.asks.single().line,
            replied(
                """
                Here is the restatement you asked for.
                none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), restated.proposals)
        assertEquals(
            Restatement.Unused.UNEXTRACTABLE,
            restated.unused,
            "the loss was booked against a later line rather than the one the format asks for",
        )
    }

    /**
     * Each party's line answers the date rule for the party that line obliges.
     *
     * **The asymmetry is per line now, because a reply can hold both sides of it.** A promise the
     * sender makes themselves is never due before its own message, while a deadline the sender
     * states about another participant is usually mentioned once it has been missed — so one line
     * of one reply is refused and the next is kept, and a refusal read once for the reply would
     * either discard the second obligation's timing or admit a date the record contradicts.
     *
     * The figure is counted per date and only beside the finding it was taken off, which is the
     * unit a sweep reads it as a rate in.
     */
    @Test
    fun `a refused date on one party's line leaves another party's date standing`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    2026-03-02T10:04:00Z|none|$OTHER_NAME|$OTHER_NAME owed the tasks.
                    2026-03-02T09:00:00Z|none|$AUTHOR_NAME|$AUTHOR_NAME owed the review.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(listOf(author("m2"), author("m1")), read.proposals.map { it.obliged })
        assertNull(
            read.proposals.first().dated,
            "a promise its own sender owes cannot be due before the message making it",
        )
        assertEquals(
            Instant.parse("2026-03-02T09:00:00Z"),
            read.proposals.last().dated,
            "a deadline another participant owes is ordinarily already behind, so it stands",
        )
        assertEquals(
            listOf(Restatement.DateRefusal.BEFORE_MESSAGE),
            read.datesRefused,
            "the refusal is counted once, against the one line it was taken off",
        )
    }

    /**
     * The answer format's own header is refused wherever the model echoes it back.
     *
     * **Measured: asked for a line per obliged party, the model writes the header above its answer
     * on a sixth of replies.** That line carries the field count, so nothing about its shape tells
     * it from a finding — the claim field reads the word claim and the party field resolves to
     * nobody — and what would be stored is a commitment claiming nothing.
     *
     * **It is the split that makes it worse than untidy, which is why the rail arrives with it.**
     * Read as the one answer a reply used to hold it cost a single garbage row. Read as the opening
     * of several it obliges nobody, and the party rule then drops the real obligation that also
     * obliges nobody — so the echo displaces a finding instead of merely adding one. Both halves
     * are asserted here: the garbage row is absent, and the two real obligations both survive.
     */
    @Test
    fun `the answer format's own header is not read as a finding`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    ${Extractor.REWRITE_FORMAT}
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                    none|none|none|The supplier reviews the tasks.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            listOf("$AUTHOR_NAME will prepare the tasks.", "The supplier reviews the tasks."),
            read.proposals.map { it.claim },
            "the echoed header obliged nobody, so it was read as a finding and the real " +
                "obligation that obliges nobody was dropped as its repeat",
        )
        assertEquals(listOf(author("m1"), null), read.proposals.map { it.obliged })
    }

    /**
     * The header is refused in the spacing a model writes, not only in the spelling it was shown.
     *
     * **The near-miss is worse than the miss, which is why this is a case of its own.** The
     * spacing is the model's own and nothing repairs it, so an echo with the delimiters spaced out
     * reads identically to a person while an equality against the header misses it entirely — and
     * it still carries the field count, so it parses, obliges nobody, and displaces the real
     * unplaceable obligation. [Window.party] keys both sides through a normalisation for exactly
     * this reason, four lines from here.
     */
    @Test
    fun `an echoed header is refused however the model spaced it`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of(
                    """
                    date | predicate | party | claim
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME will prepare the tasks.
                    none|none|none|The supplier reviews the tasks.
                    """.trimIndent(),
                ),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            listOf("$AUTHOR_NAME will prepare the tasks.", "The supplier reviews the tasks."),
            read.proposals.map { it.claim },
            "a spaced header obliged nobody, so it was read as a finding and the real obligation " +
                "that obliges nobody was dropped as its repeat",
        )
    }

    /**
     * The header the instruction asks for is the one the parser refuses.
     *
     * One declaration, two readers — the prompt interpolates it and the parser compares against it
     * — so the line a model is shown and the line that is refused cannot drift apart. Asserted
     * through the instruction rather than against a literal written here, which would be a third
     * copy and the thing this exists to prevent.
     */
    @Test
    fun `the refused header is the format line the instruction states`() {
        val format = Extractor.REWRITE_SYSTEM.lines()
            .map(String::trim)
            .singleOrNull { it.contains(Extractor.FIELD) && it.none(Char::isWhitespace) }

        assertEquals(
            Extractor.REWRITE_FORMAT,
            format,
            "the parser refuses a line the instruction never asks for, so a model echoing the " +
                "header it was actually shown is still read as a finding",
        )
    }

    /**
     * The instruction asks for a line per obliged party, and closes the rule on the cases it must
     * not reach.
     *
     * **Quoted, because polarity is not derivable and because one disclaiming clause is not
     * enough.** A rule stated over the lines at large licenses a model to divide what was whole,
     * and the categorical sentence it replaced — an answer of exactly one line — forbids the
     * division the parser now reads. So both the opening condition and the two closes are pinned:
     * a single participant's undertakings, and the two kinds that oblige nobody. Each phrase is the
     * shortest that carries its half, so the wording can be improved without this going red while
     * deleting a half takes the assertion with it.
     *
     * **Whether the model then obeys it is not assertable here** — the provider is a fake, so no
     * case in this suite exercises the judgement. The figures are in the issue this was filed as,
     * measured against the live model on the message the card names.
     */
    @Test
    fun `the restatement instruction asks for a line per obliged party and closes the rule`() {
        // Collapsed for the reason the other prompt cases are: the instruction is hard wrapped, and
        // a phrase straddling a break is not one the model reads differently.
        val instruction = Extractor.REWRITE_SYSTEM.replace(Regex("\\s+"), " ")

        assertTrue(
            "one line for each party the thing obliges" in instruction,
            "The answer format asks for a fixed number of lines, so a sentence binding two " +
                "participants has nowhere to put the second obligation and the parser's split " +
                "reads a division the model was never asked to make: $instruction",
        )
        assertTrue(
            "each takes a line of its own" in instruction,
            "Nothing states the operative case — one participant to do one thing and another to " +
                "do another — so the rule reaches the model as a permission with no occasion.",
        )
        // The first close. Unconditioned, the rule above is a standing invitation to split a single
        // participant's two undertakings, which is two rows for one party and a duplicate the
        // store cannot key away.
        assertTrue(
            "Everything a single participant is to do is one party and one line" in instruction,
            "The split is offered without the case it must not reach, so the cheapest way to obey " +
                "is to divide a sentence that binds nobody but its sender.",
        )
        // The second close, and the two kinds the parser gives exactly one reading whatever arrives.
        assertTrue(
            "an event and an attribute oblige nobody, so each of those is one line" in instruction,
            "The two kinds that have no party to divide by are left inside the rule, so a model " +
                "may answer two occasions where the parser reads one and the second is paid for " +
                "and dropped.",
        )
        // And the claim field, which is where the division is actually written. The rule above
        // sends the model to the party field; this sentence is what stops one line's claim
        // restating both obligations, which would store the same two sentences under two parties.
        assertTrue(
            "and not what the other is" in instruction,
            "Nothing scopes a line's claim to its own party, so the sentence already asked for — " +
                "who owes what to whom — reaches a split reply as licence to name both.",
        )
        // The contradiction guard. The sentence this replaced is the one a tidy-up would restore,
        // and restored it would leave the parser reading a division the prompt forbids: the
        // behaviour would revert with every case above still green.
        assertFalse(
            "exactly one line" in instruction,
            "The instruction forbids an answer of more than one line, which is the answer the " +
                "parser now reads a second obligation out of.",
        )
    }

    @Test
    fun `a date at the message's own instant is refused, and counted apart`(): Unit = runBlocking {
        // **Read as no date stated, which gives up the promise to act immediately.** That promise
        // resolves to this value legitimately and nothing can tell it from the model copying the
        // one instant it was handed. Most of the dated findings measured arrived this way, so the
        // minority reading is given up to make the field trustworthy at all — and the claim prose
        // still says what was promised.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:05:00Z|none|none|The supplier sends the revised quote now."),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertNull(proposal.dated, "the message's own instant was stored as though it were a reading")
        assertEquals("The supplier sends the revised quote now.", proposal.claim, "the claim went with the date")
        assertEquals(
            listOf(Restatement.DateRefusal.SAME_INSTANT),
            read.datesRefused,
            "an echoed instant was counted as the record contradicting itself, which is the rare " +
                "case where this is the prevalent one — merged, neither figure says what happened",
        )
    }

    @Test
    fun `an event before the message that mentions it keeps its moment`(): Unit = runBlocking {
        // **The refusal that is a commitment's alone.** A promise cannot fall due before it was
        // made, but an occasion is routinely reported after it happened — which is what the
        // past-event read exists for, over a ninety-day window. Refusing both kinds alike would
        // throw away a correctly resolved moment on every retrospective mention, drop the event out
        // of that read into the undated one, and report it as the record contradicting itself.
        val model = FakeModelProvider(
            listOf(
                Reply.of("event|m2|plain|none"),
                Reply.of("2026-03-01T15:00:00Z|none|none|A review happened on 1 March."),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            Instant.parse("2026-03-01T15:00:00Z"),
            read.proposals.single().dated,
            "a past occasion lost the moment it happened at, so it is absent from the read that " +
                "exists to find it",
        )
        assertEquals(emptyList(), read.datesRefused)
    }

    @Test
    fun `an attribute's date field is not read, so no refusal is counted for one`(): Unit = runBlocking {
        // Nothing reads a date for an attribute — its validity opens where its evidence was said —
        // so a date offered there is refused against a message it was never going to be stored
        // beside. Counted, it would put a disobeyed instruction into a figure read as a rate over
        // the findings that carry dates, which is the merge the two figures exist to avoid.
        val model = FakeModelProvider(
            listOf(
                Reply.of("attribute|m2|plain|$OTHER_NAME"),
                Reply.of("2026-03-02T10:05:00Z|works at|none|Acme Ltd"),
            ),
        )

        val read = readWindow(model, window("m2"))

        val proposal = read.proposals.single()
        assertEquals("Acme Ltd", proposal.claim, "the attribute itself must survive a date it never uses")
        assertNull(proposal.dated, "an attribute carried a date, which nothing downstream reads")
        assertEquals(
            emptyList(),
            read.datesRefused,
            "a date on an attribute line was counted as a refusal, inflating a rate measured over " +
                "commitments and events",
        )
    }

    @Test
    fun `a date one second after the message is kept, so the refusal has no tolerance`(): Unit = runBlocking {
        // The discriminating half: a date a second out is a date the model worked out from
        // something, right or wrong, and a window either side of the message's instant would fold
        // that case into the echoed one and report a resolved date as a copied one.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m2|plain|none"),
                Reply.of("2026-03-02T10:05:01Z|none|none|The supplier sends the revised quote."),
            ),
        )

        val read = readWindow(model, window("m2"))

        assertEquals(
            SECOND.plusSeconds(1),
            read.proposals.single().dated,
            "a date after the message was refused, so the bound is wider than the record can justify",
        )
        assertEquals(emptyList(), read.datesRefused)
    }

    @Test
    fun `a commitment stating no date carries none, which is the ordinary case`(): Unit = runBlocking {
        // The sentinel on a commitment rather than on an attribute, where the field never applies
        // anyway. Two of the eight commitments on the first real sweep reached it, so the path
        // works and the instruction is what decides how often it is taken.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m1|plain|none"),
                Reply.of("none|none|none|The work gets finished."),
            ),
        )

        val read = readWindow(model)

        assertNull(read.proposals.single().dated)
        assertEquals(
            emptyList(),
            read.datesRefused,
            "an absent date was counted as a refused one, and the two must not be added: the first " +
                "is the instruction being followed",
        )
    }

    @Test
    fun `a restatement with no claim is not a proposal`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(Reply.of("commitment|m1|plain|none"), Reply.of("none|none|none|   ")))

        val extraction = readWindow(model)

        assertEquals(emptyList(), extraction.proposals)
        assertEquals(1, extraction.lost)
    }

    @Test
    fun `a claim carrying the delimiter is kept whole`(): Unit = runBlocking {
        // The free text is the last field for exactly this reason: a delimiter inside it cannot
        // shift a field, so a claim mentioning one is not truncated at it.
        val model = FakeModelProvider(
            listOf(
                Reply.of("commitment|m1|plain|none"),
                Reply.of("none|none|none|Either option a|b will be confirmed."),
            ),
        )

        val proposal = readWindow(model).proposals.single()

        assertEquals("Either option a|b will be confirmed.", proposal.claim)
    }

    @Test
    fun `the standing instructions are the same on every window`(): Unit = runBlocking {
        // The precondition for the cache breakpoint the adapter puts at the end of the system
        // prompt: a value that moves per call costs the saving rather than taking it.
        val model = FakeModelProvider(listOf(Reply.of("none")))
        val extractor = Extractor(model)

        classify(model, window(), extractor)
        val elsewhere = Window(
            conversation = CHAT,
            messages = listOf(message("m7", "another message entirely")),
            target = ExternalId(Source.TELEGRAM, "m7"),
        )
        classify(model, elsewhere, extractor)

        assertEquals(
            1,
            model.requests.map { it.system }.distinct().size,
            "The classification's standing instructions differ between two windows, so nothing " +
                "below the provider's cacheable prefix is ever read from the cache.",
        )
    }

    @Test
    fun `the version names the model and changes with it`(): Unit = runBlocking {
        val one = Extractor(FakeModelProvider(version = "a-model")).version()
        val other = Extractor(FakeModelProvider(version = "another-model")).version()

        assertTrue("a-model" in one, "The watermark does not name the model: $one")
        assertTrue(
            one != other,
            "Two models produce the same extractor version, so swapping the model re-derives " +
                "nothing and every stale finding stays as it was.",
        )
    }

    /**
     * The adjudication version names the stage, the model and the effort, and moves with the model.
     *
     * The stage name is what keeps it out of the cheap tier's space: the two tiers' answers share
     * one column, so a value the other tier could also produce would have a vector rule-out read as
     * a paid verdict.
     */
    @Test
    fun `the adjudication version names its stage and changes with the model`() {
        val one = Extractor(FakeModelProvider(version = "a-model")).adjudicationVersion()
        val other = Extractor(FakeModelProvider(version = "another-model")).adjudicationVersion()

        assertTrue(one.startsWith("${Extractor.ADJUDICATION}:"), "the stage names itself first: $one")
        assertTrue("a-model" in one, "a verdict does not name the model that gave it: $one")
        assertTrue(
            "${Extractor.ADJUDICATE_EFFORT}" in one,
            "the effort decides the answer, so it belongs in the version: $one",
        )
        assertTrue(
            one != other,
            "two models produce the same adjudication version, so swapping the model retires no " +
                "verdict and every pair keeps the answer the old one gave",
        )
    }

    /**
     * What one window came to: the proposals read out of it, and how many findings were lost.
     *
     * Local to the test because nothing in the build asks the question in this shape — the pass
     * reads the two stages a submission apart and reports per batch, not per window. Only these
     * two values, and no cost: what a call was billed stays on the outcome the pass totals it
     * from, so a figure counted up here would be a second answer to a question the pass has
     * already answered.
     */
    private data class Read(
        val proposals: List<Proposal>,
        val lost: Int,
        val refused: Int,
        val repeated: Int = 0,
        /**
         * Why each restatement that produced no proposal produced none.
         *
         * Beside [lost], which tallies them all alike: the cases that care which figure a loss is
         * booked under cannot assert it through a count that merges every reason.
         */
        val unused: List<Restatement.Unused> = emptyList(),
        /**
         * Why each date that was refused was refused, in the order they were read.
         *
         * Apart from [lost] and from [unused] both, because the finding survives a refused date:
         * counted among the losses it would say a reading went nowhere, and this is the one thing
         * here that travels beside a proposal. The cases rather than a count, because the whole
         * point of separating them is that they say different things.
         */
        val datesRefused: List<Restatement.DateRefusal> = emptyList(),
        /**
         * Lines the restatements wrote that bought no finding.
         *
         * Apart from [repeated], which is the classification's own: the two stages repeat
         * themselves for different reasons and a sweep reads them as one figure only after the
         * pass has added them up. A case here that merged them could not say which stage did it.
         */
        val repeatedLines: Int = 0,
    )

    /**
     * The classification of [window], asked and read.
     *
     * [Extractor.classification] and [Extractor.candidacy] are separate functions because the ask
     * is batched and the reply is read a sweep later; taken together synchronously they are what
     * one window's classification means, which is what these cases assert.
     */
    private suspend fun classify(
        model: FakeModelProvider,
        window: Window = window(),
        extractor: Extractor = Extractor(model),
    ): Candidacy {
        val request = assertNotNull(extractor.classification(window), "The window had no text to classify.")
        return extractor.candidacy(window, BatchOutcome.Replied(model.respond(request)))
    }

    /**
     * [window] read the way the pass reads it: classified, then every candidate restated.
     *
     * Composed here rather than in the extractor, which offers the two stages and not their
     * composition — the pass is the only caller of either, and it runs them across two submissions
     * a sweep apart. So this is the same two functions in the same order with the round trips taken
     * synchronously, and not a second route through them.
     *
     * Every reply is handed to [Extractor.restated] as [BatchOutcome.Replied], which is the only
     * shape a synchronous call can produce. The outcomes a collection reaches it with instead —
     * a request unanswered, or a reply the build has no reader for — cannot arise here, so the case
     * about the second of those calls `restated` directly with the outcome itself.
     *
     * Fails the test if the classification was not read, so the cases about that ask [classify] and
     * name the outcome they expect.
     */
    private suspend fun readWindow(model: FakeModelProvider, window: Window = window()): Read {
        val extractor = Extractor(model)
        val found = assertIs<Candidacy.Found>(
            classify(model, window, extractor),
            "The classification was not read, so there was nothing to restate.",
        )
        var lost = found.lost
        var repeatedLines = 0
        val datesRefused = mutableListOf<Restatement.DateRefusal>()
        val unused = mutableListOf<Restatement.Unused>()
        val proposals = buildList {
            found.asks.forEach { ask ->
                val restated = extractor.restated(window, ask.line, BatchOutcome.Replied(model.respond(ask.request)))
                datesRefused += restated.datesRefused
                repeatedLines += restated.repeated
                if (restated.proposals.isEmpty()) {
                    lost++
                    restated.unused?.let(unused::add)
                } else {
                    addAll(restated.proposals)
                }
            }
        }
        return Read(proposals, lost, found.refused, found.repeated, unused, datesRefused, repeatedLines)
    }

    // The third ask: two claims and a verdict, with none of the conversation either came from.

    @Test
    fun `a comparison is asked over the two claims and nothing else`(): Unit = runBlocking {
        val extractor = Extractor(FakeModelProvider())

        val request = extractor.adjudication(adjudicating())

        assertEquals(
            listOf("commitment", SURVIVOR_CLAIM, SUPERSEDED_CLAIM),
            request.prompt.lines(),
            "the kind and the two claims, one per line",
        )
        // The whole reason the stage is affordable: a claim was restated to stand on its own, so
        // the comparison needs nothing the two rows do not already carry. A window rendered here
        // would pay for the conversation again to decide one pair.
        assertFalse("we should look at the quote" in request.prompt, "no conversation reaches the comparison")
        assertEquals(Extractor.ADJUDICATE_EFFORT, request.effort)
    }

    /**
     * A claim is written by a model, so nothing guarantees it is one line — and a newline inside one
     * would make the second claim read as part of the first.
     */
    /**
     * **The relation goes in front of the model**, because without it two values are not a question.
     *
     * An attribute's claim is the value alone — the name of an employer, a rate — so two of them
     * read side by side say nothing about what relation they are values of, and whether two
     * wordings mean one thing depends on that entirely.
     */
    @Test
    fun `a comparison of two values names the relation they are values of`(): Unit = runBlocking {
        val extractor = Extractor(FakeModelProvider())
        val pair = ValuePair(
            subject = EntityId.mint(),
            predicate = Predicate.of("charges"),
            one = "five thousand a month",
            other = "5000 per month",
        )

        val request = extractor.adjudication(pair)

        assertEquals(
            listOf("${Extractor.ATTRIBUTE}${Extractor.FIELD}charges", pair.one, pair.other),
            request.prompt.lines(),
        )
    }

    /**
     * Both sides of the collapse share the instruction, which is not only tidiness: one submission
     * is one request shape, so a second system prompt here would mean findings and values could
     * never be asked in the same batch.
     */
    @Test
    fun `a finding pair and a value pair are one request shape`(): Unit = runBlocking {
        val extractor = Extractor(FakeModelProvider())
        val values = ValuePair(EntityId.mint(), Predicate.of("charges"), "a rate", "another wording")

        assertEquals(
            extractor.adjudication(adjudicating()).system,
            extractor.adjudication(values).system,
            "two shapes would be two submissions, and the stage would need a rule for which to buy",
        )
    }

    @Test
    fun `a claim spanning lines is flattened so the prompt keeps its shape`(): Unit = runBlocking {
        val extractor = Extractor(FakeModelProvider())

        val request = extractor.adjudication(adjudicating(survivor = "will send\nthe quote"))

        assertEquals(3, request.prompt.lines().size, "three lines, whatever the claims hold")
        assertTrue("will send the quote" in request.prompt)
    }

    @Test
    fun `a comparison answered with the agreed word is a merge`() {
        val extractor = Extractor(FakeModelProvider())

        assertIs<Verdict.Together>(extractor.adjudicated(replied(Extractor.ONE_FACT)))
    }

    @Test
    fun `a comparison answered with the other agreed word keeps the two apart`() {
        val extractor = Extractor(FakeModelProvider())

        assertIs<Verdict.Apart>(extractor.adjudicated(replied(Extractor.TWO_FACTS)))
    }

    /**
     * The reasoning a one-word format invites, which is harmless to ignore — unlike reading it as
     * the answer.
     */
    @Test
    fun `a verdict followed by a remark is still the verdict`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(replied("${Extractor.ONE_FACT}\nBoth describe one quote."))

        assertIs<Verdict.Together>(verdict)
    }

    /**
     * **A word outside the two offered is not guessed at.** Reading it either way would let a prompt
     * and a parser drift apart without anything saying so, and the pair is worth one call and a
     * later sweep.
     */
    @Test
    fun `a comparison answered with neither word decides nothing`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(replied("probably the same"))

        assertIs<Verdict.Unread>(verdict)
    }

    /**
     * An empty body is a reply nobody received, not a verdict — the same reading a blank
     * classification gets, and for the same reason: recording a decision on it would keep two
     * findings apart for good on the strength of nothing.
     */
    @Test
    fun `a comparison that came back empty decides nothing`() {
        val extractor = Extractor(FakeModelProvider())

        assertIs<Verdict.Unread>(extractor.adjudicated(replied("")))
    }

    /**
     * The one place the collapse's reading differs from the other two stages': a refusal is final
     * here and is recorded, because a pair held open would be submitted and refused on every idle
     * sweep for the rest of the deployment.
     */
    @Test
    fun `a refused comparison is final rather than retried`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(Batched.refused("a_category"))

        // Reported apart from an ordinary verdict of difference, which is what keeps a model that
        // has started refusing every comparison from reading like a corpus of distinct findings.
        val declined = assertIs<Verdict.Declined>(verdict)
        assertTrue("a_category" in declined.reason, "the category travels, so a log can say which rule declined")
    }

    @Test
    fun `a verdict of difference carries no reason, because nothing failed`() {
        val extractor = Extractor(FakeModelProvider())

        assertIs<Verdict.Apart>(
            extractor.adjudicated(replied(Extractor.TWO_FACTS)),
            "the paid answer the stage exists for is not a condition to report",
        )
    }

    @Test
    fun `a truncated comparison leaves the pair to be asked again`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(Batched.truncated("sam"))

        assertIs<Verdict.Unanswered>(verdict, "a turn cut short clears by being asked again")
    }

    @Test
    fun `a comparison the provider never answered leaves the pair to be asked again`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(BatchOutcome.Unfinished("the batch expired", Reply.SAMPLE_USAGE))

        // **Not [Verdict.Unread], which is the one a deploy has to clear.** An expired batch is the
        // ordinary end of a submission: every request of it comes back unanswered, so reporting it
        // as a shape this build cannot read would have each expiry read as a broken parser.
        assertIs<Verdict.Unanswered>(verdict)
    }

    @Test
    fun `a comparison answered in a shape this build cannot read is the one a deploy clears`() {
        val extractor = Extractor(FakeModelProvider())

        val verdict = extractor.adjudicated(BatchOutcome.Unreadable("a stop reason nothing names", Reply.SAMPLE_USAGE))

        assertIs<Verdict.Unread>(verdict, "asking again will not clear this one, so it is named apart")
    }

    /**
     * The comparison's prompt is deliberately absent from the record watermark, and covered by a
     * version of its own.
     *
     * The record watermark decides which *records* are owed a reading, and this stage reads none —
     * so folding its instruction in would re-extract the whole corpus inside the horizon because a
     * comparison was reworded. Absent from *both* is the defect: a behavioural change that
     * invalidates nothing, where the symptom is a prompt edit that appears to do nothing and the
     * cause is three files from where anyone would look.
     */
    @Test
    fun `the comparison's instruction is covered by its own version and not by the record watermark`() {
        val extractor = Extractor(FakeModelProvider())
        val version = extractor.version()
        val adjudication = extractor.adjudicationVersion()

        assertFalse(
            Extractor.ADJUDICATE_SYSTEM.hashCode().toString() in version,
            "the adjudication prompt does not reach the record watermark",
        )
        assertTrue(
            (Extractor.CLASSIFY_SYSTEM + Extractor.REWRITE_SYSTEM).hashCode().toString() in version,
            "while the two that decide what is read out of a window do",
        )
        assertTrue(
            Extractor.ADJUDICATE_SYSTEM.hashCode().toString() in adjudication,
            "and the comparison reaches a version of its own: $adjudication",
        )
        assertFalse(
            (Extractor.CLASSIFY_SYSTEM + Extractor.REWRITE_SYSTEM).hashCode().toString() in adjudication,
            "which the reading stages stay out of, or a reword there re-buys every verdict",
        )
    }

    /**
     * An instruction this class sends that reaches no version ships a behavioural change which
     * invalidates nothing — and this is the assertion that refuses it.
     *
     * **Enumerated off the class rather than named, so that an instruction added later is covered
     * by this case rather than by whoever happens to look.** What it keys on is the `_SYSTEM`
     * suffix, and that convention is therefore the real gate: an instruction named otherwise is
     * not caught here, which is the one thing a reviewer still has to do by eye.
     *
     * The three known names are asserted as a floor and not as the whole set, so a legitimately
     * added fourth fails on being uncovered — with the message that says so — rather than on a
     * sentinel that blames the test for reading the wrong place.
     *
     * Read as fields because the getters of an `internal` companion are name-mangled, and `:domain`
     * carries no reflection library to ask more politely with.
     */
    @Test
    fun `every standing instruction the extractor sends is covered by a version`() {
        val extractor = Extractor(FakeModelProvider())
        val versions = listOf(extractor.version(), extractor.adjudicationVersion())
        val instructions = Extractor::class.java.declaredFields
            .filter { it.name.endsWith("_SYSTEM") && it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(null) as String
            }
        assertTrue(
            instructions.keys.containsAll(setOf("CLASSIFY_SYSTEM", "REWRITE_SYSTEM", "ADJUDICATE_SYSTEM")),
            "the standing instructions are not where this test reads them from, so it is asserting " +
                "nothing: found ${instructions.keys}",
        )

        // Covered singly or hashed with a sibling, because what a version has to do is move when
        // the wording does — not name each instruction apart. Both orders, since the digest is
        // taken over a concatenation and a sibling may be written either side of this one.
        val uncovered = instructions.filter { (_, text) ->
            val digests = instructions.values.flatMap { other ->
                listOf((text + other).hashCode(), (other + text).hashCode())
            } + text.hashCode()
            versions.none { version -> digests.any { it.toString() in version } }
        }

        assertEquals(
            emptyMap(),
            uncovered,
            "an instruction this class sends reaches no version, so editing it ships a behavioural " +
                "change that invalidates nothing: ${uncovered.keys}",
        )
    }

    private fun replied(text: String) = BatchOutcome.Replied(ModelOutcome.Answered(text, Reply.SAMPLE_USAGE))

    /**
     * A candidate pair, as the collapse's store would hand one over.
     *
     * Both readings are in one conversation, because that is part of what a finding is keyed by and
     * a pair across two is not a pair. The later wording carries the later span, so which survives
     * is settled by the fixture rather than by the identifiers.
     */
    private fun adjudicating(
        survivor: String = SURVIVOR_CLAIM,
        superseded: String = SUPERSEDED_CLAIM,
    ) = FindingPair.of(FindingKind.COMMITMENT, finding(survivor, FIRST), finding(superseded, SECOND))

    private fun finding(claim: String, at: Instant) = Commitment(
        id = EntityId.mint(),
        claim = claim,
        conversation = CHAT,
        evidence = setOf(
            Evidence(
                span = SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, "m$at")),
                sourceTime = at,
            ),
        ),
        hedged = false,
    )

    /**
     * Two messages by two authors, one of them the one being read.
     *
     * [target] names which, because a window reads one message and only that one's findings may be
     * reported: the other is context, and a case about a finding placed on it says so by naming the
     * other identifier in its reply.
     */
    private fun window(target: String = "m1") = Window(
        conversation = CHAT,
        messages = listOf(
            message("m1", "we should look at the quote", FIRST),
            message("m2", "I might get the revised one over by Friday", SECOND),
        ),
        target = ExternalId(Source.TELEGRAM, target),
        names = mapOf(author("m1") to AUTHOR_NAME, author("m2") to OTHER_NAME),
    )

    private fun message(
        id: String,
        text: String?,
        at: Instant = FIRST,
        author: ExternalId? = author(id),
    ) = Message(
        externalId = ExternalId(Source.TELEGRAM, id),
        sourceTime = at,
        author = author,
        text = text,
    )

    private companion object {
        val CHAT = ExternalId(Source.TELEGRAM, "chat")
        val FIRST: Instant = Instant.parse("2026-03-02T10:00:00Z")
        val SECOND: Instant = Instant.parse("2026-03-02T10:05:00Z")

        /**
         * The two wordings of one obligation the comparison cases are built from.
         *
         * Different sentences for one fact, which is the pair the exact key cannot collapse and the
         * whole reason a third tier exists.
         */
        const val SURVIVOR_CLAIM = "The quote is to be sent on Friday."
        const val SUPERSEDED_CLAIM = "Someone undertook to send the quote by Friday."

        /**
         * What the corpus names the two authors of the fixture window.
         *
         * Invented strings. They are the labels the model is expected to echo — for a party in a
         * claim, and for the subject of an attribute — so a case using one is going through the
         * path production takes wherever a source offered a name.
         */
        const val AUTHOR_NAME = "The First Author"
        const val OTHER_NAME = "The Second Author"

        fun author(id: String) = ExternalId(Source.TELEGRAM, "author-$id")
    }
}
