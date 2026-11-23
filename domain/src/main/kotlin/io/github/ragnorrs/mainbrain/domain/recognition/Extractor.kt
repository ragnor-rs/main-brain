package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The one thing in recognition that spends the model budget: reading commitments, events and
 * attributes out of one message, with the conversation around it as context.
 *
 * **Two stages, not one.** The first classifies — whether the message this window reads states a
 * commitment, an event or an attribute, and whether what it found may be read as settled. The
 * second restates one of them so that it stands on its own, which is a different job: it resolves
 * a relative date, names who owes what to whom, and removes the pronouns that make a sentence
 * useless detached from its conversation. A single call doing both tends to do the second badly,
 * because the restating drags its attention off the finding.
 *
 * **Both stages are shown who said every line**, because a claim that has to stand on its own has
 * to name its party and a window without authors offers nothing to name one with. The labels are
 * the window's — see [Window.label] — so nothing here can mint an identifier.
 *
 * The second stage is one call per candidate rather than one for all of them, and both stages are
 * submitted to the batch endpoint. One call per candidate rather than a reply holding several
 * rewrites, because matching those back to the candidates they came from would be a join on content
 * the model emits, which fails silently where this fails per candidate; batched rather than
 * synchronous because a candidate's request is independent of its siblings', so the only thing one
 * call at a time bought was the full price and a round trip held under the pass's exclusion.
 *
 * **Every reply is parsed as lines of delimited fields, not as JSON.** `:domain` carries no
 * dependency, so there is no parser here and a hand-written JSON reader would be the worst of
 * both. The format is the narrowest thing a model reliably produces: fixed fields, one record per
 * line, and the free text last so a delimiter inside it cannot shift a field.
 *
 * **It emits proposals and nothing else.** No entity is named here and no identifier is minted;
 * see [Proposal].
 */
