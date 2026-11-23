package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.firstStated
import io.github.ragnorrs.mainbrain.domain.entity.lastStated
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant
import java.time.ZoneId

/**
 * Everything a shape retrieves, as the one thing the prompt and the grounding check both read.
 *
 * **A [Span] rather than each type's own rendering, and that is what makes the check cheap.** A
 * claim is grounded if everything it cites is in the retrieved set, and the set is only comparable if
 * every kind of retrieved thing has an identifier of the same shape. So a message and everything
 * derived from one — a commitment, an event, an attribute — all arrive here as spans, and the
 * identifier each carries is the span it is evidenced by rather than anything this layer minted.
 *
 * **A derived thing cites the span it was read out of, which is the whole point.** A commitment is
 * model output: an answer stating one as fact is grounded in an earlier inference, and the citation
 * is what turns it back into something the reader can check against the message it came from.
 *
 * It also means a derived thing and its own message share an identifier, and so do two findings read
 * out of one message — a message may state more than one, and each is shown under the span that
 * states it. [gathered] is what resolves that; [distinct] is for the different case of one record
 * read twice.
 *
 * **A finding is shown under the span it was first stated in and not under every span it holds.** One
 * obligation restated later is one finding with two pointers, and offering both would put the same
 * inference in front of the model twice under two identifiers. The restating message is retrieved on
 * its own account where it is relevant.
 */
// A function per way a piece of material may be offered, plus the two folds, so the count measures
// how many labels exist rather than how much any of them does — every one is a `buildString` and a
// `copy`. Splitting it would put the labels a grounding check compares in two files, which is the
// one property the class note says must hold in one place. Suppressed here as `JdbcRetrieval`
// suppresses it for a port's reads.
@Suppress("TooManyFunctions")
internal object Material {

    /**
     * [message] as a span, naming [author] where the corpus names them, or `null` if it carries no
     * text.
     *
     * A message with no text is a photograph, a sticker or a service notice: it is a record, and it
     * is not material an answer can be composed from or a claim can rest on.
     *
     * **[author] is who wrote the turn, and it comes from the entity layer for the same reason a
     * finding's party does.** A claim about what somebody said is checkable only if the material
     * says who said anything; a turn offered without that leaves the model the sender and the
     * subject as the only agents it can name, and a stretch of a room with five participants in it
     * is then read as the subject's own words. Nothing downstream catches it — the grounding gate
     * asks whether a cited span was retrieved and not whether it was the right span — so the
     * material is where it has to be stopped. What holds the property across the shapes is a
     * required parameter here and a case over all five in `ShapesTest`, not this function alone:
     * [closing] and [asRegister] attach the name themselves and either could drop it.
     *
     * **`null` says nothing rather than saying nobody**, as it does for a finding's party, and the
     * span is then the bare body. A person record the corpus never named has no name to give, and a
     * placeholder would be read as the author's name by the one thing that cannot check it. It is a
     * required parameter and not a defaulted one so that a shape retrieving turns has to decide
     * what it knows about their authors, rather than dropping them by saying nothing.
     *
     * **[own] is defaulted where [author] is not, and the asymmetry is the point.** Every shape
     * has an author to declare, so saying nothing about one is a shape that forgot. Only a shape
     * whose answer is addressed to one of the parties has an owner to declare: a shape writing
     * *about* the corpus to nobody in particular names every party in the third person, so a
     * required argument here would be most call sites asserting they are not the ones that care —
     * and a shape reaching turns through a ranking would not be among them, since it attaches the
     * name through [authored] and never arrives here at all. `null` is what *about* means, and it
     * is the answer for everything except the cases that have to opt in.
     *
     * **[channel] is opt-in for [own]'s reason and renders only a room**, which is the same
     * judgement [of] makes for a commitment's age: a shape that writes *about* the corpus has no
     * channel to compose for, and a marker on every turn of every answer would be a clause per line
     * of the prompt that no answer can use. `null` is what *this caller is not composing a message*
     * means.
     *
     * **Which makes `null` beside a sender's mark a contradiction, and it resolves to a room.** A
     * turn marked as the sender's own is a turn being composed from, so that caller has a channel
     * by definition. A caller reaching here with that mark set and no
     * channel has lost the fact rather than declined to carry it — so it is read as a room, which
     * is the direction every other reading of this fact fails in: a private thread called a room
     * costs a draft an audience it did not have, where a room called private is the defect itself.
     *
     * **It is not a contradiction beside the reader's mark, which is why the two arms differ.**
     * That mark says who the answer is being handed to, and nothing is being composed to anybody:
     * who else reads the thread decides nothing in a brief, and a room supplied on the reader's
     * every turn would be a clause per line carrying a fact that answer cannot use. So `null`
     * there means what it means everywhere outside a draft — there is no message with an audience.
     *
     * **[latest] says this turn is one of the latest exchange's, and it is opt-in for [own]'s
     * reason.** Only a shape composing a reply has a latest exchange to find: the shapes that write
     * *about* the corpus answer over the whole window they read, so which end of it is live is a
     * question their answers do not ask. `null`-equivalent here is *this caller is not selecting an
     * exchange*, and a caller that marks nothing leaves the model to find the newest block by
     * reading every heading — see [IN_LATEST] for why that is not a thing to ask of it.
     *
     * **[anotherParty] says the turn is neither the subject's nor the reader's**, and it is opt-in
     * because only a shape asked *about* somebody has a subject to compare an author against. False
     * is what *nothing is claimed about whose turn this is* means, which is the answer for a caller
     * that did not ask and for a turn the read could not place alike — see
     * [Turn.authorIsAnotherParty], which folds every such case for the same reason.
     *
     * **It is ignored under [ownWords], which is not a case it loses.** A turn marked as the
     * archive owner's own is the reader's by definition, so the two markers cannot both be true of
     * one turn, and a caller passing both has contradicted itself rather than described a turn. The
     * sender's mark is the more specific of the two and wins.
     */
    // One parameter per fact the material says out loud about a turn, and no caller asks for all of
    // them: the shape composing a message passes the sender's mark, the channel and the live
    // exchange, and the shapes reporting on somebody pass whose turn it is — the reader's label and
    // the third party's mark together, since a brief says both of a thread it reads. Folding the two
    // mutually exclusive marks into one three-valued parameter would carry the exclusion in the type
    // rather than in the note above, at the cost of rewriting the only call site that sets the
    // sender's mark.
    @Suppress("LongParameterList")
    fun of(
        message: Message,
        author: String?,
        own: OwnTurn? = null,
        channel: Channel? = null,
        latest: Boolean = false,
        anotherParty: Boolean = false,
    ): Span? = turn(message)?.let {
        when (own) {
            null -> authored(it, author, channel, latest, anotherParty)
            OwnTurn.SENDER -> authoredBySelf(it, author, own, channel ?: Channel.Room(), latest)
            // The asker's mark renders as the reader's does: both say *this turn is the
            // person being answered*, and only the words differ.
            OwnTurn.READER, OwnTurn.ASKER -> authoredBySelf(it, author, own, channel, latest)
        }
    }

