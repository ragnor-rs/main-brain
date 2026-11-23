package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import java.time.Instant

/**
 * The reads the answer path makes, which no other caller makes.
 *
 * **Its own port rather than more functions on the record and entity repositories, and the
 * reason is that these are different questions about the same tables.** A repository here serves a
 * derivation pass: it reads a batch of records by identifier, or everything in one conversation,
 * because that is the unit of work a pass has. An answer reads the *tail* of a conversation, the
 * threads a person appears in, and what was promised inside a stretch of time — bounded, ordered,
 * and shaped by what a prompt can carry. Putting both sets on one interface would mean every fake of
 * it implements reads its own test has no use for, and the derivation passes would carry the answer
 * path's concerns in their collaborators.
 *
 * It follows [SubjectSearch], which is the same judgement applied to resolution: the read is the
 * unit, not the table.
 *
 * **Everything is bounded, and the bound is a parameter rather than a constant here.** How much
 * material to carry is the shape's decision — a draft learns register across more threads than a
 * status report needs — so the limit belongs to the caller, while the ordering that makes a limit
 * meaningful belongs here.
 */
interface Retrieval {

    /**
     * The conversations worth reading for [subject], the closest to it first.
     *
     * One read rather than three, because the three are a join: a subject's referents are contacts,
     * clients and conversations, and resolving each kind to the conversations behind it is one query
     * against the store and three round trips from here. A contact is reached through the person
     * record behind it, a client through the conversation it was read from, and a conversation is
     * itself.
     *
     * **A body of work reaches nothing of its own and needs nothing here.** It is composed from its
     * strands and they travel with it as [Subject.context] — each a client and the conversation that
     * client was read from — so the three kinds above already reach every thread of it. What that
     * costs is that the result grows with the number of strands, as it grows with the number of
     * clients a composed subject reaches, and only the per-conversation bound a shape applies to the
     * messages is below it.
     *
     * **[threads] bounds what each contact contributes, not the result.** A composed subject reaches
     * through more than one referent, and an overall bound would let the first contact's history
     * crowd out the second's — which is the opposite of what composing a hierarchy is for.
     *
     * **Ordered by how close a thread is to the subject, and only then by recency.** A conversation
     * somebody named comes first; then, for a person, the one-to-one conversation with them, however
     * long it has been quiet — it is the thread that is *with* the subject rather than one they
     * happen to write in, and under a bound of a few threads recency alone would let rooms crowd it
     * out. Everything else is most recently active first. Empty for the inbox, which has nothing
     * behind it: that is its scope rather than a failure to resolve it.
     */
    suspend fun conversationsOf(subject: Subject, threads: Int): List<ExternalId>

    /**
     * The last [perConversation] messages of each of [conversations], in conversation order within
     * each.
     *
     * The tail rather than the whole thread, because a prompt holds a bounded amount and a
     * conversation does not. Oldest-first within a conversation even though it is the newest that
     * are selected: a stretch of conversation read backwards is a different conversation, and
     * reversing at the call site is a step one caller will forget.
     */
    suspend fun recentMessages(conversations: List<ExternalId>, perConversation: Int): List<Message>

    /**
     * What was recorded as promised within [reach], most recently evidenced first.
     *
     * **Stated by, which is not owed by or owed to.** The author of the message is not the party a
     * claim obliges, so this read orders and narrows by who stated a thing. Which party is obliged
     * comes back on the row where the extraction stage could place it — see
     * [io.github.ragnorrs.mainbrain.domain.entity.Commitment.obligedParty]. Nothing here narrows by it, and
     * where it carries nothing the claim's prose is the only account of direction there is. Where it
     * carries a party, the shapes that read this port mark that party on the material beside the one
     * that stated the thing, rather than leaving direction to be read out of the words.
     *
     * **None of these is known to be outstanding, and that survives closure recognition.** What
     * [closuresOf] adds is the other half: a commitment something in the corpus is recorded as
     * having discharged. The absence of such a record is not evidence of the opposite, because a
     * promise is frequently kept in silence and produces no closing message at all — so this read
     * answers what was promised and reading a commitment here as *still open* is the caller's
     * inference rather than the store's claim.
     *
     * **Recency is the only ordering this read has, which is not the only ordering an answer may
     * have.** What fell due last week is routinely what was agreed months ago, so a caller that
     * leads with what fell due re-orders by that — see `Material.owedFirst`, which is beside the labels
     * deciding the same standing rather than here, since the deadline is on the row and ordering by
     * it in SQL would put the day boundary in two places.
     */
    suspend fun commitmentsOf(reach: Reach, limit: Int): List<Commitment>

