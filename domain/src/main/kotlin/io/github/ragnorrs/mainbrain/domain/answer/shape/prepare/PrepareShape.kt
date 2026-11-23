package io.github.ragnorrs.mainbrain.domain.answer.shape.prepare

import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Generate
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.answer.Stage
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.contacts
import io.github.ragnorrs.mainbrain.domain.answer.reach
import java.time.Clock
import java.time.ZoneId

/**
 * A script and the questions to ask, before a call with somebody.
 *
 * **The preparation half only, and the other half is a scope line.** The debrief reads the recording
 * of the call, recordings arrive through a later workstream, and a shape that answered half the
 * request from the corpus and half from a source that does not exist would be two shapes wearing one
 * name. When recordings land, the debrief is a shape of its own registering beside this one.
 *
 * Close to the status shape in what it reads and quite different in what it produces: the same
 * history and commitments, turned into what to say rather than into where things stand. That is why
 * it is its own shape rather than a flag on that one — the retrieval overlaps, the prompt does not,
 * and the prompt is the part that decides whether the answer is usable.
 *
 * **The length is a count in the instruction, because every other way of asking for brevity loses to
 * the rest of the prompt.** Brevity asked for as a judgement is outvoted by instructions that each
 * add material and are each obeyed — the account, the questions, a standing on every item and a rule
 * about each. A count of items and of questions is a thing the instruction can ask for and a reader
 * can check, where *short* is neither. It is over the whole brief rather than over a section,
 * because the material routinely sections by body of work and a per-section reading licenses the
 * length this bounds.
 *
 * **What a bound does to the rest of the answer is the part worth stating.** A cap reached by
 * compressing is the fault this shape is least able to afford: an item folded into a neighbour to
 * fit asserts more than the material does, and one stripped of its citations stops being checkable,
 * so both are forbidden where leaving the item out is not. A bounded account offered as though it
 * were everything retrieved is a second defect bought with the first, which is why a claim line
 * says the material carries more.
 *
 * **What the call is about is read from what was asked, and there is an arm for it being unsaid.**
 * The request names a person and this shape accepts nothing else, so a brief covering the whole
 * relationship is a defensible answer to it — which is why guessing which half of a relationship a
 * call concerns is not the fix. Where the words the reader typed name a matter beside the person,
 * that is the only statement of purpose anything here holds, and it decides the order of the brief
 * and what its questions rest on. **It reaches only as far as the interface passes those words
 * through as text**, which is the free-text route: an interface that takes everything after a
 * command word as the name to resolve sends a matter named beside the person into subject
 * resolution, where it reads as part of the name and resolves to nothing. So the arm that matters
 * for a typed command is the other one.
 *
 * Where nothing says what the call is for, the kind of each matter is what is left to go on: the
 * work two people do together is what a working call is for, and an arrangement between them as
 * people is something a reader taking one has nothing to do about. So those go last within the part
 * of the brief they fall in, seed no question, and the cut is what leaves one out — confined rather
 * than refused, because a record that holds only matters of that kind is then the whole of what the
 * call can be about, and a brief emptied of them would be no brief at all.
 *
 * **A matter the record reports as done is not an item.** The closure read makes one of the two
 * shapes of it visible, and it is read so that nothing already finished is chased; writing one up so
 * the brief can say it is finished spends one of a bounded few items on the one thing the reader has
 * nothing to do about. It stays where something the record shows no outcome for turns on it, which
 * is the reading of it that serves the call — and that wording rather than a shorter one, because
 * the shared caution forbids calling such a thing open and a prompt that asks for one state while
 * forbidding it is obeyed in whichever direction the model picks. **It is also the build's own
 * vocabulary and not a stronger word**: what a later message evidences is that a thing was reported
 * as done, which is what `Commitment` says the store may hold, so a rule written over matters the
 * record *settles* would ask the answering model for a determination nothing upstream makes.
 *
 * **What makes the cut deterministic is the standing on each item, not where the item sits.** The
 * two cannot be used interchangeably here: a commitment evidenced by a turn still inside the message
 * window shares that turn's span and is shown at its position, which is why the ordering rule sends
 * the model to the label and why the cut does too. A cut keyed on position would discard exactly the
 * folded items, and would read as deliberate.
 */
