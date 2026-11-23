package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * Turns a name into the thing a request is about.
 *
 * Every answerable shape starts from a subject, and the kinds a shape will accept differ — a
 * reply goes to a person, an inbox sweep is scoped by nothing. So this takes the accepted set
 * from the shape and applies one ordered rule list to whatever matched, which is what keeps
 * the policy in one place instead of once per shape.
 *
 * **Matching runs across every kind at once rather than per kind in turn.** A name is as
 * likely to be a conversation as a contact, and asking the kinds in some order would make the
 * order the tie-breaker — the first kind to match would win on evidence weaker than a later
 * kind's.
 *
 * A plain class composing a port, with no interface of its own: it inverts no I/O, and the one
 * thing it reaches is already behind one.
 */
class ScopeResolver(private val search: SubjectSearch) {

    /**
     * Resolve [name] to a subject a shape accepting [accepts] can answer for.
     *
     * The rules, in order: unique and acceptable proceeds; several that are related compose, so
     * the narrow subject is answered and the broad one is context; several that are unrelated
     * ask, because a chat interface can afford a question where a batch process cannot; an
     * unacceptable kind is traversed one hop rather than refused; and no match says so, and
     * says what was searched.
     *
     * **Before any of them, a request whose subject was never established is answered as that
     * and nothing else.** There is no name to search for and no finding that nobody was named,
     * so every rule below would be deciding on evidence this request does not carry — and the
     * one it would reach, the inbox, is an answer about everything to a question about somebody.
     *
     * **And beside it, a request that named nobody at a shape that cannot answer for the inbox.**
     * That one said what it meant, so it is not the state above; what it meant cannot be answered
     * by this shape, so it is not the inbox either. It is a request with its subject missing, which
     * is [Scope.SubjectMissing] and not a search — no name reached here, so nothing was looked
     * through and nothing below ran.
     */
    suspend fun resolve(name: SubjectName, accepts: Set<SubjectKind>): Scope {
        require(accepts.isNotEmpty()) {
            "A shape that accepts no subject must not be resolved for: there is nothing this " +
                "could return that such a shape could use, and asking means the runner " +
                "resolved scope for a shape that said it needs none."
        }
        return when (name) {
            // The inbox is reached by a request having named nobody rather than by matching a
            // name, so this is not a missing-argument case for a shape that accepts it. It has no
            // entity behind it at all, which is why nothing is searched for here. A shape that
            // does not accept it has a request with its subject missing, and says so — as that,
            // rather than as a search that came back empty, because no search ran.
            //
            // **Nothing searched, so nothing is reported as searched.** There is no name on this
            // path for a search to have run against, and naming the kinds this shape would have
            // accepted would tell the reader those kinds were looked through on the evidence that
            // the shape was willing to answer for them. [Scope.SubjectMissing] carries neither,
            // which is how it states that rather than reporting an empty read.
            SubjectName.None -> if (SubjectKind.INBOX in accepts) {
                Scope.Resolved(Subject(Candidate.inbox()), alternatives = emptyList())
            } else {
                Scope.SubjectMissing
            }

            SubjectName.Unread -> Scope.Unread
            is SubjectName.Given -> decide(name.name, search.matching(name.name), accepts, traversed = false)
        }
    }

    /**
     * The rule list, over whatever matched.
     *
     * [traversed] is what bounds the hop to one. A client resolving to its contacts re-applies
     * these rules, and without the flag a corpus whose relations happened to form a chain
     * would be walked for as long as the chain is — spending a read per level and answering
     * about something several removes from what was asked.
     */
    private suspend fun decide(
        name: String,
        candidates: List<Candidate>,
        accepts: Set<SubjectKind>,
        traversed: Boolean,
    ): Scope {
        val acceptable = candidates.filter { it.kind in accepts }
        return when {
            candidates.isEmpty() -> nothing(name)
            acceptable.isEmpty() -> traverse(name, candidates, accepts, traversed)
            else -> judge(candidates, acceptable)
        }
    }

    /**
     * Nothing matched the name, so say which of the two things that can mean.
     *
     * **A search that matched nothing has not established that the corpus holds nothing.** What it
     * establishes is that no stored label agrees with the form that was written, and the two are
     * different facts with different next actions: a reader whose subject is absent has nothing to
     * look for, and a reader who wrote a name the corpus keeps under another form has only to write
     * it differently. Reported as the first, the second is sent to the one conclusion they cannot
     * check — that there is nothing there — and told to stop looking.
     *
     * So the near forms are asked for, and whether any exist is what decides between the two. Where
     * some do, the reply carries them: they are the only evidence either way, and they are also the
     * thing the reader has no other route to, because the form a display name is stored in is the
     * source's business and nothing shows it to them.
     *
     * **They are near forms and never a reading of the name**, which is why this is not [Scope.Ask].
     * A menu asserts that one of its options is what was meant and asks which; these assert only
     * that the corpus holds a name spelled nearly like the one typed. Offered as readings they would
     * be a guess between a stranger and the person meant, which is the error the menu rule refuses —
     * offered as spellings they cost a reader a glance and tell them what form to write.
     */
    private suspend fun nothing(name: String): Scope {
        val near = search.nearest(name)
        return if (near.isEmpty()) {
            Scope.NotFound(searched = search.covers, name = name)
        } else {
            Scope.Nearest(nearest = near, searched = search.covers, name = name)
        }
    }