    /**
     * Events within [reach] that fall inside [horizon], soonest first.
     *
     * **Filtered on when the event happens, not on when it was mentioned.** A date read out of a
     * message two years ago can be next week, and filtering on the mention would miss it while
     * surfacing every passing reference made recently. An event with no date is excluded here and
     * read by [eventsUndated] instead: there is nothing to place it in time with, so admitting it
     * to a window would put every undated mention under every horizon — and leaving it out of both
     * reads would make it unreachable, this being the only way events are found.
     *
     * **Whether these are the events still to come is the caller's half of the contract.** Nothing here
     * knows where now is: this read applies the window it is given and orders ascending, so it answers
     * *what comes next* only for a window that starts at now. A window spanning now would spend the
     * whole limit on the oldest rows in it.
     *
     * **Separate from [eventsPast] so that neither can crowd the other out.** One read over a window
     * spanning now spends its limit on whichever side is busier — a subject with a full diary last
     * month then returns nothing upcoming at all, which is the one thing a question about what happens
     * next is asking for. Two bounds cannot starve each other.
     */
    suspend fun eventsAhead(reach: Reach, horizon: Horizon, limit: Int): List<Event>

    /**
     * Events within [reach] that fall inside [horizon], most recent first.
     *
     * The same date rule as [eventsAhead] and the same division of the contract — ordering is here,
     * the window is the caller's — turned the other way: what matters about the past is what happened
     * last. A dated thing that has been and gone is part of where a subject stands, so it is read
     * rather than filtered out, under its own bound for the reason [eventsAhead] gives.
     */
    suspend fun eventsPast(reach: Reach, horizon: Horizon, limit: Int): List<Event>

    /**
     * Events within [reach] whose moment could not be read, most recently first mentioned.
     *
     * **No horizon, because there is nothing to apply one to.** The two dated reads select on when
     * the event happens, so an event carrying no moment satisfies neither bound and would be
     * reachable through nothing at all — recognised, stored, evidenced, and absent from every
     * answer. This is what reaches it, and it is deliberately not a widening of those two: an
     * undated event admitted to a window would be offered as having happened inside it, which is a
     * wrong-window answer that cites correctly and reads as sound.
     *
     * **Ordered by when it was first mentioned, which is the only time it has.** The row carries
     * the earliest span that evidenced it, so a thing raised in January and raised again last week
     * sorts by January. That is the ordering [commitmentsOf] uses, this read taking no horizon for
     * the same reason that one does not, so the two are read the same way rather than each
     * inventing an order.
     *
     * **The caller owes the reader the distinction.** These are not events whose moment is now, or
     * soon, or unknown-but-probably-recent: they are mentions of something happening with no time
     * attached. Composed beside the dated ones without saying so, they would read as part of the
     * same diary — so whatever renders them has to say that no moment was stated, and a small limit
     * is the honest size for material that cannot be placed.
     */
    suspend fun eventsUndated(reach: Reach, limit: Int): List<Event>

    /**
     * What is recorded as holding about [reach], most recently evidenced first.
     *
     * **The window travels with the statement rather than being applied here, and that is the whole
     * decision.** Filtering to what holds now would hand the composition a bare value and no way to
     * say as of when — and a value read out of the wrong stretch of time is a real fact with real
     * evidence, so it cites correctly and the grounding check passes it. An answer that is fluent,
     * specific and wrong about *when* is the failure this read is shaped to avoid, so the period is
     * carried into the material and the model is told what it means.
     *
     * **Which also means this read answers two questions rather than one**: what holds about a
     * subject, and what used to. Both are answered from the same rows, because the alternative is a
     * read per tense and an answer that cannot say a fact changed.
     *
     * **Nothing is read for [Reach.InConversations].** A statement's subject is a contact here: only
     * a fact stated about its own author is stored, so there is no statement a conversation reaches
     * that a contact does not, and a conversation says nothing about whom a fact is about. Reading
     * every statement in a thread would answer a question about a client with facts about whoever
     * happened to speak in it.
     */
    suspend fun statementsOf(reach: Reach, limit: Int): List<Statement>

