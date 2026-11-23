package io.github.ragnorrs.mainbrain.domain.answer.shape.status

import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Closed
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Generate
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.Horizon
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.answer.Stage
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.answer.contacts
import io.github.ragnorrs.mainbrain.domain.answer.reach
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.firstStated
import io.github.ragnorrs.mainbrain.domain.entity.lastStated
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Where things stand with a subject, what is owed, and what comes next.
 *
 * **The daily driver, and the only shape that is pure corpus retrieval end to end.** It is also the
 * one the entity model most directly serves: what is outstanding and what is coming are questions
 * about commitments and events, which is to say about the derived layer, and the messages are there
 * so that every assertion can point at the turn it came from.
 *
 * **It accepts the inbox, which is the subject with nothing behind it.** Asked naming nobody it
 * answers from entities across everything, because there is no thread to narrow to; asked about a
 * contact, a client or a conversation it reads that subject's material as well. The two are one shape
 * rather than two, because they are the same question answered at two scopes.
 *
 * **Naming nobody rather than merely arriving without a name**, which for the shape accepting the
 * inbox is the difference between the daily question and an answer about everything to a question
 * about one person. A request whose subject was never established carries no name either and is
 * refused rather than widened — see
 * [io.github.ragnorrs.mainbrain.domain.answer.SubjectName].
 *
 * **It reads the turn behind each undischarged promise, because the row cannot say whether anybody
 * agreed to it.** A question about what was agreed needs three things told apart — what somebody
 * proposed, what one party undertook, and what both sides settled — and a commitment expresses only
 * the middle one. Worse, it does not say so: the claim is restated to stand on its own as a sentence
 * naming who owes what to whom, so a thing one party merely put to another arrives as a flat
 * assertion that it will happen. The turn states it in the speaker's own words and the turns after
 * it carry the answer, so the distinction is recovered from the material — see [Lookup.following],
 * and `RetrieveStatus`, where the bound is.
 *
 * **Decided that way rather than by prose or by a field, and an A/B against the live model is
 * why.** Offered the claim alone the model reported a proposal as a settled arrangement and
 * asserted a mutual agreement the material did not hold; offered the turn beside it, it reported
 * the thing as put forward and answered, and labelled a request and a progress figure as what
 * they were without being asked to. A rule telling it to hedge every claim would have asked for a
 * judgement the material could not support, and a field distinguishing the three would be a second
 * re-derivation of the whole corpus. The pull request carries the arms and the figures.
 *
 * One variant: there is one true account of where things stand, and offering three would invite
 * somebody to pick the account they preferred.
 */