    /**
     * A span already built, offered as the turn it is, naming [author] where there is one.
     *
     * **The same rendering [of] produces, for a turn reached through a ranking rather than through
     * its conversation.** A hit is a record identifier and a body: the index stores no author, so
     * the name is a read away rather than in hand, and a shape that skipped it would offer the
     * corpus's messages with nobody attached. [of] delegates here rather than composing the clause
     * itself, so a turn's two routes into the material cannot drift into two wordings for one fact;
     * [closing] and [asRegister] lead with a label of their own and join the name to it instead.
     *
     * Not restricted to a message by its own signature, because [SpanId] carries the kind and a
     * caller asking about a document would get nothing back from the name read and pass `null` here.
     *
     * **The name leads wherever there is one, and the room follows as a clause of its own.** The
     * instruction's attribution and voice rules are both written over the literal [SENT_BY] — they
     * say what a turn carrying it means and what a turn lacking it means — so a rendering that
     * demoted the name to a joining clause would tell the model that every turn in a room was
     * written by nobody the corpus names. Rooms are most of this corpus, so that is most of a
     * draft's material, and the consequence is the voice failure [authoredBySelf] exists to prevent
     * rather than a cosmetic one.
     *
     * **The room is still rendered where the corpus names no author**, which is why it is a clause
     * beside the name and not part of it. A turn nobody is named for carries no name clause at all,
     * so a room folded into that clause would vanish for exactly the turns an answer can say least
     * about — and vanish to the reading that composes the draft privately, in front of whoever else
     * is in the room. Hence the two forms, as [SENT_BY] and [SENT_BY_ALSO] already are for a name:
     * the leading one where the clause opens the block, the joining one where something precedes it.
     *
     * **Nothing is rendered for a one-to-one thread, and the instruction is what says so.** An
     * absent clause is read as the private case rather than as an unknown one, which it may be
     * because the kind is on the conversation row and arrives with the author's: a turn reaching
     * here at all was found, and the one caller that composes a message passes a room rather than
     * nothing where it has no turn to read. Saying it on every private turn instead would be a
     * clause on sixty lines of thread to carry a fact the model is told once.
     *
     * **[latest] is appended after the name and the room**, for the reason the room is appended
     * after the name: the attribution and voice rules are written over the leading [SENT_BY], so
     * anything put in front of it would tell the model that a marked turn was written by nobody the
     * corpus names. Which clause leads is therefore decided by what is present, in one place, and
     * every clause carries both a leading and a joining form so that any of them can be the one
     * that opens the block.
     */
    fun authored(
        span: Span,
        author: String?,
        channel: Channel? = null,
        latest: Boolean = false,
        anotherParty: Boolean = false,
    ): Span {
        val room = channel as? Channel.Room
        // Built first and the bare span returned where it comes back empty, rather than asked for
        // in a condition over every clause. One list of facts decides both whether there is a
        // label and what it says, so a clause added here cannot be forgotten in a guard that
        // would then drop it — which is the only way the two could disagree.
        val clauses = buildString {
            author?.let { append(SENT_BY).append(it) }
            // Directly after the name, because it qualifies the attribution rather than adding a
            // second fact about the turn: a reader of the block meets whose words these are and
            // whose they are not in one clause. The room and the exchange follow the pair, as they
            // follow the name.
            if (anotherParty) append(if (isEmpty()) ANOTHER_PARTY else ANOTHER_PARTY_ALSO)
            room?.let {
                append(if (isEmpty()) SAID_IN else SAID_IN_ALSO)
                it.name?.let { name -> append(ROOM_NAMED).append(name) } ?: append(A_ROOM)
                append(ROOM_IS_READ)
            }
            if (latest) append(if (isEmpty()) IN_LATEST else IN_LATEST_ALSO)
        }
        if (clauses.isEmpty()) return span
        return span.copy(text = "$clauses: ${span.text}")
    }

    /**
     * The same turn, marked as the archive owner's own words, in the role [own] names.
     *
     * **A shape whose answer is addressed to one of the parties needs this**, and it needs it on
     * the owner's turns
     * themselves rather than once in its instruction: the instruction is static prose and the
     * owner's name is corpus material, so naming them there would mean interpolating a fact into a
     * prompt that is otherwise the same on every request. Marking the turns puts the fact where
     * every other fact about a turn already is. A caller may still withhold it from a turn it is
     * offering as something other than the thread — a register example is offered for its wording,
     * and its call site says there why marking it would set two rules against each other.
     *
     * **The role is the caller's because the wording has to name what the model is doing**, which
     * is the whole of why the marker is safe at all — see [OwnTurn]. Two shapes put the owner in
     * two places, and one wording covering both would tell a shape writing a brief that it is
     * drafting.
     *
     * **It extends the name rather than composing a clause of its own**, which is what keeps
     * [authored]'s guarantee true: the leading clause is composed in one place, so the marked and
     * unmarked forms cannot drift into two wordings for one fact. The marker joins the name rather
     * than replacing it, because the name is still what the model attributes by — and the name
     * still leads the block, which is what the instruction's rule for finding this marker reads.
     *
     * A turn the corpus names no author for passes through unmarked and unattributed. The port can
     * know such a turn is the owner's — the mark is on the record layer and the name comes from a
     * reconciliation that may not have reached it — so what is lost here is real rather than
     * impossible: an owner's turn whose author has no contact yet is offered as nobody's. It is the
     * same gap the unattributed form already has, and the instruction's rule about unnamed turns is
     * what covers it; naming the sender while leaving the turn otherwise anonymous would be a
     * second kind of attribution for a reader to reconcile.
     *
     * [channel] is passed through rather than read here, so that who was present is said in one
     * place for the sender's turns and the counterpart's alike — the sender writes in the rooms as
     * well, and a mark that survived only the private thread would say the room was private.
     *
     * **[channel] is nullable here and still required, which are two different things.** It is
     * nullable because a second shape marks the owner's turns now and composes nothing: a brief
     * is handed to that person rather than sent for them, so there is no audience to get wrong
     * and nothing to say. It stays required because the argument against a *default* is
     * untouched — [Channel]'s own note says no value is safe to assume, and every caller here
     * knows the answer, so a default would spare nobody while letting a call site drop the
     * argument and compile. What keeps a draft safe is unchanged: [of] reads a sender's missing
     * channel as a room, and the draft's other call site passes the turn's own, so neither route
     * reaches here from a draft without one.
     *
     * [latest] is passed through for the reason [channel] is: the sender writes in the latest
     * exchange as well, and a mark that survived only the counterpart's turns would say the sender
     * had not written since — which is the one reading that decides whether anything is open at all.
     */
    fun authoredBySelf(
        span: Span,
        author: String?,
        own: OwnTurn,
        channel: Channel?,
        latest: Boolean = false,
    ): Span = authored(span, author?.plus(own.marker), channel, latest)

    /**
     * What the archive owner is to the answer being written, for the shape doing the asking.
     *
     * **Each wording says what the owner is for, rather than addressing them**, which is what
     * keeps a marker safe beside a turn: a bare *you* there would read as the turn being
     * addressed to the reader, and a model shown that answers the turn instead of working from
     * it. The sender's wording does that by naming the job the model has — somebody to draft
     * for — and the reader's by naming who the answer is handed to. The two are not
     * interchangeable, a brief being written *to* the owner where a draft is written *as* them,
     * so a shape marking its turns with the other one tells the model to do the other's job.
     *
     * Rendered where every other fact about a turn is rather than stated once in an instruction,
     * for the reason [authoredBySelf] gives: the owner's name is corpus material and the
     * instruction is the same prose on every request.
     */
    enum class OwnTurn(internal val marker: String) {

