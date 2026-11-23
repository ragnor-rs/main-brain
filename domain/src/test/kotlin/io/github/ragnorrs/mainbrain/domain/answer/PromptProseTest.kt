package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.shape.shapes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prompt prose, read against what the evaluation set requires an answer to contain.
 *
 * **This is the first tier to read the prompt against the fixture**, which is the whole of what is
 * new about it. [ReplyTest] already reads model-facing prose — most of its assertions about the
 * format match a substring of it — and `detekt` reads style while `:archtest` reads which alphabets
 * appear. The
 * evaluation set reads prose for meaning, but it is asked for rather than automatic, is the only
 * tier that spends the model budget, and scores an answer rather than the instruction that produced
 * it: so a prompt can forbid the very figure a case requires, score zero on answering while
 * retrieval and grounding stay green, and read as a regression in the answer path.
 *
 * **What the three cases ask.** A prohibition carries an extent as well as a wording — see
 * [Reply.Forbidden] — so two things can be settled mechanically: that nothing declared forbidden
 * covers a string a case requires its answer to contain, and that every such prohibition is still
 * stated in the prompt it governs. The first catches a declared extent widened past what an answer
 * needs; the second catches a declared clause dropped or reworded, which a pattern alone cannot see.
 * The third is not a prohibition check at all but a positive pin on the admission that figures are
 * ordinary prose, and it shares that duty with a sibling in [ReplyTest].
 *
 * **It would not have caught the defect it was built for, and that is worth knowing here rather
 * than finding out.** The sentence that nearly shipped widened a prohibition in prose while leaving
 * the declared clause present and the declared pattern untouched. Reinstated today, the first two
 * cases stay green. What reddens is the admission — the third case here, and its sibling in
 * [ReplyTest], which is the one that predates this branch and so the one that would have caught it.
 *
 * **What it cannot do.** It reads prohibitions that were declared, so a sentence written straight
 * into [Reply.format] is invisible to it, as is any widening that leaves clause and pattern alone.
 * Two of the five declarations carry no extent at all, because theirs is about where a marker sits
 * and a required string arrives with no position — so for those two only the wording is held. It
 * judges a prohibition only against output some case requires, and **no string in today's fixture
 * is matched by any declared pattern** — every value a case requires is a figure or an organisation
 * name, and not one of them matches. So
 * the required-output case cannot go red as the fixture stands, whatever the patterns say; it is a
 * rail against a future case, and the probes in the pull request rather than this fixture are what
 * show the patterns work at all. It tests each required string on its own, so a prohibition that
 * only bites on two of them appearing together is invisible. And it says nothing about an
 * instruction wrong in some other way — a misleading explanation, a pronoun with the wrong
 * antecedent, a rule contradicting another. No hermetic check reads prose for meaning, and this one
 * does not either.
 */
class PromptProseTest {

    @Test
    fun `nothing a prompt forbids rules out what a case requires its answer to contain`() {
        // Derived from the fixture rather than listed here, so a case added to the evaluation set
        // extends this without anyone remembering to. The guard below is what stops an empty read
        // of the fixture reporting as a pass over nothing — the failure this whole file exists to
        // prevent is a gate that cannot go red.
        val required = CASES.flatMap { it.mustMention }.distinct()

        assertTrue(
            required.isNotEmpty(),
            "No evaluation case requires anything of its answer, so this asserted nothing. Either " +
                "the fixture was read wrongly or every `must_mention` has been emptied.",
        )
        assertDeclarations()
        Reply.FORBIDDEN.forEach { forbidden ->
            required.forEach { wanted ->
                assertFalse(
                    forbidden.rules(wanted),
                    "The prompt forbids `${forbidden.clause}`, and that rules out `$wanted`, which " +
                        "an evaluation case requires its answer to contain. Obeyed, the answer " +
                        "dimension fails on that case while retrieval and grounding stay green, " +
                        "which reads as a defect in the answer path rather than in this sentence. " +
                        "Narrow the prohibition to the form it is about.",
                )
            }
        }
    }

    @Test
    fun `every prohibition that carries an extent is still stated in the prompt`(): Unit = runBlocking {
        // The half a pattern cannot see. A prohibition is only worth checking against a fixture
        // while the model is actually being told it, and a clause deleted from the prose leaves a
        // declaration that passes the test above by covering nothing anybody reads.
        assertDeclarations()
        // **Every shape the registry answers is read by some case, which is the guard a count of
        // cases cannot be.** `CASES` drops a case naming no shape, because there is no prompt of
        // one to read — so a case that lost its shape to a typo would leave this iterating the rest
        // and reporting green, with the dropped case's prompt never checked again. Held as the set
        // of shapes rather than the set of cases, so it also catches the reverse: a shape shipped
        // with no case in the set at all has no prompt read here and none scored anywhere.
        assertEquals(
            registry().answerable,
            CASES.map { it.shape }.toSet(),
            "The shapes the evaluation set names and the shapes the registry answers are not the " +
                "same set. A case whose `shape` went missing is dropped from this file silently, " +
                "and a shape no case names is read by nothing.",
        )
        CASES.forEach { case ->
            val prompt = flattened(system(case))

            Reply.FORBIDDEN.forEach { forbidden ->
                assertTrue(
                    prompt.contains(forbidden.clause, ignoreCase = true),
                    "The prompt for `${case.id}` never states `${forbidden.clause}`, which is " +
                        "declared as something an answer may not contain. Either the wording moved " +
                        "and the declaration did not, or the rule was dropped and its extent left " +
                        "behind to be checked against nothing.",
                )
            }
        }
    }

