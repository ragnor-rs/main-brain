package io.github.ragnorrs.mainbrain.domain.entity

import java.util.UUID

/**
 * The internal identity of a business entity — one per human, per client, per commitment.
 *
 * **Assigned once and never changed.** A contact assembled from four person records keeps the
 * identifier it was first given, and merging two contacts renumbers neither: the merge is a link
 * naming one as canonical, so nothing that already referred to either has to be rewritten. This
 * is the property that lets anything downstream hold onto one of these.
 *
 * Minted here rather than by the store, because the store would then be the thing that assigns
 * identity. A sequence from the database reads as innocent and is not: it would mean a row
 * insert is what makes an entity exist, and identity would be assigned wherever a row is
 * written. Only the recogniser may create one, and a value it mints itself is the only way to
 * say so in a type.
 *
 * Unordered and carrying no information. Two identifiers minted a year apart compare as
 * unrelated, which is correct — the order they were created in says nothing about the humans.
 */
@JvmInline
value class EntityId(val value: UUID) : Comparable<EntityId> {

    /**
     * Ordered the way the store orders it, which is **not** [UUID.compareTo].
     *
     * Java compares a UUID as two signed longs; Postgres compares its sixteen bytes unsigned.
     * The two disagree for every pair that straddles the sign bit, which is about half of all
     * pairs. Anything that orders a pair here and has it checked there — and a pair stored with
     * its order constrained is exactly that — would be rejected on half its input, at random,
     * with nothing in the code to point at.
     *
     * So this is unsigned, like the store. Nothing else about the ordering matters: these carry
     * no information and are compared only to put a pair in a settled order.
     */
    override fun compareTo(other: EntityId): Int {
        val high = value.mostSignificantBits.toULong().compareTo(other.value.mostSignificantBits.toULong())
        return if (high != 0) {
            high
        } else {
            value.leastSignificantBits.toULong().compareTo(other.value.leastSignificantBits.toULong())
        }
    }

    override fun toString(): String = value.toString()

    companion object {
        /**
         * A new identity.
         *
         * Called from a recognition pass and from nowhere else: the identity stage mints contacts
         * and clients, the inference pass mints the commitments and events it reads. One producer
         * per entity kind, which is what makes a re-run unable to fork one — and no store and no
         * source adapter may call this at all, because a row insert is not what brings an entity
         * into being.
         */
        fun mint(): EntityId = EntityId(UUID.randomUUID())
    }
}