        /** The person the draft is composed as. */
        SENDER(IS_THE_SENDER),

        /**
         * The person the brief is handed to.
         *
         * **What this buys is the one thing a brief cannot work out for itself**: which party to
         * the conversation is going to read it. Everything that party did, received or sent, they
         * already hold — so the record's silence about any of it is a gap in the record, and a
         * question put to the other side about it asks somebody to confirm what the reader is
         * holding. The mark is on their turns and the name it extends is the name a finding's
         * party carries, which is what lets the two be read as one person.
         *
         * **It reaches a brief only where the owner wrote something the retrieval kept.** That is
         * nearly always true of a conversation they are a party to, and where it is not the
         * instruction's rules simply do not fire — which costs a question that should not have
         * been asked, and never suppresses an item.
         */
        READER(IS_THE_READER),

        /**
         * The person who asked.
         *
         * **Its own case rather than [READER]'s, because that one's wording names a brief** — the
         * marker a reader of a status reply would be shown says *the person this brief is for*, and
         * the rule forbidding the model to write that parenthesis out lives in the brief's own
         * instruction. Two shapes needing the same fact under different words is what an enum of
         * markers is for.
         *
         * **What it buys is the half of its shape's question that cannot be answered without it.**
         * Asked where things stand, a reader is asking two things: what the state of the work is,
         * and what is waiting on *them*. The second needs the party they are, and without it an
         * answer can only list obligations by party and say it does not know which party asked —
         * which is what it did.
         */
        ASKER(IS_THE_ASKER),
    }

    /**
     * The turn [closed] was read out of, marked as the thing that closed a promise.
     *
     * **The same span a plain read of the conversation would produce, with a marker and its own
     * time.** The marker is what lets a reader find it among the recent turns, and the time is
     * what pairs it with the commitment whose label names the same one — without that, an answer
     * reporting two promises as done has nothing to say which turn closed which, and either
     * citation passes the grounding check.
     *
     * **Printed in [zone] like every other time of a message, which is what keeps the pairing a
     * pairing.** This turn's own block heading dates it, [state] prints the same time in the
     * commitment's label, and all three now read alike — where an instant here left a reader and a
     * model matching two renderings of one moment that differ by a day for the hours either side
     * of local midnight. The two sides move together, so the literal the instruction tells the
     * model to match on is still one literal.
     *
     * [author] carries the same meaning and the same reasoning as it does for a plain turn — see
     * [of] — and is rendered here rather than by that function because the marker and the instant
     * come first: a turn labelled twice over reads as two labels, and the clause has to join the one
     * this function wrote.
     *
     * [own] carries the same meaning it does for a plain turn, and is here so that a shape marking
     * the owner's turns marks all of them: a closing message is a turn of the thread like any
     * other, and one offered unmarked beside a marked one says the owner wrote the second and not
     * the first.
     *
     * **[anotherParty] is carried for the reason it is carried on a plain turn, and leaving it off
     * here would make the marker's absence lie.** A promise is frequently reported done by somebody
     * other than the party who gave it, so a closing turn is as likely to be a third party's as any
     * other; once the recent turns carry the mark, an unmarked turn says the subject or the reader
     * wrote it. And a closure inside the retrieved tail is rendered twice under one span identifier,
     * which [gathered] joins — so a mark on one rendering and not the other would put one record in
     * front of the model marked and unmarked at once. What the mark does not do is weaken the
     * closure as evidence: it is about who wrote the turn, not about what the turn reports, which is
     * the bound the instruction states.
     *
     * **It is said of the name and only where there is one**, which is where [of] and this part
     * company. There the marker may open the block, because the attribution clause is the block's
     * first clause and the marker belongs to it. Here a label and an instant always come first, so
     * the clause is a relative one joining the name — and with no name there is nothing for it to
     * qualify. Nothing is lost by dropping it: a turn the corpus names nobody for is already
     * covered by the instruction's rule for an unnamed turn, which says to report what was said
     * without attributing it to anybody, so there is no attribution to deny.
     *
     * `null` where the turn carries no text, which cannot happen for a closure the pass recorded:
     * the window it was read from holds only messages with something in them.
     */
    fun closing(
        closed: Closed,
        author: String?,
        zone: ZoneId,
        anotherParty: Boolean = false,
        own: OwnTurn? = null,
    ): Span? = turn(
        closed.by,
        buildString {
            append(CLOSING_TURN)
            append(Moment.written(closed.by.sourceTime, zone))
            author?.let {
                append(SENT_BY_ALSO).append(it).append(own?.marker.orEmpty())
                if (anotherParty) append(ANOTHER_PARTY_ALSO)
            }
            append(": ")
        },
    )

    /**
     * [span] marked as material for how something was written rather than for what it says.
     *
     * **Attribution is not the whole of the failure, and this is the other half.** A shape that
     * widens its retrieval with a ranked lookup for comparable past exchanges is asking the corpus
     * how things have been worded, not what is true of the subject — and spans arriving
     * indistinguishable from the subject's own thread are read as facts about them. Naming the
     * author would not fix it: a correctly attributed message from an unrelated exchange is still
     * not evidence about this person.
     *
     * **Which turn is unrelated is the caller's to decide and not this function's.** A ranked read
     * is corpus-wide, so it returns the subject's own older turns as readily as a stranger's, and
     * marking those would suppress the subject's own history under an instruction that forbids
     * citing it. The caller knows where each turn was said — see [Turn] — and offers only the rest
     * through here.
     *
     * Marked rather than left out, because the register is what the lookup is for, and marked
     * rather than put under a heading, because a shape that marks this does not section its answer:
     * dividing a reply somebody has to send is a different decision and it stands.
     *
     * **[author] is named all the same, and the two say different things.** The marker denies that
     * the span is evidence about the subject; the name says whose words are being shown as an
     * example, which is the difference between the sender's own register and somebody else's.
     * Withholding it would make this the one turn in the material that says nothing about who wrote
     * it, which is the rule this card exists to establish rather than an exception to it.
     */
    fun asRegister(span: Span, author: String?): Span = span.copy(
        text = buildString {
            append(REGISTER_EXAMPLE)
            author?.let { append(SENT_BY_ALSO).append(it) }
            append(": ")
            append(span.text)
        },
    )

    /**
     * One message as a span, under whatever [label] the caller is offering it as.
     *
     * Shared by the renderings above so that a turn is addressed and timed in one place: the
     * identifier is what grounding compares and the instant is what places the material, and two
     * copies of either would agree only until one of them was edited.
     *
     * The label defaults to none for the plain turn, whose author is put on afterwards by
     * [authored]. A renderer that has already written something of its own joins the name with
     * [SENT_BY_ALSO] instead of leading with it, so the clause is composed in two forms — the
     * leading one in exactly one place, and the joining one wherever a label comes first. Marking a
     * turn as the sender's extends the name it leads with rather than adding a third form.
     */
    private fun turn(message: Message, label: String = ""): Span? =
        message.text?.takeIf { it.isNotBlank() }?.let { text ->
            Span(
                id = SpanId(kind = RecordKind.MESSAGE, record = message.externalId),
                text = "$label$text",
                sourceTime = message.sourceTime,
            )
        }

