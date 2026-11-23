package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.ExternalId

/**
 * What a named conversation was judged to be.
 *
 * Two values and no third, because what the read filter does with a verdict is binary: a track is
 * withheld and anything else is offered. A hedge would have to be resolved to one of these
 * somewhere, and resolving it on read would put the judgement in the filter instead of in the pass.
 */
enum class Standing {

    /** An organisation or a project the work is for: a client, as identity read it. */
    BODY_OF_WORK,

    /**
     * A label that organises material rather than naming work for somebody.
     *
     * Referrals, partner programmes, banks, hiring, a shelf of resources. Each carries a name
     * exactly as a client's thread does, which is why nothing a label *is* can tell them apart.
     */
    THEMATIC_TRACK,
}

/**
 * One conversation's verdict, and what produced it.
 *
 * Keyed on the conversation rather than on the client for the reason the pass is: what is judged is
 * a conversation's name together with what is said under it, and the client read from that name is
 * one of the things the verdict governs rather than the thing it is about. It also lets a verdict
 * be recorded before identity has assigned the client, and survive identity re-deriving it.
 *
 * [version] is everything that would make two runs disagree about one conversation — the wording,
 * the model, the effort, and how much of the conversation was sampled — opaque and compared only
 * for equality. It is carried on the verdict rather than left to the watermark because the two
 * say different things: the mark says the conversation was looked at, and this says what looked at
 * it.
 */
data class Admission(
    val conversation: ExternalId,
    val standing: Standing,
    val version: String,
) {
    init {
        require(version.isNotBlank()) {
            "A verdict with no version cannot be told from one a better instruction has replaced."
        }
    }
}

/**
 * The verdicts the admission pass has reached.
 *
 * **There is no removal, and nothing here reads a verdict in order to apply it.** Filtering happens
 * in the store, where clients are read: a port handing rejections back for a caller to act on would
 * be a second place deciding what a verdict means, and the readers that matter are the statements
 * joining against the client table rather than callers of a port.
 *
 * So [of] is a read-back and not that second place — it answers what was decided about a
 * conversation, which is what makes a write assertable and what anyone diagnosing a withheld client
 * has to be able to ask. [ClientRepository.of] is the same shape beside it, for the same reason.
 */
interface AdmissionRepository {

    /**
     * Write [admissions], replacing whatever was decided about each conversation before.
     *
     * One verdict per conversation, so a re-judgement overwrites rather than accumulating. What
     * that gives up is a history of judgements, which nothing here reads; what it buys is that the
     * read filter never has to choose between two live verdicts about one conversation.
     *
     * **A stale verdict goes on being honoured until it is replaced**, which is why this overwrites
     * instead of the filter preferring the current version. A reworded instruction leaves every
     * verdict naming the old one, and a filter that ignored those would re-admit every rejected
     * track the moment the wording moved — until the pass caught up, answers would be about
     * material a judgement had already excluded. The version's job is to decide what is owed a
     * re-judgement, not what is believed meanwhile.
     */
    suspend fun record(admissions: List<Admission>)

    /** The verdicts about these conversations; unjudged ones are absent, which reads as admitted. */
    suspend fun of(conversations: List<ExternalId>): List<Admission>
}
