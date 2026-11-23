package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider.Batched
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider.Reply
import io.github.ragnorrs.mainbrain.domain.FakeSpendLedger
import io.github.ragnorrs.mainbrain.domain.FakeSubmissionRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Cluster
import io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.CommitmentRepository
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.EventRepository
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Separation
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.StatementRepository
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.entity.ValueSynonym
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inference pass: selection inside the horizon, a window per owed message, and what may be
 * marked afterwards.
 *
 * The cases here are about the pass's bookkeeping rather than about what a model finds, because
 * the bookkeeping is where a mistake is permanent. A record marked as read when the model declined
 * is a message nothing will ever read again; a mark written before the output is a record settled
 * against work that was lost; a batch reporting only its progress hides every finding it threw
 * away. And a finding written from a record nothing marks is one every later sweep writes again.
 */
class InferrerTest {

    @Test
    fun `nothing owed is no model call and no writes`(): Unit = runBlocking {
        val rig = Rig()

        val inference = rig.inferrer.inferBatch()

        assertEquals(Inference.NOTHING, inference)
        assertEquals(0, rig.model.callCount)
        assertTrue(rig.derivations.marked.isEmpty())
    }

    @Test
    fun `a commitment is written, and only then is its message marked`(): Unit = runBlocking {
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|none|The quote gets sent.") },
        )

        val inference = rig.settle()

