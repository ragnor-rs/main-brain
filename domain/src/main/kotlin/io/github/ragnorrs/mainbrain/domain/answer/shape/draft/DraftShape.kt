package io.github.ragnorrs.mainbrain.domain.answer.shape.draft

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
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import java.time.ZoneId

/**
 * A reply to a person, ready to send, in two or three complete drafts.
 *
 * **It accepts a contact and nothing else, because a reply is addressed to a person.** Asked for a
 * client it resolves through to that client's contacts rather than objecting that an organisation
 * cannot receive a message — which is scope resolution's traversal rule, not this shape's business.
 *
 * **Addressed to a person is not sent to them alone, and the two come apart on most of this
 * corpus.** A working counterpart's entire record is frequently a room shared with others, so the
 * only place a reply to them can go is a conversation three other people read. The material says
 * which of the threads it carries are shared, and the instruction composes for the one the latest
 * exchange was in — a draft written as a private message and sendable only into a room is correct
 * prose in the wrong channel, which nothing downstream can see.
 *
 * **Telegram only, and that is a scope line rather than a limitation of the shape.** The request is
 * stated for that channel or email, and email arrives with a later workstream, so there is no
 * second thread to answer and no prior correspondence on that side to learn a register from.
 *
 * **Nothing is sent.** The drafts are for a person to send, which is the standing rule for every
 * answer here and also a scope line: outbound messaging is a later workstream entirely.
 */
