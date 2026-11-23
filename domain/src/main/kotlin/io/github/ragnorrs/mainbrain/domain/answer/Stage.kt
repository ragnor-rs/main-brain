package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.index.Span

/**
 * One step of an answer pipeline.
 *
 * A pipeline is an ordered list of these and the runner owns the lifecycle, so a new answer
 * kind is a new stage list rather than new machinery. A stage takes the exchange as it stands
 * and either hands back a fuller one or settles the request outright — which is what lets a
 * shape stop early without the runner knowing why it wanted to.
 *
 * **A stage is pure in the sense that matters here: it schedules nothing and keeps nothing.**
 * It may reach a port — retrieval reads the index, generation calls the model — but it holds
 * no state between calls and decides nothing about when it runs. One instance serves every
 * request, concurrently, which is only true because everything a stage knows arrives in its
 * parameter.
 *
 * `suspend` because every stage that does real work does it over a network: retrieval and assembly
 * read the store, generation calls the model, and a plain function would block whichever thread
 * asked.
 */
interface Stage {

    /**
     * Whether running this stage spends the model budget.
     *
     * The runner reads it for two decisions it could not otherwise make without knowing what
     * the stages are. Empty retrieval has to short-circuit *before* any model call, and a
     * citation failure has to be retried by re-running generation rather than the whole
     * pipeline — both need the boundary between the cheap stages and the expensive ones, and
     * this is the only thing that can say where it is.
     *
     * Defaulted to `false` so that the cheap case is the one nobody has to remember. A stage
     * that calls the model and forgets to say so loses the short-circuit, which costs tokens;
     * the reverse costs a wasted re-run of something deterministic.
     */
    val spendsModel: Boolean get() = false

    /** Run against [exchange]. */
    suspend fun apply(exchange: Exchange): Progress
}

/**
 * What a stage did: carried the request forward, or finished it.
 *
 * Sealed so that a stage cannot settle a request by convention — returning an exchange that
 * some later stage is supposed to notice is final is exactly the arrangement where one
 * forgets. Finishing is a different return value, not a flag on the same one.
 */
sealed interface Progress {

    /** Carry on, with what this stage added. */
    data class Carried(val exchange: Exchange) : Progress

    /**
     * Stop here, with this answer, and the exchange it was answered from.
     *
     * The runner still validates it. A shape settling the request is saying what the answer
     * is, not that it is grounded — the grounding check is mechanical and belongs to the
     * machinery, or every shape would own a copy of it.
     *
     * **[exchange] is carried because the settling stage may be the one that retrieved.** The
     * agentic shape's retrieval *is* its model call, so it adds to the retrieved material and
     * settles in the same step. Checking its citations against the exchange as it stood
     * *entering* that stage would find every one of them ungrounded, retry, find the same, and
     * degrade to insufficient evidence for ever — a shape that can never answer, with nothing
     * in the failure to say why.
     *
     * @property fault what was wrong with what the stage produced, where the stage is the only
     *   thing that can say. Everything the runner checks it recomputes from the answer, so a stage
     *   has nothing to add about a composed one. What it has is the half the answer does not carry:
     *   a model call that came back and could not be read settles as insufficient evidence, which
     *   is byte-for-byte the answer an empty corpus produces — so the reply, the stop reason and
     *   the fact that the call happened at all are gone by the time the runner sees it. What the
     *   runner does with that report is its own: a reply the provider called complete is re-rolled
     *   and, if the request ends there, answered in its own words, while the outcomes carrying
     *   nothing readable keep the answer an empty corpus gets. Either way the stage settles as
     *   insufficient evidence and reports — see the rail below, which refuses it the other answer.
     *   `null` on every stage that settled with something it stands behind.
     */
    data class Settled(val answer: Answer, val exchange: Exchange, val fault: Fault? = null) : Progress {
        init {
            // **A rail rather than a precedence rule, because precedence here has been invisible
            // before.** The runner takes a stage's report as given, so a stage that reported one
            // *and* settled with a composed answer would have its word silently replace the variant
            // count and the grounding check — an ungrounded answer shipped unrepaired, with the
            // report reading as though the machinery had agreed. Which clause wins when an answer
            // fails two of them is already pinned by a test for exactly that reason, and the honest
            // fix is to make the overlap unreachable instead of ordering it: a stage reports only
            // what nothing here can recompute, which is why it produced no answer at all.
            require(fault == null || answer !is Answer.Composed) {
                "A stage reports a fault only on an answer the runner cannot check for itself. " +
                    "A composed answer is checked — its variant count, whether a shape whose " +
                    "answer is its assertions made one, whether each alternative a shape offers " +
                    "carries text to send, and its citations — so a fault reported alongside one " +
                    "would decide the attempt in place of those checks."
            }
            // **And never the answer a request is given up as, for the same reason in the other
            // direction.** [Answer.Unreadable] asserts that a re-roll was spent and that the request
            // still ended on a complete reply nobody could read — a fact about the whole request,
            // where a stage sees one attempt and cannot know whether a second is still to come. It
            // does not say both attempts read that way: the first may have been rejected for
            // something perfectly readable. Settled with here it would reach a reader after a single
            // call, with no re-roll attempted and no fault line behind it: the one answer saying
            // everything was tried, claimable by a stage that tried once.
            require(answer !is Answer.Unreadable) {
                "A stage cannot settle with the answer a request is given up as. It says a re-roll " +
                    "was spent and the request still ended on a complete reply that was read and " +
                    "rejected, which only the runner can know. A reply this stage could not read " +
                    "is reported as a fault beside an insufficient-evidence answer, and what the " +
                    "request says is the runner's."
            }
        }
    }
}