    /**
     * The corpus's name for each of [parties] that can be named, keyed by the contact asked about.
     *
     * **A finding carries its party as an identifier, and this is the only thing that turns one into
     * something a reader can see.** Recognition records who stated a commitment or an event as a
     * contact, deliberately: a model shown identifiers writes them into claims, and a label computed
     * from the window a finding was read in is reassigned the moment that window grows. So the party
     * survives as an identifier and naming it is this layer's job.
     *
     * **A contact with no name of its own takes its human's**, resolved through the merge like every
     * other read here. An account that stated something frequently carries no profile name while
     * another account of the same human does, and naming only the account would drop a name the store
     * holds. The merge is trusted because it is already trusted: the finding is in this answer at all
     * because the narrowing resolved the subject to a whole cluster, so a wrong merge misattributes
     * the claim before the name is reached, and refusing the name would not undo that.
     *
     * **A contact neither it nor its human ever named has no entry, and that is not a failure.** A
     * display name is whatever a human typed into a profile field, and the corpus does not always
     * carry one. An absent entry means an answer says what was undertaken without saying who by —
     * which is what the restating stage is already told to do where no name survives, so the two
     * halves agree rather than one inventing a placeholder the other would not recognise.
     *
     * **It names contacts and nothing else.** An attribute's subject is typed as the entity a fact is
     * about, which is a contact or a client, and only contacts are named here — a client is named by
     * the conversation it was read from and reaching that is a different read. A client identifier
     * passed here resolves to nothing for a structural reason rather than for the ordinary one above,
     * so a caller that could hold one must not read an absent entry as an unnamed person.
     *
     * In bulk because a page of findings reaches a handful of parties and one query per finding would be
     * a round trip per row rendered.
     */
    suspend fun namesOf(parties: List<EntityId>): Map<EntityId, String>

    /**
     * What the corpus holds about each of [messages] that it holds at all, keyed by the message.
     *
     * **A read beside [namesOf] rather than a widening of it, because the key is a different
     * thing.** A finding records its party as a contact identifier the derived layer minted, and
     * resolving one is that read; this answers a question about a record, and both ends of it are
     * the corpus's own identifiers. The name itself comes through the same contact and the same
     * merge, so the fallback lives in one place.
     *
     * **Keyed by the message rather than by its author, which is what lets every turn be named.** A
     * shape reading the tail of a conversation holds each message and so holds its author; a shape
     * reading the index holds a span, whose identifier names the record and nothing else — the
     * index stores no author. Keyed by the author, this read would serve the first kind of shape and
     * be unusable to the second, and the turns a ranked lookup returns would go on being offered
     * unattributed.
     *
     * **Asked once after the turns are in hand rather than per turn**, for the reason [namesOf] is
     * in bulk: a stretch of several threads reaches a handful of people, and one query per message
     * would be a round trip per line of the prompt.
     *
     * **[subject] is the contacts a turn's author is compared against, and it is what
     * [Turn.authorIsAnotherParty] answers.** A shape reading a person's threads reads rooms other
     * people write in — most of this corpus is such rooms — so a turn offered without that
     * comparison is read as the subject's own business. It is a parameter here rather than a read of
     * its own because the author's contact is already in hand on the row and the merge walk that
     * names the author is the walk the comparison needs: asking costs no round trip that naming did
     * not already cost, where a second port would fetch the same clusters again.
     *
     * Empty asks nothing, and the shapes that pass nothing are the ones not asking *about* a person
     * — the inbox among them, since a subject with nothing behind it has no contacts to compare
     * against. A shape that does ask about somebody and passes nothing anyway gets `null` on every
     * turn and marks none, which reads exactly like a corpus in which every turn is the subject's.
     *
     * A record this does not hold has no entry, which is the only way a key can be missing.
     */
    suspend fun turnsOf(
        messages: List<ExternalId>,
        subject: List<EntityId> = emptyList(),
    ): Map<ExternalId, Turn>

