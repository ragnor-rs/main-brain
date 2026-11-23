package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Claimed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where an answer's assertions came from, in the form a reader can act on.
 *
 * **The whole proposition of this build is that an answer is checkable**, and an assertion a reader
 * cannot get behind is the failure it cannot afford. The backend validates every claim's citations
 * and sends them; what it sends is an identifier, which resolves and is not usable. This is where
 * that becomes something a person can go and look up.
 *
 * **One block for the reply, not a line under every finding.** What a citation has to buy is a
 * reader opening the material and finding the turn. A line per cited message does not buy that and
 * costs a great deal: consecutive turns by one person render lines identical to each other, and a
 * reply's worth of them buries the answer under more citation than answer. Consolidated, the same
 * information is a set of names and a span of dates — which is what a reader searches a
 * conversation by.
 *
 * **What is given up is the mapping from one finding to one message, and it is given up knowingly.**
 * That mapping is the strongest form a citation can take, and it is worth its room only where the
 * line it puts there locates a message. A name with an elapsed age does not, so the mapping led to
 * material the reader still could not open. A block naming the authors and the dates is checkable
 * at the level of the reply: a weaker claim honestly kept rather than a stronger one that did not
 * hold.
 *
 * **Dates rather than ages, because an age does not locate anything.** Whatever part of a corpus is
 * older than the point past which an age stops counting is worded the same however old each piece
 * is, so a span with both ends in it reads *more than a month ago* to *more than a month ago* and
 * says nothing. A date is also what a reader scrolls a conversation to. [Age] still words how
 * current the corpus is, where an age is exactly the question being asked — and counts its days on
 * the same calendar these dates are printed in, so the two figures in one reply agree.
 *
 * **It never shows the identifier.** That is a line of machine text, and the interface owning the
 * layout is what the wire format leaves this free to decide. An interface meaning to link into a
 * client still has it.
 *
 * [zone] is the corpus's own, not the reader's: an instant carries no zone and the platform reports
 * a reader's language but never their zone, so the only well-defined calendar here is the one the
 * material was written in. The deployment names it, which is the same answer the backend reaches
 * for placing material in time, and the same variable — a reply dating a message one way in its
 * prose and another in its sources would be one defect wearing two settings.
 *
 * **What that leaves is a date a reader's own client may disagree with by a day**, since the client
 * shows each message in the reader's zone and this shows it in the corpus's. It is a real cost and
 * the smaller one: it is bounded by a day, it bites only on material written near midnight, and it
 * is the same offset for the whole reply — whereas a date guessed from a reader's language would
 * be wrong by whatever the guess was wrong by, and a reader scrolling to it would find nothing.
 */
