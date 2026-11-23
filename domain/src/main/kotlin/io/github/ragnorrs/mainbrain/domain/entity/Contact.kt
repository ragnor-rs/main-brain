package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * A human, as the system holds them — one per person record, before any merging.
 *
 * One per *record* and not one per human, which reads backwards until the merge model is in
 * view. A contact comes into being the moment a person record is recognised, with no judgement
 * involved and nothing to get wrong. Deciding that two of them are one human is a separate,
 * scored, reversible step that writes a [MergeLink] rather than fusing the two rows. So the
 * count of contacts is the count of accounts seen, and the count of *humans* is the count of
 * link heads — which is what retrieval resolves to.
 *
 * The alternative — minting a contact only once enough evidence has accumulated — would make
 * every account unreachable until it was matched, and there is nothing to match a person
 * against on the pass that first sees them.
 *
 * [displayName] is carried rather than read back through the record because it is what the name
 * signal compares and what anything rendering a contact shows. It is whatever a human typed into
 * a profile field, so it is not identity and nothing here treats it as such.
 */
data class Contact(
    val id: EntityId,
    val record: ExternalId,
    val displayName: String?,
)

/**
 * One human, as the links currently assemble them: a head and every contact resolving to it.
 *
 * What [ContactRepository.clusters] returns, and the shape a constraint has to be checked over.
 * The members carry their records because the hard negative is read from the corpus — two person
 * records sharing a conversation — so a member without its record could not be checked at all.
 *
 * [head] is itself a member. A contact nothing has merged is a cluster of one.
 */
data class Cluster(val head: EntityId, val members: List<Contact>) {
    init {
        require(members.any { it.id == head }) {
            "A cluster's head is one of its members. One missing from the membership would be a " +
                "human whose own canonical record is not checked against any constraint."
        }
    }
}