/**
 * What arrived, before any of it has been resolved.
 *
 * [command] and [text] are separate because an explicit command binds a pipeline directly
 * while free text has to be classified, and collapsing them would make a slash command
 * indistinguishable from somebody typing the same word.
 *
 * [subjectName] is the name to resolve a subject from, already separated from the rest of the
 * ask by whichever interface took it. The backend does not parse a sentence to find it: that
 * is the bot's edge work, and a request naming no subject leaves this null.
 *
 * **Absent or a name, never blank, and the bound is here rather than in whoever reads it.** A
 * blank name is the absence of one wearing a name's clothes, and read as present it shadows the
 * name the routing call had already read out of the text — which resolves to the inbox and
 * answers a question about somebody across the whole corpus. The interface shipped here sends the
 * field absent where there is no argument, so this is about every other caller: one expression in
 * one consumer would be the arrangement where three contracts describe a bound that a single
 * caller provides.
 */
data class Request(
    val text: String,
    val command: String? = null,
    val subjectName: String? = null,
) {
    init {
        require(text.isNotBlank()) { "A request carries something that was asked." }
        require(subjectName == null || subjectName.isNotBlank()) {
            "A blank subject name is the absence of one wearing a name's clothes. Which absence " +
                "it is decides whether a shape answers for the inbox or asks who was meant, so " +
                "it is said by leaving this out rather than by emptying it."
        }
    }
}

/**
 * Everything one request has accumulated, passed from stage to stage.
 *
 * A single carrier rather than a parameter per stage, because the stage list is the shape's to
 * choose: a stage cannot know what ran before it, so what it reads has to be in one place
 * whose shape does not depend on the order.
 *
 * Immutable, and every stage returns a copy. A mutable exchange shared across stages would
 * make a stage's effect on a later one invisible at the call site, which is the thing that
 * makes a pipeline hard to read.
 */
data class Exchange(
    val request: Request,
    /**
     * What the request is about, once resolved — null only for a shape that accepts no subject.
     *
     * Set by the runner before any stage runs, because every answerable shape starts from a
     * subject and resolving it is the one read that crosses the corpus and the entity layer at
     * once.
     */
    val subject: Subject? = null,
    /**
     * The subjects that matched and lost, as labels.
     *
     * Carried into generation and never retrieved for. The answer can then name the
     * alternative it did not use, which makes a wrong subject recoverable in one turn instead
     * of invisible.
     */
    val alternatives: List<Candidate> = emptyList(),
    /**
     * The corpus material this request will be answered from.
     *
     * Populated by the shape's own retrieval. The runner reads it to decide whether there is
     * anything to answer from at all, and to say when each cited span's material was written; the
     * citation check reads the span identifiers in it to decide whether a claim is grounded. All
     * of which is why it holds spans rather than their identifiers.
     */
    val retrieved: List<Span> = emptyList(),
    /** The prompt built from [retrieved], once a shape's assembly stage has built it. */
    val assembled: Assembled? = null,
    /**
     * What this request may still spend on the model.
     *
     * Set by the runner before any stage runs and carried through every copy, so that a stage
     * sizing a model call has the request's remaining budget rather than only its own idea of a
     * ceiling. A stage that spends reports it back through [spending], which is what makes the
     * bound hold across the retry.
     *
     * Defaulted so that a test or a stage constructing an exchange directly gets a working
     * budget rather than having to invent one. The runner overwrites it either way.
     */
    val budget: Budget = Budget(Budget.DEFAULT_CEILING),
) {

    /**
     * This exchange, with [usage] charged to the budget.
     *
     * The return value rather than a mutation, as everywhere else here: a stage that spent the
     * model hands back an exchange that says so, and one that forgets is visible as a budget
     * that did not move rather than as a counter somebody has to go and read.
     */
    fun spending(usage: TokenUsage): Exchange = copy(budget = budget.spending(usage))
}

/**
 * A request as it will be sent to the model.
 *
 * Two parts rather than one string, because the split is where a caching saving can come from:
 * the adapter puts the cache breakpoint at the end of [system], so keeping it byte-stable across
 * calls of the same kind is **the precondition for caching rather than a guarantee of it**.
 * Below the provider's minimum cacheable prefix a breakpoint is silently ignored, and tools
 * render ahead of the system prompt — so the shape that declares the web-search tool can never
 * share an entry with one that reads the corpus, however stable either prefix is.
 *
 * What the split does buy unconditionally is that interpolating a timestamp or an identifier
 * into [system] cannot happen by accident, since the retrieved material has somewhere else to
 * go. Moving the breakpoint's contents costs the saving rather than reducing it.
 */
data class Assembled(
    val system: String,
    val prompt: String,
    /**
     * The numbers [prompt] offered its material under, for reading the reply back.
     *
     * **Here rather than on the exchange, because it is part of what was sent.** A per-request
     * number means nothing without the assignment that made it, so the reply reader has to have the
     * same one the prompt was written from — and an exchange carrying the two separately could hold
     * a prompt labelled one way and an offer saying another. There is one nullable thing on the
     * path to the model rather than two, and whatever has the prompt has the offer.
     *
     * **Required rather than defaulted, because there is no safe value to leave it at.** Nothing
     * offered is the right answer for a prompt that carried no material and the wrong one for a
     * prompt that did: every claim in the reply then cites a number that was never given, no claim
     * survives the gate, and the request spends its retry to report that there was nothing to answer
     * from — two model calls to say what an empty corpus says for free. A caller that has to name
     * [Offer.NONE] is a caller that has decided.
     */
    val offer: Offer,
) {
    init {
        require(prompt.isNotBlank()) { "An assembled request carries a prompt." }
    }
}