// One port or value per thing this shape needs, and the index read is what takes it past the
// ceiling: it reads the corpus, the derived layer and the index, and it is the only shape that
// needs all three plus a present moment and a calendar. Grouping them behind a type would hide
// which of them `shapes` is really wiring, and the five constructor signatures there are the one
// place that is meant to show it.
@Suppress("LongParameterList")
class StatusShape(
    model: ModelProvider,
    retrieval: Retrieval,
    lookup: Lookup,
    grouping: Grouping,
    clock: Clock,
    zone: ZoneId,
    sizing: Sizing = Sizing.ANSWERING,
) : Shape {

    override val id = ShapeId("status")

    /**
     * What this shape answers, for whatever routes free text.
     *
     * **It says *agreed* in as many words, because that is the question the identifier loses.** This
     * is the only shape that reads commitments and the verdict on whether anything later discharged
     * them, so a question about what was agreed belongs here and nowhere else — and a router given
     * the word `status` alone has no way to know that. The other half of the line is the two scopes
     * the shape answers at, since asking about one person and asking across everything are the same
     * question here.
     *
     * **In the record's own vocabulary, not the asker's**, which is the same discipline the prompt
     * keeps and for the same reason. A request for where things stand is a request for what is
     * *outstanding*, and that word — with *open*, *owed* and *pending* beside it — is one the shared
     * reply format forbids every shape, because an unrecorded outcome does not establish that
     * anything remains to be done. A line here promising it would be the build saying in one place what it refuses in
     * the other, so this says what the record holds instead. Routing a question phrased either way
     * still lands here: the router reads the whole line, and *agreed*, *undertaken* and *carried out*
     * are what that question is made of.
     */
    override val description =
        "Where things stand with somebody, or across everything: what was agreed, decided or " +
            "undertaken, whether anything since has carried it out or left the outcome " +
            "unrecorded, and what is coming up."

    /**
     * Every scope a name can pick out, and everything.
     *
     * A body of work is here for the reason a client is, and refusing it would be the narrower
     * answer rather than the safer one: a question about a piece of work kept as a thread per strand
     * would resolve to one strand or to a menu of them, which is the same question asked about part
     * of the work.
     */
    override val accepts = setOf(
        SubjectKind.CONTACT,
        SubjectKind.CLIENT,
        SubjectKind.BODY_OF_WORK,
        SubjectKind.CONVERSATION,
        SubjectKind.INBOX,
    )

    override val variants = 1..1

    override val readsCorpus = true

    /**
     * Yes, and this is the shape it is most plainly true of.
     *
     * A list of dated, cited items is the answer and a narrative is not — which this shape's own
     * instruction says — so a reply here carrying no assertion has not answered the question. It
     * has either put the findings where nothing checks them or stated that the corpus holds nothing
     * on a subject retrieval returned material for, and those two reach a reader alike.
     */
    override val assertsFindings = true

    override val stages: List<Stage> = listOf(
        RetrieveStatus(retrieval, lookup, clock, zone),
        // The same clock the retrieval reads, because the two answer one question between them:
        // which of these is now past, and what day that is being judged on. A second source of now
        // would let a reply call a deadline overdue against one instant and name today from
        // another.
        Assemble(INSTRUCTION, variants, grouping, asserts = assertsFindings, zone = zone, clock = clock),
        Generate(model, sizing),
    )

    private companion object {
        /**
         * What this shape asks of the model.
         *
         * It states what the answer is for — where things stand, what was undertaken, what is next
         * — and the cautions the derived layer makes necessary: a commitment is an inference and a
         * hedged one is weaker still; which party a commitment obliges is marked on it where the row
         * carries that party and is otherwise in its claim's prose, while the party beside it is
         * whoever stated it, and the two are not the same person once an obligation on somebody else
         * counts as a commitment; nothing is to be read out of an item appearing in this list, which
         * is this shape's half of a rule `Reply.format` states for every one of them, since it is
         * retrieval that put the item here; an attribute holds over a period rather than now; and an item
         * marked as stating no date has none, rather than one the model may supply from the nearest
         * date in front of it.
         *
         * **The second section is named for what the record holds rather than for what the question
         * asks.** A request for where things stand is a request for what is outstanding, and the
         * honest answer is a list of undertakings whose outcome is unrecorded — so asking for what
         * is outstanding in one line, in those words or in any synonym of them, and forbidding that
         * reading in another would be a conflict the model resolves by picking one, which is the
         * failure mode a prompt that cannot be obeyed as written produces. It is why the part a
         * reader acts on is asked for as what was *undertaken*: the reading to avoid is the one
         * that says the record establishes something is still being waited on, and *owed* carries
         * it as plainly as *outstanding* does.
         *
         * **Saying what to report without saying what a report is for is what produced a chronicle,
         * and it is the same absence the drafted reply had.** Every rule the prompt carries
         * about an individual line — cite it, date it where the material dates it, never call an
         * unclosed promise open, no narrative — is satisfied in full by a recap of the conversation:
         * a list of lines saying that somebody raised a question and somebody explained a position,
         * each one cited, each one true, and not one of them a statement of where anything stands.
         * Fluent, grounded, and it sends the reader back to the thread this request exists to save
         * them. So the failure is named here rather than left to follow from the absence of a
         * narrative.
         *
         * **What a recap does break is the rules about the answer as a whole, and those were asked
         * for and not obeyed.** The instruction asked for its three parts in order and in priority
         * order within each, and asked for the commitments grouped by standing with the ones now
         * past leading — and the recap came back flat. So the order is computed before the material
         * is offered rather than only asked for, and what is asked for here is named against the
         * item's own label; the purpose and the order are two fixes and not one.
         *
         * **A bottom line first, then what was undertaken, then what moved.** Two readers' practices
         * agree on this and neither is a taste: a brief leads with the conclusion rather than with
         * the activity, and an answer on a phone is layered rather than reordered, because the adapter
         * splits at four thousand characters and the first message is all that is read without
         * scrolling. What that buys is the reader learning whether anything needs doing before
         * reading anything else, which is the whole of what the measured answer could not do.
         *
         * **An item the record places on nobody and dates not at all is not a next action, and
         * saying so is what keeps the list honest.** The call-preparation shape has the only
         * comparable rule in the build — a question has to be specific enough to answer — and it is
         * the one shape whose answers read as actionable. Direction is a marker on the material
         * rather than a thing to infer, so the rule is answerable rather than aspirational.
         *
         * **Aggregating what the record does not hold is a rule about the answer and not about the
         * material.** The labels stay on every item, because a model told in prose that an absence
         * is not an open state and shown an unlabelled item follows what it is shown — see
         * `Material.state`, where that reasoning is stated. What the rule is about is the answer:
         * the absences the rendering states — a date the record does not hold, an outcome it does
         * not record — are each right on the item they are said of, and an answer that says one of
         * them again on every item it reports tells a reader, at that density, mostly that this
         * system does not know.
         *
         * **Asking for an absence once was obeyed in both directions at once, which is the one
         * reading of it that helps nobody.** A read of the rendered reply found the group sentence
         * written *and* every item under it carrying the same clause: a section opening with a
         * line saying the agreements below have no deadline and no recorded outcome, above items
         * that each said exactly that. Saying it for the group satisfies the ask, and saying it
         * per item satisfies it too, and the prose never said which.
         *
         * **So the ask now names one place, and that is the whole of the fix.** Once is once in
         * the answer, the opening is where it goes, and no line beneath a heading summarises the
         * items under it. Naming the place rather than forbidding a shape is what makes the three
         * rules obeyable together: the ask for a bottom line, the ask for an absence once, and
         * this. A prohibition alone left the statement with nowhere legal to be said — the
         * failure a prompt that cannot be obeyed as written produces, which the note on the
         * second section names.
         *
         * **A prohibition turning on the summary saying nothing new is the wording this rejected,
         * and a live run is why.** Told not to write a line stating *only* what the items beneath
         * it state, the model wrote one that also counted them — more than nothing new, and so
         * inside the rule. The clause therefore names the form, the count included, rather than
         * resting on what the line adds. `StatusShapeTest` carries the run.
         *
         * **The labels on the material are deliberately not what moves.** They stay on every item
         * for the reason the note on aggregating what the record does not hold gives, and this is
         * a rule about the answer's prose, as that one is.
         *
         * **Commentary is also what the hoisted citations hang off, though removing it is not the
         * whole of that card.** Both pieces of commentary carried citation lines — the material
         * belonging to the items under them, attached to the sentence above instead — so the items
         * needing sources had none. Asking for no such sentence leaves those citations nowhere to
         * hoist to; the rendering half is filed on its own and is not here.
         *
         * **An inventory of absences is not a lead, which is a rule about what the opening is for
         * rather than about what it may mention.** The same read found the reply opening on a
         * summary of the labels further down — nothing recorded as overdue, every agreement
         * undated with no outcome recorded, when they were last discussed. The sentence asking for
         * the bottom line is left as it is, because a model with nothing pressing to report
         * reaches for the labels it can see and needs telling what the top of the answer is for.
         *
         * **A shared absence had to be stated where its items could not be named, and the three
         * clauses governing it could not all be obeyed.** Saying it for the group asked for the
         * items named under it; saying it once put it in the opening; and opening a section with a
         * line about the items beneath it is forbidden — so the only reading left states the
         * absence where nothing may be named under it. A read of the rendered reply found exactly
         * that: an opening item reporting that none of the commitments carried a date and one had
         * an outcome, over three strands whose items a reader meets twenty lines later, and the
         * per-item clause arriving anyway. The group statement now names the items it covers, and
         * naming them in the opening is said not to be a second copy — the same exemption the rule
         * against reporting a finding twice already makes for naming what most needs attention.
         * Placed at the end of the paragraph rather than beside the clause it qualifies, because
         * what follows an insertion is what it degrades and the paragraph after this one is about
         * attributes, which this corpus cannot exercise at all.
         *
         * **`PrepareShape` carries the same wording and is deliberately left alone.** The defect
         * was read off a status reply; a brief is ordered differently and its own read has not been
         * taken, so changing both would be changing one on reasoning rather than on evidence.
         *
         * **A finding reported twice is a rule about the answer as a whole, which is why nothing
         * deterministic can hold it.** The same read found one agreement named in the opening and
         * then written out again as the first finding of its section, with more detail and the same
         * citation: one finding, two places, one source. Two claims sharing a citation is not the
         * test — a commitment and the event read out of the same message legitimately share one —
         * so this is stated in the terms the mistake is made in. What it is **not** is a bar on the
         * opening naming what most needs attention: that is the bottom line doing its work, and
         * the ordering and per-item rules still want the item written out in full in its section.
         * Scoped to a second *copy*, the rule leaves both standing.
         *
         * **Two readings of one undertaking whose outcomes do not agree are reported once, as done.**
         * A restatement is rewritten on its own, so one obligation can stand as two rows — one
         * carrying a deadline with no verdict against it, the other carrying a closure with
         * evidence. Offered as two items and grouped by standing, they compose into one reply as
         * overdue and as discharged, and the reader is told both. Nothing downstream can see it:
         * each half cites a span that exists, so the grounding gate passes the pair as readily as it
         * passes two real findings.
         *
         * **The store can settle such a pair and has not settled this one, which is why the answer
         * path has to cope.** `CollapseRepository` merges a reworded finding in three tiers — the
         * claim as a content key, a vector comparison, then the model for what that leaves — so the
         * pair is not evidence of a missing mechanism. What leaves one in front of a reader is that
         * the collapse runs inside the inference pass, which a deployment may have switched off;
         * that its candidates are bounded to one conversation and kind with first statements no
         * more than `Inferrer.DEFAULT_COLLAPSE_WINDOW` apart, so a restatement further out is never
         * a candidate; and that a pair the vectors score below `WORTH_ASKING` is ruled out without
         * the model tier ever being bought. A reply composed while any of those holds still has to
         * read as one answer.
         *
         * **Which half wins is not this file's judgement to make, and the build already made it.**
         * `CollapseRepository.merge` says a found closure outranks a verdict that found none,
         * whichever row holds it, because the two readings are of one obligation and an examination
         * that caught a closing message the other missed is new evidence rather than a
         * contradiction — each verdict reads forward from its own reading, so the one restated later
         * was never read against the message that closed the thing. So the instruction reconciles
         * the pair the way a merge would and does not invent a second answer for the answer path,
         * which would have the reply and the store disagreeing about one obligation.
         *
         * **Reporting it as done rather than as a disagreement is also what keeps it clear of the
         * state rule.** A line saying the record contradicts itself leaves a reader to adjudicate
         * and is read as something still to be chased, which is the one state nothing here
         * establishes — see `Reply.format`'s shared rule on what a commitment's outcome may be said
         * to be. One line reporting it as done asserts only what a message in the material reports.
         *
         * **What bounds the ask is the paragraph that follows it**, named by what it does rather
         * than by its position, which a later insertion would move. The cost of pairing wrongly is
         * not symmetrical: two distinct promises folded into one report an undertaking as done that
         * nothing records as done, and lose it silently, where leaving a real pair unpaired is the
         * defect this card already describes and a reader can see. So the bound says what is not a
         * pair, and sends a model that cannot tell to report separately.
         *
         * **What this deliberately does not ask for is what changed since the reader last looked.**
         * Nothing records when anybody last asked, so there is no such window to report against, and
         * an instruction naming one would be answered by inventing a baseline. What the material
         * does carry is a window of events behind and a window ahead, and that is what it asks for
         * instead.
         *
         * **Who wrote a turn and who did the work a turn reports are two facts, and nothing
         * marks the second.** A turn is marked with its sender and a finding with the party that
         * stated it, both of which name who *said* something; `obliges` is the one marker naming
         * an actor, and it names who is to carry an undertaking out rather than who did a piece
         * of work already reported done. So the rule that a message belongs to whoever is marked
         * on it — which this instruction already carries, and which is right about whose *words*
         * a turn holds — produces the misattribution on its own the moment a turn reports work:
         * a status written by one participant recounts what several did, and read under that
         * rule every action in it is the sender's. The same holds a finding's party, which is
         * the author of the message the finding was read out of rather than the participant
         * whose words it restates. Neither is catchable downstream: the sentence is fluent, the
         * citation resolves, and the grounding gate asks whether a cited span was retrieved and
         * not whether the span says what the sentence says.
         *
         * **Scoped to work reported as done, which is what keeps it out of the way of the two
         * rules either side of it.** The paragraph before says to read direction off `obliges`,
         * and the one after says to name the party each undertaking is placed on; a rule telling
         * the model not to credit an actor to a marker would contradict both, and a prompt that
         * cannot be obeyed as written is resolved by the model picking one of them. What is
         * asked here is narrower than that and does not meet either: an undertaking is a thing
         * to be done and carries a marker for whom it is on, while work a message reports as
         * finished carries no marker at all.
         *
         * **Compression is named separately because it is where the error survives review.** One
         * finding drawn from an episode several people worked on reads as one person's the moment
         * it is written as one sentence, and the clauses that would have carried the second
         * participant are exactly what compressing drops.
         *
         * **Whose business a finding is, which is a different question from whose words a turn
         * holds.** Asked about a person, the entity reads are narrowed to what that person stated,
         * so the findings the derived layer contributes concern them by construction. The message
         * tail is not narrowed that way and deliberately is not: it is the tail of the threads the
         * subject appears in, whoever wrote each turn, because half a dialogue is not a dialogue
         * and the reader's own turns are where the other side of the relationship is recorded.
         * Measured on this corpus, that leaves most of the tail written by somebody who is neither
         * — the threads are mostly rooms, which is what a subject's quieter threads almost always
         * are — and a reply composed off it reports a meeting somebody else cancelled as part of
         * where things stand with the subject. The pull request carries the figures.
         *
         * **Asked about a client or a conversation, neither half holds**: the entity reads narrow
         * by thread rather than by who spoke, and there is no person for a turn's author to be
         * compared against, so nothing is marked. The rule below is written to fire on the marker
         * rather than to assert that one is there, which is what keeps it inert on those requests
         * instead of instructing the model about material it was not shown.
         *
         * **So the fact is marked on the material and the rule is written against the marker**,
         * which is the move the date standing and the direction marker already make here: a rule
         * resting on the model telling the subject apart from another participant by name is
         * unanswerable on this corpus, for the reasons
         * [io.github.ragnorrs.mainbrain.domain.answer.Turn.authorIsAnotherParty] gives, and the comparison
         * belongs where that field makes it.
         *
         * **What the marker cannot say is whether the matter concerns the subject, and the rule is
         * written to that bound.** Nothing on any row records whose business a turn is about; a
         * third party granting the subject something is that party's action and the subject's
         * standing both. So the marker reports the one fact it can resolve and the instruction asks
         * for the judgement, rather than the instruction asserting what the marker would have had
         * to establish.
         *
         * **The paragraph after it guards the opposite failure, and it is the half a prohibition
         * would have lost.** A subject with little material already produces the reply hardest to
         * read correctly, because an answer with nothing in it is indistinguishable from a broken
         * answer path — so a rule that only forbade reporting another participant's business would
         * trade a noisy reply for that one. Nothing is dropped from retrieval for the same reason:
         * the material still arrives in full and what moves is where the answer may put it.
         *
         * **Its bound against the opposite excess was measured rather than reasoned, and that is
         * why it names what passes rather than what does not.** Told only not to empty the answer,
         * a live run wrote out every other participant's matter under the heading the rule had just
         * created — obeying it, and leaving a reader to find three items of the subject's among
         * twenty. So the ask names what earns a place, and the summary it allows instead is bounded
         * and carries its citations like any other assertion. `StatusShapeTest` carries the pins;
         * the run is in the pull request.
         *
         * **A question the material answers is reported answered, and that is a different rule
         * from the closure one beside it.** A closure is a marked message saying a promise was
         * done; this is a finding whose own content is something to be worked out, settled by a
         * later turn that nothing marks, because a finding is a reading of one message and knows
         * nothing of what followed. Stated here rather than left to the closure rule, which would
         * never fire: no closure is recorded for such an item, so it is labelled as having no
         * recorded outcome and a reader is sent to chase what the thread already resolved. It
         * also stays clear of the shared rule about what an undertaking may be called — that one
         * lets a question a source itself calls open keep its source's word, and this asks for
         * the answer where the record holds one rather than for a state.
         *
         * **What none of this reaches is a claim that was already widened before it was stored.**
         * A commitment bounded to one context and restated without its qualifier arrives here as
         * a general promise, and no rule about attribution recovers the bound. The turn it was read
         * out of is now in the same block whichever way retrieval reached it — see `Lookup.following`
         * — but that turn is the one the claim was written from, and a qualifier dropped in the
         * writing was dropped from what it says: what would carry it is the turns *before* it, which
         * this reads none of, and which are frequently older than the tail. That is the
         * restatement's seat and it is fixed there — see `Extractor.REWRITE_SYSTEM`.
         *
         * **Leading with the outcome was obeyed, and the sentence budget was what was missing.** A
         * read of the rendered reply found the lead packing a restoration, a named service, the
         * artefacts it covered, a collection mechanism and a reconciliation figure into a single
         * long sentence — every fact true, cited and in the right order, and unreadable. The
         * reply it was read off is in the pull request, which is where a measurement belongs. So
         * what is asked for is the first sentence rather than the first line: the outcome alone,
         * with what it rests on following. How many facts is not stated as a limit to obey but as
         * what goes wrong, because a limit invites a reply that splits one sentence and carries on.
         *
         * **A count is asked for after the list or not at all, which is the only one of the ways
         * out that is answerable.** The same read found a framing announcing that one thing needed
         * attention above a list that held more, several of them independently actionable. Whether
         * they are facets of one situation is arguable and is exactly what cannot be settled before
         * the list exists, so requiring the number to be right would be requiring the model to know
         * it in advance; forbidding a count outright loses a framing that is useful when it is true.
         * A reader who notices one miscount discounts the next count, which is what makes this worth
         * a rule rather than leaving it to the group-label rule above — that one governs what a
         * label claims of items under it, and says nothing about a number in a sentence.
         *
         * **Today is named as today because the request now carries it.** The same finding
         * concluded that everything turned on a weekday, correctly, and the weekday was the day
         * the reply was composed — which the reply did not say, and could not have said: nothing
         * in the prompt stated the present moment, so the one date a reader needs placed was the
         * only one the model had no operand for. `Assemble` prints it where a shape asks, and this
         * is one of the two that ask — the fallback is the other, which states it so that a date
         * resolved out of old material can be read against one. The other half, stating what a
         * conclusion rests on, is a rule
         * about the sentence and is beside it rather than apart from it, because the measured
         * failure was one sentence with both faults in it.
         *
         * **How a hedged finding should read is not stated in here, and deliberately.** The leak
         * reproduced in the call-preparation shape in the same words, so it is a marker every
         * shape renders rather than this instruction's fault — see `Material`, where the marker is
         * worded, and `Reply.format`, which says the reading itself is not reported. A copy of
         * that rule here would be the second place to keep it in step.
         *
         * **What is in here is the older and narrower caution — that such a finding is not
         * settled — and it was reworded with the marker rather than left behind.** It names what
         * the material says in the material's own words, so the paragraph below and the one in
         * the call-preparation shape move when the label does. A prohibition whose subject the
         * prompt no longer marks is a rule about nothing, and nothing mechanical reads either
         * sentence.
         *
         * The sentence admitting a list and refusing a narrative is kept, because comments outside
         * this shape lean on it to explain why a reply carrying no prose at all is still an answer
         * — see `Reply.read`, `Answer` and `Runner`, none of which quotes it word for word, so what
         * has to survive is the permission rather than the wording. What is added beside it is the
         * level the all-bullets complaint is really about: a line that is a whole sentence carries
         * the because and the which-means that a fragment drops.
         */
        val INSTRUCTION = """
            You report where things stand with a subject. What this answers is whether anything
            needs doing and what, so that somebody can act on it without opening the conversation.

            Open with that: the one or two lines saying where things stand and what most needs
            attention, or that nothing does. Write the first of them as one short sentence a reader
            takes in once, carrying the outcome and nothing else. What that outcome rests on —
            where it happened, what it covered, what it came to — follows in sentences of its own.
            A sentence packed with several facts is the one most likely to be quoted and the least
            likely to be read. Then what was undertaken and what to do next, which is
            the part somebody acts on. Then what happened recently and what is coming. A reader who
            stops after the opening has to come away knowing whether anything is waiting on them.
            Those opening lines are assertions about the material like any other and carry their
            citations the same way. Where the answer has sections, they come before the first one
            and speak for the whole of it.

            What opens the answer says what the answer is. An absence the whole of the material
            shares belongs there and nowhere else, said once, as part of saying where things
            stand — what it may not do is stand in for that. An opening whose every clause reads
            off the labels further down, that nothing is recorded as overdue and the agreements
            carry no dates and no recorded outcomes and this is when they were last discussed, has
            reported the bookkeeping and not where anything stands.

            Report each finding once. One undertaking does not appear under two headings: a reader
            counting what is here counts it twice, and a reader who stops at the first screen is
            left with whichever of the two copies said less. Where a finding bears on more than
            one section, put it under the one it belongs to and leave it out of the other. Naming
            in the opening what most needs attention is not a second copy — that is the opening
            doing its work, and the item is still written out once, in full, where it belongs.

            A recap of the conversation is not a status. A line reporting that somebody raised a
            question, explained a position or sent something over says what happened and not where
            the thing now stands — so do not assemble the answer out of those. Where a message is
            what moved something, say what it moved and cite it, rather than reporting that it was
            sent.

            The material includes inferred commitments and inferred events. Those are readings this
            system made of a message, not facts a source stated. Treat them as evidence of what was
            said, cite the message behind each one, and never present one marked as not stated
            firmly or not first hand as though it were settled.

            A message is marked with who sent it, as "Sent by". The subject's threads include rooms
            other people write in, so attribute a message to the person marked on it and to nobody
            else. A message carrying no such mark was written by somebody the corpus does not name:
            say it was said without saying who by, and never attribute it to the subject or to
            anyone else in the material. This is about messages only — an inferred item carries its
            parties as markers of their own, which the next paragraph is about.

            Which party a commitment places the thing on is marked on it as "obliges", and where it
            carries no such marker it is in the claim's own words and not in the party beside it.
            The party marked as "stated by" is whoever stated the thing, which is often somebody
            else: an obligation one person states about another is recorded as a commitment, and the
            claim says whose it is. So read which party the thing is on from "obliges" or from the
            claim, say who stated it from "stated by", and never swap the two.

            A message said by somebody who is "neither the subject nor the reader" is marked with
            those words. The mark is about who wrote it and not about what it says: the subject may
            well be a party to what another participant wrote, and such a message is frequently what
            moved something. What it tells you is that the matter may belong to somebody else. So for
            any finding resting on a marked message, settle what it has to do with the subject. Where
            the subject is a party to it — it places something on them, it is addressed to them, it
            changes what they are to do — report it as you would any other finding. Where they were
            merely present while other people settled a matter of their own, it is not where things
            stand with the subject: report it under what happened recently, say whose matter it is,
            and keep it out of the opening and out of what was undertaken and what to do next.

            That is not a reason to leave material out, and it is not licence to inventory the room
            either. What earns a place is a marked message bearing on the subject's own items — it
            explains a delay of theirs, it moved something of theirs, it answers a question of
            theirs. The rest of the thread is reported in a line or two saying what those matters
            were about, without writing each one out: a reader asking where things stand with one
            person does not need every other participant's week. Such a line is an assertion about
            the material like any other and carries its citations the same way. Both ways of getting
            this wrong cost the reader the answer — stripped to the subject's own words a reply reads
            as a system that found nothing about them, and expanded into the whole thread it reads as
            one that did not understand the question.

            "Sent by" and "stated by" name who wrote or stated something, and neither names who
            did a piece of work. "Obliges" is the exception and names who is to carry an
            undertaking out. What nothing marks at all is who did work a message reports as
            already done. A message reporting work — a status, a summary, a handover — recounts
            what several people did, and whoever wrote it is frequently not the one who did any
            of it; a finding carries the mark of the message it was read out of, which is not
            always the participant whose words it restates. So for work reported as done, take
            the actor from what the sentence itself says, and where it names nobody, report the
            work without saying who did it rather than crediting it to the sender. Where you draw
            one finding from several messages, each part of it keeps the participant its own
            message named: an episode two people worked on does not become one person's work
            because you wrote it as one sentence.

            Name the party each undertaking you report is placed on. An item the record places on
            nobody and gives no date is not a next action: say what was undertaken and that the
            record places neither a party nor a time against it, rather than writing it as something
            for somebody to do.

            A commitment says what is recorded about its outcome, and there are two answers. One is
            that a later message reports the thing as done: say it was reported as done and cite
            that message. It is in the material, marked as a closing message and carrying the same
            time the commitment names, which is how you tell which turn closed which promise. The
            other answer is that the outcome is not recorded.

            Do not infer from an item appearing here that anything remains to be done. What put it
            in front of you is retrieval, not a reading of where it stands: the ones reported as
            done are in this list too, and say so.

            A finding may report something as still to be worked out — who did a thing, which
            option to take, what a figure is. Where a later message in the material settles it,
            report what was settled and cite that message, rather than repeating the question. A
            finding is a reading of one message and knows nothing of what came after it, so the
            thread is where the answer is; relaying the question sends a reader to do work the
            record shows is already done. This is not the closing message a commitment carries —
            that is marked, and this is not — and it leaves what the record holds about an
            outcome exactly as the two answers above put it. Settled means a later message says
            so; where you cannot tell whether one does, report what was said and leave it there,
            as you do where nothing settles it at all.

            A commitment whose outcome is not recorded also says where its due date stands: now
            past and by how far, today, ahead, or that no date was stated. Write them in that
            order — the ones now past first and the furthest past of those before the rest, then
            today, then ahead, then the ones with no date, and anything reported as done last of
            all. That is what somebody asking where things stand is asking for, and the standing is
            marked on each item, so read it from there rather than from where the item sits. A
            commitment reported as done says its date too but carries no such standing, because it
            is finished whenever it was due.

            A label you put over a group of them has to be true of everything under it. A group
            holding something still ahead, or something with no date at all, is not a group of
            overdue things — so either name what the group really holds or give each standing its
            own label. A reader skimming takes the label for the items.

            Do not announce a number and then list a different one. A line saying that one thing
            needs attention above a list that holds more costs the reader the count and the framing
            with it, and whether several things are facets of one situation is not something to
            settle before the list is written. So either name no number and let the items stand, or
            write them out first and then say how many are there.

            The material can hold two readings of one undertaking whose outcomes do not agree: one
            carrying a message that reported the thing as done, the other saying the outcome is not
            recorded. Each reading was restated on its own, so the two are often worded differently
            and sometimes written in different languages. Where you can see that two items are the
            same undertaking, report it once, as reported as done, and cite both the closing message
            and the reading that carries no outcome. Report it where the ones reported as done go.

            A closing message found against one reading is a message the other reading was never
            read against, so it is evidence the other reading missed rather than a second opinion
            about the same thing. That is why one line reporting it as done is the answer and not a
            line saying the record disagrees with itself: there is nothing for a reader to
            adjudicate, and a reader told in one place that a thing is settled and in another that
            nothing records it cannot tell which half to act on, and stops trusting the rest of the
            answer. Where the items carry parties, two readings of one undertaking name the same
            party, which corroborates a pair you can already see — it is not a condition, since most
            items carry no party at all.

            That is about one undertaking read twice and not about two that resemble each other. Two
            different things undertaken by one party stay two items with their own outcomes, however
            alike they read — a schedule agreed for one month and a schedule agreed for the next are
            two undertakings, not one restated. Where you cannot tell which of the two you are
            looking at, report them separately: a reader shown two items can compare them, where a
            pair wrongly joined reports an undertaking as done that nothing records as done, and
            loses it.

            A commitment whose outcome is not recorded also says how long ago it was last stated.
            Report that beside it: an undertaking given three weeks ago with nothing since is a
            different thing from one given yesterday, and without it the two read alike. Where
            something is past its date, how far past it is and how long it has gone unmentioned are
            two facts, and both are worth a reader's attention.

            An item marked as stating no date was mentioned with no time attached. Report it as
            something with no date rather than placing it by when it was mentioned, and do not
            treat it as recent, imminent or over.

            Say once what the record does not hold, rather than once for every item. Where several
            items state no date, or several have no recorded outcome, say it for the group and name
            the items it covers. Each of those is worth saying and worth saying once: repeated line
            by line, what the reader learns is mostly that this system does not know.

            Once means once in the whole answer, and the opening is where it goes. Never open a
            section with a line summarising the items beneath it — neither the labels they all
            carry nor how many of them there are. A line reading "three undertakings here, none
            with a date and none with a recorded outcome" is that summary: a reader meets all
            three facts again in the next three items, and the material it cites belongs to those
            items rather than to it. A heading naming what its section is about is not that line,
            and neither is a standing written on an item of its own. Beneath a heading the items
            speak for themselves. Where the opening says a group of items shares an absence, naming
            which items those are is part of saying it once and is not a second copy of them: a
            shared absence stated over items a reader cannot pick out tells them something about a
            set they cannot see.

            A turn marked as the asker's own was written by whoever is reading the answer. Where an
            item is theirs, say so as part of the item: what is waiting on the reader is what the
            question asks for as often as it asks what the state of the work is. Write about them in
            the second person — you, yours — and keep the names for the other parties: an answer that
            names the person reading it alongside the people it is about reads as a report somebody
            else wrote, and the reader has to work out which of the names is theirs. Where no turn
            carries that mark, the material does not say who is asking — then name the parties the
            record names and say nothing about which of them is reading.

            The material also includes attributes, each carrying the period it held over. State an
            attribute with that period. A period with no end means the fact has not been
            contradicted since it was stated, which is not the same as being true today, so do not
            report it as the present. Where two periods give the same attribute different values,
            the fact changed: say so and give both with their periods, rather than choosing one.

            A conclusion you draw states what it rests on in the same breath. Where everything
            turns on one day, say what makes that day the one — the call it is the day before, the
            date something falls due — rather than naming it and leaving the reader to work out
            why. The present moment is printed at the head of the request, after Now, with its
            weekday. A date or a weekday you name that is the one printed there is today: say so,
            rather than leaving a reader to check a calendar to find out whether it has passed.

            Answer in the language of the question. Where the question carries no language of its
            own — a command and a name, which is the ordinary case here — answer in the language of
            the material. The material is often in another language; a name
            or a phrase you quote keeps the form the source gave it. For a person the material names, that form
            is the one it gives for them, and not the way a message happens to address them.

            Be brief and specific; this is read at the start of a day rather than studied. A list
            of cited items, dated where the material dates them, is the answer; a narrative is not.
            Write each item as a whole sentence that says what follows from it, not as a fragment:
            the words that carry the reasoning — because, unless, which means — are the ones a
            fragment drops, and a reader left to reassemble them is doing the work again.
            If the material does not support a part of the question, say that part is unsupported
            rather than filling it in.
        """.trimIndent()
    }
}

