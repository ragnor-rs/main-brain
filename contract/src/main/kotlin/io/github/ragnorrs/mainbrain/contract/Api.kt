package io.github.ragnorrs.mainbrain.contract

import kotlinx.serialization.Serializable

/**
 * What the interface asks for.
 *
 * The three fields the backend can act on, and no more. [command] is separate from [text]
 * because an explicit command binds a pipeline directly while free text has to be classified,
 * and collapsing them would make a slash command indistinguishable from somebody typing the
 * same word. [subjectName] is already separated out by whichever interface took the request:
 * finding the name in a sentence is edge work, and the backend does not parse one.
 *
 * Nothing identifies the asker. The allowlist is enforced at the interface's own edge, and the
 * shared secret says which interface is calling — so a caller that reached here is already
 * authorised, and a field naming the person would be one the backend could not check.
 */
@Serializable
data class AnswerRequest(
    val text: String,
    val command: String? = null,
    val subjectName: String? = null,
)

/**
 * What came back.
 *
 * [outcome] is what to do. The other two sit out here rather than inside each outcome kind
 * because neither is the outcome's to carry: both are stamped on afterwards by the machinery,
 * and nesting them would make every kind repeat the same two fields.
 *
 * [freshness] is on every answer however it went — including the ones that found nothing, which
 * is where it matters most.
 *
 * [notUsed] is the subjects that matched and lost, and is **empty for every answer but a composed
 * one**: the others settled before anything was chosen, so there is no alternative that went
 * unused. An answer about the wrong subject is the failure that is invisible afterwards, so
 * naming the alternative is what makes it recoverable in one turn.
 */
@Serializable
data class AnswerResponse(
    val outcome: Outcome,
    val freshness: Freshness,
    val notUsed: List<SubjectRef> = emptyList(),
)

/**
 * How current the corpus was when this was answered.
 *
 * On every answer, including the ones that found nothing — *nothing was found* is the reply that
 * misleads hardest while ingestion is behind, and a question answered against a stale corpus
 * reads identically to one answered against an empty one without this.
 *
 * **Sent always and said selectively, which is the division this format holds everywhere.** This
 * is what was true of the corpus; whether a reader is told any of it is the interface's, and an
 * interface that tells them on a corpus that is current has reported that nothing is wrong, at the
 * cost of a sentence on every answer. So a caller is handed the facts rather than a judgement, and
 * a field being populated is not an instruction to render it.
 *
 * [completeThrough] is an instant in the form every source time in this build is written, and
 * null when nothing is known about what some source holds. [lagging] names the sources known to
 * be behind — the sources and not their work units, because a unit is an adapter's own name for
 * a piece of its work and means nothing to an interface.
 */
@Serializable
data class Freshness(
    val completeThrough: String? = null,
    val lagging: List<String> = emptyList(),
)

/**
 * An answer that is being worked on, and how to come back for it.
 *
 * **Why an answer is ever handed over in two parts.** One shape is a tool loop on the provider's
 * side — it searches, reads and searches again — so it runs for minutes where the others run for
 * seconds. An interface that waits inside a single request has to bound that wait, and any bound
 * wide enough for the loop is also how long a backend that has stopped answering may hold the
 * caller. Asking and collecting separately settles both: each hop is short, so a silent backend is
 * noticed quickly, and the caller's own bound on how long it will wait no longer holds a request
 * open while it does.
 *
 * [id] is opaque and is the backend's to mint. It names a piece of work in flight and nothing else
 * — not the asker, who is already established by the secret, and not the request, which the caller
 * still holds.
 */
@Serializable
data class AnswerAccepted(val id: String) {
    init {
        require(id.isNotBlank()) { "Accepted work is named, or nothing can collect it." }
    }
}

/**
 * Why a request got no answer.
 *
 * **A key, never a sentence.** An error body is read by a person like any other reply, so prose
 * here would be display text composed by the one process that has no catalogue and no locale —
 * which is the rule the whole wire format exists to keep. What went wrong in detail is in the
 * backend's log, where it is read by somebody who can act on it.
 */
@Serializable
data class Failure(val key: MessageKey)