class PrepareShape(
    model: ModelProvider,
    retrieval: Retrieval,
    grouping: Grouping,
    clock: Clock,
    zone: ZoneId,
    sizing: Sizing = Sizing.ANSWERING,
) : Shape {

    override val id = ShapeId("prepare")

    /**
     * What this shape answers.
     *
     * **The upcoming call is the whole of the distinction, and the line leads with it.** This shape
     * and the one answering where things stand read nearly the same material, so a router told only
     * what each reads could not tell them apart; what differs is that this one is asked for ahead
     * of a conversation and produces what to say in it.
     */
    override val description =
        "Preparation for a call or meeting with somebody: what to raise, and the questions to ask."

    override val accepts = setOf(SubjectKind.CONTACT)

    /**
     * One.
     *
     * A script is not a thing to offer alternatives of: the preparation is for a call that is about
     * to happen, and choosing between three framings is work the person does not have time for.
     */
    override val variants = 1..1

    override val readsCorpus = true

    /**
     * Yes, for the half of the answer that is an account of the record.
     *
     * The questions to ask are composed text and carry nothing, but the account they are chosen
     * from is assertions about the corpus and the answer is unusable without it. A reply made only
     * of questions is a script invented from material it never pointed at, which is the one thing
     * somebody reading this minutes before a call cannot check.
     */
    override val assertsFindings = true

    override val stages: List<Stage> = listOf(
        RetrievePrepare(retrieval, clock, zone),
        Assemble(INSTRUCTION, variants, grouping, asserts = assertsFindings, zone = zone),
        Generate(model, sizing),
    )

    private companion object {
        /**
         * What this shape asks of the model.
         *
         * **Three rules here are the status shape's, word for word in substance, and the duplication
         * is deliberate.** A recap offered in place of where things stand, an absence restated on
         * every item, and an item written as a fragment were all measured on that shape first, fixed
         * in its instruction, and then measured again here — in a brief, where the first costs the
         * reader the whole point of asking and the other two cost clauses on an answer that is
         * already too long. A rule seated in one shape says nothing about another, which is how all
         * three arrived back in this one; and they are not candidates for the shared format, which
         * takes a rule only where every shape can be held to it. A drafted message legitimately
         * narrates, and a question about what happened in a thread is legitimately answered with
         * what happened — so stated to all five, the first rule would break the two shapes that
         * answer the open world. What is shared instead is the pair of rules about the form a figure
         * and a time reach the reader in, which hold for anything composed — see `Reply.format`.
         *
         * **A fourth is that shape's too, and it holds here for a reason of its own.** Which turns
         * a brief may speak for is not a matter of form but of whose material it is: a thread the
         * subject merely writes in carries other participants' words, and this shape reads a longer
         * tail than that one, so proportionally more of its material is somebody else's business.
         * Nothing downstream catches it — the sentence is fluent, the citation resolves, and the
         * grounding gate asks whether a span was retrieved rather than whose business it recorded.
         * The rule is answerable only because the turn read carries the fact; stated against a name
         * it would be aspirational, for the reason `Turn.authorIsAnotherParty` gives.
         *
         * **Both arms are stated, because either alone is a defect.** A rule that only forbids
         * reporting another participant's matter trades a noisy brief for an empty one — and an
         * empty brief is indistinguishable from a broken answer path — while one that only says not
         * to leave material out buys an inventory of the room. What earns a place is tied to the
         * five-item bound rather than given a cut rank of its own, because the cut below orders
         * items that earned one and a second ranking would pull against it on the same decision.
         * A brief has no recent-events section to demote a matter into, so that half arrives as a
         * line standing for what is left out, which is also why that line cannot itself count
         * against the bound.
         *
         * **The measured fragment was composed here, which is why a prose rule is the fix.** The
         * store was read before this was written: no stored claim carries that shape — the shortest
         * of them is a clause rather than a word, none ends in a bare particle, and the shape reads
         * no attributes at all — while a bare affirmative is an ordinary thing for a turn to say,
         * and turns are in the material. So the dash and the word were the composer's, quoting a
         * one-word turn and leaving it unattached, and nothing upstream of this file produced it.
         *
         * **What the rule can and cannot reach, all the same.** It governs what the model writes.
         * Were a fragment ever a stored value relayed whole — a claim the extraction stage wrote as
         * a word, an attribute's value standing alone — no prose rule here would reach it, and that
         * defect would belong upstream; the rule names the quoted form explicitly because that is
         * the one this shape can be held to.
         *
         * **The say-once rule arrives in the form its own shape was measured into, not the form it
         * was first written in.** Asked only to say an absence once rather than per item, the
         * status shape obeyed it in both directions at once — in its opening and again under every
         * heading — so the ask there now names the one place the sentence goes. Ported without that
         * half, this prompt would carry the wording already known to be satisfiable twice. The
         * place differs because the shapes differ: that shape puts it in the opening, and this one
         * cannot, because the lead here is bounded to two single facts and the absence would be a
         * third. So it goes at the head of the account, which is where a brief's items begin.
         *
         * **And where it goes is a line opening a section, which the next sentence forbids — so the
         * statement names its items.** Read off a brief on the real archive: the head of the account
         * was a sentence saying that none of the undertakings *below* carried a recorded outcome,
         * about items the reader had not reached. Obeying the placement rule is what breaks the
         * prohibition on a section preamble, and no reading satisfies both while the sentence
         * summarises rather than identifies. Naming the items it covers settles it: the line is then
         * about things a reader can pick out rather than about a set they cannot see. The sibling
         * shape took the same change for the same reason, and this one followed it on evidence from
         * its own rendered brief rather than on the symmetry.
         *
         * **The say-once rule and the five-item bound hold each other up.** The bound is what makes
         * a clause an item expensive rather than merely wordy: a repeated absence spends the brief's
         * budget on what the record does not hold, and the bound's own escape — folding items
         * together to fit — is the thing it forbids. The whole-sentence rule is the same pair read
         * the other way: an item cut to a fragment is the other way of fitting, and it is as
         * unusable as the compression the bound rules out.
         *
         * **The kind rule is an order and a source for the questions, and it says so rather than
         * leaving a section to be inferred.** What a section is headed by is the shared format's to
         * say and it admits two keys, a body of work and a person, with a topic refused outright —
         * so a kind asked for as a heading would be a second grouping over the same material, and a
         * request carrying two groupings from two places leaves the model to pick one. The
         * material's own labels cannot stand in for the kind either: a one-to-one carries a
         * person's label whatever the two of them talk about, so the kind is a reading of each
         * matter rather than of where it was read from. Hence the three things the prompt states
         * outright, because none of them can be left to follow: that these items go last **within
         * the part of the brief they fall in** rather than gathered out of it, that they take no
         * heading of their own, and that the write-order holds within each kind rather than across
         * the two. The cut is left to decide what leaves, as a precedence rather than a set of
         * absolutes, which is why nothing here claims a first place in it.
         *
         * Prose pins hold these in `PrepareShapeTest`, which is the weak kind of assertion and
         * knowingly so: they catch a clause being dropped, not a sentence elsewhere working against
         * it, and only reading a rendered brief cold can say whether the model obeys them.
         */
        val INSTRUCTION = """
            You prepare somebody for a call. Produce the lead, the short account of where the
            relationship stands, and the questions worth asking on this call.

            Open with the lead, inside the answer's own block: one sentence saying where the
            relationship stands and one saying what the call turns on, and a third belongs in the
            account rather than the lead. Somebody who reads no further has to come away knowing
            that much. Keep each of the two to a single fact — a lead that packs several of them
            into one long sentence is not a lead, and it is the sentence most likely to be read
            and quoted. The lead asserts things about the material like anything else here and
            carries its citations the same way. Where the account has sections, the lead stands
            before the first of them and speaks for the whole brief.

            What was asked is the only thing that can say what this call is about. Where it names
            a matter beside the person — a piece of work, a document, a decision — the brief is
            about that: the account leads with what bears on it, the lead's second sentence is
            about it, and every question comes from there. This leaves nothing out on its own —
            what the call is not about is reported after what it is, and the cut below is the
            only thing that drops such an item. Where the material carries little about the
            matter named, say that of the material and report what it does carry, rather than
            handing back a brief with nothing in it.

            Where what was asked names the person and nothing else, nothing says what the call is
            for, and a record is rarely all of one kind. What the two of them do together is one
            kind. An arrangement between them as people is another — money passed from one to the
            other, an invitation to something social, a favour or a price passed on privately —
            and somebody about to take a working call has nothing to do about any of it. Report
            those last within the part of the brief they fall in, after the items about their
            work, so the reader can stop before them, and draw no question from one. They take
            no heading of their own: this says where an item goes and is not a second thing to
            group the brief by, and the write-order below holds within each of the two kinds
            rather than across them. The cut below is what leaves one out, and it already knows
            what the call is not about. Where the record holds nothing but those, that
            is what the call is about and the brief is written from them like anything else — a
            counterpart the reader knows privately and not at work still gets a usable brief.

            Which of those two this request is, and that nothing said what the call was about, are
            not findings about the record. Write neither as an item, nor as a question, nor as a
            remark of its own, and cite nothing for either: they are how you decided what to
            write, and a brief that reports how it was put together spends on the machine a line
            the reader needed for the call.

            A recap of the conversation is not an account of where things stand. A line reporting
            that somebody asked a question, explained a position or sent something over says what
            happened and not where the thing now stands — so do not assemble the account out of
            those, and do not walk the reader back through the exchange in the order it happened.
            Where a message is what moved something, say what it moved and cite it, rather than
            reporting that it was sent.

            Put the undertakings the record shows no outcome for first. A call is for the things
            that cannot be settled in writing, so one of those is worth more than a promise
            reported as done, however recent. That is which items come first and not a reading
            of where they stand, and it is also what the bound below keeps.

            A commitment the material marks as closed by a later message is reported as done — do
            not raise it as something to chase, and do not raise it as an item of its own either.
            A matter the record reports as done, written up so that the brief can say so, spends
            one of the items the bound below allows on the one thing there is nothing to do about.
            Report it where something the record shows no outcome for turns on it, and say what
            that is. The rule below about reporting an outcome as the records have it says how to
            word one you do report; it is not a reason to report one.

            A marker is not the only way the record reports a thing done, and this holds however
            it says so. A question asked and answered in the messages, a thing handed over and
            acknowledged, a matter somebody writes is sorted — where the material carries the end
            of it, that is an item with nothing to do about it as surely as a closed undertaking
            is, and the same rule decides it: out, unless something the record shows no outcome
            for turns on it, in which case say what. And it decides an item before the kinds above
            do, so a private matter the record reports as done is left out rather than reported
            last. Being short of items is not a reason to keep one: a brief of two items the
            reader can act on is worth more than four they cannot. What goes this way is named in
            the line that stands in for what the brief does not write out, below, as another
            participant's matters are — one clause saying what was finished, not an item each.

            The material includes inferred commitments. Those are readings of a message rather than
            facts, so cite the message behind each and never present one marked as not stated
            firmly or not first hand as though it were settled. Which
            party a commitment places the thing on is marked on it as "obliges", and where it
            carries no such marker it is in the claim's own words and not in the party beside it,
            which is whoever stated the thing; say who said a thing rather than swapping the two.

            A commitment whose outcome is not recorded carries where its due date stands — now past
            and by how far, today, ahead, or that no date was stated. Write them in that order: the
            ones now past first and the furthest past of those before the rest, then today, then
            ahead, then the ones with no date, and anything reported as done last, where such an
            item is in the brief at all — the rule above leaves most of them out. The standing is
            marked on each item, so read it from there rather than from where the item sits — a
            commitment read out of a recent message is shown beside that message whatever the order
            of the rest.

            A label you put over a group of them has to be true of everything under it. A group
            holding something still ahead, or something with no date at all, is not a group of
            overdue things — so either name what the group really holds or give each standing its
            own label.

            Name the party each undertaking you report is placed on. Where the record places it on
            nobody, say what was undertaken rather than supplying somebody to carry it.

            An item marked as stating no date was mentioned with no time attached. Report it as
            something with no date rather than placing it by when it was mentioned, and do not
            treat it as recent, imminent or over.

            Say once what the record does not hold, rather than once for every item. Where several
            items state no date, or several have no recorded outcome, say it for the group and name
            the items it covers. Each of those is worth saying and worth saying once: a brief read in the
            minute before a call cannot spend a clause an item on it, and repeated down the page
            what the reader learns is mostly that this system does not know.

            Once means once in the whole brief, and the head of the account is where it goes — not
            the lead, which carries its two facts and no third. Never open a section with a line
            summarising the items beneath it, neither the labels they all carry nor how many of
            them there are: a reader meets those facts again in the items themselves, and the
            material such a line cites belongs to them rather than to it. A heading naming what
            its section is about is not that line, and neither is a standing written on an item of
            its own. Beneath a heading the items speak for themselves.

            A commitment whose outcome is not recorded also carries how long ago it was last stated.
            Use it to decide what is worth raising on the call: an undertaking given three weeks ago
            with nothing since is a different thing from one given yesterday, and without it the two
            read alike.

            A message is marked with who sent it, as "Sent by". The history includes rooms other
            people write in, so attribute a message to the person marked on it and to nobody else —
            a question built on somebody else's words put into this person's mouth is worse than no
            question. A message carrying no such mark was written by somebody the corpus does not
            name: say it was said without saying who by, and do not attribute it to the person this
            call is with. This is about messages only; an inferred commitment carries its parties as
            markers of their own, as above.

            The brief is written for the person this call is for, and the turns they wrote carry
            "the person this brief is for" after the name that sent them. That parenthesis is a
            label on the material and no part of anybody's name: never write it into the brief.
            Where no turn carries it, the material does not say which party is reading, and every
            rule in this paragraph and the next two is then off — write the brief without them
            rather than guessing who the reader is.

            What that person did, received or sent themselves, they already know, and where the
            material stops on one of those the gap is in the record and not in what they know. So
            a question you propose is one its answer is not already with the reader. Money handed
            to the reader, a meeting they sat in, a message they sent — the person being called
            has nothing to tell them about any of it, and a call opened by asking somebody to
            confirm a thing you are holding yourself reads as not knowing who you are talking to.
            Where the reader was a party to something and the record stops there, report what the
            record holds and ask nothing about it. Where the thing was to happen between the
            reader and the person being called, whether it happened is something the reader
            answers: what is left to ask about such an item is what comes next, and asking both in
            one sentence asks the half they are holding. That is the one place where the rule
            below against presupposing an outcome does not apply — it is about an item whose
            outcome nobody present holds, and here the reader holds it, so a question about what
            follows takes nothing for granted at the person being called.

            None of that reaches a third person. The history includes rooms, so an undertaking
            given by somebody other than the reader is a question whoever gave it can answer,
            whether or not this call is with them.

            That nothing in the material answers something the reader sent is a fact about their
            own side of the conversation. Do not report it as a finding about the person they are
            calling.

            None of this takes an item out of the brief. An item these rules leave you no question
            to ask about is still reported in the account of where things stand: what goes is the
            question and not the item, and where it is worth saying so, say it of that item as
            part of what you report about it rather than as a question or as a remark of its own.
            Three rules leave an item out and no others: the length bound below, which chooses by
            the precedence stated with it; the rule above about a matter the record reports as
            done, which chooses by there being nothing left to act on; and the rule after this one
            about a matter belonging to somebody else, which chooses by whose matter it is. None
            of them chooses by who holds the answer, and nothing else here drops an item at all —
            what the call is about decides the order of the brief and what its questions rest on,
            and reaches the bound through that precedence rather than on its own. A thin record
            still produces a brief.

            A message said by somebody who is "neither the subject nor the reader" is marked with
            those words. The subject there is the person this call is with, and the reader is the
            person this brief is for, as above. The mark is about who wrote it and not about what it
            says: the person this call is with may well be a party to what another participant
            wrote, and such a message is frequently what moved something. What it tells you is that
            the matter may belong to somebody else. So for any item you report or question you ask
            that rests on a marked message, settle what it has to do with the person this call is
            with. Where they are a party to it — it places something on them, it is addressed to
            them, it changes what they are to do — report it as you would any other item. Where they
            were merely present while other people settled a matter of their own, it is not what
            this call turns on: report it in the standing-in line below rather than as an item of
            its own, saying whose matter it is, keep it out of the lead, and never build a question
            on it for this call, because a question is read out to them and one about somebody
            else's business asks them to account for what was never theirs. That an undertaking
            somebody else gave is answerable by whoever gave it, as above, is not a reason to put it
            to this person.

            A message arriving without that mark is not thereby this person's. The mark is put on
            only where the record settled the question and the answer was yes, so an unmarked
            message may be theirs, may be the reader's, or may be one the record cannot place at
            all. Read it as nothing having been said either way rather than as a claim, and attribute
            it from its sender as above.

            That is not a reason to leave material out, and it is not licence to inventory the room
            either. What earns an item of its own, against the bound the length rule below sets, is
            a marked message bearing on that person's own items — it explains a delay of theirs, it
            moved something of theirs, it answers a question of theirs. The rest of the thread is
            worth a line or two saying what those matters were about, without writing each one out:
            somebody preparing for a call with one person does not need every other participant's
            week. That standing-in line is an assertion about the material like any other and
            carries its citations the same way; it does not count against the length bound, since a
            line that stands for what was left out cannot be what the bound leaves out; and it is
            not the summarising line forbidden above — it stands for material the brief does not
            otherwise write out, rather than restating items that follow it. Both ways of getting
            this wrong cost the reader the brief: stripped to that person's own words it reads as a
            system that found nothing about them, and expanded into the whole thread it reads as one
            that did not understand who the call is with.

            Questions should be specific enough to answer. "Ask about the timeline" is not a
            question; naming the thing whose timeline is unclear is. Ask the ones the call cannot
            be had without, rather than one per item you reported. Write each on a line of its own
            and open it with an en dash and a space, so that the set of them reads as the list it
            is: – like this. Nothing else in the brief opens that way, and a question carries no
            citation, so the dash is what tells a reader these lines belong together.

            A question presupposes everything it does not ask, and it is read out to the person it
            is about — so whatever it takes for granted is said to their face. Where an undertaking
            has no recorded outcome, name what was undertaken and ask what came of it, leaving both
            answers available. Do not ask how it went, what it produced or what is left to do on
            it. Each of those tells the other party they did the thing, and leaves them either
            correcting you or agreeing to something neither of you knows. A question is not a
            claim line and carries no citations: what the record holds belongs in the account
            above, reported there as a finding, so a question neither restates it nor stands in
            for it.

            Check how old what a question rests on is before resting on it. Somebody's account of
            their own progress was true on the day they wrote it: the block it sits in is headed
            with that date, and an undertaking whose outcome is not recorded carries how long ago
            it was last stated, as above. A message saying something is nearly finished is a fact
            on its own day and a guess a week after it, so where the material behind a premise is
            old enough to have been overtaken, ask where the thing stands now instead of assuming
            where it stood then.

            Answer in the language of the question. Where the question carries no language of its
            own — a command and a name, which is the ordinary case here — answer in the language of
            the material. The material is often in another language; a name
            or a phrase you quote keeps the form the source gave it. For a person the material names, that form
            is the one it gives for them, and not the way a message happens to address them.

            Write each item as a whole sentence that says what follows from it. An item that trails
            off — a dash and a word, a label with no sentence around it, half a quotation — leaves
            the reader guessing what it was meant to carry, which is the one thing somebody reading
            this minutes before a call has no time to do.

            This is read in the minute before a call, so hold it to at most five items in the
            account and at most three questions. That is the whole brief rather than each part of
            it: five items across every section, not five under each. The lead is not one of the
            five.

            Where the material carries more than five, leave whole items out rather than making
            room. What goes first is anything reported as done, apart from one kept because
            something the record shows no outcome for turns on it — that one goes with the thing
            it was kept for — then whatever the call is not about, then the items whose standing
            is furthest from needing this call — read that standing from the label on each item,
            as above, and not from where the item sits. Where that still leaves more than five, the
            ones a longer silence sits behind are the ones to keep. Two ways of fitting the bound
            are worse than leaving something out, so do neither: do not drop the citations from an
            item to make room, and do not fold several items into one sentence — a sentence
            compressed to fit asserts more than the material does and strands the reasoning a
            reader needs. Write one claim line saying the material carries more than the brief
            lists, citing what you read it against, rather than letting five items read as
            everything you were given.
        """.trimIndent()
    }
}