    /**
     * Which of [commitments] something in the corpus is recorded as having closed, and what did.
     *
     * **Only the closed ones come back, and a commitment absent from the result is not an open
     * one.** The store tells apart a closure found, an examination that found none, and a
     * commitment nothing has examined; the last two are the same thing to a reader, because
     * nothing recorded says this was discharged.
     * Collapsing them here is deliberate, because the difference is about what has been paid for
     * rather than about what is true of the promise, and a caller handed three states would have to
     * decide which two to word alike. What must not happen is either of them being worded as *open*
     * — see [commitmentsOf], which says why no commitment this port returns is known to be
     * outstanding.
     *
     * **The closing message travels with it rather than being fetched afterwards.** It is evidence
     * like any other and has to be citable, so whatever renders a closure needs the turn itself to
     * offer the reader — and a second read per closed commitment would be a round trip for a row the
     * same join already has in hand.
     *
     * In bulk for the reason [namesOf] is: a page of commitments reaches a handful of closures, and
     * one query per commitment would be a round trip per row rendered.
     */
    suspend fun closuresOf(commitments: List<EntityId>): List<Closed>
}

/**
 * What narrows an entity read.
 *
 * **A named set rather than a list whose emptiness stands for everything.** Reading across the whole
 * corpus is something a caller has to say, because the alternative is silent: a subject with no contact
 * behind it would take the unscoped read, a question about one client would be answered from every
 * client's commitments, and every citation would resolve so neither the grounding check nor a reader
 * could tell.
 *
 * The derived layer is keyed by entity and the corpus by what the source called things, which is why
 * there are two narrow cases rather than one: a commitment records who stated it as a contact, and
 * reaching the same commitment from a client means going through the conversation it was stated in.
 */
sealed interface Reach {

    /**
     * Everything the corpus holds.
     *
     * The inbox, and nothing else. It is the one subject with no entity and no record behind it, so
     * reading across everything is its scope rather than a failure to narrow.
     */
    data object Everything : Reach

    /**
     * What these contacts stated.
     *
     * **An empty list reads nothing.** A subject that resolved to no contact has not asked about
     * every contact.
     */
    data class ByContacts(val contacts: List<EntityId>) : Reach

    /**
     * What was stated inside these conversations, whoever said it.
     *
     * How a client or a conversation is answered: a client is a reading of one conversation's label,
     * so what was promised to it is what was promised in that thread. Unlike [ByContacts] this says
     * nothing about who spoke, which is the most that can be said for a subject that is not a person.
     *
     * **An empty list reads nothing**, for the same reason as [ByContacts].
     */
    data class InConversations(val conversations: List<ExternalId>) : Reach
}

