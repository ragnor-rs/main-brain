package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.TokenUsage

/**
 * One request shape: what it accepts, what it promises, and the stages that answer it.
 *
 * A shape is a named pipeline with its own retrieval, its own prompt and its own evaluation
 * cases, so a new answer kind is a new implementation of this rather than a cleverer prompt.
 * Everything the runner needs to drive one is declared here, which is what lets the machinery
 * know no shape.
 */
interface Shape {

    /**
     * What this shape is called, and the command that binds it.
     *
     * One value for both rather than an identifier and a separate command string: two would be
     * able to disagree, and the disagreement reads as a command that silently answers through
     * the wrong pipeline.
     */
    val id: ShapeId

    /**
     * One line saying what this shape answers.
     *
     * **Declared beside [id] because the identifier cannot carry this on its own.** A command names
     * a shape and needs nothing further — whoever typed it had the list. A sentence has to be
     * placed against what the shapes *do*, and a classifier offered bare identifiers has to infer
     * what each covers from how its name reads. So this is what makes registering a shape enough to
     * be *routed* to, which is the property [id] already has for the commanded path.
     *
     * **A model instruction, not display text.** It is interpolated into the routing prompt and
     * rendered to nobody, so it is not a message key and has no locale. See [Classifier.Candidate].
     *
     * **One line, because the prompt lists one candidate per line.** A description carrying a line
     * break would not merely wrap: it would be read as a further candidate.
     *
     * Declared by the fallback too, which is never a candidate and whose line therefore goes
     * genuinely unread — unlike its empty [accepts], which the runner reads on every request to
     * decide whether to resolve a subject at all. Asked of every shape all the same, because an
     * opt-out is what lets one that genuinely needs a line arrive without it. What it *says* is
     * checked only of the shapes that are candidates, which is where a bad line costs an answer —
     * see [ShapeRegistry].
     */
    val description: String

    /**
     * The subject kinds this shape can answer for.
     *
     * Empty means it needs no subject at all: the shape is scoped by its own text, and whatever it
     * looks up supplements the answer rather than deciding what the question is about. Two shapes say
     * so — the one that reads the open world, and the fallback, which catches free text that may name
     * nothing. The runner skips resolution entirely for such a shape, which is why this is empty
     * rather than every kind.
     */
    val accepts: Set<SubjectKind>

    /**
     * How many variants an answer from this shape may carry.
     *
     * Part of the shape's contract and checked mechanically, because a shape promising
     * selectable alternatives and returning one is a defect the interface cannot see: it
     * renders what it is given, and one variant looks exactly like a shape that never offered
     * a choice.
     */
    val variants: IntRange

    /**
     * Whether this shape answers from the corpus.
     *
     * What decides the meaning of empty retrieval. For the corpus-reading shapes nothing
     * retrieved means there is nothing to answer from, which short-circuits before any model
     * call.
     *
     * Two shapes set it false, for two different reasons. The one that reads the open web answers from
     * material the corpus never held, so an empty corpus means only that there was nothing to
     * supplement with. The fallback answers a general question, and refusing it because no span ranked
     * highly would refuse the requests it exists to catch — which is a judgement rather than a
     * consequence, and the shape says so itself.
     */
    val readsCorpus: Boolean

    /**
     * Whether this shape's answer *is* the assertions it cites.
     *
     * True where the answer is made of the findings — what was undertaken, what is coming, and
     * what the two say about where a subject stands, which is a statement of what such an answer
     * is made of and not of the order it is laid out in: the shape owns that, and the one answering
     * where things stand leads with a bottom line drawn from the rest. False where the deliverable
     * is text the model composed: a message somebody is
     * going to send, a report on the open web, a general question the corpus may have nothing to
     * say about. A shape saying false for one of those reasons is not worse for carrying no
     * assertion; each says which reason is its own.
     *
     * **What it decides is that a composed reply from such a shape carrying no claim at all is a
     * fault.** The grounding check applies to claims and not to prose, which is what stops it
     * blocking every draft — so a shape whose answer is its cited findings satisfies it by writing
     * those findings as prose instead, and the worse the answer the likelier that route, because a
     * reply with nothing to cite has nothing to mark. What then reaches a reader is an assertion
     * about the corpus on the half of the reply nothing checks.
     *
     * **Declared rather than derived from [readsCorpus], which is true of the drafting shape too.**
     * What separates the two is not where the material comes from but what the answer is made of,
     * and only the shape can say that.
     *
     * The shape tells the model so as well — see [Reply.format] — so what the runner enforces is
     * what the prompt asked for rather than something it contradicts.
     */
    val assertsFindings: Boolean

