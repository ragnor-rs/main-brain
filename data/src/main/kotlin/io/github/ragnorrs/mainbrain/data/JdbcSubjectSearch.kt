package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.SubjectSearch
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Name search across the corpus and the entity layer at once.
 *
 * **One query over three tables rather than three queries, because the ranking has to be one
 * ranking.** A name is as likely to be a conversation as a contact, and asking the kinds in turn
 * would make the order the tie-breaker — the first kind to match would win on evidence weaker than a
 * later kind's. The union is also what makes the rarity count mean anything: a name is common or rare
 * across everything that carries it, not within one table.
 *
 * **The score is absolute and comparable between two reads**, which is what the port requires and
 * what a retrieval score could not give. For every row this reads it is the product of how well the
 * strings agree and how rare the name is, and the rarity factor is the same shape recognition uses for
 * a display name — a value carried by one subject counts in full, one carried by a crowd counts for
 * almost nothing. Two separate reads therefore produce numbers that can be ordered against each other,
 * which the traverse rule depends on when it merges what several referents contain.
 *
 * **The one candidate that is not a row takes a row's number rather than computing its own.** A body
 * of work is composed from the client rows that matched, so it carries the strongest of their scores —
 * see [bodyOfWorkAmong]. That keeps it commensurable with everything here by construction, and it is
 * why nothing above needs qualifying: the product is still how every number in this file is made.
 *
 * **That claim is about the matching reads and not about [nearest].** Every row that one returns
 * agreed on an opening rather than on a word, so all of them sit in one agreement band and the
 * score reduces to rarity — which measures how strongly a name means a subject, and nothing there
 * means a subject. Those numbers are therefore not commensurable with a match's, and nothing
 * compares them: that read orders by how much of the name agrees and hands its result to a reply
 * rather than to a rule.
 */
class JdbcSubjectSearch(private val database: Database) : SubjectSearch {

    /**
     * The kinds a name can come back under here, and nothing else.
     *
     * Written out rather than derived from [SubjectKind], so adding a kind to the enum does not
     * silently claim this read covers it — the enum is every kind a request can be about and this
     * is the subset a name can be matched against. Written out rather than read off the query too,
     * because nothing parses the SQL: what keeps the two in step is the store suite, which asserts
     * this set against the kinds one name actually comes back under, so a branch added to
     * [MATCHING] without an entry here fails there.
     *
     * **A body of work is in it although no branch of the union is for one.** It is reported off the
     * client rows rather than matched by a read of its own, since nothing stores the name its
     * strands share — but a reader naming it reaches it, and what this set is for is telling them
     * where a name of theirs could have been found. Left out, it would be the one scope this build
     * resolves and does not admit to looking in.
     *
     * The inbox is absent because there is no table to union for it: it is a mode of a request, not
     * a thing a name can agree with.
     */
    override val covers: Set<SubjectKind> = setOf(
        SubjectKind.CONTACT,
        SubjectKind.CLIENT,
        SubjectKind.BODY_OF_WORK,
        SubjectKind.CONVERSATION,
    )

    override suspend fun matching(name: String): List<Candidate> {
        val normalised = normalise(name)
        if (normalised.isEmpty()) return emptyList()
        return database.query { connection ->
            // Folded by the store, which is where the fold is defined, because the same fold has to
            // reach the stored labels — see `name_fold`'s own note.
            val labelQuery = connection.folded(normalised)
            val labelPattern = wordStart(labelQuery)
            // **The handle is compared unfolded**, which is `name_fold`'s own rule: a handle is the
            // source's key, carried by one subject, and what the fourth branch needs of it is
            // exactness. It is also matched anywhere in the value rather than at a word's start,
            // because a handle has no words — a reader who writes one without its marker has named
            // their subject exactly and must still reach it.
            val handleQuery = asHandle(normalised)
            // A name that folds away entirely names nobody, and the pattern is null rather than
            // empty so the statement says so: an empty one would read as *a space somewhere in the
            // label*, which almost every label has. The handle form needs no such guard — it is
            // compared by position rather than by a pattern, and an empty string is found in
            // everything, so it is the emptiness of the typed name that stops it, above.
            if (labelPattern == null) return@query emptyList()
            // **Read strictly first, and only widen if that found nothing.** The language the
            // interface answers in inflects a name, so a question names somebody in whatever case
            // the sentence puts them in while a label carries the plain form — and the routing stage
            // hands the name over as it was written, by design, because a name it composed rather
            // than copied would be a search spent on nothing. Comparing the two as they stand finds
            // the subject only when the sentence happened to need the plain form.
            //
            // Ordering the two reads this way is what makes the tolerance free of regression: a name
            // that resolves today takes the first read and never reaches the second, so nothing that
            // matches can start matching differently. The second read only ever runs where the
            // answer was otherwise *nothing found*, which is the one situation a wider match cannot
            // be worse than.
            val found = connection.matches(labelPattern, handleQuery, CEILING)
                .ifEmpty {
                    stems(labelQuery)?.let { connection.matches(it, handleQuery, CEILING) }.orEmpty()
                }
            val rows = found.withoutDirectChats()
            // Contact rows resolved to the humans they stand for before anything is counted or
            // scored. A merged account must not be offered as a subject of its own: it is the same
            // person as its head, and reads narrowed by it would see only part of what they said.
            val heads = connection.headsOf(rows.mapNotNull { it.contactEntity() })
            // The rarity count is over what this read found rather than over the whole corpus. A
            // name matching one subject is unique among the things it could have meant, which is
            // the question being scored — and counting corpus-wide would need a second pass over
            // every table to tell a rare name from one this query merely filtered down to.
            //
            // Counted over distinct subjects and not over rows, because two accounts of one human
            // are one thing the name could have meant. Counting the rows would halve the score of
            // every name that a merge made more certain rather than less.
            val carriers = rows.groupBy { it.name }
                .mapValues { (_, found) -> found.distinctBy { it.carrier(heads) }.size }
            val clients = connection.clientsOf(rows.mapNotNull { it.contactRecord() })
            // Read for every contact row rather than only for the ones a handle matched. A handle is
            // what a reader names to pick one of two subjects that share a label, so it has to be on
            // the candidate whatever the query happened to agree with.
            val handles = connection.handlesOf(rows.mapNotNull { it.contactRecord() })
            // Asked of the humans these rows resolve to rather than of the rows, which is what makes
            // two accounts of one reader one subject that is read through rather than two.
            val selfHeads = connection.selfClusters(rows.mapNotNull { it.head(heads) })
            // **Scored against the query the row was matched with, which is not always the one that
            // was typed.** A handle row matched the marker-less form, so scoring it against the typed
            // one would miss `EXACT` and `PREFIX` for every reader who typed the marker — which is
            // the form the menu teaches. And both sides of the comparison are folded forms, which is
            // the same rule once more: a name that resolved by crossing a script agrees with the
            // label exactly, and scoring it against what was typed would leave the strongest
            // agreement there is at the weakest band.
            val candidates = rows.map {
                it.toCandidate(labelQuery, handleQuery, carriers, clients, heads, handles, selfHeads)
            }.bestPerSubject()
            candidates + bodyOfWorkAmong(rows, candidates)
        }
    }

