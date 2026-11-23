package io.github.ragnorrs.mainbrain.domain.answer.shape.freeform

import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Generate
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.answer.Stage
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import java.time.Clock
import java.time.ZoneId

/**
 * Whatever did not match a shape.
 *
 * **A pipeline like the others, registering the same way.** What makes it the fallback is that the
 * registry names it separately, not that it is built differently — which is the property that keeps
 * "anything unclassified is answered" from being a special case in the runner.
 *
 * It takes a free-text question, finds whatever the corpus ranks highest for it, and answers from
 * that. The four named shapes are better at their own questions because their retrieval is chosen
 * for them; this one has to work from a ranking, which is why it has no fixtures and why it is where
 * a request goes rather than where one is sent.
 *
 * **Its retrieval is a ranked lookup rather than a loop, and that is less than the design asks for.**
 * An agentic fallback would let the model search, read, and search again — deciding for itself what
 * to look for next — and that needs a model call that can be handed a tool and resumed with its
 * result. [ModelProvider] offers one tool, the provider's own web search, which runs on the
 * provider's side; there is no entry point for a tool this system implements, so the corpus cannot be
 * put inside the loop. A single ranked read is what the port supports, and widening the port is a
 * change to the model seam rather than to a shape.
 *
 * **It states the present, which the shape reporting where things stand also does.** A ranking is the only thing
 * between the question and the whole corpus, so what one reply carries can be months apart in date —
 * and a date correctly worked out of an old message, set beside a recent one with nothing saying how
 * far back it is, reads as arithmetic this build got wrong. Placing it needs the present as an
 * operand, and without [clock] there is no line in the prompt stating one.
 *
 * @param clock what the present moment is read from, which the instruction below reads a reported
 *   date against. Required rather than defaulted, for the reason `Assemble` gives for taking none: a
 *   shape whose instruction mentions the present and was handed no clock states a rule about a line
 *   that is not there, and the omission would be silent.
 */
