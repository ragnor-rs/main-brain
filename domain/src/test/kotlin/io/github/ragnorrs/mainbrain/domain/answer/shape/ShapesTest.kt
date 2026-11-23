package io.github.ragnorrs.mainbrain.domain.answer.shape

import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeEmbeddings
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeIndex
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.GroupingKey
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Reply
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Scope
import io.github.ragnorrs.mainbrain.domain.answer.ScopeResolver
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.SubjectName
import io.github.ragnorrs.mainbrain.domain.answer.SubjectSearch
import io.github.ragnorrs.mainbrain.domain.answer.contacts
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The five shapes, assembled.
 *
 * **What this asserts is the registration seam, which is the issue's whole point.** Every shape this
 * build answers is reachable by its own command, the fallback catches everything else, and `offer` is
 * absent — a scope line rather than an oversight, and the one a later workstream will notice if it
 * were quietly filled in.
 */
class ShapesTest {

    @Test
    fun `every shape this build answers is bound by its own command`(): Unit = runBlocking {
        val registry = registry()

        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            assertEquals(
                ShapeId(command),
                registry.bind(Request(text = "anything", command = command)).shape.id,
                "No shape answers to /$command, so the command is unreachable.",
            )
        }
    }

    @Test
    fun `a bare command reaches the inbox or asks for a subject, over every registered shape`(): Unit =
        runBlocking {
            // **Asserted over every command the registry binds rather than over one shape**,
            // because the card was read on two — the one that drafts a reply and the one that
            // prepares for a call — and the verdict is a function of what a shape accepts rather
            // than of which shape it is. The command list is the registry's own, so a shape
            // registered later is covered without this case being edited.
            //
            // The search refuses, so a shape that searched for a name nobody gave fails here loudly.
            // That is the half the wording cannot show: the old reply said nothing was found, and
            // what makes that untrue is that no read happened.
            val resolver = ScopeResolver(RefusingSearch)
            val registry = registry()
            var asked = 0
            var swept = 0

            registry.answerable.forEach { command ->
                val binding = registry.bind(Request(text = "/$command", command = command))
                val shape = binding.shape
                // A command carrying no argument has named nobody, which is what the branch turns
                // on. Read off the binding rather than assumed, so the case rests on what the
                // registry really produces.
                assertEquals(SubjectName.None, binding.subjectName, "/$command bound some other state")
                // A shape that accepts no subject is never resolved for; the runner skips it.
                if (shape.accepts.isEmpty()) return@forEach
                val scope = resolver.resolve(SubjectName.None, shape.accepts)
                if (SubjectKind.INBOX in shape.accepts) {
                    val resolved = assertIs<Scope.Resolved>(scope, "/${shape.id.value} refused the sweep")
                    assertEquals(SubjectKind.INBOX, resolved.subject.focus.kind)
                    swept++
                } else {
                    assertEquals(
                        Scope.SubjectMissing,
                        scope,
                        "/${shape.id.value} takes a subject and cannot sweep, so a bare command is a " +
                            "request with its subject missing — not a search that came back empty.",
                    )
                    asked++
                }
            }

            // **Both arms were reached, or this case passes having exercised neither.** Iterating a
            // set and branching on a property is how a green test comes to assert nothing: a
            // registry whose every shape swept, or accepted no subject, would satisfy every
            // assertion above without once reaching the branch the card is about.
            assertTrue(
                asked > 0,
                "No registered shape takes a subject and refuses the sweep, so this case's own branch never ran.",
            )
            assertTrue(swept > 0, "No registered shape sweeps, so the half that must keep working never ran.")
        }

    /** The search a bare command must never reach, because it gave no name to search for. */
    private object RefusingSearch : SubjectSearch {
        // What a real search covers, so that a verdict built from it here would carry a plausible
        // set rather than an empty one — which is what makes reaching this a visible failure.
        override val covers: Set<SubjectKind> =
            setOf(SubjectKind.CONTACT, SubjectKind.CLIENT, SubjectKind.CONVERSATION)

        override suspend fun matching(name: String): List<Candidate> =
            error("A command carrying no argument searched for '$name'.")

        // Reached only where a name was looked up and found nothing, so a bare command must not
        // arrive here either — and arriving says the verdict was built from a read rather than from
        // the request having named nobody.
        override suspend fun nearest(name: String): List<Candidate> =
            error("A command carrying no argument read near spellings of '$name'.")

        override suspend fun within(referent: Referent): List<Candidate> =
            error("A command carrying no argument traversed $referent.")
    }

    @Test
    fun `every classifiable shape says what it answers, in one line that is not its own name again`(): Unit =
        runBlocking {
            // The identifier is what a command needs. This is what *routing* needs, and a line that
            // only repeats the identifier leaves the router exactly where the defect found it.
            //
            // **Over the four the router is shown, and not the fallback**, because every reason here
            // is a reason about being a candidate: the fallback is kept out of the candidate set, so
            // nothing reads its line and neither a blank one nor a broken one could cost an answer.
            // The registry draws the same boundary, and asserting it over all five here would be a
            // rule measured on one kind applied to one it cannot hold for.
            listOf("status", "draft", "prepare", "research").forEach { command ->
                val described = registry().bind(Request(text = "x", command = command)).shape.description

                assertTrue(
                    described.isNotBlank(),
                    "/$command describes nothing, so routing has only its name to go on.",
                )
                assertFalse(
                    described.trim().equals(command, ignoreCase = true),
                    "/$command's description is its identifier again, which conveys nothing the name did not.",
                )
                assertFalse(
                    described.contains("\n"),
                    "/$command's description spans lines, and a line is what the prompt reads as a candidate.",
                )
            }
        }

    @Test
    fun `only the shape that adjudicates agreements offers to answer about them`(): Unit = runBlocking {
        // **The measured defect, and the half of its acceptance check that needs no model.** Asked
        // in free text what was agreed, naming nobody, the request reached the fallback — which
        // retrieves messages and reads no commitment, no closure verdict and no event — because
        // the word `status` was the whole of what the router had been told about the shape that
        // does. So the material the routing decision is made on now says so.
        //
        // **Against exactly one shape**, because two candidates offering the same question is the
        // ambiguity the bare identifier already was. The shape preparing for a call reads the same
        // commitments and deliberately does not claim this question: its line turns on the call.
        //
        // A pin on a form of words, which this repository usually distrusts — worth one here
        // because the vocabulary *is* the fix. A reword that drops it restores the defect, and
        // nothing else downstream would notice: the request would simply be answered by the
        // fallback, which is indistinguishable from a question that genuinely fitted no shape.
        //
        // **Over a vocabulary rather than the one word, because the exclusivity is the half a
        // single literal cannot measure.** A second shape claiming this question in a synonym —
        // decided, promised, committed — would leave a `contains("agree")` check green while the
        // ambiguity it exists to prevent was back. These are the words that question is asked in;
        // the forward direction needs only one of them, the reverse needs all of them.
        val offering = listOf("status", "draft", "prepare", "research", "ask").filter { command ->
            val described = registry().bind(Request(text = "x", command = command)).shape.description
            AGREEMENT_WORDS.any { described.contains(it, ignoreCase = true) }
        }

        assertEquals(
            listOf("status"),
            offering,
            "A question about what was agreed is offered by $offering, which is not the one shape " +
                "that reads commitments and whether anything discharged them.",
        )
    }

    @Test
    fun `offer is not built here`(): Unit = runBlocking {
        // It reads the knowledge base, the knowledge base is Drive, and Drive arrives with a later
        // workstream. Binding it to something would answer a priced request from message history.
        val registry = registry()

        assertEquals(
            ShapeId("ask"),
            registry.bind(Request(text = "price this up", command = "offer")).shape.id,
        )
    }

    @Test
    fun `a duplicate identifier among the five would be refused at construction`() {
        // The registry enforces it; this is the assertion that the five really are distinct, which a
        // sixth package copied from a fifth is the way to break.
        val ids = listOf("status", "draft", "prepare", "research", "ask")

        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `the fallback is what free text reaches when no classifier is wired`(): Unit = runBlocking {
        assertEquals(ShapeId("ask"), registry().bind(Request(text = "a general question")).shape.id)
    }

    @Test
    fun `only the shapes that read the open world skip the corpus short-circuit`(): Unit = runBlocking {
        // Three of the four named shapes read the corpus, so an empty one settles before any model
        // call. Research and the fallback must not, or they refuse the requests they exist for.
        val registry = registry()

        listOf("status", "draft", "prepare").forEach { command ->
            assertTrue(
                registry.bind(Request(text = "x", command = command)).shape.readsCorpus,
                "/$command would reach the model with nothing to answer from.",
            )
        }
        listOf("research", "ask").forEach { command ->
            assertTrue(
                !registry.bind(Request(text = "x", command = command)).shape.readsCorpus,
                "/$command refuses a request it is meant to answer from outside the corpus.",
            )
        }
    }

    @Test
    fun `only the draft shape promises alternatives`(): Unit = runBlocking {
        // Several answers are explicitly multi-variant and the rest are not. A shape promising a
        // choice and returning one is a defect the interface cannot see.
        val registry = registry()

        assertEquals(2..3, registry.bind(Request(text = "x", command = "draft")).shape.variants)
        listOf("status", "prepare", "research", "ask").forEach { command ->
            assertEquals(1..1, registry.bind(Request(text = "x", command = command)).shape.variants)
        }
    }

    @Test
    fun `every shape has a pipeline that ends by spending the model`(): Unit = runBlocking {
        // A stage list that only ever carries the exchange forward has no answer in it, and the
        // runner raises rather than inventing one.
        val registry = registry()

        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val stages = registry.bind(Request(text = "x", command = command)).shape.stages
            assertTrue(stages.isNotEmpty(), "/$command has no stages.")
            assertTrue(stages.last().spendsModel, "/$command never reaches the model.")
        }
    }

    @Test
    fun `every shape tells the model what language to answer in`(): Unit = runBlocking {
        // The sources are in one language and the question may be in another, so a shape that says
        // nothing answers in whichever the model drifts to — and an answer in the wrong language is
        // unusable however well grounded it is. The shared reply format says nothing about language,
        // so each shape's own instruction has to.
        //
        // A presence check rather than a wording check: what has to hold is that the subject is
        // addressed, and asserting a form of words would fail on a reworded rule while passing on a
        // sentence that happened to contain it.
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))

            assertTrue(
                assembled.exchange.assembled?.system?.contains("language") == true,
                "/$command never tells the model what language to answer in.",
            )
        }
    }

    @Test
    fun `every shape is told to anchor a relative time word, and what not to re-date`(): Unit =
        runBlocking {
            // The shapes that offer turns produced this alike on the real archive: a word that was
            // only meaningful on the day it was written — tomorrow, this evening — handed to a
            // reader weeks later, where it places nothing and reads as a commitment to a day
            // nobody named. One happened to name the gap instead, and nothing in the build
            // produced that, which is why the rule is in the shared format and not in a shape.
            //
            // **Both halves are pinned, because the exclusion is what keeps the rule survivable.**
            // A block carries computed phrases — today, now past, last stated so many days ago —
            // measured against the present, and a block header that is months old. A rule saying
            // only "anchor it to the block" re-dates those by months, which is the whole of what
            // the second sentence prevents and exactly what no gate downstream would catch.
            //
            // Asserted through the registry rather than over the format's text: what has to hold
            // is that it reaches every shape, and a case reading Reply.format would pass on a rule
            // no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains("against the head of its own block rather than against today"),
                    "/$command is never told to anchor a relative time word: $system",
                )
                assertTrue(
                    system.contains("is already measured against the present"),
                    "/$command is not told to leave a computed phrase alone, so the rule to " +
                        "anchor reaches those too and re-dates them: $system",
                )
                // The step the archive read caught going wrong: counting to "on Wednesday" needs
                // the weekday of the block it counts from, and inferring that from the date is
                // where one resolution in six landed a day out.
                assertTrue(
                    system.contains("Count from the weekday printed there"),
                    "/$command is not told to count from the printed weekday, so it infers one " +
                        "from the date: $system",
                )
            }
        }

    @Test
    fun `every shape is told to hedge in ordinary language and to leave the reading out`(): Unit =
        runBlocking {
            // Two shapes produced this alike on the real archive: a finding the speaker left
            // unsettled reached the reader as a parenthetical on how cautiously the system had
            // read the message, and as a sentence saying the thing was a reading of a message
            // rather than a recorded fact. So it is a marker every shape renders rather than
            // either instruction's fault, and the rule belongs where all five get it.
            //
            // Asserted through the registry rather than over the format's text, as the relative
            // time case is: what has to hold is that it reaches every shape, and a case reading
            // Reply.format would pass on a rule no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // **The antecedent first**, because the two clauses below survive its widening: a
                // rule stated over the material rather than over a marked piece of it tells three
                // shapes their material carries a mark it does not, and both pins stay green. That
                // widening is what the conditional opening exists to prevent.
                assertTrue(
                    system.contains("Where a piece of material is marked as not stated firmly"),
                    "/$command is told about the hedge marker unconditionally rather than where " +
                        "one is present, which asserts a mark three shapes never see: $system",
                )
                assertTrue(
                    system.contains("carry it in the verb rather than in a remark beside it"),
                    "/$command is never told where a hedge belongs, so it renders the marker: $system",
                )
                assertTrue(
                    system.contains("How the material was read is not part of the answer"),
                    "/$command is not told to leave its own reading out, which is the clause that " +
                        "reached the reader: $system",
                )
            }
        }

    @Test
    fun `the two shapes that render the hedge marker caution in its own words`(): Unit = runBlocking {
        // The older and narrower rule beside the one above: such a finding is not settled. Only
        // these two render the marker, and both carried the caution in a vocabulary the material
        // stopped using — a prohibition whose subject the prompt no longer marks is a rule about
        // nothing, and nothing read either sentence.
        //
        // A literal here, which is the weaker kind of assertion. The half that is a function of
        // the two — the phrase read off the rendered material and looked for in the prompt —
        // is in `StatusShapeTest`; this one is what covers the second shape without a second
        // rendering fixture.
        listOf("status", "prepare").forEach { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
            val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

            assertTrue(
                system.contains("not stated firmly or not first hand"),
                "/$command cautions about a hedged finding in a vocabulary the material no " +
                    "longer marks, so the rule has no subject: $system",
            )
        }
    }

    @Test
    fun `every shape is told an unrecorded outcome is no state, and what to write instead`(): Unit =
        runBlocking {
            // A reply called an undertaking an unclosed task — out of the shape whose instruction
            // already forbade calling one open, still owed, outstanding or pending. The prohibition
            // was a closed list of adjectives and the model reached for the one word outside it,
            // which is what a list of words always leaves available. So the rule is stated as the
            // thing that cannot be known and asserted here as that sentence rather than as its
            // vocabulary.
            //
            // **Over all five, which is the half a shape could not hold.** Only status and prepare
            // render the unrecorded-outcome label, so a rule seated in a shape reaches the material
            // that already states it and misses the three shapes handed raw messages — where a
            // promise read out of a message is exactly as unestablishable.
            //
            // **The replacement is pinned beside the prohibition, because a prohibition alone is how
            // this failed.** Told only what not to say, a model writes the nearest word that is not
            // on the list. Told what the record's own answer is, it has something to write.
            //
            // Asserted through the registry rather than over `Reply.format`, for the reason the
            // relative-time case gives: what has to hold is that it reaches every shape, and a case
            // reading the format's text would pass on a rule no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains("An outcome that is not recorded is not an outstanding commitment."),
                    "/$command is never told that an unrecorded outcome is not an outstanding " +
                        "commitment, so a promise nothing closed reads as one: $system",
                )
                assertTrue(
                    system.contains("give it no state beyond"),
                    "/$command is told the words to avoid and not that the undertaking takes no " +
                        "state at all, so a synonym outside the list is still available: $system",
                )
                assertTrue(
                    system.contains("who undertook what, and that no outcome is recorded"),
                    "/$command is not told what to write in place of a state, which is how a " +
                        "prohibition alone gets answered with the nearest word outside it: $system",
                )
                // The exclusion, and it is what keeps the rule survivable: a date's standing is
                // computed against the present and is what tells a reader where to look first. Read
                // as a state of the promise it would be forbidden, which would take the one part of
                // the answer the forbidden words were standing in for.
                assertTrue(
                    system.contains("not states of the promise"),
                    "/$command is not told that a due date's standing is exempt, so the rule " +
                        "reaches the one fact that says what deserves attention: $system",
                )
            }
        }

    @Test
    fun `every shape is told a finding names what it is about, and what to do where it cannot`(): Unit =
        runBlocking {
            // Two shapes produced this alike on the real archive. A brief read before a call relayed
            // a hedged undertaking about writing the fifth of something once there were more than
            // four, with neither the thing counted nor what writing one means anywhere in it; and an
            // item identified only by the shorthand two correspondents use for it. The free-text
            // fallback then did the same to a progress figure — so many points of six, with nothing
            // saying which six.
            //
            // **That second shape is why the rule is here and not in an instruction.** It reads raw
            // messages and runs no extraction stage, so the referent was never dropped by one: the
            // symptom survives without it, which makes this the composition layer's and not either
            // shape's.
            //
            // **Both branches are pinned, because each alone is a defect.** Told only to resolve, a
            // model shown no resolving turn supplies a subject that fits. Told only to say the record
            // does not name the thing, it declines to resolve a shorthand it was handed the answer
            // to. And the permission is pinned beside them: the correspondents' own word is often the
            // most precise name a thing has, so a rule that read as a ban on shorthand would cost the
            // answer its checkability against the conversation.
            //
            // Asserted through the registry rather than over `Reply.format`, for the reason the
            // relative-time case gives: what has to hold is that it reaches every shape, and a case
            // reading the format's text would pass on a rule no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains(
                        "in terms somebody who does not have the conversation in front of them can identify",
                    ),
                    "/$command is never told that a finding has to name what it is about, so a " +
                        "sentence whose subject is a shorthand reaches the reader naming nothing: $system",
                )
                // The elided figure is named in the list beside the pronoun, because it is the form
                // the third measured instance took and the one a closed list would leave available:
                // a fraction with no noun on it is not a word pointing anywhere, so a rule keyed on
                // a referring word alone states the requirement and offers neither remedy.
                assertTrue(
                    system.contains("a figure whose noun is left out"),
                    "/$command is told about a referring word and not about a figure with its noun " +
                        "elided, which is the form this was measured on: $system",
                )
                assertTrue(
                    system.contains("write what it refers to"),
                    "/$command is not told to resolve a shorthand the material already explains, " +
                        "so it relays the word instead of the thing: $system",
                )
                // **The antecedent, pinned for the reason the hedge case pins its own.** Only the
                // shapes that read a conversation are handed the turns either side of a finding, so
                // a sentence saying those turns are there for this would tell the two reading the
                // open world that their material carries something it does not — the width this
                // file has paid for most often. The conditional opening is what prevents it, and
                // the rule reads correctly either way round, so nothing else would catch its loss.
                assertTrue(
                    system.contains("Where it carries the turns either side of a finding"),
                    "/$command is told the neighbouring turns are in its material rather than to " +
                        "read them where they are, which asserts a shape of material two shapes " +
                        "never get: $system",
                )
                // **This branch's own antecedent, and it is pinned for the reason the other one's
                // is.** Stated without "where the material does not say", the instruction to report
                // the thing as unnamed becomes unconditional and the model declines to resolve a
                // shorthand it was handed the answer to — the half this rule would lose while every
                // other assertion here stayed green.
                // Carried to the clause after the condition, not the condition alone: a sibling
                // rule in this same format opens "Where the material does not say what a figure
                // measures", so the bare antecedent is matched by prose this block does not own and
                // would stay green with the whole branch deleted.
                assertTrue(
                    system.contains("Where the material does not say, report what it does hold"),
                    "/$command is told to report the thing as unnamed without the condition that " +
                        "the material resolves nothing, so the resolve branch is unreachable: $system",
                )
                // Scoped to what the shape was handed rather than to the record: three of the five
                // read a bounded window, so "the record does not name it" is an assertion over a
                // corpus none of them saw — the over-claim a sibling card exists to fix — and the
                // open-world shapes have no record behind their findings at all.
                assertTrue(
                    system.contains("say that what you were given does not name the thing in question"),
                    "/$command is not told what to do where the material resolves nothing, or is " +
                        "told to answer it over the whole record rather than over what it read: $system",
                )
                // The permission, and it is what keeps the rule from costing more than it buys: the
                // correspondents' own word for a body of work is frequently its most precise name.
                assertTrue(
                    system.contains("may be kept where you also say once what it stands for"),
                    "/$command reads as forbidding the correspondents' own shorthand outright, " +
                        "which paraphrases away the most precise name a thing has: $system",
                )
            }
        }

    /**
     * Read off rendered replies, three forms of one person and more than one shape.
     *
     * A sectioned reply about everything headed sections with a person's stored name while the
     * prose beneath them named that person in the corpus's own script; a reply of findings named
     * one person by account handle in some of them and by stored name in others; and a third put a
     * name in the prose that the support line under it printed differently. Which is the
     * shared format's test rather than a shape's: the shapes that report findings produced it
     * alike, and the one that reads no finding at all produced it too.
     */
    @Test
    fun `every shape is told to name one person one way, from the material, handle included`(): Unit =
        runBlocking {
            // **Every clause here is pinned because each is a defect on its own.** Told to pick one
            // form and not which, a model picks the answer's own language and renders the stored
            // name into it — which is the direction this corpus runs in, its names being mostly in
            // the script the answers are not written in. Told about prose and not about a heading,
            // it leaves the one line it copies from a label disagreeing with the prose under it.
            // Told the handle is not a name, it drops the one thing that tells two people of one
            // stored name apart. And told which name to use without the bound, it supplies a name
            // for a finding the material attaches nobody to.
            //
            // Asserted through the registry rather than over `Reply.format`, for the reason the
            // relative-time case gives: what has to hold is that it reaches every shape, and a case
            // reading the format's text would pass on a rule no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains("One person is named one way in the whole of your answer"),
                    "/$command is never told to settle on one form of a person's name, so one " +
                        "person reaches the reader as two: $system",
                )
                // Which form, and it is the half that decides whether the rule can be obeyed at
                // all: every other place a reader meets the name — the attribution labels, a
                // section's label, the account of what the answer rests on — prints the one the
                // record holds.
                assertTrue(
                    system.contains("that way is the one the material gives for them"),
                    "/$command is told to be consistent without being told which name is " +
                        "canonical, so it is free to settle on a form nothing else in the reply " +
                        "uses: $system",
                )
                assertTrue(
                    system.contains("never transliterated, never translated and never cut to a shorter form"),
                    "/$command is not told to keep the script the name is written in, which is the " +
                        "direction this corpus fails in — a model answering in the question's " +
                        "language renders a stored name into it unprompted: $system",
                )
                // The label a marker fuses onto a name, exempted. The material really does fuse one
                // — a turn the archive owner wrote carries their role in brackets after the name —
                // and the shape that prepares a call tells the model never to write that
                // parenthesis out, so *character for character* without this contradicts it in the
                // same prompt.
                assertTrue(
                    system.contains("so leaving one out is not shortening a name"),
                    "/$command is told to reproduce a name character for character with nothing " +
                        "saying a bracketed role marker is no part of one, so the rule contradicts " +
                        "the instruction that says to drop it: $system",
                )
                // The second bound, which is the open world's: a person named on a web page has no
                // material form to prefer, so a rule stated over every name alike would have this
                // shape leave them unnamed or invent a stored form for them.
                assertTrue(
                    system.contains("named in a source you read rather than in the material is named as"),
                    "/$command is told every name comes from the material, which is false for a " +
                        "shape that reads the open world: $system",
                )
            }
        }

    @Test
    fun `the rule about naming a person sits in the shared format, not in the five instructions`() {
        // **The seat is what this rule's note spends its first paragraph on, and every pin above
        // reads the assembled prompt** — a shape's instruction and the shared format joined — so a
        // later card that copied these paragraphs into the five instructions and deleted them from
        // `format` would leave all of them green while undoing the decision. The format is
        // therefore read directly here, which is the one assertion that can see where the rule
        // lives.
        val format = Reply.format(variants = 1..1, sections = true).replace(WHITESPACE, " ")

        assertTrue(
            format.contains("One person is named one way in the whole of your answer"),
            "the rule has left the shared format, so each shape is free to word it differently " +
                "or to drop it: $format",
        )
        assertTrue(
            format.contains("Relaying what a message said is not quoting a name"),
            "the half that narrows the shapes' own quoting permission has left the shared format, " +
                "which is the one place it can be stated once for all of them: $format",
        )
    }

    @Test
    fun `the heading rule is asked per request, where a heading is, and not of the shapes forbidden one`() {
        // **The heading is where the two forms were measured against each other** — it is the one
        // line in an answer copied from a label rather than composed — and it is also the one half
        // of this rule the shared format may not carry. A shape that never sections is told in its
        // opening line to write no heading at all, so a heading rule in `format` speaks of one to
        // the two shapes forbidden it: the pair is then unobeyable, and the model picks a side
        // where no prose pin can see which. Whether there is a heading is a fact about the request,
        // which is what puts the clause beside the marker that writes one.
        //
        // Both directions, because either alone passes while the defect stands: the clause present
        // in the per-request ask, and absent from the format that forbids headings outright.
        val sectioning = Reply.sectioning().replace(WHITESPACE, " ")
        assertTrue(
            sectioning.contains("A heading naming a person uses the name the material gives for them"),
            "nothing tells a sectioned answer that a heading and the prose under it name a person " +
                "the same way, which is where the two forms were read against each other: $sectioning",
        )
        val unsectioned = Reply.format(variants = 1..1, sections = false).replace(WHITESPACE, " ")
        assertFalse(
            unsectioned.contains("heading naming a person"),
            "the format that forbids headings outright also tells the model how to write one, so " +
                "the two rules are left for the model to choose between: $unsectioned",
        )
    }

    /**
     * The same rule's other half: what is not a second name, and what the rule does not reach.
     *
     * **Split from the case above rather than written beside it, and the split is at detekt's
     * ceiling rather than at a seam in the rule.** The pins over the five shapes outgrew what the
     * style gate allows one function, so the rule is pinned by two cases, as the one about an
     * unrecorded outcome already is. What decides which pin goes
     * where is the half of the defect it answers: the case above holds that one form is settled on
     * and which form that is, and this one holds the forms that are not names, the permission the
     * rule narrows, and the two places it stops.
     */
    @Test
    fun `every shape is told a handle is no second name, that relaying is not quoting, and what stays unnamed`(): Unit =
        runBlocking {
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, as above. The handle is one of the three forms one
                // person was measured arriving in, and it is the only one a reader cannot even
                // guess is the same person.
                assertTrue(
                    system.contains("by an account handle"),
                    "/$command is not told that a handle in a message is not a second name, which " +
                        "is one of the forms this was measured on: $system",
                )
                // The permission, and it is what keeps the rule from costing more than it buys: a
                // handle is carried on a candidate precisely so a reader can tell two people of one
                // stored name apart, and this corpus collides them.
                assertTrue(
                    system.contains("may go once beside the name to tell them apart, never in place of it"),
                    "/$command reads as forbidding the handle outright, which drops the one thing " +
                        "that tells two people of one label apart: $system",
                )
                // **The half a live A/B of the two prompts added, and the one that carries most of
                // the rule.** Told only to settle on the material's form, the model stopped
                // rendering a whole answer into the corpus's script and went on naming people as
                // their messages name them wherever it reported what a message said — so one person
                // arrived in two forms in one sentence: the material's name as the party to the
                // finding, the message's name as a participant in the turn behind it. Relaying is
                // the ordinary way these shapes write, so without this the rule reaches the minority
                // of its own cases.
                assertTrue(
                    system.contains("Relaying what a message said is not quoting a name"),
                    "/$command is told to settle on one form without being told that relaying a " +
                        "turn is not the quoting case, so the message's own names go on reaching " +
                        "the reader beside the material's: $system",
                )
                // The permission it narrows, quoted close enough to its own wording that the two
                // cannot be read as disagreeing: four shapes permit a quoted name to keep the
                // source's form, and nothing said which source a person's name came from.
                assertTrue(
                    system.contains(
                        "Keeping a name in the form its source gave it means the form the material " +
                            "gives for the person",
                    ),
                    "/$command is not told which source a person's name keeps the form of, which " +
                        "is the permission this rule has to narrow rather than contradict: $system",
                )
                // The bound. A rule about which name to use reads as a rule that there must be one,
                // and a finding the material attaches nobody to has to stay that way.
                assertTrue(
                    system.contains("is reported as it stands rather than given a name you chose"),
                    "/$command is told which name to use without the bound, so a finding the " +
                        "material attaches nobody to is given a name that was chosen: $system",
                )
                // The exclusion, which is what keeps the rule survivable in the one output somebody
                // sends: a message calling the other party by the name a record holds rather than
                // the one they are written to by reads as coming from outside the conversation.
                assertTrue(
                    system.contains("it names the people in it as the thread names them, its reader included"),
                    "/$command is not told a drafted reply addresses its reader as the thread " +
                        "does, so the rule reaches the one output somebody sends verbatim: $system",
                )
            }
        }

    @Test
    fun `every shape granting the quoting permission points it at the material, and the drafting one has none`(): Unit =
        runBlocking {
            // **The quoting permission is where the rule above had to be narrowed, and it lives in
            // the shapes rather than in the shared format.** Each granter tells the model that a
            // name it quotes keeps the form the source gave it, which is right for a title and says
            // nothing about *which* source a person's name comes from — and a live A/B found that a
            // consistency rule stated only in the shared block loses to it exactly where the model
            // is relaying a turn. So the permission points at the material's form where it is
            // granted, and the shared block carries the rest.
            //
            // **The open-world shape is a granter too, and its material carries authors**: it reads
            // turns and offers them through the same attribution rendering, and its own instruction
            // tells the model to attribute a sampled message to the person marked on it and never
            // to somebody a web source named. So withholding the pointer there would leave the one
            // shape whose competing source is a web page in exactly the state the other three were
            // edited out of. What it does not carry is a finding's party, which is why its sentence
            // names the mark on a message rather than the labels the corpus shapes get.
            //
            // **Both directions, because the wrong half of this is a word's edit away and silent.**
            // The drafting shape grants no such permission at all: its reader is the other party,
            // it names everyone in a sent message as the thread names them, and that is the shared
            // block's one exclusion — so a pointer there would contradict the exclusion in the same
            // prompt.
            val granted = "For a person the material names, that form is the one it gives for them"
            listOf("status", "prepare", "ask", "research").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                assertTrue(
                    system.contains(granted),
                    "/$command grants the quoting permission without saying which source a " +
                        "person's name keeps the form of, so the permission goes on licensing the " +
                        "message's own name beside the material's: $system",
                )
            }
            val shape = registry().bind(Request(text = "x", command = "draft")).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
            val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

            assertFalse(
                system.contains(granted),
                "/draft is told to name a person as the material does, in the one output somebody " +
                    "sends verbatim and against the exclusion in the same prompt: $system",
            )
        }

    @Test
    fun `every shape is told to resolve a time it can work out, and to give a figure its unit`(): Unit =
        runBlocking {
            // Both halves were read off rendered answers, and each was read twice. A meeting agreed
            // for an hour or two after a stated time arrived as the offset, with both halves of the
            // sum in one sentence of one message; a price arrived as a bare number, in a brief and
            // again in the free-text answer, beside another figure in that same reply that carried
            // its unit. Two shapes each, which is the test for seating a rule in the shared format
            // rather than in an instruction.
            //
            // **The bounds are pinned beside the rules, because each is what keeps its rule from
            // being the next defect.** Told only to resolve, a model resolves an offset whose anchor
            // it never had and reports a time nobody named; told only to carry a unit, it supplies
            // the likeliest currency and the answer gains a fact the corpus never held.
            //
            // Asserted through the registry rather than over `Reply.format`, for the reason the
            // relative-time case gives: what has to hold is that it reaches every shape, and a case
            // reading the format's text would pass on a rule no shape was given.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains("is worked out and reported as the time of day it comes to"),
                    "/$command is never told to resolve a time it can work out, so an offset " +
                        "reaches the reader as arithmetic to do: $system",
                )
                assertTrue(
                    system.contains("report what was said rather than resolving it"),
                    "/$command is not told what to do with an offset whose anchor it does not " +
                        "have, so the rule to resolve reaches those too: $system",
                )
                assertTrue(
                    system.contains("A figure you report carries what it measures"),
                    "/$command is never told to give a figure its unit, so a price reaches the " +
                        "reader as a bare number: $system",
                )
                // The half that settles the conflict rather than filling the vacancy: four shapes
                // permit a quoted phrase to keep the source's form, which is right for a name and
                // is what left a currency in the corpus's own script beside a figure with none.
                assertTrue(
                    system.contains("a unit is not a phrase you are quoting"),
                    "/$command is told to give the unit and not that the quoting permission does " +
                        "not cover it, so the two rules are left to disagree: $system",
                )
                assertTrue(
                    system.contains("rather than supplying the likeliest"),
                    "/$command is not told what to do where the material says no unit, so it " +
                        "supplies one and the answer gains a fact the corpus never held: $system",
                )
            }
        }

    @Test
    fun `every shape is told the rule binds both ways, and that a premise states what it rests on`(): Unit =
        runBlocking {
            // **The failure the case above cannot see, and it is the same failure one step on.** A
            // model told at length not to call an undertaking open, still owed or outstanding does
            // not land on *no state at all*; it lands on the state at the other end and writes that
            // the thing was done. A proposed call question asserted a promised round of checking had
            // been carried out and asked what it turned up, over material holding the undertaking
            // and no outcome. Every assertion the case above makes was true of that prompt.
            //
            // **The premise clause is pinned beside the adjective half, because the premise is where
            // it was measured.** Both measured failures were presuppositions rather than statements:
            // nothing in them reads as a claim, which is what makes them worse — they arrive as
            // background the reader is assumed to share, and no gate reads composed prose. A rule
            // saying only *not done* leaves the construction they were written in available.
            //
            // Over all five for the reason the case above is: three of them never see the label and
            // a promise read out of a message is as unestablishable either way round.
            listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                // Flattened before matching, because the prompt is wrapped and these clauses
                // straddle a line break.
                assertTrue(
                    system.contains("That holds in both directions"),
                    "/$command is told what an unrecorded outcome may not be called and not that " +
                        "the rule binds the other way, so a promise nothing closed reads as done: " +
                        "$system",
                )
                // The principle and the vocabulary that illustrates it, in one assertion rather than
                // on the principle alone — the adjectives are what the sentence is deletable down
                // to, and a pin on its opening clause would leave the suite green while they went.
                assertTrue(
                    system.contains(
                        "It is no more done than it is waited on: not carried out, not completed, " +
                            "not delivered, not dealt with",
                    ),
                    "/$command is not told the far state is as unestablishable as the near one, " +
                        "which is the one a prohibition on the near one leaves available: $system",
                )
                assertTrue(
                    system.contains("not taken as read by a sentence resting on it"),
                    "/$command is told not to name the state and not that a sentence presupposing " +
                        "it asserts it, which is the form both measured failures took: $system",
                )
                // **The permission, not a replacement — the replacement for a question belongs to
                // the shape that writes questions, and `STATE`'s own note says so.** Pinned because
                // a both-ways prohibition read without it forbids reporting the closure the material
                // does carry, and because it is the one thing this block says about the positive
                // half. Worded without an imperative to cite: the open-web shape gets this block
                // too, and a web finding there may carry no citation at all.
                assertTrue(
                    system.contains("that outcome is reported as they have it"),
                    "/$command is told what not to assume and not that a recorded outcome is still " +
                        "reportable, so a closure the material does carry reads as forbidden too: " +
                        "$system",
                )
            }
        }

    @Test
    fun `the assertions paragraph still follows the one its first words bind back to`(): Unit =
        runBlocking {
            // **This defect has now been introduced twice, in the same slot, and nothing caught
            // either.** The paragraph added for a shape whose answer is its findings opens on
            // "those assertions", and its antecedent is the format's last paragraph — the one
            // saying what a claim is. A block inserted between them strands the reference, and the
            // relative-time paragraphs had to be moved above the claims paragraph for exactly this
            // reason. The state rule then landed in the cleared slot and had to be moved last.
            //
            // Asserted as an order rather than as presence, which is the gap that let it recur: a
            // case checking both strings are in the prompt passes whichever way round they sit.
            // Only the two shapes whose answer is its findings get the paragraph at all.
            listOf("status", "prepare").forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

                val claims = system.indexOf("is a claim and carries at least one")
                val binds = system.indexOf("What is asked of you here is those assertions")
                assertTrue(claims >= 0, "/$command has no paragraph defining what a claim is: $system")
                assertTrue(binds >= 0, "/$command is not told its findings are those assertions: $system")
                assertTrue(
                    claims < binds,
                    "/$command reads \"those assertions\" before the paragraph that gives the " +
                        "phrase its antecedent, so the reference points at whatever was inserted " +
                        "between them: $system",
                )
                // And nothing may sit between the two. Measured as adjacency rather than as order
                // alone, because order holds while a block is inserted in the middle.
                assertTrue(
                    binds - (claims + "is a claim and carries at least one.".length) <= PARAGRAPH_GAP,
                    "/$command has ${binds - claims} characters between the claims paragraph and " +
                        "the sentence binding back to it, so something was inserted between the " +
                        "two: $system",
                )
            }
        }

    @Test
    fun `no shape asks for a forbidden state in the words the rule itself uses`(): Unit = runBlocking {
        // The conflict the status shape's own note names: a prompt asking for what is outstanding in
        // one line and forbidding that reading in another cannot be obeyed as written, and a model
        // resolves it by picking one — silently. Held over all five now that the prohibition is
        // shared, because it is the shared half that makes any shape's own wording a conflict.
        //
        // **The literal half of a judgement, which is what the name says and all this can be.** The
        // vocabulary is the rule's own, so it tracks the rule and grows when it grows — but a
        // synonym outside it is exactly how this conflict came back once before, and no list closes
        // that. Two wordings were audited by reading rather than by this case: the call shape asked
        // to lead with what the record "leaves unsettled", reworded to select by label instead, and
        // the drafting shape's "what the other party has left open", which is a question in a
        // thread rather than an undertaking and so sits outside the rule's antecedent.
        //
        // Read against the collapsed prompt so that a wording broken over a line is not a wording
        // this misses.
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
            val system = assembled.exchange.assembled?.system?.replace(WHITESPACE, " ").orEmpty()

            listOf(
                "what is outstanding",
                "what is owed",
                "what is still owed",
                "what is open",
                "what is pending",
                "what is unresolved",
                "what is unclosed",
                "what is awaiting",
                "leaves unsettled",
            ).forEach { asked ->
                assertFalse(
                    system.contains(asked, ignoreCase = true),
                    "/$command asks for \"$asked\", which the shared format forbids it from " +
                        "reporting: $system",
                )
            }
        }
    }

    @Test
    fun `no shape writes its prompt in the Markdown that prompt forbids`(): Unit = runBlocking {
        // The shared reply format tells the model to write plain prose with no asterisks around
        // words, and a model told one thing and shown another follows what it is shown — which is
        // why every marker on the material is a word rather than a symbol. An instruction that
        // emphasises its own sentences contradicts that in the one place nothing was watching: the
        // format declares the rule without an extent, so the prose gate cannot see it, and a pin on
        // a sentence passes either way because the markers sit outside the words it matches.
        //
        // Over all five, because the shape this was found on is not special — and a draft is where
        // it costs most, its output being text somebody pastes into a messenger.
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))

            assertFalse(
                assembled.exchange.assembled?.system?.contains("*") == true,
                "/$command writes its own prompt with asterisks, which that prompt forbids the " +
                    "model from using.",
            )
        }
    }

    @Test
    fun `every shape tells the model how many alternatives the runner will accept`(): Unit = runBlocking {
        // **This is here because a live stack produced it, and produced it as silence.** The
        // runner rejects a reply whose variant count falls outside the shape's range, retries once,
        // and then answers insufficient evidence — the same thing it says about an empty corpus. So
        // a format that named the marker without saying how many to write left the model to pick a
        // number, and a block called an *alternative* invites several. Four of the five shapes
        // accept exactly one, so every free-text question came back as though nothing had been
        // retrieved, with a full index underneath it and the model answering correctly each time.
        //
        // Computed from the shape's own range rather than matched against a sentence, so a shape
        // that changes its range and not its prompt fails here.
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
            val system = assembled.exchange.assembled?.system.orEmpty()

            val wanted = shape.variants
            if (wanted.first == wanted.last && wanted.first == 1) {
                assertTrue(
                    system.contains("one", ignoreCase = true),
                    "/$command accepts exactly one variant and never says so.",
                )
            } else {
                listOf(wanted.first, wanted.last).forEach { bound ->
                    assertTrue(
                        system.contains(bound.toString()),
                        "/$command accepts $wanted and never states the bound $bound.",
                    )
                }
            }
        }
    }

    @Test
    fun `the count in the prompt follows the range rather than being fixed prose`(): Unit = runBlocking {
        // The property the test above cannot establish: that the range reaches the prompt at all.
        // One asserting only that a count is mentioned would pass against a format that hard-coded
        // the same sentence for every shape, which is the defect in its next form.
        val one = Assemble("an instruction", 1..1, FakeGrouping())
        val several = Assemble("an instruction", 2..4, FakeGrouping())

        val first = assertIs<Progress.Carried>(one.apply(Exchange(Request(text = "x"))))
        val second = assertIs<Progress.Carried>(several.apply(Exchange(Request(text = "x"))))

        assertTrue(
            first.exchange.assembled?.system != second.exchange.assembled?.system,
            "The same instruction produced the same prompt for one variant and for four, so the " +
                "range the runner enforces is not reaching the model.",
        )
        assertTrue(second.exchange.assembled?.system?.contains("4") == true)
    }

    @Test
    fun `every turn a shape offers is one it asked the author of`(): Unit = runBlocking {
        // **Asserted over all five at once, because this is the arrangement no shape may get
        // wrong.** A turn offered with no author leaves the sender and the subject as the only
        // agents a model can name, so another participant's words are reported as the subject's —
        // and nothing downstream sees it, since the grounding gate asks whether a cited span was
        // retrieved and not whose words it carried. The renderer takes the author as a required
        // argument, which makes a shape decide; this is what makes it decide from the corpus rather
        // than by passing nothing.
        //
        // **Computed from what each shape offered rather than from a list of which shapes read
        // turns.** Two reach a turn through a conversation, two through a ranking and one through
        // both, and a shape changing which it does is exactly when this is worth re-asking. The
        // fixture holds one turn of each provenance — one in a thread, one only the index has — and
        // nothing else, so every record in the material is a turn and not a finding's evidence.
        //
        // **Each shape is asserted to have offered one of them, because both sides of the
        // comparison are intersections.** A shape whose retrieval came back empty would make them
        // both the empty set and pass having asserted nothing, per shape and silently, which is
        // this repository's recorded shape for a gate that is off rather than clean.
        val thread = FakeRetrieval.record("1")
        val ranked = FakeRetrieval.record("90")
        val turns = setOf(thread, ranked)
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(FakeRetrieval.message("1", "something was said")),
            )
            val index = FakeIndex(hits = listOf(span("90")))
            val shape = registry(retrieval = retrieval, index = index)
                .bind(Request(text = "x", command = command)).shape
            val retrieved = assertIs<Progress.Carried>(
                shape.stages.first().apply(Exchange(Request(text = "x"), subject = FakeRetrieval.aContact())),
            ).exchange.retrieved

            val offered = retrieved.map { it.id.record }.toSet()
            val askedAbout = retrieval.asked
                .filterIsInstance<FakeRetrieval.Ask.Turns>()
                .flatMap { it.messages }
                .toSet()
            assertTrue(
                offered.intersect(turns).isNotEmpty(),
                "/$command offered none of the fixture's turns, so the case below asserts nothing " +
                    "about it: $retrieved",
            )
            assertEquals(
                offered.intersect(turns),
                askedAbout.intersect(offered),
                "/$command offers a turn it never asked the author of, so the prompt carries it " +
                    "with nobody attached",
            )
        }
    }

    @Test
    fun `a shape reporting on a subject asks whose each turn it offers is`(): Unit = runBlocking {
        // **The author is half the fact and whose turn it is, is the other half.** Naming who wrote
        // a turn does not say whether the question was about them: most of a subject's threads are
        // rooms, so a shape that asks only for names offers other participants' matters as the
        // subject's own — fluent, correctly cited, and invisible to the grounding gate, which asks
        // whether a span was retrieved and not whose business it recorded.
        //
        // **The argument is defaulted on the port, so a shape that omits it is silent rather than a
        // compile error**, and a per-shape case cannot catch the shape that does not have one.
        // Asserted over all five here so that the next shape to report on a subject inherits the
        // question rather than being asked separately whether anybody remembered.
        //
        // **Computed from each shape's own declaration rather than from a list of names.** The
        // shapes this binds are the ones asserting findings: a shape composing a message reads the
        // same threads and passes nothing on purpose, since it writes *to* the other party rather
        // than reporting *about* them, and a shape answering the open world has no subject to
        // compare an author against. So the discriminator is `assertsFindings`, and a shape
        // changing that is precisely when this is worth re-asking.
        //
        // **Driven with a contact, and the assertion is still not about that kind.** It compares
        // against `contacts()` rather than against a non-empty set, so it holds for a subject the
        // call resolves no contact for too — which is the honest shape of the requirement, since a
        // shape accepting a client or a conversation passes whatever that subject reaches and
        // legitimately marks nothing. What it forbids is passing nothing at all.
        val subject = FakeRetrieval.aContact()
        listOf("status", "draft", "prepare", "research", "ask").forEach { command ->
            val retrieval = FakeRetrieval(
                conversations = listOf(FakeRetrieval.record("1")),
                messages = listOf(FakeRetrieval.message("1", "something was said")),
            )
            val shape = registry(retrieval = retrieval, index = FakeIndex(hits = listOf(span("90"))))
                .bind(Request(text = "x", command = command)).shape
            assertIs<Progress.Carried>(
                shape.stages.first().apply(Exchange(Request(text = "x"), subject = subject)),
            )

            val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>()
            if (!shape.assertsFindings) return@forEach
            assertTrue(asked.isNotEmpty(), "/$command asked about no turns, so this asserts nothing about it")
            asked.forEach {
                assertEquals(
                    subject.contacts(),
                    it.subject,
                    "/$command reports findings about a subject and asks who wrote each turn " +
                        "without asking whose it is, so every turn comes back unplaced and none " +
                        "is marked — which reads as a corpus in which every turn is the subject's",
                )
            }
        }
    }

    @Test
    fun `the shapes reporting findings section by body of work and the two composing do not`(): Unit = runBlocking {
        // Asserted over all five at once, because which shapes section is decided in the shapes and
        // nowhere else. Both ways of getting it wrong are a word's edit apart and neither shows up
        // again downstream: a draft divided under headings is unsendable, and a report that merged
        // two bodies of work reads as a perfectly good answer.
        val spanning = Exchange(
            Request(text = "what did we agree"),
            retrieved = listOf(span("42"), span("43")),
        )

        val sections = EXPECTED.keys.associateWith { command ->
            val shape = registry(TWO_BODIES_OF_WORK).bind(Request(text = "x", command = command)).shape
            val assembly = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assembly.apply(spanning)).exchange.assembled
            requireNotNull(assembled).prompt.contains("(Body of work:")
        }

        assertEquals(EXPECTED, sections)
    }

    @Test
    fun `the shapes whose answer is its findings declare it and are told so`(): Unit = runBlocking {
        // **Both halves, because they are what can disagree.** The runner refuses a reply that
        // asserts nothing by reading the declaration; the model only knows that a statement about
        // the material is one of those assertions because the prompt says so. A shape that declares
        // it and is not told is refused for obeying the instructions it was given, and a shape told
        // it and not declaring it is never held to it — and neither shows up as anything but
        // *nothing to answer from*, which is also what an empty corpus says.
        val declared = ASSERTING.keys.associateWith { command ->
            registry().bind(Request(text = "x", command = command)).shape.assertsFindings
        }
        val told = ASSERTING.keys.associateWith { command ->
            val shape = registry().bind(Request(text = "x", command = command)).shape
            val assemble = shape.stages.filterIsInstance<Assemble>().single()
            val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
            // Flattened, because the sentence is wrapped in the prompt and a quoted phrase that
            // straddles a line break is matched by nothing.
            flattened(assembled.exchange.assembled?.system.orEmpty()).contains(ASSERTS)
        }

        assertEquals(ASSERTING, declared, "A shape declares the wrong thing about what its answer is made of.")
        assertEquals(ASSERTING, told, "A shape's declaration and what its prompt says about it disagree.")
    }

    @Test
    fun `every shape is told an absence is about what it was given, and settles nothing`(): Unit =
        runBlocking {
            // A free-text answer asserted that there were no agreements about a topic, as a claim
            // carrying citations with named authors, and the grounding gate passed it — while a
            // broader question asked separately reported an agreement on exactly that topic. Both
            // cannot be right, and the one asserting the absence is the one a reader has no way to
            // check: no citation can evidence an absence, so the claim shape that most needs
            // checking is the shape the gate is blindest to.
            //
            // **Over all five, because the shape it was read on cannot hold it.** The fallback's
            // material is a ranked read of the whole corpus, which is the furthest thing from
            // everything there is, and it is told nothing about assertions at all — the paragraph
            // above reaches only the two whose answer is its findings. So a rule seated there would
            // miss the shape that produced this, and one seated in a shape would miss the other
            // four, each of which can state an absence just as confidently.
            //
            // Asserted through the registry rather than over `Reply.format`, for the reason the
            // relative-time case gives: what has to hold is that it reaches every shape, and a case
            // reading the format's text would pass on a rule no shape was given.
            // Read off the same map the declaration is read against rather than a second list of
            // which shapes there are: a sixth added tomorrow inherits this case, where a literal
            // list would let it escape the one rule that has to reach every shape.
            ASSERTING.keys.forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                val system = flattened(assembled.exchange.assembled?.system.orEmpty())

                // **Each pin occurs once, which is what makes the assertions below mean this block
                // and not whatever else came to say the same words.** A `contains` is satisfied by
                // any occurrence, so a fragment a shape's own instruction or a later shared rule
                // also carries is a pin that stays green after the block it was written for is
                // gone. Two of the merged cards either side of this one are about an absence and
                // about a ranked sample standing for the corpus, so the collision is the live risk
                // rather than a hypothetical one. Checked here rather than in a note, because a
                // uniqueness verified by hand is verified once and then assumed.
                (ABSENCE_PINS.keys + ABSENCE_CITED.keys).forEach { pin ->
                    val found = Regex(Regex.escape(pin)).findAll(system).count()
                    assertTrue(
                        found <= 1,
                        "/$command carries \"$pin\" $found times, so the case pinning it is " +
                            "satisfied by prose this rule does not own and would stay green with " +
                            "the rule deleted. Narrow the fragment to words only this block has.",
                    )
                }

                ABSENCE_PINS.forEach { (pin, why) ->
                    assertTrue(
                        system.contains(pin),
                        "/$command is never told \"$pin\". $why\n\n$system",
                    )
                }
            }
        }

    @Test
    fun `the shapes whose answer is its findings cite an absence on all of what they read`(): Unit =
        runBlocking {
            // **Only the two that are asked for an absence as a claim line at all**, which is why
            // this is separate from the case above rather than another assertion inside it. The
            // other three state an absence in prose, where there is nothing to cite and nothing
            // this could ask of them.
            //
            // **It stays a claim line for those two, and the wording rule is what changed.** The
            // runner refuses a reply from one of these shapes when no variant carries a claim, so a
            // reply whose whole answer is that nothing was found has to assert it as a claim or be
            // rejected, retried and answered as *nothing to answer from* — the one outcome an
            // honest statement of absence must not degrade to.
            //
            // Asked for all of it rather than some, because a reader judging whether an absence
            // follows can only do that against everything it was looked for in. Asked only for
            // the material it was read against, a live run rests it on a handful of the pieces;
            // the read is in the pull request.
            //
            // **Both directions, because one of them is the defect this paragraph would be.** A
            // shape whose deliverable is text the model composed — a message somebody sends, a
            // report on the open web, a question the corpus may hold nothing about — is refused
            // nothing for asserting nothing, so telling it to write an absence as a cited claim
            // line is the rule the claims-only scope exists to prevent. Asserted as a map over all
            // five rather than as a loop over the two, so a paragraph that leaked to the other
            // three reddens here rather than passing for being present where it was wanted.
            //
            // Read against the same map the declaration itself is read against, rather than a
            // second list of which shapes these are: two spellings of one fact agree only until
            // somebody edits one.
            val prompts = mutableMapOf<String, String>()
            ASSERTING.keys.forEach { command ->
                val shape = registry().bind(Request(text = "x", command = command)).shape
                val assemble = shape.stages.filterIsInstance<Assemble>().single()
                val assembled = assertIs<Progress.Carried>(assemble.apply(Exchange(Request(text = "x"))))
                prompts[command] = flattened(assembled.exchange.assembled?.system.orEmpty())
            }

            // Each pin on its own, so a failure names which half of the paragraph went rather than
            // only that the paragraph did.
            ABSENCE_CITED.forEach { (pin, why) ->
                assertEquals(
                    ASSERTING,
                    ASSERTING.keys.associateWith { prompts.getValue(it).contains(pin) },
                    "Which shapes are told \"$pin\" no longer matches which shapes' answers are " +
                        "their findings. $why",
                )
            }
        }

    private companion object {

        /**
         * Which shapes' answers are the assertions they cite, by the command each is bound by.
         *
         * The rest deliver text the model composed — a message to send, a report on the open web,
         * an answer to a question the corpus may hold nothing about — and a reply from one of those
         * carrying no assertion is complete rather than unchecked.
         *
         * Hand-maintained, like the sectioning map below it: a sixth shape omitted from both is
         * checked by nothing here, because the registry offers no way to enumerate what is
         * registered and adding one for a test would put the seam in production source. Every case
         * in this file carries that residue.
         */
        val ASSERTING = mapOf(
            "status" to true,
            "prepare" to true,
            "draft" to false,
            "research" to false,
            "ask" to false,
        )

        /**
         * The clause telling a shape that a statement about the material is one of its assertions.
         *
         * A form of words, knowingly: no mechanical reading tells a sentence that requires a claim
         * line here from one that permits prose instead, and the rule and its reversal are written
         * with the same words. `ReplyTest` pins it against the format directly, for the wording
         * itself; what this adds is which shapes are actually sent it.
         */
        const val ASSERTS = "a statement about the material is one of them"

        /**
         * Each half of the rule bounding what an absence may say, and what that half costs.
         *
         * **Each half is pinned rather than the block, because each alone is a defect.** Without
         * the premise the scoping is a style preference. Without the replacement beside the
         * prohibition a model reaches for the nearest form outside it — which is how the sibling
         * rule in this same format failed twice — and here it has a worse escape than a synonym,
         * since the same sentence written as prose is marked by nothing and read by nothing.
         * Without the forbidden form in the words the mistake is made in, a rule given in the
         * abstract is obeyed in the abstract. And without the last, a correctly scoped sentence
         * still tells the reader to stop looking, which is the damage out of all proportion to the
         * rate.
         *
         * Every one is held to occurring at most once in the prompt, which is what makes a
         * `contains` mean this block rather than whatever else came to say the same words.
         */
        val ABSENCE_PINS = mapOf(
            "it can never establish that something was never said" to
                "So what it holds is not said to be unable to tell an absent thing from an " +
                "unretrieved one, and the scoping reads as a preference about wording.",
            "never about the records as a whole" to
                "So a statement of absence is not scoped to the shape's own material, which is " +
                "the whole of the defect.",
            "Write that nothing in what you were given says it" to
                "So it is told what not to assert and not what to write instead, which leaves " +
                "the sentence available as unmarked prose.",
            "not that the records hold nothing on it" to
                "So the corpus-level wording it must not use is never named, and the rule never " +
                "shows the form the defect took.",
            "Say once what you did look at, so the scope the statement was made in" to
                "So the scope of an absence is not made visible, and a reader cannot judge what " +
                "the statement was made against.",
            "finding nothing there is worth stating plainly" to
                "So an absence on the open web is forbidden along with the rest, and the research " +
                "shape is told to report plainly that it found nothing and told not to in the " +
                "same prompt. That absence is the one this rule's premise does not reach: the " +
                "shape ran the search and chose the terms.",
            "no outcome recorded after it is a fact about that undertaking rather than a statement" to
                "So the prohibition is shared without the carve-out the fallback already wrote " +
                "against its own wording, and it forbids the very sentence the rule about an " +
                "unrecorded outcome requires — two paragraphs of one prompt asking for opposite " +
                "things.",
            "a reply you draft is your own voice, written to be read by the other party" to
                "So the drafting shape is told to put the scope of an absence inside the message " +
                "its sender posts to the counterpart — the one output that has to be sendable " +
                "unedited. Every sibling rule in this block carves the draft out, and this is the " +
                "route back to a draft nobody can send as written.",
            "is not a finding that the thing does not exist" to
                "So it is told how to word an absence and not that it settles nothing, and a " +
                "correctly scoped sentence still tells the reader to stop looking.",
        )

        /**
         * The three halves of the paragraph asking for an absence as a claim line, and what each
         * one being absent costs.
         *
         * Held as a map so a failure names which half went. Each is matched against the assembled
         * prompt rather than against [Reply.format]'s text, and each is held to occurring at most
         * once in it — a fragment two blocks share is a pin satisfied by prose it does not own.
         */
        val ABSENCE_CITED = mapOf(
            "cite every piece of material you read it against" to
                "A shape whose answer is its findings then rests an absence on a handful of " +
                "pieces, and a reader is shown an absence over material they never saw, which " +
                "is what a live run does without this.",
            "Every piece means all of them and not the ones that came closest" to
                "So the requirement is stated once and in the abstract, and a model writing " +
                "briefly reads it as the pieces that bear on the question. Measured: without " +
                "this the absence rests on a few of the pieces even with the requirement above " +
                "it; with it, on the whole of what was read.",
            "an absence is only as checkable as the material" to
                "The requirement is then a quota rather than something the answer depends on, " +
                "and a model writing briefly trims a quota.",
            "holds nothing about what was asked: write it as a claim line" to
                "A reply whose only answer is that nothing was found then asserts nothing, which " +
                "the runner rejects whole — so the honest statement of an absence degrades to " +
                "nothing to answer from, the one outcome it must not become.",
        )

        /**
         * The words a question about what was agreed is asked in.
         *
         * Exactly one shape's description may carry any of them — the one that reads commitments
         * and the verdict on whether anything discharged them. Stemmed where a stem does the work
         * (`undertak` covers both the verb and the noun) so that a legitimate inflection does not
         * read as a different word.
         */
        val AGREEMENT_WORDS = listOf("agree", "decided", "promised", "committed", "undertak")

        /** Runs of whitespace as one space, so a quoted sentence survives the prompt's wrapping. */
        val WHITESPACE = Regex("\\s+")

        /**
         * How far apart two adjacent paragraphs may sit once the prompt is flattened.
         *
         * One space is what a paragraph break collapses to, so anything beyond a couple of
         * characters is a block somebody inserted between them. Not zero, because the flattening
         * leaves the separator behind.
         */
        const val PARAGRAPH_GAP = 2

        fun flattened(text: String): String = text.replace(WHITESPACE, " ")

        /** Which shapes answer in sections, by the command each is bound by. */
        val EXPECTED = mapOf(
            "status" to true,
            "prepare" to true,
            "ask" to true,
            "draft" to false,
            "research" to false,
        )

        /** Material from two bodies of work, which is what makes sectioning apply at all. */
        val TWO_BODIES_OF_WORK = FakeGrouping(
            mapOf(
                record("42") to GroupingKey.OfBodyOfWork("One Body Of Work"),
                record("43") to GroupingKey.OfBodyOfWork("Another Body Of Work"),
            ),
        )

        fun record(nativeId: String): ExternalId = ExternalId(Source.TELEGRAM, nativeId)

        fun span(nativeId: String): Span = Span(
            id = SpanId(RecordKind.MESSAGE, record(nativeId)),
            text = "something was said",
            sourceTime = Instant.parse("2026-10-01T09:00:00Z"),
        )

        fun registry(
            grouping: Grouping = FakeGrouping(),
            retrieval: FakeRetrieval = FakeRetrieval(),
            index: FakeIndex = FakeIndex(),
        ) = shapes(
            model = FakeModelProvider(),
            retrieval = retrieval,
            lookup = Lookup(FakeEmbeddings(), index),
            grouping = grouping,
            clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC),
            zone = ZoneOffset.UTC,
        )
    }
}
