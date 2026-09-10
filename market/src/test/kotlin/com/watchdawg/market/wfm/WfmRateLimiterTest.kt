package com.watchdawg.market.wfm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain JUnit -- no Spring context, no container. `WfmRateLimiter` touches neither, and booting a
 * Postgres to test a turnstile buys nothing (plan Decision 5).
 */
@Timeout(value = 10, unit = SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WfmRateLimiterTest {

    @Test
    fun `sequential acquires on a bucket are spaced by that bucket's rate`() {
        val clock = MutableClock(EPOCH)
        val limiter = WfmRateLimiter(limits(), clock, AdvancingSleeper(clock))

        repeat(5) { limiter.acquire(Bucket.PUBLIC) {} }

        // public is 2 req/s, so N=5 acquires cost (N-1)/L = 2s -- the first turn is free.
        assertEquals(Duration.ofSeconds(2), Duration.between(EPOCH, clock.instant()))
    }

    @Test
    fun `exhausting one bucket does not delay the other`() {
        val clock = MutableClock(EPOCH)
        val sleeper = AdvancingSleeper(clock)
        val limiter = WfmRateLimiter(limits(), clock, sleeper)

        repeat(5) { limiter.acquire(Bucket.CONTRACT_SEARCH) {} }
        assertEquals(List(4) { Duration.ofSeconds(5) }, sleeper.slept, "contract-search is 12/min")

        sleeper.slept.clear()
        limiter.acquire(Bucket.PUBLIC) {}

        assertEquals(emptyList(), sleeper.slept, "a public call waited on contract-search's budget")
    }

    @Test
    fun `pacing holds in real time, not only on the injected clock`() {
        // Inflated to 20 req/s so the test costs ~200ms rather than the 2s the configured rate would.
        val limiter = WfmRateLimiter(limits(public = WfmProperties.Rate(20, Duration.ofSeconds(1))))

        val startedAt = System.nanoTime()
        repeat(5) { limiter.acquire(Bucket.PUBLIC) {} }
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed >= Duration.ofMillis(200), "5 acquires at 20 req/s took only $elapsed")
    }

    @Test
    fun `a third concurrent acquire waits for a permit`() {
        val limiter = WfmRateLimiter(limits(public = UNPACED, maxConcurrency = 2))
        val holding = CountDownLatch(2)
        val release = CountDownLatch(1)
        repeat(2) {
            thread(isDaemon = true) {
                limiter.acquire(Bucket.PUBLIC) {
                    holding.countDown()
                    release.await()
                }
            }
        }
        assertTrue(holding.await(5, SECONDS), "the first two acquires never got their permits")

        val third = CountDownLatch(1)
        thread(isDaemon = true) { limiter.acquire(Bucket.PUBLIC) { third.countDown() } }

        assertFalse(third.await(150, MILLISECONDS), "a third call ran while both permits were held")
        release.countDown()
        assertTrue(third.await(5, SECONDS), "the third call never got a released permit")
    }

    @Test
    fun `a permit is released even when the call throws`() {
        // One permit, so a leak on the throwing path parks every later acquire forever.
        val limiter = WfmRateLimiter(limits(public = UNPACED, maxConcurrency = 1))

        repeat(2) {
            assertFailsWith<IllegalStateException> { limiter.acquire(Bucket.PUBLIC) { error("boom") } }
        }

        var ran = false
        limiter.acquire(Bucket.PUBLIC) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `narrowing concurrency gives up a slot, down to a floor of one`() {
        val limiter = WfmRateLimiter(limits(public = UNPACED, maxConcurrency = 2))

        assertEquals(1, limiter.narrowConcurrency())
        assertEquals(1, limiter.narrowConcurrency(), "the cap fell below one connection")

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        thread(isDaemon = true) {
            limiter.acquire(Bucket.PUBLIC) {
                holding.countDown()
                release.await()
            }
        }
        assertTrue(holding.await(5, SECONDS), "the first acquire never got its permit")

        val second = CountDownLatch(1)
        thread(isDaemon = true) { limiter.acquire(Bucket.PUBLIC) { second.countDown() } }

        assertFalse(second.await(150, MILLISECONDS), "a second call ran after the cap narrowed to one")
        release.countDown()
        assertTrue(second.await(5, SECONDS), "the second call never got the released permit")
    }

    @Test
    fun `each turn is counted against its own bucket`() {
        // T4's retry proves it spent budget by this counter, so the counter has to be per bucket.
        val limiter = WfmRateLimiter(limits(public = UNPACED, contractSearch = UNPACED))

        repeat(3) { limiter.acquire(Bucket.PUBLIC) {} }
        limiter.acquire(Bucket.CONTRACT_SEARCH) {}

        assertEquals(3L, limiter.turnsTaken(Bucket.PUBLIC))
        assertEquals(1L, limiter.turnsTaken(Bucket.CONTRACT_SEARCH))
    }

    private fun limits(
        public: WfmProperties.Rate = WfmProperties.Rate(2, Duration.ofSeconds(1)),
        contractSearch: WfmProperties.Rate = WfmProperties.Rate(12, Duration.ofMinutes(1)),
        maxConcurrency: Int = 2,
    ) = WfmProperties.Limits(public, contractSearch, maxConcurrency, Duration.ofSeconds(60))

    /** A clock the sleeper drives, so pacing is asserted without spending wall time. */
    private class MutableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(by: Duration) {
            now += by
        }
    }

    private class AdvancingSleeper(private val clock: MutableClock) : WfmRateLimiter.Sleeper {
        val slept = mutableListOf<Duration>()

        override fun sleep(duration: Duration) {
            slept += duration
            clock.advance(duration)
        }
    }

    private companion object {
        val EPOCH: Instant = Instant.parse("2026-09-10T00:00:00Z")

        /** Fast enough that pacing never explains a delay -- the concurrency tests want the semaphore. */
        val UNPACED = WfmProperties.Rate(1000, Duration.ofSeconds(1))
    }
}
