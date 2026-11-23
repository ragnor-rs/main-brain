package io.github.ragnorrs.mainbrain.domain.answer

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * When something was, in the one convention a reply states a message's time in.
 *
 * **One place, because two renderings of one message's time are what a reader reads as an error.**
 * A reply that heads a block with a calendar date and counts the same message's age in elapsed
 * hours gives a reader two figures a day apart and nothing to choose between them — and the one
 * they can check, by counting from the date, is the one that disagrees. So a date and a day count
 * about a message come from here, and anything printed beside a date is printed by [written].
 *
 * **The correspondents' calendar, not the clock's.** A day count a reader can verify is the number
 * of dates between two dated things, and the dates they see are the ones the material was written
 * under — so the count is taken between calendar days in that zone rather than as whole elapsed
 * periods. Counting elapsed periods instead is short by one for every message written later in the
 * day than the hour the question is asked, which is a large share of any archive rather than an
 * edge: it is not the near-midnight case, it is most of the afternoon.
 *
 * **A deadline is said this way too, though it is not a message's time.** Where a commitment's
 * date stands — now past, today, ahead — how far past it is, the date itself, an event's moment
 * and the present moment stated at the head of a request all come from here. They are printed
 * beside the times of messages and a reader weighs them against each other, so a convention of
 * their own is a second answer to the same question: a deadline and the message mentioning it
 * falling at one moment reported two figures a day apart, and a commitment falling due today
 * where the correspondents are was grouped as overdue. One calendar is what makes the pair
 * comparable; see `Material.standing` and `Material.overdue`.
 *
 * **What this cannot repair is a date resolved onto a different calendar from the one it is read
 * on, and the bound runs one way.** Recognition resolves a stated date to the start of a day in
 * UTC, so a date-only deadline is the UTC encoding of a calendar date. Read here on UTC or on a
 * zone ahead of it, that encoding keeps the date somebody stated. Read on a zone behind UTC it
 * lands on the previous day — and because the standing and the day count are taken from the same
 * reading, all three move back together rather than disagreeing with each other. Self-consistent
 * and a day early is the trade, and it is not a question about printing alone: `Material.standing`
 * groups on the same value.
 *
 * Reading a deadline on UTC instead would keep that date and give back the disagreement this
 * exists to remove, since the age beside it follows the messages. The two agree at every zone only
 * once a stated date is resolved in this same zone, which is recognition's to do; nothing in this
 * layer can choose both.
 */
internal object Moment {

    /**
     * When [at] was, as somebody in [zone] would have read a clock at that moment.
     *
     * **Local rather than the instant, because the only use a model has for this is working out a
     * relative reference, and that arithmetic is wrong in UTC.** Where the zone is ahead of UTC the
     * two disagree for the hours just after local midnight: a message written at half past two in
     * the morning still falls on the previous UTC day, so *tomorrow* in it works out a day early —
     * silently, and in the reader's favour, which is the direction [Reply.format]'s rule exists to
     * stop. Behind UTC the window is the late evening and the error runs the other way. How much of
     * a corpus sits in it is a property of that corpus, read off the source times of the messages
     * carrying such a word rather than recorded here.
     *
     * The offset is printed because a bare local time asserts a zone the reader cannot see, and
     * that one is a deployment's to set. Where it is unset the offset reads `Z`: the same instant
     * in the same zone the heading always carried.
     */
    fun written(at: Instant, zone: ZoneId): String {
        val local = at.atZone(zone)
        return "${WRITTEN.format(local)} ${WEEKDAYS[local.dayOfWeek.value - 1]} ${AT.format(local)}"
    }

    /**
     * How many of [zone]'s calendar days [to] is after [from], which is what counting the dates
     * between two dated things gives.
     *
     * Negative where [to] falls first, which is two clocks disagreeing rather than material from
     * the future; a caller deciding what to say about a figure decides what to do with that.
     */
    fun daysBetween(from: Instant, to: Instant, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate())

    /**
     * How the moment something was written is printed.
     *
     * Minutes and no finer, and a space where ISO-8601 puts a `T`, because what the model reads
     * this for is placing a day: the seconds are two characters a block that no answer can use. It
     * is a small saving and it is the whole of the argument — nothing renders this to a person, so
     * how it reads to one is not a reason available here.
     *
     * **The weekday is printed because the arithmetic it exists for cannot be done without it.**
     * Working *on Wednesday* out against a block written on the 8th means knowing what day the 8th
     * was, and a date alone leaves that to be inferred — which was measured on the archive going
     * wrong: one resolution in six landed a day out, and two answers placed the same event on
     * different days. Four characters a block buys the operand instead of hoping it is recalled.
     *
     * **The name is spelled out in [WEEKDAYS] rather than asked of a pattern**, because `EEE` is a
     * text field and a formatter resolves those through a locale — so the prompt would vary with
     * whatever the host happens to be set to, moving the material under a model for a reason
     * nothing in this build chose, and in a Russian locale putting Cyrillic weekday names into
     * every block of an English instruction. Pinning a locale would fix that and costs `:domain` a
     * `java.util` import the architecture rules refuse; seven constants cost it nothing. The
     * numeric fields need no such care — a pattern's digits come from `DecimalStyle.STANDARD` and
     * not from a locale.
     *
     * They are not display text: nothing renders them to a person, no interface sees them, and no
     * locale selects between them.
     */
    private val WRITTEN: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

    /**
     * The rest of it, once [WEEKDAYS] has been spliced in after the date.
     *
     * The offset keeps its colon, which is the one part that must stay unambiguous.
     */
    private val AT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm XXX")

    /** Indexed by `DayOfWeek.value`, which runs 1 for Monday to 7 for Sunday. */
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}
