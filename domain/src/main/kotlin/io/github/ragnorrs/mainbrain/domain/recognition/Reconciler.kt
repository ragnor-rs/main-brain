package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.ClientRepository
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.ObservedPerson
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind

/**
 * The pass that turns person records into contacts, and decides which of them are one human.
 *
 * The identity stage, and the one place that assigns a contact's or a client's identity. A plain
 * class composing ports with no interface, like the indexing pass: what schedules it — a loop, a
 * lock, a cadence — is the containing process's concern, so this does one batch and says how much
 * it did.
 *
 * **No model call, anywhere in here.** Identity is identifier matching and label reading, which
 * is deterministic and cheap, so it runs over the whole corpus while inference stays inside the
 * recency horizon. There is no horizon in this class and there should not be one.
 *
 * Three things happen per batch, in an order that matters.
 *
 * **A contact per person record**, minted if the record has none and left alone if it has. This
 * cannot fail and involves no judgement, which is why it is separate from the next step.
 *
 * **Scoring against candidates**, which are the records sharing a claim with something in the
 * batch *or* carrying a name one of them carries. Comparing against the whole corpus is not an
 * option, so those two keys select the records a batch member could match.
 *
 * **The name key is what makes a single-source corpus resolvable at all.** A handle is unique per
 * account and a number is fixed when an account is registered, so two accounts of one human share
 * no identifier and never will — selecting on claims alone would never bring such a pair together,
 * and a pair that is never assembled is never scored, never kept, and invisible to anyone looking
 * for duplicates. The name key is bounded by how common the name is, because a name a crowd
 * carries is evidence of nothing and pulling its carriers is not free.
 *
 * Neither key makes a pair merge on a name: the threshold is unmoved and a bare name match falls
 * well under it. What the name key changes is whether the pair is *scored* — and so whether it is
 * kept where a person calibrating the threshold can read it.
 *
 * **Clustering under constraints, not transitive closure.** A pair above the threshold merges,
 * but only if no pair across the two clusters it would join is forbidden. A hard negative splits
 * a cluster however strong each pairwise link in it looks on its own, which is the difference
 * between this and following the links wherever they lead.
 */
