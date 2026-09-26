package com.watchdawg.market

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.scheduling.support.PeriodicTrigger
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A task on its own thread never overlaps itself, however much it overruns. */
@Timeout(10, unit = SECONDS)
class OwnThreadScheduleTest {

    @Test
    fun `runs never overlap and share no thread with anything else`() {
        val running = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        val threads = mutableSetOf<String>()
        val done = CountDownLatch(3)

        // A 1ms rate against a 50ms task: every run is due long before the last one ends.
        val schedule = OwnThreadSchedule("probe", {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            synchronized(threads) { threads += Thread.currentThread().name }
            Thread.sleep(50)
            running.decrementAndGet()
            done.countDown()
        }, PeriodicTrigger(Duration.ofMillis(1)).apply { setFixedRate(true) })

        schedule.start()
        try {
            assertTrue(done.await(5, SECONDS))
        } finally {
            schedule.stop()
        }

        assertEquals(1, mostAtOnce.get())
        assertEquals(setOf("probe-1"), threads)
    }
}