    /**
     * Nothing of an acceptable kind matched, so look one hop inside what did.
     *
     * A reply requested for a client resolves to that client's contacts rather than objecting
     * that an organisation cannot receive a message. [traversed] having already been set is
     * what stops a second hop — and it is checked before the read rather than after, so a
     * bounded walk costs one query rather than one per level.
     *
     * **What the name matched is carried forward alongside what the hop found.** The client the
     * name actually matched is the broader subject the contact sits under, so dropping it here
     * would lose it as context and leave the answer unable to say what it was answered against
     * — which is the one thing that was certainly searched for.
     *
     * Deduplicated by referent, because the fan-out is what creates the duplicates: two matched
     * clients holding one contact return it once each, and the same subject twice reads as two
     * competing readings of the name. The strongest reading of each is the one kept.
     */
    private suspend fun traverse(
        name: String,
        candidates: List<Candidate>,
        accepts: Set<SubjectKind>,
        traversed: Boolean,
    ): Scope {
        val hop = if (traversed) {
            emptyList()
        } else {
            candidates.flatMap { search.within(it.referent) }
                .sortedByDescending { it.score }
                .distinctBy { it.referent }
        }
        return if (hop.isEmpty()) {
            Scope.NotFound(searched = search.covers, name = name)
        } else {
            decide(name, candidates + hop, accepts, traversed = true)
        }
    }

    /**
     * One acceptable match proceeds, a hierarchy composes, and competing matches ask.
     *
     * The narrow subjects are the ones nothing else here is a parent of. Exactly one means the
     * matches form a hierarchy; more than one means they compete, and a guess between two
     * different clients of the same name is the error that is invisible afterwards.
     *
     * **Which subject to answer is decided among the acceptable matches; what it is answered
     * against is drawn from all of them.** A shape that accepts only a contact still gets that
     * contact's client as context, and reading the chain from the acceptable subset alone would
     * drop every broader subject of a kind the shape cannot answer *for* — turning the client
     * from context into a competing alternative, which is what the hierarchy rule exists to
     * prevent.
     *
     * **A parent among several still makes its child the narrower subject.** A contact whose body
     * of work is three clients names all three, and a search that matched one of them has matched
     * something the contact sits under — so the test is membership rather than equality. Written as
     * equality against a single parent it would have missed exactly the case plurality exists for,
     * and three strands of one body of work would have competed with the contact inside them.
     *
     * **Which is also how a body of work named by its own name is answered as one subject.** Its
     * strands are clients and the conversations they were read from, every one of them a match on
     * the same name, and none of them is the narrowest thing here because the work names them all —
     * so the work is the single narrow subject and the strands are what it is read through. That is
     * this rule applied unchanged: what decides the focus is the relation the matches report, and
     * all this one needed was for something to report it. Where the matched clients name two
     * bodies of work, nothing reports one and the strands compete exactly as they did.
     *
     * **The account the corpus was read through is not one of the readings a name competes
     * between.** It is who is asking: in a personal archive it carries the reader's own name, so a
     * question about anyone who shares part of that name was a menu offering the reader themselves
     * beside the person they meant. Dropped from the competing set and not from the matches, which
     * is the narrow form of the rule — a name that means nobody else still resolves to it, exactly
     * as today, and what is lost is only its place in a choice offered to a reader.
     */
    private fun judge(candidates: List<Candidate>, acceptable: List<Candidate>): Scope {
        // Only where it leaves something to choose between. Every acceptable match being an account
        // read through means the name means one human however many accounts carry it, and the rules
        // below then compose or ask over those as they would over anyone.
        val competing = acceptable.filterNot { it.isSelf }.ifEmpty { acceptable }
        val narrowest = competing.filter { candidate ->
            competing.none { candidate.referent in it.parents }
        }
        val focus = narrowest.singleOrNull()
        return if (focus == null) {
            // **A body of work is never one of the readings a menu offers.** The only answer to a
            // menu is to name the option that was meant, and what it carries is a strand's own
            // label — so naming it back resolves to that strand rather than to the work, which is
            // an option a reader cannot pick. That is worse than its absence: the reply asserts
            // the work was on offer. It stays in the set above, because what it competes with is
            // what decides whether anything resolved at all; it is dropped only from the question.
            //
            // The unfiltered set stands where filtering would leave a single option, since asking
            // which of one thing was meant is not a question. Nothing here can produce that today —
            // a work is reported only beside the clients it is made of, so a search that reports
            // one reports at least two other readings of the name — and the fallback is for a
            // search that does not.
            val offered = competing.filterNot { it.kind == SubjectKind.BODY_OF_WORK }
                .takeIf { it.size > 1 } ?: competing
            Scope.Ask(offered.sortedByDescending { it.score })
        } else {
            val context = contextOf(focus, candidates)
            val chosen = (context + focus).map { it.referent }.toSet()
            // **Excluded by name as well as by referent, because one subject can be two records.**
            // Both records carry the same label under different referents, so excluding the chosen
            // referent alone leaves the other to be announced — and the reader is told that the
            // name they just asked about was not the one answered, which reads as the assistant
            // having drafted to somebody else entirely.
            //
            // **A search that resolves a person's own direct conversation no longer reaches here**,
            // because [SubjectSearch] collapses that pair before scoring. What this still catches is
            // any other two records of one subject a search reports. It also over-reaches by the
            // same stroke: a room that merely shares a person's name is a different subject, and
            // hiding it hides that the search was ambiguous — which the clause right above says it
            // must not. Narrowing it to the records a search marks as one subject is a change to
            // what [SubjectSearch] reports, not to this filter.
            val chosenNames = (context + focus).mapNotNull { it.name?.lowercase() }.toSet()
            Scope.Resolved(
                subject = Subject(focus = focus, context = context),
                alternatives = candidates
                    .filterNot { it.referent in chosen }
                    .filterNot { candidate -> candidate.name?.lowercase() in chosenNames }
                    // For the reason the competing set drops it: an alternative is announced to a
                    // reader as something the name might have meant instead, and the account the
                    // corpus was read through is never that. Where it is what *was* answered for,
                    // the chosen referent above has already excluded it.
                    .filterNot { it.isSelf }
                    // **And a body of work, for the reason a menu drops it.** An alternative is
                    // offered as a name to ask by, and this one's label is a strand's own — so
                    // asking by it reaches that strand rather than the work. Reachable where the
                    // work is not what was answered: a shape that cannot answer for one traverses
                    // to a contact inside the strands, and the work is then a reading of the name
                    // that nothing retrieved for. Dropped by kind rather than left to the name
                    // filter above, which happens to catch it only while a strand is also context.
                    .filterNot { it.kind == SubjectKind.BODY_OF_WORK }
                    .sortedByDescending { it.score },
            )
        }
    }