class Reconciler(
    private val derivations: DerivationStateRepository,
    private val corpus: Corpus,
    private val contacts: ContactRepository,
    private val clients: ClientRepository,
    private val weights: Weights = Weights.STARTING,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    private val scorer = MatchScorer(weights)
    private val persons = corpus.persons
    private val conversations = corpus.conversations
    private val messages = corpus.messages

    init {
        require(batchSize > 0) { "A batch of $batchSize records is not a batch." }
    }

    /**
     * Reconcile one batch, returning what it looked at and what came of it.
     *
     * Zero records means there was nothing pending. The two numbers differ for the reason the
     * return type carries both — a conversation naming no body of work is looked at and produces
     * no client — so records alone is progress rather than a tally of what was created, and
     * [owed] is the honest way for a driver to decide whether to go round again.
     *
     * Two record kinds, one batch. The watermark is keyed by kind, so each advances on its own,
     * and a driver has one unit of work to run rather than two to keep in step.
     */
    suspend fun reconcileBatch(): Recognised {
        val persons = contactBatch()
        val conversations = clientBatch()
        return Recognised(records = persons + conversations.records, clients = conversations.clients)
    }

    /**
     * Conversations carrying a name, read as clients.
     *
     * No scoring, no candidates and no constraints: identity here is the label, so the whole
     * stage is a read, a filter and a write. A conversation with no name is marked along with the
     * rest — it is not a client and never will be unless the source names it, and leaving it
     * unmarked would hand it back on every pass for ever.
     *
     * **A direct exchange is not a named conversation, whatever name it displays.** The platform
     * renders a two-person chat under the counterparty's own name: nobody assigned it, it is not
     * a statement about what the conversation is for, and it is already the display name of that
     * person's contact. Admitting it would mint a body of work per person ever written to, name
     * each after a human, and leave a contact as its own parent — while the name a reader would
     * search by means two things at once. Excluding it reads the source's taxonomy rather than
     * judging what a label means: which *named* conversations stand for a body of work is a
     * narrowing the model does, and nothing here anticipates it.
     */
    private suspend fun clientBatch(): Recognised {
        val derivation = Derivation(Deriver.RECOGNITION, weights.identityVersion())
        val pending = derivations.pending(derivation, RecordKind.CONVERSATION, batchSize, notBefore = null)
        if (pending.records.isEmpty()) return Recognised(records = 0, clients = 0)

        val batch = conversations.conversations(pending.records)
        val named = batch.filter { it.kind != ConversationKind.DIRECT && !it.name.isNullOrBlank() }
        clients.upsert(
            named.map { conversation ->
                Client(
                    id = EntityId.mint(),
                    conversation = conversation.externalId,
                    name = requireNotNull(conversation.name),
                )
            },
        )
        derivations.mark(derivation, RecordKind.CONVERSATION, batch.map { it.externalId }, pending.asOf)
        return Recognised(records = batch.size, clients = named.size)
    }

    private suspend fun contactBatch(): Int {
        val derivation = Derivation(Deriver.RECOGNITION, weights.identityVersion())
        // No horizon, for the reason stated at the top of this class: identity is deterministic
        // and cheap, so it runs over everything while inference stays inside the horizon.
        val pending = derivations.pending(derivation, RecordKind.PERSON, batchSize, notBefore = null)
        if (pending.records.isEmpty()) return 0

        val batch = persons.persons(pending.records)
        val known = recognise(batch)
        val candidates = candidatesFor(batch)
        val scored = score(batch, candidates)

        // Written before the marks, never after. A failure between the two re-reconciles a
        // record, which is free — a contact is kept rather than reminted and a link already
        // there is left alone. The reverse order records records as recognised that were never
        // looked at, and no re-run revisits them.
        apply(scored, known, batch + candidates)

        derivations.mark(derivation, RecordKind.PERSON, batch.map { it.externalId }, pending.asOf)
        return batch.size
    }

    /**
     * How many records are still owed identity under the current weights.
     *
     * Both kinds, because a driver looping until this is zero would otherwise stop with every
     * conversation outstanding the moment the person records ran out.
     *
     * The whole backlog rather than a page of it, for the reasons given on the port: a bounded
     * read answers whether there is work and never how much, and both callers need the number —
     * a driver to tell progress from a stall, a reader to tell a stopped pass from a quiet
     * corpus.
     */
    suspend fun owed(): Int {
        val derivation = Derivation(Deriver.RECOGNITION, weights.identityVersion())
        return derivations.owed(derivation, RecordKind.PERSON, notBefore = null) +
            derivations.owed(derivation, RecordKind.CONVERSATION, notBefore = null)
    }

    /**
     * A contact for every record in the batch, and the contacts of everything it might match.
     *
     * An identifier is minted for each record because the store keeps the one a record already
     * has and discards the offered one — so this does not need to know which records are new, and
     * asking would be a round trip to learn something the write already handles.
     */
    private suspend fun recognise(batch: List<ObservedPerson>): Map<ExternalId, Contact> {
        contacts.upsert(
            batch.map { person ->
                Contact(id = EntityId.mint(), record = person.externalId, displayName = person.displayName)
            },
        )
        return contacts.of(batch.map { it.externalId }).associateBy { it.record }
    }

    /**
     * The records sharing a claim or a name with anything in the batch, excluding the batch itself.
     *
     * Two keys, because on a source where one human's accounts share no identifier the claim key
     * finds nothing and the pair is never assembled.
     *
     * **Deduplicated, and what that protects is the denominator rather than the pair.** A record
     * sharing both a claim and a name comes back from both reads. [score] would still compare it
     * once — it keys the pairs it has seen — but the frequency it scales by counts claims across
     * the assembled set, so a record counted twice would inflate the carriers of every value it
     * holds and scale down the very signal the pair agrees on. That is exactly the exactness
     * [score] claims for itself, so it has to be true here.
     *
     * The name key is bounded by [Weights.namesakeLimit]; the claim key is not, because a claim is
     * specific enough that its carriers are few by nature, and a number a crowd shares is handled
     * where it is scored rather than by refusing to look at it.
     *
     * A candidate that has never been reconciled has no contact yet, and the pair is dropped
     * where the scores are applied rather than here. Nothing
     * is lost: it will be reconciled in its own batch, and it will find these records then —
     * every pair is scored from whichever side arrives last.
     */
    private suspend fun candidatesFor(batch: List<ObservedPerson>): List<ObservedPerson> {
        val claims = batch.flatMap { person -> person.claims.map { it.identifier } }
        val names = batch.mapNotNull { nameKey(it.displayName) }.distinct()
        val inBatch = batch.map { it.externalId }.toSet()
        val byClaim = if (claims.isEmpty()) emptyList() else persons.claimants(claims)
        val byName = if (names.isEmpty()) emptyList() else persons.namesakes(names, weights.namesakeLimit())
        return (byClaim + byName)
            .distinctBy { it.externalId }
            .filter { it.externalId !in inBatch }
    }

    /**
     * Every batch record against every candidate, scored.
     *
     * The frequency a signal is scaled by is exact for the signals that can matter. A shared
     * value is necessarily one the batch carries, and every claim the batch carries was asked
     * about — so the claimants of it are all present and the count is the corpus count, not a
     * count of what this query happened to return.
     *
     * **A candidate pulled in by its name does not perturb that**, though it arrives carrying
     * claims nobody asked about. Every pair scored here has a batch member on one side, so a
     * value the pair *shares* is one the batch carries and its claimants were all asked for. A
     * claim seen only on namesakes is counted short, and cannot be a shared signal of any pair
     * this scores. Names are counted against the corpus rather than against this set, so the
     * bound that selected the namesakes does not become the denominator that scores them.
     */
    private suspend fun score(batch: List<ObservedPerson>, candidates: List<ObservedPerson>): List<Scored> {
        if (batch.isEmpty()) return emptyList()
        val everyone = batch + candidates
        val frequency = Frequency(
            claimants = everyone.flatMap { it.claims }.groupingBy { it.identifier }.eachCount(),
            names = persons.nameFrequency(everyone.mapNotNull { nameKey(it.displayName) }.distinct()),
        )
        val seen = mutableSetOf<Set<ExternalId>>()
        return buildList {
            batch.forEach { person ->
                everyone.filter { it.externalId != person.externalId }.forEach { other ->
                    // One pair, scored once. Two records both in the batch would otherwise be
                    // compared from each side, and a pair kept twice is a pair whose stored
                    // score depends on which way round it was last written.
                    if (seen.add(setOf(person.externalId, other.externalId))) {
                        add(
                            Scored(
                                one = person.externalId,
                                other = other.externalId,
                                match = scorer.score(person.asCandidate(), other.asCandidate(), frequency),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Merge what clears the threshold and may merge; keep the rest with its score.
     *
     * Highest score first, so that when a constraint forbids a join it is the weakest evidence
     * that loses rather than whichever pair happened to be considered first.
     *
     * **A pair already resolving to one human is left alone** — neither merged again nor kept as a
     * candidate. It is a third state beside *kept* and *separated*, and it is reachable: a pair
     * merged when the weights said it cleared the threshold is re-scored whenever the identity
     * version moves, and today's arithmetic may put it under today's threshold. Keeping it would
     * file a live contact pair as an unresolved one, so the same two accounts would read as joined
     * and as merely suspected at once — and the report of unresolved pairs would lead with a pair
     * that was resolved long ago. Nothing here unlinks: a stored decision is undone by a
     * correction, never by a pass rescoring it.
     */
    private suspend fun apply(
        scored: List<Scored>,
        known: Map<ExternalId, Contact>,
        everyone: List<ObservedPerson>,
    ) {
        val contactOf = (known + contacts.of(everyone.map { it.externalId }).associateBy { it.record })
        // **The whole of every cluster the batch touches, not just the batch.** A contact merged
        // on an earlier pass is in no part of this batch, and a constraint involving it has to
        // hold anyway: the pair it forbids is one this batch can still join. Resolving only the
        // batch's own contacts leaves every absent cluster member unchecked, and the join that
        // wrongly allows is two humans fused — which nothing afterwards reports.
        val cluster = contacts.clusters(contactOf.values.map { it.id })
        val members = cluster.flatMap { it.members }
        val clusters = Constrained(
            heads = cluster.flatMap { group -> group.members.map { it.id to group.head } }.toMap(),
            recordOf = members.associate { it.id to it.record },
            // Read over the expanded membership for the same reason. Both are constraints
            // *between* contacts, so a read scoped to the batch answers for half of each pair.
            roommates = messages.sharingAConversation(members.map { it.record }).symmetric(),
            separated = contacts.separations(members.map { it.id })
                .map { it.one to it.other }.symmetric(),
        )

        val links = mutableListOf<MergeLink>()
        val kept = mutableListOf<CandidateMerge>()
        scored.sortedByDescending { it.match.score }.forEach { pair ->
            val one = contactOf[pair.one]?.id
            val other = contactOf[pair.other]?.id
            if (one == null || other == null) return@forEach
            // Already one human, by a link some pass or correction settled. Not a merge to repeat
            // and not a candidate to file; see this function's note on the third state.
            if (clusters.joined(one, other)) return@forEach
            if (pair.match.merges) {
                clusters.join(one, other)?.let { (merged, canonical) ->
                    links += MergeLink(merged, canonical, pair.match.score, pair.match.signals)
                }
            } else if (pair.match.score > 0.0) {
                kept += CandidateMerge.of(one, other, pair.match.score, pair.match.signals)
            }
        }
        contacts.merge(links)
        contacts.keep(kept)
    }

    private fun ObservedPerson.asCandidate() =
        MatchCandidate(record = externalId, displayName = displayName, claims = claims)

    private data class Scored(val one: ExternalId, val other: ExternalId, val match: Match)

    companion object {
        /**
         * Person records per batch.
         *
         * Smaller than the indexing pass's, because a batch here fans out: every record in it
         * pulls in the records sharing any of its claims *and* the records carrying any of its
         * names, and all of those are scored against it. The name key is what bounds the second
         * half — at most [Weights.namesakeLimit] carriers per distinct name, or none at all — so
         * the fan-out stays a multiple of the batch rather than a fraction of the corpus.
         */
        const val DEFAULT_BATCH_SIZE = 64
    }
}

/**
 * What one batch of the identity stage did.
 *
 * Two numbers rather than one, because this stage can settle every conversation in the corpus and
 * produce nothing: a rule that admits none of them and a watermark that marks all of them read as
 * a batch that worked. [records] is what was looked at and [clients] is what came of it, and only
 * the second moves when the rule about what a name stands for changes.
 *
 * [clients] counts the conversations that yielded a client rather than the identifiers newly
 * assigned. The write keeps the identity a conversation already holds, so telling a new client
 * from a kept one would be a second read for a number nothing but a log line wants.
 *
 * Neither number is what a driver reads as progress. The pass that schedules this reads a fall in
 * [owed] instead and says why, so both of these exist for whoever reads a log rather than for
 * anything that makes a decision.
 *
 * @property records person records and conversations together — what this batch looked at and
 *   marked, not what it made of them.
 * @property clients conversations this batch read a client from.
 */
data class Recognised(val records: Int, val clients: Int)

/**
 * The corpus, as the identity stage reads it.
 *
 * Three record ports bundled rather than taken one by one, because this pass reads three kinds
 * and would otherwise take its whole world as positional parameters — which is the shape where
 * a port gets wired into the wrong slot and nothing fails to compile. It holds ports and no
 * state, so it is the parameter list named rather than a layer added.
 */
data class Corpus(
    val persons: PersonRepository,
    val conversations: ConversationRepository,
    val messages: MessageRepository,
)

/**
 * Clustering under cannot-link constraints.
 *
 * Union-find, seeded with the merges already stored so that a re-run joins what is joined rather
 * than rediscovering it, and refusing any join that would put a forbidden pair in one cluster.
 * The refusal is checked across every member of both clusters, not just the pair being
 * considered: a hard negative between two records that are each strongly linked to a third is
 * precisely the case transitive closure gets wrong, and it is the case that fuses two humans.
 */
private class Constrained(
    heads: Map<EntityId, EntityId>,
    private val recordOf: Map<EntityId, ExternalId>,
    private val roommates: Set<Pair<ExternalId, ExternalId>>,
    private val separated: Set<Pair<EntityId, EntityId>>,
) {

    private val head = heads.toMutableMap()
    private val members = heads.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (headId, cluster) -> (cluster + headId).toMutableSet() }
        .toMutableMap()

    /**
     * Whether [one] and [other] already resolve to one human.
     *
     * Asked before a pair is acted on, and separate from [join]'s refusal: that returns `null` for
     * *already joined* and for *forbidden* alike, and the two call for opposite handling. An
     * already-joined pair is settled and wants nothing done; a forbidden one is a pair the
     * evidence still describes and a correction has overruled.
     */
    fun joined(one: EntityId, other: EntityId): Boolean = (head[one] ?: one) == (head[other] ?: other)

    /**
     * Join the clusters holding [one] and [other], or refuse.
     *
     * Returns the link to write — the absorbed head and its new canonical — or `null` when the
     * two are already one cluster or a constraint forbids it. The smaller identifier becomes
     * canonical, which is arbitrary and has to be: what matters is that it is the same choice on
     * every run, so a re-run writes no new links.
     */
    fun join(one: EntityId, other: EntityId): Pair<EntityId, EntityId>? {
        val left = head[one] ?: one
        val right = head[other] ?: other
        val leftMembers = members[left] ?: mutableSetOf(left)
        val rightMembers = members[right] ?: mutableSetOf(right)
        val refused = left == right ||
            leftMembers.any { l -> rightMembers.any { r -> forbidden(l, r) } }
        if (refused) return null

        val canonical = if (left < right) left else right
        val absorbed = if (canonical == left) right else left
        val joined = members.getOrPut(canonical) { mutableSetOf(canonical) }
        joined += members.remove(absorbed) ?: mutableSetOf(absorbed)
        joined.forEach { head[it] = canonical }
        return absorbed to canonical
    }

    private fun forbidden(one: EntityId, other: EntityId): Boolean {
        if ((one to other) in separated) return true
        val records = recordOf[one]?.let { first -> recordOf[other]?.let { first to it } }
        return records in roommates
    }
}

/** Both orderings, so that a pair can be looked up whichever way round it is held. */
private fun <T> List<Pair<T, T>>.symmetric(): Set<Pair<T, T>> =
    flatMap { (one, other) -> listOf(one to other, other to one) }.toSet()

/**
 * The version of the identity stage, for the derivation watermark.
 *
 * Built from the weights themselves rather than bumped by hand, because a changed weight changes
 * what the pass decides and a mark the string does not invalidate is a decision nothing
 * re-derives. The prefix covers changes the numbers do not capture — a signal added, the
 * normalising of a name altered, **which keys [Reconciler.candidatesFor] generates on**, **which
 * conversations [Reconciler.clientBatch] admits** — and has to be bumped when one happens. The
 * last two are the easiest to miss, because the weights are about matching people and neither a
 * rule about reading labels nor a rule about which pairs are assembled touches any of them: widen
 * what becomes a candidate without bumping this, and every record already settled stays settled —
 * so the widened generation is never asked about the corpus that motivated it, and the table it
 * was meant to fill stays empty.
 *
 * **The generation bound is in the string rather than in the prose above it.** It decides which
 * pairs are assembled, so it decides what the pass concludes — and it is derived from a constant
 * none of the weights carry, so without it a change to how common a name may be would leave every
 * settled record settled under arithmetic the build no longer performs. That is the one failure
 * this whole string exists to prevent, and a rule that held only because somebody remembered to
 * bump a prefix is the version of it that has already been missed once.
 *
 * **Only identity re-derives, and it costs no tokens.** This string keys the recognition deriver's
 * person and conversation marks; inference derives over messages under a version built separately,
 * so nothing here re-runs a model call. That asymmetry is what makes erring towards a bump the
 * cheap mistake, and it is worth checking before each one rather than assumed.
 *
 * One string serves both record kinds, so a bump re-derives every person mark as well as every
 * conversation one. That is wider than the change that prompted it and deliberately so, for the
 * reason the next paragraph gives.
 *
 * Re-deriving identity is local compute and costs no model tokens, so erring towards doing it
 * again is the cheap mistake here. Erring the other way leaves contacts joined on arithmetic
 * nothing in the build performs any more.
 */
internal fun Weights.identityVersion(): String =
    "identity-3:$phone/$email/$handle/$avatar/$name@$threshold~${namesakeLimit()}"