/**
 * What a retrieved turn is, beyond the words a span already carries.
 *
 * **What the words leave out, because none of it can be had from a span.** A span names
 * its record and nothing else, so who wrote a turn, where it was said, who else reads what is said
 * there and whether its author is the reader all fail to survive a ranked read — and each decides
 * how the turn may be offered.
 *
 * [author] is the corpus's own name for whoever wrote it and never a record identifier, for the
 * reason recognition's own lines give: a model shown identifiers writes them into claims. `null`
 * where the message records no author, where that author has no contact behind it yet, or where
 * neither that contact nor its human was ever named. The three are one answer to a reader — the
 * turn is offered without a name, and the instruction tells the model not to invent one — which is
 * the convention a finding's absent party already follows.
 *
 * [conversation] is where it was said, and it is what tells material about a subject from material
 * retrieved for its wording: a ranked read is corpus-wide, so the same counterpart's older turns
 * come back through it as readily as a stranger's, and the two must not be offered alike.
 *
 * [channel] is who else was there, and **it is a different question from [conversation] rather than
 * a reading of it.** The identifier says which thread, which is what a caller compares against the
 * threads it followed; this says whether a message sent to that thread is read by one person or by a
 * room, which is what a shape composing a message to send has to know and no amount of comparing
 * identifiers answers. It is required rather than defaulted because there is no safe value: a room
 * taken for a one-to-one thread is a draft addressed privately to a person in front of three
 * others, and that is the one failure a default would make silent.
 *
 * [authorIsSelf] says this turn is the archive owner's own. **What needs it is an answer addressed
 * to one of the parties, which is not the same as one composed as a party and is the wider of the
 * two.** A shape that writes *as* somebody has to know which side of the thread it is on, or a
 * reply to a counterpart who wrote last is composed in that counterpart's voice, which is a
 * message nobody can send. A shape writing *about* the corpus to the owner needs it for a
 * different reason and needs it just as much: everything that person did, received or sent they
 * already hold, so the corpus's silence about any of it is a gap in the record rather than in what
 * they know, and without this the answer asks them to confirm what they are holding. A shape
 * writing about the corpus to nobody in particular names every party in the third person and does
 * not read this at all.
 *
 * Read off the person records behind the author's whole cluster, not by comparing the author's name against
 * the owner's: two people sharing a display name is an ordinary state of this corpus, and a name
 * comparison would hand one of them the other's voice.
 *
 * **Three states on the record collapse to two here, and that is the right direction.** The record
 * keeps *ours*, *not ours* and *nothing is known* apart, because a source whose adapter cannot
 * report the account it authenticated as must not be read as having denied it. Neither reading has
 * a third option: a reply is either composed as this person or it is not, and an answer is either
 * handed to them or it is not. So an unknown author is false here, which is the conservative half
 * for both — it costs a draft the register of one unmarked turn, where the other way round costs
 * the draft its sender; and it costs a brief a question it need not have asked, where the other
 * way round hands the reader's own words to the person they are calling.
 *
 * [authorIsAnotherParty] says this turn was written by neither the subject the request asked about
 * nor the reader.
 *
 * **What it is for is telling a subject's own material from the business of the room it was said
 * in.** A person's threads are mostly rooms several people write in, so the tail of them carries
 * other participants' matters — and a turn offered with a name and nothing else is read as evidence
 * of where the subject stands, which cites correctly and passes the grounding gate, because that
 * gate asks whether a span was retrieved and not whose business it recorded.
 *
 * **It answers the question in one field rather than leaving a caller to combine two**, because the
 * two halves do not have the same number of states. The subject half is known wherever an author
 * resolves to a contact; the reader half is a three-valued mark on the record, and [authorIsSelf]
 * folds its unknown into `false` for a reader that has no third thing to do. A caller intersecting
 * them would publish an account whose adapter never reported itself as somebody else's — the
 * reader's own turns, demoted to the business of the room, which is a worse answer than the one
 * this field exists to fix. So the combination is made where all the states are visible.
 *
 * **Three states, and `null` carries every case where nothing can be said**: the caller asked
 * nothing by passing no contacts, the corpus names no author, the author resolves to no contact the
 * derived layer has reached, or nothing establishes whose account the author's is. Collapsing any of
 * those into `false` the way [authorIsSelf] collapses its own unknown would state a denial the
 * corpus has not established — and an unnamed turn already has a rule of its own, which says to
 * report it without attributing it to anybody. The subject's own accounts are never unknown here
 * however their ownership reads: a question about somebody has answered that question, and nothing
 * is being denied of them.
 *
 * Resolved per human through the merge rather than per account, as [authorIsSelf] is: a subject is a
 * whole cluster, and a comparison against the one identifier a subject resolved to would call a
 * person's second account somebody else. Never by comparing names — two people sharing a display
 * name is an ordinary state of this corpus, and the form a question writes a name in is frequently
 * not the form the store holds.
 */
data class Turn(
    val conversation: ExternalId,
    val channel: Channel,
    val author: String? = null,
    val authorIsSelf: Boolean = false,
    val authorIsAnotherParty: Boolean? = null,
)

