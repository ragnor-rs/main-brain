package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.Command
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import io.github.ragnorrs.mainbrain.domain.Collected
import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.GroupingKey
import io.github.ragnorrs.mainbrain.domain.answer.Horizon
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Reach
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeRegistry
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.answer.shape.shapes
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.index.Hit
import io.github.ragnorrs.mainbrain.domain.index.IndexEntry
import io.github.ragnorrs.mainbrain.domain.index.IndexRepository
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind as DomainSubjectKind

/**
 * Every declared command is answered, at exactly the scope it is declared with.
 *
 * **Over the registry this build ships, not one this file invented.** `shapes()` is the production
 * assembly, and the identifiers and `accepts` it produces do not depend on the ports handed to it —
 * so stubbing those ports yields the real command vocabulary, hermetically, with no store, no
 * credential and no container. A test built around a list written out here would go on passing a
 * shape renamed in step with it, which is the one thing this has to catch.
 *
 * **Asked through [ShapeRegistry.bind], which is how a command reaches a shape in production.**
 * Binding a command is a map lookup that spends nothing — no classifier, no model — so this needs
 * none of the machinery an answer needs. It also means nothing was added to the registry for a
 * test's benefit: the enumeration seam `ShapesTest` records as the price of closing its own
 * hand-maintained map is still unpaid, and that deferral stands untouched.
 *
 * **The scope check is set equality, so it catches both directions at once** — a kind the shape
 * accepts and the declaration omits, which is the drift this card was filed for, and a kind the
 * declaration claims and the shape refuses, which would offer a reader a scope that then fails.
 * There is no third direction and no untested one. That both are really caught was measured by
 * breaking each in turn and watching this go red; it is not inferred from the shape of the
 * assertion.
 *
 * **What is deliberately not checked is a registered shape nothing declares.** Requiring one would
 * close the open registry, which three places record as a chosen property: a shape arrives as a
 * package and a line in `shapes()`, with no edit to the runner, the resolver or the check. A sixth
 * shape nobody has declared is not yet offered in a command list, which is a decision somebody
 * takes rather than a drift that happened to them.
 *
 * **Driven with `runBlocking` rather than `runTest`, which is not a style choice.** `bind` is
 * `suspend` and nothing here wants virtual time, and `runTest` additionally refuses to start when
 * anything in the same JVM recorded an uncaught exception before it. One case in this module raises
 * an `Error` out of a coroutine on purpose — proving that a pass letting one out does not take its
 * sibling down — so these were the cases that failed, carrying that `Error` as a suppressed cause
 * and naming a defect in neither suite. Whichever case the runner happened to schedule next was the
 * victim, so it reddened on some runs and not others.
 */
class DeclaredCommandsTest {

    @Test
    fun `every declared command binds its own shape`(): Unit = runBlocking {
        assertFalse(Command.entries.isEmpty(), "This asserts nothing unless some command is declared.")
        Command.entries.forEach { command ->
            // The fallback answers an identifier the registry does not hold, so a renamed shape
            // arrives here as the fallback rather than as a failure to bind.
            assertEquals(
                command.word,
                command.boundShape().id.value,
                "/${command.word} is declared on the wire and no shape answers it. Every " +
                    "interface offers this command, so it is one a reader is invited to type and " +
                    "nothing replies to.",
            )
        }
    }

    @Test
    fun `every declared command accepts exactly what it declares`(): Unit = runBlocking {
        assertFalse(Command.entries.isEmpty(), "This asserts nothing unless some command is declared.")
        Command.entries.forEach { command ->
            val accepted = command.boundShape().accepts
            assertEquals(
                command.accepts,
                accepted.mapTo(mutableSetOf()) { it.toWire() },
                "/${command.word} declares it accepts ${command.accepts} while its shape accepts " +
                    "$accepted. The declaration is what a command list is held to, so the wider of " +
                    "the two is a kind offered and then refused, and the narrower is a kind that " +
                    "works and is never mentioned.",
            )
        }
    }

