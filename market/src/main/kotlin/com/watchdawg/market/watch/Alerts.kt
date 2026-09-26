package com.watchdawg.market.watch

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.store.MarketKey
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Runs the rules over a reconciled book and admits what they find to the outbox. The caller holds
 * the reconcile transaction, so a signal commits or rolls back with the book behind it (R9a.7),
 * and the item's book claim serialises admissions for its watches.
 */
@Component
class Alerts(private val watches: Watches, private val signals: SignalStore) {

    /**
     * [markets] maps each market of [book] to its dimensions. [seenAt] is when the book was
     * requested.
     *
     * @return how many signals were admitted.
     */
    fun evaluate(itemId: String, book: Collection<ObservedOrder>, markets: Map<Long, MarketKey>, seenAt: Instant): Int =
        watches.forItem(itemId).sumOf { watch ->
            val selected = markets.filterValues(watch::selects).keys
            underpriced(watch, book, selected).count { signals.admit(it, SignalState.PENDING, seenAt) }
        }
}