    /**
     * The body of work the matched clients name, as a one-element list, or none.
     *
     * **One candidate the corpus holds no label for, because nothing stores the name its strands
     * share.** A body of work keeping a thread per strand of it is several clients and no entity, so
     * a name that picks out the work matched every strand of it and nothing said they were one piece
     * of work — the strands competed, and the reader was asked which they meant when what they asked
     * for was all of them. This is the thing they named, reported beside the strands it is made of.
     *
     * **Reported only where the matched clients reduce to one name.** A query agreeing with a word
     * two different bodies of work both use *after* their leading name — the kind of work rather
     * than whose it is — matches labels that lead differently, so this answers nothing and the
     * caller's menu stands. Strands named differently stay two for the same reason: nothing here
     * decides *what a label means*, which is the narrowing pass's.
     *
     * **A label that reduces to nothing is no body of work and takes no part**, which is the rule
     * the two reads that count bodies of work already apply — a count passes over it. So it neither
     * joins a body of work nor stops one being reported, and such a client stays a competing
     * reading of the name on its own.
     *
     * **What the rule cannot separate is two pieces of work that share their leading name**, and
     * that is the cost of settling this by a rule rather than by a judgement. Those reduce to one
     * name and are read as one body of work, so a question about either is answered across both
     * with no menu offered. It is the same tolerance the count behind a section's heading and a
     * contact's parent already carry, and it is paid for more here: there the price was a grouping
     * somebody is filed under, and here it is the material an answer is composed from. Accepted on
     * the same terms, because the alternative is a name that picks out several threads of one piece
     * of work resolving to a question nobody can answer — and a corpus whose labels lead with a
     * kind of work rather than whose it is would need the narrowing pass before any of the three
     * reads is worth trusting.
     *
     * **And only for several strands.** One client is a body of work already and resolves as one, so
     * a second referent for it would be the same subject twice and a menu between two readings that
     * answer identically.
     *
     * **And only where the strands carry distinct labels.** Two clients labelled the same way are
     * two things the name could have meant — which is the position the rarity count already takes
     * of them — and a label cannot say which of them a thread belongs to, so nothing here can say
     * they are strands of one piece of work rather than two pieces of work named alike. Reported
     * as one, a question about either would be answered from both organisations' material under
     * the name the reader typed, with no menu and nothing in the reply saying so: the error the
     * compose rule calls the one that is invisible afterwards. So the menu stands, which is what
     * those two did before and what they do in every other read.
     *
     * **Asked by [matching] and not by [nearest].** A near spelling is shown to a reader and never
     * resolved to, and a body of work is nothing to show: it carries a strand's own label, so it
     * would arrive there as a second copy of a spelling already on the list.
     *
     * **The strands travel on it as [Candidate.parents], each as the client it is and the
     * conversation that client was read from.** That is what the compose rule reads to decide which
     * subject is answered: the narrow subject is the one nothing else here sits above, so the work
     * naming its strands makes the work the subject and the strands the material it is read through.
     * The conversation is named as well as the client because a client's label is read *from* a
     * conversation, so both rows match the same name — and a strand left out of the list stays a
     * competing reading, which is the question this exists to stop asking.
     *
     * **Its label is a strand's own and never the reduced name**, which is one no conversation holds
     * and nothing a reader could look up. The lexically first of them wins, which is the tie-break
     * the grouping read takes for the same choice — so the strand that stands for the work on a
     * subject line is the strand that heads its section, rather than the two reads naming different
     * threads of one piece of work.
     *
     * **Its score is the best of its strands'**, which needs no scoring rule of its own and is
     * commensurable with everything else here by construction: it is one of their numbers. The work
     * is as strong a reading of the name as the strongest record of any strand of it, which is what
     * a reader naming the work has in fact established.
     *
     * **Which leaves it carrying the rarity its strands carry, counted before it existed.** Where
     * two strands are labelled identically the name counts two carriers, so each of them — and the
     * work with them — scores half of what a single-threaded body of work of that name would. The
     * count is over the readings a name could have meant and the work is composed from those
     * readings rather than being one more of them, so it is not added to the denominator either.
     * Nothing turns on it: a score orders a menu, and which subject is answered is decided by the
     * relation the matches report.
     */
    private fun bodyOfWorkAmong(rows: List<Match>, found: List<Candidate>): List<Candidate> {
        val strands = rows.filter { it.kind == Match.Kind.CLIENT && it.body != null }
            .distinctBy { it.entity }
        val name = strands.mapNotNull { it.body }.distinct().singleOrNull()
            ?.takeIf { strands.size >= STRANDS }
            ?.takeIf { strands.distinctBy { strand -> strand.label }.size == strands.size }
        // Both records of each strand: the client and the conversation its label was read from carry
        // one name, so a query that reached the client reached the conversation too. A referent
        // nothing matched is passed over by the compose rule rather than failing it, which is what
        // makes naming both safe on a corpus where only one of them came back.
        val records = strands.flatMap { strand ->
            listOf(
                Referent.OfClient(EntityId(requireNotNull(strand.entity))),
                Referent.OfConversation(strand.record),
            )
        }
        val strongest = found.filter { it.referent in records }.maxByOrNull { it.score }
        // Nothing where either is absent, which is the two refusals above and one that cannot
        // happen: a strand came back from this read, so a candidate for it is in the same result.
        return if (name == null || strongest == null) {
            emptyList()
        } else {
            listOf(
                Candidate(
                    referent = Referent.OfBodyOfWork(name),
                    name = strands.mapNotNull { it.label }.minOrNull(),
                    score = strongest.score,
                    parents = records,
                ),
            )
        }
    }

