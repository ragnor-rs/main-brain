package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Binding a request to the pipeline that will answer it.
 *
 * The registry exists so the machinery knows no shape, which makes the interesting cases the
 * ones where it must *not* improvise: an unknown command and a duplicate identifier. Falling
 * through silently on the second would make a command answer through whichever shape was
 * registered last, which is a property of list order rather than of anything anybody chose.
 */
class ShapeRegistryTest {

    @Test
    fun `an explicit command binds its shape directly`(): Unit = runBlocking {
        val status = shape("status")
        val registry = ShapeRegistry(listOf(status), fallback = shape("freeform"))

        assertEquals(status.id, registry.bind(Request(text = "anything", command = "status")).shape.id)
    }

    @Test
    fun `free text takes the fallback`(): Unit = runBlocking {
        val fallback = shape("freeform")
        val registry = ShapeRegistry(listOf(shape("status")), fallback)

        assertEquals(fallback.id, registry.bind(Request(text = "where did we leave off")).shape.id)
    }

    @Test
    fun `a command this build does not know takes the fallback rather than refusing`(): Unit = runBlocking {
        // Nothing here refuses a request — a shape has to answer whatever reaches this far — so the
        // fallback answering the text as written is what binding can do with a word it does not
        // know. What keeps that from being how a typo is answered is `answerable`, read by a surface
        // while there is still a caller to refuse.
        val fallback = shape("freeform")
        val registry = ShapeRegistry(listOf(shape("status")), fallback)

        assertEquals(fallback.id, registry.bind(Request(text = "do a thing", command = "nonsense")).shape.id)
    }

    @Test
    fun `what is answerable is every registered identifier and nothing else`() {
        // Equality rather than containment, because each half rots alone: short of the whole set, a
        // surface reading this refuses a command that binds perfectly well and the shape becomes
        // unreachable; beyond it, the word it lets through is answered through the fallback at the
        // cost reading this at all exists to save.
        //
        // **The fallback is in it**, which is the distinction the contract's `Command` draws and
        // this must not. A word naming the fallback asks for something this build answers, so it is
        // not the caller's mistake — only a word naming nothing at all is. A surface refusing it
        // would make the shape the evaluation set binds by name unreachable.
        val registry = ShapeRegistry(listOf(shape("status"), shape("draft")), shape("freeform"))

        assertEquals(setOf("status", "draft", "freeform"), registry.answerable)
        listOf("stats", "statuss", "STATUS", "", "   ").forEach { unknown ->
            assertFalse(unknown in registry.answerable, "$unknown names no shape and is answerable.")
        }
    }

    @Test
    fun `a blank command is no command`(): Unit = runBlocking {
        val fallback = shape("freeform")
        val registry = ShapeRegistry(listOf(shape("status")), fallback)

        assertEquals(fallback.id, registry.bind(Request(text = "a question", command = "   ")).shape.id)
    }

    @Test
    fun `the fallback is bindable by name like any other shape`(): Unit = runBlocking {
        val fallback = shape("freeform")
        val registry = ShapeRegistry(listOf(shape("status")), fallback)

        assertEquals(fallback.id, registry.bind(Request(text = "a question", command = "freeform")).shape.id)
    }

    @Test
    fun `free text is classified into the shape the classifier names`(): Unit = runBlocking {
        val status = shape("status")
        val registry = ShapeRegistry(
            shapes = listOf(status, shape("draft")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = "status"),
        )

        assertEquals(status.id, registry.bind(Request(text = "where did we leave off")).shape.id)
    }