/**
 * Contact history and what has been promised, owed first, each turn naming who wrote it and marked
 * both where that writer is the person this brief is for and where they are neither that person nor
 * the one the call is with, and each promise the parties that stated it and that it obliges, with
 * whatever the corpus records as having discharged one and how long ago anything was last said
 * about the rest.
 *
 * **Everything a clock is read for here is the same question: which of these is worth raising.** A
 * promise due yesterday with nothing recorded against it is the single most useful thing in the
 * material and cannot be told from one due next month without now in hand; it decides the order the
 * commitments arrive in, the standing marked on each, and how stale the record of one is. One
 * reading serves all three, so the order and the labels cannot disagree.
 *
 * **Position carries the order for some of the material and not all of it**, which is why the
 * instruction states the order as something to write in rather than as a fact about what it is
 * given: a commitment read out of a turn still in the recent tail shares that turn's span and is
 * folded into its block, so it is shown where the turn falls however this sorted it. How much of
 * the material that reaches is a property of [MESSAGES] against the corpus rather than a constant —
 * this shape reads a longer tail than the status shape, so it folds more — and the standing on each
 * item's own label is what carries the order either way.
 */
private class RetrievePrepare(
    private val retrieval: Retrieval,
    private val clock: Clock,
    // The same zone the assembly heads its blocks in, because the times this renders into a block's
    // text sit a line under those headings — see `Material.of`. It also decides the standing each
    // commitment is labelled and ordered by, which this shape cuts its brief on — so one zone, for
    // the reason there is one clock. Both come from `shapes`.
    private val zone: ZoneId,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        val subject = exchange.subject ?: return Progress.Carried(exchange)

        // One reading of the clock, before anything derived from it: the standing that orders the
        // commitments and the age rendered on each are read against the same instant, so a promise
        // cannot be sorted as ahead and then labelled as past by a tick in between.
        val now = clock.instant()

        val conversations = retrieval.conversationsOf(subject, THREADS)
        val recent = retrieval.recentMessages(conversations, MESSAGES)
        val promised = retrieval.commitmentsOf(subject.reach(conversations), COMMITMENTS)

        // What the corpus records as having discharged any of them. **Read here rather than
        // left out**, though this shape's own instruction is about what to ask on a call: a promise
        // already reported as done is the one thing nobody should open a call by chasing, and
        // rendering every commitment as having no recorded outcome would put exactly that in front
        // of the reader.
        val closed = retrieval.closuresOf(promised.map { it.id }).associateBy { it.commitment }

        // **What is owed first, which is what a brief read in the minute before a call is for.** The
        // store orders by recency of evidence, and what fell due with nothing recorded against it
        // has no relationship to when it was last mentioned — a thing agreed a fortnight ago for
        // last week is exactly the item that goes unnoticed. Sorted after the closure read rather
        // than before it, because whether a promise is discharged is half of where it stands, and
        // because that read's argument is then the order the store gave.
        val stated = Material.owedFirst(promised, closed, now, zone)

        // After the commitments, because their parties are what is being named, and in one read for
        // the handful of people a subject's commitments concern. **The obliged party is asked for in
        // the same read**, which is what keeps it free: it is a contact identifier like the stater,
        // it resolves through the same merge, and for a first-hand promise it is frequently the
        // person who stated it — so a second read would ask about names this one already has.
        val parties = retrieval.namesOf(
            (stated.mapNotNull { it.statedBy } + stated.mapNotNull { it.obligedParty }).distinct(),
        )

        // And who wrote each turn, in one read once the turns and the closures are both in hand. A
        // script built from a room's history has other participants' words in it, and a turn offered
        // with no author is read as the person this call is with — which cites correctly and passes
        // the grounding gate, so nothing downstream can catch it.
        //
        // **The same read answers whether that writer is the person this brief is for**, which is
        // what `reader` below is for and is free here: it arrives on the turn beside the name, so
        // asking separately would be a second read for a field this one already returned.
        //
        // **The subject's contacts travel with it**, which is what lets the read answer whose turn
        // each is — see `Retrieval.turnsOf`'s own parameter and `Turn.authorIsAnotherParty` for why
        // the comparison cannot be made on names and what passing nothing would mean. Worth more
        // here than on the status shape for one reason that is this shape's own: [MESSAGES] is a
        // longer tail, so proportionally more of it is somebody else's business.
        val turns = retrieval.turnsOf(
            (recent + closed.values.map { it.by }).map { it.externalId }.distinct(),
            subject.contacts(),
        )

        return Progress.Carried(
            exchange.copy(
                // Messages first, for the reason `Material.gathered` gives: a commitment shares its
                // message's identifier, and the turn should read before the inference.
                retrieved = Material.gathered(
                    recent.mapNotNull { message ->
                        val turn = turns[message.externalId]
                        Material.of(
                            message,
                            turn?.author,
                            own = reader(turn?.authorIsSelf),
                            // Marked only where the read settled the question and the answer was
                            // yes. Everything it could not settle goes unmarked, which is the
                            // conservative direction: marking a turn nothing places would assert
                            // an author the record does not have, and the instruction already says
                            // to report such a turn without attributing it to anybody. The
                            // reader's own turns are unmarked by the same answer and carry the
                            // label above instead — what the reader said is the other half of the
                            // relationship a call is being prepared for.
                            anotherParty = turn?.authorIsAnotherParty == true,
                        )
                    } +
                        // The closing turns before the inferences, for the reason the comment above
                        // gives: a closing message frequently states a promise of its own, so its
                        // span collides with that commitment's — and `gathered` lets the first
                        // occurrence set the position. Appended afterwards, the inference would win
                        // the position and the turn would read under it.
                        closed.values.mapNotNull {
                            val turn = turns[it.by.externalId]
                            Material.closing(
                                it,
                                turn?.author,
                                zone,
                                own = reader(turn?.authorIsSelf),
                                // The same question as for a recent turn, and it has to be asked
                                // here too: a promise is frequently reported done by somebody other
                                // than the party who gave it. A closing turn already inside the
                                // tail is rendered twice under one identifier, so leaving it
                                // unasked here would put one record in front of the model marked
                                // and unmarked at once — wherever the mark renders on both routes,
                                // which is wherever the turn carries a name. `Material.closing`
                                // says the clause of the name and so drops it on a turn the corpus
                                // names nobody for, where the instruction's rule for an unnamed
                                // turn covers what is lost.
                                //
                                // The two marks cannot both land: the store puts the reader's own
                                // accounts and a third party's in disjoint sets, so a turn is at
                                // most one of them. This route renders both unconditionally rather
                                // than excluding one, which is why that is worth saying here.
                                anotherParty = turn?.authorIsAnotherParty == true,
                            )
                        } +
                        // Direction and age, which this shape asks for because a brief read before
                        // a call is a judgement about what to raise: which party a promise is on
                        // decides whether to chase it or to report on it, and how long the record
                        // has been silent is what separates one undated item from four others
                        // carrying the same pair of labels.
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
                        },
                ),
            ),
        )
    }

    private companion object {
        const val THREADS = 3
        const val MESSAGES = 50
        const val COMMITMENTS = 30

        /**
         * The mark for a turn the person this brief is for wrote, and nothing for anybody else's.
         *
         * **The one fact a brief cannot work out for itself is which party is going to read it.**
         * Everything that party did, received or sent is something they already hold, so the
         * record's silence about any of it is a gap in the record rather than in what they know —
         * and a question put to the other side about one of those asks somebody to confirm what
         * the reader is holding. The instruction is what draws that consequence; this is what
         * gives it something to read.
         *
         * **Null for an author nobody established as the owner, which is the conservative half.**
         * The port collapses *not ours* and *nothing is known* into one false — see `Turn` — so an
         * unmarked turn means the mark could not be established rather than that the reader did
         * not write it. The instruction carries an arm for that, as each of its other rules that
         * reads a marker does, and it is what keeps this conservative rather than merely quiet:
         * the cost of an unmarked turn is a question that should not have been asked, where
         * marking wrongly would hand the reader's own words to the person they are calling.
         */
        fun reader(isSelf: Boolean?) = Material.OwnTurn.READER.takeIf { isSelf == true }
    }
}