    /**
     * The stored labels whose words open the way the typed name's words do.
     *
     * **A reduction of the same comparison [matching] makes, and not a different one.** Both sides
     * are folded, both are anchored at a word's start, and the words have to appear in order — what
     * moves is how much of each word has to agree: [NEAR_LETTERS] of it rather than all of it. So
     * this reaches a name whose stored form diverges after its opening, which is exactly the form a
     * reader cannot guess at: the English rendering of a given name beside its transliteration, or a
     * short form the source registered instead of the full one.
     *
     * **Why a reduced opening and not an edit distance.** Measured against the archive, how much of
     * two names agrees does not say whether they are one name: the pairs that are one name agree on
     * their opening and diverge after it, while the pairs that are two names — a surname built on
     * somebody else's given name — agree on more than that and are further apart. A distance
     * threshold therefore ranks the wrong ones higher, which is why none is used, and why this is
     * reported rather than resolved.
     *
     * **Nothing here is a reading of the name, and the weakness is the reason.** A reader is shown
     * these as spellings the corpus holds so they can ask again in one; resolved to, a wrong one
     * would be an answer about a stranger. That is why the port forbids a caller resolving to them,
     * and why a bound on how many are shown costs only a spelling nobody was going to read.
     *
     * **Ordered by how much of the name actually agrees, which is not what [Candidate.score] says
     * here.** Every row of this read agreed on an opening and not on a word, so [strength] puts all
     * of them in one band and the score reduces to rarity — a measure of how strongly a name means
     * a subject, which is the question [matching] asks and this one does not. Ordering by it would
     * put a spelling two subjects share below an unrelated unique one. So the ordering is the
     * length of the agreement itself, which is the only thing here that measures nearness, with the
     * score breaking its ties.
     *
     * **It reads past what it will show, so that the bound and the ordering do not fight.** The
     * statement stops at a row count, and it orders by label length rather than by agreement, so a
     * bound close to what is offered would let the four shown be decided by where the read stopped
     * instead of by which spellings are nearest. [NEAR_READ] is therefore far above
     * [NEAR_OFFERED]. It is still a bound: on a corpus where more labels than that share one
     * opening, a nearer spelling can be cut before the ordering ever sees it, and what that costs
     * is a reply listing further spellings than it might have — never a wrong answer, since none of
     * these is resolved to.
     *
     * The handle is passed as a form that matches nothing and the rows it would reach are dropped
     * besides, because a handle is a key and a key is either typed or not: a reduced handle is a
     * different handle, and offering the holder of one as a near spelling of another would be this
     * read inventing a subject. A name too short to reduce returns nothing rather than a pattern
     * most of the corpus satisfies.
     */
    override suspend fun nearest(name: String): List<Candidate> {
        val normalised = normalise(name)
        if (normalised.isEmpty()) return emptyList()
        return database.query { connection ->
            val labelQuery = connection.folded(normalised)
            val pattern = openings(labelQuery) ?: return@query emptyList()
            connection.matches(pattern, NO_HANDLE, NEAR_READ)
                // **Dropped here as well as declined in the statement, so the property holds whatever
                // the corpus turns out to contain.** The handle branch is the one with no label
                // predicate, so a stored handle that happened to contain the marker would make its
                // row a near spelling of every name ever typed — and that row projects a label that
                // may be null, which is the one state the reply cannot render. Refused on the row
                // rather than argued from what a source writes.
                .filterNot { it.byHandle }
                .withoutDirectChats()
                .let { rows ->
                    val heads = connection.headsOf(rows.mapNotNull { it.contactEntity() })
                    val carriers = rows.groupBy { it.name }
                        .mapValues { (_, found) -> found.distinctBy { it.carrier(heads) }.size }
                    val clients = connection.clientsOf(rows.mapNotNull { it.contactRecord() })
                    val handles = connection.handlesOf(rows.mapNotNull { it.contactRecord() })
                    val selfHeads = connection.selfClusters(rows.mapNotNull { it.head(heads) })
                    rows.map { row ->
                        Near(
                            candidate = row.toCandidate(
                                labelQuery = labelQuery,
                                handleQuery = NO_HANDLE,
                                carriers = carriers,
                                clients = clients,
                                heads = heads,
                                handles = handles,
                                selfHeads = selfHeads,
                            ),
                            agreement = agreement(labelQuery, row.name),
                        )
                    }
                }
                // A spelling is what this hands back, so a row carrying none is nothing to hand
                // back. Unreachable through the three label branches, which all require a label to
                // have matched; dropped rather than relied on, because an empty list under a key
                // that announces spellings is the reply this read exists to replace.
                .filter { it.candidate.name != null }
                .sortedWith(compareByDescending<Near> { it.agreement }.thenByDescending { it.candidate.score })
                .map { it.candidate }
                .bestPerSubject()
                // The account the corpus was read through is dropped for the reason the menu drops
                // it: in a personal archive it carries the reader's own name, so every name sharing
                // an opening with it would offer the reader themselves as a spelling of somebody
                // else.
                .filterNot { it.isSelf }
                // **One entry per spelling, which is the opposite of what the menu does with the
                // same rows.** A menu lists two subjects that share a label and tells them apart by
                // their handles, because the reader is choosing between them. Here they are not
                // subjects at all — they are forms to write — and two of them read identically, so
                // the second teaches a reader nothing and spends one of the few lines on offer.
                // Measured on the archive: labels do repeat across records there, so this is the
                // ordinary case rather than a guard against a hypothetical one. Taken after the
                // ordering, so the row kept is the nearest one carrying that spelling.
                .distinctBy { it.name }
                .take(NEAR_OFFERED)
        }
    }

    /** One near spelling and how much of the typed name it agrees with, while the two are ordered. */
    private class Near(val candidate: Candidate, val agreement: Int)

    override suspend fun within(referent: Referent): List<Candidate> {
        // A contact contains nothing — a person is not a container — and the inbox is not a
        // referent anything is inside. A body of work is a third case with a reason of its own: it
        // is reported only alongside every strand it is made of, so the hop the caller is making
        // reaches those contacts through the strands themselves and answering here would hand each
        // of them back twice. All three return empty without a query rather than running one that
        // cannot match, since this is called on the traverse path for every unacceptable kind.
        val client = (referent as? Referent.OfClient)?.client
        val conversation = (referent as? Referent.OfConversation)?.conversation
        if (client == null && conversation == null) return emptyList()

        return database.query { connection ->
            val found = connection.prepareStatement(WITHIN).use { statement ->
                statement.bind {
                    entityId(client)
                    entityId(client)
                    externalId(conversation)
                    entityId(client)
                    entityId(client)
                    externalId(conversation)
                    int(CEILING)
                }
                statement.executeQuery().use { rows ->
                    rows.collect { row -> row.toInhabitant() }
                }
            }
            // Resolved and deduplicated for the reason [matching] resolves: two accounts inside one
            // container are one person in it, and offering both would make a container look more
            // populated than it is while giving each half of that person's material.
            val heads = connection.headsOf(found.map { it.contact })
            // For the reason [matching] reads them: these candidates reach a menu by the same route,
            // and two people of one name inside one container are as indistinguishable there.
            val handles = connection.handlesOf(found.map { it.record })
            // **And this one matters more here than it does there.** What is inside a container is
            // read from who wrote in it, and the account an archive is read through wrote in
            // everything — so without this it is among the people offered for every body of work
            // in the corpus, every time a name resolves by traversal.
            val selfHeads = connection.selfClusters(found.map { heads[it.contact] ?: it.contact })
            found.map { inside ->
                Candidate(
                    referent = Referent.OfContact(heads[inside.contact] ?: inside.contact),
                    name = inside.label,
                    // Everything inside one container is equally inside it: nothing here matched a
                    // name, so there is no agreement to score. The value is the same for all of them
                    // and the caller's own ordering decides.
                    score = WITHIN_SCORE,
                    parents = listOf(referent),
                    handle = handles[inside.record],
                    isSelf = (heads[inside.contact] ?: inside.contact) in selfHeads,
                )
            }.bestPerSubject()
        }
    }

    /**
     * These matches with the direct conversations dropped from among them.
     *
     * **A person and the direct conversation with them are one subject held in two records, and
     * offering both as readings of a name is a question with no answer.** Both carry the same label,
     * so a menu lists it twice and the only way to answer a menu is to name the option that was
     * meant — which returns the same menu. Every person with a direct chat was unanswerable this
     * way, which is the most ordinary subject there is.
     *
     * **Recognised by record identity rather than by the names agreeing.** A source that addresses a
     * one-to-one conversation by the identifier of the person on the other side of it has said the
     * two are the same thing; two subjects that merely share a name have not. So the test is the
     * record pair, which is also why no group conversation can be caught by it.
     *
     * The contact is what survives, because it is the richer referent: it is an entity, so the
     * derived layer can be narrowed by it, and it carries the handle that tells two people of one
     * name apart. [JdbcRetrieval.conversationsOf] reaches that same direct chat from the contact and
     * reads it ahead of every other thread, so dropping the candidate costs the answer nothing —
     * with one condition worth knowing, because the reach is through authorship: a chat the person
     * never wrote in is not among a contact's threads at all. Somebody who was messaged and never
     * replied is therefore answered from what the derived layer holds about them and not from the
     * thread. That was unreachable before this collapse too, since the menu it produced could not
     * be answered.
     */
    private fun List<Match>.withoutDirectChats(): List<Match> {
        val people = mapNotNull { it.contactRecord() }.toSet()
        return filterNot { it.isConversation && it.record in people }
    }

    /** One contact found inside a container: who it is, what it is called, and its own record. */
    private class Inhabitant(val contact: EntityId, val label: String?, val record: ExternalId)

