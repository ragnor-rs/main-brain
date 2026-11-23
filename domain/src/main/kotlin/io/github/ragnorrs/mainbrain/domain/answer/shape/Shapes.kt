package io.github.ragnorrs.mainbrain.domain.answer.shape

import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Classifier
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.ShapeRegistry
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.answer.shape.draft.DraftShape
import io.github.ragnorrs.mainbrain.domain.answer.shape.freeform.FreeformShape
import io.github.ragnorrs.mainbrain.domain.answer.shape.prepare.PrepareShape
import io.github.ragnorrs.mainbrain.domain.answer.shape.research.ResearchShape
import io.github.ragnorrs.mainbrain.domain.answer.shape.status.StatusShape
import java.time.Clock
import java.time.ZoneId

/**
 * The shapes this build can answer, assembled into a registry.
 *
 * **The one place that names all five, and it is deliberately the only one.** The runner, the
 * resolver and the check name none of them; a process's composition root names this function and not
 * the shapes. So adding a shape is a new package and one line here, which is as close to registering
 * itself as a build with no dependency-injection framework gets — and the property that matters is
 * the one it keeps: nothing in the machinery grows a branch.
 *
 * It lives in `:domain` beside the shapes rather than in a process, because everything it touches is a
 * port. No composition root has to know what a shape needs, which is what stops the five constructor
 * signatures leaking into every process that answers a request.
 *
 * **`offer` is not here.** Offer and price reads the knowledge base, the knowledge base is corporate
 * Drive, and Drive arrives with a later workstream — so the shape, its retrieval and its priced
 * rendering belong to that workstream. The registry is open, so it arrives as a package and a line
 * rather than as a change to anything.
 *
 * @param grouping which body of work a piece of retrieved material belongs to. Passed to every
 *   shape rather than to the three that section by it, because what a shape decides is whether its
 *   answer has sections at all — and a shape that was simply not given the port would decide that
 *   by omission.
 * @param retrieval the answer path's reads, and every shape takes it for the same reason: one of
 *   them says who wrote a turn, and a turn offered without that is read as the subject's own words.
 *   A shape that answers from a ranking rather than from a subject's threads may read nothing else
 *   through it, which is a fact about what those shapes need and not a rule about the port.
 * @param classifier what reads free text when no command named a shape. Absent is a working
 *   configuration: free text then takes the fallback, which is what an interface that only ever sends
 *   commands wants.
 * @param answering how the four corpus-reading shapes and the fallback size their one model call.
 *   One value for all five, because they do the same kind of work on the same kind of material —
 *   a per-shape effort would be five numbers nobody could tune against each other.
 * @param researching and how the open-web shape sizes its own, which is deliberately separate: it
 *   is the only path that searches and reads inside the call, and the only one that needs the
 *   highest effort this build asks for. Keeping the two apart is what stops that setting reaching
 *   the other four.
 * @param zone the correspondents' own, in which every shape's material is headed so that a
 *   relative reference inside a message can be worked out. Passed to all five for the same reason
 *   [grouping] is, and **required of all five for the same reason too**: a shape offering turns
 *   without it would date them in a zone nobody chose, and a default here is exactly what would
 *   let a sixth opt out by omission rather than by a decision. **It is also the day boundary a
 *   commitment's standing is decided against**, so a shape handed one zone for its material and
 *   another for its retrieval would label an item by a day its own heading contradicts — which is
 *   why both come from here and why the argument [clock] makes for one source of *now* applies
 *   to this in the same words. [Clock] carries no zone of its own: it answers *when is now*, an
 *   instant, and this is the calendar that instant is read on.
 * @param clock what the present moment is read from, and **unlike [zone] it is taken by some shapes
 *   and not all**. There are two separate needs behind it, worth telling apart because a shape may
 *   have either: where things stand and the call brief read it in retrieval, to sort and label an
 *   item by whether its date has passed, and only the first of those also hands it to assembly, so
 *   that the present is stated to the model. The fallback takes it for that second reason alone — it
 *   has no standing to compute, and states the present so that a date it reports can be read against
 *   one. A shape that composes text needs no present: a drafted reply is read when it is sent, and
 *   the open-web answer is not dated by this corpus. Which it is is each shape's own declaration,
 *   which is why this is passed per shape rather than read off a list of them here.
 */
@Suppress("LongParameterList")
fun shapes(
    model: ModelProvider,
    retrieval: Retrieval,
    lookup: Lookup,
    grouping: Grouping,
    clock: Clock,
    zone: ZoneId,
    classifier: Classifier? = null,
    answering: Sizing = Sizing.ANSWERING,
    researching: Sizing = Sizing.RESEARCHING,
): ShapeRegistry = ShapeRegistry(
    shapes = listOf(
        // In delivery order, which is also roughly the order they are used in: the daily driver
        // first, then drafting, then preparation, with research alongside since it waits on no
        // ingested source.
        StatusShape(
            model = model,
            retrieval = retrieval,
            lookup = lookup,
            grouping = grouping,
            clock = clock,
            sizing = answering,
            zone = zone,
        ),
        DraftShape(
            model = model,
            retrieval = retrieval,
            lookup = lookup,
            grouping = grouping,
            sizing = answering,
            zone = zone,
        ),
        PrepareShape(
            model = model,
            retrieval = retrieval,
            grouping = grouping,
            clock = clock,
            sizing = answering,
            zone = zone,
        ),
        ResearchShape(
            model = model,
            lookup = lookup,
            retrieval = retrieval,
            grouping = grouping,
            sizing = researching,
            zone = zone,
        ),
    ),
    fallback = FreeformShape(
        model = model,
        lookup = lookup,
        retrieval = retrieval,
        grouping = grouping,
        sizing = answering,
        zone = zone,
        clock = clock,
    ),
    classifier = classifier,
)