    /**
     * [commitment] as a span, marked as the inference it is, naming [party] where there is one.
     *
     * The hedge travels with it rather than being dropped, because a hedged promise presented as a
     * flat one is the failure mode this layer is most prone to — and the marker is in the text
     * because that is the only part the model reads.
     *
     * **[party] is who stated the thing, and it comes from the entity layer rather than from the
     * claim.** A finding records its party as an identifier, so the only name inside the claim prose
     * is whatever the restating model wrote there — a real name where the corpus named the author,
     * and otherwise a label local to the window the finding was read in, which resolves to nothing
     * afterwards. Naming the party here puts a name beside the claim that a reader can hold the
     * system to.
     *
     * **`null` says nothing rather than saying nobody.** A contact the corpus never named has no
     * name to give, and a placeholder would be read as the party's name by the one thing that cannot
     * check it. It is a required parameter and not a defaulted one so that a shape retrieving
     * findings has to decide what it knows about their parties, rather than dropping them by saying
     * nothing.
     *
     * **A missing due date is said rather than left out**, which is the same rule the event
     * rendering below states and for the same reason: a span with no date reads as one whose date
     * was not worth repeating, so a model composing from it places the thing by the only other
     * date in front of it — when it was mentioned. Most commitments carry no date at all, and
     * recognition refuses two values it used to store, so this is the ordinary case rather than the
     * odd one.
     *
     * **[closed] and [now] are what turn a date into a state**, and both are required rather than
     * defaulted for the reason [party] is: a shape that retrieved commitments has to decide what it
     * knows about their outcomes. `null` means nothing in the corpus records this one as discharged,
     * which is not the same as its being open — see [state].
     *
     * **[zone] is the calendar every time this rendering says is said in, and it is required for
     * the same reason the shape that heads its material is given one.** Two of them are the time of
     * a message a block heading also dates — a closing turn's own time, and how long ago this was
     * last stated — and a reply dating one message two ways is a reader's error to find rather than
     * ours. **The other two are not a message's time at all**: the due date this prints, and the
     * day count [state] describes its standing with. They are here because they are printed beside
     * the first two and weighed against them, which is what a calendar of their own cost — see
     * [standing]. So this parameter decides which group an item is offered under and not only how
     * its times read. Defaulting it to UTC would make a shape that forgot look right everywhere
     * the deployment happened to be UTC and silently disagree with its own headings everywhere
     * else, which is the failure nothing downstream can see.
     *
     * **[owed] is who the claim obliges, and it is a different fact from [party] rather than a
     * better-sourced version of it.** The extraction stage can place a party on the row — see
     * [Commitment.obligedParty] — and until something rendered it, the only account of direction a
     * reader got was whatever the restating model happened to write into the claim's prose. Both are
     * carried because both are true and neither covers the other: a promise relayed by a third
     * person is stated by the relayer and owed by somebody else, and an answer that had only one
     * marker would have to guess which question it answered.
     *
     * **Both are rendered even where they name one person, which is not a redundancy to collapse.**
     * How often a row carries an obliged party, and how often that party is the stater, are
     * properties of `obliged_party` beside `stated_by` and are read off those two columns rather
     * than assumed here. Suppressing the marker wherever the two agree would discard the
     * distinction the pair exists for: the markers naming one person says the store placed
     * direction and placed it first-hand, while no marker at all says this layer has no account of
     * direction beyond the claim's prose. Those are different answers to the reader's question, and
     * only one of them can be read off a name appearing twice.
     *
     * **An absent marker is several things rather than one**, which is why it is read as *no
     * account of direction here* and not as *direction was never placed*: the row may carry no
     * obliged party, or carry one the corpus never recorded a name for, or carry one the caller's
     * name read did not reach. The claim's prose is the fallback in each, and the instruction is
     * what says so.
     *
     * **[ages] and [owed] are opt-in where [party] and [closed] are required**, because a shape
     * rendering a commitment has a stated-by party and an outcome to declare whatever it is
     * answering, while direction and age answer a narrower question — which party to raise a thing
     * with, and whether the record has gone quiet long enough to be worth raising at all. So a
     * caller declares that it is asking that question rather than receiving the clauses by default.
     *
     * **Both shapes that read commitments now ask, so the defaults are unexercised outside the
     * tests, and that is a tension rather than a settled thing.** Keeping them says a caller
     * rendering a commitment for some third question would have to choose; requiring them would
     * make a shape that forgot fail to compile, which is the call made for the zone a shape dates
     * its material in. Whichever way it goes, it is a decision about this signature and not about
     * either caller, so it is recorded here rather than taken by the next shape to arrive.
     *
     * Age is rendered only where nothing recorded an outcome, because that is the item a reader
     * acts on: how long a discharged promise sat before it was discharged is history, and the
     * clause would land on every item alike and tell a reader nothing about which to look at.
     *
     * **Direction is not gated the same way, and the two are different kinds of fact.** Which party
     * a commitment obliged holds whatever became of it, so the marker is rendered on a discharged
     * one as well; how long something has gone unmentioned is a question about whether to act,
     * which a finished thing has no answer to. What makes rendering it there safe is the marker's
     * wording rather than this call — see [OBLIGES].
     */
    // A parameter per thing a commitment's rendering is about: the finding, the two parties, the
    // outcome, the clock the outcome and the age are read against, the calendar every time it says
    // is said in, and whether this caller wants the age. Collapsing the last two into a flags
    // object would hide which shape asked for what behind a type, and the asymmetry between them
    // is the thing the note above is for.
    @Suppress("LongParameterList")
    fun of(
        commitment: Commitment,
        party: String?,
        closed: Closed?,
        now: Instant,
        zone: ZoneId,
        owed: String? = null,
        ages: Boolean = false,
    ): Span =
        commitment.evidence.firstStated.let { stated ->
            Span(
                id = stated.span,
                text = buildString {
                    append(if (commitment.hedged) HEDGED_COMMITMENT else COMMITMENT)
                    party?.let { append(STATED_BY).append(it) }
                    owed?.let { append(OBLIGES).append(it) }
                    append(state(commitment, closed, now, zone))
                    if (ages && closed == null) append(age(commitment, now, zone))
                    append(": ")
                    append(commitment.claim)
                },
                sourceTime = stated.sourceTime,
            )
        }