    @Test
    fun `a shape asked for a figure is told that figures are ordinary prose`(): Unit = runBlocking {
        // **Not the only guard on that admission, and deliberately the weaker-anchored half of it.**
        // `ReplyTest` already pins the sentence against the format directly, for the defect that
        // prompted all of this: a fix pass once told the model that a number is a citation only after
        // the cite marker and never to write one in the prose itself, as a bracketed reference or any
        // other way — meaning citations, but *one* takes *a number* as its nearest antecedent and
        // *any other way* closes the escape. What repaired it was admitting the figure explicitly
        // rather than dropping the prohibition.
        //
        // What this adds is the link to the fixture: which cases actually need a figure, and which
        // figures, named in the failure. So it is anchored on the same short fragment its sibling
        // uses rather than on the whole sentence — anchoring on the sentence would redden both cases
        // for a rewording that changed nothing, and two reds for one edit teach the reader to
        // discount them.
        //
        // Anchored on a form of words at all, which is the weaker kind of assertion, knowingly: no
        // mechanical reading tells a sentence that permits a figure from one that forbids it. It
        // catches the admission being deleted; it cannot catch a prohibition elsewhere overriding it.
        val needFigures = CASES.filter { case -> case.mustMention.any { it.any(Char::isDigit) } }

        assertTrue(
            needFigures.isNotEmpty(),
            "No evaluation case requires a figure in its answer, so the admission this pins is " +
                "unexercised and the test asserted nothing.",
        )
        needFigures.forEach { case ->
            assertTrue(
                flattened(system(case)).contains(ADMITS_FIGURES, ignoreCase = true),
                "The prompt for `${case.id}` requires ${case.mustMention} in its answer and no " +
                    "longer states that figures which are part of what is being said are ordinary " +
                    "prose. Without it the citation rule is the only thing the model reads about " +
                    "writing a number, and read strictly that rule forbids every one of them.",
            )
        }
    }

