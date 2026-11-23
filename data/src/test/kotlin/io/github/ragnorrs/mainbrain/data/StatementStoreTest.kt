package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.entity.ValueSynonym
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.recognition.SAME_FACT
import io.github.ragnorrs.mainbrain.domain.recognition.WORTH_ASKING
import io.github.ragnorrs.mainbrain.domain.recognition.runningVectorsVersion
import io.github.ragnorrs.mainbrain.domain.recognition.vectorsVersion
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statement store, against a real Postgres.
 *
 * Not a unit test, because every claim here is held by the schema or by the write rather than by
 * Kotlin. That a fact read twice stays one fact is a unique constraint over content; that evidence
 * accumulates is a second row under one key; that a closed window is never reopened is a
 * `COALESCE` in an `ON CONFLICT` clause; that a model's reading cannot overwrite a person's
 * correction is a `CASE` whose ordering has to agree with an enum in another module. A fake would
 * assert that the fake does these.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case works with a freshly
 * minted subject and reads back only its own.
 */
class StatementStoreTest {

    private val statements = JdbcStatementRepository(StoreRig.database)

    @Test
    fun `a statement round-trips with its evidence`(): Unit = runBlocking {
        val subject = EntityId.mint()
        val statement = statement(subject, evidence = setOf(evidence("round-trip", MARCH)))

        statements.record(listOf(statement))

        assertEquals(listOf(statement), statements.about(listOf(subject)))
    }

    @Test
    fun `a fact read again is still one fact`(): Unit = runBlocking {
        // Re-running recognition over an unchanged corpus produces no writes. A statement has no
        // identifier to mint, so its key is its content and the second write collides with the
        // first — which is what makes re-derivation free rather than duplicating the corpus's
        // worth of facts on every pass.
        val subject = EntityId.mint()
        val statement = statement(subject, evidence = setOf(evidence("idempotent", MARCH)))

        statements.record(listOf(statement))
        statements.record(listOf(statement))

        assertEquals(listOf(statement), statements.about(listOf(subject)))
    }

    @Test
    fun `the same fact from a second span is one statement carrying both`(): Unit = runBlocking {
        // Corroboration rather than duplication: two spans saying the same thing is one fact with
        // two pointers, and either can be shown beside it.
        val subject = EntityId.mint()
        val first = evidence("corroborated-one", MARCH)
        val second = evidence("corroborated-two", APRIL)

        statements.record(listOf(statement(subject, evidence = setOf(first))))
        statements.record(listOf(statement(subject, evidence = setOf(second))))

        val stored = statements.about(listOf(subject)).single()
        assertEquals(
            setOf(first, second),
            stored.evidence,
            "A second span saying the same thing either replaced the first pointer or forked the " +
                "fact. It is one statement, and the evidence accumulates on it.",
        )
    }

    @Test
    fun `the same span offered twice adds nothing`(): Unit = runBlocking {
        // Recognition's windows overlap, so a span is read by more than one of them by design.
        // That has to cost nothing rather than grow the evidence on every pass.
        val subject = EntityId.mint()
        val span = evidence("overlapping", MARCH)

        statements.record(listOf(statement(subject, evidence = setOf(span))))
        statements.record(listOf(statement(subject, evidence = setOf(span))))

        assertEquals(setOf(span), statements.about(listOf(subject)).single().evidence)
    }

    @Test
    fun `a later contradicting value closes the earlier window and keeps both`(): Unit = runBlocking {
        // The conflict rule. Two statements, adjacent windows, nothing deleted — because "it was
        // true until June" and "it was never true" are different answers.
        val subject = EntityId.mint()
        val earlier = statement(
            subject,
            value = "one rate",
            validity = Validity(from = MARCH, until = JUNE),
            evidence = setOf(evidence("conflict-earlier", MARCH)),
        )
        val later = statement(
            subject,
            value = "another rate",
            validity = Validity(from = JUNE),
            evidence = setOf(evidence("conflict-later", JUNE)),
        )

        statements.record(listOf(earlier, later))

        val stored = statements.about(listOf(subject))
        assertEquals(setOf(earlier, later), stored.toSet(), "A contradiction removed a reading.")
        assertEquals(
            listOf(earlier),
            stored.filter { it.heldAt(APRIL) },
            "April is covered by exactly one of the two windows, or the question has two answers.",
        )
        assertEquals(listOf(later), stored.filter { it.heldAt(JUNE) }, "June belongs to the later window.")
    }

