package com.watchdawg.market.baseline

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class BudgetReplayTest {
    @Test
    fun `recorded candidate order preserves cooldown-skipped keys for the next day`() {
        val at = Instant.parse("2026-10-10T12:00:00Z")
        val cheap = ReplaySignal("threshold", "z-cheap", at, Duration.ofHours(1), BigDecimal.ONE, true, "warmup")
        val expensive = cheap.copy(key = "a-expensive", price = BigDecimal.TWO)
        val tomorrow = at.plusSeconds(86400)
        val result = replay(
            listOf(
                cheap,
                expensive,
                expensive.copy(at = tomorrow, batch = "next"),
                ReplaySignal("baseline", "baseline", tomorrow, Duration.ZERO),
            ),
            1,
            at.plusSeconds(43200),
        )
        assertEquals(1, result.budgetSuppressed)
    }

    @Test
    fun `shared daily ceiling includes other rules while replay deduplicates and applies cooldown`() {
        val at = Instant.parse("2026-10-13T23:59:59Z")
        val a = ReplaySignal("threshold", "a", at, Duration.ofHours(1), BigDecimal.TEN, true)
        val report = replay(
            listOf(
                a,
                a,
                a.copy(key = "cool"),
                ReplaySignal("baseline", "b", at.plusMillis(1), Duration.ZERO),
                ReplaySignal("baseline", "next", at.plusSeconds(1), Duration.ZERO),
            ),
            1,
        )
        assertEquals(1, report.budgetSuppressed)
        assertEquals(1, report.cooldownSuppressed)
        assertEquals(listOf(1, 1), report.admissions.values.toList())
    }

    @Test
    fun `one simultaneous observation batch cannot train and validate a lifetime rule`() {
        val at = Instant.parse("2026-10-13T00:00:00Z")
        val values = (1..100).map { TimedValue(at, it.toBigDecimal(), "$it") }
        assertEquals("insufficient", calibrateTimed(values, 35, BigDecimal("0.1"), true).first.status)
    }
}
