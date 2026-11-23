package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.time.Instant

/**
 * Something happening, as the system holds it, at the time the message gave where it gave one.
 *
 * **Weak evidence, like a commitment, and weak in a different way.** A commitment is wrong by
 * being hedged or retracted; an event is wrong mostly by being irrelevant — a date mentioned in
 * passing reads exactly like one that was agreed. So this is kept with its evidence and its hedge
 * marker too, and nothing presents one as a fixture.
 *
 * Identity is what it says within the conversation it was said in, as with [Commitment] and for the
 * same reasons: one occasion mentioned twice is one event carrying two pointers, and two mentioned
 * in one message are two events.
 *
 * Its own type rather than a flavour of commitment, because what will be asked of the two differs.
 * An event is answered by when it is and who is in it; a commitment by whether it was met and by
 * whom. Neither question is the other's, and the one column they would share — a date — means
 * something different in each.
 */
data class Event(
    override val id: EntityId,
    /** The claim, restated to stand on its own. */
    override val claim: String,
    /** The conversation it was mentioned in, which scopes the claim this is keyed by. */
    override val conversation: ExternalId,
    /** Every span that mentions it, which is one until the same occasion is read again. */
    override val evidence: Set<Evidence>,
    val hedged: Boolean,
    /**
     * When it happens, where the message stated a time.
     *
     * Resolved against the message it was stated in rather than against now, so a date read out of
     * a 2021 message is a date in 2021 — and earlier than that message where the thing had already
     * happened when it was mentioned, which is the ordinary way a past occasion enters the corpus.
     *
     * **Absent in three cases, not one**: no time was stated, the value could not be read, or it
     * was the evidencing message's own instant and so says when the thing was said rather than when
     * it happens. An event with no moment is weaker than a dated one — it cannot be placed in any
     * window — and is still better recorded than guessed at, so it is read by the one event query
     * that asks for no window.
     */
    val occursAt: Instant? = null,
    /** The contact behind the author of the evidencing message — who mentioned it. */
    val statedBy: EntityId? = null,
) : Finding {
    init {
        require(claim.isNotBlank()) { "An event with no claim records nothing." }
        require(evidence.isNotEmpty()) {
            "An event with no evidence is a claim nothing in the corpus mentions, and its " +
                "citation would name nothing a reader can look up."
        }
    }
}

/** Events, by what each says within the conversation it was said in. The same rules as [CommitmentRepository]. */
interface EventRepository {

    /**
     * Write [events], keeping the identity any of them already has.
     *
     * Answers with what it did to the rows, under the rule [CommitmentRepository.upsert] states.
     */
    suspend fun upsert(events: List<Event>): Stored

    /** The events any of these spans evidences; absent ones are absent. */
    suspend fun of(evidence: List<Evidence>): List<Event>
}
