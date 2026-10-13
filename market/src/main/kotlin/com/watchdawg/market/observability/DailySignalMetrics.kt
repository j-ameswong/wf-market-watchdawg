package com.watchdawg.market.observability

import com.watchdawg.market.watch.SignalState
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset.UTC

/**
 * Durable UTC-today counts, read on demand without logs or an in-memory reset timer.
 * Admissions are grouped by seen_at and current state, matching the admission budget's day.
 * Successes use notified_at: a prior day's signal finalized today belongs to today's successes.
 * These gauges are distinct from the existing process-lifetime signal and delivery-attempt counters.
 */
@Component
class DailySignalMetrics(
    private val jdbc: NamedParameterJdbcTemplate,
    transactions: PlatformTransactionManager,
    registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    // Even a sample taken on an ingest/dispatcher thread must not expose its uncommitted writes.
    private val committedRead = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    init {
        SignalState.entries.forEach { state ->
            Gauge.builder("watchdawg.admissions.today", this) { it.admissions(state).toDouble() }
                .description("Signals observed during the current UTC day, by current state")
                .tag("state", state.db)
                .strongReference(true)
                .register(registry)
        }
        Gauge.builder("watchdawg.notifications.today", this) { it.notifications().toDouble() }
            .description("Signals finalized successfully during the current UTC day")
            .strongReference(true)
            .register(registry)
    }

    private fun admissions(state: SignalState): Long = count(
        "state = :state and seen_at >= :from and seen_at < :until",
        mapOf("state" to state.db),
    )

    private fun notifications(): Long = count(
        "state = 'sent' and notified_at >= :from and notified_at < :until",
    )

    private fun count(predicate: String, parameters: Map<String, Any> = emptyMap()): Long {
        val day = clock.instant().atZone(UTC).toLocalDate()
        val bounds = mapOf(
            "from" to Timestamp.from(day.atStartOfDay(UTC).toInstant()),
            "until" to Timestamp.from(day.plusDays(1).atStartOfDay(UTC).toInstant()),
        )
        return committedRead.execute {
            jdbc.queryForObject("select count(*) from signal where $predicate", parameters + bounds, Long::class.java)!!
        }!!
    }
}
