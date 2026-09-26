package com.watchdawg.market.wfm.ws

import java.time.Duration
import kotlin.random.Random

/**
 * How long to wait before each reconnect (R5.3, decision 6): full jitter under a ceiling that
 * doubles from [initial] to [max].
 *
 * The ceiling goes back to [initial] only after a connection has stayed subscribed for [STABLE], so a
 * connection that drops right after subscribing still backs off. Pure, like `Cadence`: the socket
 * owns the timing.
 */
class Reconnects(
    private val initial: Duration,
    private val max: Duration,
    private val jitter: (ceiling: Duration) -> Duration = ::fullJitter,
) {
    private var ceiling = initial

    /** [subscribedFor] is how long the connection that just ended was subscribed, or null if never. */
    fun next(subscribedFor: Duration?): Duration {
        if (subscribedFor != null && subscribedFor >= STABLE) ceiling = initial
        val delay = jitter(ceiling)
        ceiling = minOf(ceiling.multipliedBy(2), max)
        return delay
    }

    companion object {
        val STABLE: Duration = Duration.ofMinutes(1)
    }
}

/** Anywhere from zero to [ceiling], so reconnecting clients spread out rather than arrive together. */
fun fullJitter(ceiling: Duration): Duration = Duration.ofMillis(Random.nextLong(ceiling.toMillis() + 1))
