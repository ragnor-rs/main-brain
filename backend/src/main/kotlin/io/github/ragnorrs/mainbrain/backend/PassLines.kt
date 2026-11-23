package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.recognition.Admissions
import io.github.ragnorrs.mainbrain.domain.recognition.Closing
import io.github.ragnorrs.mainbrain.domain.recognition.Inference
import io.github.ragnorrs.mainbrain.domain.recognition.Recognised

/**
 * The line each derived pass's batch is reported as.
 *
 * **Here rather than in the container's companion, and the two reasons are different.** The small
 * one is that the companion had grown past what the style gate allows. The larger one is that these
 * belong together: `:domain` logs nothing, so for most of the figures these passes count this file
 * is the only reader there is — and the thing most likely to go wrong is not a figure dropped but a
 * figure printed under another's words. Two stages' counts that mean opposite things are kept
 * distinguishable by the wording here and by nothing else, which is a property worth being able to
 * read in one place.
 *
 * Internal, so the tests that hold each line against its type's own fields compile against them
 * directly.
 */

/**
 * What one batch of the identity stage did, as a line for whoever reads the log.
 *
 * The client count is printed always, even at zero, which is the whole reason the stage
 * returns it. Identity settles every record it looks at, so a line reporting only that
 * number cannot fall — and a rule that admits no conversation at all would read as a pass
 * catching up on schedule for as long as the corpus kept growing.
 */
internal fun Recognised.reported(): String =
    "$records record(s) recognised, $clients client(s) named"

/**
 * What one batch of inference did, as a line for whoever reads the log.
 *
 * Everything the batch answers with, because most of it is the ways this pass falls
 * short quietly — findings lost or refused, requests unread, windows declined, replies read
 * and unusable — and `:domain` logs nothing. A driver that reported only the progress would
 * hide all of them behind a number that looked like success. The declined reasons are
 * carried out whole rather than counted: each one is a stretch of conversation nothing will
 * ever read. What is left is what the batch spent, and it is the only place the input half of
 * that is said — the driver is handed the same figure but bounds and reports the output half
 * alone.
 *
 * **Rows and attempts are both said, and the difference is accounted for rather than left
 * to subtraction.** A reader of one line should be able to tell a sweep that recognised
 * duplicates from one that read the same windows again: both reduce rows below attempts,
 * and one figure covering them says neither.
 *
 * Three things are printed whether or not they happened — the records settled, the rows
 * written, and the spend — because a batch that settled nothing or spent nothing is exactly
 * what a reader needs told. Every other figure is suppressed at zero, because a line
 * listing a dozen nothings is one nobody reads to the end.
 *
 * **Internal rather than private, for the reason [fault] gives.** A figure the pass counts
 * and this forgets to print is invisible from every other vantage point in the build —
 * `refused` was collected on every batch and printed on none — and the only thing that can
 * catch it is a test holding the line itself against the type's own fields.
 */
internal fun Inference.reported(): String = buildString {
    append("$records record(s) settled, $written row(s) written")
    // Said whenever it differs from the rows, which is the whole reason the two are separate
    // figures: a batch that offered more than it caused has the difference accounted for by
    // the rows clause below, and a batch where they agree says so by this being absent.
    if (attempted != written) append(" of $attempted attempted")
    append(unwritten())
    // Here rather than in `unwritten()`, which accounts for rows the attempts did not
    // become: these readings did become rows. What they say is that a row carries no date
    // where the model offered one it could not have read off what was said, so they belong
    // beside the figures about what a sweep produced rather than among the ways a reading
    // went nowhere.
    //
    // **A clause each, because the two figures send a reader to different places.** One is a
    // commitment due before its own message where nothing says another party owes it, and
    // rare; the other is the field being copied off the message's own line, and prevalent —
    // so a line merging them could not say which had happened. An obligation stated about
    // somebody else keeps such a date and reaches neither clause.
    if (backdated > 0) append(", $backdated date(s) refused for preceding their own message")
    if (sameInstant > 0) append(", $sameInstant date(s) refused as their message's own instant")
    // **Outside `unwritten()` on purpose, and the unit is the reason.** Everything in there is
    // counted in readings, so each of its clauses accounts for a reading that did not become a
    // row. This counts facts, which were never readings this pass would have carried anywhere:
    // the classification met something it cannot label and said so, costing no restatement.
    //
    // Said whenever it fires, and the clause is the whole value of the figure: a stage that
    // declines such material correctly and a stage that drops it in silence are the same line
    // without it, so the attribute path writing nothing could be read either way.
    if (unlisted > 0) append(", $unlisted fact(s) the attribute list does not cover")
    // The collapse's own progress, and the figure that falls where `written` rises: a merge
    // is a reading that stops being a finding of its own. Said whenever it happens, because
    // a sweep that merged is otherwise indistinguishable from a sweep with nothing owed —
    // both settle no records and write no entities.
    if (merged > 0) append(", $merged duplicate(s) merged")
    // Named because it is the measure of a threshold set too low: these are calls that
    // bought a negative answer, and a sweep where they dominate the merges is a sweep
    // paying the model to rule out pairs the vector comparison should have.
    if (apart > 0) append(", $apart pair(s) paid for and kept apart")
    // Named whenever it happens, because this is the ceiling setting the pace rather than
    // the backlog, and the two look identical from outside.
    if (held > 0) append(", $held window(s) held back for a later sweep")
    if (awaiting > 0) append(", $awaiting request(s) with the provider")
    // Spending that bought nothing, so it is said rather than left to a silent zero.
    abandoned?.let { append(", gave up on a submission after $it") }
    // Not named as windows: the classification stage declines a window and the collapse
    // declines a comparison, and both are settled rather than retried. The reasons say
    // which, so the count does not have to claim one of them for all of them.
    if (declined.isNotEmpty()) append(", ${declined.size} declined: ${declined.joinToString("; ")}")
    // A condition only a new build clears, so it is named rather than counted among the
    // losses: a reply the provider answered in a shape nothing here reads will be answered
    // the same way on every retry until something is taught to read it. The reason says
    // which stage it came from, so the line does not name one — the restatement stage and
    // the collapse both set it, and a line naming the wrong one would send a reader to the
    // wrong half of the pass.
    unreadable?.let { append(", a reply could not be used: $it") }
    // Always, even at zero. The batch that spent nothing is worth telling apart from the
    // batch whose cost nobody recorded, and this is the only place a bill is observable.
    append(
        ", spent ${usage.inputTokens} in / ${usage.outputTokens} out" +
            cached(usage.cacheReadTokens, usage.cacheWriteTokens),
    )
}

