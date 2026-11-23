package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.time.Instant

/**
 * Something a model read out of a window, before anything has decided it is real.
 *
 * **A proposal names no entity, and that is the one rule it has.** It carries its evidence, a
 * hedge marker and a reference to a *record*; it cannot say which contact it is about, because
 * naming identity is the crossing [Reconciler] exists to be the only holder of. An extractor that
 * could mint an identifier would be a second place identity is assigned, and the first wrong merge
 * would be untraceable.
 *
 * Nothing here is stored. Resolution turns these into entities and statements, which is where
 * duplicates collapse, contradictions close a window, and the record in [about] becomes a contact.
 */
data class Proposal(
    val kind: ProposalKind,
    /**
     * The claim, rewritten to stand on its own.
     *
     * The second stage's whole output. "I'll send it tomorrow" is useless detached from its
     * conversation and from when it was said, so what is kept is the rewrite that survives being
     * read on its own — which is also what makes a statement's value comparable to another's.
     *
     * **How far that holds depends on what the corpus names.** The restating stage can name a party
     * only where the window gives it a label, which is where a person record carries a usable name.
     * For an author the corpus names nothing, the claim says what was undertaken without saying who
     * by — not self-contained, and supplying it is the answer path's job rather than this stage's.
     * The party travels on [about] as a record identifier either way.
     */
    val claim: String,
    /**
     * The conversation the window read, which is what scopes the claim a finding is keyed by.
     *
     * Carried from the window rather than read back from the record, for the reason [evidence]'s
     * source time is: a collapse that had to fetch the message to find out which conversation it
     * belonged to would read the corpus once per candidate.
     */
    val conversation: ExternalId,
    val evidence: Evidence,
    /**
     * Whether this must not be read as settled.
     *
     * Carried rather than resolved, because a hedged commitment is still worth recording and is
     * not worth presenting the same way. This is the field that stops "I might be able to look at
     * it next week" being shown as a promise.
     */
    val hedged: Boolean,
    /**
     * The due date of a commitment or the moment of an event, where one was read and kept.
     *
     * Absolute, and resolved against the evidencing message's own instant rather than against
     * now: "next Friday" in a message from 2021 is a date in 2021. A backfill reads old history
     * long after the fact, so resolving against the clock would date every old promise to the week
     * the backfill ran.
     *
     * Null where no date was stated, where the value could not be read, and where it was refused
     * against the message it came out of — see `Restatement.DateRefusal`. Always null for an
     * attribute, which is keyed by the period its evidence opens rather than by a date of its own.
     */
    val dated: Instant? = null,
    /**
     * The person record this is about — the author of the evidencing message, always.
     *
     * A record identifier, never an entity one. It is filled in from the window rather than by the
     * model, which is deliberate: asking a model to name a participant is asking it to assign
     * identity, and the author of the message a promise was made in is already known for certain.
     *
     * **What that can and cannot say.** For a commitment or an event it is who *stated* the thing,
     * which is the question those two kinds ask — a promise relayed second-hand is the reporter's
     * statement, and the hedge marker is what says so. For an attribute it is the *subject*, so the
     * fact has to be one the author stated about themselves: anything else would be a fact asserted
     * of whoever happened to mention it. [Extractor] is where that is enforced, by comparing the
     * subject the model named against the author's own label, and an attribute that fails it is
     * dropped and counted rather than arriving here. What that comparison cannot see is a fact
     * misattributed to the author by the model, which passes it and arrives like any other.
     */
    val about: ExternalId? = null,
    /**
     * What an attribute asserts about its subject, and nothing for the other two kinds.
     *
     * An attribute is the one kind that becomes a statement, and a statement is keyed by its
     * predicate — so a reading with no predicate cannot be stored under anything. A commitment or
     * an event is an entity in its own right and asserts nothing *about* a subject, which is why
     * carrying one here would be a field with no meaning.
     */
    val predicate: Predicate? = null,
    /**
     * The person record a commitment obliges, where the window named one.
     *
     * A record identifier, never an entity one, exactly as [about] is — and the one record
     * reference here the model has a say in. It does not name the party: it echoes back one of the
     * window's own labels, and the record behind that label is looked up against the window's
     * authors. So nothing is minted here either, and a label no line carries resolves to null
     * rather than to a party the window never showed.
     *
     * **This is the half of direction [about] cannot carry.** That field is who *stated* the
     * thing, which for a commitment is frequently not who owes it: an obligation one participant
     * states about another is a commitment, and a direction read off the author would be
     * confidently wrong in exactly those cases.
     *
     * Null where the window labelled nobody the claim obliges, and always null for an event and an
     * attribute, which oblige nobody.
     */
    val obliged: ExternalId? = null,
) {
    init {
        require(claim.isNotBlank()) { "A proposal with no claim proposes nothing." }
        require((kind == ProposalKind.ATTRIBUTE) == (predicate != null)) {
            "An attribute is the kind that needs a predicate, and the only one that may carry " +
                "it: a statement is keyed by its predicate, and a commitment asserts nothing " +
                "about a subject for a predicate to name."
        }
        require(kind == ProposalKind.COMMITMENT || obliged == null) {
            "A commitment is the only kind that obliges a party: an event is something that " +
                "happens and an attribute is a fact about its subject, so a party carried for " +
                "either would be a field with no meaning that a reader of a row could not tell " +
                "from a direction the corpus stated."
        }
    }
}

/**
 * What kind of thing was read.
 *
 * The three the extractor classifies. They fail differently, which is why they are not one kind:
 * a commitment is a speech act, frequently hedged and sometimes retracted; an event is a date and
 * its participants, and is wrong mostly by being irrelevant; an attribute is a fact that was true
 * for a while and then quietly was not.
 */
enum class ProposalKind {
    COMMITMENT,

    EVENT,

    /**
     * Something said to be true of a contact or a client — where they work, what they charge.
     *
     * The kind that becomes a statement rather than an entity, which is why it is the only one
     * with a validity window: a fact of this sort stops being true without anything announcing
     * it, and the window is how "what was true when we last spoke" stays answerable.
     */
    ATTRIBUTE,
}
