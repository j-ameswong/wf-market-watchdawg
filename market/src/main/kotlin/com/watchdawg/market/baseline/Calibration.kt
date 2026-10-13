package com.watchdawg.market.baseline

import java.math.BigDecimal
import java.math.RoundingMode

/** Train on earlier observations, validate on later ones. The evaluated observation is never here. */
data class Calibration(val threshold: BigDecimal?, val samples: Int, val holdoutRate: BigDecimal?, val status: String)

fun calibrate(values: List<BigDecimal>, minimum: Int, targetRate: BigDecimal, lowerIsUnusual: Boolean): Calibration {
    require(minimum >= 10 && targetRate > BigDecimal.ZERO && targetRate < BigDecimal.ONE)
    if (values.size < minimum) return Calibration(null, values.size, null, "insufficient")
    val split = values.size * 2 / 3
    val train = values.take(split).sorted()
    val holdout = values.drop(split)
    // An empirical quantile selected from training data, rather than a guessed price/volume cutoff.
    val quantile = if (lowerIsUnusual) targetRate else BigDecimal.ONE - targetRate
    val index = quantile.multiply((train.size - 1).toBigDecimal()).toInt().coerceIn(train.indices)
    val threshold = train[index]
    val fired = holdout.count { if (lowerIsUnusual) it < threshold else it > threshold }
    val rate = fired.toBigDecimal().divide(holdout.size.toBigDecimal(), 6, RoundingMode.HALF_EVEN)
    return Calibration(threshold, values.size, rate, if (rate <= targetRate) "ready" else "over-budget")
}

data class TimedValue(val at: java.time.Instant, val value: BigDecimal, val key: String)

/** A held-out period contains whole observation batches and at least a day of later evidence. */
fun calibrateTimed(
    values: List<TimedValue>,
    minimum: Int,
    targetRate: BigDecimal,
    lower: Boolean,
): Pair<Calibration, List<TimedValue>> {
    val times = values.map { it.at }.distinct().sorted()
    if (values.size < minimum ||
        times.size < 3
    ) {
        return Calibration(null, values.size, null, "insufficient") to emptyList()
    }
    val cutoff = times[times.size * 2 / 3]
    val train = values.filter { it.at < cutoff }
    val holdout = values.filter { it.at >= cutoff }
    if (train.isEmpty() || java.time.Duration.between(cutoff, times.last()) < java.time.Duration.ofDays(1)) {
        return Calibration(null, values.size, null, "insufficient") to emptyList()
    }
    val sorted = train.map { it.value }.sorted()
    val quantile = if (lower) targetRate else BigDecimal.ONE - targetRate
    val index = quantile.multiply((sorted.size - 1).toBigDecimal()).toInt().coerceIn(sorted.indices)
    val threshold = sorted[index]
    val fired = holdout.filter { if (lower) it.value < threshold else it.value > threshold }
    val rate = fired.size.toBigDecimal().divide(holdout.size.toBigDecimal(), 6, RoundingMode.HALF_EVEN)
    return Calibration(threshold, values.size, rate, if (rate <= targetRate) "ready" else "over-budget") to holdout
}
