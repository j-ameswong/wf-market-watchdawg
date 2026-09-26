package com.watchdawg.market.watch

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.store.MarketKey
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit.DAYS

/** @property dailyCeiling signals a UTC day, across all watches, that may be sent (R9a.8). */
@ConfigurationProperties(prefix = "watchdawg.alerts")
data class AlertProperties(val dailyCeiling: Int)

/**
 * Runs the rules over a reconciled book, or over the orders a partial observation added, and
 * decides what reaches the outbox (R9a.5, R9a.8). The caller holds the ingest transaction, so a
 * signal commits or rolls back with the observation behind it (R9a.7).
 *
 * The poll loop and the socket ingest on different threads, so admission is serialised: once the
 * rule has found candidates, the transaction takes one advisory lock before its first cooldown or
 * ceiling read, and holds it to commit (decision 4). One lock for every watch, because the daily
 * ceiling counts across watches. Admission is the last step of both ingest paths, so the lock is
 * held only while it runs, and signals are written only under it, so its holder never waits on a
 * row another holder has. An observation that finds no candidate never takes it.
 *
 * Candidates are taken cheapest first, and each is:
 * - skipped if its dedup key is taken, whatever that signal's state;
 * - skipped if the watch admitted a signal within its cooldown, pending or sent, unless this one is
 *   cheaper. A skipped candidate is not written, so it can qualify again once the cooldown ends;
 * - written as `suppressed` if the day's admissions have reached the ceiling. It keeps its dedup
 *   key, so it is not written again on the next poll, and it is never sent;
 * - otherwise admitted as `pending`.
 */
@Component
@EnableConfigurationProperties(AlertProperties::class)
class Alerts(
    private val watches: Watches,
    private val signals: SignalStore,
    private val props: AlertProperties,
    private val metrics: AlertMetrics,
) {

    /**
     * [markets] maps each market of [book] to its dimensions. [seenAt] is when the book was
     * requested, or when a partial observation arrived; cooldowns and the day are counted on it.
     *
     * @return how many signals were admitted.
     */
    fun evaluate(itemId: String, book: Collection<ObservedOrder>, markets: Map<Long, MarketKey>, seenAt: Instant): Int =
        watches.forItem(itemId).sumOf { watch ->
            val selected = markets.filterValues(watch::selects).keys
            admit(watch, underpriced(watch, book, selected), seenAt)
        }

    private fun admit(watch: Watch, candidates: List<Candidate>, seenAt: Instant): Int {
        if (candidates.isEmpty()) return 0
        signals.lockAdmission()
        var last = signals.lastAdmitted(watch.name)
        var admitted = 0
        for (candidate in candidates) {
            if (signals.exists(candidate.dedupKey)) continue
            val price = candidate.order.unitPrice
            val cooling = last != null && seenAt.isBefore(last.seenAt.plus(watch.cooldown))
            if (cooling && price >= last!!.unitPrice) continue

            val state = if (overCeiling(seenAt)) SignalState.SUPPRESSED else SignalState.PENDING
            if (!signals.admit(candidate, state, seenAt)) continue
            metrics.signal(watch.name, state)
            // A suppressed candidate stands in for an admission here, so one burst over the
            // ceiling writes one row a poll, not one for every cheap listing in the book.
            last = Admitted(seenAt, price)
            if (state == SignalState.PENDING) admitted++
        }
        return admitted
    }

    private fun overCeiling(seenAt: Instant): Boolean {
        val day = seenAt.atZone(UTC).truncatedTo(DAYS).toInstant()
        return signals.admittedBetween(day, day.plus(1, DAYS)) >= props.dailyCeiling
    }
}
