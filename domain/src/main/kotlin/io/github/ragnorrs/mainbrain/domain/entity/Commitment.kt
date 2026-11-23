package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.time.Instant

/**
 * Something someone said would be done, as the system holds it.
 *
 * **The highest value in the derived layer and the weakest evidence in it.** A commitment is a
 * speech act: frequently hedged, sometimes retracted, often met in silence. So it is never
 * presented as a fact — [hedged] travels with it and so does its evidence, and anything rendering
 * one shows the message it was read out of.
 *
 * **Identity is what it says, within the conversation it was said in.** The claim is the content key
 * and the spans in [evidence] are what states it, which is the shape `Statement` already has. So one
 * obligation restated later in a conversation is one commitment carrying two pointers, and two
 * obligations stated in one message are two commitments — neither of which a key built from the
 * evidencing span could express, a message being one span.
 *
 * **The conversation is in the key because the claim alone cannot carry it.** A claim is restated to
 * stand on its own and names its party only where the corpus names that person, so two identical
 * unattributed claims from different chats are not known to be one obligation.
 *
 * Exact text is the first comparison and not the whole of one: a rewriting produces a different
 * string for one fact, and recognising that pair is the collapse's to do — see [CollapseRepository].
 *
 * **Direction is two facts and both are recorded, which is not the same as either being answered.**
 * Owed by us and owed to us are different rows and request shape 3 asks for both. Which person
 * record is the account the corpus was read through is part of the record — see
 * [io.github.ragnorrs.mainbrain.domain.record.Person.isSelf] — and which party a claim obliges is
 * [obligedParty]. [statedBy] is the author of the message and is **not** the obligated party: a
 * promise relayed by a third person is stated by whoever reported it, so a direction derived from
 * the author would be confidently wrong in exactly the cases that matter. The shapes answering where
 * things stand and what to say on a call read the two together and name each separately; the shape
 * drafting a reply does not, and for it the claim's prose remains the only account of direction.
 *
 * **What bounds it is how often the obliged party can be placed at all.** The restating stage can
 * answer that party only as one of its window's labels, so an obligation placed on somebody who
 * never wrote in the conversation, or on a participant whose name the label rule withdrew, carries
 * none — and there the claim's prose is the only account of direction, as it is the only
 * reader-facing one in every case.
 *
 * **State is not a field here, and what exists of it is a link beside this row.** What a later
 * message can evidence is that the thing was reported as done — see [Closure], which records that
 * and the message that records it. It is beside the finding rather than on it because this row is
 * the reading of one message and nothing later changes that reading: a re-derivation replaces what
 * it produced, and a closure written here would be replaced along with it.
 *
 * **Nothing anywhere gives this an open state, and that is a ceiling rather than an omission.** A
 * promise is frequently kept in silence, so the absence of a closing message is not evidence that
 * anything is still owed — no amount of model spend tells *still open* from *quietly done*. What
 * the build holds is closure evidenced against closure not evidenced, which subtracts from a list
 * of what is owed and never confirms what remains on one.
 */
data class Commitment(
    override val id: EntityId,
    /** The claim, restated to stand on its own. See `Proposal`, which is where the rewrite happens. */
    override val claim: String,
    /** The conversation it was stated in, which scopes the claim this is keyed by. */
    override val conversation: ExternalId,
    /**
     * Every span that states it, which is one until the same obligation is read again.
     *
     * Several is corroboration rather than duplication, as it is for a statement: an obligation
     * stated and then restated is one commitment that can be shown beside either message.
     */
    override val evidence: Set<Evidence>,
    val hedged: Boolean,
    /**
     * When it is due, where the message stated a time.
     *
     * Absolute, and resolved against the message it was stated in rather than against now. Absent
     * is the ordinary case: most promises carry no date, and an absent one is not a defect to fill
     * in with a guess.
     *
     * **Absent for several reasons, and nothing here tells them apart**: no date was stated, the
     * value could not be read, or it was refused against the message that stated it. A date is
     * refused for standing at that message's own instant, which says when the thing was said rather
     * than when it comes due; and for standing before it where nothing establishes that another
     * party owes the thing, since nothing is promised for a time already past by the party who is
     * to do it. That second refusal leaves an obligation stated about another participant alone — a
     * deadline of theirs is usually mentioned once it has been missed, so a time already behind is
     * kept there. The sweep that refused it counts each reason.
     */
    val due: Instant? = null,
    /**
     * The contact behind the author of the evidencing message — who stated it.
     *
     * Nullable because identity and inference are separate passes over the same corpus: a message
     * whose author has no contact yet leaves this empty rather than holding the pass up, and the
     * next run fills it. What it is never is an identifier this layer minted for the occasion.
     */
    val statedBy: EntityId? = null,
    /**
     * The contact a claim obliges — who is to do the thing, which is frequently not [statedBy].
     *
     * **The half of direction the author cannot give**, and the reason it is a field rather than
     * something read back out of the prose: an obligation one participant states about another is
     * a commitment, so who owes it has to be readable without parsing a sentence to be compared,
     * counted, or turned into owed-by-us against owed-to-us beside the account marker on the
     * record.
     *
     * Nullable for two reasons that are not the same. A window labels only participants the corpus
     * names, so an obligation placed on somebody who never wrote in the conversation has no label
     * to carry and leaves this empty; and identity may not have recognised a labelled party yet,
     * exactly as for [statedBy]. Neither is filled in with a guess, and an absence here is read as
     * direction unknown rather than as the obligation being the author's.
     *
     * The claim's prose is unchanged by this and stays what a reader sees and what a citation is
     * checked against.
     */
    val obligedParty: EntityId? = null,
) : Finding {
    init {
        require(claim.isNotBlank()) { "A commitment with no claim records nothing." }
        require(evidence.isNotEmpty()) {
            "A commitment with no evidence is a claim nothing in the corpus states, and its " +
                "citation would name nothing a reader can look up."
        }
    }
}

/**
 * Commitments, by what each says within the conversation it was said in.
 *
 * The store collapses what is identical and judges nothing. Whether two differently worded
 * sentences mean the same obligation is not a question SQL can answer, so it belongs to the
 * collapse — which applies what it decides through [CollapseRepository] and not through this port.
 */
interface CommitmentRepository {

    /**
     * Write [commitments], keeping the identity any of them already has.
     *
     * The same rule as contacts and for the same reason: the caller cannot know which are new, so
     * it offers an identifier for each and this discards the ones that would renumber an entity.
     * Everything else is replaced, because a re-derivation under a better prompt is a better
     * reading of the same evidence — and the evidence accumulates rather than being replaced, since
     * a span that stated this once states it still.
     *
     * **Answers with what it did to the rows, and that is this port's half of a contract.** The
     * caller offered an identifier per commitment precisely because it cannot know which are new, so
     * it cannot count its own inserts either; an implementation that returned the size of its
     * argument would hand back the one figure already in the caller's hand. [Stored.superseded] is
     * always zero here: one claim replacing another is the collapse's doing and goes through
     * [CollapseRepository].
     */
    suspend fun upsert(commitments: List<Commitment>): Stored

    /** The commitments any of these spans evidences; absent ones are absent. */
    suspend fun of(evidence: List<Evidence>): List<Commitment>
}
