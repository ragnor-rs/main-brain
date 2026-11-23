package io.github.ragnorrs.mainbrain.ingest

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A clock a test moves by hand.
 *
 * Pacing and a stated wait are both durations, so asserting either against the wall clock would
 * mean sleeping — a test that is slow when it passes and flaky when it does not. Moving time
 * instead makes "two asks inside the interval read one page" an exact claim.
 */
internal class MovableClock(
    private var now: Instant = Instant.parse("2026-03-01T12:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

    fun advance(by: Duration) {
        now = now.plus(by.toJavaDuration())
    }
}