/**
 * Everything that happened to a reading other than its becoming a new row.
 *
 * **A collapse, an overwrite and a supersede mean three different things** — a duplicate
 * recognised, a window read again, a judgement unmade — and a single figure covering them
 * leaves a defect in any of the three unmeasurable. Beside them are the ways a reading never
 * reached a write at all, and those keep the division between routine and total: a rule
 * declining a reading fires on most sweeps, while a line that would not parse says a prompt
 * and a parser have drifted, and the two must not be added.
 *
 * One function rather than two because this is one question — *why is the row count below the
 * attempt count* — whichever half of it answers in a given batch.
 */
private fun Inference.unwritten(): String = buildString {
    if (overwritten > 0) append(", $overwritten row(s) overwritten")
    if (collapsed > 0) append(", $collapsed reading(s) collapsed into another")
    if (superseded > 0) append(", $superseded stored row(s) superseded")
    if (lost > 0) append(", $lost finding(s) lost")
    // The routine counterpart to `lost`: a reading made perfectly and declined by a rule.
    if (refused > 0) append(", $refused finding(s) refused by a rule")
    // **The figure that bears on the predicate vocabulary's width**, the refusal word being
    // what the instruction asks for when no entry fits. Not a measure of it: both stages are
    // given the same list, so a classification that was wrong to call the thing an attribute
    // lands here too. Beside the rules above rather than within them, because those fire as
    // a conversation grows.
    if (unnamed > 0) append(", $unnamed attribute(s) the model named no predicate for")
    // The same judgement reached by stretching an entry instead, so it says whether that
    // rule is holding rather than how wide the list should be.
    if (outsideVocabulary > 0) {
        append(", $outsideVocabulary attribute(s) outside the predicate vocabulary")
    }
    // The one of the three the other two cannot see: a legal entry over a value of a sort it does
    // not take. Said whenever it fires, because what it refused would otherwise have been stored
    // with an open validity window and a real message citing it — a wrong fact that reads on the
    // answer path exactly as a right one does.
    if (misshapen > 0) {
        append(", $misshapen attribute(s) refused for a value the predicate does not take")
    }
    // The model would not restate it, so it is settled rather than retried.
    if (unanswered > 0) append(", $unanswered restatement(s) the model would not give")
    // Answered, readable, and nothing could be built from it: the one here that points at
    // this build rather than at the provider or the corpus.
    if (unextractable > 0) append(", $unextractable reply/replies read but not extractable")
    // Identity has not reached the subject yet, which is the two passes out of step.
    if (unplaced > 0) append(", $unplaced attribute(s) with no contact to hang on")
    // A restatement each that was bought and folded away by the write's content key. Named
    // because the spend is otherwise invisible: a model repeating every finding would read
    // as a sweep that merely costs more, with nothing in the line saying where it went.
    if (repeated > 0) append(", $repeated line(s) the model wrote more than once")
    if (unread > 0) append(", $unread request(s) left unread")
}