    @Test
    fun `the fallback is reachable by no declared command`(): Unit = runBlocking {
        // Free text with no command takes the fallback, and no classifier is wired here. That it is
        // reachable at all is why `Command` says *no command list offers it* rather than *nothing
        // names it*: the registry resolves its identifier like any other.
        val fallback = production().bind(Request(text = "a general question")).shape
        assertFalse(
            Command.entries.any { it.word == fallback.id.value },
            "The fallback ${fallback.id.value} is declared as a command. It is what an " +
                "unclassified request already gets, so offering it in a list invites a reader to " +
                "ask for the default.",
        )
    }

    private suspend fun Command.boundShape(): Shape =
        production().bind(Request(text = "/$word", command = word)).shape

    /**
     * The registry this build ships.
     *
     * Every port is a stub that fails if it is touched: binding a command reads a shape's
     * declaration and never runs one, so a stub reached at all means this test has quietly started
     * doing something else.
     */
    private fun production(): ShapeRegistry = shapes(
        model = NoModel,
        retrieval = NoRetrieval,
        lookup = Lookup(NoEmbeddings, NoIndex),
        grouping = NoGrouping,
        clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC),
        zone = ZoneOffset.UTC,
    )

    /**
     * The wire's name for a kind the model named.
     *
     * Its own copy rather than the mapper in `Outcomes.kt`, which is private to that file.
     * Exhaustive, so a kind added on either side stops this compiling.
     */
    private fun DomainSubjectKind.toWire(): SubjectKind = when (this) {
        DomainSubjectKind.CONTACT -> SubjectKind.CONTACT
        DomainSubjectKind.CLIENT -> SubjectKind.CLIENT
        DomainSubjectKind.BODY_OF_WORK -> SubjectKind.BODY_OF_WORK
        DomainSubjectKind.CONVERSATION -> SubjectKind.CONVERSATION
        DomainSubjectKind.INBOX -> SubjectKind.INBOX
    }

    private object NoModel : ModelProvider {
        override fun version(): String = unreached()
        override suspend fun respond(request: ModelRequest): ModelOutcome = unreached()
        override suspend fun submit(requests: Map<String, ModelRequest>): SubmissionId = unreached()
        override suspend fun collect(submission: SubmissionId): Collected = unreached()
    }

    private object NoRetrieval : Retrieval {
        override suspend fun conversationsOf(subject: Subject, threads: Int) = unreached()
        override suspend fun recentMessages(conversations: List<ExternalId>, perConversation: Int) = unreached()
        override suspend fun commitmentsOf(reach: Reach, limit: Int) = unreached()
        override suspend fun eventsAhead(reach: Reach, horizon: Horizon, limit: Int) = unreached()
        override suspend fun eventsPast(reach: Reach, horizon: Horizon, limit: Int) = unreached()
        override suspend fun eventsUndated(reach: Reach, limit: Int) = unreached()
        override suspend fun statementsOf(reach: Reach, limit: Int) = unreached()
        override suspend fun namesOf(parties: List<EntityId>) = unreached()
        override suspend fun turnsOf(
            messages: List<ExternalId>,
            subject: List<EntityId>,
        ): Map<ExternalId, Turn> = unreached()
        override suspend fun closuresOf(commitments: List<EntityId>) = unreached()
    }

    private object NoEmbeddings : EmbeddingProvider {
        override suspend fun version(): String = unreached()
        override suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding> = unreached()
    }

    private object NoIndex : IndexRepository {
        override suspend fun index(entries: List<IndexEntry>) = unreached()
        override suspend fun search(query: Embedding, limit: Int): List<Hit> = unreached()
        override suspend fun neighbours(span: SpanId, before: Int, after: Int): List<Span> = unreached()
    }

    private object NoGrouping : Grouping {
        override suspend fun of(records: List<ExternalId>): Map<ExternalId, GroupingKey> = unreached()
    }

    private companion object {

        fun unreached(): Nothing =
            error("Binding a command reads a shape's declaration. Nothing here should be called.")
    }
}
