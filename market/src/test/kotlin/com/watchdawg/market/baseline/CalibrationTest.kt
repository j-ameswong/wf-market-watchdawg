package com.watchdawg.market.baseline

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CalibrationTest {
    @Test
    fun `insufficient history never provides a threshold`() {
        val result = calibrate(List(5) { BigDecimal.ONE }, 35, BigDecimal("0.1"), false)
        assertEquals("insufficient", result.status)
        assertEquals(null, result.threshold)
    }

    @Test
    fun `holdout cannot move the training threshold and rejects changed regimes`() {
        val training = (1..40).map { it.toBigDecimal() }
        val quiet = calibrate(training + List(20) { BigDecimal.ONE }, 35, BigDecimal("0.1"), false)
        val shifted = calibrate(training + List(20) { BigDecimal(100) }, 35, BigDecimal("0.1"), false)
        assertEquals(quiet.threshold, shifted.threshold)
        assertEquals("ready", quiet.status)
        assertEquals("over-budget", shifted.status)
        assertNotNull(quiet.threshold)
    }

    @Test
    fun `short lifetime calibration uses the opposite tail and is deterministic`() {
        val samples = (1..40).map { it.toBigDecimal() } + List(20) { BigDecimal(40) }
        val a = calibrate(samples, 35, BigDecimal("0.1"), true)
        assertEquals("ready", a.status)
        assertEquals(a, calibrate(samples, 35, BigDecimal("0.1"), true))
        assertEquals(BigDecimal(4), a.threshold)
    }
}