        val commitment = rig.commitments.written.single()
        assertEquals("The quote gets sent.", commitment.claim)
        assertEquals(CONTACT, commitment.statedBy, "The author's contact was not attached.")
        assertEquals(listOf(message("m1")), rig.derivations.marked)
        assertEquals(
            listOf("write", "mark"),
            rig.order.toList(),
            "The mark was written first. A failure between the two then settles a message " +
                "against work that was lost, and no re-run revisits it.",
        )
        // Asserted as part of the whole `Inference` rather than beside it, so a cost that silently
        // stopped being carried cannot pass by being left unexamined. The figure is the
        // restatement's alone, because that is the submission this step read: the classification's
        // own bill was reported by the step that collected it, two batches back.
        assertEquals(
            Inference(
                records = 1,
                written = 1,
                // One reading offered and one row caused, which is the case where the two agree —
                // asserted rather than left out so that a pass gone back to counting attempts
                // fails here and not only on the case where they differ.
                attempted = 1,
                lost = 0,
                unread = 0,
                usage = Reply.SAMPLE_USAGE,
            ),
            inference,
        )
        // The other half of the same rule, and the reason the two are not added together anywhere:
        // each stage's spend is reported by the step that read it, so a sweep adding the steps up
        // gets the whole bill and neither figure stands for both.
        assertEquals(
            Reply.SAMPLE_USAGE,
            rig.steps.first { it.awaiting > 0 && it.usage != TokenUsage.NONE }.usage,
            "The classification's bill was not reported by the step that collected it.",
        )
    }

    @Test
    fun `the party a commitment obliges crosses to a contact, as its author does`(): Unit = runBlocking {
        // **Direction becomes a fact a query can read here, and not before.** The restating stage
        // answers a party as one of the window's labels, and this is the one place a record becomes
        // an entity — so what is stored is the contact behind that label, looked up in the same read
        // as the author's. Both are needed, and they differ: an obligation one participant states
        // about another is a commitment, so a direction derived from the author would be
        // confidently wrong on exactly the material this exists for.
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = listOf(
                Message(externalId = message("m1"), sourceTime = FIRST, author = AUTHOR, text = "message 1"),
                Message(
                    externalId = message("m2"),
                    sourceTime = FIRST.plusSeconds(1),
                    author = BYSTANDER,
                    text = "message 2",
                ),
            ),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|$BYSTANDER_NAME|$BYSTANDER_NAME owes the quote.") },
            named = mapOf(AUTHOR to AUTHOR_NAME, BYSTANDER to BYSTANDER_NAME),
            contacts = FakeContacts(known = mapOf(AUTHOR to CONTACT, BYSTANDER to OTHER_CONTACT)),
        )

        rig.settle()

        val commitment = rig.commitments.written.single()
        assertEquals(CONTACT, commitment.statedBy, "The author's contact was not attached.")
        assertEquals(
            OTHER_CONTACT,
            commitment.obligedParty,
            "The obliged party was not resolved to the contact behind its label, so direction is " +
                "readable only by parsing the claim's prose — which is the state this replaced.",
        )
    }

    /**
     * A sentence binding two participants is written as a row for each, off one restatement.
     *
     * **The pass-level half of the split, and the half the store sees.** The reading stage answers
     * a line per obliged party; what has to follow is two rows rather than one, each crossing to
     * the contact behind its own label — the uniqueness key is the conversation together with the
     * claim, so two claims out of one message coexist and the second obligation has somewhere to
     * go. Before this, the second party's obligation was absent from the store entirely: no row, no
     * evidence link, and nothing in the output saying anything had been dropped.
     *
     * **The message is marked once**, which is what says the split costs no further reading: one
     * window, one classification, one restatement, two findings.
     */
    @Test
    fun `a commitment binding two parties is written as a row for each`(): Unit = runBlocking {
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = listOf(
                Message(externalId = message("m1"), sourceTime = FIRST, author = AUTHOR, text = "message 1"),
                Message(
                    externalId = message("m2"),
                    sourceTime = FIRST.plusSeconds(1),
                    author = BYSTANDER,
                    text = "message 2",
                ),
            ),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = {
                Batched.of(
                    """
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME prepares the tasks.
                    none|none|$BYSTANDER_NAME|$BYSTANDER_NAME reviews the tasks.
                    """.trimIndent(),
                )
            },
            named = mapOf(AUTHOR to AUTHOR_NAME, BYSTANDER to BYSTANDER_NAME),
            contacts = FakeContacts(known = mapOf(AUTHOR to CONTACT, BYSTANDER to OTHER_CONTACT)),
        )

        val inference = rig.settle()

        // **The figure the sweep reports, and not only the rows.** A fold that wrote both rows and
        // counted one would ship green against the assertions below: what a deployment reads is
        // the count, so one candidate now has to report two findings offered and two written.
        assertEquals(2, inference.attempted, "the sweep reported one finding where it offered two")
        assertEquals(2, inference.written, "the sweep reported one row where it wrote two")
        assertEquals(
            listOf("$AUTHOR_NAME prepares the tasks.", "$BYSTANDER_NAME reviews the tasks."),
            rig.commitments.written.map { it.claim },
            "one sentence binding two participants was stored as one row, so the second " +
                "obligation is absent from the corpus and no answer can report it",
        )
        assertEquals(
            listOf(CONTACT, OTHER_CONTACT),
            rig.commitments.written.map { it.obligedParty },
            "each row has to oblige its own party, or the two are two wordings of one thing",
        )
        assertEquals(
            listOf(CONTACT, CONTACT),
            rig.commitments.written.map { it.statedBy },
            "both were stated by the author of the message they were read from, whoever they oblige",
        )
        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "the one window was marked once, which is what says both findings came off one call",
        )
    }

    /**
     * A restatement line that bought no finding is reported as spend the sweep made.
     *
     * **The rate that says whether the division by party is being obeyed.** Asked for a line per
     * obliged party, the cheapest way to obey on a sentence listing several things one participant
     * will do is to write a line each — the one way this stage can make the corpus worse rather
     * than better. The extractor refuses the surplus line; this is what makes the refusal visible,
     * because a rail that drops something silently reads from outside exactly like a model that
     * never wrote it. The classification stage has reported its own repeats since it was built,
     * and this is the same figure one stage along.
     */
    @Test
    fun `a restatement line that bought no finding is counted as a repeat`(): Unit = runBlocking {
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = listOf(
                Message(externalId = message("m1"), sourceTime = FIRST, author = AUTHOR, text = "message 1"),
            ),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = {
                Batched.of(
                    """
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME prepares the tasks.
                    none|none|$AUTHOR_NAME|$AUTHOR_NAME reviews the tasks.
                    """.trimIndent(),
                )
            },
            named = mapOf(AUTHOR to AUTHOR_NAME),
            contacts = FakeContacts(known = mapOf(AUTHOR to CONTACT)),
        )

        val inference = rig.settle()

        assertEquals(
            1,
            rig.commitments.written.size,
            "one participant's undertakings were split in two, which is two rows for one party " +
                "and a duplicate the store cannot key apart",
        )
        assertEquals(
            1,
            inference.repeated,
            "the refused line was dropped in silence, so nothing a sweep reports says the " +
                "instruction to divide by party is being taken as licence to divide what is whole",
        )
    }

    @Test
    fun `an obliged party identity has not recognised yet leaves the direction empty`(): Unit = runBlocking {
        // The second reason the column is nullable, and it is not the same as the first: identity
        // runs over the whole corpus and ahead of this, so a labelled party it has not reached yet
        // is direction unknown rather than an obligation on the author. Filling it in with the
        // author would be the confident wrong answer the field exists to avoid.
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = listOf(
                Message(externalId = message("m1"), sourceTime = FIRST, author = AUTHOR, text = "message 1"),
                Message(
                    externalId = message("m2"),
                    sourceTime = FIRST.plusSeconds(1),
                    author = BYSTANDER,
                    text = "message 2",
                ),
            ),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|$BYSTANDER_NAME|$BYSTANDER_NAME owes the quote.") },
            named = mapOf(AUTHOR to AUTHOR_NAME, BYSTANDER to BYSTANDER_NAME),
            contacts = FakeContacts(known = mapOf(AUTHOR to CONTACT)),
        )

        rig.settle()

        val commitment = rig.commitments.written.single()
        assertEquals(CONTACT, commitment.statedBy, "The author's contact was not attached.")
        assertNull(
            commitment.obligedParty,
            "A party identity has not reached was given an identifier from somewhere, and this " +
                "pass is not where one is assigned.",
        )
    }

    @Test
    fun `a window cut short leaves its messages owed`(): Unit = runBlocking {
        // The reason `Candidacy` is sealed. A window that held nothing and a window nobody read are
        // identical in the output, and marking the second settles a record against an answer that
        // never arrived. Asking again may well answer, so it stays owed.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.truncated("commitment|m1|pl") },
        )

        val inference = rig.settle()

        assertTrue(rig.derivations.marked.isEmpty(), "A window that was cut short was marked as read.")
        assertEquals(1, inference.unread)
        assertEquals(0, inference.records)
    }

    @Test
    fun `a window the model refuses is settled, and what it held is reported as given up`(): Unit = runBlocking {
        // The opposite handling, and the reason the two are different cases. A refusal is
        // deterministic, so holding the window owed means re-selecting and re-refusing it on
        // every sweep — and since selection takes the oldest owed records first, the pass would
        // make no progress at all while billing a call per sweep. The window is given up instead,
        // which is a real loss and therefore reported rather than counted.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.refused("a_category") },
        )

        val inference = rig.settle()

        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "A refused window was left owed, so the pass will meet it again on every sweep and " +
                "never get past it.",
        )
        assertEquals(1, inference.records)
        assertEquals(0, inference.unread, "A refusal is not something that will be offered again.")
        assertEquals(1, inference.declined.size, "The window was given up without saying so.")
        assertTrue("a_category" in inference.declined.single(), "Why it was given up: ${inference.declined}")
    }

    @Test
    fun `a finding placed on a context message is dropped, and that message is never marked`(): Unit =
        runBlocking {
            // **The defect a stretch-shaped window had, asserted from both sides.** A window's
            // context reaches messages this sweep neither selected nor marks, and a finding read out
            // of one of them used to be written anyway — unmarked, so nothing recorded that the
            // message had been read and every later window reaching back over it wrote the finding
            // again. Here the model answers with a context line's identifier, which is the shape
            // that produced it.
            //
            // m2 is selected and read in a window of m1..m3, so m1 and m3 are in front of the model
            // and belong to no selection.
            val rig = Rig(
                pending = listOf(message("m2")),
                conversation = conversation(4),
                bounds = Bounds(notBefore = HORIZON, contextReach = 1),
                classified = { Batched.of("commitment|m1|plain|none") },
            )

            val inference = rig.settle()

            assertTrue(
                rig.commitments.written.isEmpty(),
                "A finding was written from a context message. Nothing marks that message, so the " +
                    "next sweep whose context reaches it writes the same finding again.",
            )
            assertEquals(
                1,
                inference.refused,
                "A finding dropped for naming a context message was not counted — and counted as a " +
                    "refusal rather than as a reading this build could not make sense of, which is " +
                    "the figure that says a prompt and a parser have drifted apart.",
            )
            assertEquals(
                listOf(message("m2")),
                rig.derivations.marked,
                "Either the selected message was left owed, or a context message was marked — and " +
                    "a mark on a record nothing selected is a record settled against a window that " +
                    "only looked at it.",
            )
        }

    @Test
    fun `a message is owed until its own window has been read`(): Unit = runBlocking {
        // One selected message is one window, so what leaves a record owed is that window not
        // having been read — not another window's failure. The classification is cut short, which
        // can answer next time, so the record stays owed and the cost is still reported.
        val rig = Rig(
            pending = listOf(message("m2"), message("m3")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            // Keyed on the window rather than queued, because a submission is answered in no
            // particular order: m2's window holds nothing and m3's was cut short.
            classified = { key -> if (key == "m2") Batched.of("none") else Batched.truncated("commit") },
        )

        val inference = rig.settle()

        assertEquals(
            listOf(message("m2")),
            rig.derivations.marked,
            "A message whose own window was cut short was marked, or one whose window was read was " +
                "held back by its neighbour's failure.",
        )
        assertEquals(1, inference.unread)
        // A batch reads several windows, so its reported cost has to span them — and this is the
        // shape where a cost derived from the output rather than from the calls would under-report:
        // one window answered `none` and one was cut short, and both were billed.
        assertEquals(
            Reply.SAMPLE_USAGE + Reply.SAMPLE_USAGE,
            inference.usage,
            "A batch reported the window it could do nothing with as free.",
        )
    }

    @Test
    fun `the horizon is what bounds selection, and the pass passes it on`(): Unit = runBlocking {
        // The pass holds no clock: the cut-off comes from whoever schedules it. Without it,
        // selection is oldest-first over the whole corpus, so a pass after recent material would
        // spend its budget on 2019 and never reach the horizon.
        val rig = Rig()

        rig.inferrer.inferBatch()
        rig.inferrer.owed()

        assertEquals(
            listOf<Instant?>(HORIZON, HORIZON),
            rig.derivations.horizonsSeen,
            "Selection was not bounded by the horizon, or the lag disagrees with the batch about " +
                "what is owed — a driver looping until it is zero would never stop.",
        )
    }

    @Test
    fun `an attribute becomes a statement about the author's contact`(): Unit = runBlocking {
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
        )

        rig.settle()

        val statement = rig.statements.recorded.single()
        assertEquals(CONTACT, statement.subject)
        assertEquals(Predicate.of("works at"), statement.predicate)
        assertEquals("Acme Ltd", statement.value)
        assertEquals(Validity(from = FIRST), statement.validity, "The window opens where the evidence was said.")
        assertEquals(Origin.INFERRED, statement.origin, "A model read it, which is the weakest origin there is.")
        assertEquals(setOf(evidence("m1", FIRST)), statement.evidence)
    }

    @Test
    fun `an attribute about somebody not yet recognised is counted rather than dropped`(): Unit = runBlocking {
        // Identity runs over the whole corpus and ahead of this, so this is rare — and a run that
        // loses many of them is a run whose two passes are out of step, which is worth seeing.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
            contacts = FakeContacts(known = emptyMap()),
        )

        val inference = rig.settle()

        assertTrue(rig.statements.recorded.isEmpty(), "A statement was stored about an entity nobody minted.")
        assertEquals(1, inference.unplaced)
        assertEquals(
            0,
            inference.lost,
            "An attribute whose subject identity has not reached is the two passes out of step, " +
                "not a reading this build could not make sense of. Added into the losses it says " +
                "the prompt and the parser have drifted, which is the opposite thing to go and look at.",
        )
        assertEquals(1, inference.records, "The message was still read, so it is still settled.")
    }

    @Test
    fun `an attribute whose subject is not the author's label is dropped and counted`(): Unit = runBlocking {
        // **The defect that made five of six measured statements false.** The subject of a stored
        // attribute is the author of the message it was read from, filled in from the window and
        // never from the model — so a fact about a third party, a company or a system became a fact
        // asserted of whoever happened to mention it: a person recorded as located at a hostname.
        //
        // The subject field is what narrows it: a fact offered under the author's own label still
        // passes this rule, and the value test one stage on reads three shapes a value can never
        // be rather than whether a value is a host. Anything other than that label is a
        // subject this build cannot place, so the reading is given up rather than misfiled.
        listOf(BYSTANDER_NAME, "none", "A Party Of No Line", "").forEach { subject ->
            val rig = Rig(
                pending = listOf(message("m1")),
                classified = { Batched.of("attribute|m1|plain|$subject") },
                restated = { Batched.of("none|works at|none|Acme Ltd") },
                named = mapOf(AUTHOR to AUTHOR_NAME, BYSTANDER to BYSTANDER_NAME),
            )

            val inference = rig.settle()

            assertTrue(
                rig.statements.recorded.isEmpty(),
                "A fact whose subject is `$subject` was stored against the author who stated it.",
            )
            assertEquals(1, inference.refused, "A reading given up for `$subject` was not counted.")
            assertEquals(
                listOf(message("m1")),
                rig.derivations.marked,
                "The message was read, so it settles — holding it would re-classify it every sweep.",
            )
        }
    }

    @Test
    fun `the restatement is never asked about a subject this build cannot place`(): Unit = runBlocking {
        // The reason the subject is a field on the *classification* and not on the restatement. The
        // restatement is the expensive stage — the higher effort, and the great majority of the
        // spend — so a reading that cannot be stored has to be given up before it is paid for.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$BYSTANDER_NAME") },
            named = mapOf(AUTHOR to AUTHOR_NAME, BYSTANDER to BYSTANDER_NAME),
        )

        rig.settle()

        assertTrue(
            rig.submissions.recorded.none { it.stage == SubmissionStage.RESTATEMENT },
            "A restatement was submitted for a reading that can never be stored, which is the " +
                "stage that costs the money.",
        )
    }

    @Test
    fun `every line put in front of the model says who sent it`(): Unit = runBlocking {
        // **Without this no claim can name its party**, and the restatement instruction demands one
        // that does: the only agents a window of identifier, time and text offers are "the sender"
        // and a bare first-person verb, so twenty-three of thirty-six measured claims identified
        // nobody. The corpus's own name for the author is what the label is where there is one.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("none") },
        )

        rig.inferrer.inferBatch()

        val prompt = rig.model.submissions.single().requests.getValue("m1").prompt
        assertTrue(
            AUTHOR_NAME in prompt,
            "The author is not in front of the model, so a claim can only say `the sender`: $prompt",
        )
    }

    @Test
    fun `an author the corpus does not name leaves the field empty, and nothing is invented`(): Unit =
        runBlocking {
            // **Nothing is made up to fill it.** A label derived from this window — a position in
            // it — would be reassigned as the context grew, so the same string would mean one party
            // when the model answered and another when the answer was read; and a label this
            // codebase authored would reach a reader inside a claim, in English, with no catalogue
            // behind it. An empty field is honest and keeps the line's shape.
            val rig = Rig(
                pending = listOf(message("m1")),
                classified = { Batched.of("none") },
                named = emptyMap(),
            )

            rig.inferrer.inferBatch()

            val line = rig.model.submissions.single().requests.getValue("m1").prompt
                .lines()
                .first { it.startsWith("m1|") }
            assertEquals(
                "",
                line.split("|")[2],
                "An unnamed author was given a label anyway, so a string this code wrote can reach " +
                    "a reader in a claim: $line",
            )
            assertEquals(4, line.split("|").size, "The line lost a field, so every reply becomes unreadable.")
        }

    @Test
    fun `an attribute by an author the corpus does not name is refused before it is paid for`(): Unit =
        runBlocking {
            // No label means nothing a subject can match, so the reading goes no further. It could
            // never have been stored — the proposal's subject is that author, and a statement needs
            // a subject identity has recognised — so the point is that the refusal happens at the
            // cheap stage rather than after the restatement has been bought.
            val rig = Rig(
                pending = listOf(message("m1")),
                classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
                named = emptyMap(),
            )

            val inference = rig.settle()

            assertTrue(rig.statements.recorded.isEmpty())
            assertEquals(1, inference.refused)
            assertTrue(
                rig.submissions.recorded.none { it.stage == SubmissionStage.RESTATEMENT },
                "A restatement was bought for a reading with no subject this build can place.",
            )
        }

    @Test
    fun `a contradicting reading closes the window of what is already stored`(): Unit = runBlocking {
        // Resolution wired in, which is the property the whole statement store exists for: the
        // pass resolves against every statement held about the subject, not against its own batch.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
            held = listOf(
                Statement(
                    subject = CONTACT,
                    predicate = Predicate.of("works at"),
                    value = "A Former Employer",
                    validity = Validity(from = EARLIER),
                    origin = Origin.INFERRED,
                    evidence = setOf(evidence("m0", EARLIER)),
                ),
            ),
        )

        val inference = rig.settle()

        assertEquals(
            Validity(from = EARLIER, until = FIRST),
            rig.statements.recorded.single { it.value == "A Former Employer" }.validity,
            "The earlier reading was left open, so both employers hold at once and the question " +
                "has two answers.",
        )
        assertEquals(Validity(from = FIRST), rig.statements.recorded.single { it.value == "Acme Ltd" }.validity)
        assertEquals(1, inference.written, "The new reading opened a window of its own.")
        assertEquals(1, inference.attempted, "One reading was offered, whatever the write was handed.")
        assertEquals(
            0,
            inference.overwritten,
            "The stored window this reading narrowed was counted as a row the batch replaced. It " +
                "was not offered by the batch at all — the write is handed every window the " +
                "subject holds because resolution needs them — and counting those here is what " +
                "buries the one reading that was paid for under the size of the subject.",
        )
    }

    @Test
    fun `a reading is not counted against rows the write was handed but nobody offered`(): Unit = runBlocking {
        // **The write sees far more than the batch offered, and the figures must not.** An attribute
        // write is given every window its subject holds under every predicate, because resolution
        // cannot close a window against a reading it was not shown. So a subject that already holds
        // a few statements has its whole set re-upserted, and a figure taken from the write reports
        // those as rows this batch replaced — swamping the single reading it actually read.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
            held = listOf(
                statementAbout("based in", "Tbilisi"),
                statementAbout("charges", "a day rate"),
                statementAbout("speaks", "Georgian"),
            ),
        )

        val inference = rig.settle()

        assertEquals(1, inference.attempted)
        assertEquals(1, inference.written, "The one reading offered opened one window.")
        assertEquals(
            0,
            inference.overwritten,
            "The three untouched statements were re-upserted, as resolution requires, and reported " +
                "as rows this batch replaced. The figure then grows with the subject rather than " +
                "with anything the sweep did.",
        )
        assertEquals(0, inference.collapsed)
        assertEquals(0, inference.superseded, "Nothing was removed: every stored window still holds.")
    }

    @Test
    fun `two readings of one value at one instant are one row and one collapse`(): Unit = runBlocking {
        // Counted per surviving row and not per reading. Both readings carry the same content key —
        // a value stated twice within the same second, which chat messages produce — so asked per
        // reading both test as surviving and the one that opened no row of its own is reported
        // nowhere, leaving the gap between attempts and rows unaccounted for again.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2")),
            // Both messages at one instant, which is what makes the two keys identical.
            conversation = List(2) { index ->
                Message(
                    externalId = message("m${index + 1}"),
                    sourceTime = FIRST,
                    author = AUTHOR,
                    text = "message ${index + 1}",
                )
            },
            classified = { key -> Batched.of("attribute|$key|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
        )

        val inference = rig.settle()

        assertEquals(2, inference.attempted)
        assertEquals(1, inference.written)
        assertEquals(
            1,
            inference.collapsed,
            "Two readings share a content key, so one of them opened no row — and asked once per " +
                "reading rather than once per row, neither was reported as folded.",
        )
        assertEquals(
            inference.attempted,
            inference.written + inference.overwritten + inference.collapsed,
            "The three figures no longer come to what was offered, so the gap is back to being " +
                "a subtraction nobody can attribute.",
        )
    }

    @Test
    fun `two readings of one claim are one row, and the second is an overwrite`(): Unit = runBlocking {
        // Every derived write is an upsert keyed by content, so a claim offered twice is one row.
        // Two messages in one batch restating the same obligation is the smallest case where
        // attempts and rows differ, which is what makes it the case to pin: a pass counting what it
        // handed over reports two writes here and the store holds one.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2")),
            classified = { key -> Batched.of("commitment|$key|plain|none") },
            restated = { Batched.of("none|none|none|The quote gets sent.") },
        )

        val inference = rig.settle()

        assertEquals(2, rig.commitments.written.size, "Both readings were offered, which is what was paid for.")
        assertEquals(
            1,
            inference.written,
            "One row was caused and the figure said two. That is the gap every rate measured off " +
                "a sweep was read through.",
        )
        assertEquals(2, inference.attempted, "What was handed over is still reported, under its own name.")
        assertEquals(1, inference.overwritten, "The reading that caused no row is unaccounted for.")
        assertEquals(0, inference.collapsed, "Nothing was folded by resolution: findings are keyed by their claim.")
    }

    @Test
    fun `a value restated in the same words is one row, and the second reading is collapsed`(): Unit = runBlocking {
        // The other half of the same gap: resolution attaches a restated value as evidence rather
        // than opening a second window, so the reading leaves no row and the store never sees it.
        // Only the pass can report it, which is why the figure is computed there.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2")),
            classified = { key -> Batched.of("attribute|$key|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
        )

        val inference = rig.settle()

        assertEquals(
            1,
            rig.statements.recorded.size,
            "Resolution opened two windows for one value restated, which is the duplicate it exists to fold.",
        )
        assertEquals(2, inference.attempted)
        assertEquals(1, inference.written)
        assertEquals(
            1,
            inference.collapsed,
            "A reading folded into another was reported as nothing at all, so a sweep that read " +
                "one fact twice and a sweep that lost a reading read identically.",
        )
        assertEquals(
            0,
            inference.overwritten,
            "A collapse is not an overwrite. Counted together, the figure cannot say whether a " +
                "sweep recognised a duplicate or read the same window twice — which is the whole " +
                "of what it was asked for.",
        )
    }

    @Test
    fun `an older reading supersedes the window it reopens, and the row removed is reported`(): Unit = runBlocking {
        // What a backfill does: an earlier reading of a value already stored moves the window's
        // start, and the start is part of the key — so the stored row is replaced rather than
        // updated, and the derived layer loses a row while gaining one. A figure counting offers
        // reports one write and says nothing about the removal.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
            held = listOf(
                Statement(
                    subject = CONTACT,
                    predicate = Predicate.of("works at"),
                    value = "Acme Ltd",
                    // Later than the message being read, which is what makes this the backfill case.
                    validity = Validity(from = FIRST.plusSeconds(AN_HOUR)),
                    origin = Origin.INFERRED,
                    evidence = setOf(evidence("m9", FIRST.plusSeconds(AN_HOUR))),
                ),
            ),
        )

        val inference = rig.settle()

        assertEquals(
            Validity(from = FIRST),
            rig.statements.recorded.single().validity,
            "The window did not move back to the earlier evidence, so the fact reads as having " +
                "started later than it was first said.",
        )
        assertEquals(1, inference.written, "The window under its new key is a row this write caused.")
        assertEquals(
            1,
            inference.superseded,
            "The row the write removed was invisible: the store got smaller by one and the sweep " +
                "reported a write, which is the pair of figures that cannot be told apart.",
        )
    }

    @Test
    fun `a stretched predicate is counted apart from the rules and from the drift`(): Unit =
        runBlocking {
            // **The routine failure of the restatement stage, and the one that must not read as
            // alarming.** The instruction asks for the claim in the corpus's own language, which
            // invites the relation in that language too, so a predicate outside the closed
            // vocabulary is expected on every sweep over a corpus that is not English. Counted with
            // the replies that would not parse — the figure that says this build has something to
            // fix — it would have a deployment chasing a parser working as designed. Counted with
            // the losses it hides them, which is the same mistake the other way round.
            //
            // It is also out of `refused`, whose rules are about where a finding sits and whom it
            // is about and which fire as a conversation grows. What this figure says is whether the
            // instruction not to stretch an entry is being followed. The figure that bears on the
            // list is `unnamed`, the answer that instruction asks for — and not a measure of it,
            // both stages being given the same list.
            val rig = Rig(
                pending = listOf(message("m1")),
                classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
                restated = { Batched.of("none|employed by|none|Acme Ltd") },
            )

            val inference = rig.settle()

            assertTrue(rig.statements.recorded.isEmpty(), "A predicate nobody can query was stored.")
            assertEquals(
                1,
                inference.outsideVocabulary,
                "A stretched predicate was not counted, so nothing says whether the rule against " +
                    "stretching one is holding.",
            )
            assertEquals(
                0,
                inference.refused,
                "A word outside the list was counted among the placement and subject rules, which " +
                    "fire for reasons that have nothing to do with the vocabulary.",
            )
            assertEquals(
                0,
                inference.unnamed,
                "A stretched predicate was booked as the refusal the instruction asked for, which " +
                    "is the figure that bears on the list — so disobedience would read as a narrow list.",
            )
            assertEquals(
                0,
                inference.unextractable,
                "A relation in the corpus's own language reads as a prompt and a parser out of " +
                    "step, which is the one figure here a deployment is meant to act on.",
            )
            assertEquals(0, inference.lost, "The routine refusal was added into the losses, where it hides them.")
            assertEquals(
                listOf(message("m1")),
                rig.derivations.marked,
                "The window was read, so the record is settled: asking again is refused again.",
            )
        }

    @Test
    fun `a label withdrawn between the two stages is refused and counted as a rule`(): Unit = runBlocking {
        // **The restatement stage's own refusal, which nothing else drives.** A window is re-derived
        // when a submission is collected, so a context that has grown in between can withdraw the
        // label the stored subject field was matched against — and that costs the finding. It is the
        // only rule that reaches `REFUSED` here and can change its answer: the placement check
        // re-tests the stored line against the identifier its own request named.
        //
        // Driven a step at a time rather than through `settle`, because the withdrawal has to land
        // between the classification's collection and the restatement's. Without a case here the
        // branch could be mis-wired — to the width's figure, or to withholding the record — and
        // every neighbouring assertion of `refused == 0` would go on passing.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
        )

        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()
        // The author is no longer named, so the window offers no label for the stored subject to
        // match and the reading is given up rather than stored against whoever mentioned it.
        rig.names.remove(AUTHOR)
        val inference = rig.inferrer.inferBatch()

        assertTrue(rig.statements.recorded.isEmpty(), "A fact was stored against a party no label names.")
        assertEquals(
            1,
            inference.refused,
            "A withdrawn label was not counted as a rule declining the reading, so the restatement " +
                "stage's own refusal is reported as something else or not at all.",
        )
        assertEquals(
            0,
            inference.unnamed,
            "A rule about the subject was booked as the figure that bears on the list.",
        )
        assertEquals(0, inference.outsideVocabulary, "A rule about the subject was booked as a stretched predicate.")
        assertEquals(
            0,
            inference.lost,
            "A rule working as written was reported as this build having misread something.",
        )
        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "The record was left owed, so the pass will re-read and re-refuse it on every sweep.",
        )
    }

    @Test
    fun `a fact about a system under the author's own label is refused by its value`(): Unit = runBlocking {
        // **The one route into the store that no rule about the model's answer can close.** The
        // subject rule asks which party was named, so a fact about a machine attributed to the
        // author passes it: the subject is the author's, which is all that rule asks. From there it
        // was an ordinary attribute — a predicate on the list, a line placed on its target — and it
        // reached the store as a person's location with an open validity window and a real message
        // citing it, which on the answer path is indistinguishable from a fact that is true.
        //
        // **The value carries a scheme on purpose**, that being one of the three shapes the value
        // test reads — a value no town, title, employer or rate can be, in any script and whatever
        // its capitalisation. It is the only check here that does not take the model's own answer
        // as its evidence, so it is the one that holds when a prompt edit moves the behaviour
        // again, which is how the row that prompted this was written.
        //
        // **A bare dotted host is deliberately not among those shapes**, and a case of its own
        // holds that: a host and a place name are one shape, so a pattern catching the first
        // refuses abbreviated Latin places and titles while missing every host in the corpus's own
        // script. That route is closed by the classification stage naming the fourth kind instead.
        val refusable = "https://stage.example.test/health"
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|is based in|none|$refusable") },
        )

        val inference = rig.settle()

        assertTrue(
            rig.statements.recorded.isEmpty(),
            "A value no predicate on the list takes was stored. Nothing downstream can tell it " +
                "from a true fact: the window is open, the predicate parses and the citation checks out.",
        )
        assertEquals(0, inference.written, "A row was caused by a reading that was refused.")
        assertEquals(
            1,
            inference.misshapen,
            "The value test fired and was booked somewhere else, or did not fire at all — and this " +
                "figure is the only trace a sweep leaves of the rail that holds whatever the prompts say.",
        )
        // The two figures read off the predicate field are blind to this by construction: the name
        // was in the vocabulary and only the value was wrong. Asserted so that a future wiring
        // cannot quietly book it under the figure the list's width is read against.
        assertEquals(0, inference.unnamed, "A readable in-vocabulary predicate was counted as the refusal word.")
        assertEquals(0, inference.outsideVocabulary, "An entry that is on the list was counted as a stretched one.")
        assertEquals(
            0,
            inference.refused,
            "A value the predicate does not take was booked as a rule about the subject.",
        )
        assertEquals(0, inference.unextractable, "A well-formed line was reported as one that would not parse.")
        assertEquals(0, inference.lost, "A reading this build made sense of was reported as one it could not.")
        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "The record was left owed, so the pass will re-read and re-refuse the same value on " +
                "every sweep, at a classification and a restatement each.",
        )
    }

    @Test
    fun `a fact the classification cannot label is counted and costs no restatement`(): Unit = runBlocking {
        // **The affirmative half, and the figure that was missing entirely.** The three kinds that
        // become rows are decided with the closed vocabulary in view, so a fact outside it used to
        // leave the stage two moves: call it an attribute anyway and let the next stage pick
        // whichever entry parsed, or report nothing. The second is byte-identical to a window that
        // held nothing — no line, no figure, no clause — so the attribute path's silence could be
        // read either way, and this build read it as a corpus with no attributes in it.
        //
        // Driven through the whole settle so the call count discriminates: a fourth-kind line
        // wrongly turned into a candidate would be restated, and that second call is the only
        // visible trace of it.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("${Extractor.UNLISTED}|m1|plain|none") },
            restated = { Batched.of("none|is based in|none|somewhere") },
        )

        val inference = rig.settle()

        assertEquals(
            1,
            inference.unlisted,
            "A fact named as outside the list was not counted, so the stage declining material it " +
                "cannot label still reads exactly like the stage dropping it in silence.",
        )
        assertTrue(rig.statements.recorded.isEmpty(), "Something was stored from a kind nothing stores.")
        assertEquals(0, inference.written, "A row was caused by a kind that causes none.")
        assertEquals(
            0,
            inference.attempted,
            "A fourth-kind line was handed to a write, so it was asked about at the higher effort " +
                "and folded away rather than costing nothing beyond the classification.",
        )
        // Not a loss, not a rule, and not drift: it is the answer the instruction asks for.
        assertEquals(0, inference.lost, "The instructed answer was reported as a reading this build could not make.")
        assertEquals(0, inference.refused, "The instructed answer was booked as a rule declining a finding.")
        assertEquals(0, inference.unextractable, "A line this build reads was reported as one that would not parse.")
        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "The record was left owed over a window that was read and answered.",
        )
    }

    @Test
    fun `a label changed between the two stages is refused, as a withdrawn one is`(): Unit = runBlocking {
        // The other half of what moves a label. A counterpart who renames themselves changes the
        // record the label is a function of, so the stored subject stops matching without anything
        // being withdrawn — and the rule fires exactly as it does for a withdrawal. Asserted
        // because the surfaces describing this refusal name withdrawal alone, and a rename reaches
        // it just as surely.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("attribute|m1|plain|$AUTHOR_NAME") },
            restated = { Batched.of("none|works at|none|Acme Ltd") },
        )

        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()
        rig.names[AUTHOR] = "A Name The Author Did Not Have"
        val inference = rig.inferrer.inferBatch()

        assertTrue(rig.statements.recorded.isEmpty(), "A fact was stored against a label that had moved.")
        assertEquals(
            1,
            inference.refused,
            "A renamed author was not counted as a rule declining the reading, so only a withdrawn " +
                "label is covered and the rename route is untested.",
        )
    }

    @Test
    fun `the three answers that are not a predicate are counted apart`(): Unit = runBlocking {
        // **The separation this exists for, asserted on one sweep rather than three.** The parser
        // returns nothing for the refusal word, for a word outside the list, and for a blank field,
        // and they answer different questions: the refusal is what the instruction asks for where
        // no entry fits, so it is the one that bears on the list; a word outside the list is that
        // instruction disobeyed; a blank field is a prompt out of step with the parser. Summed,
        // none of the three could be read for what it means, and the list would be widened against
        // a figure that never measured it.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2"), message("m3")),
            // Three records rather than the default two, because each of the three answers needs a
            // window of its own and a record with no message is never read.
            conversation = conversation(3),
            classified = { key -> Batched.of("attribute|$key|plain|$AUTHOR_NAME") },
            restated = { key ->
                when {
                    key.startsWith("m1") -> Batched.of("none|employed by|none|Acme Ltd")
                    key.startsWith("m2") -> Batched.of("none|none|none|Acme Ltd")
                    else -> Batched.of("none||none|Acme Ltd")
                }
            },
        )

        val inference = rig.settle()

        assertTrue(rig.statements.recorded.isEmpty(), "None of the three can be keyed, so none is a row.")
        assertEquals(1, inference.outsideVocabulary, "A stretched predicate is its own figure.")
        assertEquals(1, inference.unnamed, "The refusal word is the figure that bears on the list, and its own.")
        assertEquals(1, inference.unextractable, "A blank field is drift, which is this build's to fix.")
        assertEquals(
            0,
            inference.refused,
            "A predicate rule was counted among the placement and subject rules, which is the " +
                "merge that made the declined count unreadable as evidence.",
        )
    }

    @Test
    fun `a date refused for preceding its message is counted, and its finding still written`(): Unit = runBlocking {
        // **The one figure of this pass that is not a reading going nowhere.** Counted among the
        // losses it would say a finding had been given up, when what was given up is a date the
        // record itself shows to be impossible — and the claim is stored, readable and worth
        // keeping. So the row is written, the date is null, and the refusal has a figure of its own.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            // Eighteen seconds before `FIRST`, which is when m1 was sent — the measured shape of the
            // defect on the real archive. The party field names the message's own author, so this
            // is the reading the record really does rule out rather than the unplaceable-party
            // fallback that is refused alongside it: the rationale above holds only of this one.
            restated = { Batched.of("2026-03-02T09:59:42Z|none|$AUTHOR_NAME|The quote gets sent.") },
        )

        val inference = rig.settle()

        assertNull(
            rig.commitments.written.single().due,
            "A date the record shows to be impossible was stored like any other.",
        )
        assertEquals(1, inference.written, "The claim was lost along with its date.")
        assertEquals(
            1,
            inference.backdated,
            "The refusal was not counted, so the rate that says whether the prompt change worked " +
                "cannot be read off a sweep at all.",
        )
        assertEquals(
            0,
            inference.sameInstant,
            "A date before the message was booked as one copied off it, so the figure that bears " +
                "on the prompt is inflated by the case the record settles on its own.",
        )
        assertEquals(
            0,
            inference.lost,
            "A refused date was booked as a finding lost, which reads as a prompt and a parser out " +
                "of step rather than as a date the model answered badly.",
        )
        assertEquals(0, inference.unextractable, "A line that parsed perfectly was reported as drift.")
    }

    @Test
    fun `the message's own instant is refused under a figure of its own`(): Unit = runBlocking {
        // **The two refusals are counted apart, and this is the one that is not rare.** Most of the
        // dated findings measured carried the evidencing message's own instant, where a date before
        // the message was the exception. Added together a sweep could not say whether the model was
        // answering from something it was never given or copying the one instant it was — and only
        // the second is a prompt to fix.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("2026-03-02T10:00:00Z|none|none|The quote gets sent now.") },
        )

        val inference = rig.settle()

        assertNull(
            rig.commitments.written.single().due,
            "The message's own instant was stored as a due date, which is the value half of them " +
                "arrive with and the one nothing downstream can trust.",
        )
        assertEquals(1, inference.written, "The claim was lost along with its date.")
        assertEquals(1, inference.sameInstant, "The echoed instant has no figure, so the rate is unreadable.")
        assertEquals(
            0,
            inference.backdated,
            "An echoed instant was booked as the record contradicting itself, which is the rare " +
                "case where this is the prevalent one.",
        )
    }

    @Test
    fun `a restatement the model will not give is told from one that produced nothing`(): Unit = runBlocking {
        // **Another way a finding fails to become a row**, and the one a real sweep spent a third
        // of its expensive stage on with the log able to say only that findings had been lost. A
        // refusal is the model's answer and will be its answer again; a reply answered, readable and
        // unparseable is a prompt and a parser out of step, which is this build's to fix. One figure
        // covering both sent a reader to the wrong half of the pass.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2")),
            classified = { key -> Batched.of("commitment|$key|plain|none") },
            restated = { key ->
                if (key.startsWith("m1")) Batched.refused() else Batched.of("not the three fields this expects")
            },
        )

        val inference = rig.settle()

        assertEquals(1, inference.unanswered, "The refusal was not counted as the model declining.")
        assertEquals(
            1,
            inference.unextractable,
            "A reply read perfectly and parsed to nothing was reported as something else, so the " +
                "one failure a deployment can act on stays invisible.",
        )
        assertEquals(
            0,
            inference.lost,
            "Both were added into the losses, where a refusal that will refuse again sits beside " +
                "a parser to go and fix.",
        )
        assertEquals(0, inference.written, "Neither reading reached the store.")
        assertEquals(
            listOf(message("m1"), message("m2")),
            rig.derivations.marked.sortedBy { it.nativeId },
            "Both messages were read, so both are settled: offering either again buys the same answer.",
        )
    }

    @Test
    fun `two failures in a row leave the row counted once rather than three times`(): Unit = runBlocking {
        // Two failures and not one: a figure is lost or counted twice where a batch is read again,
        // and one failure cannot tell a counter that resets from one that accumulates. The replies
        // are retained, so each retry re-reads the same submission and re-offers the same reading.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|none|The quote gets sent.") },
            failedCommitmentWrites = 2,
        )

        val failures = rig.driveThrough(2)
        val inference = rig.inferrer.inferBatch()

        assertEquals(2, failures, "The write did not fail twice, so this case tested one failure or none.")
        assertEquals(
            1,
            inference.written,
            "The row this batch caused was not reported as caused. A failed write stores nothing, " +
                "so the retry is the insert — counted as an overwrite, the figure is lost for good.",
        )
        assertEquals(
            1,
            inference.attempted,
            "The attempts of the batches that failed were carried into this one, so one reading " +
                "offered three times reads as three readings.",
        )
        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "Nothing was marked until the write landed, so a failure settled a record against a " +
                "finding that was never stored.",
        )
    }

    @Test
    fun `a write that fails after another has landed claims no row twice`(): Unit = runBlocking {
        // A batch writes the two findings and the attributes in sequence, each its own transaction,
        // so a failure between them leaves the first landed and the second not. The retry re-offers
        // both — and the figures have to say that one of them was already there, or the sweep claims
        // two rows for one.
        val rig = Rig(
            pending = listOf(message("m1"), message("m2")),
            classified = { key ->
                if (key == "m1") {
                    Batched.of("commitment|m1|plain|none")
                } else {
                    Batched.of("attribute|m2|plain|$AUTHOR_NAME")
                }
            },
            restated = { key ->
                if (key.startsWith("m1")) {
                    Batched.of("none|none|none|The quote gets sent.")
                } else {
                    Batched.of("none|works at|none|Acme Ltd")
                }
            },
            failedStatementWrites = 1,
        )

        val failures = rig.driveThrough(1)
        val inference = rig.inferrer.inferBatch()

        assertEquals(1, failures, "The statement write did not fail, so nothing partial was tested.")
        assertEquals(
            1,
            inference.written,
            "The commitment landed before the failure, so the retry caused one row and not two.",
        )
        assertEquals(
            1,
            inference.overwritten,
            "The reading that was already stored is unaccounted for, which is the figure going " +
                "missing rather than being counted twice — the other direction of the same defect.",
        )
        assertEquals(2, inference.attempted)
    }

    @Test
    fun `the version changes with the model and with how much context a message is read in`(): Unit = runBlocking {
        val one = versionOf(FakeModelProvider(version = "a-model"), Bounds(HORIZON, contextReach = 10))
        val otherModel = versionOf(FakeModelProvider(version = "another-model"), Bounds(HORIZON, contextReach = 10))
        val otherShape = versionOf(FakeModelProvider(version = "a-model"), Bounds(HORIZON, contextReach = 5))

        assertTrue(one != otherModel, "Swapping the model re-derives nothing: $one")
        assertTrue(
            one != otherShape,
            "Reading a message with half as much conversation around it re-derives nothing, so two " +
                "findings read under different amounts of context are keyed alike: $one",
        )
    }

    @Test
    fun `the version names the reasoning effort`(): Unit = runBlocking {
        // Effort is the one dial the provider accepts and it controls depth, so it changes what
        // is read — and the watermark's rule is that anything which would make two runs disagree
        // belongs in the version. Nothing else here would notice it being dropped.
        val version = versionOf(FakeModelProvider(version = "a-model"), Bounds(HORIZON))

        assertTrue(
            Extractor.CLASSIFY_EFFORT.name in version && Extractor.REWRITE_EFFORT.name in version,
            "An effort is missing from the version, so changing it re-derives nothing and every " +
                "window already read keeps a finding the new setting would not produce: $version",
        )
    }

    @Test
    fun `the horizon is not in the version`(): Unit = runBlocking {
        // Widening the horizon should re-derive the material it newly admits and nothing else. In
        // the version, it would re-extract everything already read inside the old horizon, at
        // full model cost, for a configuration change.
        assertEquals(
            versionOf(FakeModelProvider(), Bounds(HORIZON)),
            versionOf(FakeModelProvider(), Bounds(HORIZON.minusSeconds(A_YEAR))),
        )
    }

    private suspend fun versionOf(model: FakeModelProvider, bounds: Bounds): String {
        val rig = Rig(model = model, bounds = bounds)
        rig.inferrer.inferBatch()
        return rig.derivations.versionsSeen.single()
    }

    /**
     * One pass, its fakes, and the order they were written in.
     *
     * Assembled here rather than per test because the pass composes six ports and every case
     * varies two of them: a test that named them all would be a list of defaults with the
     * interesting part buried in it.
     */
    // ---- submitting, and reading a submission back -----------------------------------------

    @Test
    fun `one submission carries every selected record, each under its own key`(): Unit = runBlocking {
        // The key is how a reply is matched back to the work that asked for it. Two windows under
        // one key, or a key the pass cannot recognise later, is a window paid for and discarded.
        //
        // One key per *selected* record and not one per stretch, which is also what makes the
        // submission's size the backlog's size: six messages in the conversation and two of them
        // owed is two requests, where overlapping stretches made it three for the same two records.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("none") },
        )

        val submitting = rig.inferrer.inferBatch()

        val submission = rig.model.submissions.single()
        assertEquals(
            listOf("m1", "m5"),
            submission.requests.keys.sorted(),
            "Every selected message should be asked about, once each, and nothing else should be.",
        )
        assertEquals(2, submitting.awaiting)
        assertTrue(
            rig.derivations.marked.isEmpty(),
            "A submitted window settles nothing: the work is owed until the reply is read.",
        )
        assertEquals(0, rig.model.callCount, "Submitting is not asking one call at a time.")
    }

    @Test
    fun `a reply is read against its own window, whatever order they arrive in`(): Unit = runBlocking {
        // The failure this prevents is the quiet one: a pass matching replies by position would
        // attribute one message's findings to another and look like it was working.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            // Only m5's window reports a commitment, and it names m5. Were the
            // replies read positionally this would be attributed to m1's window,
            // which does not read m5 — so the candidate would be refused as not its target's.
            classified = { key -> if (key == "m5") Batched.of("commitment|m5|plain|none") else Batched.of("none") },
            restated = { Batched.of("none|none|none|Something was promised.") },
        )

        rig.settle()

        val commitment = rig.commitments.written.single()
        assertEquals("Something was promised.", commitment.claim)
        assertEquals(
            setOf(evidence("m5", FIRST.plusSeconds(4)).span),
            commitment.evidence.map { it.span }.toSet(),
            "The finding was filed against the window that reported it.",
        )
    }

    @Test
    fun `no step of the pass makes a call of its own, restatements included`(): Unit = runBlocking {
        // **The property that takes the round trip out from under the pass's exclusion**, and the
        // reason nothing here has to keep a running total alive across a throw: a collection that
        // called the provider per candidate could be interrupted part-way with calls already
        // billed, so the figure had to be carried out on the failure. Batched, there is no part-way
        // — every step either submits or parses, and a provider that goes unreachable does it before
        // anything has been read.
        //
        // Asserted over a whole cycle rather than over the submission alone, because the step that
        // used to make the calls was the collection and this is what says it no longer does.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            // Each window cites its own target, so each yields a candidate to restate — several
            // restatements, which is the case a per-candidate call would have shown up in.
            classified = { key -> Batched.of("commitment|$key|plain|none") },
        )

        rig.settle()

        assertTrue(rig.commitments.written.isNotEmpty(), "Nothing was restated, so the count proves nothing.")
        // **Asserted as the shape of the synchronous calls rather than as none of them**, which is
        // what the property actually is: no *restatement* is asked one at a time. The prefix each
        // window's restatements share is warmed by one ordinary call, because a batch cannot be
        // relied on to read its own writes — so the figure that matters is one call per window and
        // every restatement in a submission. Pinned both ways, this still fails for the regression
        // it was written against: asking per candidate would make the count the candidate count and
        // leave the restatement submission short.
        val windows = rig.model.submissions
            .first { it.requests.values.any { request -> request.prompt != Extractor.WARM } }
            .requests.size
        assertEquals(
            List(windows) { Extractor.WARM },
            rig.model.requests.map { it.prompt },
            "Every synchronous call the pass makes has to be a warming call, one per window. A " +
                "restatement among them is a provider round trip held under the pass's exclusion " +
                "and billed at the synchronous rate.",
        )
        assertEquals(
            setOf(SubmissionStage.CLASSIFICATION, SubmissionStage.RESTATEMENT),
            rig.submissions.recorded.map { it.stage }.toSet(),
            "Both stages have to reach the provider as a submission, or one of them is still " +
                "paying full price.",
        )
    }

    @Test
    fun `one refused window does not cost the others their replies`(): Unit = runBlocking {
        // A refusal is reported per request rather than thrown, and this is why: thrown out of a
        // collection it would take every other window's reply with it, and all of them would be
        // read and paid for a second time.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            // m1's window is refused and m5's reports a finding, so the two outcomes come back in
            // one collection — which is the case a refusal thrown rather than reported would have
            // taken down whole.
            classified = { key ->
                when (key) {
                    "m1" -> Batched.refused("a_category")
                    "m5" -> Batched.of("commitment|m5|plain|none")
                    else -> Batched.of("none")
                }
            },
            restated = { Batched.of("none|none|none|Something was promised.") },
        )

        rig.settle()

        assertEquals(1, rig.commitments.written.size, "The windows that answered were still read.")
        // Reported by the step that collected the classifications, which is where a refusal is
        // read — a step later the submission holding it has been settled.
        assertEquals(
            1,
            rig.steps.sumOf { it.declined.size },
            "The refusal was reported rather than swallowed.",
        )
    }

    @Test
    fun `restatements are read against their own candidates, whatever order they arrive in`(): Unit = runBlocking {
        // The same failure the classification stage guards against, one stage further on and with a
        // worse outcome: matched by position, a claim would be filed against another finding's
        // evidence — a citation that checks out and says the wrong thing.
        //
        // Two windows, each naming its own target, and the replies are keyed by candidate so the
        // claim says which one it belongs to. Read positionally the two would swap.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { key ->
                if (key == "m1" || key == "m5") Batched.of("commitment|$key|plain|none") else Batched.of("none")
            },
            restated = { key ->
                Batched.of("none|none|none|The promise from ${key.substringBefore(Inferrer.WITHIN)}.")
            },
        )

        rig.settle()

        assertEquals(
            mapOf(
                message("m1") to "The promise from m1.",
                message("m5") to "The promise from m5.",
            ),
            rig.commitments.written.associate { it.evidence.single().span.record to it.claim },
            "A restatement was filed against a finding it was not asked about.",
        )
    }

    @Test
    fun `one unusable restatement does not withhold the findings submitted beside it`(): Unit = runBlocking {
        // Per candidate rather than per submission, which is the whole reason restatement is one
        // call per finding. A reply nobody can read is a stop reason this build has no name for: a
        // retry gets the same reply and is billed again, so the candidate is given up and named —
        // and the findings that came back alongside it are written.
        val rig = Rig(
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { key ->
                if (key == "m1" || key == "m5") Batched.of("commitment|$key|plain|none") else Batched.of("none")
            },
            restated = { key ->
                if (key.startsWith("m1")) Batched.unreadable() else Batched.of("none|none|none|The promise from m5.")
            },
        )

        val inference = rig.settle()

        assertEquals(
            listOf("The promise from m5."),
            rig.commitments.written.map { it.claim },
            "The unreadable reply took its neighbour's finding with it.",
        )
        assertEquals(1, inference.lost, "The candidate given up on was not counted.")
        assertNotNull(
            inference.unreadable,
            "A reply only a new build can read was counted among the ordinary losses, where " +
                "nothing distinguishes it from a refusal that will refuse again.",
        )
        assertEquals(
            listOf(message("m1"), message("m5")),
            rig.derivations.marked.sortedBy { it.nativeId },
            "The windows were classified and read, so their records are settled: offering them " +
                "again would re-classify and re-restate them for the sake of one sentence.",
        )
    }

    @Test
    fun `the classification stage gets on with the next batch while a restatement waits`(): Unit = runBlocking {
        // Acceptance for the keying: neither stage may be held back because the other has work in
        // flight. A pass that waited here would idle through exactly the turnaround that batching
        // the restatement was meant to spend usefully.
        //
        // The restatement submission is the second this fake mints, so naming it as waiting leaves
        // the classification slot free and the restatement outstanding.
        //
        // What the next classification selects is not this case's business: the records already
        // paid for are kept out of selection by the store, which is where that is asserted — this
        // double offers whatever it was given, every time it is asked.
        val rig = Rig(
            model = FakeModelProvider(waiting = mutableSetOf("${Batched.SUBMISSION}-2")),
            pending = listOf(message("m1")),
            conversation = conversation(2),
            classified = { Batched.of("commitment|m1|plain|none") },
        )

        rig.inferrer.inferBatch()
        val asking = rig.inferrer.inferBatch()
        val meanwhile = rig.inferrer.inferBatch()

        assertEquals(
            1,
            rig.submissions.recorded.count { it.stage == SubmissionStage.RESTATEMENT },
            "The restatement was never asked for, so nothing is waiting and this proves nothing.",
        )
        assertTrue(asking.awaiting > 0, "The restatements were submitted and left outstanding.")
        // The assertion that discriminates: simply waiting would also report work outstanding, so
        // what says the stage got on with anything is a second classification having been made.
        assertEquals(
            2,
            rig.submissions.recorded.count { it.stage == SubmissionStage.CLASSIFICATION },
            "The sweep waited on the restatement instead of classifying the next windows, so a " +
                "stage was held back by work belonging to the other one.",
        )
        assertTrue(
            meanwhile.awaiting > 0,
            "A sweep finding the restatement unfinished reported nothing outstanding.",
        )
        assertNotNull(
            rig.submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.RESTATEMENT),
            "The restatement left flight without having been read.",
        )
    }

    @Test
    fun `a step of one stage reports what the other stage still has outstanding`(): Unit = runBlocking {
        // **The figure the driver reads to tell a pass that is working from one that is stuck.** A
        // restatement step knows nothing about the classification stage, so left alone it reports
        // zero outstanding — and a sweep whose marks happened not to reduce the backlog then reads
        // zero-and-unmoved as a stall and warns about a pass that is waiting correctly.
        //
        // Driven by letting the restatement land only once a second classification is in flight,
        // which is the ordinary state of this pass rather than a contrived one.
        val rig = Rig(
            model = FakeModelProvider(waiting = mutableSetOf("${Batched.SUBMISSION}-2")),
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
        )

        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()
        // The restatements answer now, with the classification submitted above still outstanding.
        rig.model.waiting.clear()
        val wrote = rig.inferrer.inferBatch()

        assertEquals(1, wrote.records, "The restatements were not read, so this proves nothing.")
        assertNotNull(
            rig.submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION),
            "No classification is outstanding, so there is nothing for this step to report.",
        )
        assertTrue(
            wrote.awaiting > 0,
            "A step that wrote its restatements reported nothing outstanding while a " +
                "classification was still with the provider. A sweep reads that as a stall.",
        )
    }

    @Test
    fun `a restatement the provider never answered leaves its records owed`(): Unit = runBlocking {
        // **The case a batch that expires arrives as.** The provider ends an unfinished batch at
        // about a day, which is inside this pass's patience, so an expired submission is read
        // through the ready path and every request of it comes back unanswered. Marking on that
        // would settle records against findings nobody ever read, and nothing
        // short of the message being edited or this pass's version moving would offer it again: the
        // mark carries the cut-off it was selected under.
        //
        // Told apart from a reply that was answered and cannot be read, which is settled rather
        // than held: that one gives up a single finding out of a window already read, where
        // holding it would re-classify that message on every sweep until a new build landed.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.unfinished() },
        )

        val inference = rig.settle()

        assertTrue(
            rig.derivations.marked.isEmpty(),
            "A record was settled against a restatement the provider never answered. The finding " +
                "is gone and nothing will offer the record again.",
        )
        assertEquals(0, inference.records)
        assertEquals(1, inference.unread, "An unanswered request is owed, not lost.")
        assertEquals(0, inference.lost)
        assertTrue(rig.commitments.written.isEmpty())
    }

    @Test
    fun `an unanswered restatement whose record has left its conversation leaves it owed`(): Unit = runBlocking {
        // The two shortfalls at once, and the order they are read in is what decides the records.
        // A request the provider never answered is owed; a record the conversation no longer holds
        // has no window to be read against. Read the other way round the window is missed first,
        // the finding is counted lost, and nothing is held back — so the record settles against a
        // reply that never came, which is the case this pass has no second chance at.
        //
        // The record is held back by the key that asked about it rather than by the window, which
        // is what makes the two independent: a key names one message, so what it may settle is
        // known without the corpus being consulted at all.
        val remembered = FakeSubmissionRepository()
        val model = FakeModelProvider()
        val classified: (String) -> BatchOutcome = { Batched.of("commitment|m1|plain|none") }
        val submitting = Rig(
            model = model,
            pending = listOf(message("m1")),
            classified = classified,
            submissions = remembered,
        )
        // Classify, then collect and hand the finding over: the restatement is outstanding after
        // this and the records it may settle travel with it.
        submitting.inferrer.inferBatch()
        submitting.inferrer.inferBatch()

        val collecting = Rig(
            model = model,
            conversation = withdrawn("m1"),
            classified = classified,
            restated = { Batched.unfinished() },
            submissions = remembered,
        )

        val collected = collecting.inferrer.inferBatch()

        assertTrue(
            collecting.derivations.marked.isEmpty(),
            "The provider never answered, so the record is owed whether or not a window for it can " +
                "still be built.",
        )
        assertEquals(1, collected.unread, "An unanswered request is owed, not lost.")
        assertEquals(0, collected.lost)
    }

    @Test
    fun `an answered restatement whose record has left its conversation is lost rather than owed`(): Unit =
        runBlocking {
            // The other half of the same read order, which is what makes the first half a
            // distinction rather than a blanket rule. Here the provider did answer: the message was
            // classified and the reply is readable, so what is gone is one finding whose evidence
            // nothing can check. Holding the record for that would re-classify and re-restate it
            // every sweep, for as long as it stayed out of its conversation.
            val remembered = FakeSubmissionRepository()
            val model = FakeModelProvider()
            val classified: (String) -> BatchOutcome = { Batched.of("commitment|m1|plain|none") }
            val submitting = Rig(
                model = model,
                pending = listOf(message("m1")),
                classified = classified,
                submissions = remembered,
            )
            submitting.inferrer.inferBatch()
            submitting.inferrer.inferBatch()

            val collecting = Rig(
                model = model,
                conversation = withdrawn("m1"),
                classified = classified,
                restated = { Batched.of(RESTATED) },
                submissions = remembered,
            )

            val collected = collecting.inferrer.inferBatch()

            assertEquals(
                listOf(message("m1")),
                collecting.derivations.marked,
                "The message was classified and the reply was readable, so the record settles: only " +
                    "the one finding that can no longer be placed is given up.",
            )
            assertEquals(1, collected.lost, "A finding that cannot be placed is lost, not owed.")
            assertEquals(0, collected.unread)
        }

    @Test
    fun `a restatement that is unreadable and whose record has gone still reports why`(): Unit = runBlocking {
        // Both failures at once, which is the case the reason is easiest to drop in: the window is
        // gone, so the extractor is never asked, and the stop reason this build cannot name would
        // be left to look like an ordinary parse failure. It is the one loss worth investigating,
        // because only a new build clears it, and it is also the one the operator cannot infer.
        val remembered = FakeSubmissionRepository()
        val model = FakeModelProvider()
        val classified: (String) -> BatchOutcome = { Batched.of("commitment|m1|plain|none") }
        val submitting = Rig(
            model = model,
            pending = listOf(message("m1")),
            classified = classified,
            submissions = remembered,
        )
        submitting.inferrer.inferBatch()
        submitting.inferrer.inferBatch()

        val collecting = Rig(
            model = model,
            conversation = withdrawn("m1"),
            classified = classified,
            restated = { Batched.unreadable() },
            submissions = remembered,
        )

        val collected = collecting.inferrer.inferBatch()

        assertNotNull(
            collected.unreadable,
            "The record had also gone, so the reason was dropped and the batch reports an " +
                "ordinary lost finding — hiding the one failure a deploy can clear.",
        )
        assertEquals(1, collected.lost)
    }

    @Test
    fun `a restatement submitted by another build leaves its records owed`(): Unit = runBlocking {
        // **The one failure here that costs records rather than findings, and it arrives on a
        // deploy.** A restatement submission is allowed most of a day, so one made before a build
        // that changes the classification reply's shape is collected by the build after it. The
        // line it carries is this build's own data, written by the classification that found the
        // candidate — and a line the parser cannot read means the shape changed, not that the model
        // answered badly.
        //
        // Read as an ordinary lost finding, every record the submission carried would be marked:
        // settled against a reading no build ever got to make, under the cut-off they were selected
        // with, so nothing offers them again and the findings are gone for good. The provider's
        // replies arrive perfectly well, so nothing else in the collection notices.
        val remembered = FakeSubmissionRepository()
        val model = FakeModelProvider()
        val id = model.submit(mapOf("m1${Inferrer.WITHIN}0" to request()))
        remembered.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.RESTATEMENT,
            kind = RecordKind.MESSAGE,
            id = id,
            // Three fields: what every build before the subject field wrote.
            requests = listOf(SubmittedWindow("m1${Inferrer.WITHIN}0", CHAT, "commitment|m1|plain")),
            records = listOf(message("m1")),
            selectedAt = SELECTED,
            // The build that wrote the three-field line, which is a different wording by
            // definition: it is the version the submission was made under and not this one.
            version = "inference-2:a-build-before-this-one",
        )

        // **The provider answers perfectly**, which is the whole point: nothing about the reply is
        // wrong, so an unscripted collection returning no result would exercise the unanswered path
        // instead and this case would pass without ever reaching the one it is about.
        model.answering = { Batched.of(RESTATED) }

        val rig = Rig(model = model, conversation = conversation(2), submissions = remembered)
        val collected = rig.inferrer.inferBatch()

        assertTrue(
            rig.derivations.marked.isEmpty(),
            "A record was marked against a line no build could read. The finding is gone and " +
                "nothing will offer the record again.",
        )
        assertEquals(0, collected.records)
        assertEquals(1, collected.unread, "A reading no build made is owed, not lost.")
        assertEquals(0, collected.lost)
        assertTrue(rig.commitments.written.isEmpty())
    }

    @Test
    fun `a request asked under a key this build does not mint leaves its records owed`(): Unit = runBlocking {
        // The same deploy, by the other route. The build before this one keyed a restatement on the
        // stretch its window began at, which need not have been one of the records the submission
        // carried — so the key resolves to no record this collection may decide about. Nothing can
        // be held back by name, so the whole submission is given up: marking the records whose
        // requests *did* parse would settle them against a collection that lost the rest.
        val remembered = FakeSubmissionRepository()
        val model = FakeModelProvider()
        val id = model.submit(mapOf("m9${Inferrer.WITHIN}0" to request()))
        remembered.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.RESTATEMENT,
            kind = RecordKind.MESSAGE,
            id = id,
            // A key naming a record the submission does not carry — an old stretch's first message.
            requests = listOf(SubmittedWindow("m9${Inferrer.WITHIN}0", CHAT, RESTATED_LINE)),
            records = listOf(message("m1")),
            selectedAt = SELECTED,
            // The build that keyed a restatement on its window's first message, as above.
            version = "inference-2:a-build-before-this-one",
        )

        // Answered, for the reason given in the case above: this is about the key, not the reply.
        model.answering = { Batched.of(RESTATED) }

        val rig = Rig(model = model, conversation = conversation(2), submissions = remembered)
        val collected = rig.inferrer.inferBatch()

        assertTrue(rig.derivations.marked.isEmpty(), "A record was marked on a key this build cannot place.")
        assertEquals(1, collected.unread)
    }

    @Test
    fun `a reword while a batch is outstanding marks under the wording that read the window`(): Unit =
        runBlocking {
            // **The one window the watermark's promise does not cover on its own.** A batch is
            // allowed most of a day and a reword arrives by deploy, so the two coincide whenever a
            // prompt card is deployed while this pass has work with the provider — the ordinary
            // state of a sweeping deployment. Marked under the version current at collection, those
            // records claim a wording that never read them, and nothing revisits them: the only
            // thing that retires a mark is the version it names moving, and it would be naming the
            // one now current.
            //
            // Two collections, because a finding takes two: the classification is read and its
            // restatement submitted, and the mark is written when *that* comes back. So the version
            // has to survive both hops, not one.
            val rig = Rig(
                pending = listOf(message("m2")),
                conversation = conversation(4),
                bounds = Bounds(notBefore = HORIZON, contextReach = 1),
                classified = { Batched.of("commitment|m2|plain|none") },
            )

            // The windows go to the provider under the wording deployed now.
            rig.inferrer.inferBatch()
            val readBy = rig.derivations.versionsSeen.first()

            // The deploy lands while the classification is outstanding. Moved by the model rather
            // than by rewording an instruction because the version is opaque and compared only for
            // equality — what the pass can see is that the string differs, and nothing downstream
            // distinguishes which of its parts moved.
            rig.model.version = "a-model-this-horizon-has-not-been-read-under"

            // Collect the classification, which submits the restatement; then collect that, which
            // is the step that writes the finding and marks.
            rig.inferrer.inferBatch()
            rig.inferrer.inferBatch()
            // Asked after the drive so the current version is on record to compare against, and
            // because asking it is what a driver does between batches.
            rig.inferrer.owed()

            assertEquals(
                listOf(message("m2")),
                rig.derivations.marked,
                "The window was read and its finding written, so its record is settled.",
            )
            assertEquals(
                listOf(readBy),
                rig.derivations.markedUnder,
                "The mark names the wording deployed at collection rather than the one that read " +
                    "the window. The record then claims to be current under an instruction that " +
                    "never saw it, and nothing will offer it again.",
            )
            assertNotEquals(
                readBy,
                rig.derivations.versionsSeen.last(),
                "The reword did not move the version, so this case cannot tell the two marks " +
                    "apart and would pass whichever one was written.",
            )
        }

    @Test
    fun `a submission naming no version is marked under the one this sweep is running`(): Unit =
        runBlocking {
            // **The migration's own case, and the only one the fallback is for.** A batch already
            // with the provider when the version column arrived carries none, and a null has to
            // mean the version current at collection — which is exactly what this pass did before
            // a submission carried one, so it costs nothing and loses nothing.
            //
            // Driven through the double rather than left to the store, because the store can show
            // only that it reads a null back: what has to survive one is the caller.
            val rig = Rig(
                pending = listOf(message("m2")),
                conversation = conversation(4),
                bounds = Bounds(notBefore = HORIZON, contextReach = 1),
                classified = { Batched.of("commitment|m2|plain|none") },
            )

            rig.inferrer.inferBatch()
            val running = rig.derivations.versionsSeen.first()
            rig.submissions.recorded.forEach { rig.submissions.forgetVersionOf(it.id) }

            rig.inferrer.inferBatch()
            // The restatement this just submitted has no version to inherit either, so forgetting
            // again is what keeps the case about the fallback rather than about the first hop.
            rig.submissions.recorded.forEach { rig.submissions.forgetVersionOf(it.id) }
            rig.inferrer.inferBatch()

            assertEquals(listOf(message("m2")), rig.derivations.marked)
            assertEquals(
                listOf(running),
                rig.derivations.markedUnder,
                "A submission that names no version has to be marked under the one this sweep is " +
                    "running. Anything else is a mark naming a version no build would produce.",
            )
        }

    @Test
    fun `a submission the provider has not finished with settles nothing`(): Unit = runBlocking {
        // Treated as a collection of nothing, a caller would settle work the provider is still
        // doing and has already been billed for.
        // Several windows, not one: a submission of one cannot tell a reported count from a
        // hardcoded 1, so the assertion below would hold against either.
        val rig = Rig(
            model = FakeModelProvider(waiting = mutableSetOf("fake-submission")),
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("commitment|m1|plain|none") },
        )

        val submitting = rig.inferrer.inferBatch()
        val waiting = rig.inferrer.inferBatch()

        assertEquals(0, waiting.records)
        assertEquals(TokenUsage.NONE, waiting.usage, "Asking after a submission is not billed.")
        assertEquals(2, submitting.awaiting, "Both selected records went in one submission.")
        assertEquals(
            2,
            waiting.awaiting,
            "A waiting sweep reports how many requests are actually outstanding. A constant here " +
                "would log one piece of work for a submission holding sixteen.",
        )
        assertTrue(rig.derivations.marked.isEmpty())
        assertTrue(rig.commitments.written.isEmpty())
    }

    @Test
    fun `a submission nobody will ever answer is given up on rather than held for ever`(): Unit =
        runBlocking {
            // Only a collection clears the in-flight slot, so without this "in flight" and "in
            // flight for ever" are the same state and the pass is stuck with nothing in the build
            // able to free it.
            val rig = Rig(
                model = FakeModelProvider(waiting = mutableSetOf("fake-submission")),
                pending = listOf(message("m1")),
                classified = { Batched.of("commitment|m1|plain|none") },
                // Submitted at the epoch, read against a horizon decades later: far past patience.
                submissions = FakeSubmissionRepository(clock = Instant.EPOCH),
            )

            rig.inferrer.inferBatch()
            val gaveUp = rig.inferrer.inferBatch()

            assertNotNull(gaveUp.abandoned, "The batch gave up without saying how long it had waited.")
            assertEquals(1, gaveUp.unread)
            assertNull(
                rig.submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION),
                "An abandoned submission still in flight would hold the pass for ever.",
            )
            // And the pass moves again: the records were never marked, so they are owed and the
            // next batch submits them afresh.
            val afterwards = rig.inferrer.inferBatch()
            assertTrue(
                afterwards.awaiting > 0,
                "After giving up the pass should submit again rather than stay wedged.",
            )
        }

    @Test
    fun `a window with no text to classify settles its records without a call`(): Unit = runBlocking {
        // A sticker has nothing for a model to find, and asking anyway is billed for
        // the instruction. So the pass settles it unasked — and that is the half worth pinning: a
        // window left owed instead would be re-selected every sweep, and because selection takes
        // the oldest owed records first, the pass would never get past it while costing nothing to
        // show for it. Nothing else in the suite builds a message without text.
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = List(2) { index ->
                Message(
                    externalId = message("m${index + 1}"),
                    sourceTime = FIRST.plusSeconds(index.toLong()),
                    author = AUTHOR,
                    text = "",
                )
            },
        )

        val inference = rig.inferrer.inferBatch()

        assertEquals(
            listOf(message("m1")),
            rig.derivations.marked,
            "A window with nothing to classify left its records owed, so the pass will offer them " +
                "again on every sweep and never advance past them.",
        )
        assertEquals(1, inference.records)
        assertEquals(0, inference.awaiting, "Nothing was asked, so nothing is outstanding.")
        assertTrue(rig.model.submissions.isEmpty(), "A window of stickers was submitted and billed for.")
        assertEquals(TokenUsage.NONE, inference.usage, "A window nobody asked about reported a cost.")
    }

    @Test
    fun `a provider that never landed the call is not an empty batch`(): Unit = runBlocking {
        // Raised by the port rather than returned, and caught nowhere in this pass: a transport
        // failure is the absence of an answer, so swallowing it would settle the corpus against a
        // call that never happened. The pass holds no `catch` at all, which makes this an assertion
        // about code that is absent — and the only way to break it is to add some.
        val rig = Rig(
            model = FakeModelProvider(failingSubmit = ModelUnavailable("the model could not be reached")),
            pending = listOf(message("m1")),
        )

        assertFailsWith<ModelUnavailable> { rig.inferrer.inferBatch() }
        assertTrue(rig.derivations.marked.isEmpty(), "Records were settled against a call that never landed.")
    }

    @Test
    fun `a classification whose record has gone is charged for, not only counted`(): Unit = runBlocking {
        // A record can leave its conversation between being classified and being collected, which
        // is the one way a window is lost now that it is named by the record it reads. The call was
        // still billed, and a sweep that counted it without charging it would hide the spend in
        // exactly the case worth investigating — a submission that bought nothing.
        val remembered = FakeSubmissionRepository()
        val model = FakeModelProvider()
        val classified: (String) -> BatchOutcome = { Batched.of("none") }
        val submitting = Rig(
            model = model,
            pending = listOf(message("m1")),
            classified = classified,
            submissions = remembered,
        )
        submitting.inferrer.inferBatch()

        // The same provider and the same submission — so the reply comes back billed exactly as it
        // was — read against a conversation none of the submitted records is in. Every request then
        // has a paid-for reply and nowhere to attribute it.
        val collecting = Rig(
            model = model,
            conversation = List(2) { index ->
                Message(
                    externalId = message("elsewhere${index + 1}"),
                    sourceTime = FIRST.plusSeconds(index.toLong()),
                    author = AUTHOR,
                    text = "a message from a conversation this submission never asked about",
                )
            },
            classified = classified,
            submissions = remembered,
        )

        val collected = collecting.inferrer.inferBatch()

        assertEquals(1, collected.unread, "The window could not be found, so it was not read.")
        assertTrue(
            collected.usage.outputTokens > 0,
            "The provider billed for the classification whether or not a window could be found " +
                "for it, so the sweep has to report the spend rather than only the shortfall.",
        )
        assertEquals(
            Reply.SAMPLE_USAGE,
            remembered.settled.single().second,
            "The submission is still billed what the batch cost, and settled rather than left " +
                "in flight — nothing further can ever be read from it.",
        )
        assertTrue(collecting.derivations.marked.isEmpty(), "Nothing was read, so nothing settles.")
    }

    @Test
    fun `each window's prefix is warmed once before the restatement that shares it`(): Unit = runBlocking {
        // **The batch cannot be relied on to read its own writes**, so the entry has to exist
        // before the submission goes over. Once per window and not once per request is the whole
        // saving: the siblings of one window share a prefix, so a second write would pay for the
        // thing caching was meant to avoid, twice.
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = conversation(3),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            // Two findings in one message, so two restatement requests share one window.
            classified = { Batched.of("commitment|m1|plain|none\nevent|m1|plain|none") },
        )

        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()

        val warmed = rig.model.requests.filter { it.prompt == Extractor.WARM }
        assertEquals(
            1,
            warmed.size,
            "Two requests share one window, so one warming call is what the prefix costs — " +
                "${rig.model.requests.size} synchronous call(s) were made in total.",
        )
        val submitted = rig.model.submissions.last().requests.values
        assertEquals(2, submitted.size, "Both findings are still submitted.")
        assertEquals(
            submitted.map { it.shared }.distinct(),
            warmed.map { it.shared },
            "The warming call has to carry the prefix the siblings share, byte for byte — a " +
                "prefix is shared only up to the first byte that differs, so a warming call that " +
                "rendered the window differently would write an entry nothing reads.",
        )
    }

    @Test
    fun `a warming call that succeeds is charged to the day`(): Unit = runBlocking {
        // **The one billed call in this pass that no submission records.** Everything else is
        // handed to the batch endpoint and billed against a stored submission, which is what a
        // day's spend is totalled from. Unrecorded, the pass could sweep all night paying to warm
        // prefixes against a day that read as barely spent — and the bound that is supposed to
        // stop it would never see the cost the warming added.
        val billed = TokenUsage(inputTokens = 1_600, outputTokens = 8, cacheWriteTokens = 1_550)
        val rig = Rig(
            model = FakeModelProvider(listOf(Reply.Returns(ModelOutcome.Answered("warmed", billed)))),
            pending = listOf(message("m1")),
            conversation = conversation(3),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("commitment|m1|plain|none") },
        )

        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()

        assertEquals(
            listOf(Deriver.RECOGNITION to billed),
            rig.spend.charges,
            "A warming call is billed and belongs to no submission, so this is the only place its " +
                "cost can reach the day it was spent on.",
        )
    }

    @Test
    fun `with nothing to spend a restatement is still collected and its records settled`(): Unit = runBlocking {
        // **An exhausted day must stop the spending, not the writing back.** A restatement
        // collection asks the provider for nothing: the reply is already paid for, and what it does
        // is write the findings and settle the records they came from. Deferred instead, those
        // findings sit bought and unwritten — and a submission left in flight long enough is
        // abandoned, which loses the work and records its real cost as nothing.
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = conversation(3),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|none|The quote gets sent.") },
        )
        // Classify, then collect and submit the restatement, so one is outstanding and paid for.
        rig.inferrer.inferBatch()
        rig.inferrer.inferBatch()

        val collected = rig.inferrer.inferBatch(maySpend = false)

        assertTrue(
            rig.commitments.written.isNotEmpty(),
            "The findings a submission already bought were not written, so the money is spent and " +
                "nothing came of it.",
        )
        assertEquals(1, collected.records, "The records the restatement covered should settle.")
    }

    @Test
    fun `with nothing to spend no submission is made`(): Unit = runBlocking {
        val rig = Rig(
            pending = listOf(message("m1")),
            conversation = conversation(3),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("commitment|m1|plain|none") },
        )

        val did = rig.inferrer.inferBatch(maySpend = false)

        assertEquals(
            emptyList(),
            rig.model.submissions,
            "Nothing may be submitted on a day with nothing left to spend: the windows stay owed " +
                "and the next day reads them.",
        )
        assertEquals(0, rig.model.callCount, "And no synchronous call either, warming included.")
        assertEquals(0, did.records, "Nothing settled, because nothing was read.")
    }

    @Test
    fun `a warming call that fails carries what its siblings had already cost`(): Unit = runBlocking {
        // **The hazard the batched shape had removed, and the reason it is handled rather than
        // accepted.** These are concurrent billed calls, so a failure of one leaves the others
        // already paid for. A figure that left carrying only the failing call's usage would
        // under-report the day by everything the siblings cost — in exactly the accounting the
        // daily bound is read from.
        val rig = Rig(
            // The first warming call answers and is billed; the second gives up carrying its own.
            // Both are read before anything is raised, so the figure that leaves is the total.
            model = FakeModelProvider(
                listOf(
                    Reply.Returns(ModelOutcome.Answered("warmed", TokenUsage(inputTokens = 700, outputTokens = 9))),
                    Reply.Fails(
                        ModelUnavailable("gave up", usage = TokenUsage(inputTokens = 300, outputTokens = 4)),
                    ),
                ),
            ),
            // Two owed records in one conversation, so two windows and two warming calls.
            pending = listOf(message("m1"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { key -> Batched.of("commitment|$key|plain|none") },
        )

        // The first step submits the classification; the second reads it back and is the one that
        // warms, so the failure arrives there rather than on the first.
        rig.inferrer.inferBatch()
        val failure = assertFailsWith<ModelUnavailable> { runBlocking { rig.inferrer.inferBatch() } }

        assertEquals(1_000, failure.usage.inputTokens, "Both warming calls' input has to be carried.")
        assertEquals(13, failure.usage.outputTokens, "And both their output.")
    }

    @Test
    fun `every selected window is handed over, and nothing is held back`(): Unit = runBlocking {
        // Nothing prices a
        // submission before it is made and the bill arrives a sweep or more later, so how much to
        // commit is not a question this stage can answer. What bounds the spending is the day's own
        // total, read by the driver before this stage runs at all — so a stage that runs commits
        // the batch it was given, and a day with nothing left runs no stage rather than a smaller
        // one. A window silently dropped here would read from outside exactly like a corpus with
        // less in it, which is what `held` reporting zero is asserted for.
        val rig = Rig(
            pending = listOf(message("m1"), message("m3"), message("m5")),
            conversation = conversation(6),
            bounds = Bounds(notBefore = HORIZON, contextReach = 1),
            classified = { Batched.of("none") },
        )

        val submitting = rig.inferrer.inferBatch()

        assertEquals(3, submitting.awaiting, "Every selected window goes in the submission.")
        assertEquals(0, submitting.held, "Nothing is held back, so nothing is reported as held.")
        assertEquals(3, rig.model.submissions.single().requests.size)
    }

    /**
     * The acceptance check that the collapse does not cost a call per pair, asserted from the one
     * place it can be: the provider was never asked.
     */
    @Test
    fun `a pair the vectors settle is merged without the provider being asked`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = pair("will send the quote", "the quote is to be sent")
        val rig = Rig(
            model = model,
            // Nothing owed, which is the sweep the collapse runs on.
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = sameFact(pair),
        )

        val did = rig.inferrer.collapseBatch()

        assertEquals(listOf(pair), rig.collapse.merged, "the pair the vectors settled was merged")
        assertEquals(1, did.merged)
        assertEquals(0, did.awaiting, "nothing was handed to the provider")
        assertEquals(
            emptyList(),
            model.submissions,
            "a collapse the vectors settled must reach no submission at all: a deduplication " +
                "costing a call per pair would be dearer than the readings it collapses",
        )
        assertEquals(TokenUsage.NONE, did.usage, "and so it spent nothing")
    }

    /**
     * What the cheap tier's own answer is recorded under, which is the version this stage is retired
     * on and the half the store tests cannot hold — they are handed a version by the case.
     *
     * **Everything that decided the rule-out has to be in it**: the sidecar's version and the two
     * bounds its vectors were weighed against. Naming the sidecar alone would leave both bounds
     * outside every version, so moving one would ship a behavioural change that invalidated nothing
     * — no pair owed again, nothing re-compared, and the store keeping answers produced by
     * arithmetic the build no longer performs.
     *
     * **Asserted against the production function and not a written-out string**, so the format may
     * change without this case needing an edit. That the function actually moves when a bound moves
     * is `CollapseTest`'s to assert, and that the difference makes the pairs owed again through real
     * SQL is `CollapseStoreTest`'s; this is the one that says the value reaching the store is that
     * function's and not the sidecar's bare version.
     */
    @Test
    fun `a pair the vectors put apart is recorded under the vector version`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = pair("will send the quote", "the invoice was paid")
        val rig = Rig(
            model = model,
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = apart(pair),
        )

        val did = rig.inferrer.collapseBatch()

        assertEquals(emptyList(), model.submissions, "ruling a pair out is free, so it costs nothing")
        assertEquals(emptyList(), rig.collapse.merged, "and the vectors put them apart, so neither moves")
        // **Recorded, and that is deliberate.** Candidate selection is oldest-first over a bound, so
        // a pair nothing writes down comes back at the head of the queue on every sweep and the
        // pairs behind it are never reached — the stage would compare one page for ever.
        assertEquals(
            listOf(pair.survivor.id to pair.superseded.id),
            rig.collapse.keptApart,
            "the cheap tier's answer is written down too",
        )
        assertEquals(
            listOf(runningVectorsVersion("fake-embeddings")),
            rig.collapse.keptApartBy,
            "under the sidecar's version and both bounds that read its vectors, so moving any of " +
                "the three retires the judgement and nothing else does",
        )
        assertEquals(
            listOf(runningVectorsVersion("fake-embeddings")),
            rig.collapse.comparedUnder.distinct(),
            "and the selection excludes under that same value, or the pair is ruled out under one " +
                "version and offered under another, for ever",
        )
        assertEquals(0, did.awaiting)

        // **The count the driver watches is built at a call site of its own**, so it is asserted
        // here rather than taken on trust: a bound folded into the selection's version and not into
        // the count's would have the count honour a rule-out the selection re-offers, which the
        // driver reads as the stage having caught up with pairs it is about to be handed.
        rig.inferrer.collapseOwed()
        assertEquals(
            rig.collapse.comparedUnder.distinct(),
            rig.collapse.countedUnder.distinct(),
            "the count and the selection name one value, or the driver reads progress where there " +
                "is none",
        )
    }

    @Test
    fun `a pair the vectors cannot settle is submitted and then merged on the verdict`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(
            model = model,
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
            adjudicated = { Batched.of(Extractor.ONE_FACT) },
        )

        val submitted = rig.inferrer.collapseBatch()
        assertEquals(1, submitted.awaiting, "the pair the vectors left is with the provider")
        assertEquals(emptyList(), rig.collapse.merged, "and nothing is merged until it answers")

        val read = rig.inferrer.collapseBatch()
        assertEquals(listOf(pair), rig.collapse.merged)
        assertEquals(1, read.merged)
        assertEquals(emptyList(), rig.collapse.keptApart)
    }

    @Test
    fun `a pair the model reads as two facts is recorded so that it is never asked about again`(): Unit =
        runBlocking {
            val pair = pair("will draft the contract", "will sign the contract")
            val rig = Rig(
                pending = emptyList(),
                candidates = listOf(pair),
                vectors = borderline(pair),
                adjudicated = { Batched.of(Extractor.TWO_FACTS) },
            )

            rig.inferrer.collapseBatch()
            val read = rig.inferrer.collapseBatch()

            assertEquals(emptyList(), rig.collapse.merged, "two facts are not one row")
            assertEquals(
                listOf(pair.survivor.id to pair.superseded.id),
                rig.collapse.keptApart,
                "the verdict is written down, or the next idle sweep buys it again",
            )
            // **Under the adjudication version and not the embedding one**, which is the half the
            // store tests cannot hold: they are handed a version by the case, so passing the cheap
            // tier's here would leave them green while every paid verdict was recorded as a vector
            // rule-out — retired by the wrong thing, and for the wrong reason.
            assertEquals(
                listOf(rig.extractor.adjudicationVersion()),
                rig.collapse.keptApartBy,
                "a paid verdict names the version that bought it",
            )
            assertEquals(
                listOf(rig.extractor.adjudicationVersion()),
                rig.collapse.adjudicatedUnder.distinct(),
                "and the selection excludes under that same version, or the pair is bought again " +
                    "on every sweep",
            )
            assertEquals(1, read.apart)
            assertEquals(
                emptyList(),
                read.declined,
                "a pair the model read and called two facts is this stage working, not a failure " +
                    "to report",
            )
            assertNull(read.unreadable)
        }

    @Test
    fun `a reword while a comparison is outstanding records the verdict under the wording that made it`(): Unit =
        runBlocking {
            // **The same window as the reading stages', and it costs more here.** This stage marks
            // no record, so the version on the row it writes is the only thing that can ever
            // retire it: a verdict stamped with a comparison that did not produce it is one
            // nothing re-examines, because the only wording that would retire it is the one it is
            // now wrongly claiming. The reading stages at least have a horizon that a reword
            // re-derives for its own reasons; here there is no second mechanism to catch it.
            val pair = pair("will draft the contract", "will sign the contract")
            val rig = Rig(
                pending = emptyList(),
                candidates = listOf(pair),
                vectors = borderline(pair),
                adjudicated = { Batched.of(Extractor.TWO_FACTS) },
            )

            rig.inferrer.collapseBatch()
            val comparedBy = rig.extractor.adjudicationVersion()

            // The deploy lands while the pair is with the provider.
            rig.model.version = "a-model-these-pairs-have-not-been-compared-under"

            rig.inferrer.collapseBatch()

            assertEquals(
                listOf(comparedBy),
                rig.collapse.keptApartBy,
                "The verdict names the comparison deployed at collection rather than the one that " +
                    "was paid for, so the reword leaves it standing: what offers a pair again is " +
                    "this version moving, and the row already claims the one it moved to.",
            )
            assertNotEquals(
                comparedBy,
                rig.extractor.adjudicationVersion(),
                "The reword did not move the adjudication version, so this case would pass " +
                    "whichever version the verdict carried.",
            )
        }

    @Test
    fun `a comparison naming no version is decided under the one this sweep is running`(): Unit =
        runBlocking {
            // The migration's case on this stage: a comparison already with the provider when the
            // version column arrived carries none, and the verdict is then written under the
            // wording running now — which is what this stage did before a submission carried one.
            val pair = pair("will draft the contract", "will sign the contract")
            val rig = Rig(
                pending = emptyList(),
                candidates = listOf(pair),
                vectors = borderline(pair),
                adjudicated = { Batched.of(Extractor.TWO_FACTS) },
            )

            rig.inferrer.collapseBatch()
            rig.submissions.recorded.forEach { rig.submissions.forgetVersionOf(it.id) }

            rig.inferrer.collapseBatch()

            assertEquals(
                listOf(rig.extractor.adjudicationVersion()),
                rig.collapse.keptApartBy,
                "A submission that names no version has to be decided under the comparison this " +
                    "sweep is running. Anything else is a verdict naming a version no build " +
                    "would produce, which nothing could ever retire.",
            )
        }

    /**
     * A refusal is recorded rather than retried, which is the same call a declined window gets and
     * for the same reason: the port documents a refusal as deterministic, so holding the pair open
     * would buy the identical refusal on every idle sweep for the rest of the deployment.
     */
    @Test
    fun `a comparison the model refuses is recorded rather than asked again`(): Unit = runBlocking {
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
            adjudicated = { Batched.refused("sensitive") },
        )

        rig.inferrer.collapseBatch()
        val read = rig.inferrer.collapseBatch()

        assertEquals(emptyList(), rig.collapse.merged)
        assertEquals(
            listOf(pair.survivor.id to pair.superseded.id),
            rig.collapse.keptApart,
            "a refusal is as final as a verdict, so it is recorded like one",
        )
        assertEquals(1, read.apart)
        assertEquals(
            1,
            read.declined.size,
            "a refusal is carried out whole, so a model that has started declining every " +
                "comparison does not read like a corpus whose near-duplicates are distinct",
        )
        assertNull(
            read.unreadable,
            "and it is not reported as a reply nothing could read: the provider answered, it just " +
                "would not answer this",
        )
    }

    /**
     * The opposite of a refusal: a reply nothing could read leaves the pair alone, because asking
     * again is exactly what clears it and recording a decision would make the failure permanent.
     */
    @Test
    fun `a verdict that cannot be read leaves the pair to be compared again`(): Unit = runBlocking {
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
            adjudicated = { Batched.of("neither one thing nor the other") },
        )

        rig.inferrer.collapseBatch()
        val read = rig.inferrer.collapseBatch()

        assertEquals(emptyList(), rig.collapse.merged)
        assertEquals(
            emptyList(),
            rig.collapse.keptApart,
            "nothing is recorded, so the pair is offered again once a build can read the reply",
        )
        assertEquals(1, read.unread)
    }

    /**
     * The gap a batch introduces: either side may have been merged away while the comparison was
     * with the provider, and merging onto a superseded row would undo the collapse that moved it.
     */
    @Test
    fun `a pair already merged away by the time the verdict lands is not merged again`(): Unit =
        runBlocking {
            val pair = pair("will draft the contract", "the contract is to be drafted")
            val rig = Rig(
                pending = emptyList(),
                candidates = listOf(pair),
                vectors = borderline(pair),
                missing = setOf(pair.superseded.id),
                adjudicated = { Batched.of(Extractor.ONE_FACT) },
            )

            rig.inferrer.collapseBatch()
            val read = rig.inferrer.collapseBatch()

            assertEquals(emptyList(), rig.collapse.merged, "the reading is already part of another finding")
            assertEquals(0, read.merged)
            assertEquals(1, read.lost, "the call was paid for and bought nothing, which is said rather than hidden")
        }

    /**
     * **The collapse has to report its own work, or the driver never calls it.**
     *
     * The driver reads a stage's lag before it asks for a batch and skips the stage when that is
     * zero. Work reached only from the reading stages' nothing-owed branch would therefore have run
     * on no sweep at all once the corpus caught up — which is the only state it has anything to do
     * in — and an adjudication already paid for would have sat in flight with nothing able to
     * collect or abandon it. This is the count that keeps the stage alive, so the case is about the
     * count existing and not about what a batch then does.
     */
    @Test
    fun `the collapse reports work of its own, with no message owed`(): Unit = runBlocking {
        val pair = pair("will send the quote", "the quote is to be sent")
        val rig = Rig(pending = emptyList(), candidates = listOf(pair), vectors = sameFact(pair))

        assertTrue(
            rig.inferrer.collapseOwed() > 0,
            "a pair to compare is work, and a stage reporting none is a stage the driver skips",
        )
        assertEquals(0, rig.inferrer.owed(), "while the reading stages are caught up, which is the point")
    }

    @Test
    fun `an outstanding comparison keeps the collapse alive until it is collected`(): Unit = runBlocking {
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(pending = emptyList(), candidates = listOf(pair), vectors = borderline(pair))

        rig.inferrer.collapseBatch()

        assertTrue(
            rig.inferrer.collapseOwed() > 0,
            "a submission with the provider is work owed, or the money is spent on a reply nothing " +
                "will ever read and the stage stays blocked behind its own slot",
        )
    }

    @Test
    fun `a stack with no sidecar reports no collapse work`(): Unit = runBlocking {
        val pair = pair("will send the quote", "the quote is to be sent")
        val rig = Rig(
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = sameFact(pair),
            embedding = false,
        )

        assertEquals(
            0,
            rig.inferrer.collapseOwed(),
            "the comparison the candidates exist for cannot be made, so there is no work to report",
        )
    }

    /**
     * A reading step never touches the collapse, which is what keeps the two stages' bounds apart.
     *
     * It holds structurally now that the collapse is a step of its own — so what this guards is the
     * way back: hanging the collapse off the branch of a reading step that finds nothing owed reads
     * like a free sweep and is in fact the one sweep the driver never takes.
     */
    @Test
    fun `a reading step compares no pairs`(): Unit = runBlocking {
        val pair = pair("will send the quote", "the quote is to be sent")
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of(Extractor.NOTHING) },
            candidates = listOf(pair),
            vectors = sameFact(pair),
        )

        rig.inferrer.inferBatch()

        assertEquals(
            emptyList(),
            rig.collapse.asked,
            "a sweep with a window to read reads it: the collapse takes the sweeps that would " +
                "otherwise do nothing",
        )
        assertEquals(emptyList(), rig.collapse.merged)
    }

    /**
     * Without a sidecar the free comparison does not exist, and the stage does not fall through to
     * the one that costs money.
     */
    @Test
    fun `a stack with no sidecar compares nothing and spends nothing`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(
            model = model,
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
            embedding = false,
        )

        val did = rig.inferrer.collapseBatch()

        assertEquals(emptyList(), model.submissions, "the absence of the free tier is not a reason to buy the paid one")
        assertEquals(emptyList(), rig.collapse.merged)
        assertEquals(0, did.merged)
    }

    /**
     * The acceptance check that a re-derivation does not fork a finding already stored.
     *
     * The pass reads a message it has already read, under a version that has moved, and restates the
     * finding in different words — which is what makes the stored row and the fresh one two rows
     * that the exact key cannot collapse. The sweep after that finds them as a candidate pair and
     * puts them back together.
     */
    @Test
    fun `a re-derivation that rewords a finding does not leave two`(): Unit = runBlocking {
        val reworded = pair("Something was promised.", "A thing was undertaken.")

        // The re-derivation itself: the pass reads a message it has read before, under a version
        // that has moved, and the restatement chooses words it did not choose the first time.
        val rereading = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|none|${reworded.superseded.claim}") },
        )
        repeat(STEPS) { rereading.inferrer.inferBatch() }
        assertEquals(
            reworded.superseded.claim,
            rereading.commitments.written.single().claim,
            "the re-derivation wrote a claim in different words, which the content key cannot " +
                "collapse against the one already stored",
        )

        // The sweep after it, with the horizon caught up and both wordings in the store. A second
        // rig because the watermark double here re-offers its selection for ever, so one pass can
        // never reach the sweep that has nothing owed.
        val collapsing = Rig(
            pending = emptyList(),
            candidates = listOf(reworded),
            vectors = sameFact(reworded),
        )
        val collapsed = collapsing.inferrer.collapseBatch()

        assertEquals(listOf(reworded), collapsing.collapse.merged, "one fact, however the rewriting worded it")
        assertEquals(1, collapsed.merged)
        assertEquals(
            reworded.survivor.id,
            collapsing.collapse.merged.single().survivor.id,
            "and the earlier reading is the one kept, so the obligation keeps its own instant",
        )
    }

    /**
     * **Every key this pass submits under has to fit the provider's identifier**, and nothing else
     * checks it.
     *
     * The adapter refuses an over-long key, which is all an adapter can do — by then the key has
     * been chosen. The fake deliberately enforces none of the port's guards, so a key the provider
     * rejects for every request passes the whole of this suite. That is exactly how a key built by
     * concatenating two identifiers got as far as being committed.
     *
     * Driven through all three stages, so the case covers the shapes rather than one of them.
     */
    @Test
    fun `every key the pass submits under fits the provider's identifier`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val rig = Rig(
            model = model,
            // A record identifier of the shape the archive actually produces, which is the longest
            // thing a classification or a restatement key is built from.
            pending = listOf(message("-1001234567890/12345678")),
            classified = { Batched.of("commitment|-1001234567890/12345678|plain|none") },
            candidates = listOf(pair),
            vectors = borderline(pair),
        )

        // Classify, then restate, then — with nothing owed — adjudicate.
        repeat(STEPS) { rig.inferrer.inferBatch() }
        val collapsing = Rig(
            model = model,
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
        )
        collapsing.inferrer.collapseBatch()

        val keys = model.submissions.flatMap { it.requests.keys }
        assertTrue(keys.isNotEmpty(), "no keys were submitted, so this case would pass for the wrong reason")
        keys.forEach { key ->
            assertTrue(
                key.toByteArray(Charsets.UTF_8).size <= ModelProvider.MAX_KEY_BYTES,
                "'$key' is ${key.toByteArray(Charsets.UTF_8).size} bytes, and the provider carries " +
                    "at most ${ModelProvider.MAX_KEY_BYTES}",
            )
        }
    }

    // ---- the statement side of the collapse --------------------------------------------------

    /**
     * A value pair reaches the model or nothing, and never a merge the vectors made on their own.
     *
     * Two values of one relation are usually a deliberate contradiction, and two short ones close
     * in wording — a rate and the rate it became — are what a vector comparison is least able to
     * tell from a rewording. So the vectors choose what to ask about and decide nothing.
     */
    @Test
    fun `two values the vectors put beyond doubt are still asked about`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = valuePair("five thousand a month", "5000 per month")
        val rig = Rig(
            model = model,
            pending = emptyList(),
            values = listOf(pair),
            // Identical vectors, which for a finding pair would merge with no call at all.
            vectors = mapOf(pair.one to listOf(1f, 0f), pair.other to listOf(1f, 0f)),
        )

        val did = rig.inferrer.collapseBatch()

        assertEquals(0, did.merged, "no value pair is ever merged without being paid for")
        assertEquals(emptyList(), rig.statements.decided, "and nothing is recorded until the model answers")
        assertEquals(1, did.awaiting, "it goes to the model instead")
    }

    @Test
    fun `two values the vectors put apart are not asked about`(): Unit = runBlocking {
        val model = FakeModelProvider()
        val pair = valuePair("Acme Ltd", "Globex")
        val rig = Rig(
            model = model,
            pending = emptyList(),
            values = listOf(pair),
            vectors = mapOf(pair.one to listOf(1f, 0f), pair.other to listOf(0f, 1f)),
        )

        val did = rig.inferrer.collapseBatch()

        assertEquals(emptyList(), model.submissions, "ruling a pair out is free, so it costs nothing")
        assertEquals(0, did.awaiting)
        // **Recorded, and that is deliberate.** Candidate selection is oldest-first over a bound,
        // so a pair nothing writes down comes back at the head of the queue on every sweep and the
        // pairs behind it are never reached — the stage would compare one page for ever.
        assertEquals(listOf(pair to false), rig.statements.decided)
        assertEquals(
            listOf(runningVectorsVersion("fake-embeddings")),
            rig.statements.decidedBys,
            "named by the vector version and not the model, so a better embedding model or a " +
                "moved similarity bound retires the judgement and nothing else does",
        )
    }

    /**
     * The acceptance check for the statement half: the verdict is recorded *and applied*, because a
     * verdict stored and not applied is one that was paid for and changed nothing.
     */
    @Test
    fun `a value pair the model reads as one fact is recorded and the subject re-resolved`(): Unit =
        runBlocking {
            val pair = valuePair("five thousand a month", "5000 per month")
            val held = listOf(
                statement(pair.one, at = FIRST),
                statement(pair.other, at = FIRST.plusSeconds(AN_HOUR)),
            )
            val rig = Rig(
                pending = emptyList(),
                values = listOf(pair),
                vectors = borderlineValues(pair),
                held = held,
                // What `synonymsOf` answers once the verdict has been written, which is what the
                // re-resolution reads back rather than assuming from the verdict it just made.
                synonyms = listOf(ValueSynonym(CONTACT, Predicate.of("charges"), pair.one, pair.other)),
                adjudicated = { Batched.of(Extractor.ONE_FACT) },
            )

            rig.inferrer.collapseBatch()
            val read = rig.inferrer.collapseBatch()

            assertEquals(listOf(pair to true), rig.statements.decided, "the verdict is written down")
            assertEquals(1, read.merged)
            // Through `recollapsed` and not `record`: the re-windowing produces an open window
            // where the store holds a close, and the ordinary write would keep the close and then
            // remove the other row — leaving the attribute reading as ended with nothing current.
            val rewritten = assertNotNull(
                rig.statements.recollapsedWith.takeIf { it.isNotEmpty() },
                "the subject was not re-resolved, so the verdict changed nothing",
            )
            assertEquals(
                emptyList(),
                rig.statements.recorded,
                "and not through the write that refuses to reopen a window",
            )
            assertEquals(
                1,
                rewritten.size,
                "two wordings of one rate are one fact over one window: ${rewritten.map { it.value }}",
            )
            assertEquals(pair.one, rewritten.single().value, "kept in the words it was first stated in")
            assertNull(rewritten.single().validity.until, "and the window was never closed")
            assertEquals(
                1,
                read.superseded,
                "**The row the re-windowing took away reached no figure.** This is the one write " +
                    "in the build that removes anything, and removing the wrong thing here is the " +
                    "defect it was built to fix — an attribute left reading as ended with nothing " +
                    "current. Unreported, that recurrence looks exactly like a merge that worked.",
            )
        }

    /**
     * A verdict is recorded whatever happened to the subject, and only a re-windowing counts as a
     * merge — a figure reporting work the store did not do is worse than no figure.
     */
    @Test
    fun `a verdict on a subject with nothing left to window is recorded and not counted`(): Unit =
        runBlocking {
            val pair = valuePair("five thousand a month", "5000 per month")
            val rig = Rig(
                pending = emptyList(),
                values = listOf(pair),
                vectors = borderlineValues(pair),
                // Nothing held about the subject by the time the verdict lands.
                held = emptyList(),
                adjudicated = { Batched.of(Extractor.ONE_FACT) },
            )

            rig.inferrer.collapseBatch()
            val read = rig.inferrer.collapseBatch()

            assertEquals(listOf(pair to true), rig.statements.decided, "the verdict was paid for, so it is kept")
            assertEquals(emptyList(), rig.statements.recorded, "there was nothing to re-window")
            assertEquals(0, read.merged, "and nothing is reported as merged")
            assertEquals(1, read.lost, "the call bought nothing, which is said rather than hidden")
        }

    @Test
    fun `a value pair the model reads as two facts is recorded and nothing re-resolved`(): Unit =
        runBlocking {
            val pair = valuePair("5000 per month", "5500 per month")
            val rig = Rig(
                pending = emptyList(),
                values = listOf(pair),
                vectors = borderlineValues(pair),
                held = listOf(statement(pair.one, at = FIRST), statement(pair.other, at = FIRST.plusSeconds(AN_HOUR))),
                adjudicated = { Batched.of(Extractor.TWO_FACTS) },
            )

            rig.inferrer.collapseBatch()
            val read = rig.inferrer.collapseBatch()

            assertEquals(
                listOf(pair to false),
                rig.statements.decided,
                "a revision is history, and the answer is recorded so it is not bought again",
            )
            assertEquals(0, read.merged)
            assertEquals(1, read.apart)
            assertEquals(
                emptyList(),
                rig.statements.recorded,
                "nothing was re-resolved: the two values were already two",
            )
        }

    /**
     * A value whose wording holds the payload's own separator.
     *
     * The payload carries the two values across a batch, so a field that could swallow the next one
     * would attribute a verdict to a pair nobody asked about — which is silent, and wrong in the
     * one direction that matters.
     */
    @Test
    fun `a value holding the payload separator is still matched back to its own pair`(): Unit =
        runBlocking {
            val pair = valuePair("5000${Inferrer.BETWEEN}month", "five thousand${Inferrer.BETWEEN}a month")
            val rig = Rig(
                pending = emptyList(),
                values = listOf(pair),
                vectors = borderlineValues(pair),
                held = listOf(statement(pair.one, at = FIRST)),
                adjudicated = { Batched.of(Extractor.TWO_FACTS) },
            )

            rig.inferrer.collapseBatch()
            rig.inferrer.collapseBatch()

            assertEquals(
                listOf(pair to false),
                rig.statements.decided,
                "the pair came back exactly as it was asked about, separators and all",
            )
        }

    @Test
    fun `the collapse reads statements and findings in one call to the sidecar`(): Unit = runBlocking {
        val finding = pair("will send the quote", "the quote is to be sent")
        val values = valuePair("Acme Ltd", "Acme Limited")
        val rig = Rig(
            pending = emptyList(),
            candidates = listOf(finding),
            values = listOf(values),
            vectors = sameFact(finding) + borderlineValues(values),
        )

        rig.inferrer.collapseBatch()

        assertEquals(
            1,
            rig.embeddings.embedded.size,
            "the sidecar is a round trip per batch, so both sides of the collapse go in one call",
        )
        assertEquals(
            setOf(finding.survivor.claim, finding.superseded.claim, values.one, values.other),
            rig.embeddings.embedded.single().toSet(),
        )
    }

    /**
     * The payload encoding is written out by hand, because `:domain` carries no dependency — so the
     * round trip is asserted rather than taken from a library's reputation.
     *
     * The cases are what a value can actually hold: the separator the payload is split on, a
     * newline, a character outside ASCII and one outside the basic plane. A field that came back
     * changed would attribute a verdict to a pair nobody asked about.
     */
    @Test
    fun `a payload field survives being encoded and read back`() {
        listOf(
            "",
            "a plain value",
            Inferrer.BETWEEN,
            "5000${Inferrer.BETWEEN}month",
            "two\nlines",
            "café résumé",
            "💰 a sum",
            "0123456789abcdef",
        ).forEach { field ->
            assertEquals(field, Inferrer.decoded(Inferrer.encoded(field)), "'$field' did not survive the round trip")
        }
    }

    @Test
    fun `an encoded field never holds the separator it travels beside`() {
        val encoded = Inferrer.encoded("a${Inferrer.BETWEEN}b${Inferrer.BETWEEN}c")

        assertFalse(
            Inferrer.BETWEEN in encoded,
            "the alphabet has to exclude the separator by construction, or a field swallows the next",
        )
    }

    @Test
    fun `a field this build did not write is refused rather than misread`() {
        // Both ways it can be wrong: an odd length, and a character outside the alphabet. Either is
        // another build's payload, which the collection counts as a call that bought nothing.
        assertFailsWith<IllegalArgumentException> { Inferrer.decoded("abc") }
        assertFailsWith<IllegalArgumentException> { Inferrer.decoded("zz") }
    }

    @Test
    fun `an adjudication carries no records, because it settles none`(): Unit = runBlocking {
        val pair = pair("will draft the contract", "the contract is to be drafted")
        val remembered = FakeSubmissionRepository()
        val rig = Rig(
            pending = emptyList(),
            candidates = listOf(pair),
            vectors = borderline(pair),
            submissions = remembered,
        )

        rig.inferrer.collapseBatch()

        val recorded = remembered.recorded.single()
        assertEquals(SubmissionStage.ADJUDICATION, recorded.stage)
        assertEquals(
            emptyList(),
            recorded.records,
            "the messages these two findings were read out of were settled by the restatement " +
                "that wrote them, so a collection here could mark nothing and must claim nothing",
        )
        assertEquals(emptyList(), rig.derivations.marked, "and nothing is marked")
    }

    @Test
    fun `a submission is collected by whichever process is running when the replies land`(): Unit =
        runBlocking {
            // The reason the identifier is persisted at all. A backend that restarts between
            // submitting and collecting has already been billed, so its successor has to be able
            // to read the replies with nothing but the table to go on.
            val model = FakeModelProvider()
            val classified: (String) -> BatchOutcome = { Batched.of("commitment|m1|plain|none") }
            val restated: (String) -> BatchOutcome = { Batched.of("none|none|none|Something was promised.") }
            val remembered = FakeSubmissionRepository()

            val submitting = Rig(
                model = model,
                pending = listOf(message("m1")),
                classified = classified,
                restated = restated,
                submissions = remembered,
            )
            submitting.inferrer.inferBatch()

            // A second pass over the same store and the same provider: a different Inferrer, a
            // different watermark double, nothing carried over in memory. Two steps, because the
            // successor has both the classification to read and the restatement it asks for.
            val collecting = Rig(
                model = model,
                pending = listOf(message("m1")),
                classified = classified,
                restated = restated,
                submissions = remembered,
            )
            collecting.inferrer.inferBatch()
            collecting.inferrer.inferBatch()

            assertEquals(
                "Something was promised.",
                collecting.commitments.written.single().claim,
                "The successor could not read what the previous process had already paid for.",
            )
            assertEquals(listOf(message("m1")), collecting.derivations.marked)
            assertTrue(
                submitting.commitments.written.isEmpty(),
                "The process that submitted wrote nothing: it had no replies to write.",
            )
            assertEquals(
                2,
                remembered.settled.size,
                "Both submissions were settled once read: the classification by the step that " +
                    "asked for the restatements, and the restatement by the step that wrote them.",
            )
            // Each stage's own bill, stored against its own submission. Folded together, the
            // per-request price that sizes the next submission of either stage would be a price
            // for both of them and size each one wrongly.
            assertEquals(
                listOf(Reply.SAMPLE_USAGE, Reply.SAMPLE_USAGE),
                remembered.settled.map { it.second },
            )
            assertEquals(
                listOf(SubmissionStage.CLASSIFICATION, SubmissionStage.RESTATEMENT),
                remembered.recorded.map { it.stage },
                "The stages have to be recorded apart, or the store cannot hold one of each in " +
                    "flight and the second could never be submitted at all.",
            )
        }

    @Test
    fun `a collected window is marked against the cut-off it was selected under`(): Unit = runBlocking {
        // A submission is allowed most of a day, so a message can be edited between being
        // classified and being marked. Marked against the collecting batch's own clock the mark
        // would look current and the classification of text that no longer exists would stand for
        // good; marked against the selection, the edited message comes back owed.
        val rig = Rig(
            pending = listOf(message("m1")),
            classified = { Batched.of("commitment|m1|plain|none") },
            restated = { Batched.of("none|none|none|Something was promised.") },
        )

        rig.settle()

        assertEquals(listOf(message("m1")), rig.derivations.marked)
        assertEquals(
            rig.derivations.selections.single(),
            rig.derivations.markedAsOf.single(),
            "The mark carries a clock other than the cut-off the record was selected under, so a " +
                "message edited while the submission was outstanding would be settled against a " +
                "classification of text that no longer exists.",
        )
        assertEquals(
            1,
            rig.derivations.selections.size,
            "The collecting batch selected records of its own. Selection is oldest-first and " +
                "bounded, so its answer moves between submitting and collecting — which is why " +
                "the submission carries both the records and the cut-off rather than re-reading " +
                "them, and a second selection here means it is re-reading them again.",
        )
    }

    @Suppress("LongParameterList")
    private class Rig(
        val model: FakeModelProvider = FakeModelProvider(),
        pending: List<ExternalId> = emptyList(),
        conversation: List<Message> = conversation(2),
        bounds: Bounds = Bounds(notBefore = HORIZON),
        contacts: ContactRepository = FakeContacts(),
        /**
         * What the corpus names each author, which is what a window labels its lines with.
         *
         * Named by default, because the named path is the one production takes wherever a source
         * offered a name — and a case that wants the unnamed fallback says so.
         */
        named: Map<ExternalId, String> = mapOf(AUTHOR to AUTHOR_NAME),
        held: List<Statement> = emptyList(),
        /**
         * What the classification of every submitted window comes back as.
         *
         * The pass submits classifications and reads them a batch later, so a test that wants a
         * finding has to say what came back rather than queue it as a reply.
         */
        classified: ((String) -> BatchOutcome)? = null,
        /**
         * What the restatement of every candidate comes back as.
         *
         * A second script rather than one shared with [classified], because both stages are now
         * submitted and a single function would be handed keys from either — the two formats are
         * different, so one lambda answering both would have to tell them apart itself, which is
         * exactly the dispatch this rig does once on their behalf.
         */
        restated: ((String) -> BatchOutcome)? = null,
        /**
         * The candidate pairs the collapse's store offers, which it offers once.
         *
         * Empty by default, so every case about the reading stages reaches the collapse step, finds
         * nothing to compare and reports nothing — which is what the pass did before this stage
         * existed.
         */
        candidates: List<FindingPair> = emptyList(),
        /**
         * The readings that have gone by the time a verdict is read back.
         *
         * How a case expresses the gap a batch introduces: a pair merged away while its
         * adjudication was with the provider is no longer a pair to decide about.
         */
        missing: Set<EntityId> = emptySet(),
        /** What vector each claim embeds to, which is what decides the tier a pair is settled by. */
        vectors: Map<String, List<Float>> = emptyMap(),
        /** The candidate value pairs the statement store offers, which it offers once. */
        values: List<ValuePair> = emptyList(),
        /** The value verdicts already bought, which resolution is handed with the statements. */
        synonyms: List<ValueSynonym> = emptyList(),
        /** Whether the stack has a sidecar at all. Without one the collapse has no middle tier. */
        embedding: Boolean = true,
        /** Where a billed call outside a submission is recorded, which is the warming call. */
        val spend: FakeSpendLedger = FakeSpendLedger(),
        /** What the comparison of every submitted pair comes back as. */
        adjudicated: ((String) -> BatchOutcome)? = null,
        /**
         * Where submissions are remembered.
         *
         * Shareable with another rig, which is how a restart is expressed: the process that
         * submitted is gone, and its successor has the table and nothing else.
         */
        // Submitted at the sweep's own clock unless a case moves it, so nothing is abandoned by
        // accident: the deadline is read from the gap between the two.
        val submissions: FakeSubmissionRepository = FakeSubmissionRepository(bounds.now),
        /**
         * How many of the first commitment writes throw, and how many of the first statement ones.
         *
         * Two counts rather than one, because a batch writes to the two in sequence and what the
         * accounting has to survive is a failure *between* them as much as one before both.
         */
        failedCommitmentWrites: Int = 0,
        failedStatementWrites: Int = 0,
    ) {
        val order = mutableListOf<String>()
        val derivations = FakeDerivations(pending, order)
        val commitments = FakeCommitments(order, failedCommitmentWrites)
        val events = FakeEvents(order)
        val statements = FakeStatements(held, order, values, synonyms, failedStatementWrites)
        val collapse = FakeCollapse(candidates, missing, order)
        val embeddings = FakeEmbeddings(vectors)

        /**
         * What the corpus names each author, read live rather than copied at construction.
         *
         * Mutable because a window is re-derived on collection, so the restatement stage refuses a
         * stored line whenever the label its subject was matched against has moved in between —
         * withdrawn, or changed by a counterpart renaming themselves, which the person store keeps
         * current. A case drives that by changing an entry here mid-sweep; nothing else can express
         * it, the rig deriving both windows from the same corpus.
         */
        val names = named.toMutableMap()

        init {
            // One script per stage, dispatched on the shape of the key the pass submitted under: a
            // classification asks about a window and is keyed by its target, a restatement asks
            // about one finding inside it and carries its place in the window too, and an
            // adjudication names a kind and two findings. The adjudication is tested first because
            // its separator is the one neither other stage's keys contain.
            val classify = classified
            if (classify != null || adjudicated != null) {
                model.answering = { key ->
                    when {
                        key.startsWith(Inferrer.VERDICT_KEY) ->
                            adjudicated?.invoke(key) ?: Batched.of(Extractor.TWO_FACTS)

                        Inferrer.WITHIN in key -> restated?.invoke(key) ?: Batched.of(RESTATED)
                        else -> classify?.invoke(key) ?: Batched.of(Extractor.NOTHING)
                    }
                }
            }
        }

        /**
         * The one the pass is given, so a case can assert against the versions it actually records
         * rather than against a literal of its own that would agree with nothing.
         */
        val extractor = Extractor(model)

        val inferrer = Inferrer(
            derivations = derivations,
            messages = FakeMessages(conversation),
            contacts = contacts,
            persons = FakePersons(names),
            extractor = extractor,
            derived = Derived(commitments, events, statements, collapse),
            bounds = bounds,
            submissions = submissions,
            model = model,
            embeddings = embeddings.takeIf { embedding },
            spend = spend,
        )

        /** Every step [settle] drove, in order, so a test can assert what each stage reported. */
        val steps = mutableListOf<Inference>()

        /**
         * Drive the pass until nothing of it is with the provider, and return the step that finished.
         *
         * **Not a fixed number of batches, because how many it takes depends on what was found.** A
         * window classified to nothing, cut short or refused is finished by the collection that read
         * it — there is nothing to restate — while a window with a finding in it needs a third batch
         * to restate and write. Driving a fixed three would run past the end of the first kind and
         * return a batch that had merely submitted the next selection, which reads as the pass
         * having settled nothing.
         *
         * So the step returned is the one that left nothing outstanding, which is the one that
         * marked and settled. [steps] holds the rest, which is where each stage's own spend is
         * reported.
         */
        suspend fun settle(): Inference {
            repeat(STEPS) {
                val did = inferrer.inferBatch()
                steps += did
                if (did.awaiting == 0) return did
            }
            return steps.last()
        }

        /**
         * Drive the pass until it has failed [expected] times, and say how many times it did.
         *
         * **The count is returned rather than asserted here**, so a case that scripted no failure at
         * all fails on its own assertion and not on a rig that silently did nothing — a drive that
         * quietly did less than it was asked for is how a case comes to certify nothing.
         *
         * Bounded by [STEPS] plus one step per expected failure, because a failed step does the work
         * of none: the submission stays in flight, nothing is marked, and the next step collects the
         * same retained replies again. Bounded by [STEPS] alone the drive would run out before the
         * retry and hand the caller a batch that had not happened yet.
         */
        @Suppress("SwallowedException")
        suspend fun driveThrough(expected: Int): Int {
            var failed = 0
            repeat(STEPS + expected) {
                if (failed == expected) return failed
                try {
                    steps += inferrer.inferBatch()
                } catch (_: IllegalStateException) {
                    failed++
                }
            }
            return failed
        }
    }

    /**
     * The watermark, which records what it was asked rather than only what it answers.
     *
     * Three of those questions are what the pass is asserted on: the version it filed its work
     * under, the horizon it bounded selection with, and what it finally marked.
     */
    private class FakeDerivations(
        private val pending: List<ExternalId>,
        private val order: MutableList<String>,
    ) : DerivationStateRepository {

        val versionsSeen = mutableListOf<String>()
        val horizonsSeen = mutableListOf<Instant?>()
        val marked = mutableListOf<ExternalId>()

        /**
         * The version every mark was written under, one entry per mark rather than per record.
         *
         * Apart from [versionsSeen], which is what *selection* asked under. The two are the same
         * string on any sweep that submits and collects without a deploy in between, and they are
         * exactly what comes apart when one lands — which is the only thing that can tell a mark
         * carrying the wording that read the window from one carrying whatever is deployed now.
         */
        val markedUnder = mutableListOf<String>()

        /** The cut-off every selection was taken against, in order. */
        val selections = mutableListOf<Instant>()

        /** The cut-off every mark was written against, in order. */
        val markedAsOf = mutableListOf<Instant>()

        override suspend fun pending(
            derivation: Derivation,
            kind: RecordKind,
            limit: Int,
            notBefore: Instant?,
        ): Pending {
            versionsSeen += derivation.version
            horizonsSeen += notBefore
            // A later cut-off on each selection, because a real store reads its own clock and the
            // whole point of carrying the first one is that it differs from the second. A constant
            // here would make a pass marking against the wrong clock indistinguishable from one
            // marking against the right one.
            val asOf = SELECTED.plusSeconds(selections.size * AN_HOUR)
            selections += asOf
            return Pending(records = pending.take(limit), asOf = asOf)
        }

        override suspend fun mark(
            derivation: Derivation,
            kind: RecordKind,
            records: List<ExternalId>,
            asOf: Instant,
        ) {
            order += "mark"
            marked += records
            markedAsOf += asOf
            markedUnder += derivation.version
        }

        // Records the same two questions the selection does, because the whole point of the lag
        // is that it counts what a batch would select — a count taken under a different horizon
        // or a different version would say a pass had stalled when it had not.
        override suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int {
            versionsSeen += derivation.version
            horizonsSeen += notBefore
            return (pending - marked.toSet()).size
        }
    }

    /**
     * The corpus, as this pass reads it: a message's conversation, and the conversation whole.
     *
     * Every other member fails, which is an assertion rather than laziness — this pass reads two
     * things, and one that started reaching for a third would say so here rather than in a review.
     */
    private class FakeMessages(private val conversation: List<Message>) : MessageRepository {

        override suspend fun conversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId> =
            messages.filter { wanted -> conversation.any { it.externalId == wanted } }.associateWith { CHAT }

        override suspend fun messagesIn(conversation: ExternalId): List<Message> = this.conversation

        // Inference reads a window around a selected message and never a conversation's tail, so
        // a call here is this fake being asked for something the pass under test does not want.
        override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> = unexpected()

        override suspend fun upsert(conversation: ExternalId, messages: List<Message>) = unexpected()

        override suspend fun messages(externalIds: List<ExternalId>): List<Message> = unexpected()

        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = unexpected()

        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = unexpected()

        override suspend fun sharingAConversation(authors: List<ExternalId>): List<Pair<ExternalId, ExternalId>> =
            unexpected()

        // A withdrawal is the source's statement, carried by ingestion. This pass reads the
        // corpus and writes none of it, so reaching here is the same defect as reaching [upsert].
        override suspend fun markWithdrawn(externalIds: List<ExternalId>) = unexpected()
    }

    /**
     * Identity, read and never written.
     *
     * The write side fails on purpose: a contact's identity is the identity stage's to assign,
     * and this pass is not it. So does `heads` — the subject of a fact is the contact its record has, not the head
     * that record is merged into, because a merge is a reversible link and following it at write
     * time would bake it in.
     */
    private class FakeContacts(
        private val known: Map<ExternalId, EntityId> = mapOf(AUTHOR to CONTACT),
    ) : ContactRepository {

        override suspend fun of(records: List<ExternalId>): List<Contact> =
            records.mapNotNull { record -> known[record]?.let { Contact(it, record, displayName = null) } }

        override suspend fun upsert(contacts: List<Contact>) = unexpected()

        override suspend fun merge(links: List<MergeLink>) = unexpected()

        override suspend fun linkOf(contact: EntityId): MergeLink? = unexpected()

        override suspend fun unmerge(contact: EntityId) = unexpected()

        override suspend fun heads(contacts: List<EntityId>): Map<EntityId, EntityId> = unexpected()

        override suspend fun clusters(contacts: List<EntityId>): List<Cluster> = unexpected()

        override suspend fun separations(among: List<EntityId>): List<Separation> = unexpected()

        override suspend fun keep(candidates: List<CandidateMerge>) = unexpected()

        override suspend fun candidates(among: List<EntityId>): List<CandidateMerge> = unexpected()
    }

    /**
     * Person records, read for one thing: what the corpus names an author.
     *
     * Every other member fails, as with the corpus fake. This pass has no business matching person
     * records against each other — that is identity's — so a pass that started doing it says so
     * here rather than in a review.
     */
    private class FakePersons(private val named: Map<ExternalId, String>) : PersonRepository {

        override suspend fun persons(externalIds: List<ExternalId>): List<ObservedPerson> =
            externalIds.map { ObservedPerson(externalId = it, displayName = named[it]) }

        override suspend fun upsert(person: Person) = unexpected()

        override suspend fun find(externalId: ExternalId): Person? = unexpected()

        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = unexpected()

        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = unexpected()

        override suspend fun claimants(claims: List<Identifier>): List<ObservedPerson> = unexpected()

        override suspend fun nameFrequency(names: List<String>): Map<String, Int> = unexpected()

        override suspend fun namesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson> =
            unexpected()
    }

    /**
     * Commitments, keyed by what they say within their conversation, as the store keys them.
     *
     * [written] is still every reading it was handed, so a case can read what the pass offered. What
     * it *answers* with is what the rows did, and that is the half worth modelling here: the pass
     * now reports the store's figures, so a fake handing back the size of its argument would make
     * every one of those assertions pass against a pass that had learnt nothing.
     */
    private class FakeCommitments(
        private val order: MutableList<String>,
        /**
         * How many of the first writes throw, before storing anything.
         *
         * **Before, because that is what a transaction does**: a store that failed leaves no row, so
         * a retry finds the claim absent and inserts it. A fake that recorded the row and then threw
         * would have the retry report an overwrite, which is the opposite of what a reader is owed
         * and would make a correct pass look like one losing a figure.
         */
        private var failures: Int = 0,
    ) : CommitmentRepository {

        val written = mutableListOf<Commitment>()
        private val rows = mutableSetOf<Pair<ExternalId, String>>()

        override suspend fun upsert(commitments: List<Commitment>): Stored {
            if (commitments.isNotEmpty() && failures > 0) {
                failures--
                error("the store was unreachable")
            }
            if (commitments.isNotEmpty()) order += "write"
            written += commitments
            // Folded one at a time rather than counted over a set, so two offers of one claim in a
            // single batch are one row and one overwrite — which is what an upsert per reading does.
            var fresh = 0
            commitments.forEach { if (rows.add(it.conversation to it.claim)) fresh++ }
            return Stored(written = fresh, overwritten = commitments.size - fresh)
        }

        override suspend fun of(evidence: List<Evidence>): List<Commitment> = unexpected()
    }

    /** Events, under the same key and the same accounting as [FakeCommitments]. */
    private class FakeEvents(private val order: MutableList<String>) : EventRepository {

        val written = mutableListOf<Event>()
        private val rows = mutableSetOf<Pair<ExternalId, String>>()

        override suspend fun upsert(events: List<Event>): Stored {
            if (events.isNotEmpty()) order += "write"
            written += events
            var fresh = 0
            events.forEach { if (rows.add(it.conversation to it.claim)) fresh++ }
            return Stored(written = fresh, overwritten = events.size - fresh)
        }

        override suspend fun of(evidence: List<Evidence>): List<Event> = unexpected()
    }

    /**
     * The collapse's store, which records what was decided rather than keeping rows.
     *
     * [candidates] is what the case puts in front of the pass, offered once and then withdrawn: the
     * store's own query excludes a pair it has merged or recorded, and a fake that went on offering
     * the same pair would have the drive loop collapse it on every step and never terminate.
     *
     * [pairs] is what [pair] answers with, keyed by the two identifiers in either order. A case that
     * leaves one out is a case where the pair vanished between the asking and the answer, which is
     * the gap the re-read exists for.
     */
    private class FakeCollapse(
        offered: List<FindingPair> = emptyList(),
        private val missing: Set<EntityId> = emptySet(),
        private val order: MutableList<String>,
    ) : CollapseRepository {

        /** What the case offered, by the two identifiers, so [pair] answers the key the pass minted. */
        private val known = offered.associateBy { setOf(it.survivor.id, it.superseded.id) }
        private var remaining = offered

        val merged = mutableListOf<FindingPair>()
        val keptApart = mutableListOf<Pair<EntityId, EntityId>>()

        /** What each kept-apart record named as its decider, so a case can tell the tiers apart. */
        val keptApartBy = mutableListOf<String>()
        val asked = mutableListOf<Int>()

        /** What the pass said it was comparing under, which is the vector version. */
        val comparedUnder = mutableListOf<String>()

        /**
         * And the same, as the *count* named it, which is a separate list on purpose.
         *
         * The driver reads a fall in the count as progress, so a count taken under a version the
         * selection does not exclude under reports the stage caught up with pairs it is about to be
         * handed. The two are built at different call sites, so only comparing them catches one of
         * the two being left behind.
         */
        val countedUnder = mutableListOf<String>()

        /**
         * And what it said it was adjudicating under, asserted beside [keptApartBy] — the pair is
         * the point: a verdict written under one version and selected under another is a pair
         * bought on every sweep for ever, and either half alone reads as correct.
         */
        val adjudicatedUnder = mutableListOf<String>()

        override suspend fun candidates(
            limit: Int,
            within: Duration,
            comparedBy: String,
            adjudicatedBy: String,
        ): List<FindingPair> {
            asked += limit
            comparedUnder += comparedBy
            adjudicatedUnder += adjudicatedBy
            return remaining.take(limit).also { remaining = remaining.drop(limit) }
        }

        override suspend fun candidateCount(within: Duration, comparedBy: String, adjudicatedBy: String): Int {
            countedUnder += comparedBy
            return remaining.size
        }

        override suspend fun pair(kind: FindingKind, one: EntityId, other: EntityId): FindingPair? =
            if (one in missing || other in missing) null else known[setOf(one, other)]

        override suspend fun merge(pair: FindingPair) {
            order += "merge"
            merged += pair
        }

        override suspend fun keepApart(kind: FindingKind, one: EntityId, other: EntityId, decidedBy: String) {
            order += "apart"
            keptApart += one to other
            keptApartBy += decidedBy
        }
    }

    /**
     * The sidecar, answering with whatever vector a case assigned each claim.
     *
     * Deliberately not a real embedding: what the cases are about is which tier decides a pair, so a
     * claim is given a vector chosen to land it above, between or below the thresholds. A claim no
     * case named gets a vector of its own that matches nothing, which is how a pair the comparison
     * should leave alone is built.
     */
    private class FakeEmbeddings(private val vectors: Map<String, List<Float>>) : EmbeddingProvider {

        val embedded = mutableListOf<List<String>>()

        override suspend fun version(): String = "fake-embeddings"

        override suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding> {
            check(kind == EmbeddingKind.PASSAGE) {
                "The collapse compares two stored claims, so both sides are passages: a question is " +
                    "the one thing it never embeds."
            }
            embedded += texts
            return texts.map { Embedding(dense = vectors[it] ?: listOf(1f, 0f, 0f)) }
        }
    }

    /**
     * Statements, recording what the pass handed over rather than what a store would keep.
     *
     * [recorded] is every reading it was offered, in the order offered, because what most of these
     * cases are about is what the pass resolves before it writes — so what a store would have kept
     * is deliberately not reconstructed, and asserting the replacement itself stays the store's
     * test against Postgres.
     *
     * **The figures it answers with are the store's, though, and they are modelled rather than
     * faked.** The pass reports what its writes did to the rows, so a fake answering with the size
     * of its argument would make a sweep's accounting assertions pass against a pass that had gone
     * back to counting attempts. Content keys are held in [keys] so a reading already there is an
     * overwrite, and the pairs the argument covers are compared against it so a window held and not
     * offered back is superseded — which is the port's precondition, stated as arithmetic.
     */
    private class FakeStatements(
        private val held: List<Statement>,
        private val order: MutableList<String>,
        /**
         * The value pairs the store offers, offered once.
         *
         * Withdrawn after the first asking for the reason the finding candidates are: the real
         * query excludes a pair it has a verdict for, and a fake that went on offering the same
         * pair would have a drive loop submit it on every step.
         */
        offeredValues: List<ValuePair> = emptyList(),
        /** The verdicts already bought, which resolution is handed alongside the statements. */
        private val synonyms: List<ValueSynonym> = emptyList(),
        /** How many of the first writes throw, before storing anything. See [FakeCommitments]. */
        private var failures: Int = 0,
    ) : StatementRepository {

        val recorded = mutableListOf<Statement>()

        /** What the collapse re-windowed, kept apart from [recorded] so a case can tell which write ran. */
        val recollapsedWith = mutableListOf<Statement>()
        val decided = mutableListOf<Pair<ValuePair, Boolean>>()

        /** What each verdict named as its decider, so a case can tell a paid answer from a cheap one. */
        val decidedBys = mutableListOf<String>()
        private var remainingValues = offeredValues

        /** The content keys this store holds, which is what an offered reading is an overwrite of. */
        private val keys = held.map { it.key }.toMutableSet()

        override suspend fun record(statements: List<Statement>): Stored {
            if (statements.isNotEmpty() && failures > 0) {
                failures--
                error("the store was unreachable")
            }
            if (statements.isNotEmpty()) order += "write"
            recorded += statements
            return stored(statements)
        }

        override suspend fun recollapsed(statements: List<Statement>): Stored {
            if (statements.isNotEmpty()) order += "recollapse"
            recollapsedWith += statements
            return stored(statements)
        }

        /**
         * What a write of [statements] did to the rows, under the replacement the port promises.
         *
         * A row is superseded by being held under one of the argument's `(subject, predicate)`
         * pairs and not being in the argument, which is the port's precondition stated as
         * arithmetic. The real store computes it after the upserts and reaches the same answer,
         * every key the fold adds being one the argument already carried.
         */
        private fun stored(statements: List<Statement>): Stored {
            val touched = statements.map { it.subject to it.predicate }.toSet()
            val offered = statements.map { it.key }.toSet()
            val superseded = keys.filter { it.subject to it.predicate in touched && it !in offered }
            var fresh = 0
            statements.forEach { if (keys.add(it.key)) fresh++ }
            keys -= superseded.toSet()
            return Stored(
                written = fresh,
                overwritten = statements.size - fresh,
                superseded = superseded.size,
            )
        }

        override suspend fun about(subjects: List<EntityId>): List<Statement> =
            held.filter { it.subject in subjects }

        override suspend fun synonymsOf(subjects: List<EntityId>): List<ValueSynonym> =
            synonyms.filter { it.subject in subjects }

        override suspend fun valueCandidates(
            limit: Int,
            comparedBy: String,
            adjudicatedBy: String,
        ): List<ValuePair> =
            remainingValues.take(limit).also { remainingValues = remainingValues.drop(limit) }

        override suspend fun valueCandidateCount(comparedBy: String, adjudicatedBy: String): Int =
            remainingValues.size

        override suspend fun decide(pair: ValuePair, same: Boolean, decidedBy: String) {
            order += if (same) "same" else "different"
            decided += pair to same
            decidedBys += decidedBy
        }
    }

    private companion object {
        val CHAT = ExternalId(Source.TELEGRAM, "chat")
        val AUTHOR = ExternalId(Source.TELEGRAM, "author")

        /**
         * What the corpus names the author of every message in these fixtures.
         *
         * An invented string, and the label the model is expected to echo when it says a fact is
         * about the person who stated it. Used in the classification replies rather than a
         * positional label, so these cases go through the path production takes.
         */
        const val AUTHOR_NAME = "A Named Author"

        /** A second participant, to be the subject a fact is wrongly placed on. */
        val BYSTANDER = ExternalId(Source.TELEGRAM, "bystander")
        const val BYSTANDER_NAME = "Another Named Party"

        val CONTACT = EntityId.mint()

        /** The contact behind the second participant, so a direction can differ from its author. */
        val OTHER_CONTACT = EntityId.mint()
        val EARLIER: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val HORIZON: Instant = Instant.parse("2026-02-01T00:00:00Z")
        val FIRST: Instant = Instant.parse("2026-03-02T10:00:00Z")
        val SELECTED: Instant = Instant.parse("2026-03-03T00:00:00Z")
        const val A_YEAR = 60L * 60 * 24 * 365
        const val AN_HOUR = 60L * 60

        /**
         * How many batches it takes to get a finding written: classify, restate, write.
         *
         * Named rather than repeated as a literal, because it is the shape of the pass and not an
         * incidental count — a stage added or removed changes it, and a test driving the wrong
         * number of steps fails as a missing write rather than as a miscount.
         */
        const val STEPS = 3

        /** A restatement that parses: no date, no predicate, and a claim that stands on its own. */
        const val RESTATED = "none|none|none|The restated claim."

        /** A classification line this build can read, for a case about the key rather than the line. */
        const val RESTATED_LINE = "commitment|m1|plain|none"

        /** Any request at all, for a case that seeds a submission rather than driving one. */
        fun request() = ModelRequest(system = "an instruction", prompt = "a window", maxTokens = 100)

        fun message(nativeId: String) = ExternalId(Source.TELEGRAM, nativeId)

        /**
         * A candidate pair of commitments, as the collapse's store would offer it.
         *
         * Both in [CHAT], because the conversation is part of the key and a pair across two is not a
         * pair at all. The later claim is given the later span, so which of the two survives is
         * settled by the fixture rather than by the identifiers.
         */
        fun pair(survivor: String, superseded: String): FindingPair = FindingPair.of(
            FindingKind.COMMITMENT,
            finding(survivor, FIRST),
            finding(superseded, FIRST.plusSeconds(AN_HOUR)),
        )

        fun finding(claim: String, at: Instant) = Commitment(
            id = EntityId.mint(),
            claim = claim,
            conversation = CHAT,
            evidence = setOf(
                Evidence(
                    span = SpanId(kind = RecordKind.MESSAGE, record = message("m-$claim")),
                    sourceTime = at,
                ),
            ),
            hedged = false,
        )

        /**
         * Vectors putting [pair] beyond doubt, so the cheap tier settles it.
         *
         * One vector for both claims: identical directions are a cosine of one, which is above any
         * threshold the constants could be moved to.
         */
        fun sameFact(pair: FindingPair): Map<String, List<Float>> =
            mapOf(pair.survivor.claim to listOf(1f, 0f), pair.superseded.claim to listOf(1f, 0f))

        /**
         * Vectors putting [pair] apart, so the cheap tier rules it out and nothing is asked.
         *
         * Orthogonal directions are a cosine of nought, which is below any bound the constants could
         * be moved to — and both claims are embedded, so this is a comparison that was made and
         * answered rather than a pair nothing compared.
         */
        fun apart(pair: FindingPair): Map<String, List<Float>> =
            mapOf(pair.survivor.claim to listOf(1f, 0f), pair.superseded.claim to listOf(0f, 1f))

        /** A candidate pair of values under one subject and predicate, as the store would offer one. */
        fun valuePair(one: String, other: String) =
            ValuePair(subject = CONTACT, predicate = Predicate.of("charges"), one = one, other = other)

        /** One reading of an attribute, as the statement store would hold it. */
        fun statement(value: String, at: Instant) = Statement(
            subject = CONTACT,
            predicate = Predicate.of("charges"),
            value = value,
            validity = Validity(from = at),
            origin = Origin.INFERRED,
            evidence = setOf(
                Evidence(
                    span = SpanId(kind = RecordKind.MESSAGE, record = message("m-$at")),
                    sourceTime = at,
                ),
            ),
        )

        /** Vectors putting two values in the band that is worth paying to decide. */
        fun borderlineValues(pair: ValuePair): Map<String, List<Float>> {
            val target = (SAME_FACT + WORTH_ASKING) / 2
            val between = listOf(target.toFloat(), kotlin.math.sqrt(1 - target * target).toFloat())
            check(cosine(listOf(1f, 0f), between) >= WORTH_ASKING) { "the band has closed: nothing is asked about" }
            return mapOf(pair.one to listOf(1f, 0f), pair.other to between)
        }

        /**
         * Vectors putting [pair] between the two thresholds, so the model tier is reached.
         *
         * **Derived from the thresholds rather than written out**, so that moving either constant
         * moves these cases with it: a hand-picked pair of floats would go on asserting the band it
         * was written for and would quietly start testing a tier it no longer reaches.
         */
        fun borderline(pair: FindingPair): Map<String, List<Float>> {
            val target = (SAME_FACT + WORTH_ASKING) / 2
            val between = listOf(target.toFloat(), kotlin.math.sqrt(1 - target * target).toFloat())
            check(cosine(listOf(1f, 0f), between) < SAME_FACT) { "the band has closed: nothing reaches the model" }
            check(cosine(listOf(1f, 0f), between) >= WORTH_ASKING) { "the band has closed: nothing is worth asking" }
            return mapOf(pair.survivor.claim to listOf(1f, 0f), pair.superseded.claim to between)
        }

        /** One statement already held about [CONTACT], under a predicate no case writes to. */
        fun statementAbout(predicate: String, value: String) = Statement(
            subject = CONTACT,
            predicate = Predicate.of(predicate),
            value = value,
            validity = Validity(from = EARLIER),
            origin = Origin.INFERRED,
            evidence = setOf(evidence("m-$predicate", EARLIER)),
        )

        fun evidence(nativeId: String, at: Instant) = Evidence(
            span = SpanId(kind = RecordKind.MESSAGE, record = message(nativeId)),
            sourceTime = at,
        )

        fun conversation(count: Int) = List(count) { index ->
            Message(
                externalId = message("m${index + 1}"),
                sourceTime = FIRST.plusSeconds(index.toLong()),
                author = AUTHOR,
                text = "message ${index + 1}",
            )
        }

        /**
         * The conversation without [gone] — the one way a window can be lost.
         *
         * A window is named by the message it reads, so it cannot move: what loses one is that
         * message leaving its conversation, which a withdrawal is how the corpus arranges. The
         * conversation is otherwise unchanged, so a submission collected against it finds every
         * other key it asked under.
         */
        fun withdrawn(gone: String) = conversation(2).filterNot { it.externalId.nativeId == gone }

        /**
         * A port member this pass must not reach.
         *
         * Failing rather than returning something empty: a fake that quietly answered would turn
         * a pass reaching past its seam into a passing test.
         */
        fun unexpected(): Nothing =
            error("The inference pass called a port member it has no business calling.")
    }
}