class DraftShape(
    model: ModelProvider,
    retrieval: Retrieval,
    lookup: Lookup,
    grouping: Grouping,
    zone: ZoneId,
    sizing: Sizing = Sizing.ANSWERING,
) : Shape {

    override val id = ShapeId("draft")

    /**
     * What this shape answers.
     *
     * **It says the request asks for a message to be written, which is what separates it from every
     * other shape.** A question about a person and a request to write to that person differ in what
     * is wanted back, not in what they are about, so the line turns on the deliverable.
     */
    override val description =
        "A message to send somebody, written out ready to send — asked to reply, to write back, " +
            "or to draft something to a person."

    override val accepts = setOf(SubjectKind.CONTACT)

    /**
     * Two or three, never one.
     *
     * The value of this shape is a choice between register and framing, and one draft is not a
     * choice. The runner checks it mechanically, because an interface cannot see the difference —
     * it renders what it is given, and a single draft looks exactly like a shape that never offered
     * alternatives.
     *
     * Where a thread has left nothing open the choice narrows to how briefly to say so, and the
     * drafts come back close to one another. That is still a choice and still not one draft, which
     * is why the count is checked and the difference between them is not. What those short messages
     * are is named in [INSTRUCTION], where the rule for that case lives.
     */
    override val variants = FEWEST..MOST

    override val readsCorpus = true

    /**
     * No, and this is the shape the claims-only scope of the grounding check exists for.
     *
     * The deliverable is a message somebody sends, and the draft itself cites nothing by
     * instruction: an assertion about the history goes in a claim line outside it, where the thread
     * calls for one. So a rule reaching this shape would refuse every draft that needed no such
     * assertion, which is most of them and is exactly the failure that scope prevents. It reads the
     * corpus, which is why the declaration is the shape's own rather than read off [readsCorpus].
     */
    override val assertsFindings = false

    override val stages: List<Stage> = listOf(
        RetrieveDraft(retrieval, lookup),
        // Not sectioned, which for this shape is the decision its whole output depends on.
        // It reads widely on purpose — register is learned across threads — so its material spans
        // several bodies of work almost every time. What it produces is one message to one
        // counterpart, and a drafted reply divided under headings is not a worse answer but one
        // nobody can send.
        Assemble(INSTRUCTION, variants, grouping, sections = false, zone = zone),
        Generate(model, sizing),
    )

    private companion object {
        /** Two is a choice; one is not. */
        const val FEWEST = 2

        /** Three is as many as somebody will read before picking one. */
        const val MOST = 3

        /**
         * What this shape asks of the model.
         *
         * What it insists on that the others do not: the drafts must differ in a way somebody can
         * choose between and must differ only in how they say it, each of them carrying everything
         * the reply owes, the prose carries no citations, it is composed as one named party writing
         * to another, it addresses what that other party left open, it undertakes nothing on
         * the sender's behalf that the material does not already show, and it states as current
         * only what the material still supports as current. A drafted reply is composed
         * text rather than an assertion about the corpus — a draft that cited its own sentences
         * would either block on the grounding check or render as a footnoted message nobody would
         * send. And it is the only shape written *as* somebody rather than *about* them, so whose
         * side it is on is a thing the instruction has to settle rather than leave to the material.
         *
         * **Saying how to write without saying what to write about is what produces a sendable
         * message nobody has a reason to send.** Every rule it carried is satisfied in full by a
         * summary of the sender's own position — which the counterpart was present for, so it moves
         * nothing on. The target is named here, and named against the failure, because a thread with
         * no obvious open question is what invites that summary.
         *
         * **Telling the drafts to differ without saying what may not differ is answered most
         * cheaply by dividing the facts up, which is the second half of the same vacancy.** Measured
         * on the archive: across one set of drafts, one carried a list of restored items, another a
         * lifted restriction and an access grant the first omitted, and a third neither. Each was
         * missing something another had, so choosing any of them dropped a fact and a reader who
         * wanted the whole picture had to write a further message out of the set — which is the
         * work this shape exists to do. The axis is bounded away from what a draft commits to, and
         * that bound leaves the material as the remaining way to differ: told the difference must
         * be real and not told what has to stay the same, a model splits the one thing it has
         * several copies of.
         *
         * **So completeness is the invariant and the wording is the axis, and the invariant sits
         * beside the rule it bounds.** The differ rule is the one satisfied by splitting, and a
         * bound paragraphs away from the permission it narrows was measured losing to the
         * permission.
         *
         * **The whole of it is conditioned on the thread leaving something open, which is what
         * keeps it clear of the nothing-open case below.** That case's honest answer is the same
         * short message at two lengths — a bare acknowledgement against one adding what has
         * changed — which is a difference in content, so any sentence of this rule left standing
         * over the drafts at large would forbid it. One clause disclaiming the owed half is not
         * enough for that: the sentences about dividing the material and about carrying what
         * another leaves out would reach the case regardless. So the condition opens the rule and
         * the close repeats that none of it applies, and what a draft says there is settled in one
         * place rather than two — stating it in both is how a model is left to resolve a prompt by
         * picking a half of it.
         *
         * **What is owed is the test, and it reaches past the exchange being answered.** A request
         * further back that nothing has met is still owed, which the clause bounding the marked
         * turns is there to say; so an invariant keyed on the marked exchange alone would leave a
         * partition across exactly the material the sibling rule below admits. It is keyed on the
         * reply's debt instead, and names both sources so neither reading is left to inference.
         *
         * **The explaining axis is the one that needs saying twice.** Register and length are
         * plainly about words; *how much they explain* reads equally as how much is explained and as
         * how much there is to explain, and the second reading is the defect. So it is named and
         * resolved — more words about the same things — rather than left to be inferred from the
         * invariant above it.
         *
         * **The rule also says what the difference positively is, because the paragraph above
         * forbids variations of one sentence.** A rule demanding the same facts, read beside that
         * prohibition, is a prompt that forbids a reading and then asks for it, which a model
         * resolves by picking a side. So the axes are named where the invariant states them —
         * briefly or at length, plainly or warmly, more explanation or less — which is what makes
         * identical facts a choice rather than a re-wording.
         *
         * **A drafts-differ rule is not the only thing that licensed the split.** The sentence
         * naming what a draft is built out of offers its kinds in a disjunction, which reads as a
         * choice to be made per draft and is the cheapest way to obey both rules at once. The
         * enumeration is what makes that sentence usable, so it carries what it is instead —
         * kinds to look for rather than alternatives to pick between — and is conditioned on the
         * material being owed, which is what keeps *what has changed since* from being required
         * of every draft on a thread the nothing-open rule governs.
         *
         * **One reply across all of them, which is the other half of the measured defect.** One
         * draft answered a side question older than the thread the others addressed, so it was no
         * alternative to them — a second reply, offered as a choice. What permitted it is the
         * clause keeping the rest of the material in bounds, which holds: a request nothing has
         * met is still owed. What that clause does not say is where an older request goes, and the
         * answer is inside the reply to the marked turns rather than instead of it. It sits beside
         * that clause, the permission it bounds being the one that reaches further back.
         *
         * **It carries the arm for the marked turns leaving nothing open, where the older request
         * is the only thing there is to answer.** Written as an unconditional *all of them answer
         * the marked exchange* the rule would forbid the one sendable reply on that thread, and a
         * rule the correct answer breaks is discounted wholesale rather than obeyed narrowly. So
         * what it fixes is that the drafts are aimed at one thing, and which thing that is follows
         * from what is open.
         *
         * **The sender's own turns are offered for two purposes and the instruction states both,
         * because stating only the first is what makes a restatement the easiest draft to write.**
         * They are the register this shape is learning, and they are also the record of what the
         * sender has already said. A register example carries an explicit rule bounding it to its
         * wording; without the matching rule the sender's own turns are wording to imitate and
         * content to compose from at once, and the content is what the other party already has.
         * The two uses are stated apart rather than equated, because a register example also
         * carries prohibitions — never replied to, never stated as fact, never cited — and a turn
         * that is both the register and citable evidence cannot inherit those.
         *
         * **What makes the rule applicable is the conversation a turn was said in, which is a
         * marker the material carries.** The weaker claim is deliberate: only the contact route
         * through [Retrieval.conversationsOf] selects on authorship, so a thread reached through a
         * client or a named conversation may be one the counterpart never wrote in, and a register
         * example comes from outside the followed threads entirely. *Was the sender's turn read by
         * the people this draft is going to* is therefore answered per conversation rather than by
         * a universal over the material — which is also the question the rule needs, since most of
         * this corpus's threads are rooms and a draft is composed for one of them.
         *
         * **The mark it keys on may be absent from a turn the owner wrote**, where the author has
         * no contact behind it yet — see [Material.authoredBySelf], which documents that gap. The
         * instruction's fallback covers the case where no turn carries the mark at all; a turn that
         * slips through in a mixed window is outside the already-delivered set and reads as new.
         * That is the residue of this fix rather than something it closes, and closing it belongs
         * to whatever reconciles those authors.
         *
         * **What is bounded is re-delivery, not reference.** A reply frequently has to name the
         * thing it is about, so a flat prohibition on mentioning anything already said is one a
         * correct draft has to break — and a rule broken by the correct answer is discounted
         * wholesale rather than obeyed narrowly. So a mention is allowed and a restatement is not,
         * and the line between them is drawn at the content: naming the thing in as few words as
         * the sentence takes, against repeating its detail or its list, which is what the
         * recipient already holds.
         *
         * **Measured against the live model, the loose form of this is worse than no rule at
         * all.** Permitting *a short confirmation* was read as permission to confirm by re-listing
         * what had been restored — the measured defect, arriving through the fix for it, on a
         * request the unchanged instruction answered without that list in the draft. A carve-out
         * on a rule about repetition has to say what may be repeated and not merely that something
         * may be, or the model picks the fuller reading.
         *
         * **And each draft is required to carry something new**, which is the same rule stated as
         * a thing to do: told only what not to send, a model sends the nearest thing outside the
         * prohibition, which is the reasoning the vocabulary rule for unrecorded outcomes records.
         *
         * **Where there is nothing new to carry, this rule points at the next one rather than
         * answering too.** What a draft says on a thread that leaves nothing open is settled below,
         * in full, including what the alternatives are when the honest answer is one confirmation.
         * Stating it in both places would be two rules over one case, which is how a model is left
         * to resolve a prompt by picking a half of it.
         *
         * **The pointer carries the content bound with it, which is the half a bare hand-off
         * loses.** The rule below bounds a confirmation's length — a short one, and nothing beyond
         * it — and says nothing about what may be inside it. That is the same permission this
         * card measured as worse than no rule, so a hand-off that dropped the bound would switch
         * it off on precisely the thread the restatement arises on: the one with nothing obviously
         * open, which is what invites a summary in the first place. So the pointer says what to
         * write is settled below *and* that naming-not-restating still holds there.
         *
         * **Which turns the reply answers is marked on the material, and the instruction reads the
         * mark rather than the dates.** Position in the list is relevance and not time: this shape
         * reads several conversations, concatenated closest-first with nothing rendered between
         * them, and the ranked hits come last although they are older than the window they extend.
         * An instruction that said to read the material forward would select the wrong turn on any
         * subject with more than one thread, which is most of them — and one that sent the model to
         * the dates instead was measured losing the same comparison for a different reason: ordering
         * every heading in the prompt to find one turn is work, and a request the thread had settled
         * read as the open one. How many headings that is follows from the read's own bounds — the
         * threads it follows, the tail it takes of each, and the widened ranked hits — which is
         * where to read it rather than here. So the exchange is selected where it can be computed —
         * see [RetrieveDraft.latestExchange] — and the instruction says which mark carries it and
         * what follows from a marked turn being the sender's own.
         *
         * **The dates stay, for everything the mark does not settle.** Which turn is being replied
         * to is one question and when anything was said is another: a marked exchange says nothing
         * about how old the fact behind a sentence is, and a model placing one by its position in
         * the prompt would be reading relevance as time again. So the rule sending it to the instant
         * on each block is kept and scoped to that, as is the fallback for material no turn of which
         * carries the sender's mark — a turn the corpus never named carries none, and the test the
         * rule would otherwise apply is vacuous without it.
         *
         * **What the nothing-open rule turns on is what the exchange still leaves waiting on the
         * sender, and who wrote last is one way that is satisfied rather than the test.** Written
         * as a conjunction the rule could not fire in the ordinary case: the other party writes
         * last on an active thread, so a thread whose latest turns ask the sender for nothing
         * failed the first half, fell through the rule entirely, and landed on the one saying the
         * drafts must differ, which is answered most cheaply by raising something. The proxy is
         * kept as a sufficient case because *the sender wrote last* is cheap, checkable and almost
         * always right where it holds — not because it guards anything: the rule's firing is what
         * suppresses substance, so a condition that only adds a way to fire cannot also narrow it.
         *
         * **What keeps an owed reply from being silenced is the condition naming what is still
         * waiting**, which is the half the proxy stood in for and the half a one-sided reading of
         * *asks anything* loses. A question the sender has already answered is not one still asked,
         * so a settled thread reads as settled; an undertaking the sender gave and has not reported
         * on is theirs to write about even where the other party asked for nothing, which is what
         * the voice rule above means by an undertaking being theirs to report on. The rest is whom
         * each turn addresses — rooms are most of this corpus's threads, so a question put to a
         * third participant is one the sender may answer and is not asked. That arm also reaches
         * the material carrying no sender's mark, where who answered what cannot be told at all:
         * what a turn asks can still be read off the turn. It is stated without the channel mark on
         * purpose, both because the mark is defined further down the instruction and because an
         * addressee is read off the words rather than off a label.
         *
         * **The count is not relaxed for it, and what the choice is gets named instead.** Two or
         * three is the shape's own range and the runner holds every reply to it, so the case cannot
         * be given a count of its own — only told what its alternatives are, which is the same
         * short message at different lengths. And the part that is not a message to send is
         * reported rather than dropped: that the latest exchange asked nothing of the sender is an
         * assertion about the material like any other, so it goes in a cited claim line, which is
         * where the reader learns why the drafts are short rather than thin. It is phrased as what
         * a turn asked and not as a state, because the shared reply format holds a draft's claim
         * lines to giving an undertaking no state at all.
         *
         * **Which conversation the draft is for is said rather than guessed, and that is the
         * cheaper half of the channel rule.** The shape picks which thread the exchange is in —
         * that is what the mark follows, and the instruction says the marked turns are all in it —
         * but it cannot compose for a channel on the model's behalf, and which thread the reply is
         * *for* is a sentence in the answer rather than a fact in the material. What the
         * instruction can insist on is that the answer place it,
         * which turns an unsendable draft into one a reader can act on — and the claim line is
         * where it goes, a statement about which thread the material holds being an assertion like
         * any other, carrying a citation for the reason every claim does. Saying it must not become
         * a count of threads: the material is a few at most and the corpus holds what it holds,
         * which is the whole-from-a-sample reading a separate card is about.
         *
         * **Placing it is not always naming it, and the rule carries the arm for that.** A thread
         * other people are in need not be a thread the corpus holds a name for, which is a case
         * the channel type keeps apart from a private one deliberately. Asked to name it anyway the
         * model would either invent a name or drop the claim, so it is told what to say instead —
         * the fallback this shape's rules need wherever a mark may be absent, as the sender's own
         * mark already shows.
         *
         * **What must not reach a room is named in the instruction and not left to the register
         * rule.** The material of a shared conversation routinely carries one participant's own
         * access — where an invitation went, where a credential was put — and a draft is the one
         * output here whose purpose is to be sent, so composing it in is the step that matters
         * rather than the material holding it. Bounded to what the thread does not already carry,
         * because material posted in that same room discloses nothing by being repeated there, and
         * a rule that refused it outright would refuse the ordinary case of answering about it.
         *
         * **An undertaking the draft originates is reachable from nowhere else.** The prose carries
         * no claims by construction, so the grounding check never reads a word of it, and an offer
         * is about what will be done rather than about the corpus — the rule against stating what
         * the material does not show has nothing to measure one against. Nor can a rail tell an
         * offer that was made from one that was not without reading the material, which is the
         * model's job and not a check's. So the bound is stated here, aimed at the end of a
         * message, where the register itself supplies the pressure to add one, and at the close
         * that asks for a detail and appends what will be done once it arrives, which is the one
         * form the rest of the bound leaves standing. It carries the no-mark arm this shape's
         * rules need wherever a mark may be absent, since telling the sender's undertakings from
         * the other party's is what that mark is read for. And the axis the drafts differ along
         * names register, length and how much they explain rather than what they commit to,
         * which would ask for the same thing from the other side; it stays off how much of what
         * is open a draft takes up, which is not a choice to offer — the completeness invariant
         * above requires all of it of every draft.
         *
         * **A fact is only as current as the thing it was observed on, and the thread rarely says
         * so outright.** Measured on this shape's own assembled prompt: the drafts dated the
         * observation wherever a turn called the thing lost in so many words, and carried it over
         * as present fact wherever the only evidence was a rebuild and a list of what came back,
         * hedging what followed from it rather than the premise itself. So the rule turns on what
         * the thread says happened to the thing rather than on whether anybody called it lost — the
         * stated case was not the failing one, and a rule keyed on the wording of a loss would have
         * landed on the half that already held.
         *
         * **The trigger is a condition with examples under it, not a list to be matched.** A closed
         * set of verbs is the construction [Reply.STATE] exists to replace: wiped, reimaged,
         * migrated and set up from scratch all say the same thing to a reader and none of them
         * would be on it, and a rule satisfied by reaching for the word nobody listed is not a
         * rule. So what is stated is that the thing the reading was taken on is not the thing
         * standing now, and the verbs follow as illustration.
         *
         * **Both ways out are statements, which is what keeps this clear of the undertaking rule.**
         * Offering to go and check is the obvious third one, and it is an undertaking the sender
         * has not made, so that rule and this one would pull against each other on a single line.
         * What is left is the observation's date, or that nothing since has confirmed it — and the
         * second of those has to say where it goes, because a remark about what the record holds is
         * what the rule above sends to a cited claim line outside the draft. Left unplaced it would
         * land there uncited, and an uncited claim line costs the whole variant. So the rule puts it
         * in the message, in the sender's own voice, which is the only place the counterpart reads.
         *
         * **Rounding up is the same fault in miniature, which is why it sits beside this rule.** A
         * list of parts restored read back as everything working, or one lifted restriction as none
         * of them, is a statement the material does not carry; both were measured on the same arm
         * as the stale premise. It is worded as saying what the material says rather than as a
         * demand for literal precision, because the register this shape is learning is a loose and
         * informal one and a rule written the other way would fight the thing that makes a draft
         * sound like the sender.
         *
         * **It is this shape's rather than the shared format's, because the remedy is.** Reading a
         * pre-rebuild observation as current is a corpus-reading fault any shape could commit, but
         * what the others would do about it is date a finding they cite; here the fix is how a
         * sentence somebody sends is worded, and the two rules the remedy leans on — no claims in
         * the prose, and no undertaking the sender has not made — exist only here. A rule in
         * [Reply.format] would reach the three shapes whose answers are their citations, where the
         * claims-only scope of the grounding check already holds the line.
         *
         * **The structured route is not what makes this work.** Which party a commitment obliges is
         * written where the extraction stage can place it, and this shape does not read it: the
         * shapes answering where things stand and what to say on a call mark that party on their
         * material, which is an improvement a draft could take up and not a prerequisite for one.
         * Taking it up here is a separate judgement, because a sendable message names a person in
         * the second person or not at all, and a marker that reads well in a report or a brief
         * reads as a label in a reply.
         */
        val INSTRUCTION = """
            You draft a reply for a person to send. Write complete messages, ready to send as they
            are, with no placeholders and no instructions to the sender.

            Produce two or three drafts that genuinely differ — in register, in length, or in how
            much they explain — so that the sender is choosing rather than approving. Do not
            produce variations of one sentence, unless the thread has left nothing open, which
            the rule below says what to do about.

            Where the thread leaves something open, the drafts differ in how they say it and not
            in how much of it they say. Everything the reply owes is in every one of them, so the
            sender can send whichever reads best and lose nothing another one had — and that asks
            for more than a re-worded sentence, since the same facts can be put briefly or at
            length, plainly or warmly, with more explanation around them or less. A draft that
            explains more does so in more words about the same things. What the difference must
            not be is a division of the material: a draft leaving out what another carries is no
            alternative to it, because choosing between them is choosing which fact to drop, and
            the sender is left writing one more message out of what you gave them. Whatever the
            reply owes is owed by each draft alike, whether it was the exchange being answered
            that left it open or something further back. Where the thread has left nothing open
            none of this applies, and what to write is settled below.

            A message is marked with who sent it, as "Sent by". The material spans several threads,
            some of them rooms this person writes in alongside others, so attribute a message to the
            person marked on it and to nobody else. A message carrying no "Sent by" was written by
            somebody the corpus does not name: do not attribute it to this person, and do not invent
            a name for it.

            You are composing as the sender, to the person the request names, and never the other
            way round. Where a "Sent by" name carries "the sender you are drafting for", that is
            who you are writing as. Where no name carries it, the sender wrote nothing the material
            reaches: compose for them anyway, and take the register from the material you have.
            The thread's last message is frequently the other party's, which is why there is a reply
            to write at all — it is not a sign that the reply is owed in the other direction. So an
            undertaking the sender already made is theirs to report on, not to ask about, and a
            question the other party asked is one to answer rather than to repeat back.

            Write in the language of the thread and in the register of business correspondence. Match
            how things have been written here, which the material shows you — the sender's own turns
            where it marks them, and the register examples, which are offered for their wording and
            carry the name of whoever wrote them. A reply that reads as machine-written is a failed
            draft even when its content is right.

            Material marked as a register example is a message from somewhere else in the corpus,
            offered to show how things have been written here. Take wording from it and nothing
            else. It says nothing about this person, so never reply to it, never state anything in
            it as fact about them, and never cite it for a claim about them.

            A turn marked as the sender's own is two things at once, and the second is what a draft
            is measured against. It shows you how the sender writes, which is the register to
            match. It also records what the sender has already said and who has read it: the
            people in the conversation it was said in. So take the wording from these turns, and
            weigh what they say as delivered to those people already rather than as material to
            compose from.

            A reply answers what the other party has left open, and the turns that make up the
            latest exchange are marked for you rather than left to be found: a block marked as
            being in the latest exchange in this person's threads is one of them. That is where the
            reply is aimed. Address what those turns asked of the sender and the sender has not
            since answered, and what they requested of them and the sender has not since met — a
            marked turn of the sender's own may be the answer to another marked turn, and where it
            is, that is settled and not something to answer again.

            An older request is not what this reply answers where a marked turn has since answered
            it, moved past it or made it moot, and it is not the reply's subject merely because it
            is the last thing you noticed being asked. What the mark does not do is put the rest of
            the material out of bounds: a request further back that nothing in the material meets
            is still owed, and the marked turns may be a stretch of a thread this person said
            nothing in, since what the mark follows is the latest exchange across their threads
            rather than the latest they wrote in. Where a marked turn carries the sender's own
            mark, the sender wrote last.

            All of the drafts are aimed at the same thing, and where the marked turns leave
            something open that is what they are aimed at. Something still owed from further back
            is then carried inside the reply to it rather than taken as one draft's subject
            instead: a draft aimed at a different question is no alternative to the others but a
            second reply, and offering it as one asks the sender to choose between two messages
            rather than between two ways of writing one. Where the marked turns leave nothing open
            and something further back is still owed, that is what every draft answers.

            Work out first, from the turns marked as the sender's own, what this recipient has
            already been told — a position stated, a commitment given, an explanation offered, a
            request met. None of it is delivered again. Weigh that against the conversation this
            draft is for: what the sender said in a thread its readers were not in is not something
            they have. You may refer to such a thing where a sentence needs it to stand up, and
            referring to it means naming it in as few words as that takes — never restating its
            content, its list or its detail, which they already hold in the message they were sent.
            A fact something since has put back in question is the exception, and is stated afresh.
            So each draft is built out of what this recipient does not have — an answer, a decision,
            what has changed since, or a question of the sender's own. That list is the kinds of
            thing to look for and not a choice between them: where the exchange leaves any of it
            still owed, what one draft carries of it they all carry. A message whose
            content is already-delivered material gives the other party nothing to read and nothing
            to reply to. Where the exchange leaves none of that to carry, what to write is settled
            below — and this bound holds there too: a confirmation names what it confirms and does
            not restate it.

            Every piece of material says when it was said, and that is what to go by for whatever
            the marks do not settle: the material is not one continuous conversation, it holds more
            than one thread with nothing marking where each begins, and the register examples are
            older than the recent turns they follow — so position in it is relevance and not time,
            and anything you place in time is placed by its own date and never by where it sits.
            Where no turn carries the sender's mark there is no way to tell which side answered
            what, so the marks say where the thread's live end is and no more than that: address the
            latest thing asked there and say no more than that.

            Where the latest exchange leaves nothing open, do not manufacture something to answer.
            Nothing is left open when nothing in that exchange is still waiting on the sender: no
            question from the other party the sender has not already answered, nothing they asked
            for that the sender has not met, and nothing the sender undertook and has not reported
            on. Which party wrote last does not decide it — the sender having written last is one
            way that is satisfied rather than the test for it. A turn of the other party's that
            answers what the sender asked earlier, or reports where something of their own stands,
            asks for nothing by itself. Where other people are in the thread and a question is put
            to one of them, it is not put to the sender: go by who a turn addresses, not by who sent
            it. Re-opening what the other party has already settled is the same failure as restating
            it: it asks them to decide again something they have decided.

            What is left to write is whatever has changed on the sender's side since, or a short
            confirmation and nothing beyond it. The alternatives are then the same short message
            written at different lengths — a bare acknowledgement against one that adds what has
            changed — and where that leaves them close to one another, that is the honest answer
            rather than a failure to differ. Inventing a difference by raising something is worse,
            and a draft that opens a subject so as to have something to say is that invention. Say
            in a claim line that the latest exchange asked nothing of the sender, citing the turn it
            ends in, so that short drafts read as the answer rather than as a thin reply.

            A draft is sent somewhere, so compose it for the conversation it will be sent in. A turn
            of this person's threads marked as said in a shared conversation, which the other people
            in it read, was written in front of those people; one of those turns with no such mark
            was said in a one-to-one thread with the sender and nobody else. A register example
            carries no such mark either and says nothing about any of this, since it comes from
            elsewhere in the corpus.

            Work out which conversation the latest exchange was in — the marked turns are all in
            that one — and write for that one. Where it
            is shared, the draft is a message other people will read: write it so that it reads
            correctly to the room, and say in a claim line which conversation it is for, citing a
            turn from that conversation, so the sender knows where to send it. Where the material
            names that conversation, name it; where it is marked only as a shared conversation, say
            that it is a shared one whose name the material does not give, and do not invent one.
            Where the latest exchange is a one-to-one thread, write it as a message to that person
            alone. Do not conclude anything about conversations you were not shown — say which one
            you wrote for, not how many there are.

            What belongs to one person does not go into a draft for a shared conversation. An
            address something was sent to, where a credential was put, a link that stands in for
            one, anything a reader would recognise as that person's own access: leave it out, and
            write what is needed without it. Where the shared conversation's own turns already
            carry it, repeating it discloses nothing that is not already there.

            The draft itself is composed text and carries no claims. If you need to state something
            about the history — what was agreed, what the record does and does not hold about it —
            put it in a separate claim line, cited, outside the draft.

            Never state as fact anything the material does not show. If the thread needs information
            you do not have, write the draft so that it does not depend on it.

            An undertaking is not yours to make. Whatever the draft says the sender will do binds
            them, and they are reading it to approve rather than to check, so the only one a
            draft may carry is one the material shows they already made — and then only where
            what is being answered turns on it, which is what keeps it clear of the rule above
            against restating what they have already said. Where no turn carries the sender's
            mark there is nothing to tell their undertakings from the other party's: carry none,
            and do not attribute one either way. Do not originate one: no offering to do work, to
            look into something, to send it on, to fix or to check it, and no telling the other
            party to leave a thing to the sender instead of doing it themselves.
            Watch the end of the message, which is where business correspondence reaches for a
            courtesy offer and where one nobody made reads as politeness. Asking for something is
            not an undertaking and is often the right close; saying what you will do once you have
            it is one, so the ask ends where the ask ends. Where the reply would need an
            undertaking the sender has not made, the draft stops short of it and says what is true
            instead — a shorter message, not a placeholder for them to fill in.

            A fact is only as current as the thing it was observed on. Before writing something as
            true now, read what the thread says happened to that thing since it was observed: where
            a later turn leaves it no longer the thing the reading was taken on, an earlier
            observation of how it behaved is no longer a statement about the present. Rebuilt,
            reinstalled, replaced, wiped, set up again from scratch or brought back up piece by
            piece are ways that happens and not the test — nothing has to say outright that the old
            state was lost. Say when it was observed, or that nothing since has confirmed it, in
            the message itself and in the sender's own words: it is what you are telling the other
            party, not a remark about the record, so it does not go in a claim line. Do not write
            the premise as current fact and hedge only what follows from it.

            Say what the material says, not the rounder thing it suggests. Where it names what was
            done, name that: parts brought back one at a time are not everything working, and one
            restriction lifted is not none of them.

            Where the last turn asks nothing and requests nothing — an acknowledgement, a note the
            other party made so as not to forget, a clarification of something they themselves said
            — writing back that it is understood is not worth sending, and two or three wordings of
            it are not a choice. Answer instead with what is still outstanding between the two of
            them in this thread: something undertaken that nothing reports as done, an arrangement
            made and never confirmed, a question of the sender's that went unanswered. Name it, in
            the sender's own words. That is then what every draft carries, and they still differ
            only in how they say it. Where the thread holds nothing outstanding at all, write that
            there is nothing waiting — still as the sender writing to them, not as a remark about
            what the record holds.
        """.trimIndent()
    }
}

