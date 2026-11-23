package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Builds the prompt from whatever a shape retrieved.
 *
 * **Shared and parameterised rather than written once per shape, because assembly is machinery.**
 * What differs between shapes is the standing instruction and what they retrieved; where the cache
 * breakpoint falls, how a span is labelled so a citation can be checked, and how the losing
 * subjects are presented are the same questions with the same answer every time. Five copies would
 * be five chances for one of them to label a span differently and silently break grounding.
 *
 * **The grouping read is here rather than in each shape's retrieval for the same reason.** A shape
 * that forgot it would answer from material whose bodies of work are merged, which reads as a
 * perfectly good answer: every citation resolves, grounding passes, and what is wrong with it is
 * that one body of work's agreed order is presented as everybody's. Reaching a port from assembly
 * is what the stage contract allows and the price of the one arrangement no shape can get wrong.
 *
 * @param instruction the shape's own standing instructions. Kept byte-stable: it is interpolated
 *   into nothing, and the retrieved material goes after the breakpoint, which is the precondition
 *   for the prefix being billed at read rates after the first call.
 * @param grouping which body of work each retrieved record belongs to, for the sectioning the
 *   answer is grouped by.
 * @param sections whether this shape's answer is sectioned at all. True for the three that report
 *   findings about the corpus, and false for the two that do not: a drafted message is one message
 *   to one counterpart, and a research answer is about the open world, so neither is improved by
 *   being divided along a line its question did not have. It also decides whether the format forbids
 *   a heading outright — see [Reply.format]. The default is the reporting case, because the failure
 *   it prevents — bodies of work silently merged — is the one nothing downstream can detect, while a
 *   shape sectioned that should not be is obvious the moment anybody reads one answer.
 * @param asserts whether this shape's answer is the assertions it cites, which decides whether the
 *   format tells the model that a statement about the material is one of them. Passed from
 *   [Shape.assertsFindings] rather than restated, as [variants] is, so the declaration the runner
 *   enforces and the instruction the model is given cannot disagree. Defaulted to the composing
 *   case, which is the one that needs no sentence of its own.
 * @param zone the correspondents' own, which is what a block's heading time is printed in so that
 *   [Reply.format]'s rule about relative references can be obeyed at all — and, since every time a
 *   reply prints follows it, **what a commitment's standing is decided on as well.** Setting this
 *   moves an item between the overdue, today and ahead groups and changes the day count beside it;
 *   it is the sort key and not only a presentation choice, which is `Material.standing`'s to
 *   explain. **Defaulted to UTC, and the default is the same instant in the same zone it was always
 *   shown in** — printed differently, which [Moment] explains, so a deployment that sets nothing
 *   still dates its material by UTC and nothing it shows the model moves in time. What the default
 *   no longer is, is neutral: unset, the standings are decided on the UTC day too, which is a
 *   choice about the record rather than an absence of one. It is a deployment's to set
 *   because it is a fact about whose archive this is and the platform does not report it: an
 *   instant carries no zone, and the only other route — reading it off the hours people happen to
 *   write in — was measured against this build's own corpus and pins no single zone.
 * @param clock what the present moment is read from, for the shapes that state it to the model, or
 *   null for the ones that do not. **Absent rather than defaulted to something, which is the one
 *   thing that makes this unlike [zone]**: a zone has a right answer when nothing is set and the
 *   material is dated either way, where a clock omitted means a shape says nothing about the
 *   present — the line is not written, rather than written wrongly. A shape whose instruction never
 *   mentions today has no use for it, and a fact in front of a model with nothing telling it what
 *   the fact is for is the shape of thing that gets rendered into an answer. The cost is the one a
 *   prior pass named of defaulting `zone`: a sixth shape that ought to state the present gets
 *   silence rather than a compilation error, and nothing but this note says so. A clock and not an
 *   instant, because the registry is built once at startup and an instant captured there would be
 *   the moment the process started for as long as it ran. Read in [prompt] and never in the system
 *   block — the present is true of this request and not the next one, which is the ground
 *   [Reply.sectioning] is asked for per request on.
 */