    /**
     * The broader subjects [focus] sits under, narrowest first.
     *
     * Walked through the matched set rather than read from the store: a parent nobody searched
     * for is not context for this request, it is a relation that happens to exist. The visited
     * set bounds a cycle — a relation that points back at something already on the chain is a
     * defect in whatever reported it, and looping here would hang the request rather than
     * report it.
     *
     * **A generation at a time, because a subject can sit under several records of one broader
     * subject.** The strands of one body of work are siblings rather than a chain, so they are
     * added together and their own parents are asked for together, which is what keeps *narrowest
     * first* true of the result. One absent from the matched set is passed over rather than ending
     * the walk: with several parents, stopping at the first unsearched one would drop the siblings
     * that were searched for, and those are the context that exists.
     */
    private fun contextOf(focus: Candidate, among: List<Candidate>): List<Candidate> {
        val byReferent = among.associateBy { it.referent }
        val visited = mutableSetOf(focus.referent)
        return buildList {
            var generation = focus.parents.filter { visited.add(it) }
            while (generation.isNotEmpty()) {
                val found = generation.mapNotNull { byReferent[it] }
                addAll(found)
                generation = found.flatMap { it.parents }.filter { visited.add(it) }
            }
        }
    }
}

/**
 * The corpus and the entity layer, read together by name.
 *
 * One port rather than one per kind, because resolution is the one read that crosses both in a
 * single operation — a conversation is a record while a contact is an entity, and both are
 * legitimate subjects of the same question. Splitting it would make the caller merge two
 * rankings whose scores mean nothing to each other.
 */
interface SubjectSearch {

