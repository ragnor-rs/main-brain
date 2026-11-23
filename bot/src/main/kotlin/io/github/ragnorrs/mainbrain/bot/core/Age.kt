package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How long ago something was, in words a reader of one locale gets.
 *
 * **An age rather than a date, because an age is the thing being decided on.** What the one line
 * that uses this asks is whether the corpus is current enough to rely on, and that is a question
 * about how much time has passed rather than about a calendar. Where a date is what a reader needs
 * — locating the material behind an answer — [ClaimSources] states one.
 *
 * **Days are counted on the corpus's calendar all the same, which is the one thing a reader can
 * check.** A reply carries dates as well as this line, and both describe the same material: shown
 * *one day ago* beside a block of dates two days apart, a reader counting the dates finds the
 * figures disagree and has no way to tell which is wrong. So the figure a reader can count is
 * counted the way they count it — in the zone the material was written in, which [zone] names and
 * which is the same one the dates are printed in.
 *
 * **Below a day it stays elapsed time, because there is no date in front of the reader to check it
 * against.** Silence and a count of hours answer how current the corpus is and nothing else; a
 * message twenty minutes old that happens to fall the other side of midnight is current, and
 * calling it a day old to satisfy a calendar would be the same defect pointing the other way.
 *
 * Its own file rather than a function on the line that uses it, because the plural rules below are
 * a locale's and a second line asking an age of an instant should find them rather than restate
 * them.
 *
 * [clock] is a parameter rather than a call to `now()`, and [zone] is one rather than the host's
 * default, which is what keeps every decision here a unit test.
 */
internal class Age(
    private val catalogue: Catalogue,
    private val clock: Clock,
    private val zone: ZoneId,
) {

    /**
     * How long ago [instant] was, in words, or null where there is nothing worth saying.
     *
     * **Coarser the older it gets, because that is how the decision coarsens.** Within the hour
     * there is nothing to say at all; through the day the hours are what say whether ingestion is
     * keeping up; past a month the answer is the same whatever the count, so counting further buys
     * a precision nobody acts on.
     *
     * **Null inside the first bound rather than a phrase, which is one figure and not two.** A
     * caller deciding whether to say anything and this deciding what to say would be two readings
     * of the same bound, and the drift between them would show as a sentence saying the material
     * is current appearing exactly where the caller meant to leave the answer unqualified. So the
     * bound is read once, here, and *too recent to be worth a sentence* reaches the caller the
     * same way *we could not read it* does: as nothing to add. An hour is where it sits because
     * ingestion runs continuously, so inside one there is nothing a reader could act on
     * differently.
     *
     * **The two sub-day bounds are elapsed durations and the day count is a calendar one**, for the
     * reason the class comment gives — and the order they are tested in is what keeps that
     * coherent: anything under a day is answered as hours before the calendar is consulted, so a
     * message from an hour ago is never called a day old for having crossed midnight.
     *
     * Null rather than the raw value when the instant does not parse, for a different reason and
     * with the same result. The wire promises an instant, so a value that is not one means the
     * service is wrong rather than the material being old — and saying so by showing it would put
     * the machine shape back in front of the reader. What is dropped is the one phrase; whoever
     * asked for it decides what the line does without it.
     */
    fun since(instant: String, locale: String?): String? {
        val written = runCatching { Instant.parse(instant) }.getOrNull() ?: return null
        val now = clock.instant()
        val elapsed = Duration.between(written, now)
        // How many of the corpus's own calendar days lie between the two, which is what a reader
        // counting the dates in the same reply arrives at. Read only past the sub-day bounds.
        //
        // **Never fewer than one, which is not defensive and is reachable.** Where the zone puts
        // its clocks back, a whole elapsed period advances the wall clock by twenty-three hours —
        // so material a day old can fall on the day it is read, and the branch this feeds is the
        // one a whole period has already been established for. Unclamped it renders a count of
        // nought days, which is no age at all.
        val days = ChronoUnit.DAYS
            .between(written.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
            .coerceAtLeast(1)
        return when {
            // Something dated ahead of the clock lands here too, which is two clocks disagreeing
            // rather than material from the future. Nothing is said about either, which is also
            // what keeps a minus sign out of a sentence: the branch that would have counted is
            // never reached.
            elapsed.toHours() < JUST_NOW_HOURS -> null

            elapsed.toDays() < 1 -> counted(
                elapsed.toHours(),
                BotKey.AGE_HOURS_ONE,
                BotKey.AGE_HOURS_FEW,
                BotKey.AGE_HOURS_MANY,
                locale,
            )

            days <= LONG_AGO_DAYS -> counted(
                days,
                BotKey.AGE_DAYS_ONE,
                BotKey.AGE_DAYS_FEW,
                BotKey.AGE_DAYS_MANY,
                locale,
            )

            else -> catalogue.text(BotKey.AGE_LONG_AGO, locale)
        }
    }

    /**
     * [count] with its noun, in the grammatical number the floor locale puts that count in.
     *
     * **A key per category, because the count cannot be formatted into one sentence.** The
     * catalogue substitutes literally rather than through `MessageFormat` — see `Catalogue.fill`,
     * where that is a deliberate choice — so a plural is picked by choosing between entries.
     *
     * The rule below is the floor locale's own, and how many categories a language has is its own
     * business: a locale whose categories differ takes three entries of its own and a branch here,
     * and one with no distinction points all three at the same entry.
     */
    private fun counted(count: Long, one: BotKey, few: BotKey, many: BotKey, locale: String?): String {
        val key = when {
            // The teens first, and the order is load-bearing. Eleven ends in a one and takes the
            // many form anyway, so testing the last digit before this would never reach them.
            count % TEEN_BASE in TEENS -> many
            count % UNIT_BASE == 1L -> one
            count % UNIT_BASE in SMALL_PLURAL -> few
            else -> many
        }
        return catalogue.text(key, locale, count.toString())
    }

    private companion object {

        /** Within this many whole hours, how recent the material is stops being worth saying. */
        private const val JUST_NOW_HOURS = 1L

        /**
         * Beyond this many of the corpus's calendar days, how old something is stops changing the
         * decision.
         *
         * Derived for the question this words: whether the corpus is current enough to rely on,
         * where past a month the answer is the same whatever the count, so counting further buys a
         * precision nobody acts on. The bound is a figure of days and is unchanged by which
         * boundary the days are counted on; what moved is the count, not how coarse it goes.
         *
         * **A collapse this coarse is why locating material is not asked of this.** Whatever share
         * of a corpus falls past the bound is worded identically however far past it each piece
         * is — which is no answer to *where do I find this*, and on an archive with history behind
         * it that share is most of it. That question takes a calendar date, and [ClaimSources]
         * states one.
         */
        private const val LONG_AGO_DAYS = 30L

        /** What a count is taken modulo to find the teens, which are the floor locale's exception. */
        private const val TEEN_BASE = 100L

        /** The counts that take the many form whatever their last digit is. */
        private val TEENS = 11L..14L

        /** And what it is taken modulo to read that last digit, for every count that is not one. */
        private const val UNIT_BASE = 10L

        /** The last digits the floor locale treats as a small plural. */
        private val SMALL_PLURAL = 2L..4L
    }
}
