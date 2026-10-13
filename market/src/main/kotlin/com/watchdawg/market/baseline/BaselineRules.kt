package com.watchdawg.market.baseline

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.watch.Candidate
import com.watchdawg.market.watch.Watch
import com.watchdawg.market.wfm.OrderType
import com.watchdawg.market.wfm.WfmContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

const val VOLUME_SPIKE = "volume-spike"
const val VANISH_FAST = "vanish-fast"

@Component
class BaselineRules(
    private val jdbc: JdbcTemplate,
    private val context: WfmContext,
    private val watches: com.watchdawg.market.watch.Watches = com.watchdawg.market.watch.Watches(emptyList()),
    private val markets: com.watchdawg.market.store.MarketResolver? = null,
) {
    /** Refresh every configured baseline before any baseline admission can read readiness. */
    fun prepare(at: Instant): Map<Pair<String, Long>, List<Candidate>> = watches.all
        .filter { it.rule in setOf(VOLUME_SPIKE, VANISH_FAST) }.flatMap { watch ->
            markets?.keysOf(watch.itemId).orEmpty().filterValues(watch::selects).keys.map { market ->
                (watch.name to market) to candidates(watch, market, at)
            }
        }.toMap()

    fun candidates(watch: Watch, market: Long, at: Instant): List<Candidate> = when (watch.rule) {
        VOLUME_SPIKE -> volume(watch, market, at)
        VANISH_FAST -> lifetimes(watch, market, at)
        else -> emptyList()
    }

    private fun volume(watch: Watch, market: Long, at: Instant): List<Candidate> {
        val day = at.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant()
        val rows = jdbc.query(
            """
            select bucket, volume from item_stat where market_id = ? and crossplay = ?
                and section = 'closed' and granularity = 'daily' and bucket < ? and bucket >= ? and fetched_at <= ?
            order by bucket
            """,
            { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getInt(2).toBigDecimal() },
            market,
            context.crossplay,
            Timestamp.from(day),
            Timestamp.from(day.minus(100, ChronoUnit.DAYS)),
            Timestamp.from(at),
        )
        val ratios = rows.indices.mapNotNull { i ->
            if (i < 14) return@mapNotNull null
            val window = rows.subList(i - 14, i + 1)
            // Sparse upstream history is retained, but is not silently treated as zero volume.
            if (window.zipWithNext().any { (a, b) ->
                    Duration.between(a.first, b.first) != Duration.ofDays(1)
                }
            ) {
                return@mapNotNull null
            }
            val mean = window.dropLast(1).sumOf { it.second }.divide(BigDecimal(14), 8, RoundingMode.HALF_EVEN)
            if (mean.signum() <= 0) null else rows[i].first to rows[i].second.divide(mean, 8, RoundingMode.HALF_EVEN)
        }
        val current = ratios.lastOrNull()
        val history = ratios.dropLast(1).map { TimedValue(it.first, it.second, "volume|${it.first}") }
        val (learned, _) = calibrateTimed(history, watch.minSamples, watch.targetRate, false)
        val calibration = if (learned.status == "ready" &&
            (current == null || current.first < day.minus(2, ChronoUnit.DAYS))
        ) {
            learned.copy(status = "stale")
        } else {
            learned
        }
        record(watch, market, calibration, at)
        if (current == null || current.first < day.minus(2, ChronoUnit.DAYS) || calibration.status != "ready" ||
            current.second <= calibration.threshold!!
        ) {
            return emptyList()
        }
        return listOf(
            candidate(
                watch,
                market,
                "volume|${current.first}",
                at,
                "Closed-trade daily volume is ${current.second.stripTrailingZeros()} times its preceding 14-day mean; calibrated threshold ${calibration.threshold}",
            ),
        )
    }

    private fun lifetimes(watch: Watch, market: Long, at: Instant): List<Candidate> {
        val historical = lifetimeRows(market, at, current = false)
        val rows = lifetimeRows(market, at, current = true)
        val history = historical.map { TimedValue(it.at, it.seconds, "lifetime|${it.id}|${it.at}") }
        val (calibration, _) = calibrateTimed(history, watch.minSamples, watch.targetRate, true)
        record(watch, market, calibration, at)
        if (calibration.status != "ready") return emptyList()
        return rows.filter { it.at == at && it.seconds < calibration.threshold!! }.map {
            candidate(
                watch,
                market,
                "lifetime|${it.id}|${it.at}",
                at,
                "Listing ${it.id} disappeared after an observed ${it.seconds} seconds (threshold ${calibration.threshold}); this is not proof of a sale",
            )
        }
    }

    private fun lifetimeRows(market: Long, at: Instant, current: Boolean): List<Lifetime> {
        val comparison = if (current) "=" else "<"
        val limit = if (current) "" else "limit 500"
        return jdbc.query(
            """
            select v.order_id, v.observed_at, extract(epoch from (v.observed_at - a.observed_at)) as lifetime
            from order_event v
            join lateral (
                select observed_at, crossplay from order_event a where a.order_id = v.order_id and a.market_id = v.market_id
                    and a.event = 'appeared' and a.observed_at < v.observed_at
                order by a.observed_at desc limit 1
            ) a on a.crossplay = v.crossplay
            where v.market_id = ? and v.crossplay = ? and v.event = 'vanished' and v.observed_at $comparison ?
                and v.observed_at >= ?
            order by v.observed_at desc, v.order_id $limit
            """,
            { rs, _ -> Lifetime(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getBigDecimal(3)) },
            market,
            context.crossplay,
            Timestamp.from(at),
            Timestamp.from(at.minus(90, ChronoUnit.DAYS)),
        ).reversed()
    }

    private fun record(watch: Watch, market: Long, result: Calibration, at: Instant) {
        jdbc.update(
            """
            insert into baseline_calibration(watch, market_id, rule, crossplay, threshold, samples,
                holdout_rate, calibrated_at, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(watch, market_id, rule, crossplay) do update set threshold = excluded.threshold,
                samples = excluded.samples, holdout_rate = excluded.holdout_rate,
                calibrated_at = excluded.calibrated_at, status = excluded.status
            """,
            watch.name, market, watch.rule, context.crossplay, result.threshold, result.samples,
            result.holdoutRate, Timestamp.from(at), result.status,
        )
    }

    private fun candidate(watch: Watch, market: Long, key: String, at: Instant, message: String) = Candidate(
        watch,
        watch.rule,
        ObservedOrder(key, market, OrderType.SELL, 0, null, 0, null, false, BigDecimal.ZERO),
        key = "${watch.name}|$market|$key",
        title = "${watch.itemName}: ${watch.rule}",
        message = message,
    )

    private data class Lifetime(val id: String, val at: Instant, val seconds: BigDecimal)
}