    /**
     * The kinds a name can come back under here.
     *
     * Declared by the search rather than inferred by the caller, because the caller does not know
     * what this reaches — and a refusal tells whoever asked where their name was looked for, which
     * is a property of this read and not of the shape that wanted it. A shape's accepted kinds are
     * what it is willing to answer *for*; naming those instead reports a kind as searched on the
     * evidence that something would have been acceptable had it matched.
     *
     * **A kind a name comes back under rather than one it is compared against, and the two differ
     * by one.** Nothing stores the name a body of work's strands share, so no stored value is
     * compared with it — what is compared is the strands' own labels, and the work is what the
     * agreement is reported as. Reading this as the compared set would leave the one scope a reader
     * can reach and not be told was looked in.
     *
     * **A capability, not a tally of statements issued.** It is fixed for an implementation, so it
     * says which kinds a name can come back under and not that every one of them was read on any
     * particular call: [matching] may answer from a name that reduces to nothing without reading
     * anything, and [within] reads inside what already matched rather than across these. What a
     * reader is owed is where a name of theirs could have been found, which is exactly this; a
     * per-call record of statements issued would be a different field, and no reply needs one. It is
     * what admits a body of work, whose name is held in no column and read off the strands'.
     *
     * **Never [SubjectKind.INBOX].** It is reached by a request having named *nobody*, so no name
     * is ever compared on its behalf and none could be: there is nothing a name could agree with
     * and no reading of one to report. A search claiming to cover it would put a mode of this build
     * into a list of places a reader's name was looked for. That is a sharper test than holding no
     * entity, which a body of work does not either — one is a scope a name reaches and the other is
     * what a request falls to when it names nothing. [Scope.NotFound] refuses it rather than trusting this prose —
     * an implementation that declared it fails the request rather than misinforming a reader, which
     * is the opposite of what the interface does with the same input and is deliberate: the one is
     * a defect in this build, the other a message already on the wire.
     */
    val covers: Set<SubjectKind>

    /**
     * The subjects [name] might mean, scored, across every kind at once.
     *
     * Scored the way reconciliation is scored: a rarer name is stronger evidence than a common
     * one, so the ranking is not simply how closely the strings agree.
     *
     * **Which makes the score absolute rather than a rank, and comparable between two reads.**
     * Reconciliation sums weighted signals and compares the total against a fixed threshold, so
     * it orders pairs drawn from different queries against each other; a retrieval score cannot
     * be read that way, and the caveat that belongs on a fused ranking does not belong here.
     * Resolution depends on the difference: the traverse path merges what several referents
     * contain and orders the result, which is only meaningful if two reads produce commensurable
     * numbers.
     *
     * **[name] is matched in whatever script it was written in.** The corpus is Russian and the
     * interface answers in Russian, so a reader names a person the way their own question names
     * them, while a source stores whatever script the display name was registered in — and a label
     * is copied from the source rather than transliterated, so comparing the two as they stand finds
     * nothing at all rather than finding it weakly.
     *
     * **What that costs is stated as plainly as what it buys.** Crossing a script means reducing
     * both sides, so names that differ only by what the reduction removes — a doubled letter, a
     * glide — are read as one name and offered as two readings of it. And the crossing is not total:
     * a label holding the English form of a given name rather than a transliteration of it meets no
     * Cyrillic, and such a subject resolves by the surname beside it. A caller may rely on the
     * common renderings meeting, not on every pair of forms meeting.
     *
     * **Where the clients that matched name one body of work and there are several of them, the
     * work itself is reported beside them, carrying them as its strands.** That is the one candidate
     * here no label is stored for: a body of work keeping a thread per strand is several clients and
     * no entity, so naming it matched the strands and nothing said they were one piece of work.
     *
     * **What an implementation may read it by is a rule over the labels and never a judgement about
     * what they mean**, which is a derived pass's and belongs to a later workstream. So a reader
     * cannot take *reported* for *these are one piece of work*: the rule is as good as the labels
     * are, and an implementation states where it fails. What a reader may rely on is the other
     * direction — that it is never reported for a single client, which is a body of work already
     * and answers as one.
     *
     * **Its label is borrowed from a strand rather than reduced**, because a reduced name is one no
     * conversation holds and nothing a reader could look up. Which strand is settled, not arbitrary,
     * so two reads of one request agree.
     *
     * **A name agrees with a label at the start of a word and not inside one.** Typing the beginning
     * of a name resolves it; a short name landing in the middle of an unrelated longer one is a
     * coincidence of letters, and admitting it costs a menu nobody can answer rather than a weak
     * match. A handle is not a name in this sense and is matched as the key it is: anywhere in the
     * value, unreduced.
     *
     * **[name] may also be inflected, because a question names somebody in the case its sentence
     * needs** and what reaches here is what the reader wrote. That is tolerated by comparing
     * strictly first and, only where nothing was found, again against the words of the name
     * shortened to their stems. A caller therefore cannot read *nothing found* as *no such name in
     * any form*; it already means both were tried.
     */
    suspend fun matching(name: String): List<Candidate>