// A parameter per thing assembly needs, which is the same call `Material.of` makes and for the
// same reason: the shape's instruction, its variant range, the grouping port, and then the
// declarations a shape makes about its own answer — whether it sections, whether its answer is its
// assertions, which zone its material is dated in, and whether it states the present. Collapsing
// the last pair into a time object would hide which shape asked for what behind a type, and the
// asymmetry between them — one defaulted to a value, one to nothing at all — is what the notes
// above are for.
@Suppress("LongParameterList")
class Assemble(
    private val instruction: String,
    private val variants: IntRange,
    private val grouping: Grouping,
    private val sections: Boolean = true,
    private val asserts: Boolean = false,
    private val zone: ZoneId = ZoneOffset.UTC,
    private val clock: Clock? = null,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        // **Folded here as well as by the shape that retrieved, because the numbering is what makes
        // it load-bearing.** Every shape already folds its queries' results together, and the gate
        // compares against a set — so a span arriving twice has always been one piece of evidence.
        // Offered two numbers it would become two: the model cites both and the reader is shown the
        // same finding twice. The runner does not trust shapes on this for the gate's purposes
        // either, which is why `retrievedIds` is a set.
        //
        // **A rail rather than a repair: no shape hands this an unfolded list.** All five fold first,
        // so for every shape that exists this is identity and the prompt is unchanged. What it decides
        // is what a shape that forgot would get.
        //
        // **`gathered` rather than `distinct`, because this cannot tell the two collisions apart and
        // neither fold is right for both.** A record and something inferred from it share an identifier
        // and carry different texts, where `distinct` drops one — the model is shown a turn without the
        // commitment read out of it, under a number standing for both. One record reached twice carries
        // the same text almost always, and the live copy first when it does not, where `gathered` joins
        // a stale body to the current one. Joining is chosen because it is the visible failure: a
        // doubled body is in front of the model, where a dropped inference is in front of nobody.
        val material = Material.gathered(exchange.retrieved)
        val offer = Offer(material.map { it.id })
        val keys = keys(material)
        return Progress.Carried(
            exchange.copy(
                assembled = Assembled(
                    system = "$instruction\n\n${Reply.format(variants, sections, asserts)}",
                    prompt = prompt(exchange, material, offer, keys),
                    offer = offer,
                ),
            ),
        )
    }

    /**
     * The body of work behind each retrieved record, or nothing where sectioning does not apply.
     *
     * **Read once per request and only where it can change the answer.** Material that all belongs
     * to one body of work has nothing to be sectioned by, so a label on every block would be tokens
     * spent on a word the answer cannot use — and a question about a single subject is then assembled
     * exactly as it was before any of this existed, which is what keeps the common case honest rather
     * than merely cheap.
     *
     * **A record with no body of work behind it is not a key.** Absence is ordinary — the open web
     * was never in the corpus — and counting it would section an answer that spans one body of work
     * and some unplaceable material, where there is no second heading to write.
     *
     * The labels do not have to survive the retry, which is why nothing carries them: the retry
     * resumes at generation and the prompt it re-sends is the one assembled here.
     *
     * Read off the folded material rather than off the exchange, so that what is labelled and what is
     * numbered are the same list. Folding cannot change the answer — records are deduplicated here
     * either way — but two derivations of one thing agree only until somebody changes one.
     */
    private suspend fun keys(material: List<Span>): Map<ExternalId, GroupingKey> {
        if (!sections || material.isEmpty()) return emptyMap()
        val found = grouping.of(material.map { it.id.record }.distinct())
        return if (found.values.distinct().size > 1) found else emptyMap()
    }

    /**
     * What this request is about, what was asked, and the material to answer from.
     *
     * The present where a shape states it, then the subject and the losing alternatives, and the
     * material last, so that the longest part of the prompt is also the part nothing reads back —
     * a model asked to cite its material does better with the question in front of it than buried
     * under the evidence. The present leads because everything under it is read against it.
     *
     * [material] and [offer] are passed in rather than derived here, because they are the two
     * halves of one assignment: a label is a position in the list this walks, so deriving either
     * one separately would make them agree only for as long as nobody sorts one of them.
     */
    private fun prompt(
        exchange: Exchange,
        material: List<Span>,
        offer: Offer,
        keys: Map<ExternalId, GroupingKey>,
    ): String = buildString {
        // The present, where the shape asked for it, and first because what follows is read
        // against it. The weekday is printed for the reason a block's heading prints one: it is
        // the operand, and inferring it from a date is where an archive read went a day wrong.
        //
        // **Through [written], because there is one calendar and so nothing left to distinguish
        // the two.** What a reply compares this against is the standing `Material` computed —
        // *today*, *now past*, *ahead* — and the day count printed beside it, and both of those
        // are reckoned on the correspondents' calendar, which is also what the headings take. Said
        // here in UTC instead, this would disagree with them for the hours where the two calendars
        // part, and the instruction that reads the printed date as today would call a commitment
        // the material marks as ahead due today.
        clock?.let { appendLine("Now: ${written(it.instant())}") }
        exchange.subject?.let { subject ->
            appendLine("Subject: ${label(subject.focus)}")
            // **Which way the relation runs depends on the subject, and saying it the wrong way
            // round is a false statement about the material.** For every other subject the context
            // is what it sits inside — a person's body of work, a topic's forum. A body of work's
            // context is the threads it is *made of*, and it is shown under one of their labels,
            // so *within* would tell the model this subject sits inside something carrying its own
            // name and invite an answer about that thread alone.
            val relation = if (subject.focus.kind == SubjectKind.BODY_OF_WORK) STRAND else WITHIN
            subject.context.forEach { appendLine("$relation: ${label(it)}") }
        }
        if (exchange.alternatives.isNotEmpty()) {
            // Named so the answer can say what it was not about. They are labels and nothing was
            // retrieved for them, which the model is told outright — otherwise it answers about
            // one of them from material that belongs to the subject.
            appendLine()
            appendLine("Other readings of the name, which nothing was retrieved for:")
            exchange.alternatives.forEach { appendLine("- ${label(it)}") }
        }
        appendLine()
        appendLine("Asked:")
        appendLine(exchange.request.text)
        if (keys.isNotEmpty()) {
            // Ahead of the material, because it is what the labels below are for. In the prompt
            // rather than in the system block for a harder reason: it is true of this request and
            // not of the next one, and interpolating it into the prefix would move the cache
            // breakpoint's contents on every call.
            appendLine()
            appendLine(Reply.sectioning())
        }
        if (material.isNotEmpty()) {
            appendLine()
            appendLine("Material:")
            material.forEachIndexed { position, span ->
                append(material(offer.label(position), span, keys[span.id.record]))
            }
        }
    }

    /** A candidate as the model sees it: what it is called, and what kind of thing it is. */
    private fun label(candidate: Candidate): String =
        "${candidate.name ?: UNNAMED} (${candidate.kind.name})"

    /**
     * One span under [label], which is the number a claim about it has to carry, and the body of
     * work it is from.
     *
     * The label leads the block rather than trailing it, because a model copying one back has to
     * find it before it has finished reading the text. The body of work rides on the same line
     * rather than on one of its own: it is two or three words, and a line per piece of material
     * would be a line per piece of material.
     *
     * **The span's own identifier is not here at all.** It is what the grounding check compares and
     * what a fault report names, and both of those read it off the retrieved set rather than out of
     * the reply — so printing it would be offering the model a second and longer thing to copy, for
     * no reader of the prompt that needs it.
     *
     * **The material stays in the order the shape offered it**, grouped by nothing. A shape puts
     * its most relevant query first, and one may re-order what a read handed back before offering
     * it, so gathering the blocks under their labels here would quietly demote whatever the shape
     * thought mattered most — and the label is on every block, which is what sectioning needs. The
     * numbering follows that order for the same reason: it is the order the model reads, so the
     * first thing offered is the first thing numbered.
     */
    private fun material(label: String, span: Span, key: GroupingKey?): String = buildString {
        appendLine()
        append("$label ${written(span.sourceTime)}")
        key?.let { append(" ${belonging(it)}") }
        appendLine()
        appendLine(span.text)
    }

    /**
     * When a piece of material was written, as the correspondents themselves would have read a
     * clock at that moment.
     *
     * **Local rather than the instant, because the only use the model has for this is working out
     * a relative reference, and that arithmetic is wrong in UTC.** Where the zone is ahead of UTC
     * the two disagree for the hours just after local midnight: a message written at half past two
     * in the morning still falls on the previous UTC day, so *tomorrow* in it works out a day
     * early — silently, and in the reader's favour, which is the direction [Reply.format]'s rule
     * exists to stop. Behind UTC the window is the late evening and the error runs the other way.
     * How much of a corpus sits in it is a property of that corpus, read off the source times of
     * the messages carrying such a word rather than recorded here.
     *
     * **Every time a reply prints is said this way, and that is [Moment]'s to say rather than this
     * function's.** [Material] prints a closing turn's time into a block's text, counts how long
     * ago a commitment was last stated, prints a commitment's due date and an event's moment, and
     * counts how far past a deadline stands; the present moment above is printed from here as
     * well. They all come from one calendar, so a reader comparing any two of them finds one
     * answer — which is what a date in a block and a figure beside it being on different calendars
     * cost. What matters here is that the rule sends the model to the heading, which is the time it
     * is right to work *tomorrow* out against.
     *
     * The zone is a deployment's to set — see [zone]. Where it is unset the offset reads `Z`: the
     * same instant in the same zone the heading always carried.
     */
    private fun written(at: Instant): String = Moment.written(at, zone)

    /**
     * Where a piece of material belongs, as the model sees it.
     *
     * The two cases are spelled out rather than rendered as one label, because they are not the same
     * claim: naming a body of work says the material belongs to it, and naming a person says nothing
     * does. A prompt that called both a body of work would be asserting the second into the first.
     */
    private fun belonging(key: GroupingKey): String = when (key) {
        is GroupingKey.OfBodyOfWork -> "(Body of work: ${key.label})"
        is GroupingKey.OfPerson -> "(Person: ${key.label})"
    }

    private companion object {
        /**
         * What a candidate with no name is called in the prompt.
         *
         * A record may carry no label and still be the best match, so this is an ordinary case.
         * It is not display text: nothing renders it to a person, and the interface never sees it.
         */
        const val UNNAMED = "unnamed"

        /**
         * What a context line is headed by, which says how the subject and the line are related.
         *
         * Two words rather than one, because the relation itself differs — see the call site.
         * Neither is display text: both reach a model and no interface sees either.
         */
        const val WITHIN = "Within"
        const val STRAND = "Strand"
    }
}

