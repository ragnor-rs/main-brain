package io.github.ragnorrs.mainbrain.domain.answer.shape.research

import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Generate
import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.answer.Stage
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import java.time.ZoneId

/**
 * Research on companies, people, markets or providers, with a source against every claim.
 *
 * **The one shape that reads the world rather than the corpus, and the one that waits on no source.**
 * The web search it needs is a tool the provider runs on its own side, declared on the request, so
 * nothing here opens outbound access from the machine. That is also why this shape is answerable in
 * this build while the sources it would otherwise want are still to come.
 *
 * **It accepts no subject.** Scoping it by a name would be the wrong operation: the question is about
 * something the corpus may never have heard of, and an entity lookup *supplements* the answer — who
 * is already known — rather than deciding what the question is about. The runner skips resolution
 * entirely for a shape whose accepted set is empty, which is exactly right here.
 *
 * **Agentic by construction, and the only shape where that is true of the model rather than of us.**
 * Searching the open web is a tool loop: the provider searches, reads, searches again, and the turn
 * may pause and be resumed. So the effort is the highest this path uses and the ceiling is the
 * largest, and both are confined to this shape rather than spent on the four that read a fixed query
 * set.
 */
class ResearchShape(
    model: ModelProvider,
    lookup: Lookup,
    retrieval: Retrieval,
    grouping: Grouping,
    zone: ZoneId,
    sizing: Sizing = Sizing.RESEARCHING,
) : Shape {

    override val id = ShapeId("research")

    /**
     * What this shape answers.
     *
     * **It says the material is the open web and not this organisation's own correspondence**, which
     * is the line a router has to draw here: a question about a company could be either, and what
     * decides it is whether the asker wants what is known from outside or what was said inside.
     */
    override val description =
        "Research from the open web on a company, a person, a market or a provider — what is " +
            "publicly known about them, rather than what this organisation's own correspondence says."

    /** Nothing. The question is its own scope — see the class note. */
    override val accepts = emptySet<SubjectKind>()

    override val variants = 1..1

    /**
     * No.
     *
     * Not because it reads nothing from the corpus — it does, as a supplement — but because an empty
     * corpus must not refuse it. The short-circuit exists to stop a model call that has nothing to
     * answer from, and this shape's material comes from the web: a corpus that happens to know
     * nothing about the subject is the ordinary case rather than a reason to decline.
     */
    override val readsCorpus = false

    /**
     * No. The findings are from the web and carry their sources in the prose.
     *
     * A claim line here is only ever about the held material offered as a supplement, and a question
     * about a company nobody here has dealt with has none to make — which is the ordinary case for
     * this shape rather than a degraded one. Refusing a reply that asserts nothing about the corpus
     * would refuse the answers this shape exists to give.
     */
    override val assertsFindings = false

    override val stages: List<Stage> = listOf(
        SupplementWithWhatIsKnown(lookup, retrieval),
        // Not sectioned either, for a different reason than the draft's: the answer is about the
        // open web and the corpus material is a supplement to it. Grouping that answer by the
        // bodies of work the supplement happens to come from would break up a reply about something
        // outside the corpus along a division the question did not have.
        Assemble(INSTRUCTION, variants, grouping, sections = false, zone = zone),
        Generate(model, sizing, openWeb = true),
    )

    private companion object {
        /**
         * What this shape asks of the model.
         *
         * Two instructions here are not obvious. The first is the split between the two kinds of
         * source. A claim line is checked against the material the system retrieved, so it can only
         * ever cite the corpus; a web finding carries its source in the prose instead.
         *
         * **The second is that the held material is named as a sample, which this shape was measured
         * needing.** Asked about a person through the interface, it reported that they appeared in
         * the corpus exactly once and were named in no other message — off the ranked supplement
         * alone, about somebody the store holds far more of than that supplement carried, with every
         * citation resolving and the grounding gate passing it clean. What that gate asks is whether
         * a cited span was retrieved, never whether the retrieved set was the corpus. The licence was
         * the sentence calling the supplement what this organisation holds; the fallback's companion
         * carries the longer argument for why each shape words this for its own read rather than
         * sharing one wording. Two things follow for this prompt in particular. A count, a
         * uniqueness claim and an absence over what is held are refused, but **saying that somebody
         * is new to us is not** — it is the reason the supplement is offered, so the prompt says
         * where that goes instead of forbidding it. And the absence split is this shape's alone: an
         * absence on the web is genuinely its to report, because it ran that search itself.
         *
         * **That split rests on the instruction rather than on the check, and it did not always.** A
         * citation used to be a forty-character span identifier, which a URL or a source name could
         * not accidentally resemble — a web finding in a claim line failed loudly and the request
         * degraded. A citation is now a small number, and this shape offers up to [KNOWN] pieces of
         * held material under exactly the numbers a model habitually footnotes web sources with. So a
         * web finding written into a claim line as `<< 1` now *resolves*, to whichever held span was
         * first, and passes the grounding gate as an assertion about the corpus that nothing in the
         * corpus supports. Failing loudly has become failing silently, which is why the instruction
         * below says twice over where a web source goes and tells the model not to number them.
         */
        val INSTRUCTION = """
            You research a question against the open web and report what you find.

            Every finding carries its source inline, as the name of the source and its link, next to
            the finding itself. A finding without a source does not go in the answer. Where sources
            disagree, say so and give both. Do not number your sources: write the name and the link,
            never a bracketed figure standing for one.

            Separate what you found from what you inferred. Say when something is your reading of the
            evidence rather than what a source states.

            The material at the end, if any, is some of what this organisation already holds about
            the subject — a sample found by ranking the corpus against the question, and not the
            whole of what is held. Use it to say what is already in hand, and to avoid presenting a
            known contact as a new discovery. Do not use it to say how much is held: that somebody
            appears once, or in one place, or nowhere else, or is not known to this organisation,
            are statements about a whole you were not given, and no number you cite evidences one.
            Where nothing in it bears on the subject, that is what to say — this ranked sample of
            what is held carries nothing about them — and it is the sentence to write rather than
            that they are new or unknown here. It is about this sample, not about the material, so
            it is ordinary prose and carries no citation. Each message in the sample is marked with
            who sent it, as "Sent by": attribute it to the person marked on it and to nobody else,
            and never to somebody a source on the web named. A message carrying no such mark was
            written by somebody the corpus does not name, so say it was said without saying who by.

            Claim lines are only for assertions about that held material, and cite its numbers. The
            numbers mean the held material and nothing else. Web findings never go in a claim line,
            and never carry a number: they carry their source in the prose, as above. A web finding
            given a number would be read as an assertion about held material, which is the one way
            this answer can be wrong without looking wrong.

            Answer in the language of the question, whatever language the sources you read are in. A
            name, a title or a phrase you quote keeps the form its source gave it. For a person the
            material names, that form is the one it gives for them — the name marked on the message,
            not the way a message addresses them and not the way a source on the web writes it.

            Say plainly when you could not find something on the web. An absence there is yours to
            report, because you ran that search yourself, and it is useful; a plausible guess is the
            one failure this is unusable with. An absence in the held material is not the same
            thing: you searched nothing, you were handed a ranked sample of what is held, so report
            that the sample does not carry the thing rather than that the organisation does not hold
            it.
        """.trimIndent()
    }
}