    /**
     * The stored names nearest [name], for a name that agreed with none of them.
     *
     * **Called only where [matching] found nothing, and it is not a weaker [matching].** What it
     * answers is a different question: not *which subject did this name mean* but *what forms close
     * to this one does the corpus hold*. A caller must never resolve a request to one of these — the
     * agreement is too weak to be a reading of the name, which is the whole reason they are reported
     * instead of matched.
     *
     * **What that buys is the one thing a reader cannot get otherwise.** A display name is stored in
     * whatever form its source registered, and nothing shows a reader which that is; told only that
     * nothing was found, their only route forward is to guess the spelling. A near form is the
     * spelling, so a name the corpus keeps differently becomes one more question rather than a dead
     * end.
     *
     * **And it is what separates a form that did not match from a subject that is absent.** Those
     * are different facts with different next actions, and a search alone cannot tell them apart:
     * nothing matched is all it knows. Whether anything near exists is the evidence that can, which
     * is why a caller asks this where a name was searched for and found nothing.
     *
     * **Which is narrower than *before reporting an absence*.** A caller reports one for another
     * reason too — a name that matched something nothing answerable sits inside — and that is not a
     * question about spelling, since the name agreed with a stored label exactly. Asking there would
     * spend a read on a question nobody posed, and reporting the empty answer would be a claim about
     * work done on a name that was never in doubt.
     *
     * Empty is the ordinary answer and means no stored name is near enough to be worth showing — a
     * genuine absence as far as anything here can establish one. Empty is also what a name too short
     * to be reduced returns, since a reduction of it would reach most of the corpus.
     *
     * The bound on how near is *near* belongs to the implementation and is deliberately not stated
     * here as a number: what this interface promises is that the result is ordered strongest first
     * and short enough to read, and that offering a wrong one costs a reader a glance rather than a
     * wrong answer.
     */
    suspend fun nearest(name: String): List<Candidate>

    /**
     * The subjects one hop inside [referent] — a client's contacts, a supergroup's topics.
     *
     * Called only when nothing of an acceptable kind matched, which is what keeps it off the
     * ordinary path. A referent with nothing inside it returns empty rather than failing: a
     * client whose contacts have not been recognised yet is an ordinary state.
     *
     * **A body of work is one of those, and the strands are why.** Nothing is inside it that is not
     * inside one of its strands, and [matching] reports it only alongside every strand it is made
     * of — so the hop the caller is making reaches those contacts through the strands themselves,
     * and answering here as well would hand back each of them twice.
     */
    suspend fun within(referent: Referent): List<Candidate>
}

/** The kinds of thing a request can be about. */
enum class SubjectKind {

    /** A human, as the entity layer holds them. */
    CONTACT,

    /** A body of work, read from the name of a conversation people share. */
    CLIENT,

    /**
     * A body of work held as several of those, which is every strand of one piece of work.
     *
     * **It is the same sort of thing [CLIENT] is and a different scope**, which is why it is a kind
     * of its own rather than a client with more records behind it. Identity reads one label from one
     * conversation, so a body of work that keeps a thread per strand of it is several clients and no
     * entity joins them; naming the work then matched the strands as competing clients and the
     * reader was asked which one they meant, when the answer they asked for is all of them.
     *
     * **Nothing persists it and nothing mints it.** It is derived from the labels the strands carry,
     * for the length of one request, which is why its referent holds a name and not an identifier —
     * see [Referent.OfBodyOfWork]. The strands stay separate clients and nothing is reconciled into
     * one.
     */
    BODY_OF_WORK,

    /** A thread in the corpus, which is a record rather than an entity. */
    CONVERSATION,

    /**
     * Everything outstanding, scoped by nothing.
     *
     * The one subject no name reaches. It has no entity and no record behind it, which
     * [BODY_OF_WORK] has not either — what is particular here is that nothing is ever compared
     * against it: it is what a shape answers for when a request named nobody, which is why it is a
     * kind here rather than the absence of one. The distinction is load-bearing rather than
     * pedantic: a request whose subject was never established has no name either and must not
     * reach this. See [SubjectName].
     */
    INBOX,
}

/**
 * What a subject points at.
 *
 * Carrying the identifier rather than a kind and a loose string, so that a candidate cannot
 * claim one kind and hold another's identifier. [kind] is read off the case, which is what
 * makes the two unable to disagree.
 */
sealed interface Referent {

    val kind: SubjectKind

    data class OfContact(val contact: EntityId) : Referent {
        override val kind: SubjectKind get() = SubjectKind.CONTACT
    }

    data class OfClient(val client: EntityId) : Referent {
        override val kind: SubjectKind get() = SubjectKind.CLIENT
    }

    /**
     * A body of work across its strands, held as the name they share.
     *
     * **A name rather than an identifier, because nothing mints one.** The strands are clients and
     * the work is no entity, so there is no row to point at and no identifier to carry — what makes
     * two strands one body of work is that their labels are built on one name, and that name is
     * therefore the whole of what identifies it. Two reads of one request produce the same referent
     * because they reduce the same labels.
     *
     * [name] is the reduced leading name and is never shown to anybody: it is a key, and a reduced
     * name is one no conversation holds. What a reader and a model see is [Candidate.name], which
     * borrows a strand's own label.
     *
     * **Nothing is read for it directly.** The strands travel with it as the subject's context —
     * each as the client it is and the conversation that client was read from — so the reach is the
     * conversations of every strand without anything having to resolve a name back to rows.
     */
    data class OfBodyOfWork(val name: String) : Referent {
        override val kind: SubjectKind get() = SubjectKind.BODY_OF_WORK

