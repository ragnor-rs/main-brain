package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two stages every generating shape shares.
 *
 * They are machinery rather than a shape's own work, which is why they are tested here once rather
 * than through each of the five. What a shape contributes is the instruction and the numbers; what
 * these do with them is the same every time, and a copy per shape would be five chances to label a
 * span differently and silently break grounding.
 */
class StagesTest {

    @Test
    fun `assembly puts the shape's instruction and the reply format in the system prompt`(): Unit = runBlocking {
        val progress = Assemble("Report where things stand.", 1..1, FakeGrouping()).apply(exchange())

        val assembled = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled)
        assertTrue(assembled.system.startsWith("Report where things stand."))
        assertTrue(assembled.system.contains("[VARIANT]"))
    }

    @Test
    fun `the system prompt is byte-stable across requests`(): Unit = runBlocking {
        // The precondition for the prefix being billed at read rates after the first call. Anything
        // per-request interpolated into it moves the breakpoint's contents and costs the saving.
        val stage = Assemble("Report where things stand.", 1..1, FakeGrouping())

        val one = assertIs<Progress.Carried>(stage.apply(exchange(text = "about one thing"))).exchange
        val two = assertIs<Progress.Carried>(stage.apply(exchange(text = "about another"))).exchange

        assertEquals(one.assembled?.system, two.assembled?.system)
    }

    @Test
    fun `the prompt carries the question, the subject and the material`(): Unit = runBlocking {
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange(text = "where did we leave off").copy(
                subject = Subject(focus = candidate("A Client", SubjectKind.CLIENT)),
                retrieved = listOf(span("42", "we agreed Friday")),
            ),
        )

        val prompt = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt
        assertTrue(prompt.contains("where did we leave off"))
        assertTrue(prompt.contains("A Client"))
        assertTrue(prompt.contains("we agreed Friday"))
    }

    @Test
    fun `every retrieved span is labelled with the number a claim has to cite`(): Unit = runBlocking {
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(retrieved = listOf(span("42", "first"), span("43", "second"))),
        )

        val assembled = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled)
        assertTrue(assembled.prompt.contains("[1]"), assembled.prompt)
        assertTrue(assembled.prompt.contains("[2]"), assembled.prompt)
        assertEquals(listOf(id("42"), id("43")), assembled.offer.spans)
    }

    @Test
    fun `the heading is in the zone the writer kept, not the instant's own`(): Unit = runBlocking {
        // The hour half, on an instant whose UTC and local days agree — so what this holds is
        // that the clock face moved to the writer's, and nothing about a date boundary. The case
        // below is the one where the day itself moves.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping(), zone = ELSEWHERE).apply(
            exchange().copy(
                retrieved = listOf(span("42", "tomorrow", Instant.parse("2026-10-01T14:30:00Z"))),
            ),
        )

        val prompt = prompt(progress)
        assertTrue(prompt.contains("2026-10-01 Thu 23:30 +09:00"), prompt)
        assertFalse(prompt.contains("14:30"), prompt)
    }

    @Test
    fun `a message written past local midnight is headed with the day its writer called it`(): Unit =
        runBlocking {
            // The failure the zone exists for. Where the zone runs ahead of UTC the two disagree
            // for the hours just after local midnight, and a "tomorrow" worked out against the UTC
            // date lands a day early — in the direction that makes a stale promise read as live.
            // Asserted on the date rather than the hour, because the day is what it is wrong by.
            val progress = Assemble("Instruction.", 1..1, FakeGrouping(), zone = ELSEWHERE).apply(
                exchange().copy(
                    retrieved = listOf(span("42", "tomorrow", Instant.parse("2026-10-01T22:30:00Z"))),
                ),
            )

            val prompt = prompt(progress)
            // The weekday moves with the date, which is the half that makes the arithmetic
            // doable: a block written past local midnight is Friday where the UTC day is still
            // Thursday, and counting from the wrong one is the error this heading exists to stop.
            assertTrue(prompt.contains("2026-10-02 Fri 07:30 +09:00"), prompt)
            assertFalse(prompt.contains("2026-10-01"), prompt)
            assertFalse(prompt.contains("Thu"), prompt)
        }

    @Test
    fun `given no zone the heading stays in UTC`(): Unit = runBlocking {
        // What a stack that sets nothing gets: the same instant in the same zone the heading has
        // always carried. The printed form differs — see Assemble.WRITTEN, which is why this
        // asserts the rendering rather than the instant's own `toString`.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(retrieved = listOf(span("42", "first"))),
        )

        assertTrue(prompt(progress).contains("2026-10-01 Thu 09:00 Z"), prompt(progress))
    }

    @Test
    fun `the weekday is the same three letters wherever this runs`(): Unit = runBlocking {
        // `ofPattern` without a locale binds the JVM default, so the heading would be written in
        // whatever language the host happens to be set to — moving the prompt under the model for
        // a reason nothing in this build chose, and in a Russian locale putting Cyrillic into
        // every block of an English instruction. Asserted as a shape rather than a literal, so
        // this holds for any date the fixture is later given.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(retrieved = listOf(span("42", "first"))),
        )

        val heading = Regex("""\[1] \d{4}-\d{2}-\d{2} ([A-Za-z]{3}) \d{2}:\d{2}""")
            .find(prompt(progress))
        assertNotNull(heading, "no heading of the expected shape: ${prompt(progress)}")
        assertTrue(
            heading.groupValues[1] in setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            "the weekday is not an ASCII English abbreviation, so the locale leaked: ${prompt(progress)}",
        )
    }

    @Test
    fun `the heading carries one time and not the instant beside it`(): Unit = runBlocking {
        // Asserted as an absence because that is the claim. Printing both would pass every case
        // above while giving the model two times in the heading to choose between.
        //
        // Scoped to the heading, which is all this stage writes. A derived block's *text* carries
        // times the material layer put there — a deadline, an event's moment, a closing turn — now
        // said in this same calendar and form, so the claim this holds is narrower than "one time
        // per block" and deliberately worded for what it covers. What the format tells the model
        // instead is which line a heading is.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping(), zone = ELSEWHERE).apply(
            exchange().copy(retrieved = listOf(span("42", "first"))),
        )

        assertFalse(prompt(progress).contains("2026-10-01T09:00:00Z"), prompt(progress))
    }

    @Test
    fun `no span identifier appears in the prompt`(): Unit = runBlocking {
        // Why the number is worth having: the long form is what a model mis-copies, and leaving it
        // beside the number would give it something longer to copy and a second form to choose
        // between. Asserted as an absence because that is the claim — a case checking only that the
        // number is present would pass with both forms printed.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(retrieved = listOf(span("42", "first"))),
        )

        val prompt = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt
        assertFalse(prompt.contains(Citation.render(id("42"))), prompt)
        assertFalse(prompt.contains("MESSAGE/TELEGRAM"), prompt)
    }

    @Test
    fun `one span retrieved twice is offered one number`(): Unit = runBlocking {
        // It is one piece of evidence — the gate compares against a set and has always treated it
        // that way. Offered two numbers the model cites both, and the reader is shown the same
        // finding twice. The shapes deduplicate before they get here; this is the rail under that,
        // because the numbering is what makes a duplicate visible rather than harmless.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            // The same body twice, which is what one record read by two of a shape's queries is. A
            // record and something inferred from it share an identifier and carry *different* bodies,
            // which is a different collision with a different answer — the case above.
            exchange().copy(retrieved = listOf(span("42", "one body"), span("42", "one body"))),
        )

        val assembled = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled)
        assertEquals(listOf(id("42")), assembled.offer.spans)
        assertFalse(assembled.prompt.contains("[2]"), assembled.prompt)
    }

    @Test
    fun `a record and what was inferred from it share a number and both texts`(): Unit = runBlocking {
        // A derived thing is addressed by the span it was read out of, so a commitment and the message
        // it came from arrive under one identifier carrying different texts. One identifier must carry
        // one text, and the numbering makes that unavoidable rather than merely tidy — so the two are
        // joined under one number. Dropping either would leave the model shown a turn with the
        // commitment read out of it missing, or the commitment with no turn to check it against,
        // beneath a number that claims to stand for both.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(
                retrieved = listOf(span("42", "we agreed Friday"), span("42", "Inferred commitment: deliver Friday")),
            ),
        )

        val assembled = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled)
        assertEquals(listOf(id("42")), assembled.offer.spans)
        assertTrue(assembled.prompt.contains("we agreed Friday"), assembled.prompt)
        assertTrue(assembled.prompt.contains("Inferred commitment: deliver Friday"), assembled.prompt)
    }

    @Test
    fun `a reply citing the numbers it was offered is grounded in the spans behind them`(): Unit = runBlocking {
        // The round trip, over the fake provider and through both stages: the prompt assigns the
        // numbers, the reply names them, and what comes back out is span identifiers. Asserted end
        // to end rather than on either half, because the two could agree about a form and still
        // disagree about which span a number meant.
        val model = FakeModelProvider(
            listOf(FakeModelProvider.Reply.of("[VARIANT]\nThe account.\n[CLAIM] they agreed << 1, 2")),
        )
        val retrieved = listOf(span("42", "first"), span("43", "second"))

        val assembly = assertIs<Progress.Carried>(
            Assemble("Instruction.", 1..1, FakeGrouping()).apply(exchange().copy(retrieved = retrieved)),
        )
        val answer = settled(Generate(model).apply(assembly.exchange))

        val claim = assertIs<Answer.Composed>(answer).variants.single().claims.single()
        assertEquals(setOf(id("42"), id("43")), claim.cites)
        assertTrue(claim.unoffered.isEmpty())
    }

    @Test
    fun `the reply is read against the offer that travelled, not one rebuilt from what was retrieved`(): Unit =
        runBlocking {
            // **The assertion the two round-trip cases above cannot make.** They retrieve material in
            // the order it is numbered, so a `Generate` that threw the offer away and re-derived one
            // from `exchange.retrieved` would produce the same answer and pass them both. Here the
            // offer deliberately disagrees with that order, so citing 1 resolves to the second span if
            // the travelled offer was used and to the first if it was rebuilt.
            //
            // It matters because `Progress.Settled` lets a stage retrieve and settle together, so the
            // retrieved list a reply is read beside is not always the one the prompt was written from.
            val model = FakeModelProvider(
                listOf(FakeModelProvider.Reply.of("[VARIANT]\nThe account.\n[CLAIM] they agreed << 1")),
            )
            val exchange = exchange().copy(
                retrieved = listOf(span("42", "first"), span("43", "second")),
                assembled = Assembled(
                    system = "instruction",
                    prompt = "prompt",
                    offer = Offer(listOf(id("43"), id("42"))),
                ),
            )

            val answer = settled(Generate(model).apply(exchange))

            assertEquals(
                setOf(id("43")),
                assertIs<Answer.Composed>(answer).variants.single().claims.single().cites,
                "Citation 1 resolved to the first span retrieved rather than the first span offered, " +
                    "so the reply was read against a numbering the prompt never used.",
            )
        }

    @Test
    fun `a reply citing a number that was never offered keeps the answer and loses the claim`(): Unit = runBlocking {
        val model = FakeModelProvider(
            listOf(FakeModelProvider.Reply.of("[VARIANT]\nThe account.\n[CLAIM] they agreed << 9")),
        )

        val assembly = assertIs<Progress.Carried>(
            Assemble("Instruction.", 1..1, FakeGrouping())
                .apply(exchange().copy(retrieved = listOf(span("42", "first")))),
        )
        val answer = settled(Generate(model).apply(assembly.exchange))

        val variant = assertIs<Answer.Composed>(answer).variants.single()
        assertEquals("The account.", variant.prose)
        assertEquals(setOf(9), variant.claims.single().unoffered)
    }

    @Test
    fun `the losing subjects are named as readings nothing was retrieved for`(): Unit = runBlocking {
        // Without saying so the model answers about one of them from material belonging to the
        // subject, which is the wrong-subject failure that is invisible afterwards.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(alternatives = listOf(candidate("Another Client", SubjectKind.CLIENT))),
        )

        val prompt = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt
        assertTrue(prompt.contains("Another Client"))
        assertTrue(prompt.contains("nothing was retrieved for"))
    }

    @Test
    fun `a subject's context appears as what it is answered against`(): Unit = runBlocking {
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(
                subject = Subject(
                    focus = candidate("A Person", SubjectKind.CONTACT),
                    context = listOf(candidate("Their Client", SubjectKind.CLIENT)),
                ),
            ),
        )

        val prompt = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt
        assertTrue(prompt.contains("Subject: A Person"))
        assertTrue(prompt.contains("Within: Their Client"))
    }

    @Test
    fun `a body of work's context appears as the threads it is made of, not as what it sits in`(): Unit =
        runBlocking {
            // The relation runs the other way for this one subject, and the prompt is where that
            // becomes a statement about the material. Its label is one of its strands' own, so
            // *within* would say this subject sits inside a thread carrying its own name — and the
            // answer that invites is about that thread rather than about the work.
            val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
                exchange().copy(
                    subject = Subject(
                        focus = candidate("A Work — design", SubjectKind.BODY_OF_WORK),
                        context = listOf(
                            candidate("A Work — design", SubjectKind.CLIENT),
                            candidate("A Work — delivery", SubjectKind.CLIENT),
                        ),
                    ),
                ),
            )

            val prompt = requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt
            assertTrue(prompt.contains("Subject: A Work — design (BODY_OF_WORK)"), prompt)
            assertTrue(prompt.contains("Strand: A Work — design (CLIENT)"), prompt)
            assertTrue(prompt.contains("Strand: A Work — delivery (CLIENT)"), prompt)
            assertFalse(
                prompt.contains("Within:"),
                "A body of work was said to sit within something, and its own label is one of the " +
                    "things it was said to sit within. Prompt: $prompt",
            )
        }

    @Test
    fun `a shape that does not section is told in the system prompt not to write a heading`(): Unit = runBlocking {
        // The flag has to reach the format, not only the labels. A shape that never sections and is
        // nonetheless handed the permissive format gets no heading rule at all — which is how a
        // drafted reply comes back divided under headings nobody can send.
        val composing = Assemble("Instruction.", 1..1, FakeGrouping(), sections = false)
        val reporting = Assemble("Instruction.", 1..1, FakeGrouping())

        val draft = assertIs<Progress.Carried>(composing.apply(exchange())).exchange.assembled
        val report = assertIs<Progress.Carried>(reporting.apply(exchange())).exchange.assembled

        assertTrue(requireNotNull(draft).system.contains("No preamble, no code fences, no headings."), draft.system)
        assertTrue(!requireNotNull(report).system.contains("No preamble, no code fences, no headings."), report.system)
    }

    @Test
    fun `material spanning two bodies of work is labelled with each and asks for sections`(): Unit = runBlocking {
        // The label has to travel, because a span's identifier is qualified by the chat rather than
        // by the conversation: there is nothing in the material itself to group by, so a prompt
        // without this gets topic-first sections however it is asked.
        val progress = Assemble(
            "Instruction.",
            1..1,
            FakeGrouping(
                mapOf(
                    record("42") to GroupingKey.OfBodyOfWork("Northwind Foods"),
                    record("43") to GroupingKey.OfBodyOfWork("Kestrel Logistics"),
                ),
            ),
        ).apply(exchange().copy(retrieved = listOf(span("42", "first"), span("43", "second"))))

        val prompt = prompt(progress)
        assertTrue(prompt.contains("(Body of work: Northwind Foods)"))
        assertTrue(prompt.contains("(Body of work: Kestrel Logistics)"))
        assertTrue(prompt.contains("Section the answer by them"))
    }

    @Test
    fun `a person heads a section where no body of work is behind the material`(): Unit = runBlocking {
        // The second of the two admissible keys. Material from a one-to-one with somebody who works
        // across several bodies of work belongs to none of them, and a prompt that called it one
        // would be asserting a hierarchy the corpus does not state.
        val progress = Assemble(
            "Instruction.",
            1..1,
            FakeGrouping(
                mapOf(
                    record("42") to GroupingKey.OfBodyOfWork("Northwind Foods"),
                    record("43") to GroupingKey.OfPerson("A Person"),
                ),
            ),
        ).apply(exchange().copy(retrieved = listOf(span("42", "first"), span("43", "second"))))

        val prompt = prompt(progress)
        assertTrue(prompt.contains("(Person: A Person)"))
        assertTrue(!prompt.contains("(Body of work: A Person)"))
    }

    @Test
    fun `material all from one body of work is assembled as it always was`(): Unit = runBlocking {
        // A question about a single subject is unchanged, which is the acceptance half nothing else
        // can state: the label would be a word the answer has no second section to use it against,
        // and the sectioning instruction would be asking for one heading.
        val material = listOf(span("42", "first"), span("43", "second"))
        val one = GroupingKey.OfBodyOfWork("Northwind Foods")
        val sectioning = Assemble(
            "Instruction.",
            1..1,
            FakeGrouping(mapOf(record("42") to one, record("43") to one)),
        )

        val labelled = sectioning.apply(exchange().copy(retrieved = material))
        val plain = Assemble("Instruction.", 1..1, FakeGrouping()).apply(
            exchange().copy(retrieved = material),
        )

        assertEquals(prompt(plain), prompt(labelled))
    }

    @Test
    fun `a record nothing can place is not a second body of work`(): Unit = runBlocking {
        // Absence is ordinary rather than a failure, so it cannot be allowed to stand for a heading:
        // one body of work and some unplaceable material has no second section to write.
        val material = listOf(span("42", "first"), span("43", "second"))
        val progress = Assemble(
            "Instruction.",
            1..1,
            FakeGrouping(mapOf(record("42") to GroupingKey.OfBodyOfWork("Northwind Foods"))),
        ).apply(exchange().copy(retrieved = material))

        val prompt = prompt(progress)
        assertTrue(!prompt.contains("Body of work"))
        assertTrue(!prompt.contains("Section the answer by them"))
    }

    @Test
    fun `a shape that composes prose is neither sectioned nor read for sections`(): Unit = runBlocking {
        // A drafted reply divided under headings is not a worse answer but one nobody can send. The
        // read is skipped as well as the labels, because a draft reads widely on purpose and would
        // otherwise pay for a query whose answer it discards on every request.
        val grouping = FakeGrouping(
            mapOf(
                record("42") to GroupingKey.OfBodyOfWork("Northwind Foods"),
                record("43") to GroupingKey.OfBodyOfWork("Kestrel Logistics"),
            ),
        )

        val progress = Assemble("Instruction.", 1..1, grouping, sections = false).apply(
            exchange().copy(retrieved = listOf(span("42", "first"), span("43", "second"))),
        )

        assertTrue(!prompt(progress).contains("Body of work"))
        assertEquals(emptyList(), grouping.asked)
    }

    @Test
    fun `the sectioning instruction is asked in the prompt, never in the system block`(): Unit = runBlocking {
        // It is true of this request and false of the next, so interpolating it into the prefix
        // would move the cache breakpoint's contents on every call.
        val stage = Assemble(
            "Report where things stand.",
            1..1,
            FakeGrouping(
                mapOf(
                    record("42") to GroupingKey.OfBodyOfWork("Northwind Foods"),
                    record("43") to GroupingKey.OfBodyOfWork("Kestrel Logistics"),
                ),
            ),
        )

        val spanning = assertIs<Progress.Carried>(
            stage.apply(exchange().copy(retrieved = listOf(span("42", "first"), span("43", "second")))),
        ).exchange
        val single = assertIs<Progress.Carried>(stage.apply(exchange())).exchange

        assertTrue(requireNotNull(spanning.assembled).prompt.contains("Section the answer by them"))
        assertTrue(!requireNotNull(spanning.assembled).system.contains("Section the answer by them"))
        assertEquals(single.assembled?.system, spanning.assembled?.system)
    }

    @Test
    fun `the present moment is printed where a shape asked for it`(): Unit = runBlocking {
        // The operand a reply needs to say that a day it names is today. Printed in a heading's
        // form so there is one shape to parse, and with the weekday because that is the operand —
        // inferring it from a date is where an archive read went a day wrong.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping(), clock = ASKED).apply(exchange())

        assertTrue(prompt(progress).contains("Now: 2026-10-05 Mon 12:00 Z"), prompt(progress))
    }

    @Test
    fun `a shape that states nothing about the present is given no such line`(): Unit = runBlocking {
        // Opt-in, because a fact in front of a model with no instruction about what it is for is
        // the shape of thing that gets rendered into an answer. Asserted as an absence because
        // that is the claim.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping()).apply(exchange())

        assertFalse(prompt(progress).contains("Now:"), prompt(progress))
    }

    @Test
    fun `the present shares the calendar the headings and the standings are on`(): Unit = runBlocking {
        // **Every time in a prompt shares one convention, and the reason this is asserted on a
        // zone that is not UTC.** A heading is local because the arithmetic it exists for is a
        // relative word a person wrote. The present is compared against the standing `Material`
        // computed and the day counts printed beside it, and those are reckoned on that same
        // calendar — so a present said in UTC instead would sit a day behind those labels for the
        // hours where the two calendars part, and the instruction that reads this date as today
        // would then call a commitment the material marks as ahead due today.
        //
        // Asserted as both halves on one prompt: the heading and the present moved together.
        val progress = Assemble("Instruction.", 1..1, FakeGrouping(), zone = ELSEWHERE, clock = ASKED)
            .apply(
                exchange().copy(
                    retrieved = listOf(span("42", "first", Instant.parse("2026-10-01T14:30:00Z"))),
                ),
            )

        val prompt = prompt(progress)
        assertTrue(prompt.contains("Now: 2026-10-05 Mon 21:00 +09:00"), prompt)
        assertFalse(prompt.contains("Now: 2026-10-05 Mon 12:00 Z"), prompt)
        assertTrue(prompt.contains("[1] 2026-10-01 Thu 23:30 +09:00"), prompt)
    }

    @Test
    fun `across local midnight the present is the day the standings were reckoned on`(): Unit =
        runBlocking {
            // The hours the case above is about, on the instant where it actually bites: past
            // local midnight in a zone ahead of UTC, the local date is already tomorrow and so is
            // every `now past` / `today` / `ahead` label on the material, each computed against
            // that calendar's day. Printing the UTC date here is what would make a reply call an
            // item due today that its own material marks as ahead — so the date printed is the
            // correspondents' one, and the UTC date must not appear.
            val crossing = Clock.fixed(Instant.parse("2026-10-05T21:00:00Z"), ZoneOffset.UTC)
            val progress = Assemble("Instruction.", 1..1, FakeGrouping(), zone = ELSEWHERE, clock = crossing)
                .apply(exchange())

            val prompt = prompt(progress)
            assertTrue(prompt.contains("Now: 2026-10-06 Tue 06:00 +09:00"), prompt)
            assertFalse(prompt.contains("Now: 2026-10-05"), prompt)
        }

    @Test
    fun `the present moment is in the request, never in the system block`(): Unit = runBlocking {
        // It is true of this request and false of the next one, which is the sectioning
        // instruction's reasoning exactly: the breakpoint sits at the end of the system block, so
        // a per-request value interpolated there would move what the prefix holds on every call.
        // What that costs is not asserted here — it is a property of the provider's billing and of
        // how often a prefix repeats, measured in the pull requests that measured it.
        val earlier = Assemble("Instruction.", 1..1, FakeGrouping(), clock = ASKED)
        val later = Assemble(
            "Instruction.",
            1..1,
            FakeGrouping(),
            clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC),
        )

        val one = assertIs<Progress.Carried>(earlier.apply(exchange())).exchange.assembled
        val other = assertIs<Progress.Carried>(later.apply(exchange())).exchange.assembled

        assertEquals(one?.system, other?.system)
        assertTrue(requireNotNull(one).prompt != requireNotNull(other).prompt)
    }

    @Test
    fun `generation says it spends the model`() {
        // The runner reads this to place the short-circuit and the resume point. A stage that
        // spends and does not say so loses both.
        assertTrue(Generate(FakeModelProvider()).spendsModel)
    }

    @Test
    fun `an answered reply becomes a composed answer`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.of("[VARIANT]\nThe answer.")))

        val progress = Generate(model).apply(assembled())

        assertEquals(listOf("The answer."), assertIs<Answer.Composed>(settled(progress)).variants.map { it.prose })
    }

    @Test
    fun `a truncated reply is not an answer`(): Unit = runBlocking {
        // It reads like a finished one, which is why the outcome is sealed and the reason is read
        // before the text.
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.truncated("[VARIANT]\nHalf an ans")))

        assertIs<Answer.InsufficientEvidence>(settled(Generate(model).apply(assembled())))
    }

    @Test
    fun `a refusal is not an answer`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.refused("some_category")))

        assertIs<Answer.InsufficientEvidence>(settled(Generate(model).apply(assembled())))
    }

    @Test
    fun `a paused turn is not an answer`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.paused("[VARIANT]\npartial")))

        assertIs<Answer.InsufficientEvidence>(settled(Generate(model).apply(assembled())))
    }

    @Test
    fun `a reply in no readable format is not an answer`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.of("I am not going to use your markers.")))

        assertIs<Answer.InsufficientEvidence>(settled(Generate(model).apply(assembled())))
    }

    @Test
    fun `a reply nothing could read carries the reply out with the answer`(): Unit = runBlocking {
        // The answer is the one an empty corpus produces, deliberately — which is exactly why the
        // reply has to leave with it. Without it a request that paid for a reply the reader rejected
        // reports what one that never called the model reports, and the cause has to be guessed.
        val said = "I am not going to use your markers."
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.of(said)))

        val progress = Generate(model).apply(assembled())

        val unreadable = assertIs<Fault.Unreadable>(reported(progress))
        assertEquals(said, assertIs<ModelOutcome.Answered>(unreadable.outcome).text)
    }

    @Test
    fun `a truncation carries what it managed before it ran out of room`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.truncated("[VARIANT]\nHalf an ans")))

        val progress = Generate(model).apply(assembled())

        val unreadable = assertIs<Fault.Unreadable>(reported(progress))
        assertEquals("[VARIANT]\nHalf an ans", assertIs<ModelOutcome.Truncated>(unreadable.outcome).partial)
    }

    @Test
    fun `a refusal says it was refused rather than looking like an empty corpus`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.refused("some_category")))

        val progress = Generate(model).apply(assembled())

        val unreadable = assertIs<Fault.Unreadable>(reported(progress))
        assertEquals("some_category", assertIs<ModelOutcome.Refused>(unreadable.outcome).category)
    }

    @Test
    fun `a paused turn carries what it managed before it stopped`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.paused("[VARIANT]\npartial")))

        val progress = Generate(model).apply(assembled())

        val unreadable = assertIs<Fault.Unreadable>(reported(progress))
        assertEquals("[VARIANT]\npartial", assertIs<ModelOutcome.Paused>(unreadable.outcome).partial)
    }

    @Test
    fun `a reply that read as an answer reports nothing wrong with it`(): Unit = runBlocking {
        // The other half of the rule, and the one that would go unnoticed: a stage reporting a fault
        // on every reply would have the backend warning about every answer it gave.
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.of("[VARIANT]\nan answer")))

        assertNull(reported(Generate(model).apply(assembled())))
    }

    @Test
    fun `the call is sized to the shape's ceiling when the budget allows it`(): Unit = runBlocking {
        val model = FakeModelProvider()

        Generate(model, sizing(ceiling = 1_000)).apply(assembled().copy(budget = Budget(ceiling = 100_000)))

        assertEquals(1_000, model.requests.single().maxTokens)
    }

    @Test
    fun `the call is sized to what the request has left when that is smaller`(): Unit = runBlocking {
        // The shape's number is about the answer it wants; the budget's is about what the request
        // may still spend. Passing the larger of the two on would make the bound advisory.
        val model = FakeModelProvider()

        Generate(model, sizing(ceiling = 100_000)).apply(
            assembled().copy(budget = Budget(ceiling = 20_000).spending(usage(outputTokens = 12_000))),
        )

        assertEquals(8_000, model.requests.single().maxTokens)
    }

    @Test
    fun `what the call cost is charged to the request's budget`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.of("[VARIANT]\nx", usage(outputTokens = 300))))

        val progress = Generate(model).apply(assembled().copy(budget = Budget(ceiling = 1_000)))

        assertEquals(700, assertIs<Progress.Settled>(progress).exchange.budget.remaining)
    }

    @Test
    fun `a refusal is charged for too`(): Unit = runBlocking {
        // Billed for what it read. Dropping usage on the failure paths would under-report the cost
        // by exactly the calls nobody looks at.
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.refused(usage = usage(outputTokens = 50))))

        val progress = Generate(model).apply(assembled().copy(budget = Budget(ceiling = 1_000)))

        assertEquals(950, assertIs<Progress.Settled>(progress).exchange.budget.remaining)
    }

    @Test
    fun `a shape's effort and web access reach the request`(): Unit = runBlocking {
        val model = FakeModelProvider()

        Generate(model, sizing(effort = Effort.XHIGH), openWeb = true).apply(assembled())

        assertEquals(Effort.XHIGH, model.requests.single().effort)
        assertTrue(model.requests.single().openWeb)
    }

    @Test
    fun `the web search tool is off unless a shape asks for it`(): Unit = runBlocking {
        val model = FakeModelProvider()

        Generate(model).apply(assembled())

        assertEquals(false, model.requests.single().openWeb)
    }

    @Test
    fun `generation reached with nothing assembled is a defect in the stage list`(): Unit = runBlocking {
        // Not an answer: a shape whose stages are ordered wrongly has nothing to send, and
        // degrading would hide the ordering mistake behind an ordinary reply.
        assertFailsWith<IllegalStateException> { Generate(FakeModelProvider()).apply(exchange()) }
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        fun exchange(text: String = "a question"): Exchange = Exchange(Request(text = text))

        /** An exchange that has been through assembly, which is what generation requires. */
        fun assembled(): Exchange =
            exchange().copy(
                assembled = Assembled(system = "instruction", prompt = "prompt", offer = Offer.NONE),
            )

        fun id(nativeId: String): SpanId = SpanId(RecordKind.MESSAGE, record(nativeId))

        fun span(
            nativeId: String,
            text: String,
            sourceTime: Instant = Instant.parse("2026-10-01T09:00:00Z"),
        ): Span = Span(
            id = id(nativeId),
            text = text,
            sourceTime = sourceTime,
        )

        /**
         * A zone nine hours ahead of UTC, with no summer time to make the arithmetic depend on
         * the month.
         *
         * Far enough ahead that a mid-afternoon instant and a late-evening one fall on different
         * local days, which is what lets one case hold the hour and another hold the date.
         * Deliberately nowhere this build is deployed or written: what these cases assert is that
         * the printed time follows whatever zone it was given, and naming a real deployment's
         * would make the case read as a fact about that deployment.
         */
        val ELSEWHERE: ZoneId = ZoneId.of("Asia/Tokyo")

        /**
         * When a request that states the present was asked.
         *
         * A Monday, named in the cases rather than computed, so a weekday printed wrongly reads as
         * the wrong day in the failure instead of as an expression that agrees with itself. Fixed
         * rather than the real clock for the ordinary reason, and for one particular to this line:
         * every case here that asserts a date or an hour is absent from the prompt would otherwise
         * go red on whichever day or minute the suite happened to run.
         */
        val ASKED: Clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC)

        /** The record a span of the same native identifier was read out of, which is what places it. */
        fun record(nativeId: String): ExternalId = ExternalId(Source.TELEGRAM, nativeId)

        /** The prompt a carried exchange was assembled with. */
        fun prompt(progress: Progress): String =
            requireNotNull(assertIs<Progress.Carried>(progress).exchange.assembled).prompt

        fun candidate(name: String, kind: SubjectKind): Candidate = Candidate(
            referent = when (kind) {
                SubjectKind.CONTACT -> Referent.OfContact(EntityId.mint())
                SubjectKind.CLIENT -> Referent.OfClient(EntityId.mint())
                SubjectKind.BODY_OF_WORK -> Referent.OfBodyOfWork(name)
                SubjectKind.CONVERSATION -> Referent.OfConversation(ExternalId(Source.TELEGRAM, name))
                SubjectKind.INBOX -> Referent.Inbox
            },
            name = name,
            score = 1.0,
        )

        fun usage(outputTokens: Long): TokenUsage =
            TokenUsage(inputTokens = 0, outputTokens = outputTokens)

        /**
         * A sizing with one field varied from what the corpus-reading shapes use.
         *
         * So a case about the ceiling says nothing about the effort, and the other way round.
         */
        fun sizing(
            effort: Effort = Sizing.ANSWERING.effort,
            ceiling: Int = Sizing.ANSWERING.ceiling,
        ): Sizing = Sizing(effort = effort, ceiling = ceiling)

        /** The answer a settling stage produced. */
        fun settled(progress: Progress): Answer = assertIs<Progress.Settled>(progress).answer

        /** What the settling stage said was wrong with what it produced, if anything. */
        fun reported(progress: Progress): Fault? = assertIs<Progress.Settled>(progress).fault
    }
}