/**
 * Who and what the corpus already knows about the question.
 *
 * A ranked lookup over the index with the question as written, and then a read that says who wrote
 * each turn it found. The second is not optional: the index stores no author, so a ranked read that
 * stopped there would offer the corpus's messages with nobody attached, and the point of this
 * material is who is already known and what was said before.
 *
 * It supplements and does not scope: an empty result is the ordinary case for a question about a
 * company nobody here has dealt with, and the shape answers from the web regardless — which is what
 * [ResearchShape.readsCorpus] being false arranges.
 */
private class SupplementWithWhatIsKnown(
    private val lookup: Lookup,
    private val retrieval: Retrieval,
) : Stage {

    override suspend fun apply(exchange: Exchange): Progress {
        val known = Material.distinct(lookup.of(exchange.request.text, limit = KNOWN))
        // Who wrote each of them. The point of this material is to say who is already known and what
        // was said before, so a turn with nobody attached answers half of that — and the half it
        // answers is the one a web answer is most likely to put in the wrong mouth, since the names
        // it has read are the only ones in front of it.
        val turns = retrieval.turnsOf(known.map { it.id.record }.distinct())
        return Progress.Carried(
            exchange.copy(retrieved = known.map { Material.authored(it, turns[it.id.record]?.author) }),
        )
    }

    private companion object {
        /**
         * How much held material to carry.
         *
         * Smaller than the corpus-reading shapes take. It is context for a web answer rather than the
         * material the answer is made of, and a long tail of weak matches would crowd out the part of
         * the prompt that matters.
         */
        const val KNOWN = 10
    }
}
