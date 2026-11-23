package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * A body of work, read from the structure of the conversations about it.
 *
 * An organisation where the taxonomy names clients and a project where it names projects. The
 * distinction does not reach identity — the label is read either way — but it does reach what an
 * answer may claim, because one body of work's agreed priorities are not another's.
 *
 * Identity by label rather than by matching, which is why this needs no scoring, no merge link
 * and no threshold. A conversation people share carries a name somebody chose, so the name is the
 * closest thing in the corpus to a statement of what the work is organised into. Reading it costs
 * nothing and cannot be wrong in the way a merge can.
 *
 * Stated as the one kind excluded rather than as the kinds admitted, which is also how the pass
 * is written: a kind added to the source taxonomy later should have to argue for its exclusion
 * rather than be left out by an enumeration nobody revisited.
 *
 * **A direct exchange carries no such name**, so no client is read from one. What a two-person
 * chat displays is the counterparty, rendered by the platform rather than assigned by anybody,
 * and it is already that person's contact name. The reader who names it means the person.
 *
 * **Not every named conversation is a body of work.** A thematic track carries a label exactly as
 * a client's thread does, and telling them apart means knowing what the names mean — a judgement,
 * and one that needs the model. So this is every named conversation people share, and narrowing it
 * is inference's to do rather than something to guess at here with a word list.
 *
 * [name] is the label as the source gives it. A taxonomy that lives in names cannot be recovered
 * by re-deriving if the name never entered the corpus, so nothing here normalises it away.
 */
data class Client(
    val id: EntityId,
    val conversation: ExternalId,
    val name: String,
) {
    init {
        require(name.isNotBlank()) {
            "A client is identified by the label its conversation carries, so a blank one is " +
                "not a client — it is a conversation nobody named."
        }
    }
}

/**
 * Clients, by the conversation each was read from.
 *
 * No merging here, and no links. Two conversations naming one body of work would be two clients,
 * and joining them is the same scored, reversible problem contacts have — but a named conversation
 * is created by a human who can see the existing ones, so duplicates are rare in a way duplicate
 * accounts are not. Whichever workstream finds them has the contact machinery to copy.
 *
 * It is also why one body of work spread over several conversations is several clients: a client is
 * keyed by the conversation it was read from, and nothing here states that two of them are one
 * thing. A body of work discussed only in a direct exchange has no client at all, because no
 * conversation names it.
 */
interface ClientRepository {

    /**
     * Write [clients], keeping the identity any of them already has.
     *
     * The same rule as contacts, for the same reason: the caller cannot know which are new, so
     * it offers an identifier for each and this discards the ones that would renumber an entity.
     */
    suspend fun upsert(clients: List<Client>)

    /** The clients read from these conversations; absent ones are absent. */
    suspend fun of(conversations: List<ExternalId>): List<Client>
}
