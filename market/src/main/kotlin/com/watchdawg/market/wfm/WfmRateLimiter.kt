package com.watchdawg.market.wfm

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The two independent budgets of R1.2, keyed by route class rather than API version: v1
 * `statistics` paces on [PUBLIC] alongside every v2 route, and only auction search is separate.
 */
enum class Bucket {
    PUBLIC,
    CONTRACT_SEARCH,
}

/**
 * Paces every outbound call to warframe.market.
 *
 * Each [Bucket] hands out evenly spaced turns with no burst allowance. Spending a minute's worth
 * of contract-search budget at once would satisfy "12 req/min" arithmetically, but the upstream
 * rules describe a rate rather than a window we may fill, and a `429` is a bug in our pacing
 * (SPEC 9). A single global [Semaphore] caps in-flight calls on top of that, because `509` is a
 * connection-level signal from Cloudflare rather than a per-route one (R1.4).
 *
 * A turn is taken *before* the permit and never while holding one, so a caller waiting out its
 * pacing delay does not occupy one of the scarce connection slots.
 */
class WfmRateLimiter(
    limits: WfmProperties.Limits,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
) {
    /** Injected alongside the clock so pacing is deterministic under test. */
    fun interface Sleeper {
        fun sleep(duration: Duration)
    }

    private val gates = mapOf(
        Bucket.PUBLIC to Gate(limits.public),
        Bucket.CONTRACT_SEARCH to Gate(limits.contractSearch),
    )

    private val inFlight = Semaphore(limits.maxConcurrency, true)

    /**
     * Runs [call] once this thread's turn on [bucket] has come and a connection slot is free.
     * The slot is returned even when [call] throws, so a failed request cannot leak concurrency.
     */
    fun <T> acquire(bucket: Bucket, call: () -> T): T {
        awaitTurn(gates.getValue(bucket))
        inFlight.acquire()
        try {
            return call()
        } finally {
            inFlight.release()
        }
    }

    private fun awaitTurn(gate: Gate) {
        val now = clock.instant()
        val wait = Duration.between(now, gate.reserve(now))
        if (wait > Duration.ZERO) sleeper.sleep(wait)
    }

    /** One bucket's turnstile: hands out instants spaced by the rate's interval. */
    private class Gate(rate: WfmProperties.Rate) {
        init {
            require(rate.permits > 0) { "a rate must allow at least one permit, was $rate" }
        }

        private val interval: Duration = rate.per.dividedBy(rate.permits.toLong())
        private val lock = ReentrantLock()
        private var next: Instant? = null

        fun reserve(now: Instant): Instant = lock.withLock {
            val turn = maxOf(now, next ?: now)
            next = turn + interval
            turn
        }
    }
}