    /**
     * [commitments] with what is owed first: now past, then today, then ahead, then undated, and
     * whatever [closed] records as discharged last.
     *
     * **An order the store cannot produce, because the store orders by recency of evidence.** What a
     * person asking where things stand wants first is the promise that fell due with nothing
     * recorded against it, and that has no relationship to when it was last mentioned — a thing
     * agreed three months ago for last week is exactly the item that goes unnoticed. The instruction
     * asked the model to group them this way while the material arrived in a different order and
     * said so, which is a prompt that can be obeyed only by re-sorting a list under its own
     * description.
     *
     * **Discharged last rather than left where it fell**, which follows from [state] rather than
     * being a second decision: a closed commitment is deliberately not grouped by its due date, so
     * sorting by that date alone would file a promise discharged late among the overdue ones and
     * put finished work back at the top of the answer.
     *
     * Within the three dated groups, the earliest deadline first, so the longest overdue leads. The
     * undated and the discharged keep the order they were retrieved in — recency, which is the only
     * thing that distinguishes them — and the sort is stable, so that is what they keep.
     *
     * **Position is not the whole of what carries this, and cannot be.** [gathered] folds a
     * commitment into the block of the turn it was read out of, and a shape reads its messages
     * before its findings — so a commitment evidenced by a turn the shape also retrieved is shown at
     * that turn's position whatever this returns. That is a turn in the recent tail for the shape
     * that reads one, and for the shape that also widens each promise through the index it is every
     * undischarged promise's own turn. What survives either way is the standing on each item's own
     * label, which is why the instruction states the order as something to write in rather than as a
     * fact about the material.
     */
    fun owedFirst(
        commitments: List<Commitment>,
        closed: Map<EntityId, Closed>,
        now: Instant,
        zone: ZoneId,
    ): List<Commitment> =
        commitments
            .map { Owed(it, standing(it, closed[it.id], now, zone)) }
            .sortedWith(compareBy({ it.standing.ordinal }, { it.deadline }))
            .map { it.commitment }

    /** One commitment beside the standing it is ordered by, so the standing is decided once. */
    private class Owed(val commitment: Commitment, val standing: Standing) {
        /**
         * Its deadline where the standing is one a deadline orders, and one shared value where not,
         * so the groups a deadline must not order keep the order they arrived in — the sort being
         * stable.
         *
         * Read through [Standing.byDeadline] rather than off the date alone: every standing a
         * deadline orders has one, so a missing date here is not a case to fall back for but a
         * group that is ordered by something else.
         */
        val deadline: Instant = commitment.due?.takeIf { standing.byDeadline } ?: Instant.EPOCH
    }

    /**
     * Where a commitment stands, as the one thing both the label and the order are derived from.
     *
     * **One function because the day boundary is subtle and two copies of it would drift.** A date
     * resolves to the start of a day, so a promise due today stands before this instant for all but
     * the first moment of it — compared against now rather than against the day's own bounds, the
     * whole of today's work reports as overdue. An ordering that made that comparison separately
     * would sort today's promises in among the overdue ones while the label beside each one said
     * *today*, and the answer would contradict its own material.
     *
     * **On [zone]'s calendar, which is the one every time this material prints is said in.** The
     * label sits beside the date it describes and beside the age of the message that stated it, and
     * a reader weighs the three against each other — so a boundary of its own put an item falling
     * due today where the correspondents are into the overdue group, and one falling due tomorrow
     * there into today's. A day count taken from the same boundary then disagreed with the age
     * printed next to it by a day, for every deadline in the hours where the two calendars part.
     * Through [Moment] rather than against a truncated instant, so the group, the figure [overdue]
     * describes it with and the heading a reader checks both against cannot be on different days.
     *
     * **That holds where the zone is UTC or ahead of it, and the direction is worth stating because
     * the record is what bounds it.** A date somebody stated is resolved to the start of a day in
     * UTC before it is stored, so reading it here on a zone behind UTC lands it on the previous
     * day: the group, the figure and the printed date move back together, which is self-consistent
     * and a day early. Reading the deadline on the calendar it was resolved on would trade that for
     * the disagreement this exists to remove, since the age it is weighed against follows the
     * messages — the two can only agree once a stated date is resolved in this same zone, which is
     * recognition's to do and not this layer's. [Moment] records the same bound.
     *
     * **Daylight saving cannot move this, which the boundary it replaced had to say and was the
     * other half of its reason.** The count is between two calendar dates, not between elapsed
     * periods, so a day of twenty-three or twenty-five hours changes neither end; and the zone is a
     * deployment's to name rather than the host's, so it does not follow whatever machine the
     * process runs on. That is also why no clamp belongs here where `Age` needs one — that counts
     * dates behind a gate measured in elapsed time, and the two can disagree across a clocks-back
     * evening, where this gates and counts on one expression.
     *
     * The count is taken once rather than per branch: the label and the order are derived from this
     * one value, which is the whole of the note above, and a later edit moving one comparison and
     * not the other is exactly the drift it warns about.
     */
    private fun standing(commitment: Commitment, closed: Closed?, now: Instant, zone: ZoneId): Standing {
        val due = commitment.due
        return when {
            closed != null -> Standing.CLOSED
            due == null -> Standing.UNDATED
            else -> when (Moment.daysBetween(due, now, zone)) {
                in 1..Long.MAX_VALUE -> Standing.PAST
                0L -> Standing.TODAY
                else -> Standing.AHEAD
            }
        }
    }

    /**
     * How long ago the corpus last recorded anything about [commitment].
     *
     * **Absence and age are different questions, and saying only the first is what reads as a
     * system that knows nothing.** That an outcome is not recorded answers whether the field is
     * filled; how long the thing has sat unmentioned is what tells a reader whether to act. An
     * undertaking given three weeks ago with nothing since and one given yesterday carry the same
     * labels without this.
     *
     * **Computed here rather than left to the model, although every block already carries its
     * instant.** Date arithmetic across a prompt is the one thing this build has measured a model
     * getting wrong in the reader's favour — a relative expression relayed as though it were still
     * current — so the elapsed figure is produced where it can be checked.
     *
     * From [lastStated] rather than from the span the block is shown under: a restated obligation
     * was last seen at the restatement, and the question is how stale the record is. Nothing yet
     * a day old is said as today rather than as nought days, which also absorbs a clock that reads
     * behind the corpus.
     *
     * **Counted in the correspondents' calendar days, because that is the count a reader can
     * check.** The figure sits a line under a heading dating the same message, and a reader
     * verifies it by counting the dates between that heading and today. Counted as whole elapsed
     * periods instead it is one short for every message written later in the day than the hour the
     * question is asked — not the near-midnight corner but most of an afternoon's material — and
     * the reply then reports a message its own heading places seven days back as six days old.
     * [Moment] holds the convention and the reasoning; it is the same one the heading is printed
     * in, which is the whole point of taking it from there.
     *
     * **A day count, and [overdue] counts the same days of something else.** That one is how far
     * past a stated deadline stands and this one is how long a record has gone unmentioned — two
     * different questions, printed in one clause so a reader can weigh urgency against staleness.
     * That is a comparison, so both are counted on this calendar: a deadline and the message that
     * mentions it falling at one moment report one figure, and the gap a reader reads off the pair
     * is the gap between the two dates.
     */
    private fun age(commitment: Commitment, now: Instant, zone: ZoneId): String {
        val days = Moment.daysBetween(commitment.evidence.lastStated.sourceTime, now, zone)
        return when {
            days < 1 -> LAST_STATED_TODAY
            days == 1L -> "$LAST_STATED$days$DAY_AGO"
            else -> "$LAST_STATED$days$DAYS_AGO"
        }
    }

