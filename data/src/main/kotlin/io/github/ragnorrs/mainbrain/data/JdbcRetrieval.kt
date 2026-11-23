package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Channel
import io.github.ragnorrs.mainbrain.domain.answer.Closed
import io.github.ragnorrs.mainbrain.domain.answer.Horizon
import io.github.ragnorrs.mainbrain.domain.answer.Reach
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.sql.ResultSet
import java.util.UUID

/**
 * The answer path's reads, as SQL.
 *
 * **Every read that returns a list is bounded and ordered in the statement rather than
 * afterwards.** A limit applied in Kotlin would read the rows first, which for a contact with years
 * of history is the whole history fetched to discard most of it; and an ordering applied afterwards
 * cannot use the index the ordering exists to exploit. Both are what make the per-shape limits
 * meaningful rather than decorative. The keyed lookups — the names, the closures, the turns — carry
 * neither, because the caller is naming rows it already holds and there is no ordering for a limit
 * to make meaningful.
 *
 * **The per-container limits are window functions, not a query per container.** The tail of each of
 * five conversations is one statement with `row_number()` partitioned by conversation — five
 * statements would be five round trips, and a single `LIMIT` over the union would let the busiest
 * conversation consume the whole allowance.
 */
@Suppress("TooManyFunctions")
class JdbcRetrieval(private val database: Database) : Retrieval {

    /**
     * Named conversations, then a client's own, then what the subject's contacts wrote in.
     *
     * The first two come out of the referents and need no query; only the third does, because a
     * contact is reached through the person record behind it and the threads that record appears in
     * are a corpus read. Ordered as the port promises, then deduplicated with the first occurrence
     * winning, so a thread that was named outranks the same thread found for a contact.
     */
    override suspend fun conversationsOf(subject: Subject, threads: Int): List<ExternalId> {
        val referents = (listOf(subject.focus) + subject.context).map { it.referent }
        val named = referents.filterIsInstance<Referent.OfConversation>().map { it.conversation }
        val clients = referents.filterIsInstance<Referent.OfClient>().map { it.client }
        val contacts = referents.filterIsInstance<Referent.OfContact>().map { it.contact }

        if (clients.isEmpty() && contacts.isEmpty()) return named.distinct()

        return database.query { connection ->
            val ofClients = if (clients.isEmpty()) {
                emptyList()
            } else {
                connection.prepareStatement(CLIENT_CONVERSATIONS).use { statement ->
                    statement.bind { strings(clients.map { it.value.toString() }) }
                    statement.executeQuery().use { rows -> rows.collect { it.toExternalId() } }
                }
            }
            val authored = if (contacts.isEmpty()) {
                emptyList()
            } else {
                // Resolved to whole clusters first: a contact identifier names an account, and the
                // threads a human writes in are the threads any of their accounts writes in. The head
                // travels with each row so the per-subject bound stays per human rather than becoming
                // one allowance per account.
                val cluster = connection.clustersOf(contacts).toList()
                connection.prepareStatement(CONTACT_CONVERSATIONS).use { statement ->
                    statement.bind {
                        strings(cluster.map { (member, _) -> member.value.toString() })
                        strings(cluster.map { (_, head) -> head.value.toString() })
                        int(threads)
                    }
                    statement.executeQuery().use { rows -> rows.collect { it.toExternalId() } }
                }
            }
            (named + ofClients + authored).distinct()
        }
    }