/**
 * The fixed query set: what was promised, what is near, what was mentioned with no time attached,
 * what holds about the subject, the recent stretch of the thread, and then the reads that qualify
 * those — which promise was discharged, what each finding's party is called, and who wrote each
 * turn.
 *
 * **Every read here is a fixed query with no model in the loop, which is what makes the cost
 * predictable and fixtures deterministic.** The question does not choose the queries — the shape does
 * — so two runs of the same request read the same material. Stated without a count, because a count
 * here is a figure that goes stale the next time a kind of material is added and nothing checks it.
 *
 * **The inbox takes the unscoped form of the same reads.** Nothing narrows it, so the entity reads
 * go corpus-wide and the tail read does not happen at all: there is no thread, and reading every
 * conversation's tail would be a prompt nobody can afford for an answer nobody asked for. The turns
 * around each undischarged promise are still read, since those are reached through the finding
 * rather than through a thread — so messages reach that answer, by that route and through a closing
 * turn, and not by the tail.
 *
 * **One read here is not a fixed query over the store at all**, and it is the last of them: the
 * turns around each promise come from the index, keyed by the span the finding carries. It is still
 * no model in the loop and still decided by the shape rather than by the question, so what makes
 * the set affordable and fixtures deterministic holds for it too.
 */
private class RetrieveStatus(
    private val retrieval: Retrieval,
    // The index, read by the span a commitment was read out of rather than by the question. What it
    // buys is the one fact a claim cannot carry — whether the thing was undertaken or merely put to
    // somebody — and the turns in which the other party answered. See `Lookup.following`.
    private val lookup: Lookup,
    private val clock: Clock,
    // The same zone the assembly heads its blocks in, because the times this renders into a block's
    // text sit a line under those headings — see `Material.of`. It also decides the standing each
    // commitment is labelled and ordered by, so the argument above for one source of `now` holds
    // for the zone in the same words: two of them would label an item by a day its own heading
    // contradicts. Both come from `shapes`, which is where that is stated once for all five.
    private val zone: ZoneId,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        val subject = exchange.subject ?: return Progress.Carried(exchange)

        // One reading of the clock for every horizon, so the two windows really do meet at the same
        // instant rather than straddling a tick. They meet inclusively — now is the forward window's
        // lower bound and the backward one's upper, and both are closed, so an event dated exactly now
        // is returned by each — and `Material.gathered` collapses the pair, since it is the same span
        // twice.
        val now = clock.instant()

        // Conversations first: they are what narrows the entity reads for a subject that is not a
        // person, so the reach cannot be decided before they are in hand.
        val conversations = retrieval.conversationsOf(subject, THREADS)
        val reach = subject.reach(conversations)

        val recent = retrieval.recentMessages(conversations, MESSAGES)
        val promised = retrieval.commitmentsOf(reach, COMMITMENTS)
        val ahead = retrieval.eventsAhead(reach, Horizon(from = now, until = now.plus(SOON)), AHEAD)
        val behind = retrieval.eventsPast(reach, Horizon(from = now.minus(RECENTLY), until = now), BEHIND)

        // Outside both windows, because an event with no moment satisfies neither bound and is
        // reachable through nothing else. Read here rather than left out: it is a recognised finding
        // with evidence, and the alternative is a row that exists for no reader. What stops it being
        // answered as having happened recently is `Material.of`, which says the date is absent
        // rather than omitting it.
        val undated = retrieval.eventsUndated(reach, UNDATED)

        // Unfiltered by the clock, unlike the windowed event reads, and that is deliberate: the window
        // travels into the material so the model can say as of when, because a value taken from the
        // wrong stretch of time cites correctly and passes the grounding check.
        val held = retrieval.statementsOf(reach, ATTRIBUTES)

        // What the corpus records as having discharged any of the commitments, with the turn that
        // records it. **One read after the commitments, like the parties below**, and for the same
        // reason: which closures to ask about is not known until the findings are in hand.
        //
        // A commitment absent from this is one nothing records as discharged, which the rendering
        // says out loud and which is **not** a claim that it is still owed — a promise kept in
        // silence produces no closing message at all. `Retrieval.closuresOf` is where that is
        // stated.
        val closed = retrieval.closuresOf(promised.map { it.id }).associateBy { it.commitment }

        // **What is owed, in the order somebody asking where things stand is asking for**, which
        // the store cannot return: it orders by recency of evidence, and what fell due last week is
        // frequently what was agreed months ago. Sorted after the closure read rather than before
        // it, because whether a promise is discharged is half of where it stands — and because that
        // read's argument is then the order the store gave, which is what it asked about.
        val stated = Material.owedFirst(promised, closed, now, zone)

        // **The turn each promise was read out of, and the few that follow it.** A claim is one
        // sentence restated to stand on its own, so a thing one party put to another arrives here as
        // a flat assertion that it will happen — and a question about what was agreed is then
        // answered with an arrangement nobody made. The speech act is in the turn, and whether the
        // other party took it up or turned it down is in the turns after it; neither is on the row.
        //
        // **Read for the promises nothing closed, which is the bound that keeps this affordable.** A
        // commitment the corpus records as discharged already carries its closing turn, so widening
        // it would buy a second account of a question already answered. Asked after the closure read
        // for that reason, and after the commitments for the reason every read here is.
        //
        // **A record this stage already read is kept as the copy it read, not as the index's.**
        // `Material.distinct` is where that is reasoned: two readings of one message are not two
        // facts, and the live row is the better copy, because the index holds whatever the last
        // pass wrote. Offering both would put a stale body and the current one under one
        // identifier the moment a message is edited before the next pass — and `Material.gathered`
        // joins texts that differ rather than choosing, since for a record and a finding read out
        // of it that is the right answer. So the overlap is dropped here, where the two copies can
        // still be told apart.
        val read = (recent.map { it.externalId } + closed.values.map { it.by.externalId }).toSet()
        val around = lookup.following(
            stated.filter { it.id !in closed }
                // **Both spans the evidence names, which are one span for most findings.** The
                // first is where the thing was put, which is what says whether it was put at all;
                // the last is where it was said again, which is where the answer to a restated
                // proposal is. `firstStated` is defended on the ground that a restatement is
                // reached through its own message, which the answer path retrieves on its own
                // account — true of the tail read and false of a request that has none, which is
                // the request this card was measured on. Asking for both costs nothing where a
                // finding carries one span, and the read folds the pair where they differ.
                .flatMap { listOf(it.evidence.firstStated.span, it.evidence.lastStated.span) },
            after = FOLLOWING,
        ).filterNot { it.id.record in read }

        // One read for every party across every kind, after the findings are in hand: the parties
        // are not known before them, and asking per kind would be a round trip each for the
        // overlapping handful of people a subject's findings concern. An attribute's party is its
        // subject, because only a fact stated about its own author is stored.
        //
        // **The obliged party is asked for in the same read and not in one of its own**, which is
        // what keeps it free: it is a contact identifier like the rest, it resolves through the same
        // merge, and for a first-hand promise it is frequently the person who stated it, so a second
        // read would ask about names this one already has.
        val parties = retrieval.namesOf(
            (
                stated.mapNotNull { it.statedBy } +
                    stated.mapNotNull { it.obligedParty } +
                    ahead.mapNotNull { it.statedBy } +
                    behind.mapNotNull { it.statedBy } +
                    undated.mapNotNull { it.statedBy } +
                    held.map { it.subject }
                ).distinct(),
        )

        // And who wrote each turn, in one read for the handful of people a few threads reach. A
        // thread the subject merely writes in carries other participants' words, so a turn offered
        // without its author is read as the subject's own — which cites correctly and passes the
        // grounding gate, because that gate asks whether a span was retrieved and not whose it was.
        // The closing turns are in it for the same reason the recent ones are: it is a message.
        //
        // **The subject's contacts travel with it**, because naming the author is half the fact
        // and the other half is whose turn it is. Most of a subject's threads are rooms, so most of
        // this tail is other participants' matters, and a name alone does not settle it — see
        // `Turn.authorIsAnotherParty`, which carries why the comparison cannot be made on names.
        // Passing nothing here marks nothing, which reads as a corpus in which every turn is the
        // subject's.
        //
        // **The widened turns are in it for the same reason, and for them it is the whole point.** A
        // promise widened out of a thread the subject merely writes in brings that thread's other
        // participants with it, and the turn that answers a proposal is frequently one of theirs —
        // so a turn offered here without its author would be read as the subject's own refusal.
        val turns = retrieval.turnsOf(
            (recent.map { it.externalId } + closed.values.map { it.by.externalId } + around.map { it.id.record })
                .distinct(),
            subject.contacts(),
        )

        return Progress.Carried(
            exchange.copy(
                retrieved = turnsAndFindings(
                    recent = recent,
                    around = around,
                    turns = turns,
                    closed = closed,
                    stated = stated,
                    parties = parties,
                    now = now,
                    ahead = ahead,
                    behind = behind,
                    undated = undated,
                    held = held,
                ),
            ),
        )
    }

    /**
     * Everything retrieved, as the spans the prompt and the grounding check both read.
     *
     * Its own function because the reads above and the rendering below answer different questions —
     * what to fetch and how to offer it — and because the whole of it has to be one expression for
     * `gathered` to fold a record reached twice.
     *
     * **Messages first, because a commitment shares its message's identifier and the two are
     * gathered into one block**: the turn should read before what was inferred from it.
     */
    @Suppress("LongParameterList") // One parameter per read above, which is what assembling them is.
    private fun turnsAndFindings(
        recent: List<Message>,
        around: List<Span>,
        turns: Map<ExternalId, Turn>,
        closed: Map<EntityId, Closed>,
        stated: List<Commitment>,
        parties: Map<EntityId, String>,
        now: Instant,
        ahead: List<Event>,
        behind: List<Event>,
        undated: List<Event>,
        held: List<Statement>,
    ): List<Span> = Material.gathered(
        recent.mapNotNull { message ->
            val turn = turns[message.externalId]
            Material.of(
                message,
                turn?.author,
                // Marked only where the read settled the question and the answer was yes.
                // Everything it could not settle goes unmarked, which is the conservative direction
                // here: marking a turn nothing places would assert an author the record does not
                // have, and the instruction already tells the model to report such a turn without
                // attributing it to anybody.
                anotherParty = turn?.authorIsAnotherParty == true,
                // **The asker's own turns are marked now, which reverses what stood here.** The
                // reasoning was that they are the other half of the relationship and so part of
                // what a question about where things stand asks about — true, and it answers only
                // half the question. Asked what is happening *and what is expected of me*, the
                // shape had no way to know which party was asking: it listed the obligations by
                // party and said outright that the material did not say who wanted to know. Read
                // off a real reply. The mark is what makes the second half answerable, and the
                // first half is unaffected — a turn marked as the asker's is still a turn in the
                // relationship.
                own = asker(turn?.authorIsSelf),
            )
        } +
            // The widened turns, rendered through the same call a tail turn is, so the clause
            // naming the author cannot drift into two wordings for one turn. The *body* can drift —
            // the tail reads the record and this reads what the last indexing pass wrote — which is
            // why a record this request already read is not among these at all; see where `around`
            // is built. What reaches here is the turns the request had no other route to.
            around.map { span ->
                val turn = turns[span.id.record]
                Material.authored(
                    span,
                    turn?.author,
                    // Asked here for the reason it is asked of a tail turn, and for a sharper one:
                    // the turn that answers a proposal is frequently written by somebody who is
                    // neither, and an unmarked one reads as the subject's own answer.
                    anotherParty = turn?.authorIsAnotherParty == true,
                    // **Unmarked as the asker's, unlike a tail turn, and the reason is the call
                    // rather than the question.** This path takes a span already built, and the
                    // mark is composed where a turn is — so marking here means widening a signature
                    // four shapes share for a turn that is a ranked extra rather than part of the
                    // thread being reported. What a question about the asker's own obligations
                    // leans on is the tail and the closures, and both carry it.
                )
            } +
            // The closing turns, beside the recent ones and for the same reason: a closure is
            // evidence, and an answer saying a promise was reported as done has to be able to point
            // at the turn reporting it. A turn already in the tail arrives twice and `gathered`
            // folds the pair.
            closed.values.mapNotNull {
                val turn = turns[it.by.externalId]
                Material.closing(
                    it,
                    turn?.author,
                    zone,
                    // The same question as for a recent turn, and it has to be asked here too: a
                    // promise is frequently reported done by somebody other than the party who gave
                    // it. A closing turn already in the tail is rendered twice under one
                    // identifier, so an unasked mark here would put one record in front of the
                    // model marked and unmarked at once.
                    anotherParty = turn?.authorIsAnotherParty == true,
                    // And the asker's, for the same reason as the tail turn's: a promise the asker
                    // reported done themselves reads differently from one the other side reported.
                    own = asker(turn?.authorIsSelf),
                )
            } +
            // Direction and age, which this shape asks for and the drafting one does not: a
            // question about where things stand is a question about who owes what and how long it
            // has been waiting. The call-preparation shape asks for both too, on the same
            // reasoning — see `Material.of`, which carries why they are asked for rather than
            // given.
            stated.map {
                Material.of(
                    it,
                    it.statedBy?.let(parties::get),
                    closed[it.id],
                    now,
                    zone,
                    owed = it.obligedParty?.let(parties::get),
                    ages = true,
                )
            } +
            ahead.map { Material.of(it, it.statedBy?.let(parties::get), zone) } +
            behind.map { Material.of(it, it.statedBy?.let(parties::get), zone) } +
            undated.map { Material.of(it, it.statedBy?.let(parties::get), zone) } +
            held.map { Material.of(it, parties[it.subject]) },
    )

    private companion object {
        /**
         * The asker's mark, where the read settled that the turn is theirs.
         *
         * **Only on a settled yes**, as every other mark here is: the port collapses *not theirs*
         * and *nothing is known* into one false, so an unmarked turn means the question could not
         * be answered rather than that somebody else wrote it. Marking on anything weaker would
         * attribute the asker's own words to them on a guess, and what that costs is the opposite
         * of what the mark is for — an answer confidently telling somebody what is waiting on them
         * from a turn that was never theirs.
         */
        fun asker(isSelf: Boolean?) = Material.OwnTurn.ASKER.takeIf { isSelf == true }

        /** Conversations followed per contact. A subject's recent threads, not its whole history. */
        const val THREADS = 3

        /** Messages read from the tail of each conversation. */
        const val MESSAGES = 40

        const val COMMITMENTS = 30

        /**
         * Turns read after the one each promise was read out of.
         *
         * **Enough to reach the other party and not enough to quote the thread back.** What
         * settles a proposal is the other side answering it, so the figure is set from how many
         * turns pass before they do — measured on the archive as a distribution rather than taken
         * from the one refusal this card was filed on, and sized at the knee of it, where a further
         * turn buys about half what this one does. It is not sized to reach every answer: past the
         * knee the window grows faster than the coverage, and a proposal answered much later is
         * missed rather than paid for on every promise. The pull request carries the curve.
         *
         * **What bounds the cost is the count of promises, not this.** Up to [COMMITMENTS] of them,
         * each widened by this many, and two promises close together in one thread widen over the
         * same turns, which are offered once.
         */
        const val FOLLOWING = 3

        /**
         * Attributes carried, as windows rather than as facts.
         *
         * The smallest of the bounds: the predicate vocabulary is a closed list of a few entries, so
         * a subject's attributes are a handful of values plus whatever corrections reopened them. A
         * larger figure would spend the prompt on the same few facts restated. Stated as the shape
         * of the list rather than as its length, which is held in one place and may move.
         */
        const val ATTRIBUTES = 12

        /** Upcoming events carried. The larger half: what is next is what the question asks for. */
        const val AHEAD = 12

        /** Events already past carried. Fewer, and under their own bound so the two cannot starve. */
        const val BEHIND = 8

        /**
         * Events carried that state no moment at all.
         *
         * The smallest of the event bounds, because this is the weakest material of the three: it
         * says something was mentioned as happening and cannot say when. Enough that a finding is
         * reachable rather than stored for nobody, and not enough to spend a prompt that has two
         * dated windows to fit on mentions that cannot be placed.
         */
        const val UNDATED = 4

        /**
         * How far back a dated thing still bears on where a subject stands.
         *
         * Generous, because something agreed three months ago is exactly what goes unnoticed, and
         * surfacing it is what the answer is for rather than letting it age out. It bounds the past
         * event read only — commitments carry no horizon, because a commitment has no date of its own
         * and ordering them by when they were evidenced is already a recency bound.
         */
        val RECENTLY: Duration = Duration.ofDays(90)

        /** How far ahead counts as next. Shorter than [RECENTLY]: what is coming is a shorter list. */
        val SOON: Duration = Duration.ofDays(30)
    }
}