    @Test
    fun `a closed window is not reopened by a pass re-deriving the earlier fact`(): Unit = runBlocking {
        // The failure this guards against is specific: a pass re-derives an old fact in a batch
        // that happens not to contain the later, contradicting one, and offers it with an open
        // window. Taking the offered value would unclose a window that was correctly shut, and
        // the contradiction would have to be found again before anything read the fact.
        val subject = EntityId.mint()
        val span = setOf(evidence("not-reopened", MARCH))
        statements.record(
            listOf(statement(subject, validity = Validity(from = MARCH, until = JUNE), evidence = span)),
        )

        statements.record(listOf(statement(subject, validity = Validity(from = MARCH), evidence = span)))

        assertEquals(
            JUNE,
            statements.about(listOf(subject)).single().validity.until,
            "A re-derivation reopened a closed validity window.",
        )
    }

    @Test
    fun `a close can move earlier, which is what a backfill asks for`(): Unit = runBlocking {
        // The other half of the one-way rule, and the half that was missing: a stored close may
        // not be *reopened*, but it has to be allowed to move. A backfill reads older history
        // after newer, so a contradicting value can arrive for a period *between* two windows
        // already stored — and resolution then computes an earlier close for the first of them.
        // Refusing it leaves two statements covering the instants in between, which is exactly
        // what the half-open window is supposed to make impossible.
        val subject = EntityId.mint()
        val span = setOf(evidence("narrowed", MARCH))
        statements.record(
            listOf(statement(subject, validity = Validity(from = MARCH, until = SEPTEMBER), evidence = span)),
        )

        statements.record(
            listOf(statement(subject, validity = Validity(from = MARCH, until = JUNE), evidence = span)),
        )

        assertEquals(
            JUNE,
            statements.about(listOf(subject)).single().validity.until,
            "A close computed earlier than the stored one was refused, so the fact still holds " +
                "over a period a later reading contradicts.",
        )
    }

    @Test
    fun `a window closes on a pass that has the contradiction in hand`(): Unit = runBlocking {
        // The other direction, and the reason the rule above is one-way rather than "never
        // change the window": a close has to be writable, or a contradiction could never be
        // recorded for a fact already stored as open.
        val subject = EntityId.mint()
        val span = setOf(evidence("closes", MARCH))
        statements.record(listOf(statement(subject, validity = Validity(from = MARCH), evidence = span)))

        statements.record(
            listOf(statement(subject, validity = Validity(from = MARCH, until = JUNE), evidence = span)),
        )

        assertEquals(JUNE, statements.about(listOf(subject)).single().validity.until)
    }

    @Test
    fun `two readings of one period are both kept`(): Unit = runBlocking {
        // Why the value is in the key, which the window's start does not cover: two sources
        // disagreeing about the same period is a disagreement to keep, not one reading to pick.
        // Without the value, the second write collides with the first and its value is discarded
        // silently — the row holds one reading and nothing records that there was another.
        val subject = EntityId.mint()
        val one = statement(
            subject,
            value = "what one source said",
            evidence = setOf(evidence("disagreement-one", MARCH)),
        )
        val other = statement(
            subject,
            value = "what the other said",
            evidence = setOf(evidence("disagreement-other", MARCH)),
        )

        statements.record(listOf(one, other))

        assertEquals(
            setOf(one, other),
            statements.about(listOf(subject)).toSet(),
            "Two sources disagreeing over one window left one reading stored. Both are kept: " +
                "picking between them is the answer path's to do, with both in view.",
        )
    }

