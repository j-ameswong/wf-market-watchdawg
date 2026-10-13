package com.watchdawg.market.baseline

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class ReplaySignal(
    val watch: String,
    val key: String,
    val at: Instant,
    val cooldown: Duration,
    val price: BigDecimal = BigDecimal.ZERO,
    val cheaperBypasses: Boolean = false,
    val batch: String? = null,
)
data class ReplayReport(val admissions: Map<LocalDate, Int>, val budgetSuppressed: Int, val cooldownSuppressed: Int)

data class ReplaySeed(
    val watch: String,
    val key: String,
    val at: Instant,
    val price: BigDecimal,
    val cooling: Boolean,
    val admitted: Boolean = cooling,
)

/** One chronological admission replay shared by all rule families, never one budget per rule. */
fun replay(
    signals: List<ReplaySignal>,
    ceiling: Int,
    countFrom: Instant = Instant.MIN,
    seeds: List<ReplaySeed> = emptyList(),
): ReplayReport {
    val days = sortedMapOf<LocalDate, Int>()
    val seen = HashSet<String>()
    val last = HashMap<String, ReplaySignal>()
    for (seed in seeds.sortedBy { it.at }) {
        seen += seed.key
        if (seed.admitted) {
            val day = seed.at.atZone(ZoneOffset.UTC).toLocalDate()
            days[day] = days.getOrDefault(day, 0) + 1
        }
        if (seed.cooling) last[seed.watch] = ReplaySignal(seed.watch, seed.key, seed.at, Duration.ZERO, seed.price)
    }
    var capped = 0
    var cooled = 0
    var batch: String? = null
    val batchLast = HashMap<String, ReplaySignal>()
    for (signal in signals) {
        if (signal.batch == null || signal.batch != batch) {
            batchLast.clear()
            batch = signal.batch
        }
        if (signal.key in seen) continue
        val previous = batchLast[signal.watch] ?: last[signal.watch]
        if (previous != null && signal.at < previous.at.plus(signal.cooldown) &&
            (!signal.cheaperBypasses || signal.price >= previous.price)
        ) {
            cooled++
            continue
        }
        seen += signal.key
        val day = signal.at.atZone(ZoneOffset.UTC).toLocalDate()
        if (days.getOrDefault(day, 0) >= ceiling) {
            if (signal.at >= countFrom) capped++
        } else {
            days[day] = days.getOrDefault(day, 0) + 1
            if (last[signal.watch]?.at?.isAfter(signal.at) != true) last[signal.watch] = signal
        }
        batchLast[signal.watch] = signal
    }
    return ReplayReport(days, capped, cooled)
}