        init {
            require(name.isNotBlank()) {
                "A body of work with no name is not one: the name the strands share is the only " +
                    "thing that identifies it, so a blank one would make every label that " +
                    "reduces to nothing the same body of work."
            }
        }
    }

    /** A conversation, held as the source's own identifier because it is a record, not an entity. */
    data class OfConversation(val conversation: ExternalId) : Referent {
        override val kind: SubjectKind get() = SubjectKind.CONVERSATION
    }

    data object Inbox : Referent {
        override val kind: SubjectKind get() = SubjectKind.INBOX
    }
}

/**
 * One thing a name might have meant.
 *
 * [parents] are the broader subjects this sits under — a contact's body of work, a topic's
 * supergroup — and are what make composing decidable without a second read. They are reported by
 * whatever matched, because only that read knows the relation.
 *
 * **Several rather than one, because a body of work can be several clients.** Where one keeps a
 * thread per strand of it, each strand is a client and the body of work is no entity at all: the
 * only way to say a contact sits under it is to name every client of it, and naming one would
 * assert that the contact belongs to that strand. Empty is the ordinary case for the broadest
 * subjects and for anything whose parent could not be determined; one element is the ordinary case
 * for everything else. **It is a hierarchy and not a menu** — the elements are what this subject is
 * answered *against*, not things the name might have meant instead — so a reader must never present
 * them as options.
 *
 * **For everything but a body of work they are the broader subject**, held as the one record that
 * carries its label or as the several that do.
 *
 * **A body of work carries its strands here, which reads the other way round and is the same
 * field.** What the compose rule answers is whatever nothing else here sits above, so the work
 * naming its strands is what makes the work the subject and each strand the material it is read
 * through. Named by the strands instead, the work would be their broader subject and every strand a
 * competing reading of its name, which is the question this build stopped asking. The elements are
 * then several different clients and the conversations their labels were read from, rather than
 * several records of one subject — which is why the sentence above says *answered against* and not
 * *broader*.
 *
 * [name] is nullable because a record may carry no label and still be the best match on a
 * claim. Nothing renders it as prose; it reaches the model as a label.
 *
 * [handle] is the subject's own unique name where it has one — a person's account handle. It is
 * carried because a label is not a key: two subjects of a kind can share one, and an interface
 * offering a choice between them needs something a reader can name that means exactly one of them.
 * A handle resolves through [SubjectSearch.matching] like any other name, which is what lets it
 * answer the question a label leaves open. Null for everything that carries none — a conversation
 * has no handle, and nor does a person nobody set one for.
 *
 * [isSelf] says this subject is the account the corpus was read through — the person asking,
 * rather than one of the people a question could be about. False for every other kind of subject
 * and for a corpus where nothing is marked, which is what makes it safe to read without asking
 * first whether the question was ever answerable.
 */
data class Candidate(
    val referent: Referent,
    val name: String?,
    val score: Double,
    val parents: List<Referent> = emptyList(),
    val handle: String? = null,
    val isSelf: Boolean = false,
) {

    val kind: SubjectKind get() = referent.kind

    companion object {
        /**
         * The inbox.
         *
         * Its score is not a match strength and nothing ranks it: the inbox is reached by a
         * request having named nobody, so there was no matching for it to have won. The value
         * is fixed rather than zero because zero reads as the worst match rather than as no
         * match having happened.
         */
        fun inbox(): Candidate = Candidate(referent = Referent.Inbox, name = null, score = 1.0)
    }
}

/**
 * What the request is about: one subject, and whatever broader subjects it sits under.
 *
 * [context] is a hierarchy rather than a list of alternatives. A contact and the client it
 * belongs to are not competing readings of a name — the narrow one is what is answered and the
 * broad one is what it is answered against — so collapsing them into one list would lose which
 * is which.
 */
data class Subject(val focus: Candidate, val context: List<Candidate> = emptyList())

/**
 * What a request said about its subject, before anything looked the subject up.
 *
 * **Three states rather than a nullable name, because two different things used to arrive as
 * null.** A request that names nobody in particular is the daily question the inbox exists for.
 * A request that named somebody whose name never reached here is a question about a subject,
 * with the subject missing. Collapsed into one absent value, the second is answered as the
 * first: a question about a named person is answered for the whole inbox, confidently and about
 * the wrong thing, and nothing afterwards can tell that from a sweep somebody asked for.
 *
 * So the absence of a name is split into *named nobody* and *nobody established*, and only the
 * first reaches the inbox. The inbox becomes something a request is routed to on purpose rather
 * than something it falls into when a field is empty.
 */