    @Test
    fun `one value holding over two periods is two statements`(): Unit = runBlocking {
        // Why the window's start is in the key. A rate quoted in March, revised in June and
        // restored in September agrees with itself on every other part of the key, and a key
        // without the start would collapse the two periods into one.
        val subject = EntityId.mint()
        val first = statement(
            subject,
            validity = Validity(from = MARCH, until = JUNE),
            evidence = setOf(evidence("twice-first", MARCH)),
        )
        val again = statement(
            subject,
            validity = Validity(from = SEPTEMBER),
            evidence = setOf(evidence("twice-again", SEPTEMBER)),
        )

        statements.record(listOf(first, again))

        assertEquals(setOf(first, again), statements.about(listOf(subject)).toSet())
        assertTrue(
            statements.about(listOf(subject)).none { it.heldAt(JULY) },
            "July falls in the gap between the two windows and nothing should cover it.",
        )
    }

    @Test
    fun `an origin is strengthened and never downgraded`(): Unit = runBlocking {
        // A correction outranks re-recognition, and a source's own structure outranks a model's
        // reading. This is the one place the authority order is enforced rather than stated, and
        // it has to agree with `Origin`'s declaration order in the domain.
        val subject = EntityId.mint()
        val span = setOf(evidence("origin", MARCH))
        statements.record(listOf(statement(subject, origin = Origin.INFERRED, evidence = span)))

        statements.record(listOf(statement(subject, origin = Origin.LABELLED, evidence = span)))
        assertEquals(
            Origin.LABELLED,
            statements.about(listOf(subject)).single().origin,
            "A labelled reading did not replace an inferred one. A profile field outranks a " +
                "passing mention.",
        )

        statements.record(listOf(statement(subject, origin = Origin.CONFIRMED, evidence = span)))
        statements.record(listOf(statement(subject, origin = Origin.INFERRED, evidence = span)))
        assertEquals(
            Origin.CONFIRMED,
            statements.about(listOf(subject)).single().origin,
            "Re-recognition overwrote a person's correction, which is the one thing recognition " +
                "may not do.",
        )
    }

    @Test
    fun `a backfilled earlier reading of one value leaves one statement, not two`(): Unit = runBlocking {
        // The case the content key makes awkward. A fact read out of a June message opens its
        // window in June; backfill then delivers an older message saying the same thing, and
        // resolution correctly recomputes the window as opening in March — which is a different
        // key. Written as a pure addition that is a second row beside the one it supersedes: same
        // value, overlapping windows, and half the evidence on each, with nothing to say which is
        // current. Backfill runs backwards by design, so this is ordinary rather than exotic.
        val subject = EntityId.mint()
        val fromJune = statement(
            subject,
            value = "An Employer",
            validity = Validity(from = JUNE),
            evidence = setOf(evidence("backfill-june", JUNE)),
        )
        statements.record(listOf(fromJune))

        val widened = fromJune.copy(
            validity = Validity(from = MARCH),
            evidence = fromJune.evidence + evidence("backfill-march", MARCH),
        )
        statements.record(listOf(widened))

        val stored = statements.about(listOf(subject))
        assertEquals(
            listOf(Validity(from = MARCH)),
            stored.map { it.validity },
            "The June-keyed row was left beside the March-keyed one, so one fact is stored twice " +
                "with overlapping windows: $stored",
        )
        assertEquals(
            2,
            stored.single().evidence.size,
            "The surviving statement carries the union of the evidence, or superseding one row " +
                "has lost the span that was attached to it.",
        )
    }