// One function past the per-class cap, and the shape is three asks each with a builder and a reader
// beside it. Splitting it would put one stage's request in one file and its reply in another, which
// is exactly the pairing a reader checking a prompt against its parser needs in front of them.
@Suppress("TooManyFunctions")
class Extractor(
    private val model: ModelProvider,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    /**
     * What this extractor's output depends on, for the derivation watermark.
     *
     * The model, the prompts and the reasoning effort, because any of the three changing changes
     * what is read. The prompts are covered by a digest of them rather than by a number somebody
     * remembers to raise — a reworded instruction that nothing re-derives is exactly the failure
     * the watermark exists to prevent — and the efforts are named because the port documents
     * effort as the one dial that works, controlling depth and spend together.
     *
     * What a digest cannot cover is how the window is rendered into the prompt, which is why the
     * pass puts its own prefix in front of this.
     *
     * **The adjudication stage has a version of its own and is deliberately absent from this one**,
     * though it is this class's third ask and spends the same budget. This watermark decides which
     * *records* are owed a reading, and that stage reads no record: it compares two findings already
     * written and decides whether they are one. Folding its prompt in here would re-extract the
     * whole corpus inside the horizon, at full price, because a comparison was reworded. So it
     * carries [adjudicationVersion] instead, which retires what that stage decided and nothing else.
     */
    fun version(): String =
        "${model.version()}@${(CLASSIFY_SYSTEM + REWRITE_SYSTEM).hashCode()}/$CLASSIFY_EFFORT+$REWRITE_EFFORT"

    /**
     * What the adjudication stage's answers depend on, so that a better comparison retires them.
     *
     * **A version per stage, which is the shape [Closer] already has and for the same reason.** The
     * three asks this class makes are invalidated by different things: reword the classification or
     * the restatement and the records inside the horizon are owed a reading again, while rewording
     * the comparison must revisit the pairs it decided and must *not* re-extract a corpus no word of
     * it touches. One version over all three buys the second cost to get the first.
     *
     * **Carried on the verdict row rather than in a watermark table**, because a pair is not a
     * record and no pass marks one: what this stage produces is a row saying two findings were
     * compared, and the only place that can say which wording compared them is the row itself. See
     * [io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository.keepApart], which stores it, and
     * [io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository.candidates], which stops excluding a
     * pair whose verdict names a version that has moved.
     *
     * The model, the instruction and the effort, for the reason [version] names them: any of the
     * three changing changes the answer. [ADJUDICATION] goes in front so that the value can never be
     * read as the cheap tier's, which names its own stage and is not named alike — and that matters
     * because the two tiers' answers share one column.
     *
     * **What a digest cannot cover here is the same thing it cannot cover there: how the pair is
     * rendered into the prompt.** [comparison] builds the subject line and flattens each claim onto
     * one line, and none of that is hashed — so a change to it moves the answer and would retire
     * nothing. Where the reading stages have the pass's own prefix in front of them to raise by
     * hand, this has [ADJUDICATION]'s revision, which is what that is for.
     */
    fun adjudicationVersion(): String =
        "$ADJUDICATION:${model.version()}@${ADJUDICATE_SYSTEM.hashCode()}/$ADJUDICATE_EFFORT"

    /**
     * The classification this window needs asking, or `null` where it needs none.
     *
     * **Split from reading the answer so that the asking can be batched.** The classification runs
     * over every window and is submitted with every other window's at half the price; what comes
     * back is read by [candidacy]. The restatement stage is submitted the same way, a collection
     * later — it depends on the classification it follows, so the two can only ever be a collection
     * apart, and that is a stage of its own rather than a reason to pay full price.
     *
     * `null` rather than an empty request for a window whose target has no text: a sticker has
     * nothing for a model to find, and asking anyway is billed for the instruction and for the
     * conversation around it. A caller that gets `null` has nothing to submit and nothing to wait
     * for, and may settle that one record — a window reads one message, so nothing else is owed on
     * its behalf.
     */
    fun classification(window: Window): ModelRequest? =
        if (!window.hasText) {
            null
        } else {
            ModelRequest(
                system = CLASSIFY_SYSTEM,
                prompt = "${window.target.nativeId}\n\n${window.rendered()}",
                maxTokens = maxTokens,
                effort = CLASSIFY_EFFORT,
            )
        }

    /**
     * What [classified] means for [window]: the findings it named, and what to ask about each.
     *
     * The window is passed in again rather than carried alongside the submission, because it is
     * re-derived on collection: the pass reads the conversation whole and takes the context around
     * the target again, so a window needs nothing remembered but the record it reads.
     *
     * **What is rendered here is not always the text the classification read.** A submission is
     * collected a sweep or more after it was made, and a message landing inside the target's reach
     * in between joins the context — as does an edit to a context message's text. So the
     * restatement can be asked against a line the classification never saw. It cannot invent a
     * finding from one: the candidate comes from the classification's own reply, and that reply may
     * only name the target. What a changed context *can* do is withdraw a label, which costs a
     * finding and is why [reading] compares one rather than trusting it.
     *
     * Nothing is asked here. The requests come back on [Candidacy.Found] for the caller to submit,
     * because a stage that called the provider while reading the previous stage's reply would put a
     * round trip back inside the pass's exclusion, which is the whole of what batching it bought.
     *
     * No partial is ever read. A truncated classification is a list of findings that stops
     * somewhere, and reading it would settle the window on a reply that was cut off.
     *
     * **The failures do not divide the same way, and conflating them stalls the pass.** A
     * truncation or an unfinished turn can come out differently next time — a shorter window, a
     * higher ceiling, a turn that completes — so the window is still owed. A refusal cannot: the
     * port documents it as "not retryable: the same request refuses again", so holding the window
     * open means re-selecting it, re-classifying it and being refused again on every sweep, at a
     * model call each. Selection is oldest-first over a small batch, so one such window stops the
     * pass advancing at all.
     *
     * A submission's own two failures join the retryable side. One the provider never answered is
     * owed because another attempt is exactly what clears it. One whose reply cannot be read is
     * owed too, and that is the less obvious call: a retry will not clear it — only a build that
     * knows the value will — but giving the window up would lose what it holds for good, over a
     * value the next deploy may well name. The reason travels out so a log can say which it was.
     */
    fun candidacy(window: Window, classified: BatchOutcome): Candidacy = when (classified) {
        is BatchOutcome.Replied -> when (val outcome = classified.outcome) {
            is ModelOutcome.Answered -> found(window, outcome.text)
            is ModelOutcome.Truncated -> Candidacy.NotRead("the classification ran out of room")
            is ModelOutcome.Paused -> Candidacy.NotRead("the classification did not finish")
            is ModelOutcome.Refused -> Candidacy.Declined(
                "the classification was declined" + outcome.category?.let { " as $it" }.orEmpty(),
            )
        }

        is BatchOutcome.Unfinished ->
            Candidacy.NotRead("the classification was not answered: ${classified.reason}")

        is BatchOutcome.Unreadable ->
            Candidacy.NotRead("the classification cannot be read: ${classified.reason}")
    }

    /**
     * A classification that came back, which is not the same as one that said something.
     *
     * An empty or blank body is the case to be careful with: the model is told to answer the
     * single word `none` when a window holds nothing, so a reply with no content is not that
     * answer — it is an answer nobody received. Reading it as an empty finding list would settle
     * the window for ever against a call that produced nothing, which is the collapse
     * [Candidacy] is sealed to prevent.
     *
     * **The three ways a line produces no candidate are counted apart, because they mean different
     * things.** A line the parser cannot read at all says the model is answering in a shape this
     * build does not know — a prompt and a parser that have drifted apart, which is a total failure
     * worth a deployment's attention. A line read perfectly and refused by a rule — a finding placed
     * on a context message, an attribute whose subject field is not the author's own label — is the
     * ordinary course of events and will happen on most sweeps. And a line naming [UNLISTED] is
     * neither: it is the stage saying it met a fact outside the closed vocabulary, which is the
     * answer the instruction asks for and the one figure that says how often that happens. Folded
     * into one figure the routine case buries the
     * total one, which is the shape that made four unrelated answer-path failures read as one.
     *
     * None asks for a restatement, which is why all three travel on [Candidacy.Found] rather than
     * being inferred later from how many replies came back — and why refusing a reading here rather
     * than after the restatement is what makes it cheap, since the restatement is the expensive
     * stage.
     *
     * **A line the model wrote twice is a third figure and not a third way of producing nothing.**
     * The prompt and the parser agree and no rule was applied; the model simply repeated itself, and
     * every copy is asked about regardless, because a line carries no claim and two real findings of
     * one kind on one message are indistinguishable from one written twice. So it travels beside the
     * others as spend that was made rather than as a finding that was not.
     *
     * **A reply of nothing but prose settles the record rather than leaving it owed.** It is an
     * answer the model gave and this cannot use, most often one that has started explaining itself
     * instead of answering — and that is deterministic, so asking again buys the same prose at a
     * call each. Held owed it would sit at the head of every sweep's selection for ever, which is
     * the loop [Candidacy.Declined] exists to avoid, so it is counted as lost here instead. A reply
     * that narrates *around* a `none` is the opposite case and costs nothing: the refusal is the
     * answer, the prose is commentary, and nothing was lost.
     */
    private fun found(window: Window, classified: String): Candidacy {
        // The one reply that does leave the window owed: an answer nobody received, where asking
        // again may well be answered. Tested first because a blank body carries no candidate line
        // either, so the prose case below would otherwise claim it and name the wrong failure.
        if (classified.isBlank()) return Candidacy.NotRead("the classification came back empty")

        val lines = classified.candidateLines()
        val unreadable = if (lines.isEmpty() && !classified.saysNothing()) {
            classified.lines().count { it.isNotBlank() }
        } else {
            0
        }

        val rendered = window.rendered()
        var lost = unreadable
        var refused = 0
        var repeated = 0
        var unlisted = 0
        val seen = mutableSetOf<String>()
        val asks = buildList {
            lines.forEach { line ->
                // Compared without case, as every field of a line is, so that a model which
                // recapitalised its own repeat is counted as the one repeat it is.
                //
                // Counted and still asked about. A classification line carries no claim — a kind,
                // a message, a hedge and a subject — so two genuinely different findings of one
                // kind on one message produce the same line, and dropping the second would lose a
                // finding the derived layer is built to keep. What a repeat costs is a duplicate
                // restatement the content-keyed write then folds away, which the figure is here to
                // make visible rather than to trade away silently.
                if (!seen.add(line.lowercase())) repeated++
                when (val reading = reading(line, window)) {
                    is Reading.Unreadable -> lost++
                    is Reading.Refused -> refused++
                    // Counted and not asked about, which is the whole of what the fourth kind
                    // buys: a line that says the stage met a fact it cannot label, for the price
                    // of the classification already made and no restatement at all.
                    is Reading.Unlisted -> unlisted++
                    is Reading.Read -> add(Ask(line, restatement(rendered, reading.candidate)))
                }
            }
        }
        return Candidacy.Found(asks, lost, refused, repeated, unlisted)
    }

    /**
     * One restatement's reply read back against the candidate it was asked for.
     *
     * **[line] is the classification's own line, parsed again rather than carried as parts.** The
     * parser that reads it here is the one that found the candidate, so there is one definition of
     * what a finding is; carried as a structure it would be a second one, free to drift from the
     * format the model is actually told to produce.
     *
     * **One candidate can come back as more than one finding, and the only thing that divides them
     * is which party each obliges.** A sentence binding two participants — one to prepare the work
     * and another to review it — is one thing the classification reports and two obligations the
     * store has to hold, because a row carries one [Proposal.obliged] and direction is part of what
     * a commitment is. The split is here rather than a stage earlier for a reason the shape of a
     * classification line settles: that line is a kind, a message, a hedge and a subject, so two
     * commitment lines on one message are the same line — see [Candidacy.Found.repeated] —
     * and both build the identical restatement request. Two candidates could not be asked
     * different questions, so they could not come back as different readings. Asked once and
     * answered per party, the split costs no further call at the expensive stage.
     *
     * **Two rails bound what a further line may add, and that is a rail rather than a request.**
     * The instruction asks for a line per party; what a model does with an instruction to divide
     * something is sometimes to divide what was whole, and a single participant's undertakings
     * split in two is the excess this invites. So a line is refused where another line of the same
     * reply already obliged its party, and refused where another already said the same thing in the
     * same words — the second because the store keys a finding by its conversation and its claim,
     * so two rows worded alike are one row there whoever they oblige, and the write would keep the
     * later party. Both are counted on [Restatement.repeated] rather than passed over. An event and
     * an attribute oblige nobody, so each of those is one party and keeps the single reading it had.
     *
     * **What that gives up is two obligations genuinely falling on one party, and the loss is
     * unconditional.** It cannot be recovered by the classification stage reporting two lines, for
     * the reason the paragraph above gives: two such lines are identical and buy identical
     * restatements. The evidence stays in the corpus either way, and a claim naming one of the two
     * undertakings is the narrower reading rather than a wrong one.
     *
     * **The first line is still the answer.** It decides whether there is a reading at all and why
     * there is none, so a reply whose opening line will not parse is lost exactly as it was before
     * the split. The lines after it are read whole — each carries its own claim, its own date and
     * its own refusal, because a line stands for its party's obligation rather than for a party
     * bolted onto the first line's sentence — but a line can only ever *add* a finding, never
     * change what the reply failed for. A model that writes a closing remark after its answer is
     * the ordinary failure of this format, and a remark carrying no delimiter parses into nothing.
     *
     * No proposal where the model declined, ran out of room, answered in a shape *this* cannot parse
     * — a line with the wrong number of fields, a blank claim, a blank predicate field on an
     * attribute — answered a predicate no statement could be keyed by, whether the refusal word or
     * a word the vocabulary does not hold, answered an entry the vocabulary does hold over a value
     * of a sort it does not take, or where the line no longer passes the checks [reading]
     * makes against [window]. The last is the case batching
     * brings: a submission is read a sweep or more after it was made, and the conversation grows in
     * between, so a label the line names may no longer be the target's. A finding is then lost rather
     * than placed on the wrong party.
     *
     * A candidate that cannot be restated is dropped rather than failing the window, and counted so
     * that it is not dropped in silence. The asymmetry with a refused classification is deliberate: a
     * window nobody classified is work not done and has to be done again, while a single candidate
     * the model will not restate would otherwise be retried for ever — a refusal is deterministic, so
     * the same request refuses again. The evidence stays in the corpus either way.
     */
    fun restated(window: Window, line: String, reply: BatchOutcome): Restatement {
        // The reason travels even where the line cannot be placed, because the two failures are
        // independent: a reply the provider could not read is a condition of the build, and
        // dropping the reason because the window had also moved would hide it in the one case
        // worth investigating.
        val unreadable = (reply as? BatchOutcome.Unreadable)?.reason
        val reading = reading(line, window)
        val candidate = (reading as? Reading.Read)?.candidate
        if (candidate == null) {
            // **[Reading.Unreadable] on a stored line is a deploy, not a defect in the reply.** The
            // line is this build's own data, written by the classification that found the candidate,
            // so a shape this cannot read means the shape changed between the two collections. Said
            // so rather than counted as a lost finding, because the caller has to leave the record
            // owed: marking it would settle a message against a reading no build ever got to make.
            // **The stored line and not the reply**, and the two reasons it can fail are the two
            // [Reading] already separates: a shape this build cannot parse is a deploy under the
            // submission, while a line read and declined by a rule is the ordinary course of
            // events. The stop reason travels beside either where there was one, for the reason
            // stated above.
            return Restatement(
                findings = emptyList(),
                unreadable = unreadable,
                // Written as the rule and its remainder rather than the other way round, because
                // a line in a shape this build cannot parse reaches here having never been
                // submitted as a restatement by it, and so would a line naming the fourth kind,
                // which nothing here asks about and no build has stored. Both are its
                // own data in another build's shape and both have to leave the record owed, so
                // only the rule that settles has to be named.
                unused = if (reading is Reading.Refused) {
                    Restatement.Unused.REFUSED
                } else {
                    Restatement.Unused.FOREIGN
                },
            )
        }
        // Through the sealed types rather than a cast, so that an outcome added to either port
        // breaks this rather than falling into the failure branch by default.
        val answer = when (reply) {
            is BatchOutcome.Replied -> when (val outcome = reply.outcome) {
                is ModelOutcome.Answered -> outcome.text
                is ModelOutcome.Truncated, is ModelOutcome.Refused, is ModelOutcome.Paused -> null
            }

            is BatchOutcome.Unfinished, is BatchOutcome.Unreadable -> null
        }
        // The header the instruction states is dropped wherever it appears, because a model asked
        // for a line per party writes it above its answer from time to time and it carries the
        // field count — see [REWRITE_FORMAT], which is the one declaration the prompt asks for it
        // by and this refuses it by.
        val answered = answer?.lines()?.map(String::trim)?.filter { it.isNotEmpty() && !echoesFormat(it) }.orEmpty()
        // The opening line, which decides whether there is a reading at all and why there is none.
        val opening = answered.firstOrNull()?.let { read(candidate, window, it) }
        // The lines after the opening are read only where the opening produced a commitment: a
        // party is what they can add, and the two kinds that oblige nobody have none. Each is read
        // whole even so — its own claim, its own date and its own refusal — because a line stands
        // for its party's obligation and not for a party bolted onto the opening line's sentence. A
        // reply whose opening line will not parse is lost on that line, exactly as it was before a
        // candidate could come back as more than one finding.
        val readings = if (opening?.claim != null && candidate.kind == ProposalKind.COMMITMENT) {
            listOf(opening) + answered.drop(1).map { read(candidate, window, it) }
        } else {
            listOfNotNull(opening)
        }
        val folded = fold(candidate, readings)
        return Restatement(
            findings = folded.findings,
            // Named only for the failure a deploy can clear, and not for a refusal or a truncation:
            // those are the model's answer to this request and will be its answer again, while this
            // is a stop reason the build has no reader for yet.
            unreadable = unreadable,
            // Read off the opening line, which is the one that says whether this reply produced a
            // reading: a line after it can only ever add a finding, so a surplus line that would
            // not parse says nothing about why there was nothing to add one to.
            //
            // The predicate field only where that line gave up a claim, because a line that gave up
            // none did not parse and says nothing about its predicate field whatever that field
            // held.
            unused = unused(
                folded.findings.firstOrNull()?.proposal,
                reply,
                answer,
                opening?.field?.takeIf { opening.text != null },
                opening?.misshapen == true,
            ),
            repeated = folded.repeated,
        )
    }

    /**
     * The findings [readings] come to, and how many of its lines bought none.
     *
     * **What a further line has to bring to be worth a row, and the two rails are not one
     * question.** The party is what the split divides by, so a line obliging a party another line
     * already obliged adds no division; `null` is a party like any other here, being the answer
     * for an obligation no line's label could place, and it is also what holds an event and an
     * attribute to the single reading they have always had.
     *
     * **The claim is what the store keys by**, which is the half the party rail cannot see: a
     * finding is keyed by its conversation together with its claim, so two lines obliging
     * different parties in the same words are one row there — and both readings carrying the one
     * message's instant, the write would take the later line's party and the opening line's
     * obligation would be lost under a figure that reads as a better reading arriving. Refused
     * here instead, where it is visible and the opening line still wins.
     *
     * Its own function because [restated] carries the reasons a reply produced nothing, and the
     * two together ran past the complexity this file holds itself to.
     */
    private fun fold(candidate: Candidate, readings: List<Line>): Folded {
        val findings = mutableListOf<Restatement.Finding>()
        val obliged = mutableSetOf<ExternalId?>()
        val claimed = mutableSetOf<String>()
        var repeated = 0
        readings.forEach { reading ->
            val claim = reading.claim ?: return@forEach
            // Counted rather than passed over, for the reason the classification stage counts the
            // line it wrote twice: the call was paid for, and the rate is the one dial that says
            // whether the instruction to divide by party is being obeyed or taken as licence to
            // divide what was whole. Both rails are one figure — either way the model wrote a line
            // that bought no finding.
            if (!obliged.add(claim.obliged) || !claimed.add(claim.text)) {
                repeated++
                return@forEach
            }
            // The refusal travels with the finding it was taken off rather than beside the list, so
            // that a date given up for a line this dropped cannot be reported at all.
            findings += Restatement.Finding(candidate.proposal(claim), reading.refusal)
        }
        return Folded(findings, repeated)
    }

    /** What one reply's lines came to, before the reasons it may have produced nothing. */
    private data class Folded(val findings: List<Restatement.Finding>, val repeated: Int)

    /**
     * Whether [line] is the answer format's own header rather than an answer in it.
     *
     * **Compared field by field after trimming, and not against the header as one string.** The
     * spacing is the model's own — nothing repairs what it writes — so an echo with the delimiters
     * spaced out reads identically to a person and not at all to an equality, while still carrying
     * the field count that makes it parse. See [REWRITE_FORMAT] for what such a line costs.
     */
    private fun echoesFormat(line: String): Boolean =
        line.split(FIELD).map { it.trim().lowercase() } == REWRITE_FORMAT.split(FIELD)

    /**
     * One line of a restatement's reply read against the candidate it answers.
     *
     * Its own function because a reply holds a line per obliged party, so everything here is
     * answered once per line while the reasons a reply produced nothing are answered once per
     * reply — see [restated], which folds these by the party each one names.
     */
    private fun read(candidate: Candidate, window: Window, line: String): Line {
        val fields = line.split(FIELD, limit = REWRITE_FIELDS).takeIf { it.size == REWRITE_FIELDS }.orEmpty()
        val text = fields.getOrNull(CLAIM)?.trim()?.takeIf { it.isNotEmpty() }
        // A predicate is what a statement is keyed by, so an attribute without one cannot be
        // stored and is lost here rather than written under an empty name. The other two kinds
        // assert nothing about a subject, so a predicate offered for them is ignored: the field
        // has no meaning there and refusing the whole reading over a surplus word would lose a
        // commitment to a formatting slip.
        val field = fields.getOrNull(PREDICATE)?.trim()?.let(Companion::predicateField)
            .takeIf { candidate.kind == ProposalKind.ATTRIBUTE }
        val predicate = (field as? PredicateField.Named)?.predicate
        // **The backstop, and the one test here that does not take the model's word for whether
        // its answer can be used.** A predicate that parsed and a value of a sort it does not take
        // is the shape no other check can see: the name is legal, so the vocabulary figures stay
        // silent, and the subject rule reads which party was named rather than what the fact is
        // about. See [machineShaped], which states what it can and cannot catch.
        //
        // Tested only where both halves arrived, because a reading missing either is already lost
        // for a reason of its own and a figure here would report the same loss twice.
        val misshapen = predicate != null && text != null && machineShaped(text)
        val wanted = (candidate.kind != ProposalKind.ATTRIBUTE || predicate != null) && !misshapen
        // **Resolved against the window's own authors and never against the claim's prose**, which
        // is the question the attribute's subject asks read the other way round — see [Window.party].
        // The two ask the same thing and do not answer it alike: this one keys both sides through the
        // label relation, while the subject rule compares the field to the label for equality bar
        // case, so a label re-spaced on the way back names its party here and loses the finding
        // there. A commitment's alone: an event and an attribute oblige nobody, so a label offered
        // for either is surplus and ignored, as a predicate is for the two kinds that are not an
        // attribute.
        val obliged = fields.getOrNull(PARTY)?.trim()
            ?.takeIf { candidate.kind == ProposalKind.COMMITMENT }
            ?.let(window::party)
        // Whether the obligation is somebody else's, which [elsewhere] states the reasoning for.
        val elsewhere = elsewhere(obliged, candidate.message.author)
        // **A date that is not after the message stating it is refused, not stored**, in the two
        // cases [Restatement.DateRefusal] separates. Either way it costs the date and not the claim,
        // for the reason [instantOrNull] gives: an undated finding is the ordinary case, and the
        // claim is what the call was paid for.
        val stated = fields.getOrNull(DATE)
            // Read for the two kinds that store one. An attribute's validity opens where its
            // evidence was said and nothing reads this field for it, so a date offered there would
            // be refused against a message it was never going to be stored beside — and counted,
            // which would put a disobeyed instruction into a figure that is read as a rate over
            // dated findings. The predicate field is gated to the attribute kind for the same
            // reason, one field along.
            ?.takeIf { candidate.kind != ProposalKind.ATTRIBUTE }
            ?.let { instantOrNull(it.trim()) }
        // Resolved per line, because the asymmetry turns on which party that line obliges: one line
        // of a reply can name the sender and the next another participant, and the two do not get
        // the same answer about a date already behind.
        val refusal = stated?.let {
            dateRefusal(it, candidate.message.sourceTime, candidate.kind, elsewhere)
        }
        return Line(
            claim = text?.takeIf { wanted }
                ?.let { Claim(it, stated.takeIf { refusal == null }, predicate, obliged) },
            text = text,
            field = field,
            misshapen = misshapen,
            refusal = refusal,
        )
    }

    /**
     * Whether [obliged] is a party other than [author], which is the question the date refusal
     * turns on and the only one this build can ask about direction.
     *
     * **Written as the narrow positive rather than as its negation**, because every way of not
     * knowing has to fall on the same side and a negation collects them one at a time. Only a party
     * the window placed on a record that is demonstrably not the author's is elsewhere: a party no
     * label could place is not, and neither is one read off a message the corpus names no author
     * for — there the comparison has nothing to stand on, and a null author is an ordinary state of
     * this corpus rather than a case that cannot arise.
     *
     * Not knowing counts as the author's own, which is the conservative half of the trade: that is
     * the reading whose date the record can contradict, so it keeps a timing out of the store
     * rather than putting one in that nothing can check.
     *
     * Its own function rather than a line in [restated], which this rail and the value test landing
     * on that method together carried past the complexity threshold. This is the half that reads as
     * a named question on its own.
     */
    private fun elsewhere(obliged: ExternalId?, author: ExternalId?): Boolean =
        obliged != null && author != null && obliged != author

    /**
     * Why [stated] cannot be the date of a [kind] read out of a message sent at [sent], or `null`
     * where it can.
     *
     * **Both cases are refusals and they are counted apart**, because they say different things
     * about what to do next. See [Restatement.DateRefusal].
     *
     * **A commitment's date cannot precede its message unless somebody else owes it, and the
     * asymmetry is the point.** Nothing is promised for a time already past by the party who is to
     * do it, so a date standing before a promise its own author owes is no reading of anything.
     * What falls outside that is everything [elsewhere] names and the event kind.
     *
     * An event is routinely reported after it happened, which is what the past-event read exists
     * for and what the entity's own field says when it states that a date read out of an old
     * message is a date in that message's own week. An obligation the author states about another
     * participant is the same shape of thing: a deadline another party owes is usually mentioned
     * once it has been missed, so a time already behind is the commonest reading there and refusing
     * it would discard what the corpus states. Refusing all of them alike would throw away a
     * correctly resolved moment on every retrospective mention and report it as the record
     * contradicting itself.
     *
     * **What stays inside it is every obligation whose direction the window could not settle** —
     * no label for the party, or no author on the message to compare one against. Those are
     * refused as a first-hand promise is, and the caller says why: it is the reading whose date the
     * record can contradict, so not knowing keeps the timing out rather than putting one in.
     * [Restatement.DateRefusal.BEFORE_MESSAGE] states what that costs the figure.
     *
     * Exact equality for the second, with no tolerance either side. A date a minute out is a date
     * the model worked out from something, right or wrong, while the message's own instant to the
     * second is the one value it was handed — and a window would fold the first case into the
     * second and report a resolved date as an echoed one. It applies to every kind, because
     * copying the one instant the line was handed says nothing about who owes the thing.
     */
    private fun dateRefusal(
        stated: Instant,
        sent: Instant,
        kind: ProposalKind,
        elsewhere: Boolean,
    ): Restatement.DateRefusal? = when {
        kind == ProposalKind.COMMITMENT && !elsewhere && stated.isBefore(sent) ->
            Restatement.DateRefusal.BEFORE_MESSAGE

        stated == sent -> Restatement.DateRefusal.SAME_INSTANT
        else -> null
    }

    /**
     * Which case a restatement with no proposal was, in the order they have to be tested.
     *
     * The unreadable reply goes first because it also leaves [answer] null, so testing the answer
     * first would report a stop reason this build cannot read as the model having declined — two
     * conditions with nothing in common, one of them cleared only by a deploy.
     *
     * [misshapen] is tested before the field is read, because it is the one refusal whose
     * field holds a legal entry: left to the field, a value of a sort no predicate takes would be
     * counted as a line that would not parse, which is the figure a deployment is meant to act on.
     *
     * [field] decides between the three that are all a reply the provider answered and readable,
     * and they say different things: the refusal word is the instructed answer for an attribute no
     * entry fits, so it is the one that bears on the list; a word outside the vocabulary is that
     * instruction not being followed; and a line that did not parse is this build's own drift.
     * Counted together, none of the three could be read for what it means.
     *
     * `null` only alongside a proposal, which [Restatement]'s own check holds to.
     */
    private fun unused(
        proposal: Proposal?,
        reply: BatchOutcome,
        answer: String?,
        field: PredicateField?,
        misshapen: Boolean,
    ): Restatement.Unused? = when {
        proposal != null -> null
        reply is BatchOutcome.Unreadable -> Restatement.Unused.UNREADABLE
        answer == null -> Restatement.Unused.DECLINED
        // Before the field is looked at, because this is the one case where the field holds a
        // legal entry and the reading is refused anyway: read after it, a misshapen value would be
        // booked as a line that would not parse, which is the figure a deployment acts on.
        misshapen -> Restatement.Unused.MISSHAPEN
        // Over the whole sealed type, so an answer added to the field fails to compile here rather
        // than falling in with the drift.
        else -> when (field) {
            PredicateField.Outside -> Restatement.Unused.OUTSIDE
            PredicateField.Refused -> Restatement.Unused.UNNAMED
            // A blank field is drift, so it belongs with the lines that would not parse — and so
            // does `null`, which is the wrong field count, a blank claim, or one of the two kinds
            // that assert nothing about a subject. A named predicate cannot reach here with no
            // proposal: the caller passes one only where a claim was read, and the two together
            // build it.
            PredicateField.Blank, is PredicateField.Named, null -> Restatement.Unused.UNEXTRACTABLE
        }
    }

    /**
     * Whether the two claims of [pair] state one fact, asked of the model.
     *
     * **The last tier of the collapse and the only one that costs money**, reached for a pair the
     * vectors put near without settling. Two sentences and nothing else go in front of the model: no
     * conversation, no context, no participants. That is what makes this the cheapest shape the
     * question has — the claims were restated precisely so that they stand on their own, so a
     * comparison needs nothing the rows do not already carry, and a window rendered here would pay
     * for the whole conversation again to decide one pair.
     *
     * The kind is named because the question differs by it: two commitments are one obligation or
     * two, while two events are one occasion or two, and a model told which it is reading does not
     * have to infer it from the sentences.
     */
    fun adjudication(pair: FindingPair): ModelRequest =
        comparison(pair.kind.name.lowercase(), pair.survivor.claim, pair.superseded.claim)

    /**
     * Whether the two values of [pair] state one fact about its subject.
     *
     * The statement side of the same question, and it shares the instruction rather than having one
     * of its own — which matters for a reason beyond tidiness: one submission is one request shape,
     * so a second instruction here would mean findings and values could never be asked in the same
     * batch and the stage would need a rule for which to spend a sweep on.
     *
     * **The predicate goes in front of the model, because without it the two values are not a
     * question.** An attribute's claim is the value alone — the name of a company, a rate — so two
     * of them read side by side say nothing about what relation they are values of, and whether two
     * wordings mean one thing depends on that relation entirely.
     */
    fun adjudication(pair: ValuePair): ModelRequest =
        comparison("$ATTRIBUTE$FIELD${pair.predicate.name}", pair.one, pair.other)

    /** One comparison, however the two texts were arrived at. */
    private fun comparison(subject: String, one: String, other: String) = ModelRequest(
        system = ADJUDICATE_SYSTEM,
        prompt = listOf(subject, one.oneLine(), other.oneLine()).joinToString("\n"),
        maxTokens = maxTokens,
        effort = ADJUDICATE_EFFORT,
    )

    /**
     * What the model said about a pair: one fact, two facts, or nothing this can use.
     *
     * **[Verdict.Apart] and [Verdict.Declined] have the same consequence and are reported apart.**
     * Neither merges anything and both are recorded, so nothing pays to ask the same pair twice. But
     * one is the answer the stage is for and the other is a call that bought nothing, and a single
     * case for both would have a model that had started refusing every comparison read exactly like
     * a corpus whose near-duplicates are genuinely distinct.
     *
     * **[Verdict.Unread] is the one that keeps the pair a candidate**, and it is narrow: a reply the
     * provider never finished, one cut short, or one in a shape this build has no reader for. Asking
     * again is exactly what clears the first two, and nothing is recorded, so the pair comes back on
     * a later sweep at no cost beyond the call already lost.
     *
     * A blank body is read as unanswered rather than as a verdict, for the reason a blank
     * classification is: the instruction asks for one of two words, so no word at all is a reply
     * nobody received and not a decision to keep two findings apart for good.
     */
    fun adjudicated(reply: BatchOutcome): Verdict = when (reply) {
        is BatchOutcome.Replied -> when (val outcome = reply.outcome) {
            is ModelOutcome.Answered -> verdictOf(outcome.text)
            is ModelOutcome.Truncated -> Verdict.Unanswered("the comparison ran out of room")
            is ModelOutcome.Paused -> Verdict.Unanswered("the comparison did not finish")
            // Recorded rather than retried, which is what separates this from the three above.
            is ModelOutcome.Refused -> Verdict.Declined(
                "the comparison was declined" + outcome.category?.let { " as $it" }.orEmpty(),
            )
        }

        // **Told apart, because only one of them is a condition a deploy clears.** A batch the
        // provider never finished is the ordinary end of an expired submission and clears by being
        // asked again; a reply in a shape this build has no reader for does not, and is the one
        // worth a line of its own. Collapsed together, every expiry would report that a new build
        // was needed — which is what the restatement stage is careful not to do.
        is BatchOutcome.Unfinished -> Verdict.Unanswered("the comparison was not answered: ${reply.reason}")
        is BatchOutcome.Unreadable -> Verdict.Unread("the comparison cannot be read: ${reply.reason}")
    }

    /**
     * The verdict a reply's text names, or that it names none.
     *
     * The first non-empty line and not the whole body: a model
     * that adds a sentence of reasoning after its answer is the ordinary failure of a one-word
     * format. A word outside the two offered is [Verdict.Unread] rather than either verdict —
     * guessing which was meant is how a build drifts from its own prompt silently, and the pair
     * costs one call and comes back.
     */
    private fun verdictOf(text: String): Verdict {
        val answer = text.lines().map(String::trim).firstOrNull { it.isNotEmpty() }
        return when {
            answer == null -> Verdict.Unread("the comparison came back empty")
            answer.equals(ONE_FACT, ignoreCase = true) -> Verdict.Together
            answer.equals(TWO_FACTS, ignoreCase = true) -> Verdict.Apart
            else -> Verdict.Unread("the comparison answered neither $ONE_FACT nor $TWO_FACTS")
        }
    }

    /**
     * One classification line read back: a candidate, a refusal, a fact outside the vocabulary, or
     * a line this cannot parse.
     *
     * **The identifier has to be the window's target, not merely a message in the window.** The
     * context lines are in front of the model so that it can tell what the target refers to, and a
     * finding the model attributes to one of them is a finding about a record this sweep neither
     * selected nor marks — so nothing would record that the message had been read, and every later
     * window reaching over it would produce the finding again. The instruction says so and this
     * enforces it, because an instruction is the half of a rule a reviewer can read and nothing
     * checks.
     *
     * **An attribute is kept only where the model named its author as the subject.** The subject field
     * names the party the fact concerns, as one of the window's own labels; the proposal's subject
     * is the author of the evidencing message and nothing here can make it anything else, so an
     * attribute about anybody or anything else would be stored against whoever happened to mention
     * it. A subject that is not the author's label — the reserved word, a company, a system, another
     * participant — is refused here and the caller counts it.
     *
     * **What it compares is the subject the model named, not what the fact is about.** So the same
     * fact misattributed to the author passes this rule: nothing here reads the value, which has
     * not been answered yet. This rule closes the case where the model names the system, and what
     * covers the misattributed case is the classification stage naming [UNLISTED] — not a shape
     * test over the value, which cannot be written here; see [machineShaped].
     *
     * **The comparison is safe across the gap between the two stages because a label is a function
     * of the author record**, not of this window — see [Window.label]. A window re-derived on
     * collection can hold more context than the one the model answered against, and the one thing
     * that must not happen is a label meaning one party when the model writes it and another when
     * it is read: that stores a fact about one person against another, with a citation that checks
     * out. A corpus name cannot move that way. What a changed context can do is *withdraw* a label,
     * where a name this window's authors did not share has come to be shared, and that costs the
     * finding rather than misplacing it.
     */
    private fun reading(line: String, window: Window): Reading {
        val fields = line.split(FIELD).map(String::trim).takeIf { it.size == CLASSIFY_FIELDS }
            ?: return Reading.Unreadable

        val target = window.targetMessage
        val placed = fields[MESSAGE] == target.externalId.nativeId
        val kind = kindOrNull(fields[KIND])
        val hedged = hedgeOrNull(fields[HEDGE])
        // Only an attribute has a subject to establish. A commitment or an event asserts nothing
        // about one, so a label offered for either is surplus and ignored, as a predicate is.
        //
        // A label the corpus does not supply is `null`, and nothing matches it — so an attribute
        // stated by an author the corpus names nothing is refused here, before the expensive stage.
        // It could never be stored: the proposal's subject is that author, and a statement needs a
        // subject the identity stage has recognised.
        //
        // Compared without case, as every other field of this reply is: the subject is free text
        // echoed back from the prompt, so a model returning a name it has recapitalised would
        // otherwise lose the finding to a difference that means nothing.
        val label = window.label(target.author)
        val established = kind != ProposalKind.ATTRIBUTE ||
            (label != null && fields[SUBJECT].equals(label, ignoreCase = true))

        // **The fourth kind is read before the three that can become findings.** Nothing is stored
        // from one, so neither its hedge nor its subject is read — read through the two functions
        // above, a model writing either field oddly on a line nothing stores would cost the figure
        // this answer exists to produce and report it as the parser having drifted.
        //
        // Placement is still tested, because it decides the same thing it decides for the other
        // three: a fact the model took from a context line is a fact about a record this sweep
        // neither selected nor marks, and counting it here would fill the figure with findings
        // from messages that were never being read.
        return when {
            fields[KIND].equals(UNLISTED, ignoreCase = true) ->
                if (placed) Reading.Unlisted else Reading.Refused

            kind == null || hedged == null -> Reading.Unreadable
            placed && established -> Reading.Read(Candidate(kind, target, window.conversation, hedged))
            else -> Reading.Refused
        }
    }

    /**
     * What one classification line came to.
     *
     * **Four cases rather than a nullable candidate, because three of them need different
     * handling.** [Unreadable] says the model answered in a shape this build does not know, which
     * on a stored line means the build changed underneath a submission; [Refused] says the line was
     * read and a rule declined it, which is the ordinary course of events; [Unlisted] says the model
     * named a fact the closed vocabulary does not cover, which is the answer the instruction asks
     * for. Counted together the routine two would hide the total one — see [found].
     */
    private sealed interface Reading {

        /** The line is in a shape this cannot parse: a field count, a kind or a hedge it has no reader for. */
        data object Unreadable : Reading

        /** The line was read, and a rule declined to turn it into a candidate. */
        data object Refused : Reading

        /**
         * The line named the fourth kind: a fact outside the closed vocabulary.
         *
         * Apart from [Refused], though neither becomes a candidate and neither is a loss. This one
         * is the instructed answer for material the attribute path cannot label, so it is the only
         * figure that says how often that happens; counted with the rules about placement and
         * subject it would be buried under a share that moves whenever a conversation grows.
         */
        data object Unlisted : Reading

        data class Read(val candidate: Candidate) : Reading
    }

    /**
     * One finding's restatement: the window it was found in, then which finding to restate.
     *
     * **The window comes first and the finding that selects it last, which is the opposite of how
     * a reader would write it.** Every finding of one window is restated by a request of its own,
     * so the window travels once per finding where the instruction travels once per submission —
     * it is by far the largest thing two sibling requests have in common. Caching matches a
     * prefix, so a shared body only pays if it precedes what varies; written the natural way
     * round, with the kind and the identifier first, two requests about one window would share
     * only the instruction above and the window would be paid for in full every time.
     *
     * The instruction says the same thing in prose, and it has to: the model is told the last line
     * names the message, so the two move together or the model reads a message identifier as a
     * message.
     */
    private fun restatement(rendered: String, candidate: Candidate) = ModelRequest(
        system = REWRITE_SYSTEM,
        shared = rendered,
        prompt = "${candidate.kind.name.lowercase()}$FIELD${candidate.message.externalId.nativeId}",
        maxTokens = maxTokens,
        effort = REWRITE_EFFORT,
    )

    /**
     * A call whose only purpose is to leave the restatement prefix in the provider's cache.
     *
     * **Because a batch cannot be relied on to read its own writes.** The requests of one
     * submission are answered concurrently and in any order, and an entry becomes readable only
     * once the first response using it has begun — so the siblings that share a window cannot be
     * counted on to read what one of them writes. An entry written by an ordinary call *before*
     * the submission goes over can be. Asking the batch endpoint to warm itself is not an option
     * either: the shape that would do it cheaply, a request for no output at all, is refused there.
     *
     * The prefix is [REWRITE_SYSTEM] and the window, byte for byte as a restatement sends them,
     * because a prefix is shared only up to the first byte that differs. What follows the
     * breakpoint is as short as a request may be: the reply is discarded, so anything spent past
     * the prefix is spent on nothing. The lowest effort for the same reason — the point is that
     * the window was read, not that anything was concluded from it.
     *
     * **It asks at the restatement's own effort, and that is load-bearing rather than tidy.**
     * Changing the effort invalidates the message blocks of a cached prefix, and the breakpoint
     * for a shared region sits in the user turn — so a warming call at any other effort writes an
     * entry its siblings cannot read, and the whole of what it bought is a write at twice the
     * input rate. The ceiling on its output may differ, because that is not part of what the
     * provider matches on.
     *
     * **It is a real call and is billed like one**, which is the whole of the trade: one window's
     * input at the write rate, against its siblings reading the same window at a tenth of the
     * input rate. At the measured shape — a window the larger part of each request, and about
     * seven requests to a window — that is worth doing, and it is worth measuring rather than
     * assuming, which is what the cache counters on a collection are for.
     */
    fun warming(window: String) = ModelRequest(
        system = REWRITE_SYSTEM,
        shared = window,
        prompt = WARM,
        maxTokens = WARM_MAX_TOKENS,
        effort = REWRITE_EFFORT,
    )

    /** A classified candidate, before anything has been restated. */
    private data class Candidate(
        val kind: ProposalKind,
        val message: Message,
        val conversation: ExternalId,
        val hedged: Boolean,
    ) {

        fun proposal(claim: Claim) = Proposal(
            kind = kind,
            claim = claim.text,
            conversation = conversation,
            evidence = Evidence(
                // Ordinal zero: a message is one span, as the index holds it. A proposal and a
                // retrieved hit therefore name the span the same way, which is what lets a
                // citation be checked by comparing values.
                span = SpanId(kind = RecordKind.MESSAGE, record = message.externalId),
                sourceTime = message.sourceTime,
            ),
            hedged = hedged,
            dated = claim.dated,
            about = message.author,
            predicate = claim.predicate,
            obliged = claim.obliged,
        )
    }

    /**
     * The second stage's answer: the restated claim, the date it resolved, the predicate, and
     * which party the thing obliges.
     */
    private data class Claim(
        val text: String,
        val dated: Instant?,
        val predicate: Predicate? = null,
        val obliged: ExternalId? = null,
    )

    /**
     * One line of a restatement's reply, read but not yet folded in with its siblings.
     *
     * [claim] is the reading that line gives up, or `null` where it gives up none, and [refusal] is
     * the date it gave up where it gave one up. Both belong to the line and travel on with it.
     *
     * [text], [field] and [misshapen] are what the *reply's* own failure is named from, and only
     * the opening line's are ever read — see [restated], where a later line may add a finding but
     * never change what the reply failed for. [text] is the claim field as it arrived, which
     * decides whether the predicate field says anything at all; [field] is which of the four
     * answers that field held, of which one is a legal entry and the rest divide between a refusal
     * and a line that would not parse; [misshapen] is a legal entry over a value of the wrong sort.
     * All three travel per line because the parse is per line.
     */
    private data class Line(
        val claim: Claim?,
        val text: String?,
        val field: PredicateField?,
        val misshapen: Boolean,
        val refusal: Restatement.DateRefusal?,
    )

    internal companion object {

        /**
         * The ceiling on either call's output.
         *
         * Thinking counts against it, so this is not sized to the few lines of answer: a value
         * that fits the prose alone truncates it and reports [ModelOutcome.Truncated], which this
         * class correctly refuses to read.
         */
        const val DEFAULT_MAX_TOKENS = 4000

        /**
         * Cheaper than the restatement, because finding which lines state a promise is a
         * judgement over text already in front of the model rather than one to be reasoned
         * about. The budget for this build is a fixed credit, and this is the call that runs
         * over every window.
         *
         * Both efforts are in [version], so changing either re-derives what it would now read
         * differently.
         */
        val CLASSIFY_EFFORT = Effort.MEDIUM

        /** The judgement-heavy half: a relative date to resolve, and who owes what to whom. */
        val REWRITE_EFFORT = Effort.HIGH

        const val FIELD = "|"

        /**
         * What a warming call asks, which is as little as a request may carry.
         *
         * Not a restatement: it names no message, so the model has nothing to restate and the
         * reply is discarded unread. What matters is only that the prefix in front of it was read.
         * It is also what tells a warming call from a real one in a log or a test.
         */
        const val WARM = "none"

        /**
         * The ceiling on a warming call's output.
         *
         * Small, because the reply is thrown away and thinking counts against this — so the figure
         * is what the call is permitted to waste rather than what it needs. A truncated answer is
         * the expected outcome and costs nothing that matters: the prefix is cached by having been
         * read, whatever became of the output.
         */
        const val WARM_MAX_TOKENS = 64

        /** The classification's fields: the kind, the message, the hedge, and the subject. */
        const val CLASSIFY_FIELDS = 4

        /**
         * Where each of those fields sits on the line.
         *
         * Named rather than counted at the point of use, because the names are what tie the
         * parser to the instruction that asks for them in this order — a reader checking the two
         * against each other has nothing else to go on.
         */
        private const val KIND = 0
        private const val MESSAGE = 1
        private const val HEDGE = 2
        private const val SUBJECT = 3

        /**
         * What each entry of the vocabulary takes as its value.
         *
         * **The list and the gloss are one declaration, because an entry with no gloss is worse
         * than no gloss at all.** Both instructions carry this rather than the bare names: a
         * relation named and not described leaves the sort of thing it takes to be inferred, and
         * the fault this was written for is a value of the wrong sort filed under a name that
         * parsed. Written as two declarations, an entry added to the vocabulary would reach the
         * prompts unglossed and be the one entry a model had nothing but its name to go on for.
         *
         * Added to or reworded, this moves [version] with the prompts it is interpolated into, so
         * the corpus inside the horizon is read again under the new wording.
         */
        private val TAKES = mapOf(
            "works at" to "the name of an employer",
            "has the role" to "a job title",
            "is based in" to "a town, a city or a country",
            "charges" to "a rate or a fee",
        )

        /**
         * The predicates an attribute may assert, and the whole of them.
         *
         * **A statement is keyed by its predicate, so an open vocabulary is a silent data
         * defect.** Resolution groups readings by subject and predicate to decide which of them
         * contradict each other, so two spellings of one idea are two series that are never
         * compared: a rate quoted in March and revised in June closes the earlier window only if
         * both arrived under the same name. `works at` against `employed by` leaves the superseded
         * fact standing for ever — present, correct-looking, and never contradicted. A predicate
         * the model wrote in the conversation's own language is the same fault, reached by the
         * likelier route: the instruction to write the *claim* in that language invites it.
         *
         * [Predicate] deliberately stays open text, because closing it there would mean a prompt
         * naming a new relation could not be stored until the domain was rebuilt. This is the
         * other side of that decision: the line is held here, where the vocabulary is chosen, and
         * an attribute no entry fits is lost rather than keyed under a name nothing queries.
         * Losing one is reported, and under which figure depends on how the model answered: the
         * refusal word the instruction asks for is [Restatement.Unused.UNNAMED], and a stretched
         * word is [Restatement.Unused.OUTSIDE]. The first is the nearest thing a sweep has to a
         * signal about the width, though it cannot be told from a classification that was wrong to
         * call the thing an attribute.
         *
         * **Neither sees the narrower case, which is the kind the classification stage names
         * [UNLISTED].** Obeyed, it is [Candidacy.Found.unlisted] — a fact the stage met, could not
         * label, and reported as such, which is the only figure in this build that measures how
         * often that happens at all. Disobeyed, what follows turns on the kind the model gives it
         * instead. Called an attribute, two rules stand in the way rather than one: the subject
         * rule, which refuses a fact named as being about something other than the author, and the
         * value test, which refuses a value of a sort no entry takes however the subject was named
         * — see [machineShaped] for what that can and cannot catch. Called a commitment or an
         * event, no subject is checked and nothing tests a value, so it becomes a row of that kind.
         *
         * **No figure isolates the width even so.** What [Candidacy.Found.unlisted] counts is
         * material arriving that the list does not cover, which is not the same question: a
         * vocabulary too narrow and a corpus that genuinely holds other facts read as the same
         * number, and only the corpus says which. What the figure settles is whether any such
         * material arrives at all, where before there was nothing to read.
         *
         * These are what the request shapes ask about a business contact — where they work, what
         * they do, where they are, and what they charge. Contact details are
         * absent on purpose: an address or a telephone number is captured as a claim on the person
         * record and matched by the identity stage, so a predicate for one would be a second,
         * weaker copy of something already structural.
         *
         * **The list reaches [CLASSIFY_SYSTEM] and [REWRITE_SYSTEM] as [VALUES] and is enforced by
         * [predicateField], and it has to be the same list in all three.** The classification
         * stage carries it because that is where a thing is decided to be an attribute at all;
         * deciding that without the list in view is how a fact the list does not describe comes
         * to be filed under whichever entry parses. A prompt offering a word the parser drops is a
         * call paid for and thrown away; a parser accepting one the prompt never offered is the
         * open vocabulary again, arriving by the back door.
         *
         * Changing it is a re-derivation and not a migration, because it moves [version] — so the
         * corpus inside the horizon is read again under the new list. What that does not do is
         * revisit statements already keyed under a retired predicate: the only write that removes
         * one replaces it under the same subject and predicate, and no later reading will ever
         * carry that name — so they stay, with open validity windows that nothing will ever close.
         * Removing an entry is therefore the expensive direction, and adding one is cheap.
         */
        val PREDICATES = TAKES.keys.toList()

        /** The same list as the store keys by, so membership is tested after normalising. */
        private val VOCABULARY = PREDICATES.map(Predicate::of).toSet()

        /**
         * The vocabulary as a sentence, which is the form both instructions interpolate.
         *
         * **One line and not four, because a multi-line value breaks the block it lands in.**
         * `trimIndent` runs over the interpolated string, so a value carrying newlines of its own
         * contributes unindented lines, the common prefix it strips falls to nothing, and every
         * other line of the instruction keeps the indentation of this source file.
         */
        val VALUES = TAKES.entries.joinToString("; ") { "${it.key} takes ${it.value}" }

        /** The restatement's fields: the date, the predicate, the obliged party, and the claim. */
        const val REWRITE_FIELDS = 4

        /**
         * The answer format the restatement instruction states, as one declaration.
         *
         * **Interpolated into the instruction and refused by the parser, because a model echoes it
         * back.** Asked for a line per obliged party, it sometimes writes the header above its
         * answer — and that line has the field count, so nothing about its shape tells it from a
         * finding: the claim field reads `claim`, the party field reads `party` and resolves to
         * nobody, and what would be stored is a commitment whose claim is the word claim.
         *
         * **Harmless while a reply was one line and not afterwards, which is why the rail arrives
         * with the split.** Read as the single answer it cost one garbage finding; read as the
         * opening of several it costs two, because it obliges nobody and the party rule then drops
         * the real obligation that also obliges nobody. So the echo would displace a finding rather
         * than merely adding one.
         *
         * One declaration for the prompt and the parser, so the line the instruction asks for and
         * the line the parser refuses cannot drift apart. The field count beside it is held to this
         * by its own case in `ExtractorTest`.
         *
         * **The classification stage's header is not treated this way**, and deliberately: a line
         * echoing it names no kind this build reads, so it is already counted as the drift it is
         * rather than stored — and that figure is worth keeping rather than filtering away.
         */
        val REWRITE_FORMAT = listOf("date", "predicate", "party", "claim").joinToString(FIELD)

        /**
         * Where each of those fields sits on the line.
         *
         * Named for the same reason the classification's are, and the party is third rather than
         * last for the reason the author is third on a rendered line: the free text goes last, so
         * a delimiter inside a claim cannot shift a field.
         */
        private const val DATE = 0
        private const val PREDICATE = 1
        private const val PARTY = 2
        private const val CLAIM = 3

        /** What the model answers when a window holds none of the four. */
        const val NOTHING = "none"

        /**
         * What the model answers for a fact stated about a subject that no predicate asserts.
         *
         * **A fourth kind, and nothing is stored from one.** The three the pass writes are decided
         * with the closed vocabulary in view, so a fact that is attribute-shaped and outside it left
         * the stage two moves and no third: call it an attribute anyway and let the next stage pick
         * whichever entry parses, or report nothing and be indistinguishable from a window that held
         * nothing. The first writes a value no predicate takes; the second is byte-identical to the
         * stage working, so neither the log nor the store could tell the two apart.
         *
         * So this is an answer rather than a silence: the line is read, refused for being outside
         * the vocabulary, and counted. What it buys is [Candidacy.Found.unlisted] — the first figure
         * in this build that says how often the attribute path meets material it cannot label, and
         * therefore the first evidence of what the path's silence means.
         *
         * **It is also the affirmative half of an instruction that was only ever a prohibition.**
         * Concluding that none of four candidates applies is the reasoning shape a model handles
         * worst, and a bare negation of the candidate set is the weakest form of it; a named outcome
         * to report is the strongest the instruction can be without a fifth entry nothing queries.
         */
        const val UNLISTED = "unlisted"

        /**
         * Whether [value] is a shape no entry of the vocabulary can take.
         *
         * **The backstop, and deliberately not a second reading of the instruction.** The prompts
         * say what each entry takes; this tests what arrived, so it holds whatever a later prompt
         * edit does to the behaviour — which is the failure it was written for, a system fact
         * reaching the store as a person's location after two unrelated prompt changes moved a
         * verdict the prose had always forbidden.
         *
         * **Three shapes, and deliberately not a hostname among them.** A scheme, a leading path
         * segment and a dotted quad are values no town, job title, employer or rate can be, in any
         * script and whatever their capitalisation, so refusing them costs nothing that was ever
         * going to be stored.
         *
         * **A bare dotted host is not tested, because no pattern can test it here.** A host and a
         * place name are one shape — a dotted token of letters — so a pattern wide enough to catch
         * `stage.example.internal` also refuses `St.Petersburg` and `Sr.Developer`, while one
         * narrow enough to spare those misses every host written in the corpus's own script. The
         * two classes coincide rather than merely overlapping: a rail of that kind was tried here,
         * measured wrong in both directions, and removed for it. What covers that case instead is
         * the classification stage naming [UNLISTED], which reads the fact and not its shape.
         *
         * So a zero here says none of these three shapes arrived, and never that no wrong value
         * did.
         */
        fun machineShaped(value: String): Boolean {
            val text = value.trim()
            return SCHEMED.matches(text) || PATHED.matches(text) || NUMBERED.matches(text)
        }

        /** A value carrying a URL scheme, which names a service and not a person's anything. */
        private val SCHEMED = Regex("""^[A-Za-z][A-Za-z\d+.\-]*://.*$""")

        /** A filesystem path, absolute or relative to a home or a directory. */
        private val PATHED = Regex("""^(~|\.{1,2})?/.*$""")

        /** A dotted quad, with an optional port and path after it. */
        private val NUMBERED = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")

        /**
         * The first stage's standing instructions.
         *
         * Byte-stable across every call, which is the precondition for the cache breakpoint the
         * adapter puts at the end of it. Interpolating anything per call — a date, an identifier —
         * would move the cached prefix's contents and cost the saving rather than take it.
         *
         * **A commitment is required to have a party who is to do the thing, and that is the half
         * the kind's own definition cannot carry.** Saying the kind holds whoever performs the
         * thing is what admits an obligation the speaker places on somebody else; read on its own
         * it also admits a sentence placing the work on nobody at all — work stated impersonally
         * as needing doing, and a decision to defer with nothing said about who then acts. Those
         * reach a status answer as obligations somebody owes, which is worse than an omission: a
         * reader chases work nobody undertook and that may already be settled.
         *
         * **What such a sentence becomes is named, and what it does not become is named too.** A
         * bare negation of the candidate set is the weakest form of an instruction — see
         * [UNLISTED], which exists because of it — and this rule cannot do better than one for the
         * refused shapes themselves: nothing is stored from them and no figure counts them, so a
         * window refused here comes back as the word for nothing and is settled like any other
         * window holding none of the four. What it can do, and does, is close the two routes a
         * refused sentence would otherwise take. The event route is left open where the message
         * does report an occurrence, in the terms the event paragraph states and not narrower ones
         * — a condition stated twice and unequally is what makes a kind unreliable, which is the
         * fault the paragraph below this one describes. The fourth kind is closed outright: a
         * figure that collected unowned work would stop measuring what it is there to measure,
         * which is material the closed vocabulary cannot label.
         *
         * **The rule is keyed on there being nobody to do the thing and never on there being no
         * name for them**, which is the one way it could cost a sound reading. The restating stage
         * already answers `none` for a party no line labels, and nothing downstream requires a
         * commitment to resolve one — so a commitment whose party cannot be named is a commitment,
         * and the instruction's second paragraph says so rather than leaving the model to infer it.
         * A rule refusing every unnamed party would discard a sound reading of that shape along
         * with the spurious ones.
         *
         * The deferral half is scoped the same way, to a decision with nobody taking the thing on
         * afterwards: a speaker who puts something off and says who does it next has made a
         * commitment, and a rule keyed on the deferring itself would lose it. The clause refusing
         * such a sentence however firm a time it carries is what settles it against the deadline
         * rule two paragraphs below, which sends a date something is wanted by to a commitment —
         * scoped, in its turn, to where somebody is to do the thing.
         *
         * **The event kind is stated once, and the single statement is what makes it reliable.**
         * Given twice and incompatibly — a kind list requiring a stated time against a paragraph
         * calling an event something that took place — an occurrence a message reports without
         * saying when satisfies one telling and not the other, and nothing in the instruction
         * says which wins, so the model settles it per call: one construction is read as an event
         * and the same construction elsewhere as nothing. Ranking the two cannot repair it, the
         * ordering being statable only here, where the model never reads. So the kind list
         * carries no time requirement and the paragraph below elaborates that rather than
         * overriding it.
         *
         * **Its exclusion is there because the recall is otherwise bought with precision**, and
         * it says that excluding something reclassifies nothing. Told only what is not an event,
         * the model looks for the next closest kind and finds the commitment, which this rule has
         * no measurement to move: the sentence is what holds the other three still. The rates for
         * both halves are in the issue this was filed as, which is where a rate belongs.
         *
         * **A dateless event is this stage's to produce and the comparison stage's to describe,
         * and only one of them says so.** [ADJUDICATE_SYSTEM] still glosses the kind as something
         * happening at a stated time, and is deliberately left doing so: that string is hashed
         * into [adjudicationVersion] and nothing else, so rewording the gloss retires every
         * verdict already stored under it — a cost nothing here measures. The gloss heads a
         * three-way choice of what is being compared rather than stating a condition, and the
         * comparison's own instruction tells the model to answer that two things differ where it
         * cannot tell, so a pair this stage now produces more of is at worst kept apart. Named
         * because one decision held in two instructions is a fault class in this file, and the
         * same wording in `V11__inference.sql`'s column comment is uneditable for a third reason:
         * Flyway verifies checksums.
         */
        val CLASSIFY_SYSTEM = """
            You read one message of a business conversation and find four kinds of thing stated in
            it: a commitment, where someone states that something will be done, whoever is to do
            it; an event, which is something that happened or is to happen; an attribute, which is
            one of the facts on this closed list about a person or a company:

            $VALUES

            and $UNLISTED, which is what would be an attribute if the list covered it: a fact
            stated about a person, a company or a thing that none of those entries describes.

            Each entry of that list takes a value of one definite sort, so a fact whose value is of
            another sort — a machine, a service, a setting, a document — is $UNLISTED, and that is
            what to report it as rather than reaching for whichever entry the fact resembles.
            $UNLISTED is for a fact of that shape and for nothing else a message happens to say,
            so a message stating no fact about anyone or anything states none of the four.

            A commitment is a commitment whoever is to do it. Where the speaker states what another
            participant is to do — agreeing it for them, or saying what that participant owes and
            by when — that is a commitment rather than an event.

            Either way a commitment has a party who is to do the thing, and a message that states
            none has no commitment in it. Two shapes state work and no party: work described
            impersonally as needing doing, with nobody said to be doing it, and a decision to put
            something off or to drop it, with nobody said to be taking it on afterwards. Нужно
            обновить сертификат and Раскатку перенести на следующую неделю are those two shapes —
            each states work and nobody to do it, so neither is a commitment, however definite the
            work is and however firm a time it carries. Each is none of the four, unless what the
            message states is one of the others, and one of the others is in reach: an occurrence
            the message reports as having happened is an event, whoever acted on it. Work nobody
            undertook is not $UNLISTED either — that is for a fact stated about a person, a company
            or a thing, and work waiting to be done is not a fact of that sort.

            That is about there being nobody to do the thing, and never about how briefly the
            message puts it or whether the party is one you can name. A sender saying they will see
            to something has undertaken it, however short the message and whether or not the
            conversation gives you a name for them: посмотрю is a commitment. So is a thing the
            sender tells another participant to do, and a thing the sender says that participant
            will do. Accepting a way of doing something that was put to the sender is the sender
            undertaking it, where the thing accepted is theirs to do. What the two shapes above
            lack is anybody at all who is to do the thing.

            An event is the occasion itself — a meeting, a trip, a visit, something that took
            place — and not something somebody undertook to do, however definite the time attached
            to it. A deadline, or a date something is wanted by, belongs to a commitment where
            somebody is to do the thing, whether the speaker took it on or it falls on somebody
            else.

            A time is not required of an event, and neither is the occasion resembling anything
            anybody arranged: something the message reports as having already happened is one
            whether or not the message says when. That holds for the report that an undertaking
            was carried out, which is the occurrence and not the undertaking excluded above. What
            this does not make an event of is anything the speaker does not report as having
            happened — something being asked about, something wanted, expected or guessed at, and
            a view taken on an occurrence rather than the occurrence itself. Excluding one here is
            no reason to read it as another kind: whether it is a commitment is settled above on
            its own terms, and a message stating none of the four states none of them.

            The first line you are given is the identifier of the message to read. The lines after
            it are that message and the conversation around it, one message per line, as fields
            separated by |: its identifier, when it was sent, who sent it, and its text. The
            conversation may be in any language.

            The third field is what to call that participant. It is empty where the sender has no
            name on record: then that participant has no label, and you do not invent one. Where
            you name a party, write the label from that party's line exactly as it appears there,
            in whatever script it is written, never translated and never shortened.

            The lines around the message to read are context and nothing else. They are there so
            that you can tell what it refers to and whether anybody took it up. Report only what
            the message to read states itself, however plainly a neighbouring line states
            something of its own.

            Answer with one line per thing you find, and nothing else:

            kind|message|hedge|subject

            kind is commitment, event, attribute or $UNLISTED.
            message is the identifier of the message to read, copied exactly from the first line.
            It is the only identifier that may appear here.
            hedge is about how far the thing can be relied on, and two separate conditions make
            it hedged. Either is enough.

            The speaker is unsure of it — something that might happen, probably will, or will if
            there is time; возможно, наверное, если будет время.
            Or the speaker does not have it first hand: they are passing on what they were told,
            which they cannot settle on the original speaker's behalf however firmly they put it.

            Write plain where the speaker states the thing as their own knowledge or their own
            undertaking, without hedging it. That covers something a speaker reports about another
            person from their own knowledge — a colleague's deadline, known to someone who works
            with them — which is plain, not hedged.

            Grammatical person is a clue to the second condition and not the test for it. A
            first-person sentence is hedged where its own wording hedges it, and a third-person
            one is plain where the speaker knows the thing rather than having heard it.

            subject is who an attribute is about, and only an attribute stated about the sender of
            the message to read is recorded at all. So report an attribute only where that sender
            is describing themselves, and write their own label, copied exactly, as the subject.
            Where one of the facts on the list is stated about another participant or about a
            company, or about a sender who has no label, there is no subject to record it against,
            so leave it out.

            Write none for a commitment, for an event and for $UNLISTED, none of which asserts
            anything about a subject this records. Write plain as the hedge of $UNLISTED: nothing
            is recorded from one, so how far it can be relied on is not read.

            Answer with the single word none if the message to read states none of the four.
            Report a thing once, even where it is discussed across several messages.
        """.trimIndent()

        /**
         * The second stage's standing instructions, byte-stable for the same reason.
         *
         * **Self-containment is what this stage is for, and it is also what widens a claim.** A
         * restatement is asked to stand without the conversation in front of the reader, so the
         * model supplies whatever the sentence needs to read whole — and a qualifier that bounds
         * the thing reads like a dependence on context rather than like part of what was
         * promised. Dropped, it produces a claim that is fluent, correctly attributed, correctly
         * dated and broader than anything anybody said: a promise about one tool restated as a
         * promise about how somebody works. Nothing downstream can recover it. The answer path
         * is handed the claim, not the window it was read in, and the turns carrying the bound
         * are routinely older than the tail any shape retrieves — so the rule has to be here,
         * where the window is still in front of the model, and it is stated as the distinction
         * the error is made on rather than as a general caution about accuracy.
         *
         * **It asks for the narrower reading where the bound cannot be told**, which is the
         * direction a reader can recover from: a claim too narrow cites the evidence that would
         * widen it, while a claim too wide cites evidence that does not support it and reads as
         * settled. Not recoverable by a later restatement, which is worth saying because it
         * would be the obvious hope — [io.github.ragnorrs.mainbrain.domain.entity.FindingPair] makes the
         * earliest reading the survivor and merges a later one onto it, so a second pass over
         * the same obligation never rewrites the stored claim. Only a re-derivation does, and
         * that re-reads the same message rather than a later one.
         *
         * **Scoped to a commitment and an event, because an attribute's claim is the value
         * alone** — see the paragraph above it, which says so, and [TAKES], which declares the
         * sort each predicate's value must be. A qualifier written into an attribute's claim is
         * a value of the wrong sort, which the parser refuses, so a rule inviting one would cost
         * the finding. The measurement behind this rule was taken on commitments, and the bound
         * it is scoped to is the one it was measured on.
         */
        val REWRITE_SYSTEM = """
            You restate one thing that was read out of a business conversation, so that it can be
            understood on its own.

            You are given the conversation first, one message per line as its identifier, when it
            was sent, who sent it, and its text. The last line you are given is not a message: it
            is the kind, and then the identifier of the message to restate. The thing to restate is
            stated in the message that last line names; the others are context. The third field is
            empty where that sender has no name on record.

            Answer with one line for each party the thing obliges, and nothing else:

            $REWRITE_FORMAT

            Where the message states that one participant is to do one thing and another
            participant is to do another, both are obliged and each takes a line of its own, naming
            its own party and restating that party's part. Everything a single participant is to do
            is one party and one line, however many things it covers; an event and an attribute
            oblige nobody, so each of those is one line always.

            date is when the commitment is due, or when the event happens, and only where the
            message says so. Most commitments are stated without one, so none is the ordinary
            answer and the one to write whenever the message leaves the timing open. Do not choose
            a date because one would be reasonable: a date you can work out from what was said is
            stated, and a date you would have to assume is not.

            Where there is one, write it as an ISO-8601 instant in UTC, and resolve a relative date
            — tomorrow, next Friday — against the instant of the message it is stated in, which is
            on that message's line. Never against today. So for a message sent at
            2026-10-06T14:20:00Z, a promise to send something tomorrow has the date
            2026-10-07T00:00:00Z, and a promise to send it soon has the date none.

            Never answer the message's own send time copied from its line, whichever kind you are
            restating. That instant says when the thing was said, not when it comes due or when it
            happens — so something undertaken or happening straight away has no date to give, and
            takes none.

            A promise the sender makes themselves is never due before its own message. Where the
            party field names the sender and the timing you worked out stands earlier than the
            message was sent, write none rather than a date in the past or a date moved forward to
            fit. Where the party field names another participant, a time already behind is the
            ordinary case — their deadline is usually mentioned once it has been missed — so write
            the time the message states and do not move it. Where the party field is none, write
            none for a time already behind as well: nothing there says whose obligation it is, and a
            timing that cannot be placed is not recorded. An event's date may well be earlier too:
            something that has already happened is reported after the fact, and it keeps the date it
            happened on.

            For an attribute the field does not apply at all: write none.

            predicate names what an attribute asserts. It must be exactly one of these names,
            copied as written and with nothing added to it:

            ${PREDICATES.joinToString(", ")}

            Each of them takes a value of one definite sort: $VALUES. Write the name whose value
            the claim you are about to write actually is, and only where it is of that sort: the
            claim for an attribute is that value and nothing else, so the two answers stand or
            fall together.

            Write none for a commitment, for an event, and for an attribute that none of them
            fits — a fact outside this list is not recorded at all, so do not stretch one to
            cover it. These are English and stay English whatever language the conversation is
            in; the claim is the only part that is in the conversation's language, and a party's
            label inside it keeps the script it is written in.

            party is who a commitment obliges — the party who is to do the thing, which is not
            always the sender. Write the label from that party's own line, copied exactly as it
            appears in the third field there, in whatever script it is written. That is the
            sender's own label where the sender undertook the thing themselves, and another
            participant's label where the sender said what that participant owes. Write none where
            no line you are given carries that party's label: somebody who did not write in this
            conversation, or a sender whose third field is empty. Never invent a label and never
            take one from the words of a message rather than from a line's third field. Write none
            for an event and for an attribute, which oblige nobody.

            claim is in the language the conversation is in. For a commitment or an event it is one
            sentence naming who owes what to whom, or what happens and when. Where you are writing
            a line for each obliged party, that line's claim says what the party its own party
            field names is to do, and not what the other is. Name a party by the
            label in the third field of their line, copied exactly as it appears there. Where that
            field is empty the party has no name you may use: say what was undertaken without
            naming them, and do not invent a name or a number for them. For an attribute it is the
            value alone, with the predicate left out: for works at, the name of the company and
            nothing else.

            A label is not prose and is not translated along with it. Copy it
            character for character, in the script it is written in, even where every other word of
            your sentence is in another language, and never transliterate it, translate it, or
            shorten it to a first name. So for a conversation in Russian whose third field reads
            Westvale Freight, the claim carries Westvale Freight, and never Вествейл Фрейт, which
            is a different label and matches nothing.

            Either way the claim must be understandable with as little else in front of the reader
            as the conversation allows: no "this", no "it", no reference to what was discussed
            earlier, and no pronoun standing for a person whose line gives you their label.

            For a commitment or an event, standing on its own is not the same as being general,
            and the second is a different claim from the one that was made. Keep whatever bounds
            the thing — what it was said about, the tool or the place or the piece of work it
            applies to, the condition attached to it — even where the sentence reads more
            cleanly without it. Somebody agreeing to use a language with a piece of software has
            not agreed to use it with people, and a promise about one thing restated without its
            bound becomes a promise about everything, which is a promise nobody made. Where the
            bound is what the surrounding lines supply, write it in: that is the context being
            used for what it is for. Where you cannot tell what bounds it, restate the narrower
            reading rather than the wider one. None of this reaches an attribute, whose claim is
            the value alone and takes no qualifier.
        """.trimIndent()

        /**
         * What a comparison of two attribute values is headed by, before the relation.
         *
         * The kind's own name, so that the instruction and the three things it may be handed line
         * up with [ProposalKind] rather than with a word chosen here.
         */
        val ATTRIBUTE = ProposalKind.ATTRIBUTE.name.lowercase()

        /**
         * The third stage's standing instructions, byte-stable for the same reason as the others.
         *
         * **Nothing about a conversation is in the prompt this heads**, which is what makes the
         * stage affordable: a claim was restated to stand on its own, so the comparison is over two
         * sentences and the instruction has to be written for exactly that.
         */
        val ADJUDICATE_SYSTEM = """
            You are given two pieces of text that were each read out of a business conversation,
            and you decide whether they state the same thing or two different things.

            The first line says what you are comparing. The second and third lines are the two
            pieces of text. They may be in any language, and the two may be in different
            languages.

            The first line is one of:

            commitment — both lines state something someone said would be done, by whoever is to
            do it, so two obligations falling on different parties are two commitments.
            event — both lines state something happening at a stated time.
            $ATTRIBUTE$FIELD followed by a relation, such as $ATTRIBUTE${FIELD}works at — both lines
            are values of that relation for one person or company. Each line is the value alone
            and not a sentence: for works at, the name of an employer; for charges, a rate.

            They state the same thing when they are the same undertaking, the same occasion, or the
            same value of the relation, however differently they are worded — a different phrasing,
            a different language, more or less detail, one naming a party the other leaves out, one
            giving a date or a number the other states in words. Wording is not the question.

            They state different things when they are two undertakings, two occasions, or two
            values, even where they are about the same subject, involve the same people, fall on
            the same day, or follow from one another. Two steps of one piece of work are two
            commitments. A promise and the report that it was done are two different things. Two
            amounts that are not the same amount are two values, however alike they read — a rate
            that was revised is the history of a rate and not a rewording of it.

            Answer with exactly one word and nothing else:

            $ONE_FACT if the two lines state the same thing.
            $TWO_FACTS if they state two different things.

            Answer $TWO_FACTS where you cannot tell. Two records kept apart can be read together by
            whoever asks; one thing wrongly merged into another cannot be told from a single
            reading.
        """.trimIndent()

        /**
         * The effort the comparison is asked at.
         *
         * Between the two others, and the reasoning is the input rather than the judgement: the
         * whole prompt is two sentences, so there is nothing to read closely and no date to resolve
         * — but deciding that two differently worded undertakings are one does take a judgement,
         * and at the lowest effort it reduces to comparing words, which is what the vector tier
         * already did and did better.
         */
        val ADJUDICATE_EFFORT = Effort.MEDIUM

        /**
         * What names the adjudication stage in [adjudicationVersion].
         *
         * A shape and a revision, as [Closer.DERIVATION] is: the revision is what moves where the
         * *meaning* of a verdict changes without the instruction being touched — a change to how
         * the pair is rendered in front of the model, a second question asked of the same prompt,
         * or a different thing stored from the answer. The instruction's own wording needs no help
         * from it, being hashed; everything else that decides the answer does, and the rendering is
         * the one that is easiest to move without noticing.
         */
        const val ADJUDICATION = "adjudication-1"

        /** What the model answers for a pair that states one thing. */
        const val ONE_FACT = "same"

        /** What it answers for a pair that states two, and for a pair it cannot tell apart. */
        const val TWO_FACTS = "different"

        /**
         * [this] with its newlines flattened, so that a claim occupies the one line it is given.
         *
         * A restated claim is meant to be one sentence and usually is, but nothing enforces it —
         * the model writes it — and a newline inside one would turn a three-line prompt into four
         * and make the second claim read as part of the first.
         */
        private fun String.oneLine(): String = lines().joinToString(" ") { it.trim() }.trim()

        /** The kind a classification line names, or `null` for anything this does not offer. */
        private fun kindOrNull(field: String?) = when (field?.lowercase()) {
            "commitment" -> ProposalKind.COMMITMENT
            "event" -> ProposalKind.EVENT
            "attribute" -> ProposalKind.ATTRIBUTE
            else -> null
        }

        /**
         * Whether a classification line marks its finding as hedged, or `null` where it says
         * neither — which is a line this cannot read rather than a finding to assume settled.
         */
        private fun hedgeOrNull(field: String?) = when (field?.lowercase()) {
            "hedged" -> true
            "plain" -> false
            else -> null
        }

        /**
         * The lines of a classification worth trying to read: not blank, not the refusal, and not
         * prose.
         *
         * **A line carrying no [FIELD] at all is commentary and not a finding in a shape this
         * cannot parse.** The model narrates its own refusal from time to time — it explains that
         * the sender has no label, and then answers `none` on a line of its own — and a reply in
         * that shape is the rule working, not a prompt and a parser that have drifted. Kept here it
         * was counted as unreadable, which is the figure reserved for the build being wrong; the
         * division those two figures exist to hold would then be inverted by the commonest thing
         * the model does.
         *
         * A line that *has* delimiters and the wrong number of fields is still unreadable, and that
         * is the case worth keeping: a model drifting its format emits fields, not sentences.
         */
        fun String.candidateLines(): List<String> =
            lines().map(String::trim).filter {
                it.isNotEmpty() && !it.equals(NOTHING, ignoreCase = true) && it.contains(FIELD)
            }

        /**
         * Whether the reply carries the refusal on a line of its own.
         *
         * The companion to [candidateLines] dropping prose: a reply of nothing but commentary and a
         * reply of commentary *and* `none` both yield no candidate lines, and only the second is an
         * answer. Without this the first would settle its record against a reply that never said so.
         */
        fun String.saysNothing(): Boolean =
            lines().any { it.trim().equals(NOTHING, ignoreCase = true) }

        /**
         * Which of the four a predicate field held.
         *
         * **The blank test comes first because it is a guard and not a classification**:
         * [Predicate.of] requires a non-blank name, so reaching it with an empty field throws out
         * of the parser. The other two are disjoint and their order carries nothing. See
         * [PredicateField] for why they are not one answer.
         */
        fun predicateField(field: String): PredicateField = when {
            field.isBlank() -> PredicateField.Blank
            field.equals(NOTHING, ignoreCase = true) -> PredicateField.Refused
            else ->
                Predicate.of(field)
                    .takeIf { it in VOCABULARY }
                    ?.let(PredicateField::Named)
                    ?: PredicateField.Outside
        }

        /** An instant, or `null` where the field holds no readable one. */
        fun instantOrNull(field: String): Instant? =
            if (field.equals(NOTHING, ignoreCase = true)) {
                null
            } else {
                // A date this cannot read costs the date and not the claim. A commitment with no
                // due date is the ordinary case — most are stated without one — so dropping the
                // whole proposal over an unreadable field would lose the thing for the sake of
                // the detail.
                try {
                    Instant.parse(field)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
    }
}

/**
 * What came of classifying one window: which findings it holds, and what to ask about each.
 *
 * **Sealed, so a caller cannot settle a window without first saying what happened to it.** The
 * three cases decide whether the one record the window reads may be marked, and they are three
 * rather than two because "not read" and "will never be read" need opposite handling. A window
 * classified to nothing is done. One cut short is still owed, and asking again may well answer. One
 * the model declined will be declined again — the port says a refusal is not retryable — so holding
 * it owed costs a call per sweep and, because selection is oldest-first, stops the pass advancing
 * past it.
 */
sealed interface Candidacy {

    /**
     * The classification was read, and these are the restatements it calls for.
     *
     * [asks] is empty for a window the model classified to nothing, which is the ordinary case and
     * not a failure: the record behind it is settled without a second call being made.
     *
     * [lost] counts the lines this build could not parse — a field count, a kind or a hedge it has
     * no reader for. It is the figure that says a prompt and a parser have drifted apart, which is
     * a total failure: nothing can be read from any reply until a build fixes it.
     *
     * [refused] counts the lines that were read and declined by a rule — a finding placed on a
     * context message, an attribute whose subject is not the author's own label. Routine, and
     * expected on most sweeps.
     *
     * **Separate figures and not one total, because the routine ones bury the rest.** They are
     * counted here rather than inferred later from how many replies came back, since none of them
     * asks for a restatement — but added together, a sweep's ordinary refusals would hide the
     * build-level failure completely.
     *
     * [repeated] counts the lines the model wrote more than once in one reply, every one of which is
     * still asked about. The instruction says to report a thing once and the model does not always,
     * but a line carries no claim — a kind, a message, a hedge and a subject — so two genuinely
     * different findings of one kind on one message are the same line, and dropping the second would
     * lose a finding the derived layer is built to keep. What a repeat costs instead is a duplicate
     * restatement at the higher of the two efforts, arriving at the write as an attempt the content
     * key folds away: a figure here is spend that was really made, which is the reason to report it
     * rather than to trade a finding for it. Counted apart from the collapse for the reason that
     * figure gives — that is resolution folding two readings of one fact, which is the pass working,
     * and this is a reply the model repeated itself in.
     */
    data class Found(
        val asks: List<Ask>,
        val lost: Int,
        val refused: Int = 0,
        val repeated: Int = 0,
        /**
         * Facts the model named as outside the closed vocabulary, which are reported and not
         * stored.
         *
         * **The figure that was missing, and the reason the fourth kind exists.** Before it, a
         * stage that correctly declined material it could not label and a stage that silently
         * dropped it produced the same observation — no line, no figure, no clause — so the
         * attribute path's silence could be read either way and this build read it as the first.
         * A figure here is the first evidence of which it is.
         *
         * **Not a loss and not a refusal.** Nothing was paid for beyond the classification that
         * found it, no restatement follows, and nothing about the build is wrong when it fires:
         * it is the instructed answer. Counted with [refused] it would sit under a share that
         * moves with the length of a conversation; counted with [lost] it would read as drift.
         */
        val unlisted: Int = 0,
    ) : Candidacy

    /**
     * The classification was not read and its window is still owed, because asking again may answer.
     *
     * [reason] names how it happened rather than only that it did, because they are not the same
     * failure and they do not all clear the same way. **The pass does not report it today:** it
     * counts these as unread and names only the declined ones, so a model that has started
     * truncating every window reads from outside like a corpus with nothing new in it. The reason
     * is carried so that whatever reports it needs no second look at the reply.
     */
    data class NotRead(val reason: String) : Candidacy

    /**
     * The model will not classify this window, and asking again will not change that.
     *
     * Reported apart from [NotRead] because the pass has to treat it as finished: the window is
     * marked and whatever it held is given up, which is a real loss and the reason [reason] is
     * carried out rather than swallowed. The alternative is a window re-selected and re-refused on
     * every sweep — at a model call each, and with the pass stuck behind it, since selection takes
     * the oldest owed records first.
     */
    data class Declined(val reason: String) : Candidacy
}

/**
 * One restatement waiting to be asked, as the request to make and the line that asked for it.
 *
 * **The line travels with the request because the reply cannot be read without it.** A restatement
 * comes back as a claim and nothing else — not which finding it restates, nor which message
 * evidences it — so what pairs it back up is the candidate it was asked about. Kept as the
 * classification's own line rather than as parsed parts, so that one parser defines what a finding
 * is; see [Extractor.restated].
 */
data class Ask(val line: String, val request: ModelRequest)

/**
 * One restatement's outcome: the findings there are, and why there are none where there are none.
 *
 * **No cost travels with it.** What the call was billed is on the outcome the collection already
 * holds, and the pass totals it there — before it looks for the window, so that a reply it can do
 * nothing with is still charged for. A figure here would be the same number counted twice, which
 * is the shape that lets two readers disagree about what a batch spent.
 *
 * [unreadable] names why a restatement was given up on, when it was given up on for being a reply
 * the provider could not read. `null` on every candidate where that did not happen, which is almost
 * all of them; carried because the condition is one only a new build clears, so without it the only
 * trace would be a candidate counted as lost beside the ordinary parse failures it is nothing like.
 */
data class Restatement(
    /**
     * What this restatement gave up: a finding per party the thing obliges, each with the date it
     * was refused where one was.
     *
     * **A list because one candidate can bind two parties**, and a row carries one obliged party:
     * a sentence putting one participant on the work and another on reviewing it is one thing the
     * classification reports and two obligations the store has to hold. Empty where the reply
     * produced no reading at all, which is what [unused] then says the reason for.
     *
     * At most one entry per party, which the invariant below holds to and [Extractor.restated]
     * states the reasoning for. An event and an attribute oblige nobody, so each of those has one
     * party and therefore one entry.
     *
     * **A refusal is carried on its finding rather than in a list beside them**, because the rule
     * is a pairing and not a count: a date given up for a line that produced nothing would be a
     * refusal reported for something never written, in a figure read as a rate over the findings
     * that were. Written as two lists, that rule is a bound on their lengths, which the case it
     * forbids satisfies.
     */
    val findings: List<Finding>,
    val unreadable: String? = null,
    /**
     * Why there is no finding, where there is none, and `null` where there is one.
     *
     * **An enumeration rather than a flag, because the caller reports each of these as a figure of
     * its own**, and the figures mean opposite things about whether anything needs fixing.
     */
    val unused: Unused? = null,
    /**
     * Lines the model wrote that bought no finding, because another line of the same reply had
     * already answered for their party or said the same thing in the same words.
     *
     * **The rate that says whether the division is being obeyed**, which is the one way this stage
     * can make the corpus worse rather than better: asked for a line per obliged party, the
     * cheapest way to obey on a sentence listing several things one participant will do is to
     * write a line each. Counted and not passed over, for the reason [Candidacy.Found.repeated]
     * counts the classification's equivalent — the call was paid for, and a rail that drops
     * something silently reads from outside exactly like a model that never wrote it.
     *
     * Both rails are one figure because they say the same thing about the reply: the model wrote a
     * line that added nothing. Which of the two refused it is not a question a sweep can act on
     * differently.
     */
    val repeated: Int = 0,
) {
    /**
     * The findings' proposals, which is what the pass writes.
     *
     * A view and not a field, so that nothing can hold a proposal this restatement's [findings] do
     * not.
     */
    val proposals: List<Proposal> get() = findings.map { it.proposal }

    /**
     * Why each date read off this reply was refused, and empty where every one was kept or there
     * was none.
     *
     * **Apart from [unused], because the finding survives a refused date.** Every entry there is a
     * reading that produced nothing, and this is a reading that produced a claim with its date
     * taken off it — so folding it in would either break the rule that a reason and a proposal
     * never travel together, or report a finding as lost that was written and is readable.
     *
     * Nothing is entered where there was no date, where the date stood after the message, and where
     * it stood before one on an obligation the window placed on another participant. A date refused
     * off a line that produced no finding cannot appear here at all, which is [findings]' doing
     * rather than a rule stated here. So it counts dates and not findings, which is the unit the
     * figures on [Inference] report it in — and a reply holding a line per obliged party can refuse
     * a date on one of them and keep one on another.
     */
    val datesRefused: List<DateRefusal> get() = findings.mapNotNull { it.dateRefused }

    init {
        require(findings.isEmpty() == (unused != null)) {
            "A restatement either produced a finding or says why it did not, never both and " +
                "never neither: a reason beside a finding is a figure reported for something " +
                "that was written, and neither is a finding lost with nothing naming where."
        }
        // **The party rule, held in the type rather than only where the lines are folded.** It is
        // the whole of what a reply with more than one line on it divides by, and the excess an
        // instruction to divide something invites is a single participant's undertakings split in
        // two. Two rows obliging one party are two readings of one obligation with nothing in the
        // store to tell them apart, which is the shape the collapse exists to settle and not one to
        // manufacture here.
        require(findings.distinctBy { it.proposal.obliged }.size == findings.size) {
            "Two of these findings oblige the same party, which is not a division a reply holding " +
                "a line per obliged party makes."
        }
        // **And the claim rule, which the party rule cannot stand in for.** A finding is keyed in
        // the store by its conversation and its claim, so two of them worded alike are one row
        // there whoever they oblige — and both carrying one message's instant, the write would keep
        // the later party and lose the earlier obligation under a figure that reads as a better
        // reading arriving.
        require(findings.distinctBy { it.proposal.claim }.size == findings.size) {
            "Two of these findings say the same thing in the same words, which the store keys as " +
                "one row: the second would take the first's party rather than stand beside it."
        }
    }

    /** One finding, with the date this reading gave up where it gave one up. */
    data class Finding(val proposal: Proposal, val dateRefused: DateRefusal? = null)

    /**
     * Why a date was refused.
     *
     * **Two cases rather than one figure, because they say different things about what to do
     * next.** One is a date the record itself rules out and the other is the field being unusable
     * half the time; added together, neither could be read for what it means — which is the rule
     * [Unused] states for the answers that are a word, one field along.
     */
    enum class DateRefusal {
        /**
         * A commitment the message's own author is obliged by fell due before that message.
         *
         * No reading puts a promise's due date earlier than the sentence making it, so this is the
         * model having answered from something other than what it was given, and the one case the
         * record can settle on its own. The rare one, where [SAME_INSTANT] is not.
         *
         * **A commitment only**, because an event is routinely reported after it happened and a
         * moment earlier than the mention is the ordinary reading of one.
         *
         * **And a commitment the author owes, because that is where the asymmetry holds.** An
         * obligation stated about another party carries a deadline that is usually already behind
         * when it is mentioned, so the date stands and nothing is counted here. Whose obligation it
         * is comes from the party field the restating stage answers, resolved against the window's
         * own labels.
         *
         * **What that leaves inside this figure, and it is the bound worth knowing.** Direction the
         * window could not settle is read as the author's own, so a past date on one of those is
         * refused and counted here too. That covers an obligation on somebody who never wrote a
         * line here, one on a participant whose name the label rule withdrew, and one read off a
         * message the corpus names no author for. None of them is the record contradicting itself:
         * the corpus may well have stated the timing. The figure is therefore a ceiling on that
         * contradiction rather than a measure of it, and what bounds the difference is how often a
         * window can place the obliged party at all — which is read off the stored rows.
         */
        BEFORE_MESSAGE,

        /**
         * The date was the evidencing message's own instant, to the second.
         *
         * **Read as no date stated, which gives up acting immediately as a date.** A promise to act
         * straight away resolves here legitimately, and so does something happening as it is
         * mentioned; nothing can tell either from the model echoing the one instant it was handed,
         * because they produce the same value. What settles it is the rate: most of the dated
         * findings measured arrived this way, so the field cannot be trusted at this value by
         * anything downstream and the defensible readings are the minority. What is given up is a
         * date nothing could rely on; the claim prose still says what was undertaken.
         *
         * The figures are in the issue this was filed as, which is where a rate belongs: restated
         * here it would be a tally nothing re-measures.
         */
        SAME_INSTANT,
    }

    /**
     * Why a restatement left no proposal.
     *
     * **The division that matters is routine against total.** A reading a rule declined, a model
     * that would not answer, a model answering the refusal word, a model stretching an entry and a
     * model answering a value of the wrong sort are the ordinary course of events, while a reply
     * nothing could parse and a stop reason with no
     * reader say a prompt and a parser have drifted apart. [FOREIGN] is on neither side: it is
     * this build's own data in another build's shape, and the record stays owed. Counted together,
     * the routine ones fire on most sweeps and hide the others completely — which is the rule
     * `Reading` states for the classification stage, and this is the same rule one stage on.
     *
     * Stated without a count of the cases, because the count is the part that goes stale and
     * nothing checks it.
     */
    enum class Unused {
        /**
         * The stored line was written by a build whose shape this one cannot parse.
         *
         * This build's own data in another build's format, which means a deploy landed between the
         * submission and its collection. The record stays owed: marking it would settle a message
         * against a reading no build ever got to make.
         */
        FOREIGN,

        /**
         * The stored line was read and a rule declined it.
         *
         * The rule that fires here is the subject one: an attribute whose stored subject field no
         * longer matches the label its window gives the author. A context that has grown since the
         * submission can withdraw a label, which costs the finding — see [reading], which states it
         * from the other side. The placement rule shares this case and cannot change its answer,
         * the stored line being re-tested against the identifier its own request named.
         *
         * **Routine, and that is the whole reason it is not [UNEXTRACTABLE].** Both produce no row
         * and only one of them says anything is wrong. This and [FOREIGN] are the two decided
         * before the reply is looked at, so either fires whatever the provider answered.
         */
        REFUSED,

        /**
         * An attribute whose predicate the model answered with the refusal word.
         *
         * **The nearest thing a sweep has to a signal about the vocabulary's width, because it is
         * the answer the instruction asks for.** [REWRITE_SYSTEM] tells the model to write the
         * refusal word for an attribute that none of the entries fits, rather than stretch one to
         * cover it. So on a candidate the classification already called an attribute, this is the
         * two stages disagreeing about whether the fact fits the list — the first found an
         * attribute, the second found no entry for it.
         *
         * **It is not a measure of the width**, because both stages are given the same list: the
         * same figure covers a classification that was wrong to call the thing an attribute at all.
         * No figure here isolates the width; see [PREDICATES].
         *
         * Settled rather than retried: it is the model's answer to this request and will be its
         * answer again. Apart from [OUTSIDE] because that one is the same question answered by
         * disobeying, and the two need telling apart — this one bears on the list, the other says
         * whether the instruction is being followed.
         */
        UNNAMED,

        /**
         * An attribute's predicate was a word the vocabulary does not hold.
         *
         * A model answering `employed by`, or the same idea in the corpus's own language, which the
         * instruction to write the claim in that language invites. Storing it would key a statement
         * under a name nothing queries, so the reading is lost here instead.
         *
         * **A figure here is the instruction not being followed, not a corpus the list does not
         * describe.** The model was told to answer the refusal word rather than stretch an entry, so
         * this is what it does when it stretches one anyway — which is why it is counted apart from
         * [UNNAMED], where an obeyed instruction puts the same fact. Zero here says the stretching
         * rule is holding; it does not say the list is wide enough.
         */
        OUTSIDE,

        /**
         * The predicate was a legal entry and the value was not the sort it takes.
         *
         * **The only figure here that no answer of the model's decides.** Every other entry is
         * read off what came back — a word, a shape, a stop reason — while this one tests the value
         * against the relation it arrived under, so it holds whatever a later wording of either
         * instruction does to the behaviour. That is what it is for: the fault it was written
         * against was a correct instruction, obeyed for as long as anybody measured, that stopped
         * being obeyed when two unrelated edits moved the prose around it.
         *
         * Apart from [OUTSIDE], which is the neighbouring fault and the opposite half of it: there
         * the value is a fact of the right sort under a name the list does not hold, here it is a
         * name the list holds over a value of the wrong sort. Both lose the reading; only this one
         * would otherwise have stored it.
         *
         * **Not counted as a loss of the build's making.** The call was paid for and bought
         * nothing, as [UNEXTRACTABLE] was, but nothing about the prompt and the parser is out of
         * step — so a figure here is the model to be told better, and never a build to be fixed.
         */
        MISSHAPEN,

        /** The reply came back under a stop reason this build has no reader for. */
        UNREADABLE,

        /** The model would not restate the finding: it refused, ran out of room, or paused. */
        DECLINED,

        /**
         * It answered, the reply was readable, and the line itself could not be parsed.
         *
         * A line with the wrong number of fields, a blank claim, or a blank predicate field on an
         * attribute. The last belongs here rather than with the two answers that are a word: no
         * instruction asks for an empty field, so a model leaving one is this build's prompt and
         * parser out of step, which is what this figure is for. **How often a model leaves it blank
         * rather than writing the refusal word it was asked for is unmeasured**, so a figure here of
         * one or two may be a slip by a model told to write a word, rather than drift.
         *
         * The expensive failure: the call was made at the higher of the two efforts and bought
         * nothing, and unlike the routine cases — [REFUSED], [UNNAMED], [OUTSIDE], [MISSHAPEN]
         * and [DECLINED] —
         * it is this build's to fix, so a figure here that is any share of the batch is a prompt
         * and a parser to be put back in step.
         */
        UNEXTRACTABLE,
    }
}

/**
 * Which of four answers a restatement's predicate field held.
 *
 * **Sealed, so a caller cannot treat the three that are not a predicate alike.** They say different
 * things about this build, and the first two bear on the vocabulary differently:
 *
 * - [Refused] is the refusal word, which [Extractor.REWRITE_SYSTEM] asks for when no entry fits.
 *   On something the classification already called an attribute, that is the two stages
 *   disagreeing about whether the fact fits the list. It is the nearest thing to a signal about
 *   the width, not a measure of it: both stages are given the same list, so a classification that
 *   was wrong to call the thing an attribute lands here too.
 * - [Outside] is a word the list does not hold, so it is that same instruction disobeyed. It says
 *   whether the stretching rule is holding, not whether the list is wide enough.
 * - [Blank] is a field no instruction asks for, so it is drift.
 *
 * Merged into a nullable predicate, the one that bears on the list is buried under two that do
 * not — and a corpus producing many blank fields reads as a vocabulary that is too narrow.
 */
sealed interface PredicateField {
    /** An entry of [Extractor.PREDICATES], normalised. */
    data class Named(val predicate: Predicate) : PredicateField

    /** The refusal word. */
    data object Refused : PredicateField

    /** Empty, which no instruction asks for. */
    data object Blank : PredicateField

    /** A word the vocabulary does not hold. */
    data object Outside : PredicateField
}

/**
 * What the model made of two readings that might be one fact.
 *
 * **Sealed, so that a caller cannot apply a verdict without saying which of the three it had.** The
 * three decide opposite things about the same pair, and the two that are not a merge are the ones
 * worth keeping apart: one records a decision and never asks again, the other records nothing and
 * leaves the pair to be offered later. Collapsed into a nullable "merge or not", a refusal would
 * read as a pair still worth asking about and the stage would buy the same refusal on every sweep.
 */
sealed interface Verdict {

    /** One fact, read twice. The pair is merged and the survivor carries the evidence of both. */
    data object Together : Verdict

    /**
     * Two facts. The model read the pair and said they state different things.
     *
     * The ordinary paid answer, and not a failure of anything: a pair reaches the model precisely
     * because the vectors could not settle it. It carries no reason because there is nothing to
     * explain — the pair is recorded as kept apart and counted, and a sweep where these dominate the
     * merges is saying the cheap tier's lower bound is set too low.
     */
    data object Apart : Verdict

    /**
     * The model will not compare the pair, and asking again will not change that.
     *
     * Reported apart from [Apart] for the reason [Candidacy.Declined] is reported apart from
     * [Candidacy.NotRead]: this is a loss rather than an answer, and it is still final. Holding the
     * pair open would have the same two sentences submitted and refused on every idle sweep for the
     * rest of the deployment, so it is recorded like a verdict — but it asserts nothing about the
     * two claims, which is why [reason] is carried out whole rather than counted.
     */
    data class Declined(val reason: String) : Verdict

    /**
     * The provider did not answer, so the pair stays a candidate and asking again may settle it.
     *
     * A cut-short turn, one that never finished, and the whole of an expired submission — which is
     * the ordinary way a batch ends when the provider gives up on it. Nothing is recorded, so a
     * later sweep offers the pair again; the call is lost and the pair is not.
     *
     * **Apart from [Unread] because only one of the two says anything about the build.** This one
     * clears by being asked again and happens as a matter of course; reported as a condition a
     * deploy has to clear, every expiry would read as a broken parser.
     */
    data class Unanswered(val reason: String) : Verdict

    /**
     * The reply came back in a shape this build has no reader for, so the pair stays a candidate.
     *
     * Narrow, and the one worth a deployment's attention: a word outside the two the instruction
     * offers, or a stop reason nothing here names. Asking again will not clear it — only a build
     * that knows the value will — so it is named rather than counted among the answers, while
     * still recording nothing, because a decision written on it would make the drift permanent.
     */
    data class Unread(val reason: String) : Verdict
}