    /**
     * Where a commitment stands, which decides both its label and its place in the answer.
     *
     * **The declaration order is the answer's order**, [owedFirst] sorting on it: moving a constant
     * within this list moves the group it names in every answer that orders commitments — where
     * things stand and what to say on a call alike — which is the one edit here that changes an
     * answer without touching a shape, the rendering or an instruction.
     *
     * [byDeadline] says whether a deadline orders this group. The two that it does not are the ones
     * with no deadline to order by and the ones a deadline must not order — see [owedFirst].
     */
    private enum class Standing(val byDeadline: Boolean) {
        PAST(true),
        TODAY(true),
        AHEAD(true),
        UNDATED(false),
        CLOSED(false),
    }

    /**
     * What is recorded about a commitment's date and its outcome, in the words a reader may repeat.
     *
     * **Two things in one clause, because they are one question.** A promise due last week and
     * recorded as done is finished; the same promise with nothing recorded is the thing a person
     * asked about. Rendered as separate labels the model would have to pair them itself, and the
     * pairing is the whole of what the daily driver is for.
     *
     * **The absent outcome is said out loud and never worded as open.** A promise is frequently kept
     * in silence, so nothing recorded means nothing recorded — not still owed. This is the sentence
     * the card behind the closure pass calls its ceiling, and it is here as well as in the prompt
     * — the shared reply format, which every shape gets — because the prompt is advice and this is
     * the material: a model told one thing and shown another follows what it is shown.
     *
     * **The date is always said, closed or not.** A closed commitment is not *grouped* by its date —
     * a promise discharged late is discharged, and offering it beside the ones nothing has settled
     * would put finished work back in front of the reader — but saying nothing about its date is a
     * different thing, and the one the event and commitment renderings both exist to prevent: a span
     * with no date reads as one whose date was not worth repeating, so the model places the thing by
     * the only other date in front of it, which is when it was mentioned.
     *
     * **Due today is its own answer and not the past**, which [standing] decides and this renders.
     *
     * **How far past is part of the answer and not a refinement of it.** A promise two weeks
     * overdue and one that fell due this morning are the same label without it, so a reader has to
     * read every date and subtract to find the one to act on — and the figure is computed rather
     * than left to be worked out from the date beside it, for the reason [age] gives.
     */
    private fun state(commitment: Commitment, closed: Closed?, now: Instant, zone: ZoneId): String {
        val outcome = closed?.let { "$CLOSED_AT${Moment.written(it.by.sourceTime, zone)}$CLOSED_HERE" } ?: UNRECORDED
        val due = commitment.due ?: return "$UNDATED$outcome"
        val standing = when (standing(commitment, closed, now, zone)) {
            Standing.PAST -> "$NOW_PAST${overdue(due, now, zone)}"
            Standing.TODAY -> DUE_TODAY
            Standing.AHEAD -> AHEAD
            // A discharged promise carries no standing: it is finished whenever it was due, and
            // grouping it by that date would put finished work back among the overdue ones.
            Standing.CLOSED -> ""
            // Unreachable, this arm being past the point where an absent date returned above.
            // Rendered as nothing rather than as the no-date wording, which beside a date that is
            // present would contradict itself. Left as an arm rather than folded into an `else` so
            // that a standing added to the enum fails to compile here instead of taking whatever a
            // fallback happened to render.
            Standing.UNDATED -> ""
        }
        return " (due ${Moment.written(due, zone)}$standing)$outcome"
    }

    /**
     * How far past [due] stands, in the number of [zone]'s calendar days between its date and
     * today's.
     *
     * **Dates and not elapsed periods, because a deadline is a date and not an instant.** Where
     * somebody stated a date it resolves to the start of a day; where they stated a time it is a
     * moment inside one. Whole 24-hour periods counted from the raw value put the two ends on
     * different footings, so every deadline carrying a time of day reported a day fewer than the
     * one a reader counting dates arrives at — and a deadline that fell yesterday evening reported
     * as past by nothing at all.
     *
     * **The same calendar as [age], which is what makes the two figures comparable.** They are
     * printed in one clause so a reader can weigh urgency against staleness, and that is a
     * comparison: counted on different calendars, a deadline and the message that mentions it
     * falling at the same moment came out a day apart. Through [Moment], which is also the
     * boundary [standing] groups on, so the figure cannot disagree with the group it describes.
     *
     * A figure of nothing is unreachable from here: [Standing.PAST] requires the deadline to fall
     * on an earlier date than today's, so the smallest count between the two is a day.
     */
    private fun overdue(due: Instant, now: Instant, zone: ZoneId): String {
        val days = Moment.daysBetween(due, now, zone)
        return if (days == 1L) "$BY$days$DAY" else "$BY$days$DAYS"
    }

    /**
     * [event] as a span, marked as an inference, with the date that places it and [party] where
     * there is one.
     *
     * [party] carries the same meaning and the same reasoning as it does for a commitment — see
     * [of].
     *
     * **A missing moment is said rather than left out**, as a missing due date is above. An event
     * with no date is a mention of something happening with no time attached, and a span that
     * simply carries no date reads as one whose date was not worth repeating — so a model composing
     * from it will place it by the only other date in front of it, which is when it was mentioned.
     * That is the wrong-about-when failure the statement rendering below is shaped to avoid,
     * arriving by omission instead. It matters more here than for a commitment, because an undated
     * event is composed beside two windows of dated ones.
     *
     * [zone] is the calendar the moment is said in, which is the one every time this material
     * prints is said in — see [of] for a commitment, where a date printed on a calendar of its own
     * put a figure beside it a day out from the heading a reader checks both against.
     */
    fun of(event: Event, party: String?, zone: ZoneId): Span = event.evidence.firstStated.let { stated ->
        Span(
            id = stated.span,
            text = buildString {
                append(if (event.hedged) HEDGED_EVENT else EVENT)
                party?.let { append(STATED_BY).append(it) }
                append(event.occursAt?.let { " (${Moment.written(it, zone)})" } ?: UNDATED)
                append(": ")
                append(event.claim)
            },
            sourceTime = stated.sourceTime,
        )
    }

    /**
     * [statement] as a span, carrying the period it held over and naming [subject] where there is one.
     *
     * **The period is in the text, and that is the point of rendering a statement at all.** A value
     * read out of the wrong stretch of time is a real fact with real evidence: it cites correctly, so
     * the grounding check passes it and the answer is fluent, specific and wrong about when. The only
     * defence available here is to put the window in front of the model and say what it means, which
     * is the same move the hedge marker makes for a commitment.
     *
     * **An open window says the fact is still held as far as the corpus knows, not that it is true
     * now.** Nothing closes a window except a later contradicting statement, so a fact going
     * unmentioned for a year leaves its window open; wording it as the present tense would assert
     * something the store never claimed.
     *
     * **A closed window is worded with the exclusivity the type means, in the type's own words.** A
     * window runs up to but not including its end, because a contradiction opens the next one at
     * exactly that instant — so "until" alone would read as covering it, and two adjacent windows
     * would both appear to hold at the moment one replaces the other. That is the wrong-about-when
     * failure in miniature, at the one instant where the store is precise.
     *
     * [Origin] decides how strongly it may be put, for the reason the entity model gives: a model's
     * reading of hedged text is the weakest thing in that package and is never shown as a bare fact,
     * while a profile field and a human's correction are progressively stronger.
     */
    fun of(statement: Statement, subject: String?): Span = statement.evidence.firstStated.let { stated ->
        Span(
            id = stated.span,
            text = buildString {
                append(
                    when (statement.origin) {
                        Origin.INFERRED -> INFERRED_ATTRIBUTE
                        Origin.LABELLED -> LABELLED_ATTRIBUTE
                        Origin.CONFIRMED -> CONFIRMED_ATTRIBUTE
                    },
                )
                subject?.let { append(ABOUT).append(it) }
                append(
                    statement.validity.until
                        ?.let { " (held from ${statement.validity.from} up to but not including $it)" }
                        ?: " (held from ${statement.validity.from}, not since contradicted)",
                )
                append(": ")
                append(statement.predicate.name)
                append(" — ")
                append(statement.value)
            },
            sourceTime = stated.sourceTime,
        )
    }