    @Test
    fun `superseding touches only the predicate it was given`(): Unit = runBlocking {
        // The precondition is per subject *and* predicate, which is what lets a pass write one
        // fact about a contact without deleting everything else known about them.
        val subject = EntityId.mint()
        val other = EntityId.mint()
        val elsewhere = statement(
            subject,
            value = "a value under another predicate",
            evidence = setOf(evidence("untouched-predicate", MARCH)),
        ).copy(predicate = Predicate.of("based in"))
        val another = statement(other, evidence = setOf(evidence("untouched-subject", MARCH)))
        statements.record(listOf(elsewhere))
        statements.record(listOf(another))

        statements.record(
            listOf(statement(subject, value = "a fresh reading", evidence = setOf(evidence("fresh", JUNE)))),
        )

        assertEquals(
            listOf(elsewhere),
            statements.about(listOf(subject)).filter { it.predicate == Predicate.of("based in") },
            "Writing one predicate removed what was stored under another.",
        )
        assertEquals(listOf(another), statements.about(listOf(other)), "Another subject's statements were removed.")
    }

    @Test
    fun `re-recording the same set supersedes nothing`(): Unit = runBlocking {
        // The removal is driven by what the written set does not contain, so an unchanged re-run
        // has to be inert — otherwise every pass would delete and rewrite the corpus's facts.
        val subject = EntityId.mint()
        val held = statement(subject, evidence = setOf(evidence("inert", MARCH)))
        statements.record(listOf(held))

        val again = statements.record(listOf(held))

        assertEquals(listOf(held), statements.about(listOf(subject)))
        assertEquals(
            Stored(written = 0, overwritten = 1, superseded = 0),
            again,
            "An inert re-run claimed to have caused or removed a row, which is the figure a sweep " +
                "reports as its own work.",
        )
    }

    @Test
    fun `a write says which rows it caused, replaced and removed`(): Unit = runBlocking {
        // **The port that moves all three figures, and the only producer of the third.** The rows
        // removed are the ones this port holds and was not given back, so a caller counting what it
        // handed over cannot see the store get smaller at all.
        val subject = EntityId.mint()
        val inJune = statement(
            subject,
            value = "An Employer",
            validity = Validity(from = JUNE),
            evidence = setOf(evidence("figures-june", JUNE)),
        )
        statements.record(listOf(inJune))

        // The backfill case: an earlier reading of the same value moves the window's start, and the
        // start is in the key — so the June-keyed row is removed and a March-keyed one inserted.
        val widened = inJune.copy(
            validity = Validity(from = MARCH),
            evidence = inJune.evidence + evidence("figures-march", MARCH),
        )
        val stored = statements.record(listOf(widened))

        assertEquals(
            Stored(written = 1, overwritten = 0, superseded = 1),
            stored,
            "The store lost a row and gained one, and the figures said otherwise.",
        )
    }

    @Test
    fun `a contradicting value is a row caused beside a row replaced`(): Unit = runBlocking {
        // The ordinary resolution case, and the one that must not read as a removal: the earlier
        // window is narrowed rather than removed, because "it was true until June" and "it was
        // never true" are different answers.
        val subject = EntityId.mint()
        val earlier = statement(
            subject,
            value = "A Former Employer",
            validity = Validity(from = MARCH),
            evidence = setOf(evidence("narrowed-march", MARCH)),
        )
        statements.record(listOf(earlier))

        val stored = statements.record(
            listOf(
                earlier.copy(validity = Validity(from = MARCH, until = JUNE)),
                statement(
                    subject,
                    value = "A Current Employer",
                    validity = Validity(from = JUNE),
                    evidence = setOf(evidence("narrowed-june", JUNE)),
                ),
            ),
        )

        assertEquals(
            Stored(written = 1, overwritten = 1, superseded = 0),
            stored,
            "Narrowing a window was reported as removing a row, or opening one as replacing it.",
        )
    }

    @Test
    fun `nothing offered is nothing done, and nothing claimed`(): Unit = runBlocking {
        assertEquals(Stored.NOTHING, statements.record(emptyList()))
        assertEquals(Stored.NOTHING, statements.recollapsed(emptyList()))
    }

