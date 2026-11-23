package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * Contacts, the links that join them, and the constraints that forbid a join.
 *
 * The derived layer's half of identity. Nothing here writes to the corpus and nothing here reads
 * a source: a contact is made of person records, and person records are what a source said.
 *
 * As everywhere else, a write is an upsert and a re-run writes nothing new. What makes that true
 * here is that an identifier is minted by the caller and kept by the store: a record that already
 * has a contact keeps the one it has, so re-running the pass cannot fork an entity or renumber
 * one.
 */
interface ContactRepository {

    /**
     * Write [contacts], keeping the identity any of them already has.
     *
     * A record already carrying a contact is left with it, and the identifier offered here is
     * discarded rather than overwriting it. That asymmetry is the whole of re-run safety: the
     * caller cannot know which records are new without asking, so it is allowed to offer a
     * freshly minted identity for every one of them and rely on this to ignore the ones that
     * would renumber something.
     */
    suspend fun upsert(contacts: List<Contact>)

    /** The contacts for these person records, in no particular order; absent ones are absent. */
    suspend fun of(records: List<ExternalId>): List<Contact>

    /**
     * Record that each link's merged side now resolves to its canonical side.
     *
     * A link that is already there is left alone rather than rescored. The score on a stored
     * link is the evidence that carried the decision when it was made, and overwriting it with
     * today's would quietly rewrite the reason a merge happened — which is the one thing an
     * unmerge needs to read.
     */
    suspend fun merge(links: List<MergeLink>)

    /**
     * The link merging [contact] into a head, or `null` when it is a head itself.
     *
     * What makes a merge reviewable rather than merely reversible. The score and the signals are
     * stored so that a human asking why two accounts were joined gets the evidence that joined
     * them, and so that whoever decides to undo it is reading the decision rather than guessing
     * at it. Written and never read, they would be a comment in a column.
     */
    suspend fun linkOf(contact: EntityId): MergeLink?

    /**
     * Undo the merge of [contact], and record that it may not happen again.
     *
     * Two writes, and both are needed. Removing the link restores both sides; recording the
     * [Separation] is what stops the next pass merging them straight back, because the evidence
     * that carried the merge is still in the corpus and still says what it said. A correction
     * outranks re-recognition.
     *
     * Does nothing if [contact] is not merged into anything — unmerging what was never merged
     * is not an error, and nothing about it is worth refusing.
     */
    suspend fun unmerge(contact: EntityId)

    /**
     * The head each of [contacts] resolves to, following links.
     *
     * **Every input gets an entry**, including a head, which maps to itself. A caller resolving a
     * set of contacts is asking which of them are the same human, and an absent key would be
     * indistinguishable from a contact that does not exist.
     *
     * In bulk because the pass resolves a whole batch and its candidates at once, and one query
     * per contact would be a round trip per account considered.
     *
     * **This answers for the inputs and for nothing else.** A caller that needs the other side of
     * a constraint — every record already merged into the same human — wants [clusters], because
     * a member nobody asked about does not appear here.
     */
    suspend fun heads(contacts: List<EntityId>): Map<EntityId, EntityId>

    /**
     * Every cluster any of [contacts] belongs to, whole.
     *
     * The difference from [heads] is the one that decides whether two humans get fused. [heads]
     * resolves what it is given; this returns the full membership of each cluster it lands in,
     * including contacts the caller has never seen. A pass checking cannot-link constraints needs
     * that: a hard negative or a correction involving a record merged on some earlier pass forbids
     * a join the current batch can still make, and a check that only knew the batch would allow
     * it. The resulting cluster is a human assembled wrongly, which no later pass reports and no
     * count makes visible.
     *
     * An unmerged contact is a cluster of one, so every input appears in exactly one group here.
     */
    suspend fun clusters(contacts: List<EntityId>): List<Cluster>

    /** The recorded separations among [among] — the pairs a correction has forbidden. */
    suspend fun separations(among: List<EntityId>): List<Separation>

    /**
     * Keep [candidates] — pairs that scored without reaching the threshold.
     *
     * A pair already kept has its score replaced, because this one is the current reading of
     * evidence that has since grown. That is the opposite of [merge]'s rule, and deliberately:
     * a candidate is a running assessment, while a link is a decision that was taken.
     */
    suspend fun keep(candidates: List<CandidateMerge>)

    /**
     * The kept candidate pairs involving any of [among].
     *
     * What makes keeping them worth doing. A pair scoring halfway today crosses the threshold the
     * moment a number appears on one side, and this is the read that finds the pairs a newly
     * arrived claim could tip — so the work is a re-evaluation of a few pairs rather than a
     * rescoring of the corpus.
     */
    suspend fun candidates(among: List<EntityId>): List<CandidateMerge>
}