    override suspend fun recentMessages(
        conversations: List<ExternalId>,
        perConversation: Int,
    ): List<Message> {
        if (conversations.isEmpty()) return emptyList()
        val wanted = conversations.distinct()
        return database.query { connection ->
            connection.prepareStatement(RECENT_MESSAGES).use { statement ->
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                    int(perConversation)
                }
                statement.executeQuery().use { rows -> rows.collect { it.toMessage() } }
            }
        }
    }

    override suspend fun commitmentsOf(reach: Reach, limit: Int): List<Commitment> =
        read(reach, COMMITMENTS, limit) { it.toCommitments() }

    override suspend fun eventsAhead(reach: Reach, horizon: Horizon, limit: Int): List<Event> =
        events(reach, horizon, limit, ahead = true)

    override suspend fun eventsPast(reach: Reach, horizon: Horizon, limit: Int): List<Event> =
        events(reach, horizon, limit, ahead = false)

    override suspend fun eventsUndated(reach: Reach, limit: Int): List<Event> =
        read(reach, EVENTS_UNDATED, limit) { it.toEvents() }

    override suspend fun statementsOf(reach: Reach, limit: Int): List<Statement> =
        read(reach, STATEMENTS, limit) { it.toStatements() }

    /**
     * The closing message of every one of [commitments] the corpus records as discharged.
     *
     * Only the closed ones, which is the port's own contract: a verdict recording that nothing
     * closed a promise carries no span, and handing one back would be a second way of saying what
     * an absent row already says.
     */
    override suspend fun closuresOf(commitments: List<EntityId>): List<Closed> {
        if (commitments.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(CLOSURES).use { statement ->
                statement.bind { strings(commitments.distinct().map { it.value.toString() }) }
                statement.executeQuery().use { rows ->
                    rows.collect {
                        Closed(
                            commitment = EntityId(it.getObject("id", UUID::class.java)),
                            by = it.toMessage(),
                        )
                    }
                }
            }
        }
    }

    /**
     * The display name of each of [parties] that can be named, its own first and its human's after.
     *
     * **Resolved through the cluster like every other read on this port.** A contact is one account,
     * and the account that stated a thing often carries no profile name while another account of the
     * same human does — so naming only the account loses a name the store holds. Its own name comes
     * first because it is the most specific, and the head's stands in when the account has none.
     *
     * The merge is trusted here because it is already trusted upstream: the finding is in this answer
     * at all because the narrowing resolved the subject to a whole cluster. A wrong merge therefore
     * misattributes the claim before it ever reaches the name, and withholding the name would not
     * undo that — it would only drop true names in the ordinary case to avoid a wrong one in a case
     * that is already wrong for a bigger reason.
     *
     * A contact whose name is null is left out rather than mapped to an empty string: the caller
     * distinguishes an unnamed party from a named one, and a blank would be a name as far as a map
     * lookup is concerned.
     */
    override suspend fun namesOf(parties: List<EntityId>): Map<EntityId, String> {
        if (parties.isEmpty()) return emptyMap()
        val wanted = parties.distinct()
        return database.query { connection ->
            // Member to head for every account of every human asked about, so a nameless account can
            // borrow the name of the one the merge made canonical.
            val heads = connection.clustersOf(wanted)
            val named = connection.prepareStatement(CONTACT_NAMES).use { statement ->
                statement.bind { strings((wanted + heads.keys + heads.values).distinct().map { it.value.toString() }) }
                statement.executeQuery().use { rows ->
                    rows.collect {
                        EntityId(it.getObject("id", UUID::class.java)) to it.getString("display_name")
                    }
                }
            }.toMap()
            wanted.mapNotNull { party ->
                (named[party] ?: heads[party]?.let { named[it] })?.let { party to it }
            }.toMap()
        }
    }

    /**
     * Where each of [messages] was said, what the corpus calls whoever wrote it, whether that was
     * the account the corpus was read through, and whether it was neither that account nor the
     * subject asked about.
     *
     * **Separate reads rather than one join, and one of them is [namesOf] itself.** What turns a
     * contact into a name is its own profile field, its human's where it has none, and a merge walk
     * to find that human — which is already written once on this port. Resolving each message to its
     * author's contact and then asking that read costs a statement of its own, and that read makes
     * more of them; writing the whole of it as SQL here would be a second copy of the fallback, and
     * two namings agree only until one of them is edited. [authorship] is the other, and it answers
     * the last two facts together for the reason its own lines give. The statements are deliberately
     * sequential rather than nested, so a read that is already in bulk never holds two connections
     * at once.
     *
     * A message whose author the derived layer has not reconciled has a conversation and no name,
     * as does one that records no author at all. That is self-healing rather than a hole: identity
     * runs over every person record under no horizon, so the gap is the window between a record
     * arriving and the next sweep reaching it, and an unnamed turn is a case the answer path already
     * has an answer for.
     */
    override suspend fun turnsOf(messages: List<ExternalId>, subject: List<EntityId>): Map<ExternalId, Turn> {
        if (messages.isEmpty()) return emptyMap()
        val wanted = messages.distinct()
        // The conversation, who reads it, and the author's contact in one row, the contact absent
        // where the message names nobody the derived layer has reached.
        val found = database.query { connection ->
            connection.prepareStatement(MESSAGE_TURNS).use { statement ->
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeQuery().use { rows ->
                    rows.collect {
                        Turned(
                            message = it.toExternalId(),
                            conversation = ExternalId(
                                Source.valueOf(it.getString("conversation_source")),
                                it.getString("conversation_native_id"),
                            ),
                            channel = channelOf(
                                it.getString("conversation_kind"),
                                it.getString("conversation_name"),
                            ),
                            contact = it.getObject("contact", UUID::class.java)?.let(::EntityId),
                        )
                    }
                }
            }
        }
        val contacts = found.mapNotNull { it.contact }.distinct()
        val named = namesOf(contacts)
        val authorship = authorship(contacts, subject)
        return found.associate { row ->
            row.message to Turn(
                conversation = row.conversation,
                channel = row.channel,
                author = row.contact?.let { named[it] },
                authorIsSelf = row.contact in authorship.ours,
                // Three answers from one set membership, and the absent case is two of them: a
                // caller that asked nothing and a row that reached no contact both leave nothing to
                // say. Folded here rather than in the shape, so the unknowns cannot drift into two
                // readings — see [Turn.authorIsAnotherParty].
                authorIsAnotherParty = when {
                    subject.isEmpty() -> null
                    row.contact == null -> null
                    row.contact in authorship.unestablished -> null
                    else -> row.contact in authorship.elsewhere
                },
            )
        }
    }

    /** One row of [MESSAGE_TURNS], before the two reads that name its author have run. */
    private class Turned(
        val message: ExternalId,
        val conversation: ExternalId,
        val channel: Channel,
        val contact: EntityId?,
    )

    /**
     * Who reads a thread the source recorded as [kind] and called [name].
     *
     * **Read as *not direct* rather than as each room kind in turn, which is the direction that
     * fails safe.** A kind this build has not seen — a source gaining a container the enum does
     * not name, or one spelled differently — resolves to a room, so the worst a stranger costs is
     * a draft composed for an audience of one person as though others were present.
     * Matched the other way round it would cost the defect this read exists to prevent: a room
     * answered privately. It is also what the grouping read already does with the same column.
     *
     * A blank name is the absence of one wearing a name's clothes, so it is carried as no name
     * rather than as a room called nothing.
     */
    private fun channelOf(kind: String, name: String?): Channel =
        if (kind == ConversationKind.DIRECT.name) {
            Channel.OneToOne
        } else {
            Channel.Room(name?.takeIf { it.isNotBlank() })
        }

    /**
     * Whose each of [contacts] is: the human the corpus was read through, the [subject] asked
     * about, or neither.
     *
     * **Asked of the cluster, not of the row, which is the whole of why this is a read of its own
     * rather than columns on the one above.** A human is read through where *any* of their accounts
     * is, so an owner whose second account is merged into their first has to answer for both — and
     * the adapter marks a known account's siblings `false` rather than leaving them unset, so
     * reading the author's own row would answer `false` for an account the merge has already made
     * the owner's. A draft composed from that answer is in the counterpart's voice, which is one of
     * the two defects this read exists to prevent. The subject side resolves the same way and for
     * the same reason: the turns in a person's threads are written under whichever account they
     * happened to use, so comparing identifiers would call a human's second account somebody else
     * on exactly the corpus where several accounts per human is why the merge exists.
     *
     * Never a comparison of names — two people sharing a display name is ordinary here, and the
     * name on the same turn resolves through this same merge rather than standing for the account.
     *
     * **One upward walk for both questions, which is what makes the subject comparison free.**
     * [headsOf] names the human and the two membership reads answer for them; resolving through
     * [clustersOf] instead would run the downward walk again to widen a set nothing here reads.
     * Because the walk is shared, asking whose a turn is costs no round trip that naming its author
     * did not already cost. [headsOf] is total over its input, so a contact nothing has merged maps
     * to itself and compares as itself.
     */
    private suspend fun authorship(contacts: List<EntityId>, subject: List<EntityId>): Authorship {
        if (contacts.isEmpty()) return Authorship()
        return database.query { connection ->
            val heads = connection.headsOf(contacts + subject)
            val clusters = heads.values.distinct()
            val ourHeads = connection.selfClusters(clusters)
            // Whose account it is may be unanswered rather than denied, and only a caller stating
            // the denial needs the two apart — see [selfKnownClusters].
            val settled = connection.selfKnownClusters(clusters)
            val subjectHeads = subject.mapNotNullTo(mutableSetOf()) { heads[it] }
            Authorship(
                ours = contacts.filterTo(mutableSetOf()) { heads[it] in ourHeads },
                elsewhere = contacts.filterTo(mutableSetOf()) {
                    heads[it] !in ourHeads && heads[it] !in subjectHeads
                },
                // A contact that is neither the subject's nor established as the reader's, which is
                // the one case the denial must not be stated for. The subject's own accounts are
                // not in it however their ownership reads: a question about somebody answers that
                // question, and nothing is being denied of them.
                unestablished = contacts.filterTo(mutableSetOf()) {
                    heads[it] !in settled && heads[it] !in subjectHeads
                },
            )
        }
    }

    /**
     * Whose the turns' authors are, as three sets over one walk.
     *
     * Sets rather than a map per contact because every reader asks a membership question, and the
     * empty value is the honest answer for a caller that named no messages.
     */
    private class Authorship(
        val ours: Set<EntityId> = emptySet(),
        val elsewhere: Set<EntityId> = emptySet(),
        val unestablished: Set<EntityId> = emptySet(),
    )

    /**
     * One event read, ordered from whichever end the caller is asking about.
     *
     * The two differ only in `ORDER BY`, so they share a statement body: what matters about the future
     * is what comes next and about the past what happened last, and that is a direction rather than a
     * different question.
     */
    private suspend fun events(reach: Reach, horizon: Horizon, limit: Int, ahead: Boolean): List<Event> {
        val direction = if (ahead) EVENTS_SOONEST else EVENTS_LATEST
        return read(reach, eventStatements(direction), limit, horizon) { it.toEvents() }
    }

    /**
     * Run whichever statement [reach] names, bind its narrowing, and map the rows.
     *
     * **A narrow case with nothing in it returns without a query.** That is the whole point of
     * [Reach]: only [Reach.Everything] reads everything, and it has to say so. Which statement each
     * case uses is [Narrowed]'s subject.
     */
    private suspend fun <T> read(
        reach: Reach,
        statements: Narrowed,
        limit: Int,
        horizon: Horizon? = null,
        // The whole result set rather than one row, because a finding arrives as one row per span
        // and the rows have to be grouped onto it. See `toCommitments`.
        rows: (ResultSet) -> List<T>,
    ): List<T> {
        val statement = when (reach) {
            Reach.Everything -> statements.everywhere
            is Reach.ByContacts -> statements.byContact.takeIf { reach.contacts.isNotEmpty() }
            is Reach.InConversations -> statements.inConversation?.takeIf { reach.conversations.isNotEmpty() }
        } ?: return emptyList()
        return database.query { connection ->
            // Resolved before binding rather than inside the statement. A derived row is attributed to
            // the account that stated it, so narrowing on the identifier the subject resolved to omits
            // whatever the same human said under another of their accounts. It cannot be a leading CTE
            // here for the reason [IN_CONVERSATION] gives: that would put its placeholder ahead
            // of the horizon's, and the binder is positional.
            val cluster = (reach as? Reach.ByContacts)?.let { connection.clustersOf(it.contacts).keys }
            connection.prepareStatement(statement).use { prepared ->
                prepared.bind {
                    if (horizon != null) {
                        instant(horizon.from)
                        instant(horizon.until)
                    }
                    when (reach) {
                        Reach.Everything -> Unit
                        is Reach.ByContacts -> strings(cluster.orEmpty().map { it.value.toString() })
                        is Reach.InConversations -> {
                            val wanted = reach.conversations.distinct()
                            strings(wanted.map { it.source.name })
                            strings(wanted.map { it.nativeId })
                        }
                    }
                    int(limit)
                }
                prepared.executeQuery().use { found -> rows(found) }
            }
        }
    }

    /**
     * One statement per narrowing, which is what [Reach] chooses between.
     *
     * A statement per narrowing rather than one with a disjunction, because the narrow cases reach the
     * row differently — a contact is named on it, a conversation is a subquery away — and a single
     * statement carrying both would pay for the one it is not using on every call. Not every kind of
     * row is reachable every way, so a narrowing may have no statement at all.
     */
    private class Narrowed(
        val everywhere: String,
        val byContact: String,
        /**
         * `null` where the kind of row cannot be reached through a conversation at all.
         *
         * A statement is the case: its subject is a contact, so a thread reaches no statement a
         * contact does not, and every statement stated in a thread is about whoever spoke rather
         * than about the client the thread stands for. Absent rather than a statement returning
         * nothing, so that the one read with no conversation form says so here instead of carrying
         * SQL nothing runs.
         */
        val inConversation: String? = null,
    )

    private companion object {

        /**
         * The conversation each of these clients was read from.
         *
         * A client *is* a reading of a conversation's label, so its record is the conversation —
         * which is why this is a lookup rather than a join through anything.
         */
        val CLIENT_CONVERSATIONS = """
            SELECT record_source AS source, record_native_id AS native_id
            FROM admitted_client
            WHERE id = ANY (?::uuid[])
        """.trimIndent()

        /**
         * The threads each of these humans is closest to, at most the limit per human.
         *
         * `row_number()` partitioned by head is what makes the bound per human. The window runs over
         * the grouped rows — one per human per conversation, carrying that pair's latest message — so
         * the ordering inside each partition is by how recently *that* human was active in *that*
         * thread, which is the question. A plain `LIMIT` over the union would let one subject's
         * history fill the allowance and return nothing for the other.
         *
         * **Partitioned by the head and joined on the member, which are two different things.** The
         * caller passes a resolved cluster as parallel arrays: the corpus names the account that wrote
         * each message, so the join has to match a row, while the allowance belongs to the human, so
         * the window has to group by the head. Partitioning by the row instead would give a human
         * merged from three accounts three times the threads of one who was never merged.
         *
         * **A person's own direct chat comes first, ahead of recency.** It is the thread that is with
         * them rather than one they happen to write in, so a question about a person is about it above
         * all — and under a bound of a few threads, somebody active in several rooms would otherwise
         * have the one conversation that is theirs pushed out by them. Expressed as an aggregate
         * because the window runs over grouped rows: the contact's record is not grouped, and
         * `bool_or` asks the question the group can answer, which is whether any account of this human
         * is the person this thread is with.
         */
        /**
         * The columns a statement and its evidence are rebuilt from.
         *
         * The same list `JdbcStatementRepository` selects, because both feed one row mapper — two
         * column lists for one mapper is a column renamed in one place and read as null in the other.
         */
        val STATEMENT_COLUMNS =
            """
            s.id, s.subject, s.predicate, s.value, s.valid_from, s.valid_until, s.origin,
                   e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
            """.trimIndent()

        /**
         * What holds about a subject, newest window first, bounded before the evidence is joined.
         *
         * **The limit is applied in the subquery so it bounds statements and not evidence rows.** A
         * statement carries one row per piece of evidence, so a limit applied after the join would
         * return a handful of well-corroborated facts and silently drop the rest — the same trap the
         * finding reads already avoid.
         *
         * Ordered by the window's start rather than by when the system believed it: `valid_from` is
         * the source time of the evidence, so this is most-recently-stated first, and ordering by
         * `believed_at` would put a backfill's reading of 2021 ahead of a fact stated yesterday.
         *
         * No window filter. The period is carried into the material instead, because a value read out
         * of the wrong stretch of time cites correctly and passes the grounding check — see the port.
         */
        val STATEMENTS = Narrowed(
            everywhere = """
                SELECT $STATEMENT_COLUMNS
                FROM (
                    SELECT * FROM statement
                    ORDER BY valid_from DESC, id DESC
                    LIMIT ?
                ) s
                JOIN statement_evidence e ON e.statement = s.id
                ORDER BY s.valid_from DESC, s.id DESC, e.ordinal
            """.trimIndent(),
            byContact = """
                SELECT $STATEMENT_COLUMNS
                FROM (
                    SELECT * FROM statement
                    WHERE subject = ANY (?::uuid[])
                    ORDER BY valid_from DESC, id DESC
                    LIMIT ?
                ) s
                JOIN statement_evidence e ON e.statement = s.id
                ORDER BY s.valid_from DESC, s.id DESC, e.ordinal
            """.trimIndent(),
        )

        /**
         * The name each named contact carries, for the parties a page of findings reaches.
         *
         * Unnamed contacts are filtered here rather than in Kotlin so that an absent name and an
         * absent contact arrive the same way — as no row — because the caller treats them alike and a
         * null column mapped into the map would need the same filter one step later.
         *
         * **A name of nothing but whitespace is filtered with them**, for the reason the absent one
         * is: a blank is a name as far as a map lookup is concerned, and it renders as the label with
         * nobody after it — which is exactly the placeholder every caller here is written not to
         * print. The column admits one, so this is the only place it can be stopped; the archive
         * carries none today, and what makes it worth a predicate rather than a wait is that every
         * retrieved turn now goes through this read and not only a page of findings.
         *
         * One predicate covers both: `btrim` of null is null, and a null comparison is not true, so
         * the row is excluded. A separate null test would be a second way of saying it.
         */
        val CONTACT_NAMES = """
            SELECT id, display_name
            FROM contact
            WHERE id = ANY(?::uuid[])
              AND btrim(display_name) <> ''
        """.trimIndent()

        /**
         * Where each of these messages was said, and the contact behind whoever wrote it.
         *
         * One row per message at most: a message belongs to one conversation and records one
         * author, and `contact` holds one row per person record, which its own uniqueness
         * constraint on that pair guarantees. Projected under the column names a record identifier
         * is read back from, so the pair the caller asked with is what comes back as the key —
         * matching on position instead would key the result by whatever order the join happened to
         * produce. The conversation is projected under names of its own for the same reason.
         *
         * **The contact is reached by a left join and the conversation by an inner one**, which is
         * the difference between the two facts: every message has a conversation, and plenty record
         * no author or an author identity has not reached yet. A message the corpus does not hold
         * is absent altogether.
         *
         * **Bound to the asked-for pairs rather than ordered or limited**, as the two other keyed
         * lookups on this port are: the caller is naming records it already holds, so there is no
         * ordering for a limit to make meaningful. The class note's rule is about the reads that
         * return a list.
         *
         * **Joined above the `WHERE` rather than tested inside it**, which [IN_CONVERSATION] warns
         * against for the statements that bind a horizon too: this one binds nothing but the pairs,
         * so the binder's order cannot come apart from the statement's.
         *
         * **A withdrawn message is not excluded here, unlike every read that fetches one.** Nothing
         * is quoted back by naming an author: the caller is holding a span it retrieved through some
         * other read, and filtering here would only strip the name off a turn already in the prompt.
         * `RECENT_MESSAGES` and `CLOSURES` are the reads where the withdrawal bites, and the index's
         * neighbour read is the third — see `JdbcIndexRepository.NEIGHBOURS`, which excludes it for
         * the same reason those two do, since it is the index read that hands a body back.
         */
        val MESSAGE_TURNS = """
            SELECT m.source,
                   m.native_id,
                   cv.source AS conversation_source,
                   cv.native_id AS conversation_native_id,
                   cv.kind AS conversation_kind,
                   cv.name AS conversation_name,
                   c.id AS contact
            FROM message m
            JOIN unnest(?::text[], ?::text[]) AS asked (source, native_id)
              ON m.source = asked.source AND m.native_id = asked.native_id
            JOIN conversation cv ON cv.id = m.conversation_id
            LEFT JOIN contact c ON c.record_source = m.author_source
                               AND c.record_native_id = m.author_native_id
        """.trimIndent()

        val CONTACT_CONVERSATIONS = """
            SELECT source, native_id FROM (
                SELECT cv.source,
                       cv.native_id,
                       row_number() OVER (
                           PARTITION BY cluster.head
                           ORDER BY bool_or(cv.source = c.record_source
                                        AND cv.native_id = c.record_native_id) DESC,
                                    max(m.source_time) DESC
                       ) AS rank
                FROM unnest(?::uuid[], ?::uuid[]) AS cluster (member, head)
                JOIN contact c ON c.id = cluster.member
                JOIN message m ON m.author_source = c.record_source
                              AND m.author_native_id = c.record_native_id
                JOIN conversation cv ON cv.id = m.conversation_id
                GROUP BY cluster.head, cv.source, cv.native_id
            ) ranked
            WHERE rank <= ?
            ORDER BY rank
        """.trimIndent()

        /**
         * The tail of each conversation, in the order the caller asked for them.
         *
         * `WITH ORDINALITY` carries the caller's ordering into the statement, which is the only way
         * to return conversations in the order they were given — an array has an order and a join
         * does not preserve it. Within each conversation the rows come back oldest-first even though
         * it is the newest that were selected, because a stretch of conversation read backwards is a
         * different conversation.
         *
         * Withdrawn messages are excluded. A source that said a message is gone has not had it
         * removed from the corpus, but quoting it back in an answer is the one thing that statement
         * is recorded to prevent.
         */
        val RECENT_MESSAGES = """
            SELECT source, native_id, author_source, author_native_id, body, source_time FROM (
                SELECT m.source,
                       m.native_id,
                       m.author_source,
                       m.author_native_id,
                       m.body,
                       m.source_time,
                       asked.ord,
                       row_number() OVER (
                           PARTITION BY m.conversation_id ORDER BY m.source_time DESC
                       ) AS rank
                FROM message m
                JOIN conversation cv ON cv.id = m.conversation_id
                JOIN unnest(?::text[], ?::text[]) WITH ORDINALITY AS asked (source, native_id, ord)
                  ON cv.source = asked.source AND cv.native_id = asked.native_id
                WHERE m.withdrawn_at IS NULL
            ) ranked
            WHERE rank <= ?
            ORDER BY ord, source_time
        """.trimIndent()

        /**
         * The closing message of every one of these commitments that has one.
         *
         * **An inner join, so only the closed ones come back.** A verdict recording that nothing
         * closed a promise carries no span, and a left join would hand the caller a row that has to
         * be read as *no closure* — which is the same answer as the absent row and a second way of
         * saying it. The port says why the two states are deliberately one to a reader.
         *
         * A withdrawn message is excluded here as it is in every other read: a promise whose
         * closing turn was withdrawn is one nothing citable discharges, and offering it would quote
         * back exactly what the withdrawal is recorded to prevent.
         */
        val CLOSURES = """
            SELECT cc.commitment AS id, m.source, m.native_id, m.author_source, m.author_native_id,
                   m.body, m.source_time
            FROM commitment_closure cc
            JOIN message m
              ON m.source = cc.record_source
             AND m.native_id = cc.record_native_id
            WHERE cc.commitment = ANY (?::uuid[])
              AND cc.record_source IS NOT NULL
              AND m.withdrawn_at IS NULL
        """.trimIndent()

        /**
         * What every finding read projects: the claim row, then the span joined to it.
         *
         * `source_time` is the evidence's and is deliberately the only one of that name in the
         * projection. The claim row carries one too — when the thing was first stated — and a
         * finding has no such field, so projecting both would give the row mapper two columns of
         * one name and a reason to read the wrong one.
         */
        const val COMMITMENT_COLUMNS =
            "c.id, c.conversation_source, c.conversation_native_id, c.claim, c.hedged, c.due_at, " +
                "c.stated_by, c.obliged_party"

        /** What every event read projects. */
        const val EVENT_COLUMNS =
            "e.id, e.conversation_source, e.conversation_native_id, e.claim, e.hedged, e.occurs_at, e.stated_by"

        /** The span columns, from the evidence table joined to whichever claim row is being read. */
        const val EVIDENCE_COLUMNS =
            "ev.record_kind, ev.record_source, ev.record_native_id, ev.ordinal, ev.source_time"

        /**
         * Whether a finding was stated inside one of the wanted conversations.
         *
         * Read off the claim row, which carries the conversation because that is what scopes the
         * claim it is keyed by. Nothing has to reach the corpus to find out.
         *
         * **An `EXISTS` in the `WHERE` rather than a join, so that the parameter order matches the
         * order the binder binds in.** A join is written above the `WHERE`, which would put its two
         * array placeholders ahead of the horizon's two — and a positional binder that bound the
         * horizon first would then filter events by conversation name and conversations by date,
         * silently, with no type error to catch it. Keeping every narrowing inside the `WHERE` keeps
         * the statement's order and the binder's the same one.
         */
        const val IN_CONVERSATION = """
            EXISTS (
                SELECT 1 FROM unnest(?::text[], ?::text[]) AS wanted (source, native_id)
                WHERE wanted.source = %s.conversation_source
                  AND wanted.native_id = %s.conversation_native_id
            )
        """

        /**
         * The findings chosen and limited first, with their evidence joined on after.
         *
         * The limit has to bind inside the subquery. Applied to the joined result it would count
         * evidence rows, so one obligation carrying two spans would displace another finding
         * entirely — a read that got quietly narrower the better the deduplication worked.
         */
        val COMMITMENTS_EVERYWHERE = """
            SELECT $COMMITMENT_COLUMNS, $EVIDENCE_COLUMNS
            FROM (
                SELECT * FROM commitment
                WHERE superseded_by IS NULL
                ORDER BY source_time DESC
                LIMIT ?
            ) c
            JOIN commitment_evidence ev ON ev.commitment = c.id
            ORDER BY c.source_time DESC
        """.trimIndent()

        val COMMITMENTS_BY_CONTACT = """
            SELECT $COMMITMENT_COLUMNS, $EVIDENCE_COLUMNS
            FROM (
                SELECT * FROM commitment
                WHERE stated_by = ANY (?::uuid[])
                  AND superseded_by IS NULL
                ORDER BY source_time DESC
                LIMIT ?
            ) c
            JOIN commitment_evidence ev ON ev.commitment = c.id
            ORDER BY c.source_time DESC
        """.trimIndent()

        val COMMITMENTS_IN_CONVERSATION = """
            SELECT $COMMITMENT_COLUMNS, $EVIDENCE_COLUMNS
            FROM (
                SELECT * FROM commitment c
                WHERE ${IN_CONVERSATION.format("c", "c").trim()}
                  AND c.superseded_by IS NULL
                ORDER BY c.source_time DESC
                LIMIT ?
            ) c
            JOIN commitment_evidence ev ON ev.commitment = c.id
            ORDER BY c.source_time DESC
        """.trimIndent()

        val COMMITMENTS = Narrowed(
            everywhere = COMMITMENTS_EVERYWHERE,
            byContact = COMMITMENTS_BY_CONTACT,
            inConversation = COMMITMENTS_IN_CONVERSATION,
        )

        /**
         * The dated-event filter every windowed event read shares.
         *
         * `occurs_at` rather than `source_time`: a date read out of a message two years ago can be next
         * week. An event with no date fails the comparison and is excluded, which is wanted here —
         * there is nothing to place it in time with, and a row admitted to a window it cannot claim
         * would be answered as having happened in it. [EVENTS_UNDATED] is what reads those instead.
         */
        const val EVENTS_DATED =
            "WHERE e.occurs_at >= ? AND e.occurs_at <= ?\n  AND e.superseded_by IS NULL"

        /**
         * One event read, narrowed by [extra] and ordered by [direction].
         *
         * **The ordering is applied twice, and both are load-bearing.** The inner one decides which
         * events the limit keeps; the outer one decides what order they come back in, because a
         * join does not preserve the order of the relation it reads. Dropping the outer one would
         * leave the sequence up to the planner, which is how "what is coming up next" quietly stops
         * being next.
         */
        fun eventsRead(extra: String, direction: String) = """
            SELECT $EVENT_COLUMNS, $EVIDENCE_COLUMNS
            FROM (
                SELECT * FROM event e
                $EVENTS_DATED$extra
                ORDER BY e.occurs_at$direction
                LIMIT ?
            ) e
            JOIN event_evidence ev ON ev.event = e.id
            ORDER BY e.occurs_at$direction
        """.trimIndent()

        /** The three narrowings of an event read, in one direction. */
        fun eventStatements(direction: String) = Narrowed(
            everywhere = eventsRead("", direction),
            byContact = eventsRead("\n  AND e.stated_by = ANY (?::uuid[])", direction),
            inConversation = eventsRead("\n  AND ${IN_CONVERSATION.format("e", "e").trim()}", direction),
        )

        /**
         * One undated-event read, narrowed by [extra].
         *
         * **Ordered by `source_time`, which the dated reads deliberately do not use.** There it
         * would answer a question about next week with whatever was mentioned most recently; here
         * it is the only time the row has, and the alternative to ordering by it is a limit that
         * keeps whichever rows the planner happened to reach. It is the earliest span that
         * evidenced the row rather than the latest, so this is first-mentioned order.
         *
         * Apart from [eventsRead] because that statement interpolates [EVENTS_DATED], whose
         * comparison no undated row can satisfy. It also binds two bounds ahead of the narrowing,
         * and this read has none to bind — so the binder's order here is the narrowing and then the
         * limit.
         */
        fun undatedEventsRead(extra: String) = """
            SELECT $EVENT_COLUMNS, $EVIDENCE_COLUMNS
            FROM (
                SELECT * FROM event e
                WHERE e.occurs_at IS NULL
                  AND e.superseded_by IS NULL$extra
                ORDER BY e.source_time DESC
                LIMIT ?
            ) e
            JOIN event_evidence ev ON ev.event = e.id
            ORDER BY e.source_time DESC
        """.trimIndent()

        /**
         * The narrowings of the undated-event read.
         *
         * Reachable every way a dated event is, because what it cannot say is *when* — not whom it
         * concerns or which conversation it was mentioned in.
         */
        val EVENTS_UNDATED = Narrowed(
            everywhere = undatedEventsRead(""),
            byContact = undatedEventsRead("\n  AND e.stated_by = ANY (?::uuid[])"),
            inConversation = undatedEventsRead("\n  AND ${IN_CONVERSATION.format("e", "e").trim()}"),
        )

        /** Soonest first, for the events still ahead. */
        const val EVENTS_SOONEST = ""

        /** Most recent first, for the events already past. */
        const val EVENTS_LATEST = " DESC"
    }
}

private fun ResultSet.toExternalId(): ExternalId =
    ExternalId(Source.valueOf(getString("source")), getString("native_id"))

/**
 * A message as the answer path needs it.
 *
 * Its claims are not read. They are what identity matching compares, and nothing on this path looks
 * at them — fetching them would be a join per message for a field the prompt never shows.
 */
internal fun ResultSet.toMessage(): Message = Message(
    externalId = toExternalId(),
    sourceTime = requireNotNull(instant("source_time")),
    author = externalId("author_source", "author_native_id"),
    text = getString("body"),
)