    @Test
    fun `nothing is read back for a subject that was not asked about`(): Unit = runBlocking {
        val subject = EntityId.mint()
        val other = EntityId.mint()
        statements.record(listOf(statement(subject, evidence = setOf(evidence("scoped", MARCH)))))

        assertEquals(
            emptyList<Statement>(),
            statements.about(listOf(other)),
            "A read scoped to one subject returned another's facts. The rig is shared, so this " +
                "is the assertion that the `unnest` join filters rather than decorating.",
        )
    }

    // ---- two wordings of one value ---------------------------------------------------------

    /**
     * The state a synonym verdict has to repair, written back through the store.
     *
     * **This is the case `record` cannot carry, and the reason the collapse needs its own write.**
     * The forked state is two rows: the first wording closed where the second opened. Once the two
     * values are known to be one, resolution correctly produces a single *open* window — but
     * `record` never reopens a stored close, by a rule established deliberately for the ordinary
     * re-derivation path, so the `COALESCE` keeps the stale close and the second row is then
     * removed for not being offered. The attribute would read as having ended, with nothing
     * current, and no later run could repair it: re-resolving is idempotent and offers the same
     * open window for ever.
     */
    @Test
    fun `collapsing two wordings leaves the fact holding, not ended`(): Unit = runBlocking {
        val subject = EntityId.mint()
        val first = evidence("collapse-first", MARCH)
        val second = evidence("collapse-second", JUNE)
        // The fork: "A" held until June, when "B" took over. The same fact, worded twice.
        statements.record(
            listOf(
                statement(
                    subject,
                    value = "Acme Ltd",
                    validity = Validity(from = MARCH, until = JUNE),
                    evidence = setOf(first),
                ),
                statement(
                    subject,
                    value = "Acme Limited",
                    validity = Validity(from = JUNE),
                    evidence = setOf(second),
                ),
            ),
        )

        // What resolution produces once the verdict says the two wordings are one value.
        statements.recollapsed(
            listOf(
                statement(
                    subject,
                    value = "Acme Ltd",
                    validity = Validity(from = MARCH),
                    evidence = setOf(first, second),
                ),
            ),
        )

        val stored = assertNotNull(statements.about(listOf(subject)).singleOrNull(), "one fact, not two")
        assertEquals("Acme Ltd", stored.value)
        assertEquals(MARCH, stored.validity.from)
        assertNull(
            stored.validity.until,
            "the fact still holds: the close belonged to a contradiction that turned out to be a " +
                "rewording, so leaving it would make the attribute read as ended with nothing current",
        )
        assertEquals(setOf(first, second), stored.evidence, "and it carries both wordings' evidence")
    }

    @Test
    fun `two values of one subject and predicate are offered as a pair`(): Unit = runBlocking {
        val subject = EntityId.mint()
        statements.record(
            listOf(
                statement(subject, value = "Acme Ltd", evidence = setOf(evidence("pair-a", MARCH))),
                statement(
                    subject,
                    value = "Acme Limited",
                    validity = Validity(from = JUNE),
                    evidence = setOf(evidence("pair-b", JUNE)),
                ),
            ),
        )

        val offered = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).filter { it.subject == subject }