    /**
     * One row of the union, before it has been scored.
     *
     * One parameter per projected column, which is what the long-parameter rule is counting: the
     * shape is the statement's, and grouping them into something smaller here would only move the
     * correspondence somewhere a reader has to reassemble it.
     */
    @Suppress("LongParameterList")
    private class Match(
        val kind: Kind,
        val entity: UUID?,
        val record: ExternalId,
        val label: String?,
        val name: String,
        /**
         * Whether [name] is this subject's handle rather than its label.
         *
         * Carried because scoring has to compare against the query the row was matched with, and
         * the handle branch matched a marker-less form of it. Read from the statement rather than
         * inferred from [kind], since a contact row can arrive from either branch.
         */
        val byHandle: Boolean,
        val clientOfRecord: UUID?,
        /**
         * The body of work this row's label names, for a client row, lowercased.
         *
         * Null on every other kind — nothing else is a strand of a piece of work — and null for a
         * client whose label reduces to nothing, which is no body of work rather than one more.
         */
        val body: String?,
    ) {

        /** The person record behind this row, if it is a contact — what a client is looked up by. */
        fun contactRecord(): ExternalId? = if (kind == Kind.CONTACT) record else null

        /** Whether this row is a thread rather than a person or an organisation. */
        val isConversation: Boolean get() = kind == Kind.CONVERSATION

        /** The contact identifier this row carries, if it is a contact — what is resolved to a head. */
        fun contactEntity(): EntityId? =
            if (kind == Kind.CONTACT) EntityId(requireNotNull(entity)) else null

        /** The human this row stands for, if it is a contact. An identifier absent from [heads] is one. */
        fun head(heads: Map<EntityId, EntityId>): EntityId? =
            contactEntity()?.let { heads[it] ?: it }

        @Suppress("LongParameterList")
        fun toCandidate(
            labelQuery: String,
            handleQuery: String,
            carriers: Map<String, Int>,
            clients: Map<ExternalId, List<UUID>>,
            heads: Map<EntityId, EntityId>,
            handles: Map<ExternalId, String>,
            selfHeads: Set<EntityId>,
        ): Candidate = Candidate(
            referent = referent(heads),
            name = label,
            score = strength(if (byHandle) handleQuery else labelQuery, name) * rarity(carriers[name]),
            parents = parents(clients),
            // Read for people only, which is narrower than *who has one*: a source hands a public
            // room a handle as readily as a person, and ingestion records it. What a room's handle
            // would make answerable is two same-named rooms, and this build resolves a handle
            // through the person record alone — so those are reported as indistinguishable rather
            // than told apart. A client has none to read either way.
            handle = contactRecord()?.let { handles[it] },
            // People only, for a reason of its own rather than this one's: a body of work and a
            // thread are not accounts, so neither is a thing a corpus could have been read through.
            isSelf = head(heads)?.let { it in selfHeads } == true,
        )

        /**
         * What this row names, with a contact resolved to the human it belongs to.
         *
         * Only contacts resolve: a client and a conversation are records rather than people, and
         * nothing merges them. An identifier absent from [heads] is already a head.
         */
        fun referent(heads: Map<EntityId, EntityId>): Referent = when (kind) {
            Kind.CONTACT -> EntityId(requireNotNull(entity)).let { Referent.OfContact(heads[it] ?: it) }
            Kind.CLIENT -> Referent.OfClient(EntityId(requireNotNull(entity)))
            Kind.CONVERSATION -> Referent.OfConversation(record)
        }

        /**
         * What this row counts as, when counting how many subjects a name could have meant.
         *
         * **A client and the conversation its name was read from are one subject in two tables.**
         * Identity reads a client's label *from* a conversation, so every client in the corpus
         * shares its name with exactly one conversation by construction — and counting the two
         * apart halves the rarity of every client name while a contact's stays whole. That is the
         * mistake the note at the call site forbids for two accounts of one human, in the other
         * direction: [parents] already states that these two compose rather than compete, and this
         * is the count agreeing with it.
         *
         * Keyed on the record, which is the conversation's own identifier on both rows, so the two
         * collapse without either kind knowing about the other. Two *different* clients of one name
         * still count twice, and so does a client sharing a name with some unrelated conversation —
         * those are genuinely two things the name could have meant. A contact counts by its head,
         * which is the only kind anything merges.
         */
        fun carrier(heads: Map<EntityId, EntityId>): Referent = when (kind) {
            Kind.CONTACT -> referent(heads)
            Kind.CLIENT, Kind.CONVERSATION -> Referent.OfConversation(record)
        }

        /**
         * The broader subjects this sits under, where they can be known without inventing them.
         *
         * A conversation that was read as a client sits under that client, which is what makes the
         * two compose rather than compete when a name matches both. A contact sits under a body of
         * work when exactly one holds it — a contact writing in several bodies of work has no
         * single parent, and choosing one would assert a hierarchy the corpus does not state.
         *
         * **Several for a contact, because one body of work can be several clients.** Where it
         * keeps a thread per strand of it, the strands are clients and the body of work is not an
         * entity at all — so what this can hand back is every client of it, and a reader of the
         * list is told *this body of work* without any one strand being named as the parent. One
         * element is the ordinary case and the list is what makes the other expressible.
         *
         * A client sits under nothing: it is the broadest subject there is.
         */
        private fun parents(clients: Map<ExternalId, List<UUID>>): List<Referent> = when (kind) {
            Kind.CLIENT -> emptyList()
            Kind.CONVERSATION -> listOfNotNull(clientOfRecord?.let { Referent.OfClient(EntityId(it)) })
            Kind.CONTACT -> clients[record].orEmpty().map { Referent.OfClient(EntityId(it)) }
        }

        enum class Kind { CONTACT, CLIENT, CONVERSATION }
    }