// Seven parameters is the count detekt refuses, and `Assemble` carries the same suppression for the
// same reason: each one is a port or a fact about this shape's own material, and collapsing the
// zone and the clock into a time object would hide two different questions — which calendar the
// material is dated on, and when now is — behind one type.
@Suppress("LongParameterList")
class FreeformShape(
    model: ModelProvider,
    lookup: Lookup,
    retrieval: Retrieval,
    grouping: Grouping,
    zone: ZoneId,
    clock: Clock,
    sizing: Sizing = Sizing.ANSWERING,
) : Shape {

    override val id = ShapeId("ask")

    /**
     * What this shape answers — declared, and read by nothing.
     *
     * The registry keeps the fallback out of the candidate set, so no router is ever shown this
     * line: a request arrives here by not having been placed elsewhere, never by being chosen. It
     * is stated because the contract asks every shape for one, and leaving it to a default is what
     * would let a shape that is genuinely routed to arrive without one.
     */
    override val description =
        "Anything that is not one of the others, answered from whatever the corpus ranks highest " +
            "for the question."

    /**
     * Nothing.
     *
     * Free text may name no subject at all, and a fallback that refused those would refuse the
     * requests it exists for. What the question does name is found by the lookup rather than
     * resolved, which is the difference between supplementing and scoping.
     */
    override val accepts = emptySet<SubjectKind>()

    override val variants = 1..1

    /**
     * No, and this one is a judgement rather than a consequence.
     *
     * A corpus with nothing to say about the question is the ordinary case here — somebody asks
     * something general, and refusing it because no span ranked highly would make the fallback
     * useless at exactly the questions it catches. So the model is called, and the instruction below
     * is what keeps it from inventing: it answers from the material or says it has none.
     *
     * The grounding check still holds over what it checks. A claim line about the corpus carries
     * citations and is compared against what was retrieved, so an answer composed from nothing
     * cannot offer a *claim* about material it never saw. It can write the same thing as prose,
     * which nothing compares — see [assertsFindings], which is where that is decided for a shape
     * and which this one declares out of.
     */
    override val readsCorpus = false

    /**
     * No, and for the same judgement [readsCorpus] is false for.
     *
     * This shape answers whatever did not match another, including a question that is not about
     * this organisation's material at all — and its instruction tells it to say so and answer
     * plainly. Such an answer asserts nothing about the corpus and is the right answer, so a rule
     * requiring an assertion would refuse the requests the fallback exists to catch.
     *
     * **That leaves this shape able to state something about the corpus as prose, and nothing here
     * refuses it.** Its instruction is what asks for a claim line instead, which is prose asking
     * for prose. It is the accepted residue of the declaration: the alternative refuses the
     * question this shape exists for, and which of the two costs more is a judgement the evaluation
     * set is where to settle.
     */
    override val assertsFindings = false

    override val stages: List<Stage> = listOf(
        RetrieveByRanking(lookup, retrieval),
        Assemble(INSTRUCTION, variants, grouping, zone = zone, clock = clock),
        Generate(model, sizing),
    )

    private companion object {

        /**
         * What the fallback is told, the half of its own read it has to be told about, and the
         * rules in here that were derived rather than inherited.
         *
         * **A ranked sample described only by its precision is summarised as the corpus.** The
         * ranking warning said that some of the material may be irrelevant and nothing about what
         * the ranking left behind, so a model holding what it was told is the relevant part of the
         * corpus had no reason not to report it as the corpus — and did: an answer opened by stating
         * that what followed was everything the material held about somebody, and went on to place
         * them in a single body of work. Both are statements about a whole this shape never read,
         * out of a hit list bounded by [RetrieveByRanking]'s own limit. How small a part of the
         * index that is, is a property of the corpus and is read off it rather than recorded here.
         *
         * **Worded per shape rather than seated in `Reply.format`, and `PrepareShape` is why that
         * is checkable rather than a preference.** It states the same rule for its own read — that
         * the items it lists are not everything it was given — and states it as a claim line citing
         * what it was read against, because that shape asserts its findings. Here such a sentence
         * cites nothing, for the reason [assertsFindings] gives. One wording cannot be both, so the
         * rule belongs to each shape in the terms of the read it answers from: a ranked sample here,
         * a ranked supplement to a web answer in the open-web shape, a window chosen by subject in
         * the other two.
         *
         * **The open-web shape carries it in its own words, because it was measured breaking it.**
         * Asked about a person through the interface, it reported that they appeared in the corpus
         * exactly once and were named in no other message — off its ranked supplement, about
         * somebody the store holds far more of than the supplement carried. The read is in the pull
         * request, which is where a measurement belongs. Its instruction called that supplement what
         * the organisation holds, which is the licence this one removes, so the sentence is reworded
         * there rather than shared. The two shapes that retrieve by subject are left, and what
         * remains of the general question is the absence card.
         *
         * **The model is told the bound as a standing fact and not handed a count, which is the
         * decision this card turned on.** The store's search applies no similarity floor and returns
         * the rows it was asked for, so wherever the index is larger than the read the hit list is
         * full every time, and a flag computed from it would be true on every request. A value that
         * never varies is a sentence, not a field: carried as one it would put per-request text in
         * the prompt and a reading on the exchange for something no answer could act on differently.
         * The port says nothing either way — see `IndexRepository.search` — so what holds is what
         * the store does today, and a floor added there would want this read again. **A corpus is
         * not always larger than the read**: the pinned fixture the evaluation set runs against sits
         * within this read's widening of it, so there is nothing left over to report there, and a
         * sentence asserting a remainder would be false. It is why the prose below says what the
         * corpus may hold rather than that it holds more.
         *
         * **It says what to write instead, because the prohibition alone is satisfied by hedging.**
         * Told only not to claim the whole, a model qualifies every line, and a reader who asked a
         * question gets a paragraph about the limits of the search. So the scope is stated once, in
         * the terms that are true — the material this request retrieved — and the absence case is
         * given its own wording, since that is the form the mistake takes when the quantity is zero.
         * It also says which sentences are prose: an absence and a scope cite nothing, where the
         * claim-line rule — stated here and again in the shared format — asks every assertion about
         * the material for a number it could not have.
         *
         * **Two sentences the shared format asks for are named as not being the forbidden kind**,
         * because a model told to write one and not the other picks. `Reply.sectioning` asks for
         * two labels naming one body of work to be reported as one, and `Reply.format`'s rule on
         * what a commitment's outcome may be said to be asks for an undertaking with nothing
         * recorded after it to be reported as exactly that. Both are about an item in the material
         * rather than a count of the corpus, and the prompt says so where it draws the line.
         *
         * **What the gate reads of this is the citation and not the sentence**, for the reason
         * [readsCorpus] and [assertsFindings] record. A cited claim here is checked against what was
         * retrieved; what it is never asked is whether the retrieved set was the corpus — and the
         * offending sentences carry no citation at all, so nothing compares them. A pin on the
         * wording in the shape's own test is the rail available, and it is the weak kind knowingly.
         *
         * **A finding carrying nothing is what the substance paragraph is for.** The ranking
         * sentence above it already said that some of the material may be irrelevant and to use
         * what bears on the question. That rule was obeyed against topic and not against
         * substance: a turn whose whole content was a word of approval was kept because it was
         * *about* the right person, and it cost a line of the reply and a citation beneath it. So
         * the paragraph sharpens the ranking rule rather than adding one, and it names its own
         * subject to do it: an earlier draft opened by reading *irrelevant* back off whichever
         * sentence preceded it, which held only while nothing came between them, and a sibling card
         * inserted a paragraph there within the day. A rule is named by what it does and not by
         * where it sits, so this one says what it is about and can be read from anywhere in the
         * instruction.
         *
         * **Filtering the material before the model reads it was the alternative, and the seat is
         * wrong.** The only test available that early is length, and length drops the one-word
         * answer that settles something — a rate, a name, a yes — which is often the most useful
         * turn in a thread and is the opposite error, worse than the one being fixed. The widening
         * compounds it: [RetrieveByRanking] fetches neighbours around each hit precisely so that a
         * reply arrives with the message it answered, so a filter over those would undo what the
         * widening is for. Nothing short of reading a turn tells an interjection from a
         * confirmation, and on this path the model is the only thing that reads.
         *
         * **It is several clauses where one prohibition would read more cleanly, because a
         * prohibition alone was measured and found insufficient.** The shape the defect actually
         * takes is a roll-up — several acknowledgements gathered into one finding — so the
         * prohibition names that and the count of them with it, a count being the evasion a rule
         * against *saying nothing new* invites. Naming where an accepting turn does belong is what
         * keeps the prohibition obeyable: the acceptance has somewhere legal to be said, rather
         * than nowhere, which is the reading a model resolves by reporting the turn anyway. The
         * thin-record clause is there because a draft without it sent that case into prose and the
         * account of the dropped turns came back as a finding instead — the sentence that
         * contradicted it is this instruction's own, asking that every assertion about the material
         * be a claim line, and not the shared format's, which this shape is declared out of by
         * [assertsFindings]. **That clause is the one place here where the governed statement is
         * left nowhere legal to be said, which is against the grain of every other rule in this
         * file, and it is kept on a measurement rather than on the principle.** The repair the
         * principle asks for — bar the account as a *finding* and name the thin record a place to
         * be said in — was written and run over the same material, and it scored slightly worse
         * than the absolute form rather than better. Naming the place appears to legitimise the
         * subject. The margin is a single reply either way, so what this rests on is that the
         * cheaper rule measured no worse, not that the narrowing was shown to be harmful.
         *
         * **The clause keeping a short answer closes no measured failure**, and is here because a
         * rule read as *drop what is short* would discard a confirmation. Every arm was scored for
         * that too, to establish that it does not. The figures are in the pull request, which is
         * where a measurement belongs.
         *
         * **Seated here and not in the shared format, because one shape produced it** — the same
         * criterion the date rule's own seat rests on, and not a claim that no other shape could.
         * Two others are handed turns nothing filters: the open-web shape takes a ranked read as a
         * supplement to its web answer, and the shape preparing for a call takes a conversation's
         * recent tail. Neither produced this defect and neither has been measured for it, so the
         * format's membership rule — a rule lives there where more than one instruction would
         * otherwise say it — is not yet met. What would move it is a second shape measuring the
         * same thing, and the case asserting the seat is what makes that a decision rather than a
         * drift. The shape that composes a reply is the one it must not reach: it reads a thread's
         * tail on purpose, acknowledgements included, because that is where the register is learned
         * — a rule telling it to drop them would take away what its material is for.
         *
         * **It is the broadest question the system can be asked, so it produces the reply most in
         * need of structure**, which is what the lead and the order paragraphs are for. A question
         * naming nobody returns material from every conversation the ranking reached, which the
         * model sections by body of work; the findings then arrive under a heading each in no
         * legible order with nothing above them, and a reader has no way to tell after the first
         * screen whether to read on. The read that measured it is in the pull request, which is
         * where a measurement belongs.
         *
         * **Neither fault is an accuracy fault, which is why nothing catches either.** Every finding
         * is a faithful reading of real material and correctly cited, so the grounding gate passes
         * the whole reply: what is wrong is that it cannot be read. The shape that reports where
         * things stand asks for both, and the wording here deliberately follows its — one rule said
         * two ways is two rules to keep in step — bar what has to differ: a lead here says what the
         * answer *is*, where there is no subject for it to say anything stands about.
         *
         * **The lead is asked for positively and the aggregate restatement refused in the same
         * breath**, because the gap is what the model fills: told only not to summarise, it writes
         * nothing at the top; told only to summarise, it restates what kind of material it found
         * instead of what the answer is. Both are measured faults in this build's replies.
         *
         * **The order is stated here as well as computed**, for the reason `newestFirst` gives, and
         * it is where `Reply.sectioning` sends the order within a section — that delegation names
         * these instructions in as many words, so this is the prose it points at. Recency rather
         * than the ranking's score, because a reader can follow the first and cannot see the second.
         *
         * **It is stated as an order to write in and not as a fact about the material**, which is
         * what `Material.owedFirst` and the shape preparing for a call both found: a position
         * carries the order across the stretches `newestFirst` sorts, and not inside one, where the
         * turns gathered around a hit read forwards. So the paragraph sends the model to the date on
         * each block, which is true of every block, rather than to where the block sits.
         *
         * **A date resolved correctly out of old material was read as a defect, which is what the
         * date paragraph is for.** The resolution is the shared format's and it works: a relative
         * word in a message is counted off that message's own block heading, and the date it comes
         * to is right. What was missing is the second step — that date set beside a recent one, with
         * nothing saying how far back it is, reads as arithmetic this build got wrong rather than as
         * old material. The reply had no way to say it, because nothing in the prompt stated when
         * now was. The read it came from is in the pull request, which is where a measurement
         * belongs.
         *
         * **How far, and not merely that it is past.** A date some weeks behind and one some months
         * behind are the two readings a reader has to tell apart, and it is the second that looks
         * like a miscount; a rule asking only for *past* answers a question the reader did not have.
         *
         * **The figure is asked for at a month and no finer, which is a bound and not a
         * convenience.** The gap is the model's own arithmetic over a date it has just read, and
         * this shape computes nothing to check it against — unlike `Material`, which derives its
         * figures and can hold them to a boundary. A gap counted in months survives an error of a
         * day in that arithmetic; a gap counted in days is exactly the size of one, and asking for
         * one would spend the reader's trust on a figure nothing here verifies. Below a month the
         * date itself is in front of the reader and needs no figure beside it.
         *
         * **Asked of the model rather than computed, which is the opposite of what `Material` does
         * and for a reason that does not reach here.** `Material` computes an elapsed figure
         * precisely so date arithmetic is not left to a model, and that is right wherever the build
         * holds the two instants. This shape's material holds no commitment rows and so no stored
         * standing: the date in question is one the model has just resolved out of a message, and
         * there is nothing for the build to compute a gap from. So the figure is asked for, bounded
         * to the granularity the paragraph above can defend.
         *
         * **Seated here rather than in the shared format, because one shape produced it.** The
         * format carries a rule where more than one instruction would otherwise say it. Among the
         * others, only the shape reporting where things stand is handed a present at all, and its
         * items arrive carrying a standing computed for them; the rest are handed none. So this is
         * the shape whose reply can set a resolved old date beside a recent one with nothing placing
         * either, which is both why it produced the defect and why the rule sits here.
         *
         * **It reads a date already arrived at, and does not re-open what a relative word is
         * anchored to.** `Reply.format` sends a word inside a message to the head of its own block
         * and says outright that the present is not that anchor. This rule applies after that one
         * has run: what it governs is the date reported, not the arithmetic that reached it — which
         * is why it names a calendar date and excludes a standing the build wrote into a block.
         */
        val INSTRUCTION = """
            You answer a question from the material you are given.

            Open with what the answer is: one or two lines saying what the material says about the
            question, or saying that it does not answer it. A line there saying what the material
            says carries its citations like any other assertion about it; one saying the material
            answers nothing has none to carry. Where the answer has sections, those lines come
            before the first heading and speak for the whole of it. A reader who stops after them
            has to come away knowing what the answer is — so do not open by saying what kind of
            material was found, or by restating what the items below have in common.

            Order the answer by recency, newest first. Where it has sections, the section holding the
            most recent material comes first; within a section, the most recent item comes first.
            Take that order from the date on each block rather than from where the block sits: the
            material leads with the most recent of it, but a run of neighbouring turns inside it
            reads forwards. A reader who cannot tell what orders the answer cannot tell whether to
            read on.

            The material was found by ranking the corpus against the question, which cuts both ways:
            some of what you were given may be irrelevant, and the corpus may hold more that no
            ranking put in front of you. Use what bears on the question and ignore the rest; do not
            stretch to connect something that does not fit.

            So what you were given may be a part of the corpus rather than all of it, and nothing in
            it says what the rest holds. Do not present it as exhaustive: not as everything there is
            about somebody, not as everything the corpus holds, and not as everything in the
            material, which a reader takes for the records themselves whatever you meant by it. Do
            not write that somebody appears in only one conversation, project or piece of work, and
            do not turn what you can see into a count of what the corpus holds. No number you cite
            evidences what the corpus does not hold elsewhere, so where such a sentence is true a
            reader cannot tell it from where it is not. Two things in front of you are not claims of
            that kind, and both are asked for elsewhere in these instructions: that two threads you
            were given are one piece of work, and that an undertaking you can see has no outcome
            recorded after it. Each is about an item in the material rather than about how much the
            corpus holds.

            Whether something bears on the question is a matter of substance and not only of
            topic. A turn whose whole content is an
            acknowledgement, a greeting or a bare interjection is part of the record and is not a
            finding: leave it out rather than report that it was written, and do not gather several
            of them into one finding or into a count of them. Where such a turn accepts a proposal
            or answers a question that is in the material, what was accepted is the finding and the
            turn is cited under it — the acceptance is what the reader learns, and a word of
            approval is not on its own evidence that anything was concluded. Noting a statement is
            not accepting a proposal: where the turn it follows told somebody something rather than
            asking them something, there is nothing there to report. A short turn that answers a question — a rate, a name, a
            yes — carries as much as a long one, so report it together with the question it answers;
            where the question it answers is not in the material, there is nothing for it to settle
            and it is left out with the rest. If leaving them out leaves little to say, what was
            retrieved for this question is what did not answer it, and an account of what you left
            out is no part of saying so — not that the rest were short replies, not how many there
            were, not when the last of them was.

            A message is marked with who sent it, as "Sent by". Nothing narrowed this search, so the
            material spans conversations and people who have nothing to do with each other:
            attribute a message to the person marked on it and to nobody else, and never carry a
            statement from one of them over to another. A message carrying no such mark was written
            by somebody the corpus does not name — say it was said without saying who by, and do not
            invent a name for it.

            Every assertion about the material is a claim line and cites the numbers it rests on.

            Where the material does not answer the question, say that what was retrieved for this
            question does not answer it rather than that the corpus holds nothing about it, and say
            what you did see that bears on it. Where the scope is worth stating at all, state it
            once and in those terms — the material this request retrieved. Both of those are about
            this search rather than about what the material says, so they are ordinary prose and
            carry no citation.

            Where the material does answer, the opening lines are still the answer and not the
            scope: say the scope after them, in a sentence of its own, and spend the rest of the
            reply on what was asked. Somebody who asked a question is owed what the material says,
            not a paragraph about the limits of the search.

            If the question is not about this organisation's own material at all, answer it plainly
            and say that the corpus was not what you answered from.

            The line at the head of the request beginning Now says when the present is, with its
            weekday. A calendar date you report from the material is read against it, and one a
            month or more away says so: how many months behind the present it is, or that it is
            that far ahead. A date nearer than that needs no such figure, because it is close
            enough to the present that the date speaks for itself. This governs a date on the
            calendar and not a standing this system wrote into a block, which arrives already
            placed and is used in the words it came in.

            The ranking reaches wherever it reaches, so two dates in one reply can be months apart
            — and a date months behind the present, standing unplaced beside a recent one, reads as
            a miscount. That is the one error a reader cannot tell apart from a correct old date.

            Answer in the language of the question. The material is often in another language; a name
            or a phrase you quote keeps the form the source gave it. For a person the material names, that form
            is the one it gives for them, and not the way a message happens to address them.

            Never present a guess as something the material supports. A reader who cannot tell the
            two apart cannot use either.
        """.trimIndent()
    }
}