/**
 * The one model call, and the reading of what came back.
 *
 * **Shared, parameterised, and the only place on the answer path that spends the model on prose.**
 * A shape chooses how its call is sized and whether it may search the open web; everything else —
 * sizing the call against what the request has left, reading the stop reason before the text,
 * turning a reply into variants, charging what it cost — is the same everywhere and is machinery.
 *
 * @param sizing the effort this shape wants and the most it wants for one call. The actual ceiling
 *   is that or what the request has left, whichever is smaller: the shape's number is about the
 *   answer it wants, and the budget's is about what the request may still spend, and the smaller of
 *   the two is the only one that is safe to pass on. The per-request budget still bounds a turn
 *   that pauses — each resumption is charged, and the runner stops the request once the ceiling is
 *   reached — which is what keeps a shape's own ceiling from being the whole of what it may cost.
 */
class Generate(
    private val model: ModelProvider,
    private val sizing: Sizing = Sizing.ANSWERING,
    private val openWeb: Boolean = false,
) : Stage {

    override val spendsModel: Boolean get() = true

    override suspend fun apply(exchange: Exchange): Progress {
        val assembled = exchange.assembled
            ?: error(
                "Generation was reached with nothing assembled. A shape's stage list puts " +
                    "assembly before generation, and one that does not has nothing to send.",
            )

        val outcome = model.respond(
            ModelRequest(
                system = assembled.system,
                prompt = assembled.prompt,
                maxTokens = minOf(sizing.ceiling, exchange.budget.remaining),
                effort = sizing.effort,
                openWeb = openWeb,
            ),
        )
        // Charged whatever happened. A refusal and a truncation are both billed for what they
        // read, so an outcome that produced nothing readable still moves the budget — and a retry
        // that did not see that spend would be the doubling the bound exists to prevent.
        val spent = exchange.spending(outcome.usage)

        // The stop reason is read before the text, which is what the sealed outcome makes
        // unavoidable. Three of the four cases produced nothing worth standing behind: a
        // truncation reads like a finished answer, a refusal carries no answer at all, and a pause
        // stopped mid-flight. All three settle as insufficient evidence rather than as errors,
        // because that is what the system can honestly say.
        val variants = when (outcome) {
            // Read against the offer the prompt was written from, which is why it travels on
            // `assembled` rather than being rebuilt here from what was retrieved. On the retry this
            // is the first attempt's offer, because the retry resumes from the exchange as it stood
            // before generation — renumbering the same material would make the second attempt read
            // its own numbers off a different assignment than the one it was shown.
            is ModelOutcome.Answered -> Reply.read(outcome.text, assembled.offer)
            is ModelOutcome.Truncated, is ModelOutcome.Refused, is ModelOutcome.Paused -> emptyList()
        }

        // **Nothing readable came back, and the outcome goes out with the answer that says so.** The
        // answer settled here is the same one an empty corpus produces and that is deliberate, so by
        // itself it loses the two facts worth having: that the model was called at all, and what it
        // did. Both travel on the fault, which is also what lets the runner tell a reply the
        // provider called complete from one that carried nothing readable — it re-rolls the first
        // and answers the request in its own words where the second call comes back the same way,
        // and keeps this answer for the rest. Without the outcome, a request that paid for a reply
        // the format reader rejected would report exactly what one that never called the model
        // reports, with a token count in the adapter's log as the only sign anything had happened.
        return if (variants.isEmpty()) {
            Progress.Settled(
                answer = Answer.InsufficientEvidence(),
                exchange = spent,
                fault = Fault.Unreadable(outcome),
            )
        } else {
            Progress.Settled(answer = Answer.Composed(variants), exchange = spent)
        }
    }
}