        val pair = assertNotNull(offered.singleOrNull(), "exactly one pair, offered once: $offered")
        assertEquals(Predicate.of("works at"), pair.predicate)
        assertEquals(setOf("Acme Ltd", "Acme Limited"), setOf(pair.one, pair.other))
    }

    /**
     * One value can hold over two separate periods, and the question being decided is about the two
     * strings — so a self-join over the rows would offer one pair of wordings once per pair of
     * windows and pay for the same answer repeatedly.
     */
    @Test
    fun `a value that held over two periods is not a pair with itself`(): Unit = runBlocking {
        val subject = EntityId.mint()
        statements.record(
            listOf(
                statement(
                    subject,
                    value = "Acme Ltd",
                    validity = Validity(from = MARCH, until = JUNE),
                    evidence = setOf(evidence("twice-a", MARCH)),
                ),
                statement(
                    subject,
                    value = "Globex",
                    validity = Validity(from = JUNE, until = JULY),
                    evidence = setOf(evidence("twice-b", JUNE)),
                ),
                statement(
                    subject,
                    value = "Acme Ltd",
                    validity = Validity(from = JULY),
                    evidence = setOf(evidence("twice-c", JULY)),
                ),
            ),
        )

        val offered = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).filter { it.subject == subject }

        assertEquals(1, offered.size, "two distinct wordings are one pair, however many windows they held over")
        assertEquals(setOf("Acme Ltd", "Globex"), setOf(offered.single().one, offered.single().other))
    }

    @Test
    fun `a pair the model decided is never offered again, whichever way it went`(): Unit = runBlocking {
        listOf(true, false).forEach { same ->
            val subject = EntityId.mint()
            statements.record(
                listOf(
                    statement(subject, value = "Acme Ltd", evidence = setOf(evidence("decided-a-$same", MARCH))),
                    statement(
                        subject,
                        value = "Acme Limited",
                        validity = Validity(from = JUNE),
                        evidence = setOf(evidence("decided-b-$same", JUNE)),
                    ),
                ),
            )
            val pair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == subject }

            statements.decide(pair, same = same, decidedBy = ADJUDICATED_BY)

            assertEquals(
                emptyList<ValuePair>(),
                statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).filter { it.subject == subject },
                "a verdict of $same was not recorded, so the next idle sweep would buy it again",
            )
        }
    }

    /**
     * The statement half of the adjudication version: rewording the comparison makes a paid verdict
     * owed again, and a cheap rule-out is left where it is.
     *
     * Both halves in one case, because what the query has to get right is telling the two apart —
     * they share one column, and a predicate honouring the wrong version would either re-buy every
     * vector judgement on a reword or keep a verdict for ever after a better embedder arrived.
     */
    @Test
    fun `a value verdict is retired by its own version and not the other tier's`(): Unit = runBlocking {
        val paid = EntityId.mint()
        val cheap = EntityId.mint()
        listOf(paid, cheap).forEach { subject ->
            statements.record(
                listOf(
                    statement(subject, value = "Acme Ltd", evidence = setOf(evidence("ver-a-$subject", MARCH))),
                    statement(
                        subject,
                        value = "Acme Limited",
                        validity = Validity(from = JUNE),
                        evidence = setOf(evidence("ver-b-$subject", JUNE)),
                    ),
                ),
            )
        }
        val paidPair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == paid }
        val cheapPair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == cheap }
        // Counted before the verdicts and compared against the counts after, because the rig is
        // shared: a count is over every subject any case has written, so only its own movement is
        // this case's to assert.
        val currentBefore = statements.valueCandidateCount(COMPARED_BY, ADJUDICATED_BY)
        val rewordedBefore = statements.valueCandidateCount(COMPARED_BY, REWORDED)

        statements.decide(paidPair, same = false, decidedBy = ADJUDICATED_BY)
        statements.decide(cheapPair, same = false, decidedBy = COMPARED_BY)

        val owed = statements.valueCandidates(LIMIT, COMPARED_BY, REWORDED)
        assertEquals(
            listOf(paidPair),
            owed.filter { it.subject == paid },
            "a verdict bought under a wording that no longer exists is owed again",
        )
        assertEquals(
            emptyList<ValuePair>(),
            owed.filter { it.subject == cheap },
            "and a reworded comparison says nothing about what the vectors ruled out",
        )
        assertEquals(
            currentBefore - 2,
            statements.valueCandidateCount(COMPARED_BY, ADJUDICATED_BY),
            "both verdicts are honoured under the version that is running, so the count falls by two",
        )
        assertEquals(
            rewordedBefore - 1,
            statements.valueCandidateCount(COMPARED_BY, REWORDED),
            "and only the cheap one is honoured under a reworded comparison, so it falls by one — " +
                "the count moves with the selection, or the driver reads a stall where there is work",
        )
    }

    /**
     * A moved similarity bound retires this side's rule-outs too, and leaves the paid verdicts.
     *
     * The bound this side reads decides what it is worth paying to ask about, so moving it changes
     * which pairs are ruled out — and the answers given under the old one have to be taken again.
     * The finding side's equivalent is in `CollapseStoreTest`; this is the half that would otherwise
     * rest on the two sides sharing a value by inspection.
     */
    @Test
    fun `a value rule-out is retired by a moved similarity bound and a paid verdict is not`(): Unit =
        runBlocking {
            assertNotEquals(
                COMPARED_BY,
                BOUND_MOVED,
                "moving a bound has to move the version, or this case asserts nothing: $COMPARED_BY",
            )
            val paid = EntityId.mint()
            val cheap = EntityId.mint()
            listOf(paid, cheap).forEach { subject ->
                statements.record(
                    listOf(
                        statement(subject, value = "Acme Ltd", evidence = setOf(evidence("bnd-a-$subject", MARCH))),
                        statement(
                            subject,
                            value = "Acme Limited",
                            validity = Validity(from = JUNE),
                            evidence = setOf(evidence("bnd-b-$subject", JUNE)),
                        ),
                    ),
                )
            }
            val offered = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY)
            val paidPair = offered.single { it.subject == paid }
            val cheapPair = offered.single { it.subject == cheap }

            statements.decide(paidPair, same = false, decidedBy = ADJUDICATED_BY)
            statements.decide(cheapPair, same = false, decidedBy = COMPARED_BY)

            assertEquals(
                emptyList<ValuePair>(),
                statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY)
                    .filter { it.subject == paid || it.subject == cheap },
                "both answers hold under the version that gave them, or this case proves nothing",
            )
            val owed = statements.valueCandidates(LIMIT, BOUND_MOVED, ADJUDICATED_BY)
            assertEquals(
                listOf(cheapPair),
                owed.filter { it.subject == cheap },
                "the pair the vectors ruled out is owed again under a bound that would decide it afresh",
            )
            assertEquals(
                emptyList<ValuePair>(),
                owed.filter { it.subject == paid },
                "and a moved bound says nothing about what the model was paid to decide",
            )
        }

    /**
     * **One unordered pair is one row**, and the order is the database's to choose.
     *
     * Which of two values a sweep compares first carries nothing, so a verdict stored under the
     * order it was asked in could be written twice and found by neither asking. The ordering is
     * done in SQL because it is a question of collation: a caller sorting text with its own
     * comparison would disagree with the index this has to satisfy.
     */
    @Test
    fun `a verdict is found whichever way round the pair was given`(): Unit = runBlocking {
        val subject = EntityId.mint()
        statements.record(
            listOf(
                statement(subject, value = "Acme Ltd", evidence = setOf(evidence("order-a", MARCH))),
                statement(
                    subject,
                    value = "Acme Limited",
                    validity = Validity(from = JUNE),
                    evidence = setOf(evidence("order-b", JUNE)),
                ),
            ),
        )
        val pair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == subject }
        val reversed = ValuePair(pair.subject, pair.predicate, pair.other, pair.one)

        statements.decide(reversed, same = true, decidedBy = ADJUDICATED_BY)
        // Recorded the other way round too, which must be the same row and must not fail.
        statements.decide(pair, same = true, decidedBy = ADJUDICATED_BY)

        assertEquals(
            emptyList<ValuePair>(),
            statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).filter { it.subject == subject },
        )
        assertEquals(
            1,
            statements.synonymsOf(listOf(subject)).size,
            "one unordered pair is one synonym, however many times it was decided",
        )
    }

    @Test
    fun `only the verdicts that say same are read back as synonyms`(): Unit = runBlocking {
        val subject = EntityId.mint()
        statements.record(
            listOf(
                statement(subject, value = "Acme Ltd", evidence = setOf(evidence("syn-a", MARCH))),
                statement(
                    subject,
                    value = "Globex",
                    validity = Validity(from = JUNE),
                    evidence = setOf(evidence("syn-b", JUNE)),
                ),
            ),
        )
        val pair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == subject }

        statements.decide(pair, same = false, decidedBy = ADJUDICATED_BY)

        assertEquals(
            emptyList<ValueSynonym>(),
            statements.synonymsOf(listOf(subject)),
            "two values kept apart tell resolution nothing: it already treats them as two",
        )
    }

    @Test
    fun `a value holding the index's own length is still stored`(): Unit = runBlocking {
        // The verdict table keys on a digest rather than on the values, so a value longer than an
        // index row can carry does not fail the insert and stop the pass.
        val subject = EntityId.mint()
        val long = "a very long value ".repeat(400)
        statements.record(
            listOf(
                statement(subject, value = long, evidence = setOf(evidence("long-a", MARCH))),
                statement(
                    subject,
                    value = long.replaceFirst("a very", "one very"),
                    validity = Validity(from = JUNE),
                    evidence = setOf(evidence("long-b", JUNE)),
                ),
            ),
        )
        val pair = statements.valueCandidates(LIMIT, COMPARED_BY, ADJUDICATED_BY).single { it.subject == subject }

        statements.decide(pair, same = true, decidedBy = ADJUDICATED_BY)

        assertEquals(1, statements.synonymsOf(listOf(subject)).size)
    }

    private fun statement(
        subject: EntityId,
        value: String = "a value",
        validity: Validity = Validity(from = MARCH),
        origin: Origin = Origin.INFERRED,
        evidence: Set<Evidence>,
    ) = Statement(
        subject = subject,
        predicate = Predicate.of("works at"),
        value = value,
        validity = validity,
        origin = origin,
        evidence = evidence,
    )

    /** A pointer at a message span, with a record identifier the rig guarantees is unused. */
    private fun evidence(name: String, at: Instant) = Evidence(
        span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId(name)),
        sourceTime = at,
    )

    private companion object {
        val MARCH: Instant = Instant.parse("2026-03-01T00:00:00Z")
        val APRIL: Instant = Instant.parse("2026-04-01T00:00:00Z")
        val JUNE: Instant = Instant.parse("2026-06-01T00:00:00Z")
        val JULY: Instant = Instant.parse("2026-07-01T00:00:00Z")
        val SEPTEMBER: Instant = Instant.parse("2026-09-01T00:00:00Z")

        /** Larger than any one case writes, so a case asserting a pair is not bounded out of it. */
        const val LIMIT = 512

        /** The sidecar version behind the vector versions below; its content carries nothing. */
        const val EMBEDDINGS = "fake-embeddings-1"

        /**
         * The vector version a case compares under; any cheap rule-out is recorded against it.
         *
         * Built by the production function for the reason `CollapseStoreTest` gives: this side's
         * rule-outs are retired by a moved similarity bound too, so a hand-written string would
         * leave that unexercised against real SQL.
         */
        val COMPARED_BY = runningVectorsVersion(EMBEDDINGS)

        /** The same sidecar with one similarity bound moved, which also retires a vector rule-out. */
        val BOUND_MOVED = vectorsVersion(EMBEDDINGS, sameFact = SAME_FACT, worthAsking = WORTH_ASKING - 0.05)

        /** The adjudication version a case buys verdicts under; a paid one is recorded against it. */
        const val ADJUDICATED_BY = "adjudication-1:fake-model@1/MEDIUM"

        /** The same stage under a reworded comparison, which is what retires a paid verdict. */
        const val REWORDED = "adjudication-1:fake-model@2/MEDIUM"
    }
}
