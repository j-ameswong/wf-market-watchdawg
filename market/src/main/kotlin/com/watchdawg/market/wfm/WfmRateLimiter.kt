package com.watchdawg.market.wfm

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The two rate budgets, which are independent of each other (R1.2).
 *
 * Which one applies is decided by the route, not by the API version. Auction search is the only
 * thing on [CONTRACT_SEARCH]; everything else shares [PUBLIC], the v1 `statistics` route included.
 */
enum class Bucket {
    PUBLIC,
    CONTRACT_SEARCH,
}

/**
 * Paces every outbound call to warframe.market.
 *
 * Each [Bucket] hands out turns at a fixed spacing, with no bursting. Firing off a whole minute of
 * contract-search budget at once would still add up to "12 req/min", but the upstream rules
 * describe a rate, not a window we are free to fill however we like. We treat a `429` as our own
 * bug (SPEC 9).
 *
 * On top of the pacing, one shared [Semaphore] limits how many calls are in flight at once. That
 * is a separate concern: a `509` from Cloudflare is about open connections, not about any one
 * route (R1.4).
 *
 * A caller takes its turn *before* it takes a permit, never while holding one. Otherwise a thread
 * waiting out its pacing delay would be sitting on a connection slot it is not using.
 */
class WfmRateLimiter(
    limits: WfmProperties.Limits,
    private val metrics: WfmMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
) {
    /** Injectable, like the clock, so tests can assert pacing without really sleeping. */
    fun interface Sleeper {
        fun sleep(duration: Duration)
    }

    private val gates = mapOf(
        Bucket.PUBLIC to Gate(limits.public),
        Bucket.CONTRACT_SEARCH to Gate(limits.contractSearch),
    )

    private val inFlight = Permits(limits.maxConcurrency)
    private val concurrencyLock = ReentrantLock()
    private var concurrency = limits.maxConcurrency

    init {
        metrics.trackConcurrency(::maxConcurrency)
    }

    /**
     * Waits for this thread's turn on [bucket] and for a free connection slot, then runs [call].
     *
     * The slot is always released, including when [call] throws, so a failing request cannot leak
     * concurrency.
     */
    fun <T> acquire(bucket: Bucket, call: () -> T): T {
        metrics.requestIssued(bucket, awaitTurn(gates.getValue(bucket)))
        inFlight.acquire()
        try {
            return call()
        } finally {
            inFlight.release()
        }
    }

    /** How many connection slots are currently allowed. Only [narrowConcurrency] changes it. */
    val maxConcurrency: Int get() = concurrencyLock.withLock { concurrency }

    /**
     * Gives up one connection slot in answer to a `509`, down to a minimum of one (R1.4).
     *
     * Waiting alone would only put off the same collision. The server told us we had too many
     * connections open, not that we were going too fast, so the cap itself has to come down.
     *
     * It never goes back up; only a restart resets it. ADR-0004 treats a `509` as a bug in our own
     * budgeting, and creeping back toward a limit the server has already refused is exactly the
     * traffic pattern the upstream rules exist to stop.
     */
    fun narrowConcurrency(): Int = concurrencyLock.withLock {
        if (concurrency <= 1) return@withLock concurrency
        inFlight.reduce(1)
        --concurrency
    }

    /** Returns how long the caller waited. [WfmMetrics] records it as a measure of budget pressure. */
    private fun awaitTurn(gate: Gate): Duration {
        val now = clock.instant()
        val wait = Duration.between(now, gate.reserve(now))
        if (wait <= Duration.ZERO) return Duration.ZERO
        sleeper.sleep(wait)
        return wait
    }

    /** Subclassed only because [Semaphore.reducePermits] is protected, and R1.4 needs to call it. */
    private class Permits(permits: Int) : Semaphore(permits, true) {
        fun reduce(n: Int) = reducePermits(n)
    }

    /** One bucket's queue. Hands out call times spaced one interval apart. */
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