    /**
     * [spans] with one entry per identifier, the first of a repeated one winning.
     *
     * For two readings of the *same* record — a message reached through the thread and again through
     * the index — one of them is simply the better copy, and the first is the live one: a shape reads
     * the record before it reads the index, and the index holds whatever the last pass wrote. Joining
     * them would put a stale body and the current one under one identifier the moment a message is
     * edited and before the next indexing pass.
     *
     * Use [gathered] where the colliding spans are a record and something inferred from it, which is a
     * different relationship with a different answer.
     */
    fun distinct(spans: List<Span>): List<Span> = spans.distinctBy { it.id }

    /**
     * [spans] gathered one to an identifier, texts that differ joined under the one they share.
     *
     * **One identifier must carry one text.** A derived thing is addressed by the span it was read out
     * of, so a commitment and the message it was read out of arrive under the same identifier carrying
     * two different texts — and so does any set of findings read out of one message, since nothing
     * numbers them within a span: a commitment, an event and an attribute evidenced by the same turn
     * all share its identifier. There is no choice between them that works: dropping the message
     * leaves the model told to cite a turn it was never shown, and keeping both would print one
     * identifier over two bodies, so a claim citing it could not be resolved to either.
     *
     * So they are joined, and what the reader gets is the turn and what this system read out of it in
     * one citable block. Identical texts collapse to one, and the first occurrence sets the position:
     * what a position carries is the shape's to decide — a shape putting its most relevant query
     * first means one thing by it and one sorting its material by recency another — and this fold
     * keeps whichever it chose rather than imposing a third.
     */
    fun gathered(spans: List<Span>): List<Span> =
        spans.groupBy { it.id }.map { (_, found) ->
            val texts = found.map { it.text }.distinct()
            if (texts.size == 1) found.first() else found.first().copy(text = texts.joinToString("\n"))
        }

    // Prompt labels, not display text: nothing renders them to a person and no interface sees
    // them. They tell the model that what follows is something the system inferred rather than
    // something a source said, which is the distinction a reader of the answer needs it to keep.
    //
    // **The hedge is worded as what the speaker did, not as a mark this layer wrote**, and a read
    // of the rendered reply is why. *Hedged* is a word about the extraction, and a model shown one
    // writes a clause about the extraction: the obligation reached the reader through a
    // parenthetical on how cautiously it had been read, in a sentence a person would not write.
    // What the flag records is one of two things about the speaker — they were unsure of it, or
    // they were passing on somebody else's — so the label says that instead, which is a fact a
    // reader can act on. Which of the two it was is not stored, so one wording covers both rather
    // than choosing. [Reply.format] carries the other half of this: that how the material was read
    // is not itself something to report.
    private const val COMMITMENT = "Inferred commitment"
    private const val HEDGED_COMMITMENT = "Inferred commitment, not stated firmly or not first hand"
    private const val EVENT = "Inferred event"
    private const val HEDGED_EVENT = "Inferred event, not stated firmly or not first hand"

    // Said in place of a commitment's due date or an event's moment where there is none, rather
    // than omitted: the model is told the time is absent from the record, not left to supply the
    // nearest one it can see.
    private const val UNDATED = " (no date stated)"

    // What the corpus records about a promise's outcome, and the two wordings are not symmetrical.
    //
    // One says a later message reports the thing as done and that the message is in the material to
    // be cited. The other says nothing recorded says so — which is **not** a claim that anything is
    // still owed, because a promise kept in silence produces no closing message at all. No wording
    // here may read as *open*, *outstanding* or *still owed*: those are states this build cannot
    // establish, and a label is what a model repeats.
    // What closes a promise, and the instant is load-bearing rather than decorative: it is the only
    // thing pairing this label with the turn that carries it. The material holds up to forty recent
    // turns, so *a later message* alone leaves the model choosing among them — and a citation of the
    // wrong one passes the grounding check, which asks whether a cited span was retrieved and not
    // whether it was the right span. The closing turn is rendered with the same instant beside it.
    private const val CLOSED_AT = ", closed by the message sent "
    private const val CLOSED_HERE = ", which is in this material"

    // What the closing turn is labelled as, carrying the instant that pairs it with the commitment.
    private const val CLOSING_TURN = "Message reported as closing a commitment, sent "

    private const val UNRECORDED = ", outcome not recorded"

    // Where a commitment's date stands, which is the half of state a deadline decides. Lifted into
    // constants beside the outcome wordings rather than written into the rendering, because these
    // are the words a model repeats to a reader and the one place a reader's wording lives is this
    // block — and because the order of the answer is keyed on the same three groups.
    private const val NOW_PAST = " — now past"
    private const val DUE_TODAY = " — today"
    private const val AHEAD = " — ahead"

    // How far past, and how long since anything was recorded. Two figures rather than one, because
    // they answer different questions: a promise may be weeks overdue and have been restated
    // yesterday, or fall due next month having gone unmentioned since spring.
    private const val BY = " by "
    private const val LAST_STATED = ", last stated "
    private const val LAST_STATED_TODAY = ", last stated today"
    private const val DAY = " day"
    private const val DAYS = " days"
    private const val DAY_AGO = " day ago"
    private const val DAYS_AGO = " days ago"

    // Who wrote a turn. Said of the message rather than of a claim, which is why the wording is the
    // sender's and not the finding labels' "stated by": what a turn carries is its own words, so
    // there is no second party in it for the two to be confused over. A turn whose author the
    // corpus does not name carries neither this nor anything in its place.
    private const val SENT_BY = "Sent by "

    // The same fact appended to a label that has already said something about the message, where
    // the leading form would read as a second label. Worded as a clause for that reason alone.
    private const val SENT_BY_ALSO = ", sent by "

    // Where a turn was said, rendered only for a thread other people are in, and in two forms for
    // the reason the name above is: the leading one where no author precedes it, the joining one
    // where one does — see [authored], which must keep the name leading.
    //
    // **It says who reads the thread rather than what the source calls the container.** A model
    // shown a kind would have to know what follows from it; what follows is the only thing a draft
    // needs, so it is the thing that is said. That is also why the two cases here are not the
    // source's own taxonomy of containers: every kind but a direct exchange puts the words in front
    // of somebody else, which is the one distinction a composed reply turns on.
    private const val SAID_IN = "Said in "
    private const val SAID_IN_ALSO = ", said in "

    // Which thread, where the corpus names it. The name is the channel a reply is for, and it is
    // what makes a draft sendable: a counterpart writing in several rooms is otherwise answered
    // into whichever one the model assumed.
    private const val ROOM_NAMED = "the shared conversation named "

