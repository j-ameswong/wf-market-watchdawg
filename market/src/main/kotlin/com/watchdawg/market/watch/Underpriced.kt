package com.watchdawg.market.watch

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.wfm.OrderType

/** A listing a rule found, before suppression decides whether it becomes a signal. */
data class Candidate(val watch: Watch, val rule: String, val order: ObservedOrder) {
    /** Watch, order and unit price: the same listing at the same price is one condition (R9a.5). */
    val dedupKey: String get() = "${watch.name}|${order.id}|${order.unitPrice.stripTrailingZeros().toPlainString()}"
}

const val UNDERPRICED = "underpriced"

/**
 * The underpriced-listing rule (R9a.3): every live sell order in a market [watch] selects, from an
 * online owner, at or below the watch's unit price, cheapest first.
 *
 * It reads the whole reconciled book rather than its events, because the events record nothing
 * about online status: a cheap listing whose owner comes online changes no event, and is still
 * caught here on the next poll. Offline owners' listings linger for up to 48h and cannot be acted
 * on, so they never qualify.
 *
 * [selected] holds the markets of the book's item that [watch] selects.
 */
fun underpriced(watch: Watch, book: Collection<ObservedOrder>, selected: Set<Long>): List<Candidate> = book
    .filter { it.type == OrderType.SELL && it.ownerOnline && it.marketId in selected }
    .filter { it.unitPrice <= watch.maxUnitPrice }
    .sortedWith(compareBy({ it.unitPrice }, { it.id }))
    .map { Candidate(watch, UNDERPRICED, it) }
