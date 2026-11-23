package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.contract.Freshness
import java.time.Clock
import java.time.ZoneId

/**
 * How current the corpus was, in the one sentence a reader gets about it — when there is one.
 *
 * **Said only when the corpus is behind, which is the only state it can tell a reader anything
 * about.** The sentence exists to stop somebody relying on an answer composed from stale material,
 * and on a current corpus there is nothing to stop: measured across every shape this build has, it
 * arrived on each one stating that the material had been collected just now, which is a sentence a
 * reader can do nothing with. On a reply that retrieved nothing it was worse than vacuous — it
 * asserted a collection on the one answer where the system never got as far as looking.
 *
 * **What it is *not* scoped to is whether anything was found, and that distinction is the whole
 * reason the suppression is worded this way.** *Nothing was found* and *nothing was found yet* are
 * the same reply to a reader, and a name absent from the corpus because ingestion is three weeks
 * behind is exactly the case where saying so is the answer. So a lagging corpus says so on a
 * refusal as readily as on an answer; what is dropped is the current corpus, where neither reply
 * had anything to qualify.
 *
 * **A footer on the answer and never a message of its own.** It qualifies what was answered, so
 * detached from it there is nothing for a reader to attach it to — and on a reply long enough to
 * split, a note sent separately is a message whose whole content is a sentence about somebody
 * else's sentence. Where it is attached is [OutcomeRenderer]'s; whether anything is said is here.
 *
 * Its own file because a reader who has to decode it stops reading it, and the distinction it draws
 * is then paid for and not had.
 *
 * **The wire carries an instant and this decides what to say about it.** An instant is right for a
 * format several interfaces read; what a person should be told is a different question, and it is
 * this interface's to answer, as the locale and the catalogue are. Answering it in the backend
 * would put one reader's wording in the format every reader shares, and answering it nowhere is
 * what put a machine timestamp in front of somebody.
 *
 * **What a reader is told is how old the material is, not when a clock read something.** Age is
 * the thing being decided on — whether this answer can be relied on — where a date would make a
 * reader do the subtraction to find out. Saying it is [Age]'s, as is the calendar the days in it
 * are counted on: that is the corpus's, which is the one the dates elsewhere in the same reply are
 * printed in. Where an answer's own material has to be located rather than judged for currency,
 * [ClaimSources] states a date, in that same zone rather than a reader's.
 *
 * [clock] and [zone] are parameters rather than a call to `now()` and the host's default, which is
 * what keeps every decision here a unit test.
 */
internal class FreshnessLine(
    private val catalogue: Catalogue,
    clock: Clock,
    zone: ZoneId,
) {

    private val age = Age(catalogue, clock, zone)

    /**
     * What is said about [freshness], or null when there is nothing worth saying.
     *
     * Two clauses, each said only if there is something to say. Separate sentences rather than one
     * template with holes in it: a template covering every combination would be four per locale,
     * and a locale that composes its clauses differently would inherit this one's punctuation.
     *
     * **How old the material is, only once it is old enough to matter.** [Age.since] draws that
     * bound and holds the reasoning for where it sits, and says *nothing to add* by returning
     * null — the same answer it gives for an instant it cannot read, and for the same purpose
     * here. One reading of one bound, so a figure of this file's own cannot drift from it.
     *
     * **A source known to be behind is always said**, and takes no bound of its own. The clause
     * reports that part of the corpus is not being read at all, which does not become less true
     * for having been noticed recently. The two clauses are independent, and a reply carrying only
     * this one is the ordinary case rather than a strange one: the other is dropped whenever what
     * was collected is current, which is most of the time, while a source being behind is about
     * what is still owed and so says nothing about how recent the collection was.
     */
    fun said(freshness: Freshness, locale: String?): String? {
        val clauses = buildList {
            freshness.completeThrough?.let { instant ->
                age.since(instant, locale)?.let { add(catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, locale, it)) }
            }
            if (freshness.lagging.isNotEmpty()) {
                val named = freshness.lagging.joinToString { source(it, locale) }
                add(catalogue.text(BotKey.CORPUS_LAGGING, locale, named))
            }
        }
        return clauses.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * A source, in the name a reader knows it by.
     *
     * Found by name rather than matched branch by branch, so a source a later workstream adds
     * needs a key and a bundle entry and nothing here. The wire carries the service's own constant
     * for a source and the contract types it as a string, which is what makes an unknown one
     * reachable: it falls back to that constant — escaped, because at that point it is a value
     * from elsewhere rather than one of this interface's own words. Showing a constant is poor;
     * losing the fact that a source is behind is worse.
     */
    private fun source(name: String, locale: String?): String =
        BotKey.entries.firstOrNull { it.name == SOURCE_PREFIX + name }
            ?.let { catalogue.text(it, locale) }
            ?: escaped(name)

    private companion object {

        /** How a source's key is spelled, given what the wire calls it. */
        private const val SOURCE_PREFIX = "SOURCE_"
    }
}
