package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * Which body of work each piece of retrieved material belongs to.
 *
 * **Its own read rather than a field on a span, because a span is written by the indexing pass and
 * this is a question only the answer path asks.** Widening [io.github.ragnorrs.mainbrain.domain.index.Span]
 * would put a column on the one type the index writes, where nothing has a label to supply, and
 * every writer would then carry a field for a reader's benefit.
 *
 * **Keyed by the record rather than by the conversation, because that is what the answer path
 * holds.** A span's identifier names the record it was read out of and is qualified by the chat
 * rather than by the conversation — a source may let a message move between the topics inside one
 * chat — so the conversation cannot be recovered from it, and the material a shape retrieved
 * carries nothing else to group by. The same read therefore serves a message, an inferred
 * commitment and an inferred event alike: a derived thing is addressed by the span it was read out
 * of, so it groups with the turn it came from without this knowing anything about either.
 *
 * **A record whose body of work cannot be determined is absent, not defaulted.** Absent is the
 * ordinary case rather than a failure — material from the open web was never in the corpus at all,
 * and a direct exchange with somebody who appears nowhere else belongs to no body of work anybody
 * named. Standing one in for it would assert a hierarchy the corpus does not state, which is the
 * thing sectioning exists to stop.
 *
 * The contract, which is what an implementation has to hold to:
 *
 * - A conversation people share is its own body of work, read from the label it carries.
 * - A conversation carrying no label of its own takes its container's, because a forum's traffic
 *   hangs off the topics inside it and an unnamed one is part of the thing that is named.
 * - A one-to-one conversation takes the body of work of the person on the other side, where that
 *   person writes inside exactly one. Otherwise it is that person: somebody who works across
 *   several has no single one, and choosing between them states a hierarchy the corpus does not.
 *
 * **"Exactly one" counts bodies of work and not the conversations each was read from.** Writing
 * somewhere that has none counts as nothing rather than as one more, and an unnamed conversation
 * counts as the container it belongs to, by the rule above. Where a body of work keeps a thread per
 * strand of it, the strands carry labels built on one name and count once: counting the strands
 * would head a counterpart's material by their own name on the strength of how the work is filed,
 * which is a grouping lost to a label rather than to anything about what they work on.
 *
 * **Reduced by the leading name each label carries, which is a rule and not a judgement.** Telling
 * two labels of one body of work from two bodies of work is otherwise the model's to do from the
 * labels it is shown, and it still is for what a section is headed by; what an implementation needs
 * here is only enough to count, and a shared leading name is the half of that question that can be
 * settled without the scored matching this build refuses for the purpose. Labels agreeing on
 * nothing but their first characters are not joined — a name boundary is the unit.
 *
 * **And it counts per account rather than per human.** A person reached through a one-to-one is the
 * account on the other side of it; where two accounts have been recognised as one human, what each
 * writes is counted separately. The case that reaches is a one-to-one with somebody whose other
 * account works on something else, which is rare while one source is read and nothing cross-platform
 * is matched.
 */
interface Grouping {

    /** The body of work behind each of [records], where one could be determined. */
    suspend fun of(records: List<ExternalId>): Map<ExternalId, GroupingKey>
}

/**
 * What a section of an answer is headed by.
 *
 * **Two cases and no third, because they are the two admissible top-level keys.** A topic is never
 * one: an answer that groups by topic and merges the bodies of work inside each heading asserts
 * that one body of work's agreed priority order is the priority order, and every citation under
 * such a heading is individually sound — so grounding passes it and nothing deterministic can see
 * it.
 *
 * **The kind is carried rather than folded into the label**, because the two read differently to
 * whoever gets the answer. A heading naming a person says that this material belongs to no single
 * body of work, which is a fact about the corpus; presenting it as one would be the assertion this
 * type exists to avoid making.
 *
 * [label] is prompt text and not display text: nothing resolves it from a catalogue, because it is
 * read out of the corpus rather than written here — a body of work is named by whoever named the
 * conversation, and a person by whatever their profile says.
 */
sealed interface GroupingKey {

    /**
     * What the heading reads.
     *
     * Never blank, and both cases require it: a heading with nothing in it places material no better
     * than an absent label does, while costing a section the reader cannot use. An implementation
     * that has only a blank name has not determined a key and leaves the record out.
     */
    val label: String

    /** A body of work, named by the conversation it is organised in. */
    data class OfBodyOfWork(override val label: String) : GroupingKey {
        init {
            require(label.isNotBlank()) { "A body of work is its label, so a blank one names nothing." }
        }
    }

    /** The person a one-to-one conversation is with, where no single body of work is behind it. */
    data class OfPerson(override val label: String) : GroupingKey {
        init {
            require(label.isNotBlank()) { "A person heads a section by name, so a blank one heads nothing." }
        }
    }
}