    /**
     * The ordered stages that answer this shape.
     *
     * Retrieval, assembly and generation, in whatever arrangement the shape needs. The runner
     * contributes shape binding, scope resolution and validation around them and never looks
     * inside this list, beyond asking each stage whether it spends the model.
     */
    val stages: List<Stage>
}

/**
 * A shape's name, which doubles as the command that selects it.
 *
 * A value class rather than a bare string so that a command cannot be passed where a shape
 * identifier belongs, and so the registry's map cannot be keyed by the wrong half of anything.
 */
@JvmInline
value class ShapeId(val value: String) {
    init {
        require(value.isNotBlank()) { "A shape is identified by its name, and a blank one names nothing." }
    }
}

/**
 * The shapes this build can answer, and the one that answers everything else.
 *
 * **Shapes register here; nothing here knows what they are.** Adding a shape is a new package
 * constructing itself into this list, with no edit to the runner, the resolver or the check —
 * which is the whole reason the machinery takes a registry rather than a `when` over an enum.
 *
 * The free-form path is a pipeline like the others and registers the same way. What makes it
 * the fallback is that it is named separately here, not that it is built differently.
 */
class ShapeRegistry(
    shapes: List<Shape>,
    private val fallback: Shape,
    /**
     * What reads free text when no command named a shape.
     *
     * Absent is a working configuration rather than a degraded one: free text then takes the
     * fallback, which answers a general question from a ranked read of the corpus and needs no
     * classification to do it. An interface that only ever sends commands — the model-facing one
     * does — spends nothing on a classifier it would never consult.
     */
    private val classifier: Classifier? = null,
) {

    private val byId: Map<ShapeId, Shape> = (shapes + fallback).associateBy { it.id }

    /**
     * The shapes free text may be classified into — everything registered except the fallback,
     * each with the line that says what it answers.
     *
     * The fallback is excluded because it is where an unclassified request goes: offering it as a
     * candidate would let the model choose what it gets by default anyway, and a verdict naming it
     * would be indistinguishable from no verdict at all.
     *
     * Built from the shapes rather than held as a table beside them, so the two cannot disagree
     * about what a shape answers.
     */
    private val classifiable: List<Classifier.Candidate> =
        shapes.map { Classifier.Candidate(it.id, it.description) }

    init {
        val registered = shapes + fallback
        // Checked over the classifiable shapes and not the fallback, because that is where the
        // description has a consequence: the fallback's is never shown to anything, while a
        // candidate offered with nothing usable beside its identifier is a shape the router can
        // only reach by guessing at its name — the one failure this field exists to prevent.
        //
        // **Both halves of the contract, because the prompt interpolates this verbatim.** Blank
        // offers the model nothing; a line break makes one candidate read as two, which is the
        // worse of the pair and the one no reader would see.
        val undescribed = shapes.filter { it.description.isBlank() || it.description.lineSequence().count() > 1 }
        require(undescribed.isEmpty()) {
            "A classifiable shape says what it answers, in one line, and these do not: " +
                undescribed.joinToString { it.id.value } +
                ". Routing reads one line per candidate, so a blank one offers the model the " +
                "identifier and nothing else, and a broken one offers a candidate that does not exist."
        }
        require(registered.distinctBy { it.id }.size == registered.size) {
            "Two shapes share an identifier: " +
                registered.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.joinToString { it.value } +
                ". A command would then answer through whichever of them was registered last, " +
                "which is a property of list order rather than of anything anybody chose."
        }
    }

    /**
     * The words that bind a shape, which is every registered shape's identifier.
     *
     * **Read by a surface, so that a command naming no shape is refused rather than answered.**
     * [bind] still binds the fallback for one, because a caller reaching this far has had its
     * request accepted and a shape has to run; what this is for is not letting it get that far.
     * Answered through the fallback, a word that means nothing costs an answering call at the
     * costliest path, is ranked against a corpus by text carrying it, and is reported in the log
     * under a shape name there is no shape for.
     *
     * **The fallback is in it, because a command can name it.** What is true of the fallback is not
     * that nothing names it but that no command list offers it — so a caller asking for it by name
     * is asking for something this build answers, and only a word naming nothing at all is the
     * caller's mistake. Which words a *list* offers is the contract's `Command` to say, and this is
     * not that: it is what may be *accepted*, which is the larger set and the wrong one to offer.
     *
     * Strings rather than [ShapeId]s, because what is checked against it is a wire field and
     * [ShapeId] refuses a blank one — building one from whatever a caller sent would raise where
     * the answer wanted is *no*.
     */
    val answerable: Set<String> = byId.keys.mapTo(mutableSetOf()) { it.value }

    /**
     * The shape that will answer [request], and what deciding that cost.
     *
     * An explicit command binds directly and spends nothing. Free text is classified. Anything
     * unclassified falls to the free-form path, which includes a command this build does not know:
     * nothing here refuses a request, so a word naming no shape still has to be answered by
     * something, and the fallback answering the text as written is better than this layer deciding
     * that a reply is a refusal. What keeps that from being how a typo is answered is [answerable],
     * asked where there is still a caller to refuse.
     *
     * **An unknown command is not classified either.** Somebody typing a command meant to name a
     * pipeline, not to be read as prose, and routing the words of a mistyped command by their
     * meaning is a second guess on top of the first. The fallback answers it as asked.
     *
     * `suspend`, and returning what it spent, because classification is a model call: the one
     * model call on this path that a request pays for before any shape has been chosen, and a
     * per-request bound that did not count it would not be a bound.
     */
    suspend fun bind(request: Request): Binding {
        val commanded = request.command?.takeIf { it.isNotBlank() }
        // **Named nobody, stated rather than defaulted, because the other path out of this
        // function means the opposite by the same silence.** A command's name is the argument the
        // interface separated out, so one carrying none has genuinely named nobody — while free
        // text nothing read has established nothing. Left to a shared default, one of the two
        // would be asserting the other's meaning.
        if (commanded != null) {
            return Binding(byId[ShapeId(commanded)] ?: fallback, subjectName = SubjectName.None)
        }

        // No classifier and an unclassifiable text are the same outcome, so they share a path: the
        // fallback answers it, having spent whatever the attempt cost, which is nothing in the first
        // case.
        val verdict = classifier?.classify(request.text, classifiable)
        return Binding(
            shape = verdict?.shape?.let { byId[it] } ?: fallback,
            // Carried whatever shape was bound. The fallback accepts no subject and will not be
            // resolved for, so a name reaching it is simply unused — and discarding it here would
            // make the field depend on which shape won, which is not what it is about.
            //
            // **No classifier establishes nothing, rather than establishing that nobody was
            // named.** Nothing read the text either way. It is tempting to call it *nobody* on the
            // grounds that the fallback accepts no subject and is never resolved for, which is
            // true of the fallback this build registers — but nothing here holds a fallback to
            // that, and one accepting the inbox would then answer corpus-wide for text nothing
            // had read. That is this card's defect reached through configuration instead of
            // through the model, and the honest value closes it without depending on which shape
            // was registered.
            subjectName = verdict?.subjectName ?: SubjectName.Unread,
            usage = verdict?.usage ?: TokenUsage.NONE,
        )
    }

    /**
     * Which shape answers, what the request is about, and what choosing it cost.
     *
     * The cost is carried rather than discarded because routing is model spend on every free-text
     * request, and the runner charges it to that request's [Budget] — spend that nothing counted
     * would make the ceiling a number about generation rather than about the request.
     *
     * [subjectName] is what the classification read out of the text.
     *
     * **It has no default, because both constructions here mean different things by an absent
     * name.** A command carrying no argument has named nobody, while free text nothing read has
     * said neither, and the difference decides whether a shape sweeps the whole corpus or asks
     * what the request was about. A default would let one of those two stay silent and be read as
     * the other. [Classifier.Verdict] carries the same field and keeps a default, which is not
     * an inconsistency: there, one construction makes no model call at all and *nothing* is what
     * it has established. See [SubjectName].
     */
    data class Binding(
        val shape: Shape,
        val subjectName: SubjectName,
        val usage: TokenUsage = TokenUsage.NONE,
    )
}