internal class ClaimSources(
    private val catalogue: Catalogue,
    private val zone: ZoneId,
) {

    /**
     * [body] rendered, with what the whole of it rests on after it.
     *
     * **A heading is emphasised, set off by a blank line, and otherwise untouched.** Which parts
     * are headings is the one thing this interface is told and could not work out, and those two
     * are the whole of what it does with it: a reader scanning a long answer for the section they
     * want is looking for the short emphasised line that names it, and emphasis with nothing above
     * it still reads as part of what came before. The space is composed here rather than relied on
     * from the model — it used to write a blank line of its own and an answer that reached a reader
     * as one string kept it, which made the separation a property of what the model happened to
     * write. It goes above a heading and never above the first, where there is nothing to separate
     * it from.
     *
     * The text goes through [plain] and then [escaped] before either, in that order and not the
     * other: the model's own markers come off first, and escaping last is what keeps an ampersand
     * in a section's name from making the platform reject the whole message.
     *
     * **Every claim counts towards the block, whether or not its text reached the prose.** Matching
     * a claim to the line it was written into is what made a citation land on the wrong text: a
     * sentence the model wrote in aggregate about its own material reads as a claim, and the sources
     * belonging to the findings under it collected on the header instead. With one block per reply
     * there is no line to attach to and nothing to attach wrongly, and a claim the prose never
     * carried keeps its support rather than needing a rule of its own.
     */
    fun under(body: List<Block>, claims: List<Claimed>, locale: String?): String {
        val rendered = body.flatMap { block ->
            when (block) {
                is Block.Heading -> listOf(bold(escaped(plain(block.text))))

                // **Back into the lines it was composed from, and the blanks between them
                // discarded.** A part arrives as its lines joined by one newline, each of them one
                // piece the answer was built out of — a sentence of prose, or an assertion behind
                // its marker. Whatever blank lines the model happened to leave inside survive into
                // that string, which is why the same question asked twice came back dense once and
                // spaced once: the rhythm of a reply was the model's to decide and nothing could
                // fail when it decided badly. Split and rejoined, it is this interface's, and a
                // test can hold it.
                is Block.Prose -> block.text.lines().map(String::trim).filterNot(String::isEmpty)
                    .map { escaped(plain(it)) }
            }
        }
            // **One blank line between every element, and the same one everywhere.** A heading is
            // set off from the section above it and from its own first item; one item is set off
            // from the next; and what the answer rests on is set off from the answer. The uniform
            // join is what makes those one rule rather than four, and what keeps a doubled blank
            // out of a reply whose model wrote its own.
            .joinToString(PARAGRAPH)
        return sources(claims, locale)?.let { rendered + PARAGRAPH + it } ?: rendered
    }

    /**
     * What the alternative numbered [position] rests on, or null where it rests on nothing stated.
     *
     * **Its own message, which is the whole reason it can be shown at all.** A reader copies an
     * alternative and sends it, so anything inside that message is something they have to delete
     * first — and a draft's assertions are in the third person about the person being written to,
     * so sending one sends somebody a note about themselves. Apart from the drafts they are what
     * they were always for: what one rests on, checkable, and left behind when it is forwarded.
     *
     * **It names the alternative it belongs to, because adjacency no longer does.** These used to
     * follow their own draft, which said which was which and put a message the reader is not
     * sending between two they might be — so reaching the third alternative meant scrolling past
     * what the first two rested on. The drafts are sent together now and these come after them, so
     * what identifies one is the number its draft's header carries.
     *
     * **Per alternative, because a reader choosing the second draft wants the second draft's
     * support.** What consolidating removes here is the repetition inside one block — drafts are
     * written from one retrieval of one thread, so a line per cited message named the same person
     * several times over, twice identically — and not the block itself.
     *
     * The assertions themselves are what this message is for, so they are stated one by one. Where
     * the material behind them is from goes once, under the lot.
     *
     * **They carry the marker now, which reverses the reasoning above it rather than ignoring it.**
     * The argument for no marker was redundancy: the marker tells a finding from prose inside an
     * answer, and a message of nothing but findings has no prose to tell them from. That held while
     * a basis was a message of its own. Now every alternative's basis shares one message, so what a
     * reader faces is three headed groups of two to four statements each — and an unmarked run of
     * those is the wall of text the marker exists to break up everywhere else. The marker is the
     * same punctuation the answer's own lists use, which is the point: a list of findings should
     * look like one wherever it is.
     *
     * **The heading is separated from its statements, and the sources line from them**, by the
     * blank line this interface uses between blocks everywhere. The heading is deliberately not
     * emphasised: the end-to-end suite tells a basis from a draft by the message opening with this
     * key's literal run, and a tag in front of it would make every basis read as an alternative.
     */
    fun basis(position: String, claims: List<Claimed>, locale: String?): String? {
        if (claims.isEmpty()) return null
        val stated = claims.map { ASSERTION + escaped(plain(it.text)) }
        val said = listOf(catalogue.text(BotKey.DRAFT_BASIS, locale, position), "") +
            stated +
            (sources(claims, locale)?.let { listOf("", it) } ?: emptyList())
        return said.joinToString("\n")
    }

    /**
     * Who wrote the material behind [claims] and when, or null where nothing datable is behind it.
     *
     * **Authors in the order their material is cited, deduplicated.** One person writing several
     * of the turns an answer rests on is the ordinary case, and naming them once is the whole
     * saving. The order is the backend's — the order the spans arrive in — and deliberately not
     * claimed to be the order the prose names people in: those two disagree routinely, which is
     * what made ordering a per-finding line against its claim a defect in its own right. A list
     * of who the material is from needs no agreement with the prose to be true, which is the
     * other reason this form survives where that one did not.
     *
     * **An unreadable instant narrows the span rather than failing the block.** The wire promises
     * an instant, so a value that is not one means this service is wrong rather than the material
     * being old — and the span is still true of the cites that did parse. Only when nothing at all
     * parses is there no span to state, and then the block goes: a list of names with no dates is
     * the information-free line this file exists to stop rendering.
     *
     * **Material the corpus names nobody for drops out of the names and stays in the span**, so a
     * reply resting on both named and unnamed turns names the authors it has. The block is then
     * not an exhaustive account of who wrote the material, and that is the right trade in this
     * direction only: dropping the names a reader could use because one turn carries none would
     * cost them the whole block to avoid overstating a corner of it. The names-only wording is for
     * material with no author anywhere in it, which is why it asks whether *every* cite is unnamed
     * rather than whether any is.
     *
     * **Set back rather than emphasised, and the whole line rather than part of it.** A citation is
     * what a reader consults and not what they read, so it takes the weaker of the two emphases —
     * which is what keeps the answer above it the thing the eye lands on. Emphasis goes on after
     * the escaping and around the finished line, so a name containing an ampersand is set back
     * like any other.
     */
    private fun sources(claims: List<Claimed>, locale: String?): String? {
        val cites = claims.flatMap { it.cites }
        val dates = cites.mapNotNull { written(it.sourceTime) }
        val first = dates.minOrNull() ?: return null
        val last = dates.max()
        val authors = cites.mapNotNull { it.author }.distinct()
        val named = authors.joinToString { escaped(it) }
        // A shape per combination rather than one with optional pieces, for the reason a subject
        // option has several: a locale composing them differently should not inherit this one's
        // punctuation, and a span of one day is a different sentence from a span of several rather
        // than a degenerate one. Material the corpus names nobody for is rare and not impossible,
        // and it costs the block its names and not the block.
        val said = when {
            authors.isEmpty() && first == last ->
                catalogue.text(BotKey.SOURCES_UNNAMED_ON, locale, dated(first, locale))

            authors.isEmpty() ->
                catalogue.text(BotKey.SOURCES_UNNAMED_BETWEEN, locale, dated(first, locale), dated(last, locale))

            first == last -> catalogue.text(BotKey.SOURCES_ON, locale, named, dated(first, locale))

            else -> catalogue.text(BotKey.SOURCES_BETWEEN, locale, named, dated(first, locale), dated(last, locale))
        }
        return italic(said)
    }

    /** The day [instant] falls on in the corpus's calendar, or null if it cannot be read. */
    private fun written(instant: String): LocalDate? =
        runCatching { Instant.parse(instant).atZone(zone).toLocalDate() }.getOrNull()

    /**
     * [date] as a reader of this locale writes one.
     *
     * **The pattern is a catalogue entry because the order of the fields is a locale's own**, and a
     * pattern in Kotlin would be one locale's convention compiled into every locale's reply. It
     * renders digits and separators only — no month name — so nothing here depends on the platform
     * having locale data for the reader's language, and the result is composed of this interface's
     * own digits in the sense the escaping rule means.
     */
    private fun dated(date: LocalDate, locale: String?): String =
        DateTimeFormatter.ofPattern(catalogue.text(BotKey.SOURCE_DATE, locale), Locale.ROOT).format(date)

    private companion object {

        /** A blank line between the answer and what it rests on. */
        private const val PARAGRAPH = "\n\n"

        /**
         * What sets one stated assertion off from the next.
         *
         * The same punctuation the answer's own lists carry, restated here rather than shared,
         * because the two are composed by different processes and neither may reach into the
         * other's: what a backend puts in front of a claim line is its business, and what this
         * interface puts in front of one is this file's. Punctuation, so it carries no words and
         * needs no locale.
         */
        private const val ASSERTION = "• "
    }
}