    // A room the corpus holds no name for. Said rather than left out, because an absent clause
    // means a one-to-one thread here and a nameless room is not one — the distinction the channel
    // type keeps apart would be collapsed again by rendering it as nothing. What is lost is which
    // thread to send to, which the instruction has an arm for rather than asking for it anyway.
    private const val A_ROOM = "a shared conversation"

    // Who else reads it, which is the whole point of naming the thread at all, and the clause both
    // cases end with.
    private const val ROOM_IS_READ = ", which the other people in it read"

    // That this turn is one of the latest exchange's, in the two forms the clauses above are in —
    // the leading one where nothing precedes it, the joining one where a name or a room does.
    //
    // **It is a selection the retrieval makes and not arithmetic the model is asked to do.** What a
    // reply answers is whatever the latest exchange left open, and the material a draft is composed
    // from is several threads' tails concatenated closest-first with the ranked register examples
    // after them — so position in it is relevance and not time, and the newest turn of the live
    // thread sits in the middle of the prompt with older material on both sides. Told only to go by
    // the instant on each block, a model has to order every heading in the prompt to find that
    // turn, and it was measured replying to a request the thread had already moved past instead.
    // The turns that make up the exchange are a set the caller can compute exactly, so it is
    // computed where it can be checked and said where the model cannot miss it, which is the move
    // the elapsed figure on a commitment already makes for date arithmetic.
    //
    // **Nothing is rendered on the rest, which is what makes the mark worth anything.** A clause on
    // every turn of every thread would be the heading problem again in a second wording.
    // **In this person's threads rather than with them**, which is what the selection establishes
    // and no more: the thread is picked by recency across everything retrieved for the subject, and
    // most of those threads are rooms the subject happens to write in. A label claiming the turns
    // were theirs would be asserting what this caller has not asked — whose a turn is can be had
    // from the turn read, and `RetrieveDraft` does not ask for it; see `RetrieveDraft`, where the
    // narrower reading is argued and declined.
    private const val IN_LATEST = "In the latest exchange in this person's threads"
    private const val IN_LATEST_ALSO = ", in the latest exchange in this person's threads"

    // What marks a turn as the archive owner's, extending the name rather than standing in for it.
    // One wording per role the owner is in, which is what `OwnTurn` is about.
    //
    // **The second person here addresses the model and not the turn's author**, which is the whole
    // of why this wording is safe: a bare "you" beside a turn would read as the turn being
    // addressed to the reader, and a model shown that answers the turn instead of composing from
    // it. Naming the role the model is filling leaves nothing for it to reply to.
    private const val IS_THE_SENDER = " (the sender you are drafting for)"

    // The same fact for a shape that writes to the owner rather than as them. It names the reader
    // and not a second person, for the reason above: a brief is prose about other people handed to
    // this one, so a wording in the second person would put the model in a conversation with the
    // turn instead of describing it.
    private const val IS_THE_READER = " (the person this brief is for)"

    /** And the one asking, for a shape whose answer is not a brief. */
    private const val IS_THE_ASKER = " (the person asking)"

    // What a turn retrieved for its wording is offered as, which is **not** evidence about the
    // subject. It comes from wherever the ranking found it, so the denial is in the label and not
    // only in the instruction: a model told one thing and shown another follows what it is shown,
    // and this is the material.
    private const val REGISTER_EXAMPLE = "Register example, not evidence about the subject"

    // That the turn is neither the subject's nor the reader's, which is the fact that tells a
    // subject's own material from the business of the room it was said in.
    //
    // **In the label for the reason the register denial is**: a subject's threads are mostly rooms
    // several people write in, and a turn carrying a name and nothing else is read as evidence of
    // where the subject stands. Told in prose that rooms carry other people's matters and shown a
    // turn that says only who sent it, a model follows what it is shown, and this is the material.
    //
    // **It says which two parties it is not, rather than naming a third.** *Another participant*
    // alone would be read against the subject only, and the reader is a participant too — their own
    // turns in the thread are where the other half of the relationship is recorded and are not the
    // thing being denied. Both exclusions are therefore said, and what is left is a party whose
    // matters the question did not ask about.
    //
    // **Said of the author and not of the matter, which is the bound on what it can claim.** Who
    // wrote a turn is a fact resolved through the merge; whether what it says concerns the subject
    // is not on any row, and a reader of the marker is told as much by its wording — the subject may
    // well be a party to what another participant wrote. So the marker places the burden where it
    // can be met and the instruction carries the judgement, which is the division the direction
    // marker on a commitment already makes.
    //
    // Two forms for the reason every clause in this block has two: the leading one where no name
    // precedes it, the joining one where one does. Both are needed because the two facts come from
    // different reads — whose a turn is resolves on the contact, and the name is a read of its own
    // that may come back with nothing — so a turn can be known not to be the subject's and carry no
    // name to hang the clause on. Whether a corpus currently holds such a turn is not what decides
    // it: the rendering has to be right for the shape of the two reads.
    private const val ANOTHER_PARTY = "Said by neither the subject nor the reader"
    private const val ANOTHER_PARTY_ALSO = ", who is neither the subject nor the reader"

    // Who stated the thing, not which party it obliges. The two came apart once an obligation
    // stated about somebody else began to count as a commitment: the marker below carries direction
    // where the row places a party and the claim's own words carry it where the row does not, and
    // this names who said so. Wording that said "promised by" would assert the one as the other, which
    // is wrong in exactly the cases a question about what one party owes another turns on.
    private const val STATED_BY = ", stated by "

    // Which party the claim obliges, where the extraction stage could place one on the row. Kept
    // plainly apart from the label above: the two parties are routinely different people, and one
    // wording covering both is the swap this pair exists to prevent.
    //
    // **Worded as what the commitment does rather than as what a party is still carrying**, which
    // is what keeps it inside the rule stated with the outcome wordings further up this block: no
    // wording a model repeats from here may read as open, outstanding or still owed. Direction is a
    // fact about whom a claim obliges and holds whatever became of the thing, so it is rendered on
    // a discharged commitment too — and a label reading *owed by* would then say, on an item the
    // corpus records as done, the one state this build cannot establish. Dropping the marker once
    // something is discharged is the other way out, and it loses a fact that is still true to
    // protect a wording that need not be used.
    //
    // The column's own migration says nothing on the answer path renders this, which was true when
    // it was written and cannot be edited there — an applied migration's checksum covers the whole
    // of its text — so the live statement is here, which is the arrangement that migration itself
    // makes for the column beside it.
    private const val OBLIGES = ", obliges "

    // An attribute's three strengths, which are its origin and not a hedge. Worded apart from the
    // finding labels above because an attribute is a state the corpus recorded rather than something
    // somebody undertook, and one wording for both would let the model report a stored profile field
    // in the same breath as a promise.
    private const val INFERRED_ATTRIBUTE = "Inferred attribute"
    private const val LABELLED_ATTRIBUTE = "Attribute a source recorded"
    private const val CONFIRMED_ATTRIBUTE = "Attribute a person confirmed"

    // The subject of an attribute, which is who the fact is *about* rather than who reported it: only
    // a fact stated about its own author is stored, so the two coincide here — but the wording has to
    // be the one that stays true if that ever stops being the case.
    private const val ABOUT = ", about "
}