sealed interface SubjectName {

    /**
     * The request named this.
     *
     * Non-blank, because a blank name is the absence of one wearing a name's clothes — and
     * whichever absence it is, [None] or [Unread] says which rather than leaving the resolver to
     * guess from a string.
     */
    data class Given(val name: String) : SubjectName {
        init {
            require(name.isNotBlank()) {
                "A blank name names nothing, and which nothing it is matters: a request that " +
                    "named nobody is the inbox, and one whose name was lost is not."
            }
        }
    }

    /**
     * The request named nobody in particular, and that was established rather than assumed.
     *
     * A command carrying no argument, or free text the routing call read and reported as naming
     * no particular subject. For a shape that accepts the inbox this is the question; for one
     * that does not, it is a request with its subject missing.
     */
    data object None : SubjectName

    /**
     * Whether the request named anybody was never established.
     *
     * The routing call is what reads a name out of a sentence, and it can come back without
     * having said: a reply of one line answers which pipeline and not who, and a name it wrote
     * that is not in the request was not copied out of it. Either way nothing here knows whether
     * a subject was named, so nothing here may answer as though none was.
     *
     * **It is not a failure and it is not the inbox.** Its honest reply is a question — restate
     * the request with the subject named, or ask for the inbox, since nothing at this point knows
     * which was meant. Either is one turn, where an answer composed for the wrong subject is not
     * recoverable at all because nothing says it happened. It is not the only state answered with
     * a question: an ambiguous name asks which was meant, and one nothing can tell apart says so.
     * What is particular here is that the question is about the subject itself.
     */
    data object Unread : SubjectName
}

/** What resolution decided. */
sealed interface Scope {

    /** One subject, and the matches that lost. */
    data class Resolved(val subject: Subject, val alternatives: List<Candidate>) : Scope

    /**
     * Several unrelated matches: a menu rather than a guess.
     *
     * Ordered by score, so whatever renders it leads with the likeliest. It is still a
     * question — the ordering is a courtesy and not a decision taken.
     *
     * **Every option is something a reader can name back**, which is the whole of what answering a
     * menu is. A body of work is therefore not among them however strongly it matched: it carries a
     * strand's label, so naming it reaches that strand and not the work. See [SubjectSearch.matching]
     * for why it has no name of its own to offer.
     */
    data class Ask(val options: List<Candidate>) : Scope {
        init {
            require(options.size > 1) {
                "Asking which of one thing was meant is not a question. A single acceptable " +
                    "match resolves, and reaching here with one means the rule order was " +
                    "bypassed."
            }
        }
    }

    /**
     * Nothing acceptable matched.
     *
     * [searched] is carried so the answer can say what was looked through rather than only
     * that nothing was found — the difference between a useless reply and one that tells
     * somebody to rephrase.
     *
     * **Not [Nearest], and the two split on whether anything spelled nearly like the name was
     * found.** A name agreeing with no stored label does not establish that the corpus holds
     * nothing — it establishes that the form written is absent — so where some near form exists it
     * is the evidence against an absence and [Nearest] is the verdict.
     *
     * **Which is the split for one of the two routes here and not for the other.** A name that
     * matched something of an unacceptable kind, with nothing answerable inside it, agreed with a
     * stored label exactly — so its spelling is not what went wrong, nothing asks for a near form of
     * it, and nothing may read this as having asked. What it shares with the other route is that
     * there is nothing to work with, which is what this verdict says and the whole of what it says.
     *
     * **[name] is the name that was searched for, and it is never absent.** Both routes here run a
     * read against a name first, so there is always one to echo — and the field's being able to
     * hold nothing was what let a request with no name at all be reported as a search that failed.
     * A request that named nobody is [SubjectMissing] and a request whose name never arrived is
     * [Unread]; neither is this, and neither can build this.
     *
     * **[searched] is what the search covers, which is [SubjectSearch.covers], and not what the
     * shape accepts.** The two differ in both directions: a search reaches kinds a shape would
     * refuse, and a shape accepts the inbox, which no search reaches. Filled from the accepted set
     * it is a claim about work that did not happen — and it is rendered to a reader as the places
     * their name was looked for, which is the one thing it is for.
     *
     * Empty only where the search covers nothing, which no implementation of it does. It is a
     * capability rather than a tally, so a reader of this field learns where a name of theirs could
     * have been found and not which kinds were read on this call.
     */
    data class NotFound(val searched: Set<SubjectKind>, val name: String) : Scope {
        init {
            // The inbox is reached by having named nobody, so no name is ever matched against it
            // and it cannot be among the kinds a name was looked for in. Refused here rather than
            // stated on the port, because this is where the set becomes a claim to a reader.
            require(SubjectKind.INBOX !in searched) {
                "The inbox is never searched: it has no entity and no record to match a name " +
                    "against, so reporting it as looked through is a claim about work that did " +
                    "not happen. Fill this from what the search covers, not from what a shape " +
                    "accepts."
            }
        }
    }