    @Test
    fun `the subject the classifier read out of the text is carried`(): Unit = runBlocking {
        // Free text arrives with no name separated out: an interface carves a name out of a
        // command, where it is an argument, and cannot do it for a sentence.
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = "status", subjectName = SubjectName.Given("Acme")),
        )

        val binding = registry.bind(Request(text = "where did we leave off with Acme"))

        assertEquals(SubjectName.Given("Acme"), binding.subjectName)
    }

    @Test
    fun `a classification that never said what the text is about is carried as that`(): Unit = runBlocking {
        // Not flattened to *nobody named*, which is what let a question about a person be
        // answered for the inbox. The binding carries which of the two absences this is, so the
        // resolver can refuse rather than sweep.
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = "status", subjectName = SubjectName.Unread),
        )

        assertEquals(SubjectName.Unread, registry.bind(Request(text = "who is Acme")).subjectName)
    }

    @Test
    fun `a subject named for text that could not be routed is carried too`(): Unit = runBlocking {
        // The fallback accepts no subject and will not be resolved for, so it goes unused — but
        // discarding it here would make the field depend on which shape won.
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = null, subjectName = SubjectName.Given("Acme")),
        )

        assertEquals(
            SubjectName.Given("Acme"),
            registry.bind(Request(text = "anything about Acme")).subjectName,
        )
    }

    @Test
    fun `a command binds with no subject of its own, because nothing classified it`(): Unit = runBlocking {
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = "draft", subjectName = SubjectName.Given("Somewhere Else")),
        )

        // The name such a request has was separated out by the interface and is on the request
        // itself; nothing here reads it, and the classifier was never called.
        //
        // **Nobody named rather than nothing established**, which is the deliberate half of the
        // distinction: a command carrying no argument has genuinely named nobody, so a shape
        // accepting the inbox still answers one. The runner takes the request's own name over
        // this, so what this value settles is the bare-command case.
        val binding = registry.bind(Request(text = "/status Acme", command = "status", subjectName = "Acme"))

        assertEquals(SubjectName.None, binding.subjectName)
    }

    @Test
    fun `text the classifier cannot place takes the fallback`(): Unit = runBlocking {
        val fallback = shape("freeform")
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = fallback,
            classifier = FakeClassifier(names = null),
        )

        assertEquals(fallback.id, registry.bind(Request(text = "what is the capital of Chile")).shape.id)
    }

    @Test
    fun `a classifier naming a shape that is not registered takes the fallback`(): Unit = runBlocking {
        // A verdict for something the registry does not hold was invented rather than chosen, and
        // the nearest registered identifier is not what the model meant.
        val fallback = shape("freeform")
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = fallback,
            classifier = FakeClassifier(names = "offer"),
        )

        assertEquals(fallback.id, registry.bind(Request(text = "price this up")).shape.id)
    }

    @Test
    fun `the fallback is never offered as a candidate to classify into`(): Unit = runBlocking {
        // Offering it would let the model pick what an unclassified request gets anyway, and a
        // verdict naming it could not be told from no verdict at all.
        val classifier = FakeClassifier(names = null)
        val registry = ShapeRegistry(
            shapes = listOf(shape("status"), shape("draft")),
            fallback = shape("freeform"),
            classifier = classifier,
        )

        registry.bind(Request(text = "anything"))

        assertEquals(listOf(ShapeId("status"), ShapeId("draft")), classifier.offered?.map { it.id })
    }

    @Test
    fun `each candidate is offered with the line saying what it answers`(): Unit = runBlocking {
        // The identifier alone underdetermines the choice — one word standing for everything a
        // pipeline covers — so the description travels with it rather than being inferred from it.
        val classifier = FakeClassifier(names = null)
        val registry = ShapeRegistry(
            shapes = listOf(shape("status", describedAs = "what was agreed, and whether it was done")),
            fallback = shape("freeform"),
            classifier = classifier,
        )

        registry.bind(Request(text = "what did we agree"))

        assertEquals(
            listOf(Classifier.Candidate(ShapeId("status"), "what was agreed, and whether it was done")),
            classifier.offered,
        )
    }

    @Test
    fun `a classifiable shape that describes nothing is refused at construction`() {
        // A candidate offered with nothing beside its identifier is one the router can reach only
        // by guessing at its name, which is the failure the field exists to prevent.
        assertFailsWith<IllegalArgumentException> {
            ShapeRegistry(listOf(shape("status", describedAs = "  ")), fallback = shape("freeform"))
        }
    }

    @Test
    fun `a description broken over two lines is refused at construction`() {
        // The worse half of the contract and the one no reader would see: the prompt interpolates
        // this verbatim, one candidate per line, so a line break offers the model a candidate that
        // does not exist — and a reply naming it classifies to nothing.
        assertFailsWith<IllegalArgumentException> {
            ShapeRegistry(
                listOf(shape("status", describedAs = "where things stand\nand a second candidate")),
                fallback = shape("freeform"),
            )
        }
    }

    @Test
    fun `the fallback describing nothing is accepted, because nothing is ever shown it`(): Unit = runBlocking {
        // Scoped to where a blank line has a consequence. The fallback is not a candidate, so its
        // description reaches no prompt, and refusing it here would be a rule measured elsewhere.
        val fallback = shape("freeform", describedAs = "")
        val registry = ShapeRegistry(listOf(shape("status")), fallback = fallback)

        assertEquals(fallback.id, registry.bind(Request(text = "anything")).shape.id)
    }

    @Test
    fun `a command is not classified, and costs nothing`(): Unit = runBlocking {
        val classifier = FakeClassifier(names = "draft")
        val registry = ShapeRegistry(
            shapes = listOf(shape("status"), shape("draft")),
            fallback = shape("freeform"),
            classifier = classifier,
        )

        val binding = registry.bind(Request(text = "anything", command = "status"))

        assertEquals(ShapeId("status"), binding.shape.id)
        assertEquals(TokenUsage.NONE, binding.usage)
        assertNull(classifier.offered)
    }

    @Test
    fun `an unknown command is not classified either`(): Unit = runBlocking {
        // Somebody typing a command meant to name a pipeline, not to have their words read as prose.
        val fallback = shape("freeform")
        val classifier = FakeClassifier(names = "draft")
        val registry = ShapeRegistry(listOf(shape("draft")), fallback, classifier)

        assertEquals(fallback.id, registry.bind(Request(text = "mistyped", command = "drafts")).shape.id)
        assertNull(classifier.offered)
    }

    @Test
    fun `what classification cost is carried back so the request can be charged for it`(): Unit = runBlocking {
        val spent = TokenUsage(inputTokens = 120, outputTokens = 7)
        val registry = ShapeRegistry(
            shapes = listOf(shape("status")),
            fallback = shape("freeform"),
            classifier = FakeClassifier(names = "status", usage = spent),
        )

        assertEquals(spent, registry.bind(Request(text = "where are we")).usage)
    }

    @Test
    fun `with no classifier free text still answers, through the fallback`(): Unit = runBlocking {
        // An interface that only ever sends commands spends nothing on a classifier it would never
        // consult, so absent is a working configuration rather than a degraded one.
        val fallback = shape("freeform")
        val registry = ShapeRegistry(listOf(shape("status")), fallback)

        val binding = registry.bind(Request(text = "a general question"))

        assertEquals(fallback.id, binding.shape.id)
        assertEquals(TokenUsage.NONE, binding.usage)
        // **And it establishes nothing about the subject, rather than reporting nobody.** Nothing
        // read the text. The fallback this build registers accepts no subject and is never
        // resolved for, so today the value goes unused either way — but nothing here holds a
        // fallback to that, and one accepting the inbox would then answer corpus-wide for text
        // nothing had read, which is the defect this card is about reached through configuration.
        assertEquals(SubjectName.Unread, binding.subjectName)
    }

    @Test
    fun `two shapes sharing an identifier are refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            ShapeRegistry(listOf(shape("status"), shape("status")), fallback = shape("freeform"))
        }
    }

    @Test
    fun `a shape sharing the fallback's identifier is refused too`() {
        assertFailsWith<IllegalArgumentException> {
            ShapeRegistry(listOf(shape("freeform")), fallback = shape("freeform"))
        }
    }

    @Test
    fun `a blank identifier names nothing`() {
        assertFailsWith<IllegalArgumentException> { ShapeId("  ") }
    }

    // --- doubles and fixtures -------------------------------------------------------------

    /**
     * A classifier that returns what it was told to, and records what it was offered.
     *
     * [offered] is `null` until it is consulted, which is what distinguishes *not called* from
     * *called and found nothing* — the difference between a command binding directly and free text
     * that could not be placed.
     */
    private class FakeClassifier(
        private val names: String?,
        private val subjectName: SubjectName = SubjectName.Unread,
        private val usage: TokenUsage = TokenUsage.NONE,
    ) : Classifier {

        var offered: List<Classifier.Candidate>? = null

        override suspend fun classify(text: String, among: List<Classifier.Candidate>): Classifier.Verdict {
            offered = among
            return Classifier.Verdict(
                shape = names?.let { ShapeId(it) },
                subjectName = subjectName,
                usage = usage,
            )
        }
    }

    private companion object {

        /**
         * A shape that is nothing but its identifier and the line saying what it answers: binding
         * reads no other field.
         *
         * [describedAs] defaults to something non-blank because every case but one is about which
         * shape was chosen rather than about what the router was told; the case that refuses a
         * blank line passes one.
         */
        fun shape(id: String, describedAs: String = "what $id answers"): Shape = object : Shape {
            override val id = ShapeId(id)
            override val description = describedAs
            override val accepts = emptySet<SubjectKind>()
            override val variants = 1..1
            override val readsCorpus = true
            override val assertsFindings = false
            override val stages = emptyList<Stage>()
        }
    }
}
