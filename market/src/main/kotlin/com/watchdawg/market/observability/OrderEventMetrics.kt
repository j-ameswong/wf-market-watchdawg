package com.watchdawg.market.observability

import com.watchdawg.market.ingest.Source
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Collections
import java.util.IdentityHashMap

/** Process-lifetime throughput of committed event rows, not attempted observations or replays. */
@Component
class OrderEventMetrics(registry: MeterRegistry) {
    private val counters = Source.entries.associateWith {
        Counter.builder("watchdawg.order.events")
            .description("Committed order-event rows since process start")
            .tag("source", it.db)
            .register(registry)
    }

    /** Wrap the central append so a transaction is required before any database writes occur. */
    fun appendEvents(source: Source, append: () -> IntArray) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "order-event append requires a transaction"
        }
        check(TransactionSynchronizationManager.isSynchronizationActive())
        val counts = append()
        // PostgreSQL normally returns exact per-row counts (including zero for conflicts).
        // Never interpret JDBC SUCCESS_NO_INFO as a row: batch rewriting must stay disabled.
        check(counts.all { it >= 0 }) { "order-event metrics require exact JDBC update counts" }
        val inserted = counts.sumOf { it.toLong() }
        if (inserted == 0L) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                private var retained = true
                private val laterSavepoints = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

                override fun savepoint(savepoint: Any) {
                    laterSavepoints.add(savepoint)
                }

                override fun savepointRollback(savepoint: Any) {
                    // A savepoint created before this append discards these rows when rolled back.
                    if (savepoint !in laterSavepoints) retained = false
                }

                override fun afterCommit() {
                    if (retained) counters.getValue(source).increment(inserted.toDouble())
                }
            },
        )
    }
}
