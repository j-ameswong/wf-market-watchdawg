package com.watchdawg.market.watch

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Signal and delivery counts (R12.3): how the alert budget (R9a.8) is measured rather than
 * guessed. Every meter is registered at startup, for every watch, so a quiet day reads as zero.
 *
 * Deliveries are counted apart from signals. A suppressed signal is never delivered, and a crash
 * between a send and its mark can deliver one twice (R10.2).
 */
@Component
class AlertMetrics(private val registry: MeterRegistry, watches: Watches) {

    private val signals = watches.all.flatMap { watch ->
        SignalState.entries.map { state ->
            (watch.name to state) to
                Counter.builder(SIGNALS).tag(WATCH, watch.name).tag(STATE, state.db).register(registry)
        }
    }.toMap()

    private val deliveries = Delivery.entries.associateWith {
        Counter.builder(DELIVERIES).tag(OUTCOME, it.tag).register(registry)
    }

    /** A signal of [watch] entered [state]. A watch no longer configured is counted all the same. */
    fun signal(watch: String, state: SignalState) {
        (signals[watch to state] ?: Counter.builder(SIGNALS).tag(WATCH, watch).tag(STATE, state.db).register(registry))
            .increment()
    }

    fun delivery(outcome: Delivery) = deliveries.getValue(outcome).increment()

    private companion object {
        const val SIGNALS = "watchdawg.signals"
        const val DELIVERIES = "watchdawg.deliveries"
        const val WATCH = "watch"
        const val STATE = "state"
        const val OUTCOME = "outcome"
    }
}

/** What one delivery attempt came to. */
enum class Delivery {
    SENT,

    /** Failed, with attempts left. */
    RETRIED,

    /** Failed, and out of attempts (R10.3). */
    FAILED,
    ;

    val tag: String get() = name.lowercase()
}