    internal companion object {

        /**
         * How much the strings agreeing is worth, before rarity.
         *
         * Three bands rather than an edit distance. The question is whether somebody meant this
         * subject, and the ways a person under-specifies a name are these: they type it, they type
         * its beginning, or they type a word from inside it. An edit distance would also reward a
         * typo, which is a different and much weaker kind of agreement, and would make the score
         * depend on name length.
         *
         * **A label and the query against it are both folded forms**, so a name written in one
         * script scores against a label stored in another exactly as it would against its own — the
         * band is about how much of the name was given, and which script it was given in is not a
         * kind of under-specification. A handle and its query are both unfolded, for the reason
         * [MATCHING] gives. Either way the two strings compared here are the two the statement
         * matched on.
         *
         * There is no band for *no agreement*, and [matching] cannot reach one: a label row matched
         * [wordStart], so the query begins one of its words, and a handle row contains the query
         * somewhere. [nearest] does reach one, and lands in [CONTAINS] — its rows agreed on an
         * opening and not on a word, so none of them can be exact or a prefix. That the weakest
         * band holds both a word typed from inside a name and a name merely spelled nearly like one
         * does not blur anything a caller reads: the two arrive from different reads, one of them
         * resolves and the other is only ever shown, and nothing compares a score across them.
         */
        fun strength(query: String, name: String): Double = when {
            name == query -> EXACT
            name.startsWith(query) -> PREFIX
            else -> CONTAINS
        }

        /**
         * How much a name counts, given how many subjects carry it.
         *
         * The same shape recognition scores a display name with: carried by one, it counts in full;
         * carried by a crowd, almost nothing. A name matching a single subject is the strongest
         * evidence a name can be, which is why the floor is one rather than two — unlike the
         * pairwise case, where the pair itself is always two carriers.
         */
        fun rarity(carriers: Int?): Double =
            ALONE.toDouble() / (carriers ?: ALONE).coerceAtLeast(ALONE)

        /**
         * [name] as a handle would be written in the corpus, which is without the marker.
         *
         * A reader types the marker because that is how the platform shows a handle; the corpus
         * stores it normalised — lowercased and without the marker — which is
         * [io.github.ragnorrs.mainbrain.domain.record.IdentifierType]'s doing rather than the platform's.
         * Stripped here rather than in
         * [normalise], because a display name is matched by the same normalisation and a marker
         * inside one is part of it.
         *
         * A marker with nothing after it is left as it is, so what the handle branch is given is a
         * marker — which no stored handle contains, since the corpus stores them without one. It
         * therefore matches no handle, which is right: nobody named a subject.
         */
        fun asHandle(name: String): String =
            name.removePrefix(HANDLE_MARKER).ifEmpty { name }

        const val HANDLE_MARKER = "@"

        /** What [MATCHING]'s fourth branch writes into `matched_as`, naming the string it compared. */
        const val MATCHED_AS_HANDLE = "HANDLE"

        /**
         * Trim, collapse runs of whitespace, lowercase.
         *
         * **Not the comparison for a label** — `name_fold` is, and the statement applies it to the
         * typed name and the stored label alike. What this is still for: it tells a name that is
         * nothing but whitespace from one worth a round trip, before any read happens; it is the
         * whole of the comparison the handle branch makes, which is deliberately unfolded; and
         * trimming is what leaves a typed marker at the front of the string, where [asHandle] looks
         * for it. Everything it does to a label, the fold does again, so applying it first changes
         * no label outcome.
         */
        fun normalise(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase()

        val WHITESPACE = Regex("\\s+")

        /**
         * [folded] as a pattern matching it at the start of a word, or null if it folded to nothing.
         *
         * **A name agrees with a label where a word of the label begins with it, and nowhere else.**
         * Somebody under-specifies a name by typing its beginning or by typing a word from inside
         * it, and both of those start a word; what starts mid-word is a coincidence of letters. The
         * cost of allowing one is not a weaker match but a question with no answer — a short given
         * name landing inside an unrelated surname is a second subject in the menu, and the reader
         * is asked to choose between the person they meant and a stranger.
         *
         * Open at the end, which is what keeps the beginning of a name a match: a reader typing the
         * first four letters of somebody's given name means them, and the band above already scores
         * that below an exact agreement. Anchored by a space because the fold leaves exactly one
         * between words; [MATCHING] pads the label's own start so that its first word is reachable
         * by the same pattern.
         *
         * Null rather than a pattern for a name that folded away, because what the statement needs
         * to be told is that nothing was named.
         */
        fun wordStart(folded: String): String? = folded.ifEmpty { null }?.let { "% $it%" }

        /**
         * [folded] with each word shortened to its stem, as a pattern, or null if nothing would be.
         *
         * **A tolerance for an inflected name, and deliberately not a stemmer.** The corpus's
         * language puts a name in whatever case the sentence needs, so what reaches this is a form
         * ending in letters the label does not carry — and the ending is the only part that moves.
         * Dropping the last letters of each word and matching what is left at the start of a word
         * finds the label without anything here having to know which ending it was: a genitive, a
         * dative and an instrumental of one name all reduce to the same stem as the plain form does,
         * because they differ from it only after that point.
         *
         * **A word is shortened by what an ending can be and no more** — two letters where the word
         * can spare them, one where it can spare only that, and nothing at all below five. The floor
         * is the point: the shortest names are where a stem stops being evidence, and they are the
         * names that landed inside a stranger's surname before [wordStart] anchored the comparison.
         * A four-letter name is never shortened, so the fault that rule closed cannot reopen here.
         *
         * **The words are anchored but not joined.** Each stem has to begin a word of the label and
         * they have to appear in order, with anything between them, because it is the words of a
         * name that inflect and not the gaps: a reader writing two inflected words would otherwise
         * have to have both of them end exactly as the label does.
         *
         * Null where no word is long enough to shorten, since the pattern would then be the strict
         * one that has already been tried.
         */
        fun stems(folded: String): String? {
            val words = folded.split(" ").filter { it.isNotEmpty() }
            val shortened = words.map { word ->
                when {
                    word.length >= TWO_LETTER_ENDING -> word.dropLast(2)
                    word.length >= ONE_LETTER_ENDING -> word.dropLast(1)
                    else -> word
                }
            }
            if (shortened == words) return null
            return shortened.joinToString("% ", prefix = "% ", postfix = "%")
        }

        /** The length at which a word can spare two letters to an ending, and below it one. */
        const val TWO_LETTER_ENDING = 6
        const val ONE_LETTER_ENDING = 5

        /**
         * [folded] with each word cut to its opening, as a pattern, or null if nothing would be cut.
         *
         * **The same shape as [stems] and a different quantity.** That one shortens a word by what
         * an inflection can be, which is one or two letters, and is a tolerance on a match. This
         * one keeps only [NEAR_LETTERS] of each word however long it is, and is not a match at all:
         * what it finds is reported as a spelling the corpus holds, never resolved to.
         *
         * **Anchored and ordered exactly as [stems] is**, so a two-word name has to meet a label's
         * words in sequence — which is what keeps a reader's given name and surname from matching
         * two unrelated people's. The anchoring is also what stops the opening landing mid-word,
         * where an opening's worth of letters is a coincidence rather than a spelling, and it is
         * what carries over the half of the matching rule that cannot be given up: a short name
         * inside a stranger's longer one.
         *
         * **What is deliberately not carried over is [stems]' floor of five letters.** That floor
         * is there because a stem below it stops being evidence *that a name means a subject*, and
         * nothing here means a subject — so the reduction goes as deep as the opening whatever the
         * word's length, and a name of four letters is reduced where a stem would have left it
         * alone. The breadth that buys is real and grows with the name: a long name is cut to its
         * opening like any other, so most of what a reader typed stops being compared. Nothing
         * bounds the consequence except that these are shown rather than resolved to, and that
         * [NEAR_OFFERED] of them are shown.
         *
         * Null where no word is longer than the opening, since cutting would then change nothing
         * and the pattern would be the strict one [matching] has already tried. So a name that is
         * its own opening is never widened — which is a floor on this function and not a bound on
         * how much of the corpus a longer name can reach.
         */
        fun openings(folded: String): String? {
            val words = folded.split(" ").filter { it.isNotEmpty() }
            val cut = words.map { it.take(NEAR_LETTERS) }
            if (cut == words) return null
            return cut.joinToString("% ", prefix = "% ", postfix = "%")
        }

        /**
         * How much of a word's opening has to agree for the corpus to be holding a near spelling.
         *
         * Measured on the real archive rather than chosen, and the figure is in the pull request.
         * What the measurement found is that this is not a dial between precision and recall: the
         * names a reader writes for a subject the corpus keeps under another form — a given name's
         * English rendering beside its transliteration, a short form registered in place of a full
         * one — agree on their opening and part immediately after it, so one step wider reaches
         * none of them while still reaching unrelated names. Narrower is not available either: a
         * name is never reduced below its own opening, which is what [openings] refuses.
         *
         * **Counted in the folded form's characters, not in the name's letters, and the two differ.**
         * A letter that renders as several Latin ones spends the whole opening on itself, so a name
         * beginning with one is reduced to less of itself than a Latin-typed name is — widest for
         * exactly the names whose transliteration is least predictable, which is also where this
         * read is most needed. Stated rather than corrected: the fold is the comparison, so there is
         * no other unit available to count in.
         *
         * What it costs is that a name nobody carries can share an opening with one somebody does,
         * and such a reader is shown a spelling that is not theirs. That is the cheaper failure by
         * the margin this whole read exists for: the alternative is telling them the corpus holds
         * nothing about their subject, which is the one wrong reply they cannot check.
         */
        const val NEAR_LETTERS = 3

        /**
         * The most near spellings a reply offers.
         *
         * A reader is shown these to recognise one, which is a glance down a short list rather than
         * a search through a long one — and any of them is acted on the same way, by asking again
         * with it. So the cost of a bound is a spelling nobody was going to read, and the cost of
         * none is a reply whose length depends on how common the opening happened to be.
         */
        const val NEAR_OFFERED = 4

        /**
         * The most rows the near read reads, which is deliberately far more than it offers.
         *
         * [CEILING] bounds a read whose own ordering decides nothing — there, overflow risks missing
         * an ambiguity. Here the rows that survive are what the reply is composed from, and the
         * statement orders by label length rather than by agreement, so a bound near [NEAR_OFFERED]
         * would hand the reader the shortest labels sharing the opening instead of the nearest
         * spellings. Reading well past what is shown is what leaves the ordering, rather than the
         * truncation, to choose.
         *
         * Not unbounded, for [CEILING]'s own reason: a three-letter opening is the broadest pattern
         * this file issues, and the alternative to a bound is a request that reads every label to
         * compose a list of four.
         */
        const val NEAR_READ = 500

        /**
         * How many characters of [query] the words of [name] agree with, at a word's start.
         *
         * The ordering for [nearest], and the only measure of nearness available to it: every row
         * it reads sits in one [strength] band, so the score cannot tell two of them apart by how
         * much of the name they hold. Summed per word of the query against the best-agreeing word
         * of the label, because the pattern that found the row matched the words in order and
         * independently, so that is the agreement the row actually has.
         *
         * Both sides are folded forms, which is what makes the count comparable at all — the same
         * reason [strength] reads the folded pair rather than what was typed.
         */
        fun agreement(query: String, name: String): Int {
            val labelWords = name.split(" ").filter { it.isNotEmpty() }
            return query.split(" ").filter { it.isNotEmpty() }.sumOf { word ->
                labelWords.maxOfOrNull { label -> word.commonPrefixWith(label).length } ?: 0
            }
        }

        /**
         * A handle form that reaches no stored handle, for the read that compares no handle.
         *
         * [MATCHING]'s fourth branch is a `position` test, and an empty string is found in every
         * value — so the branch cannot be switched off by passing nothing. The marker is the closest
         * thing to a value no handle holds: the corpus stores a handle without one, which is
         * [asHandle]'s own note. A near read must not reach that branch at all, since a handle is a
         * key and a key reduced is a different key.
         *
         * **It is a narrowing and not the guarantee, which is why [nearest] drops those rows too.**
         * Normalisation strips a marker from the front of a handle rather than from inside it, so a
         * stored value holding one anywhere else would satisfy this test — and that branch carries
         * no label predicate, so one such row would be a near spelling of every name ever typed.
         * What makes the property hold is the filter on the rows; this only keeps the database from
         * doing the work.
         */
        const val NO_HANDLE = HANDLE_MARKER

        /**
         * How many clients of one name make a body of work worth reporting as its own subject.
         *
         * Two, because one client is a body of work already: it resolves by its own label, and a
         * second referent for it would offer a reader two readings of a name that answer the same
         * way. What several of them add is the thing nothing else can say — that these are strands
         * of one piece of work — so that is where the referent starts.
         */
        const val STRANDS = 2

        const val EXACT = 1.0
        const val PREFIX = 0.7
        const val CONTAINS = 0.5
        const val ALONE = 1

        /** What a contact reached by traversal scores. See the note at the call site. */
        const val WITHIN_SCORE = 0.5

        /**
         * The most rows a read that resolves a name returns — [matching]'s two, and not [nearest].
         *
         * A bound rather than everything, because a one-letter query matches most of the corpus and
         * the caller's rules are about a handful of readings of a name. What it costs is that a
         * subject can be missed by a query broad enough to overflow it, which is the cheaper failure:
         * the alternative is a request that reads the whole corpus to decide it was ambiguous.
         *
         * **The near read needs its own and has one**, because that trade is not the same trade: here
         * overflow risks missing an ambiguity, and there it would let truncation choose the contents
         * of a reply. See [NEAR_READ].
         *
         * **Since a body of work is composed from the rows this returns, overflow can also decide a
         * resolution.** The client whose leading name differs is what keeps two pieces of work from
         * being read as one, and a query broad enough to overflow can cut it — so a collision can
         * resolve where it would have asked. The other direction is the same cut: a strand past the
         * bound is absent from the work's own records, so an answer about the whole of a piece of
         * work is composed from the part that survived. Both are bounded by what overflows this at
         * all, which is a query matching fifty subjects; a name that picks out a body of work
         * matches its strands and little else.
         */
        const val CEILING = 50

        /**
         * The typed name in the form a label is compared in.
         *
         * **Asked of the store, because the fold is one definition and it lives there.** The
         * comparison has to reach the stored labels, so the rule is `name_fold`'s; asking the store
         * to apply it to the typed name as well is what keeps there from being a second copy of a
         * character-for-character table that would have to agree with the first to match anything.
         * It also keeps [strength] comparing like with like — the bands read the folded forms, which
         * is what the statement matched on. A handle is not folded and so is not asked for here.
         */
        const val FOLDED = "SELECT name_fold(?) AS label"

        /**
         * The union: a label folded in each of the three label branches, a handle compared as it is.
         *
         * **Each label branch is anchored by `' ' ||`, which is half the matching rule and not
         * formatting.** The pattern [wordStart] builds opens with a space, so prefixing the label's
         * own start is what makes its first word reachable. A branch that dropped the concatenation
         * would match its kind inside a word while the other two did not, and nothing else here
         * would say so.
         *
         * **The fold is named once per label branch**, in a subselect the predicate then filters,
         * rather than written into the projection and the `WHERE` separately. [CLIENTS_OF] names its
         * own reduction once for the same reason: two copies of one expression are two things that
         * have to agree, and a read whose filter and projection disagreed would score every row
         * against a string it was never matched by.
         *
         * `client` and `contact` carry their record as a source pair, and `conversation` is keyed by
         * one, so every branch projects one shape — the record, the label, the compared form — and
         * the kind that tells them apart.
         * `client_of_record` is the client read from that same conversation, which is what lets a
         * conversation and its client compose instead of competing.
         *
         * **`body` is the body of work a client's label names, and only the client branch has one.**
         * It is [bodyOfWork] applied here rather than reduced in Kotlin, because that rule is one
         * definition and it lives in SQL: a second copy of the delimiter class would be two things
         * that have to agree about what one body of work is. Lowercased in the statement for the
         * reason the two reads that count bodies of work lower theirs — a label's case is the
         * source's business and never a distinction between two pieces of work. The other three
         * branches are null and not empty: a contact and a conversation are not strands of anything,
         * and a label that reduces to nothing is no body of work either, which is what [bodyOfWork]
         * returns null for.
         *
         * **The fourth branch matches a person by their handle, and is what makes an ambiguous name
         * answerable.** A label is not a key — two people can carry one — so a menu offering both is
         * answerable only by something that names one of them, and a handle is the only such name a
         * person has. It is a contact row like the first branch's: what differs is the string the
         * query agreed with, so `label` stays the display name while `name` is the handle, trimmed
         * and lowercased and otherwise as the source wrote it. That is also what scores it correctly
         * — a handle is carried by one subject, so the rarity factor leaves it at full strength,
         * which is what an exact identifier deserves against a substring of somebody's name.
         *
         * **It is the one branch the fold does not reach, and the property above is why.** Folding a
         * handle would turn its separator into a word break and make two handles that differ only by
         * a reduced letter one string — so an exactly-typed key would share a rarity bucket with
         * somebody's label and lose the full strength this paragraph rests on. It is compared by
         * position rather than by a pattern, which both keeps a handle written without its marker
         * reachable and leaves a typed `_` a literal rather than the single-character wildcard a
         * `LIKE` would have read it as.
         *
         * **`matched_as` says which string each row agreed with, and scoring needs it.** A contact
         * row arrives from either the first branch or the fourth, and the two were compared against
         * different forms of the query — the fourth against the marker-less one, and now also
         * against an unfolded one. Inferring it from the kind is not possible, and comparing against
         * the wrong form costs an exactly-typed handle its exact-match band.
         */
        val MATCHING = """
            WITH matched AS (
                SELECT 'CONTACT' AS kind, entity, source, native_id, label, name,
                       'LABEL' AS matched_as,
                       NULL::uuid AS client_of_record,
                       NULL::text AS body
                FROM (
                    SELECT c.id AS entity,
                           c.record_source AS source, c.record_native_id AS native_id,
                           c.display_name AS label,
                           name_fold(c.display_name) AS name
                    FROM contact c
                    WHERE c.display_name IS NOT NULL
                ) contacts
                WHERE ' ' || name LIKE ?
                UNION ALL
                SELECT 'CLIENT', entity, source, native_id, label, name, 'LABEL', NULL::uuid, body
                FROM (
                    SELECT cl.id AS entity,
                           cl.record_source AS source, cl.record_native_id AS native_id,
                           cl.name AS label,
                           name_fold(cl.name) AS name,
                           lower(${bodyOfWork("cl.name")}) AS body
                    FROM admitted_client cl
                ) clients
                WHERE ' ' || name LIKE ?
                UNION ALL
                SELECT 'CONVERSATION', NULL::uuid, source, native_id, label, name, 'LABEL',
                       client_of_record, NULL::text
                FROM (
                    SELECT cv.source AS source, cv.native_id AS native_id,
                           cv.name AS label,
                           name_fold(cv.name) AS name,
                           cl.id AS client_of_record
                    FROM conversation cv
                    LEFT JOIN admitted_client cl
                           ON cl.record_source = cv.source
                          AND cl.record_native_id = cv.native_id
                    WHERE cv.name IS NOT NULL
                ) conversations
                WHERE ' ' || name LIKE ?
                UNION ALL
                SELECT 'CONTACT', c.id,
                       c.record_source, c.record_native_id,
                       c.display_name,
                       lower(regexp_replace(btrim(i.value), '\s+', ' ', 'g')),
                       '${MATCHED_AS_HANDLE}',
                       NULL::uuid,
                       NULL::text
                FROM contact c
                JOIN person p ON p.source = c.record_source
                             AND p.native_id = c.record_native_id
                JOIN identifier i ON i.record_kind = '${RecordTable.PERSON.table}'
                                 AND i.record_id = p.id
                                 AND i.type = '${IdentifierType.HANDLE.name}'
                WHERE position(? IN lower(regexp_replace(btrim(i.value), '\s+', ' ', 'g'))) > 0
            )
            SELECT kind, entity, source, native_id, label, name, matched_as, client_of_record, body
            FROM matched
            ORDER BY length(name), name
            LIMIT ?
        """.trimIndent()

        /**
         * The handle each of these people goes by.
         *
         * **What makes a handle usable as an answer is that it is matched like any other name**, so
         * this read exists only to put it on a candidate — the branch of [MATCHING] above is what
         * resolves one. A person with several is named by the lexically first, because a reader
         * offered a different one of them on each of two reads cannot tell the two subjects apart
         * any better than the label did — any settled choice will do, and this one needs no column
         * the table does not have.
         *
         * Keyed by the person record rather than by the contact, because that is where the
         * identifiers hang and a contact names its record directly.
         */
        internal val HANDLES_OF = """
            SELECT p.source, p.native_id, min(i.value) AS handle
            FROM person p
            JOIN identifier i ON i.record_kind = '${RecordTable.PERSON.table}'
                             AND i.record_id = p.id
                             AND i.type = '${IdentifierType.HANDLE.name}'
            JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
              ON p.source = wanted.source AND p.native_id = wanted.native_id
            GROUP BY p.source, p.native_id
        """.trimIndent()

        /**
         * The single body of work whose conversations hold each of these person records.
         *
         * `HAVING count(DISTINCT ...) = 1` is the whole point: a contact writing in two bodies of
         * work has no one parent, and this returns nothing for it rather than picking the first.
         *
         * **Counted in bodies of work and not in clients, and so every client of the one body of
         * work is returned** — see [bodyOfWork]. A body of work keeping a thread per strand of it
         * is several clients, and one strand of it is not what a contact sits under: handing back
         * whichever client sorted first would state a hierarchy the corpus does not, which is the
         * thing the `HAVING` is here to refuse. So the row carries them all and the caller holds a
         * parent per client, which is the shape that can say *this body of work* without naming a
         * strand of it.
         *
         * **Which is why this read names every strand where the grouping read names one.** The
         * sibling read's answer is a *label*, read by the model, which is told to join two labels
         * built on one name; naming one strand there loses nothing a reader can see. A parent is not
         * a label: it reaches retrieval as the scope a request is answered against, so naming one
         * strand would narrow the material to that strand and the answer would be about part of the
         * work. The two reads differ because what they hand back is used for different things.
         *
         * **The aggregate is filtered by the same expression the `HAVING` counts, because `count`
         * passes over a null and `array_agg` does not.** A label that reduces to nothing is no body
         * of work, so it does not decide the count — and aggregated unfiltered it would hand the
         * caller a parent belonging to no body of work at all, admitted by a group it took no part
         * in deciding.
         *
         * **Ordered, which states a guarantee rather than repairing an instability.** An aggregate
         * with `DISTINCT` already sorts its input, so the order was ascending either way; the
         * parents reach a prompt as the subjects a request is answered against, and one question
         * resolving two ways between runs is a difference the evaluation set would score without
         * anything having changed. Written down, it survives the `DISTINCT` being dropped.
         *
         * **The subquery names the reduction once and hands the aggregates distinct pairs.** Two
         * things come of that. The expression is written once rather than in both the filter and the
         * predicate, so the two cannot drift apart. And what the aggregates see is one row per
         * contact-and-client rather than one per message: this asks which clients somebody wrote in
         * and never how often, so feeding it the whole scan made every aggregate sort it —
         * measurably, about a third of the statement's time on the real corpus.
         */
        internal val CLIENTS_OF = """
            SELECT author_source,
                   author_native_id,
                   array_agg(client ORDER BY client) FILTER (WHERE body IS NOT NULL) AS clients
            FROM (
                SELECT DISTINCT
                       m.author_source,
                       m.author_native_id,
                       cl.id::text          AS client,
                       ${bodyOfWork("cl.name")} AS body
                FROM message m
                JOIN conversation cv ON cv.id = m.conversation_id
                JOIN admitted_client cl
                  ON cl.record_source = cv.source AND cl.record_native_id = cv.native_id
                JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
                  ON m.author_source = wanted.source AND m.author_native_id = wanted.native_id
            ) authored
            GROUP BY author_source, author_native_id
            HAVING count(DISTINCT lower(body)) = 1
        """.trimIndent()

        /**
         * The contacts one hop inside a client or a conversation, topics included.
         *
         * One statement for both, selected by which parameter is non-null: a client is reached
         * through the conversation it was read from, so both cases are the same join once that
         * conversation is known. Two statements would be two copies of it.
         *
         * **A container's topics are part of it, and that is where its messages actually are.** A
         * Telegram supergroup holds almost nothing directly — its traffic hangs off the topics inside
         * it, each a conversation of its own naming the supergroup as parent — so matching the named
         * conversation alone returns the members of an empty shell. The second branch of the union is
         * that one hop down, and one hop is the whole depth: a topic cannot contain a topic.
         *
         * The parent is matched on the source's own identifier pair rather than through a foreign key,
         * because that is how `conversation` stores it: a topic can be captured before the supergroup
         * that contains it, so the link is a pair of columns that may name a row not yet present.
         */
        val WITHIN = """
            WITH wanted AS (
                SELECT cv.id
                FROM conversation cv
                LEFT JOIN admitted_client cl ON cl.record_source = cv.source
                                            AND cl.record_native_id = cv.native_id
                WHERE (?::uuid IS NOT NULL AND cl.id = ?::uuid)
                   OR (cv.source = ? AND cv.native_id = ?)
                UNION
                SELECT child.id
                FROM conversation child
                JOIN conversation parent ON parent.source = child.parent_source
                                        AND parent.native_id = child.parent_native_id
                LEFT JOIN admitted_client cl ON cl.record_source = parent.source
                                            AND cl.record_native_id = parent.native_id
                WHERE (?::uuid IS NOT NULL AND cl.id = ?::uuid)
                   OR (parent.source = ? AND parent.native_id = ?)
            )
            SELECT DISTINCT c.id, c.display_name, c.record_source, c.record_native_id
            FROM contact c
            JOIN message m ON m.author_source = c.record_source
                          AND m.author_native_id = c.record_native_id
            WHERE m.conversation_id IN (SELECT id FROM wanted)
            ORDER BY c.display_name NULLS LAST
            LIMIT ?
        """.trimIndent()
    }

    private fun ResultSet.toInhabitant(): Inhabitant = Inhabitant(
        contact = EntityId(getObject("id", UUID::class.java)),
        label = getString("display_name"),
        record = ExternalId(Source.valueOf(getString("record_source")), getString("record_native_id")),
    )

    /**
     * The rows [labelPattern] and [handleQuery] reach, unscored, at most [limit] of them.
     *
     * A function rather than a block at its callers because the statement is issued three times
     * with three different patterns: [matching] reads strictly and then, where that found nothing,
     * with the words of the name shortened; [nearest] reads with the words cut to their openings.
     * The first two pass the handle form unchanged, since a handle does not inflect — it is a key
     * rather than a word of a sentence — and the third passes one that reaches no handle at all.
     *
     * [limit] is the caller's because what truncation costs differs between them: see [CEILING] and
     * [NEAR_READ].
     */
    private fun Connection.matches(labelPattern: String, handleQuery: String, limit: Int): List<Match> =
        prepareStatement(MATCHING).use { statement ->
            statement.bind {
                string(labelPattern)
                string(labelPattern)
                string(labelPattern)
                string(handleQuery)
                int(limit)
            }
            statement.executeQuery().use { rows -> rows.collect { it.toMatch() } }
        }

    private fun ResultSet.toMatch(): Match = Match(
        kind = Match.Kind.valueOf(getString("kind")),
        entity = getObject("entity", UUID::class.java),
        record = ExternalId(Source.valueOf(getString("source")), getString("native_id")),
        label = getString("label"),
        name = getString("name"),
        byHandle = getString("matched_as") == MATCHED_AS_HANDLE,
        clientOfRecord = getObject("client_of_record", UUID::class.java),
        body = getString("body"),
    )
}

/**
 * One candidate per subject, keeping the best-scoring reading of each.
 *
 * Resolution collapses several rows onto one subject — two merged accounts, or one person writing in
 * a supergroup and in its topics — and two candidates naming the same thing are not two readings a
 * caller can choose between: whichever it picked would resolve identically. The best score survives
 * because the rows differ in how well their own name agreed with the query, and the label shown should
 * be the one that agreed.
 *
 * First-seen order is preserved, so whatever the statement ordered by still holds across the result.
 */
private fun List<Candidate>.bestPerSubject(): List<Candidate> =
    groupBy { it.referent }.values.map { readings -> readings.maxBy { it.score } }

/**
 * The clients of the single body of work holding each of [records], where there is exactly one.
 *
 * Kept beside its one caller rather than in `Matching.kt`: that file holds the reads recognition
 * makes, and this is the answer path asking a different question of the same tables. A record with
 * no single body of work is absent from the result, which is what lets the caller leave a contact's
 * parent unset rather than guessing at it.
 *
 * Several clients per record where one body of work keeps a thread per strand of it. Absent and
 * empty therefore mean the same thing to a caller, and the statement produces no empty array: a
 * group that passes the `HAVING` has at least one client in it by construction.
 */
private fun Connection.clientsOf(records: List<ExternalId>): Map<ExternalId, List<UUID>> {
    if (records.isEmpty()) return emptyMap()
    val wanted = records.distinct()
    return prepareStatement(JdbcSubjectSearch.CLIENTS_OF).use { statement ->
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
        }
        statement.executeQuery().use { rows ->
            rows.collect {
                ExternalId(
                    Source.valueOf(it.getString("author_source")),
                    it.getString("author_native_id"),
                ) to it.uuids("clients")
            }.toMap()
        }
    }
}