    private companion object {

        /**
         * Where the evaluation fixture is, handed over by the build.
         *
         * Declared as a task input there as well, or this is up to date the moment it has passed
         * once and a case edited into conflict with the prompt is never read again.
         */
        const val CASES_PROPERTY = "mainbrain.evalCases"

        /**
         * The fragment admitting a figure into prose, as the prompt states it.
         *
         * The same short phrase its sibling in `ReplyTest` matches, rather than the whole sentence:
         * what has to hold is that the admission is there, and a rewording of the examples around it
         * is not the defect either case exists to catch. Held here rather than in [Reply] and
         * deliberately not interpolated into the format — the prompt's bytes are a cache prefix and
         * the evaluation set scores what they produced, so a check arriving beside the prose is worth
         * having without rewriting the prose to get it.
         */
        const val ADMITS_FIGURES = "are ordinary prose"

        /**
         * That there is anything declared to check at all.
         *
         * **Both of the prohibition cases iterate [Reply.FORBIDDEN], so emptying it left them
         * asserting nothing** — the two guards already here covered the fixture rather than the list
         * under test. The third case was green on its own merits, since it reads the admission and
         * never touches the list, which is why this is called from two places and not three. That is
         * the shape the rest of this file exists to prevent, so it was reachable from inside it.
         * Measured rather than reasoned about: with the list emptied, all three reported green.
         *
         * **The second half is there because a prohibition may now declare no extent**, which is the
         * right answer for one about position rather than content. The cost of allowing it is that
         * every declaration losing its pattern would leave the required-output case iterating
         * entries that rule out nothing, which is the same vacuous green by another route.
         */
        fun assertDeclarations() {
            assertTrue(
                Reply.FORBIDDEN.isNotEmpty(),
                "Nothing is declared forbidden, so every prohibition case below iterates an empty " +
                    "list and passes without asserting anything. The gate is off, not clean.",
            )
            assertTrue(
                Reply.FORBIDDEN.any { it.declaresExtent },
                "No declaration claims an extent, so the required-output case cannot fail whatever " +
                    "the fixture asks for. A positional prohibition carries none by design, but " +
                    "not all of them can be that.",
            )
            Reply.FORBIDDEN.forEach { forbidden ->
                // **A blank clause is the one that matters, because `contains("")` is true of every
                // string.** The clause case would then pass for any prompt at all — an assertion
                // that cannot fail, inside the file written to stop exactly that.
                assertTrue(
                    forbidden.clause.isNotBlank(),
                    "A prohibition declares a blank clause, so the case asserting it is still " +
                        "stated passes against any prompt whatsoever.",
                )
                // And a declared extent that matches nothing is the same hole on the other case:
                // indistinguishable from a positional rule through `rules` alone, which is why
                // the declaration is asked directly.
                assertTrue(
                    !forbidden.declaresExtent || forbidden.rules(EVERY_FORBIDDEN_FORM),
                    "`${forbidden.clause}` claims an extent that rules out none of the forms the " +
                        "declarations are about, so the required-output case can never fail on it. " +
                        "Either the pattern is broken or the prohibition is positional and should " +
                        "declare no extent at all.",
                )
            }
        }

        /**
         * A string carrying every form a declaration with an extent is about.
         *
         * Used to establish that such a declaration still rules something out. One string rather
         * than an example per entry, so adding a prohibition does not mean adding a fixture here —
         * the cost is that it has to be extended when a declaration is about a form it does not
         * carry, which its own failure message says.
         */
        const val EVERY_FORBIDDEN_FORM = "a `backtick`, a [7] reference, and a ``` fence"

        /** Runs of whitespace as one space, so a re-wrapped paragraph still reads as its sentence. */
        val WHITESPACE = Regex("\\s+")

        fun flattened(text: String): String = text.replace(WHITESPACE, " ")

        /**
         * The evaluation cases, read at run time.
         *
         * Read rather than restated: a case's question is in the corpus language, which no Kotlin
         * source here carries. That is a convention at this point and not a rail — `:archtest`'s
         * alphabet rule reads `src/main` only, so it would not see this file — so it is checked by
         * hand over the changed files instead. Parsed as a tree rather than into declared types,
         * which keeps the serialization compiler plugin out of a module whose own rule is that no
         * library reaches it.
         */
        val CASES: List<Case> by lazy {
            val path = requireNotNull(System.getProperty(CASES_PROPERTY)) {
                "$CASES_PROPERTY is unset, so the evaluation fixture cannot be found. The build " +
                    "passes it; running this test outside Gradle will not."
            }
            val root = Json.parseToJsonElement(File(path).readText()).jsonObject
            // **A case naming no shape is not one of these.** Its subject resolves to a question,
            // so the request settles in resolution and no shape is ever bound — there is no prompt
            // of it to read, and `system` below would fail trying to assert which one it was.
            // Dropped here rather than in each case, because every one of them reads a prompt.
            root.getValue("cases").jsonArray.map { it.jsonObject }.mapNotNull { case ->
                case.getValue("shape").jsonPrimitive.contentOrNull?.let { shape ->
                    Case(
                        id = case.getValue("id").jsonPrimitive.content,
                        shape = shape,
                        command = case["command"]?.jsonPrimitive?.content,
                        text = case.getValue("text").jsonPrimitive.content,
                        mustMention = case["answer"]?.jsonObject?.get("must_mention")
                            ?.jsonArray?.map { it.jsonPrimitive.content }
                            .orEmpty(),
                    )
                }
            }
        }

        /**
         * The system block the shape this case binds to would actually be sent.
         *
         * Through the registry and the shape's own assembly rather than by reading a constant: each
         * instruction is private to its shape, the format is parameterised by what the shape
         * declares — its variant range, whether it sections, whether its answer is its assertions —
         * and a case names a shape by identifier — the fallback lives
         * in `freeform/` and answers to `ask`. Matching on a package name instead would skip every
         * case bound to that one, so the binding is asserted rather than assumed.
         */
        suspend fun system(case: Case): String {
            val bound = registry().bind(Request(text = case.text, command = case.command)).shape

            assertEquals(
                ShapeId(case.shape),
                bound.id,
                "`${case.id}` names the shape `${case.shape}` and binds to `${bound.id.value}`, so " +
                    "the prompt checked here is not the one that case is scored against.",
            )
            val assemble = bound.stages.filterIsInstance<Assemble>().single()
            val carried = assemble.apply(Exchange(Request(text = case.text)))

            return requireNotNull((carried as Progress.Carried).exchange.assembled).system
        }

        fun registry() = shapes(
            model = FakeModelProvider(),
            retrieval = FakeRetrieval(),
            lookup = Lookup(FakeEmbeddings(), FakeIndex()),
            grouping = FakeGrouping(),
            clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC),
            zone = ZoneOffset.UTC,
        )
    }

    /** One evaluation case, in the part of it this file is about. */
    private data class Case(
        val id: String,
        val shape: String,
        val command: String?,
        val text: String,
        val mustMention: List<String>,
    )
}