    /**
     * Nothing matched the name, and the corpus holds names spelled nearly like it.
     *
     * **Not [NotFound], and the difference is what the search established rather than what it
     * returned.** Both arise from a name that agreed with no stored label, so neither has a subject.
     * That one has looked for near forms and found none, which is the closest this build comes to
     * establishing that the corpus holds nothing of the kind; this one has found some, so the name
     * that was written is the only thing shown to be absent. The next action differs, which is the
     * test the pair of keys above already applies to a name that never arrived: there is nothing to
     * find, against write the name as the corpus spells it.
     *
     * **[nearest] are spellings and not readings.** They are reported so a reader can see the form
     * their subject may be stored under, and nothing here claims one of them is what was meant. A
     * wrong one costs a glance; offered as a reading it would be a guess between a stranger and the
     * person meant, which [Ask] exists to refuse. Ordered strongest first and never empty — empty is
     * [NotFound], which is the same reply without them.
     *
     * [searched] is what the search covers, for the reason [NotFound] carries it and under the same
     * refusal of the inbox: it is rendered to a reader as the places their name was looked for.
     */
    data class Nearest(
        val nearest: List<Candidate>,
        val searched: Set<SubjectKind>,
        val name: String,
    ) : Scope {
        init {
            require(nearest.isNotEmpty()) {
                "A nearest-names verdict with no names in it is a name nothing was found for, " +
                    "which is NotFound. Reaching here with none means the caller reported a " +
                    "search it did not make."
            }
            // For the reason [NotFound] refuses it, and because this is the same claim to a reader.
            require(SubjectKind.INBOX !in searched) {
                "The inbox is never searched: it has no entity and no record to match a name " +
                    "against, so reporting it as looked through is a claim about work that did " +
                    "not happen. Fill this from what the search covers, not from what a shape " +
                    "accepts."
            }
        }
    }

    /**
     * The request named nobody, at a shape that cannot answer for the inbox.
     *
     * **Two requests reach this, and neither mentioned a subject** — see [SubjectName.None]. A
     * command that takes one, carrying no argument; and free text a routing call read and reported
     * as naming no particular subject. What they share is the whole of what this verdict says, so
     * nothing carried from it may assume a command was sent.
     *
     * **Not [NotFound], because no search ran.** That one is the verdict of a read: a name went to
     * the store and the store held nothing of an acceptable kind, so its reply names the name and
     * what was looked through. Here there was never a name, and reported as nothing-found it tells
     * whoever asked that the corpus holds nothing about a subject they never named. That is the one
     * conclusion they cannot check and the one that is wrong: what is missing is the subject, which
     * they can supply in one turn.
     *
     * **Not [Unread] either, and the difference is what the request said rather than what the reply
     * asks for.** This request said what it meant and said nobody; that one said neither, because
     * whether it named anybody was never established. Both leave a reader having to name a subject,
     * so both may well render the same way — but one verdict for the two would lose what the request
     * said, which is the distinction [SubjectName] exists to keep.
     *
     * It carries nothing. There is no name to echo and no kind was consulted, so every field it
     * could offer would be a claim about work that did not happen — which is why it is a verdict
     * rather than a nothing-found with both fields emptied: a reader of those fields would be
     * reading a cause out of two absences.
     */
    data object SubjectMissing : Scope

    /**
     * The request's subject was never established, so nothing was resolved.
     *
     * **Not [NotFound], and the difference is what the request said rather than what was done
     * about it.** A request reaches that one having named a subject the corpus does not hold: it
     * said what it meant, and the answer is that there is nothing to work with. A request reaches
     * this one having said neither, so what nobody knows is what it was about. That a search ran
     * for the one and not the other follows from the distinction rather than being it — which is
     * why [SubjectMissing] sits beside this one and not beside that one, having run no search and
     * still said what it meant. Rendered as nothing-found it would tell whoever asked that a subject they may never
     * have named is absent from the corpus, which is the wrong thing to act on: it sends them
     * looking for a gap in the material instead of restating the question.
     *
     * **Nor [SubjectMissing], which is the request that named nobody on purpose.** The two ask the
     * same thing of a reader and are kept apart for what they say about the request: there, nobody
     * was named and that is established; here, whether anybody was named is not.
     *
     * It carries nothing for the same reason: there is no name to echo and no kind to name. What
     * is missing is what the request was about, and only whoever asked can supply it.
     */
    data object Unread : Scope
}