/**
 * The thread, the history with this contact, how comparable replies were written before, and who
 * wrote each of those turns.
 *
 * A fixed set of reads, with no model in any of them. The one that makes the drafts sound like the
 * sender rather than like a model asks the index for past exchanges resembling this one, which is
 * where the register is learned from — an ordinary ranked lookup whose question is the thread's own
 * recent text rather than something generated.
 *
 * **The two kinds of material it gathers are not the same kind of thing, and both say so.** The
 * threads are what was said to and by this person, each turn naming whoever wrote it; the ranked
 * hits are examples of wording from wherever the corpus had them, and are marked as that. Offered
 * alike they read alike, and a draft then replies to somebody else's conversation.
 *
 * **It also says which of those turns the reply is to.** Retrieving the latest exchange is not the
 * same as offering it as findable: what it holds is a set this stage can compute and a model has to
 * reconstruct by ordering the whole prompt — see [latestExchange].
 */
private class RetrieveDraft(
    private val retrieval: Retrieval,
    private val lookup: Lookup,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        val subject = exchange.subject ?: return Progress.Carried(exchange)

        val conversations = retrieval.conversationsOf(subject, THREADS)
        val recent = retrieval.recentMessages(conversations, MESSAGES)

        // Comparable replies, asked for with the subject's own recent words rather than with the
        // request's. What was said to this person is what a comparable exchange resembles; the request
        // is an instruction to answer it and resembles nothing in the corpus.
        //
        // **The latest words across the threads, chosen by time rather than by position.** The reads
        // come back grouped by conversation in the order they were asked for, so taking from either
        // end of the list takes from one thread: the front is the oldest of the most relevant thread,
        // and the back is the newest of the least. Neither is what is being replied to.
        //
        // Read off the messages rather than off the material, so the question is what was said and
        // not the labels this layer puts around it: embedded with them, the index would be ranking
        // the corpus against a list of names, which finds whoever is mentioned most rather than the
        // exchanges this one resembles.
        val latest = recent
            .filter { !it.text.isNullOrBlank() }
            .sortedByDescending { it.sourceTime }
            .take(SAMPLE)
            .reversed()

        val comparable = lookup.widened(
            question = latest.joinToString("\n") { it.text.orEmpty() },
            limit = COMPARABLE,
            around = AROUND,
        )

        // What the corpus holds about every turn this shape has in hand, in one read for both kinds
        // at once. **This shape reads more threads than any other and is the one that needs it
        // most**: the threads past the first are rooms the counterpart happens to write in, so an
        // unattributed stretch of one of them is read as the counterpart's own words — a draft
        // replying to things they never said, cited correctly and passed clean by the grounding
        // gate.
        //
        // The ranked hits are in the same read, keyed by their record: the index stores no author
        // and no conversation, so a turn reached through a ranking has nothing to name it with and
        // nothing to place it by until this is asked.
        val turns = retrieval.turnsOf(
            (recent.map { it.externalId } + comparable.map { it.id.record }).distinct(),
        )

        // **A ranked hit from one of these threads is this subject's material and the rest is
        // register, and the lookup cannot tell them apart.** Its question is the thread's own recent
        // words, so the corpus's nearest neighbours are frequently the same counterpart's older
        // turns — the ones this read's own window left behind. Marked as register they would be
        // offered under an instruction that forbids stating them as fact or citing them, which
        // suppresses the subject's own history; left unmarked, a stranger's exchange is read as
        // evidence about them. So the two are told apart by where each was said.
        //
        // **What that does not reach is a thread this request did not read**, a sixth conversation
        // of the same counterpart being the case: it is the subject's own material and is marked as
        // register all the same. The bound is the shape's own, and widening it would be a different
        // decision about how much history a draft reads.
        //
        // A span the thread read already is deduplicated rather than decided here: `distinct` keeps
        // the first of a repeated identifier and the thread comes first, so for the window itself
        // the live body wins and the index's copy of it is dropped.
        val threads = conversations.toSet()
        val inLatest = latestExchange(recent, turns)
        return Progress.Carried(
            exchange.copy(
                retrieved = Material.distinct(
                    recent.mapNotNull { message ->
                        val turn = turns[message.externalId]
                        Material.of(
                            message,
                            turn?.author,
                            own = Material.OwnTurn.SENDER.takeIf { turn?.authorIsSelf == true },
                            // **Absent only where the turn itself is, which the window cannot
                            // produce.** The channel arrives on the same row as the author and the
                            // read inner-joins the conversation, so every message this window
                            // returned has one — the nullable here is the turn's and not the
                            // channel's. That matters because the instruction gives a missing
                            // channel the positive meaning *said one to one*: were the two
                            // genuinely independent, nothing would separate a private thread from
                            // one this read could not place, and the safe reading would have to be
                            // asserted rather than inferred. A turn carrying no author is a
                            // different case and reaches here with its channel intact.
                            channel = turn?.channel,
                            latest = message.externalId in inLatest,
                        )
                    } +
                        comparable.map { span ->
                            val turn = turns[span.id.record]
                            if (turn != null && turn.conversation in threads) {
                                // The sender's own turn from a thread this read followed is what
                                // the register is learned from, so the mark has to survive the
                                // ranked route as well as the windowed one — and so does who else
                                // reads the thread it came from, this route reaching the same rooms
                                // the window does.
                                //
                                // **The latest-exchange mark is the one that does not come this
                                // way, and that is a property of the set rather than an omission.**
                                // It is computed over the window, so every turn carrying it is a
                                // turn the window returned — and `distinct` keeps the window's copy
                                // of a record reached twice. A hit this branch renders alone is
                                // therefore one the window left behind: either from the exchange's
                                // own thread, where the window is that thread's tail and anything
                                // outside it is older, or from another thread, where it is not the
                                // exchange whatever its instant. Only the first of those is older
                                // by construction, and neither is a turn the mark belongs on.
                                if (turn.authorIsSelf) {
                                    Material.authoredBySelf(
                                        span,
                                        turn.author,
                                        Material.OwnTurn.SENDER,
                                        turn.channel,
                                    )
                                } else {
                                    Material.authored(span, turn.author, turn.channel)
                                }
                            } else {
                                // A register example is deliberately left unmarked even when the
                                // sender wrote it: the instruction forbids replying to one, and a
                                // turn marked as the sender's *and* as not-evidence is two rules
                                // pulling against each other on one line.
                                Material.asRegister(span, turn?.author)
                            }
                        },
                ),
            ),
        )
    }

    /**
     * Which of [recent] make up the latest exchange, as the records they are addressed by.
     *
     * **The question the instruction used to put to the model, answered here instead.** What a reply
     * answers is whatever the latest exchange left open, and a model told to find that exchange by
     * the instant on each block has to order the whole prompt to do it: this shape's material is
     * several threads' tails concatenated closest-first with the ranked register examples after
     * them, so the live thread's newest turn sits in the middle with older material on both sides.
     * That comparison was measured going wrong, and in the direction that reads as a working
     * answer: the thread's newest turns absent from every draft, which replied to an older request
     * those turns had overtaken. The set is exactly computable from what was retrieved, so it is
     * computed and marked rather than asked for.
     *
     * **One thread, which is the thread holding the newest turn, and that is a narrower claim than
     * the mark's wording would bear.** A draft is one message sent to one place, so the exchange it
     * answers is in one conversation — the same conversation the channel rule composes for, reached
     * here by the same route, which is what keeps the two from naming different threads. What
     * decides which thread is recency alone, and recency across threads is not the same question as
     * which thread is *with* this person: the read deliberately ranks a one-to-one thread above a
     * room however quiet it has been, and this does not. So on a subject whose rooms are busier
     * than their own thread, the mark can land on a stretch of a room in which this person said
     * nothing — which is why the label says the exchange is the latest *in this person's threads*
     * rather than with them, and why the instruction aims the reply at the marked turns without
     * putting anything else out of bounds. Narrowing it to the subject's own turns is now a question
     * this shape could ask — [io.github.ragnorrs.mainbrain.domain.answer.Turn.authorIsAnotherParty] answers
     * it where a caller passes the subject's contacts, which this one does not — so what stands
     * between the mark and the narrower reading is a decision about drafts rather than a fact
     * nothing can supply.
     *
     * **It runs from after the sender's own turn that precedes what is being answered, because that
     * is what *left open* means.** Everything said since the sender last wrote is what they have
     * not answered; the boundary is read off the record's own marks rather than off a gap in the
     * clock, so there is no tolerance here to be wrong about.
     *
     * **The boundary is taken before the newest turn that is not the sender's own, not before the
     * newest turn.** A sender who signs off in three messages is ordinary, and a boundary at their
     * previous turn would then mark the last of those three alone and drop the turns it answers. So
     * what is marked is that answered turn and everything after it, which is the exchange — and a
     * marked turn carrying the sender's mark is precisely how the model is shown that they wrote
     * last. Where every turn of the thread is the sender's own there is nothing of the other
     * party's to answer, and the mark falls on their newest turn alone, which says the same thing.
     *
     * **Blank turns are kept for the boundary and dropped from the mark**, and the asymmetry is the
     * point. A photograph or a captionless file is a record and not something a reply can answer,
     * so one must not be marked — but a sender who answers with the file itself has answered, and a
     * boundary blind to that turn would mark the request they just met as still open while showing
     * no turn of theirs, which reads as the request being unanswered *and* the sender silent.
     *
     * Where no turn of that thread carries the sender's mark the boundary is absent rather than
     * nought, and what is marked is the thread's bounded tail — which side answered what is
     * unknowable there, so what the mark says is where the live end of the thread is and no more.
     *
     * **At most [EXCHANGE] turns**, so the clause still tells one stretch of the material from the
     * rest; the newest end is what is kept. The instruction is written to suit that: the marked
     * turns are where the reply is aimed rather than the only turns it may answer, so a request
     * further back that nothing has met is not excluded by the bound.
     *
     * Ties on the instant fall to the order the read returned, the sort being stable — so two turns
     * stamped in the same second are ordered as the store ordered them rather than arbitrarily.
     *
     * Empty for a contact with no history, there being no exchange to mark, and empty for a newest
     * turn the read could not place: the window inner-joins the conversation, so that cannot arise
     * from it — and marking an unplaceable turn would be asserting a thread for the channel rule to
     * compose for while carrying no channel, which that rule reads as a private one.
     */
    private fun latestExchange(recent: List<Message>, turns: Map<ExternalId, Turn>): Set<ExternalId> {
        val newest = recent.maxByOrNull { it.sourceTime }
        val thread = newest?.let { turns[it.externalId]?.conversation } ?: return emptySet()
        val inThread = recent
            .filter { turns[it.externalId]?.conversation == thread }
            .sortedBy { it.sourceTime }
        val answered = inThread.indexOfLast { turns[it.externalId]?.authorIsSelf != true }
        val from = if (answered < 0) inThread.lastIndex else boundary(inThread, turns, answered) + 1
        return inThread.drop(from)
            .filter { !it.text.isNullOrBlank() }
            .takeLast(EXCHANGE)
            .mapTo(mutableSetOf()) { it.externalId }
    }

    /**
     * The sender's own last turn before [answered], or nought less one where they have none.
     *
     * Separate so that the two readings of *the sender's turn* stay apart: this one is the boundary
     * and takes every turn into account, while what may be marked drops the blank ones. One
     * function doing both is what let a captionless reply read as no reply at all.
     */
    private fun boundary(inThread: List<Message>, turns: Map<ExternalId, Turn>, answered: Int): Int =
        inThread.subList(0, answered).indexOfLast { turns[it.externalId]?.authorIsSelf == true }

    private companion object {
        /**
         * Conversations followed per contact.
         *
         * More than the status shape reads, because register is what this shape is learning and it
         * is learned across threads rather than within one.
         */
        const val THREADS = 5

        /** Messages read from the tail of each conversation. */
        const val MESSAGES = 60

        /**
         * The most turns the latest exchange may be marked over.
         *
         * **A bound on the marker rather than a definition of the exchange**, which the sender's own
         * previous turn already settles. What it protects is the only property the marker has: a
         * clause distinguishes a stretch of the material from the rest of it, and one carried by a
         * sizeable share of the window distinguishes nothing — which is the heading problem again in
         * a second wording. A counterpart who writes a great many turns without the sender
         * answering is the case it bites on, and the newest of them are the ones kept.
         */
        const val EXCHANGE = 15

        /** How much of the thread's tail is used as the question for the comparable lookup. */
        const val SAMPLE = 10

        const val COMPARABLE = 12

        /**
         * Neighbours fetched either side of a comparable hit.
         *
         * A reply is only a model of register with the message it answered beside it, so this is
         * wider than a plain lookup would need: the pair is the unit, not the hit.
         */
        const val AROUND = 2
    }
}