/**
 * A `text[]` column of entity identifiers, read back as the identifiers it holds.
 *
 * Beside its one caller for the reason [clientsOf] is beside its own, and in this file rather than
 * among the shared SQL helpers because that file is at the function count detekt allows one — a
 * reader looking for it finds it next to the only statement that projects an array.
 */
private fun ResultSet.uuids(column: String): List<UUID> {
    val stored = getArray(column)?.array as? Array<*> ?: return emptyList()
    return stored.filterIsInstance<String>().map(UUID::fromString)
}

/**
 * A typed name in the form a stored label is compared in.
 *
 * Beside its one caller for the reason [clientsOf] is beside its own. The statement has no `FROM`,
 * so it returns exactly one row and [single] cannot fail.
 */
private fun Connection.folded(name: String): String =
    prepareStatement(JdbcSubjectSearch.FOLDED).use { statement ->
        statement.bind { string(name) }
        statement.executeQuery().use { rows -> rows.collect { it.getString("label") }.single() }
    }

/**
 * The handle each of [records] goes by, where one is known.
 *
 * Beside its callers for the reason [clientsOf] is: this is the answer path asking something of the
 * identity tables that recognition does not ask. A record with no handle is absent rather than
 * mapped to nothing, so a caller reads the absence as *this person has none to offer*.
 */
private fun Connection.handlesOf(records: List<ExternalId>): Map<ExternalId, String> {
    if (records.isEmpty()) return emptyMap()
    val wanted = records.distinct()
    return prepareStatement(JdbcSubjectSearch.HANDLES_OF).use { statement ->
        statement.bind {
            strings(wanted.map { it.source.name })
            strings(wanted.map { it.nativeId })
        }
        statement.executeQuery().use { rows ->
            rows.collect {
                ExternalId(
                    Source.valueOf(it.getString("source")),
                    it.getString("native_id"),
                ) to it.getString("handle")
            }.toMap()
        }
    }
}
