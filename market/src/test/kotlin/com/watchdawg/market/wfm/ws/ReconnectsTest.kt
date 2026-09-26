package com.watchdawg.market.wfm.ws

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Duration.ofSeconds
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C5 T4: reconnect delays (R5.3, decision 6). */
class ReconnectsTest {

    /** Delays at their ceiling, so the ceiling can be read. */
    private val reconnects = Reconnects(ofSeconds(1), Duration.ofMinutes(5), jitter = { it })

    @Test
    fun `the ceiling doubles from one second to the five-minute cap`() {
        val delays = List(12) { reconnects.next(subscribedFor = null) }

        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300, 300).map(::ofSeconds), delays)
    }

    @Test
    fun `a connection that drops within a minute of subscribing still backs off`() {
        reconnects.next(subscribedFor = null)

        assertEquals(ofSeconds(2), reconnects.next(subscribedFor = ofSeconds(59)))
        assertEquals(ofSeconds(4), reconnects.next(subscribedFor = ofSeconds(59)))
    }

    @Test
    fun `a connection that stayed subscribed for a minute resets the ceiling`() {
        repeat(3) { reconnects.next(subscribedFor = null) }

        assertEquals(ofSeconds(1), reconnects.next(subscribedFor = Reconnects.STABLE))
        assertEquals(ofSeconds(2), reconnects.next(subscribedFor = null))
    }

    @Test
    fun `full jitter spreads delays from zero to the ceiling`() {
        val delays = List(1000) { fullJitter(ofSeconds(4)) }

        assertTrue(delays.all { it >= Duration.ZERO && it <= ofSeconds(4) }, "a delay fell outside 0 to 4s")
        assertTrue(delays.any { it < ofSeconds(1) } && delays.any { it > ofSeconds(3) }, "delays did not spread")
    }
}