/**
 * The cache halves of a batch's usage, said either way.
 *
 * A read is billed at a fraction of fresh input and a write at a premium over it, so the
 * two moving is the difference between a prompt that caches and one that does not — while
 * the token total barely shifts.
 *
 * **Zero is said rather than suppressed, because for most stages zero is the finding.** A
 * breakpoint below the model's minimum cacheable prefix is ignored silently: no error, no
 * refusal, just both counters at nothing. Nothing in this build can detect that before the
 * call, so the only place it can be noticed is here — and a line that went quiet at zero
 * would report a breakpoint that never took exactly as it reports one that was never
 * placed. For closure it is the known reading rather than a finding, because that
 * instruction has been measured against the minimum and is under it; said all the same, so
 * that the day it changes is visible in the same place.
 */
private fun cached(read: Long, written: Long): String =
    if (read == 0L && written == 0L) {
        " (nothing read from or written to cache)"
    } else {
        " ($read cached, $written written to cache)"
    }

/**
 * What one closure batch did, as a line for whoever reads the log.
 *
 * **Every figure the type carries, for the reason [Inference.reported] gives**: a count the
 * pass keeps and this forgets to print is invisible from every other vantage point in the
 * build, and this stage's quiet failures are counts rather than exceptions.
 *
 * **The examined and unclosed figures are deliberately two and not one.** A sweep that
 * examined ten commitments and closed none is this stage working on a corpus that closes
 * things in silence; a sweep that examined ten and could read none of the replies is this
 * stage broken. Reported as one number they are the same line.
 *
 * `unclosed` is never worded as *open*, here or anywhere: what the verdict records is that
 * the material weighed does not report the promise as done, which is not the same claim.
 */
internal fun Closing.reported(): String = buildString {
    append("$examined commitment(s) examined, $closed closed")
    // Named rather than inferred from the difference, because the difference also contains
    // the replies nothing could be read from.
    if (unclosed > 0) append(", $unclosed with no closure in the material weighed")
    // A call that bought no answer. The commitment stays owed, so a sweep where this
    // dominates is a stage paying for nothing and still reporting progress.
    if (unread > 0) append(", $unread reply(s) bought no answer")
    // A condition only a new build clears, as it is for the reading stages: a payload this
    // build cannot read was written by one that keyed it differently, and no retry helps.
    if (unreadable > 0) append(", $unreadable request(s) whose stored window could not be read")
    // Carried out whole rather than counted, as a declined window is for the reading stages: each
    // one is a stretch of conversation the provider would not look at, and the category is the only
    // thing that says why. Kept apart from the verdicts above for the reason the type states.
    if (declined.isNotEmpty()) append(", ${declined.size} declined: ${declined.joinToString("; ")}")
    if (awaiting > 0) append(", $awaiting request(s) with the provider")
    abandoned?.let { append(", gave up on a submission after $it") }
    // Always, even at zero, for the reason the inference line says it: this is the second stage
    // spending the day's allowance, and the log is where a bill is read or nowhere.
    append(
        ", spent ${usage.inputTokens} in / ${usage.outputTokens} out" +
            cached(usage.cacheReadTokens, usage.cacheWriteTokens),
    )
}

/**
 * What one batch of the admission pass did, as a line for whoever reads the log.
 *
 * **Every figure the type carries, for the reason [Inference.reported] gives.** `:domain`
 * logs nothing, so a figure this forgets to print is one nobody can read anywhere.
 *
 * **The rejections are printed at zero, and that is the whole point of the line.** Zero is the
 * expected answer on a source where every named conversation is already a body of work — the
 * case this pass is deferred for — and a line that said nothing there would be
 * indistinguishable from a pass that is not running. The same goes for the spend: it is what
 * says whether the judgement was worth paying for.
 *
 * **Settled and judged are two figures.** The difference is what the pass marked without
 * asking anybody: a two-person exchange, a conversation with no name, and one the provider
 * refused. Reported as one number, a sweep that paid for nothing and a sweep that judged every
 * conversation read alike.
 */
internal fun Admissions.reported(): String = buildString {
    append("$settled conversation(s) settled, $judged judged, $rejected not a body of work")
    // A reply that named both verdicts or neither. The conversation stays owed, so a sweep where
    // this dominates is a stage paying for answers it cannot read.
    if (unreadable > 0) append(", $unreadable reply(s) carried no verdict")
    // Ran out of room or stopped mid-flight: also still owed, and also the build's problem.
    if (unread > 0) append(", $unread reply(s) unfinished")
    // Carried out whole rather than counted, as a declined window is for the reading stages: the
    // category is the only thing that says why, and a refused conversation is settled rather than
    // asked again.
    if (declined.isNotEmpty()) append(", ${declined.size} declined: ${declined.joinToString("; ")}")
    append(
        ", spent ${usage.inputTokens} in / ${usage.outputTokens} out" +
            cached(usage.cacheReadTokens, usage.cacheWriteTokens),
    )
}