/**
 * Who reads a message sent to the thread a turn was said in.
 *
 * **Two cases rather than one per kind the source distinguishes, because the question is who is
 * present and not what the container is called.** A topic inside a supergroup and a broadcast
 * channel are both places where somebody other than the two correspondents reads what is written,
 * which is the whole of what a composed reply has to account for; collapsing them here keeps the
 * one distinction that changes a draft and leaves the source's taxonomy where it belongs, on the
 * record. It follows from the question rather than from the present membership of
 * [io.github.ragnorrs.mainbrain.domain.record.ConversationKind], so a kind added there needs no case here.
 *
 * **A sealed pair rather than a flag, so that a room the corpus holds no name for cannot read as a
 * one-to-one thread.** Every kind but a direct exchange may carry a name somebody chose, and *may*
 * is not *does* — see [io.github.ragnorrs.mainbrain.domain.record.ConversationKind]. A nullable name
 * standing for both facts would make an unnamed room indistinguishable from a private thread, which
 * is the defect this type exists to prevent arriving by a different route.
 */
sealed interface Channel {

    /**
     * The thread is with one person, so a message sent to it is read by them and nobody else.
     *
     * What a two-person exchange displays is the counterparty rather than a name anybody assigned,
     * so there is nothing to carry here: naming it would offer a person's own display name as
     * though it were a channel somebody chose.
     */
    data object OneToOne : Channel

    /**
     * Other people are in the thread and read what is written there.
     *
     * [name] is what they call it, where the corpus holds one, and it is carried so that a reply
     * can say which channel it is for — a draft composed for one of several rooms is unsendable
     * until the sender knows which. `null` says the corpus holds no name, which is a room all the
     * same.
     */
    data class Room(val name: String? = null) : Channel
}

/**
 * A commitment the corpus records as discharged, and the message that records it.
 *
 * **Not a state on the commitment, which is the whole shape of this.** The finding is the reading of
 * one message and nothing later changes it, so what closure adds travels beside it — removing these
 * rows restores the prior reading exactly, and a re-derivation of the finding cannot drop the
 * closure with it.
 *
 * [by] is the turn that reports the thing as done, carried so that it can be offered to a reader as
 * something to look up rather than as an assertion this system makes.
 */
data class Closed(val commitment: EntityId, val by: Message)

/**
 * A stretch of time to read events out of.
 *
 * Its own type rather than a pair of instants, because a pair has an order that is easy to swap at a
 * call site and impossible to notice afterwards: reversed ends match nothing, and an empty result
 * reads exactly like a corpus holding no events.
 *
 * Named a horizon rather than a window because [io.github.ragnorrs.mainbrain.domain.recognition.Window] is a
 * stretch of a *conversation*, and two types called the same thing would be read as one idea.
 */
data class Horizon(val from: Instant, val until: Instant) {
    init {
        require(!until.isBefore(from)) {
            "A horizon ending before it starts covers no time at all, and would return nothing " +
                "while reading as a corpus that holds nothing."
        }
    }
}

/**
 * How an entity read should be narrowed for this subject.
 *
 * The one place that decides it, because getting it wrong is silent — see [Reach].
 *
 * A person is read by the contacts behind it. Anything else that is not the inbox is read through the
 * conversations it reaches, which is why [conversations] is a parameter — the caller has already
 * resolved them for the message read, and resolving them twice would be a second query for an answer
 * already in hand.
 */
fun Subject.reach(conversations: List<ExternalId>): Reach {
    val contacts = contacts()
    return when {
        focus.kind == SubjectKind.INBOX -> Reach.Everything
        contacts.isNotEmpty() -> Reach.ByContacts(contacts)
        else -> Reach.InConversations(conversations)
    }
}

/**
 * The contacts this subject reaches, focus and context together.
 *
 * Empty for every subject that is not a person. Callers want [reach] rather than this: on its own the
 * emptiness says only that nothing here is a person, which is not the same as being scoped by
 * nothing.
 */
fun Subject.contacts(): List<EntityId> =
    (listOf(focus) + context)
        .map { it.referent }
        .filterIsInstance<Referent.OfContact>()
        .map { it.contact }
        .distinct()
