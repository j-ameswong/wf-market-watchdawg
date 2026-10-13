package com.watchdawg.market.baseline

import com.watchdawg.market.contracts.ContractProperties
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.watch.AlertProperties
import com.watchdawg.market.watch.Candidate
import com.watchdawg.market.watch.UNDERPRICED
import com.watchdawg.market.watch.Watch
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.WfmContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Record prospective candidate demand before admission. Only a complete UTC day under one
 * continuously running configuration can establish a combined budget fit. Restarts, changed
 * thresholds or newly observed markets start fresh evidence; imported statistics alone cannot.
 * Called under the shared admission lock and transaction, including for contract candidates.
 */
@Component
class BaselineBudget(
    private val jdbc: JdbcTemplate,
    private val watches: Watches,
    private val contracts: ContractProperties,
    private val context: WfmContext,
    private val markets: MarketResolver,
    private val budget: AlertProperties,
    @org.springframework.beans.factory.annotation.Value("\${watchdawg.socket.enabled:true}")
    private val socketEnabled: Boolean = true,
    @org.springframework.beans.factory.annotation.Value("\${watchdawg.contracts.enabled:false}")
    private val contractsEnabled: Boolean = false,
    @org.springframework.beans.factory.annotation.Value("\${wfm.poll.interval:2m}")
    private val pollInterval: Duration = Duration.ofMinutes(2),
    private val operator: com.watchdawg.market.operator.OperatorState? = null,
) {
    private val run = UUID.randomUUID().toString()

    @org.springframework.transaction.annotation.Transactional
    fun evaluated(stream: String, at: Instant, maxGap: Duration) {
        if (watches.all.none { it.rule in setOf(VOLUME_SPIKE, VANISH_FAST) }) return
        jdbc.execute("select pg_advisory_xact_lock(726193504)")
        jdbc.update(
            """
            insert into baseline_coverage(stream, run, started_at, last_at, max_gap_seconds)
            values (?, ?, ?, ?, ?)
            on conflict(stream) do update set run = excluded.run,
                started_at = case when baseline_coverage.run <> excluded.run
                    or excluded.last_at - baseline_coverage.last_at > make_interval(secs => excluded.max_gap_seconds)
                    then excluded.started_at else baseline_coverage.started_at end,
                last_at = excluded.last_at, max_gap_seconds = excluded.max_gap_seconds
            where excluded.last_at >= baseline_coverage.last_at or baseline_coverage.run <> excluded.run
        """,
            stream,
            run,
            Timestamp.from(at),
            Timestamp.from(at),
            maxGap.seconds,
        )
    }

    fun evaluatedItem(itemId: String, at: Instant) = evaluated("item|$itemId", at, pollInterval.multipliedBy(2))

    fun observe(watch: Watch, candidates: List<Candidate>, at: Instant): Boolean {
        val baselineWatches = watches.all.filter { it.rule in setOf(VOLUME_SPIKE, VANISH_FAST) }
        if (baselineWatches.isEmpty()) return false
        val parts =
            mutableListOf(run, watches.all.toString(), contracts.toString(), context.toString(), budget.toString())
        parts += "operator-revision=${operator?.snapshot?.view?.revision ?: 0}"
        var complete = true
        var fresh = true
        for (target in baselineWatches.sortedBy { it.name }) {
            val selected = markets.keysOf(target.itemId).filterValues(target::selects).keys.sorted()
            if (selected.isEmpty()) complete = false
            for (market in selected) {
                val calibration = jdbc.query(
                    """
                    select status, threshold, calibrated_at from baseline_calibration
                    where watch = ? and market_id = ? and rule = ? and crossplay = ?
                    """,
                    { rs, _ -> Triple(rs.getString(1), rs.getBigDecimal(2), rs.getTimestamp(3).toInstant()) },
                    target.name,
                    market,
                    target.rule,
                    context.crossplay,
                ).singleOrNull()
                if (calibration?.first != "ready") complete = false
                if (calibration == null ||
                    Duration.between(calibration.third, at).abs() > Duration.ofMillis(1)
                ) {
                    fresh = false
                }
                parts += "${target.name}|$market|${calibration?.first}|${calibration?.second?.stripTrailingZeros()}"
            }
        }
        parts += "complete=$complete"
        // A permanent delivery failure removes cooldown state. Restart evidence rather than replay
        // a different state machine with an unknown failure instant.
        parts += "failed=" + jdbc.queryForObject("select count(*) from signal where state = 'failed'", Long::class.java)
        val required = watches.all.map { "item|${it.itemId}" }.toMutableSet()
        required += baselineWatches.filter { it.rule == VOLUME_SPIKE }.map { "statistics|${it.slug}" }
        if (socketEnabled) required += "socket"
        if (contractsEnabled) {
            required +=
                (
                    operator?.activeContracts(contracts.watches) ?: contracts.watches.filter {
                        it.enabled
                    }
                    ).map { "contract|${it.kind}|${it.weapon}" }
        }
        val coverage = jdbc.query(
            "select stream, started_at, last_at, max_gap_seconds from baseline_coverage where run = ?",
            { rs, _ ->
                rs.getString(1) to
                    Triple(rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getLong(4))
            },
            run,
        ).toMap()
        // A collection gap starts a new replay, seeded from actual admissions, not an incomplete shadow past.
        parts += required.sorted().map { "$it|${coverage[it]?.first}" }
        val signature = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val previous = jdbc.query("select signature, started_at from baseline_budget_epoch") { rs, _ ->
            rs.getString(1) to rs.getTimestamp(2).toInstant()
        }.singleOrNull()
        val start = if (previous?.first == signature) previous.second else at
        if (previous?.first != signature) {
            jdbc.update("delete from baseline_shadow")
            jdbc.update(
                """
                insert into baseline_budget_epoch(singleton, signature, started_at, status)
                values (true, ?, ?, 'insufficient') on conflict(singleton) do update
                set signature = excluded.signature, started_at = excluded.started_at,
                    checked_until = null, budget_suppressed = null, status = 'insufficient'
                """,
                signature,
                Timestamp.from(start),
            )
        }
        val batch = UUID.randomUUID().toString()
        for (candidate in candidates) {
            jdbc.update(
                """
                insert into baseline_shadow(watch, dedup_key, seen_at, cooldown_seconds, unit_price, cheaper_bypasses, batch)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                watch.name,
                candidate.dedupKey,
                Timestamp.from(at),
                watch.cooldown.seconds,
                candidate.order.unitPrice,
                watch.rule == UNDERPRICED,
                batch,
            )
        }
        val covered = required.all { stream -> coverage[stream]?.let { at <= it.second.plusSeconds(it.third) } == true }
        val coveredStart = maxOf(start, required.mapNotNull { coverage[it]?.first }.maxOrNull() ?: at)
        val firstDay = coveredStart.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
        val until = at.truncatedTo(ChronoUnit.DAYS)
        if (!complete || !covered || until <= firstDay || (watch.rule in setOf(VOLUME_SPIKE, VANISH_FAST) && !fresh)) {
            jdbc.update(
                "update baseline_budget_epoch set status = 'insufficient', checked_until = null, budget_suppressed = null",
            )
            return false
        }
        val shadow = jdbc.query(
            "select * from baseline_shadow where seen_at < ? order by id",
            { rs, _ ->
                ReplaySignal(
                    rs.getString("watch"),
                    rs.getString("dedup_key"),
                    rs.getTimestamp("seen_at").toInstant(),
                    Duration.ofSeconds(rs.getLong("cooldown_seconds")),
                    rs.getBigDecimal("unit_price"),
                    rs.getBoolean("cheaper_bypasses"),
                    rs.getString("batch"),
                )
            },
            Timestamp.from(until),
        )
        // Seed all permanent dedup keys and the last pending/sent cooldown before this run.
        val seeds = jdbc.query(
            "select watch, dedup_key, seen_at, unit_price, state from signal where seen_at < ? order by id",
            { rs, _ ->
                ReplaySeed(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getBigDecimal(4),
                    rs.getString(5) in setOf("pending", "sent"),
                    rs.getString(5) != "suppressed",
                )
            },
            Timestamp.from(start),
        )
        val report = replay(shadow, operator?.ceiling(budget.dailyCeiling) ?: budget.dailyCeiling, firstDay, seeds)
        jdbc.update(
            "update baseline_budget_epoch set checked_until = ?, budget_suppressed = ?, status = ?",
            Timestamp.from(until),
            report.budgetSuppressed,
            if (report.budgetSuppressed ==
                0
            ) {
                "ready"
            } else {
                "over-budget"
            },
        )
        return report.budgetSuppressed == 0
    }
}