/**
 * The corpus, ranked against the question, widened around each hit, the stretches offered newest
 * first, and each turn naming its author.
 *
 * **What the ranking decides is which stretches are in front of the model, not the order they are
 * shown in** — see [newestFirst], which is where that is argued. A position carries recency across
 * the stretches and not inside one, where the turns gathered around a hit read forwards; the
 * instruction therefore sends the model to the date on each block rather than to its position.
 *
 * **Two reads rather than one, and the second is not optional here.** A hit is a record identifier
 * and a body: the index stores no author, so a ranked read that stopped there would offer the
 * corpus's messages with nobody attached — and a question about what somebody said would be answered
 * from whatever ranked highest, whoever wrote it, with every citation resolving and the grounding
 * gate passing it clean. This shape has no subject to narrow by, so what it retrieves spans
 * conversations and participants more widely than any scoped shape's material does.
 */
private class RetrieveByRanking(
    private val lookup: Lookup,
    private val retrieval: Retrieval,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        val stretches = lookup.clustered(exchange.request.text, limit = HITS, around = AROUND)
        val ranked = Material.distinct(newestFirst(stretches))
        // Folded first, so the name read is asked about each record once however many spans of it
        // ranked. Asked after the ranking for the reason every name read on this path is: which
        // records to ask about is not known until the material is in hand.
        val turns = retrieval.turnsOf(ranked.map { it.id.record }.distinct())
        return Progress.Carried(
            exchange.copy(retrieved = ranked.map { Material.authored(it, turns[it.id.record]?.author) }),
        )
    }

    /**
     * The stretches newest first, overlapping ones joined, each keeping the order the index returned
     * it in.
     *
     * **Computed rather than only asked for, because asking alone is known not to hold.** The shape
     * reporting where things stand asked for its order in prose, was answered with a flat chronicle,
     * and sorts its material before offering it. So the order an answer is written in is established
     * twice over — by what the model is shown, and by the instruction restating it — and neither half
     * carries it alone: the instruction has to be obeyed over a list the model cannot see the seams
     * of, and prose on its own did not survive contact with the model.
     *
     * **The stretch is the unit of order, which is the whole of why [Lookup.clustered] exists.**
     * Sorting the spans would interleave several conversations into one apparent thread — the
     * failure `Lookup.widened` names — while sorting the stretches moves every hit together with the
     * context that makes it readable.
     *
     * **A stretch is dated by its newest span rather than by its hit**, because the hit is wherever
     * the ranking happened to land inside it, and what a reader orders a section by is the most
     * recent thing in it.
     *
     * **Which is why the fold runs before the sort and not after it.** Two hits close together in
     * one conversation have overlapping windows, which is ordinary — nine of twenty stretches
     * overlapped another on the real archive, and the figures are in the pull request. Folded after
     * the flatten, a stretch is dated by a span that is then dropped from it, and the blocks printed
     * at that position can all be older than the date that put them there. Folded first, each
     * stretch is dated by what it still carries: the trailing context a higher-ranked stretch
     * already took is offered at the position its own turns are from, which is where a reader
     * ordering by date would look for them. The fold keeps the earlier occurrence either way, so
     * which copy of a shared turn is offered is unchanged — only where it sits.
     *
     * **What this spends is relevance order, and that is the trade the measured reply asked for.**
     * The ranking decided which material is here at all; a score the reader cannot see is not an
     * order they can follow, so what a position carries now is recency, which they can.
     */
    private fun newestFirst(stretches: List<List<Span>>): List<Span> {
        val offered = mutableSetOf<SpanId>()
        return stretches
            .map { stretch -> stretch.filter { offered.add(it.id) } }
            // A stretch wholly inside a higher-ranked one is left with nothing, and a date read off
            // an empty one would be no date at all.
            .filter { it.isNotEmpty() }
            .sortedByDescending { stretch -> stretch.maxOf { it.sourceTime } }
            .flatten()
    }

    private companion object {
        /**
         * How many hits to take.
         *
         * More than a scoped shape needs, because nothing narrowed the search: the ranking is the
         * only thing standing between the question and the whole corpus, and a short list from a
         * broad search misses more than a long list from a narrow one.
         */
        const val HITS = 20

        /** Neighbours either side of each hit, since a message indexed alone can be a bare reply. */
        const val AROUND = 2
    }
}
