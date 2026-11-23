package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.FakeSubmissionRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.ClosureCandidate
import io.github.ragnorrs.mainbrain.domain.entity.ClosureRepository
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedPerson
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The closure pass: what it asks, what it does with an answer, and what it refuses to write.
 *
 * **The cases that matter are the ones where a wrong reading would settle a commitment on nothing.**
 * A reply naming a message the window never carried, a reply naming a position instead of an
 * identifier, and a call that bought no answer all have the same tempting handling — treat it as
 * *nothing closed it* — and each of them would record a verdict and stop the commitment ever being
 * examined again. So the negative half of this suite is the point of it.
 *
 * The store's half is asserted against Postgres in `ClosureStoreTest`: which commitments are owed,
 * and what stops being owed. Nothing here re-asserts that.
 */
class CloserTest {

    @Test
    fun `it asks about the promise with the material that followed it`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val closer = closer(closures = FakeClosures(listOf(candidate())), model = model)

        closer.closeBatch()

        val request = assertNotNull(model.submissions.singleOrNull()).requests.values.single()
        assertEquals(Closer.CLOSURE_SYSTEM, request.system)
        // **`shared` must stay unset**, and this is the assertion that holds it. A shared region is
        // where the adapter puts the one cache breakpoint, taking it off the instruction — which
        // pays only when sibling requests have that region in common. No two requests here do: one
        // commitment, one conversation. Set, every call would write a cache entry no later call can
        // match and pay the premium for it.
        assertNull(request.shared, "a per-commitment window as the shared region caches nothing twice")
        val prompt = request.prompt
        assertTrue(prompt.contains(CLAIM), "the promise itself is not in front of the model: $prompt")
        assertTrue(prompt.contains("sent it over"), "the material that followed is not in the window: $prompt")
        assertTrue(prompt.contains(CLOSING.nativeId), "the model has no identifier to answer with: $prompt")
        // The claim last, which is what the instruction says the last thing given is.
        assertTrue(
            prompt.lastIndexOf(CLAIM) > prompt.indexOf(CLOSING.nativeId),
            "the promise being decided about does not come last: $prompt",
        )
    }

    @Test
    fun `the key is the commitment, so a reply can be matched back to it`(): Unit = runBlocking {
        // And it is short enough to be one. A key is encoded into the provider's own identifier, and
        // a stage keying by what a request is about would be refused for every request it made —
        // which is what the fake enforces on submit.
        val model = FakeModelProvider()
        val candidate = candidate()
        val closer = closer(closures = FakeClosures(listOf(candidate)), model = model)

        closer.closeBatch()

        assertEquals(
            setOf(candidate.commitment.id.value.toString()),
            assertNotNull(model.submissions.singleOrNull()).requests.keys,
        )
    }

    @Test
    fun `a reply naming a message in the window closes the commitment`(): Unit = runBlocking {
        val closures = FakeClosures(listOf(candidate()))
        val model = FakeModelProvider().answering(CLOSING.nativeId)
        val closer = closer(closures = closures, model = model)

        closer.closeBatch()
        val did = closer.closeBatch()

        val verdict = assertNotNull(closures.recorded.singleOrNull())
        assertEquals(CLOSING, assertNotNull(verdict.closedBy).span.record)
        assertEquals(RecordKind.MESSAGE, assertNotNull(verdict.closedBy).span.kind, "a message is one span")
        assertEquals(1, did.closed)
        assertEquals(0, did.unclosed)
    }

    @Test
    fun `a reply of the refusal word records that nothing closed it`(): Unit = runBlocking {
        // The ordinary outcome, and it has to be written down: a commitment nothing closed stays a
        // candidate by every test the store can make, so without the verdict every sweep pays to
        // reach the same answer over the same material.
        val closures = FakeClosures(listOf(candidate()))
        val closer = closer(closures = closures, model = FakeModelProvider().answering(Extractor.NOTHING))

        closer.closeBatch()
        val did = closer.closeBatch()

        val verdict = assertNotNull(closures.recorded.singleOrNull())
        assertNull(verdict.closedBy)
        assertEquals(1, did.unclosed)
        assertEquals(0, did.closed)
    }

    @Test
    fun `a verdict is good only as far as the material it weighed`(): Unit = runBlocking {
        // The watermark, and why it is a message instant rather than a clock: what is recorded as
        // considered has to be exactly what was sent, or a later sweep skips material nothing read.
        val closures = FakeClosures(listOf(candidate()))
        val closer = closer(closures = closures, model = FakeModelProvider().answering(Extractor.NOTHING))

        closer.closeBatch()
        closer.closeBatch()

        val mark = assertNotNull(closures.recorded.singleOrNull()).weighed
        assertEquals(LATEST, mark.at)
        // The record as well as the instant: a source's instants are coarse, so the mark has to say
        // which of a second's messages the reading stopped at or the next sweep steps over the rest.
        assertEquals(CLOSING, mark.record)
    }

    @Test
    fun `an identifier the window never carried is no verdict at all`(): Unit = runBlocking {
        // The failure this suite exists for. A model writing an identifier it inferred rather than
        // copied is the likeliest way this stage goes wrong, and reading it as nothing-closed would
        // settle the commitment on the strength of a hallucination.
        val closures = FakeClosures(listOf(candidate()))
        val elsewhere = ExternalId(Source.TELEGRAM, "a-message-from-another-thread")
        val closer = closer(closures = closures, model = FakeModelProvider().answering(elsewhere.nativeId))

        closer.closeBatch()
        val did = closer.closeBatch()

        assertTrue(closures.recorded.isEmpty(), "nothing may be written off an identifier nobody was shown")
        assertEquals(1, did.unread)
        assertEquals(0, did.closed + did.unclosed)
    }

    @Test
    fun `a position in the window is not an identifier`(): Unit = runBlocking {
        // The instruction asks for an identifier copied from a line and forbids a position, because a
        // position is a label computed from the window and the window grows while a submission is
        // outstanding. A parser accepting one would resolve a number against whatever the window
        // holds when the reply is read.
        val closures = FakeClosures(listOf(candidate()))
        val closer = closer(closures = closures, model = FakeModelProvider().answering("2"))

        closer.closeBatch()
        val did = closer.closeBatch()

        assertTrue(closures.recorded.isEmpty(), "a position must buy no verdict")
        assertEquals(1, did.unread)
    }

    @Test
    fun `a refused comparison is a verdict and a truncated one is not`(): Unit = runBlocking {
        // Both produced no answer, and only one of them is final. A refusal is deterministic, so
        // asking again would buy the same nothing; a truncation clears by being asked again, and
        // recording it would settle the commitment for good on a call that failed.
        val refused = FakeClosures(listOf(candidate()))
        val truncated = FakeClosures(listOf(candidate()))
        val declining = FakeModelProvider().replying {
            BatchOutcome.Replied(ModelOutcome.Refused(category = "whatever", usage = TokenUsage.NONE))
        }
        val cutting = FakeModelProvider().replying {
            BatchOutcome.Replied(ModelOutcome.Truncated(partial = "", usage = TokenUsage.NONE))
        }
        val refusing = closer(closures = refused, model = declining)
        val truncating = closer(closures = truncated, model = cutting)

        refusing.closeBatch()
        val refusal = refusing.closeBatch()
        truncating.closeBatch()
        val cut = truncating.closeBatch()

        // **A refusal is settled and counted apart.** The provider's classifiers declined the
        // request, so nothing was read and no judgement about the promise was reached — reported
        // under `unclosed`, a provider refusing every window would read exactly like a corpus that
        // closes its promises in silence. It still writes a row, because a refusal of the same
        // input is deterministic and leaving none would buy the same refusal for ever.
        assertEquals(0, refusal.unclosed, "a refusal must not read as a corpus that closes nothing")
        assertEquals(1, refusal.declined.size, "a refusal is not reported at all: $refusal")
        assertTrue(
            refusal.declined.single().contains("whatever"),
            "the provider's own category is dropped, so nothing says why: ${refusal.declined}",
        )
        assertNull(assertNotNull(refused.recorded.singleOrNull()).closedBy, "a refusal settles the window")
        assertEquals(1, refusal.examined, "a settled window is one the stage examined")
        assertEquals(1, cut.unread, "a reply that ran out of room decided nothing")
        assertTrue(truncated.recorded.isEmpty())
        assertEquals(0, cut.examined, "a batch that reached no verdict examined nothing")
    }

    @Test
    fun `an empty reply is a call that bought nothing, not a verdict`(): Unit = runBlocking {
        // The instruction asks for an identifier or one word, so no word at all is a reply nobody
        // received — which is the reading the adjudication stage already takes of a blank body. Read
        // as nothing-closed it would settle the commitment for its whole window on an empty string.
        val closures = FakeClosures(listOf(candidate()))
        val closer = closer(closures = closures, model = FakeModelProvider().answering(""))

        closer.closeBatch()
        val did = closer.closeBatch()

        assertTrue(closures.recorded.isEmpty(), "an empty reply must write no verdict")
        assertEquals(1, did.unread)
        assertEquals(0, did.unclosed)
    }

    @Test
    fun `the promise's own identifier is not a legal answer`(): Unit = runBlocking {
        // It is in front of the model — the window's first line is the promise — and the
        // instruction forbids answering with it, because a promise does not close itself. Accepted,
        // it would record a commitment as discharged by the very message that made it.
        val closures = FakeClosures(listOf(candidate()))
        val closer = closer(closures = closures, model = FakeModelProvider().answering(STATING.nativeId))

        closer.closeBatch()
        val did = closer.closeBatch()

        assertTrue(closures.recorded.isEmpty(), "the promise must not close itself")
        assertEquals(1, did.unread)
    }

    @Test
    fun `the instruction holds the model to the rules the stage depends on`(): Unit = runBlocking {
        // **The prompt is the whole of the guard for three of this stage's failure modes**, and
        // nothing else in the build reads it. Quoted rather than paraphrased, because a rule and its
        // reversal are the same words in a summary.
        val system = Closer.CLOSURE_SYSTEM.replace(Regex("\\s+"), " ")

        assertTrue(
            system.contains("Never answer with the first line's identifier"),
            "nothing stops the model answering with the promise itself: $system",
        )
        assertTrue(
            system.contains("never write a position"),
            "a position would be read against whatever the window holds when the reply arrives: $system",
        )
        assertTrue(
            system.contains(
                "Answer ${Extractor.NOTHING} unless a message states that the thing promised has been done",
            ),
            "the default is not stated, so any later activity reads as discharge: $system",
        )
        // What it excludes are the lines that follow a promise most often, so a definition admitting
        // them would close almost every commitment in the corpus and report a wholly settled
        // archive. Listed rather than counted: the count is the part that goes stale.
        listOf(
            "acknowledging the promise",
            "saying it will be done",
            "starting it",
            "somebody doing something else",
            "moving on to another subject",
        ).forEach { nonClosure ->
            assertTrue(system.contains(nonClosure), "the instruction no longer excludes '$nonClosure': $system")
        }
        // **And the two shapes of closure that announce nothing**, which the guard above suppresses
        // on its own: asked only for a message *stating* that the thing was done, a reader finds
        // neither of the ways a conversation ordinarily finishes an undertaking, and both were
        // measured unrecorded against a thread that settled all of its own.
        assertTrue(
            system.contains("the thing promised, delivered"),
            "a promise to look or to check reads as open while its findings sit in the window: $system",
        )
        assertTrue(
            system.contains("handing over what was asked for is the saying"),
            "delivery still has to be announced to count, which is the defect itself: $system",
        )
        assertTrue(
            system.contains("the matter declared finished"),
            "the plainest closing signal a worked-out exchange holds is not closure: $system",
        )
        // The half that keeps the widening from costing precision. Both bounds are asserted, because
        // either one alone admits more than the corpus says.
        assertTrue(
            system.contains("a partial answer, or work described as still going on, is progress"),
            "part of the promised thing now closes it, so progress reads as discharge: $system",
        )
        assertTrue(
            system.contains("it is withdrawal and not completion"),
            "a matter dropped rather than settled now closes its promises: $system",
        )
        assertTrue(
            system.contains("answer with the first of them"),
            "nothing settles which of several closing messages to name, so two runs may differ: $system",
        )
    }

    @Test
    fun `a closure far past the promise is inside the window and does not become the mark`(): Unit = runBlocking {
        // **The defect this covers is a window that reaches the work and stops before the
        // declaration.** In a thread that works something out, the promise is followed by whatever
        // else the room is talking about, then the work reported over a run of messages, then
        // somebody saying the matter is finished — so the plainest closing signal is also the
        // furthest from the promise. Read on a thread that settled every undertaking in it, each
        // declaration sat past the bound this stage used to carry, and a window stopping short of
        // one weighs the whole exchange and misses the only line that settles anything.
        val turns = (1..Closer.DEFAULT_WINDOW).map { nth ->
            message("turn-$nth", "a line of the exchange", STATED.plusSeconds(60L * nth))
        }
        // Near the end rather than at it, so the two instants this case holds apart cannot coincide.
        val declaration = turns[turns.lastIndex - 6]
        val closures = FakeClosures(listOf(candidate(later = turns)))
        val model = FakeModelProvider().answering(declaration.externalId.nativeId)
        val closer = closer(closures = closures, model = model)

        closer.closeBatch()
        val did = closer.closeBatch()

        assertEquals(
            listOf(Closer.DEFAULT_WINDOW),
            closures.askedWindows,
            "the pass asked the store for a window other than the one its bounds carry",
        )
        val verdict = assertNotNull(closures.recorded.singleOrNull())
        assertEquals(declaration.externalId, assertNotNull(verdict.closedBy).span.record)
        assertEquals(1, did.closed)
        // **The mark is the end of the window and not the closing message**, which is earlier. Dated
        // to the closure, the verdict would claim to have weighed less than it did, and everything
        // between the closure and the end of the window would be read again by the next sweep.
        assertEquals(
            Weighed(turns.last().sourceTime, turns.last().externalId),
            verdict.weighed,
            "the watermark must be the last message weighed, not the one that closed the promise",
        )
    }

    @Test
    fun `a commitment with nothing left to weigh is settled without a call`(): Unit = runBlocking {
        // The material inside its horizon has been read. Asking anyway would be a request billed for
        // the instruction and the claim with nothing in front of them, and the answer is already
        // known: nothing in the corpus closes it.
        val closures = FakeClosures(listOf(candidate(later = emptyList())))
        val model = FakeModelProvider()
        val closer = closer(closures = closures, model = model)

        val did = closer.closeBatch()

        assertTrue(model.submissions.isEmpty(), "an empty window must not be paid for")
        val verdict = assertNotNull(closures.recorded.singleOrNull())
        assertNull(verdict.closedBy)
        assertEquals(
            Weighed(STATED, STATING),
            verdict.weighed,
            "with nothing weighed, the promise's own message stands as the mark",
        )
        assertEquals(1, did.unclosed)
    }

    @Test
    fun `with the day spent it collects what is paid for and asks nothing`(): Unit = runBlocking {
        // Not the same as doing nothing: a collection asks the provider for nothing and writes the
        // verdicts a submission already bought. Skipped, they would sit unwritten until the
        // allowance refreshed, and a submission left in flight long enough is abandoned — the work
        // lost and its cost recorded as nothing.
        val closures = FakeClosures(listOf(candidate()))
        val model = FakeModelProvider().answering(CLOSING.nativeId)
        val closer = closer(closures = closures, model = model)
        closer.closeBatch()

        val did = closer.closeBatch(maySpend = false)

        assertEquals(1, did.closed, "the reply already bought is read")
        assertEquals(1, model.submissions.size, "nothing new may be submitted with the day spent")
    }

    @Test
    fun `nothing is submitted while a submission is outstanding`(): Unit = runBlocking {
        // One slot, which the store enforces: a stage with two in flight could not say which of its
        // own work it had already paid for.
        val model = FakeModelProvider()
        val closer = closer(closures = FakeClosures(listOf(candidate())), model = model)
        closer.closeBatch()
        model.waiting += model.submissions.single().id.value

        val did = closer.closeBatch()

        assertEquals(1, model.submissions.size)
        assertEquals(1, did.awaiting, "a pass waiting on a provider is not a pass that has stalled")
    }

    @Test
    fun `a submission that will never be answered is given up on`(): Unit = runBlocking {
        // Without this, in flight and in flight for ever are the same state and the one slot is held
        // for good. Every request counts as unread rather than as a verdict: nothing was decided.
        val closures = FakeClosures(listOf(candidate()))
        val model = FakeModelProvider()
        // One submission store across the two sweeps, which is what makes the second one find what
        // the first left in flight. Two would be two passes that had never heard of each other.
        val submissions = FakeSubmissionRepository(clock = NOW)
        closer(closures = closures, model = model, submissions = submissions).closeBatch()
        model.waiting += model.submissions.single().id.value

        val did = closer(
            closures = closures,
            model = model,
            now = NOW.plus(Duration.ofDays(2)),
            submissions = submissions,
        ).closeBatch()

        assertEquals(1, did.unread)
        assertNotNull(did.abandoned, "how long it had been waiting is what says why it was given up")
        assertTrue(closures.recorded.isEmpty(), "an abandoned submission decides nothing")
    }

    @Test
    fun `the reply is read against the window that was submitted, not the one that grew`(): Unit = runBlocking {
        // The reason the window travels in the payload. Material arrives while a submission is
        // outstanding, so a window rebuilt at collection would offer identifiers the model never
        // saw — and the watermark taken from it would settle material nothing weighed.
        val closures = FakeClosures(listOf(candidate()))
        val model = FakeModelProvider().answering(CLOSING.nativeId)
        val closer = closer(closures = closures, model = model)
        closer.closeBatch()

        // The store now hands back a longer window for the same commitment, as it would once the
        // conversation moved on.
        val arrived = message("a-later-message", "and another thing", LATEST.plusSeconds(3600))
        closures.offer(
            listOf(candidate(later = listOf(statedTurn(), interimTurn(), closingTurn(), arrived))),
        )
        val did = closer.closeBatch()

        assertEquals(1, did.closed)
        assertEquals(
            LATEST,
            assertNotNull(closures.recorded.singleOrNull()).weighed.at,
            "the watermark is what was weighed, not what the window holds now",
        )
    }

    @Test
    fun `the version carries the model, so a moved model retires what it decided`(): Unit = runBlocking {
        val one = closer(closures = FakeClosures(emptyList()), model = FakeModelProvider(version = "model-one"))
        val other = closer(closures = FakeClosures(emptyList()), model = FakeModelProvider(version = "model-two"))

        assertTrue(one.version().startsWith("${Closer.DERIVATION}:"), one.version())
        assertTrue(one.version().contains("model-one"), one.version())
        assertTrue(one.version() != other.version(), "two models must not share a version")
        assertTrue(
            one.version().contains("${Closer.CLOSURE_EFFORT}"),
            "the effort decides the answer, so it belongs in the version: ${one.version()}",
        )
    }

    /**
     * The twin of the extractor's own coverage case, for the reason that one states: an instruction
     * this class sends that reaches no version ships a behavioural change which invalidates nothing.
     *
     * One instruction holds it today. It is enumerated rather than named so that a second added
     * here is covered by this case, which is the whole point — a stage with one instruction is
     * exactly where the defect is easiest to add, because the version looks obviously complete.
     *
     * Keyed on the `_SYSTEM` suffix, which makes that convention the real gate; read as fields
     * because an `internal` companion's getters are name-mangled.
     */
    @Test
    fun `every standing instruction the closer sends is covered by its version`() {
        val version = closer(closures = FakeClosures(emptyList()), model = FakeModelProvider()).version()
        val instructions = Closer::class.java.declaredFields
            .filter { it.name.endsWith("_SYSTEM") && it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(null) as String
            }
        assertTrue(
            instructions.keys.contains("CLOSURE_SYSTEM"),
            "the standing instructions are not where this test reads them from, so it is asserting " +
                "nothing: found ${instructions.keys}",
        )

        val uncovered = instructions.filter { (_, text) ->
            val digests = instructions.values.flatMap { other ->
                listOf((text + other).hashCode(), (other + text).hashCode())
            } + text.hashCode()
            digests.none { it.toString() in version }
        }

        assertEquals(
            emptyMap(),
            uncovered,
            "an instruction this class sends reaches no version, so editing it ships a behavioural " +
                "change that invalidates nothing: ${uncovered.keys}",
        )
    }

    @Test
    fun `the lag is what the store says is owed`(): Unit = runBlocking {
        // The driver reads a fall in this as progress, and a count of its own here would be a second
        // definition of owed that could disagree with the selection's.
        val closures = FakeClosures(listOf(candidate()), owed = 7)

        assertEquals(7, closer(closures = closures, model = FakeModelProvider()).owed())
        assertEquals(
            listOf(Closer.DEFAULT_EXAMINE),
            closures.askedHorizons,
            "the horizon the count is asked for has to be the one the selection uses",
        )
    }

    @Test
    fun `the floor the bounds carry is the one both reads are made under`(): Unit = runBlocking {
        // The driver reads a fall in the lag as progress, so a count taken under a different floor
        // from the selection would report commitments the selection then declined to return — a
        // stage that never drains. The two go through one bound for that reason.
        val closures = FakeClosures(listOf(candidate()), owed = 3)
        val closer = closer(closures = closures, model = FakeModelProvider().answering(Extractor.NOTHING), rearm = 9)

        closer.owed()
        closer.closeBatch()

        assertEquals(
            listOf(9, 9),
            closures.askedFloors,
            "the count and the selection each asked once, and both under the configured floor",
        )
        assertEquals(
            listOf(NOW, NOW),
            closures.askedInstants,
            "and both under the sweep's own instant, which is what decides whether the floor has " +
                "lapsed — two readings there would have the count and the selection disagree",
        )
    }

    @Test
    fun `a floor of nothing is refused, because it is the behaviour the floor exists to stop`() {
        // Zero reads as a deployment having set something, and what it sets is one call per message.
        listOf(0, -1).forEach { floor ->
            val refused = assertFailsWith<IllegalArgumentException>("a floor of $floor has to be refused") {
                ClosureBounds(now = NOW, rearm = floor)
            }
            assertTrue(
                refused.message.orEmpty().contains("$floor"),
                "the message has to name the figure that was refused: ${refused.message}",
            )
        }
    }

    @Test
    fun `a reword while an examination is outstanding decides under the wording that weighed it`(): Unit =
        runBlocking {
            // **This stage marks no record, so the version on the verdict is what a reword
            // retires it by.** A batch is allowed most of a day and a reword arrives by deploy, so
            // the two coincide on exactly the sweep after a prompt card lands. Written under the
            // instruction current at collection, the verdict claims a wording that never weighed
            // the window, and the reword leaves it standing: what would offer the commitment again
            // is that version moving, and the row already names the one that moved to it.
            //
            // What is left is the re-arm — fresh material inside the closing horizon — which is
            // about the conversation growing rather than about the instruction changing. So a
            // commitment in a quiet thread is never re-examined under the new wording at all.
            val closures = FakeClosures(listOf(candidate()))
            val model = FakeModelProvider().answering(CLOSING.nativeId)
            val closer = closer(closures = closures, model = model)

            closer.closeBatch()
            val weighedBy = closer.version()

            // The deploy lands while the examination is with the provider. Moved by the model
            // rather than by rewording the instruction because the version is opaque and compared
            // only for equality — what the stage can see is that the string differs.
            model.version = "a-model-these-commitments-were-not-examined-under"

            closer.closeBatch()

            assertEquals(
                weighedBy,
                assertNotNull(closures.recorded.singleOrNull()).decidedBy,
                "The verdict names the instruction deployed at collection rather than the one " +
                    "that weighed the window, so the reword leaves it standing: what retires a " +
                    "verdict is the version it claims moving, and it claims the one now current.",
            )
            assertNotEquals(
                weighedBy,
                closer.version(),
                "The reword did not move this stage's version, so the case would pass whichever " +
                    "version the verdict carried.",
            )
        }

    @Test
    fun `an examination naming no version is decided under the one this sweep is running`(): Unit =
        runBlocking {
            // The migration's case: a batch already with the provider when the version column
            // arrived carries none, and a null has to mean the version current at collection —
            // which is what this stage did before a submission carried one. Driven through the
            // double rather than left to the store, because the store can show only that it reads
            // a null back; what has to survive one is the caller.
            val closures = FakeClosures(listOf(candidate()))
            val submissions = FakeSubmissionRepository(clock = NOW)
            val closer = closer(
                closures = closures,
                model = FakeModelProvider().answering(CLOSING.nativeId),
                submissions = submissions,
            )

            closer.closeBatch()
            submissions.recorded.forEach { submissions.forgetVersionOf(it.id) }

            closer.closeBatch()

            assertEquals(
                closer.version(),
                assertNotNull(closures.recorded.singleOrNull()).decidedBy,
                "A submission that names no version has to be decided under the instruction this " +
                    "sweep is running. Anything else is a verdict naming a version no build " +
                    "would produce, which nothing could ever retire.",
            )
        }

    // --- doubles and fixtures -------------------------------------------------------------

    private fun closer(
        closures: ClosureRepository,
        model: FakeModelProvider,
        now: Instant = NOW,
        submissions: FakeSubmissionRepository = FakeSubmissionRepository(clock = now),
        rearm: Int = Closer.DEFAULT_REARM,
    ) = Closer(
        closures = closures,
        persons = FakePersons(mapOf(AUTHOR to "A Named Person")),
        submissions = submissions,
        model = model,
        bounds = ClosureBounds(now = now, rearm = rearm),
    )

    /** Every request answered with [text], whatever was asked. */
    private fun FakeModelProvider.answering(text: String) = replying {
        BatchOutcome.Replied(ModelOutcome.Answered(text, TokenUsage.NONE))
    }

    private fun FakeModelProvider.replying(answer: (String) -> BatchOutcome) = also { it.answering = answer }

    private fun candidate(
        later: List<Message> = listOf(statedTurn(), interimTurn(), closingTurn()),
    ) = ClosureCandidate(
        commitment = Commitment(
            id = COMMITMENT,
            claim = CLAIM,
            conversation = CONVERSATION,
            evidence = setOf(Evidence(span = span(STATING), sourceTime = STATED)),
            hedged = false,
        ),
        // The stating turn is in the store's window only where it carries text of its own; both
        // shapes reach the pass, and it rebuilds the target line either way.
        later = later.filterNot { it.externalId == STATING },
    )

    private fun statedTurn() = message(STATING.nativeId, "I will send the quote", STATED)

    /**
     * A line between the promise and its closure.
     *
     * **The window has to hold more than one message for this suite to discriminate anything.** With
     * a single line in it, every assertion about what was remembered is satisfied by remembering the
     * first thing — so a payload that kept only the head of the window passed the whole suite, which
     * is what a calibration run found.
     */
    private fun interimTurn() = message(INTERIM.nativeId, "noted, thanks", STATED.plusSeconds(1800))

    private fun closingTurn() = message(CLOSING.nativeId, "sent it over", LATEST)

    private fun message(native: String, text: String, at: Instant) = Message(
        externalId = ExternalId(Source.TELEGRAM, native),
        sourceTime = at,
        author = AUTHOR,
        text = text,
    )

    private fun span(record: ExternalId) = SpanId(kind = RecordKind.MESSAGE, record = record)

    /**
     * The verdicts the pass wrote, and the candidates it was offered.
     *
     * [recorded] is every verdict rather than a store's-eye view of the rows, because what these
     * cases are about is which verdicts the pass reaches at all — whether a row would have replaced
     * another is `ClosureStoreTest`'s.
     */
    private class FakeClosures(
        private var candidates: List<ClosureCandidate>,
        private val owed: Int = 0,
    ) : ClosureRepository {

        val recorded = mutableListOf<Closure>()
        val askedHorizons = mutableListOf<Duration>()

        /** Every floor the pass asked under, so the two reads can be held to the same one. */
        val askedFloors = mutableListOf<Int>()

        /**
         * Every window the pass asked for, so the bound it carries is known to reach the store.
         *
         * The size of this bound is the whole of what decides whether a closure far from its
         * promise is in front of the model at all, and nothing else in this suite would notice a
         * pass that asked for a window of its own.
         */
        val askedWindows = mutableListOf<Int>()

        fun offer(next: List<ClosureCandidate>) {
            candidates = next
        }

        /** Every sweep instant the pass asked under, so the floor's lapse can be held to one. */
        val askedInstants = mutableListOf<Instant>()

        @Suppress("LongParameterList")
        override suspend fun owed(
            version: String,
            commitments: Int,
            window: Int,
            examine: Duration,
            rearm: Int,
            now: Instant,
        ): List<ClosureCandidate> {
            askedHorizons += examine
            askedFloors += rearm
            askedInstants += now
            askedWindows += window
            return candidates.take(commitments)
        }

        override suspend fun owedCount(version: String, examine: Duration, rearm: Int, now: Instant): Int {
            askedHorizons += examine
            askedFloors += rearm
            askedInstants += now
            return owed
        }

        override suspend fun record(closures: List<Closure>) {
            recorded += closures
        }
    }

    private class FakePersons(private val named: Map<ExternalId, String>) : PersonRepository {

        override suspend fun persons(externalIds: List<ExternalId>): List<ObservedPerson> =
            externalIds.map { ObservedPerson(externalId = it, displayName = named[it]) }

        override suspend fun upsert(person: Person) = fail("the closure pass writes no person record")

        override suspend fun find(externalId: ExternalId): Person? = fail("unused")

        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = fail("unused")

        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = fail("unused")

        override suspend fun claimants(claims: List<Identifier>): List<ObservedPerson> = fail("unused")

        override suspend fun nameFrequency(names: List<String>): Map<String, Int> = fail("unused")

        override suspend fun namesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson> =
            fail("unused")
    }

    private companion object {

        val NOW: Instant = Instant.parse("2026-03-02T12:00:00Z")

        val STATED: Instant = Instant.parse("2026-03-02T09:00:00Z")

        /** The latest instant in the window, which is what a verdict off it is good through. */
        val LATEST: Instant = Instant.parse("2026-03-02T10:00:00Z")

        val CONVERSATION = ExternalId(Source.TELEGRAM, "a-conversation")

        val STATING = ExternalId(Source.TELEGRAM, "the-promise")

        val INTERIM = ExternalId(Source.TELEGRAM, "an-acknowledgement")

        val CLOSING = ExternalId(Source.TELEGRAM, "the-closure")

        val AUTHOR = ExternalId(Source.TELEGRAM, "an-author")

        val COMMITMENT = EntityId.mint()

        const val CLAIM = "The quote is sent."
    }
}
