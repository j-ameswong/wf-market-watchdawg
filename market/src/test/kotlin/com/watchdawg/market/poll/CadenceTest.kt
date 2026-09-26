package com.watchdawg.market.poll

import org.junit.jupiter.api.Test
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.assertEquals

/** When poll rounds start, without Spring or a scheduler. */
class CadenceTest {

    private val start = Instant.parse("2026-09-26T12:00:00Z")
    private var now = start
    private val clock = object : Clock() {
        override fun getZone() = UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val cadence = Cadence(Duration.ofMinutes(2), Duration.ofMinutes(1), clock)

    @Test
    fun `the first round waits the initial delay, then one per interval (R6_3)`() {
        assertEquals(start.plusSeconds(60), cadence.nextExecution(SimpleTriggerContext()))

        val first = start.plusSeconds(60)
        val second = cadence.nextExecution(ran(first, finishedAfter = Duration.ofSeconds(5)))
        assertEquals(first.plusSeconds(120), second)
        assertEquals(
            second.plusSeconds(120),
            cadence.nextExecution(ran(second, finishedAfter = Duration.ofSeconds(90))),
        )
    }

    @Test
    fun `an overrun starts the next round as it ends, with no burst of make-up rounds`() {
        val first = start.plusSeconds(60)
        val overran = first.plusSeconds(400)

        val next = cadence.nextExecution(ran(first, finishedAfter = Duration.ofSeconds(400)))
        assertEquals(overran, next)
        assertEquals(Duration.ofSeconds(280), cadence.lateness(overran))

        // The cadence carries on from the late start, not from where it should have been.
        assertEquals(
            overran.plusSeconds(120),
            cadence.nextExecution(ran(overran, finishedAfter = Duration.ofSeconds(1))),
        )
    }

    @Test
    fun `a ten-minute Retry-After at a two-minute cadence means no round for ten minutes (R6_6)`() {
        val first = start.plusSeconds(60)
        now = first.plusSeconds(3)
        cadence.throttled(Duration.ofMinutes(10))

        assertEquals(now.plusSeconds(600), cadence.nextExecution(ran(first, finishedAfter = Duration.ofSeconds(3))))
    }

    @Test
    fun `a throttle without Retry-After leaves the cadence alone`() {
        val first = start.plusSeconds(60)
        cadence.throttled(null)

        assertEquals(first.plusSeconds(120), cadence.nextExecution(ran(first, finishedAfter = Duration.ofSeconds(3))))
    }

    @Test
    fun `a round on time is not late`() {
        val due = cadence.nextExecution(SimpleTriggerContext())
        assertEquals(Duration.ZERO, cadence.lateness(due))
        assertEquals(Duration.ZERO, cadence.lateness(due.minusSeconds(1)))
    }

    private fun ran(at: Instant, finishedAfter: Duration) = SimpleTriggerContext(at, at, at.plus(finishedAfter))
}
