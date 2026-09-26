package com.watchdawg.market.ingest

import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.store.UnknownItemException
import com.watchdawg.market.wfm.Order
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit.MICROS

sealed interface BookOutcome {
    data class Reconciled(val events: Int, val quotes: Int) : BookOutcome

    /** A book at least as new was already reconciled for this item, so this one changed nothing. */
    data object Stale : BookOutcome
}

/**
 * The two ways an observation reaches the store (C4).
 *
 * [reconcileBook] takes a full book of one item and may record any change. [ingestPartial] takes
 * orders from the socket or `/recent`, which are neither complete nor necessarily current, and may
 * only add orders never seen before (R4.5, R4.11). Each call is one transaction, so a failure
 * leaves nothing half-written.
 *
 * Markets are resolved before the transaction opens. [MarketResolver] commits a new market on its
 * own, so a market can outlive an ingest that rolls back, which is harmless (R4.6).
 */
@Component
class OrderIngest(
    private val store: OrderStore,
    private val markets: MarketResolver,
    transactions: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactions)

    /**
     * Reconciles [orders], the full book of [itemId], observed at [observedAt] (R4.1–R4.10), and
     * writes one quote per market of the item (R4.3).
     *
     * @throws UnknownItemException if [itemId] is not in the catalog; nothing is written.
     */
    fun reconcileBook(itemId: String, orders: List<Order>, observedAt: Instant): BookOutcome {
        val at = observedAt.truncatedTo(MICROS)
        val book = observe(orders, skipUnknownItems = false) { itemId }

        return transaction.execute {
            if (!store.claimBook(itemId, at)) return@execute BookOutcome.Stale

            val changes = reconcile(store.knownOrders(itemId, book.map { it.id }), book, at)
            // An order a partial source added after the read above already has its appearance.
            val addedMeanwhile = changes.appeared.filterNot { store.insertIfAbsent(it, at) }.mapTo(HashSet()) { it.id }
            changes.changed.forEach { store.update(it, at) }
            store.markGone(changes.vanished.map { it.id }, at)
            val events = changes.events.filterNot { it.orderId in addedMeanwhile }
            store.append(events, Source.BOOK, at)

            val quotes = quotes(markets.marketsOf(itemId), book)
            store.insertQuotes(quotes, at)
            BookOutcome.Reconciled(events.size, quotes.size)
        }
    }

    /**
     * Records `appeared` for each of [orders] the store has never seen, and nothing else (R4.11).
     * An order without an item id, or for an item the catalog lacks yet, is skipped: the first book
     * poll of that item will record it.
     *
     * @return how many orders appeared.
     */
    fun ingestPartial(orders: List<Order>, source: Source, observedAt: Instant): Int {
        require(source != Source.BOOK) { "a full book goes through reconcileBook" }
        val at = observedAt.truncatedTo(MICROS)
        val observed = observe(orders, skipUnknownItems = true) { it.itemId }

        return transaction.execute {
            val added = observed.filter { store.insertIfAbsent(it, at) }
            store.append(
                added.map { reconcile(emptyList(), listOf(it), at).events.single() },
                source,
                at,
            )
            added.size
        }
    }

    /** Resolves each visible order's market, once per distinct tuple in this call. */
    private fun observe(
        orders: List<Order>,
        skipUnknownItems: Boolean,
        itemOf: (Order) -> String?,
    ): List<ObservedOrder> {
        val resolved = HashMap<MarketKey, Long?>()
        return orders.filter { it.visible }.distinctBy { it.id }.mapNotNull { order ->
            val itemId = itemOf(order) ?: return@mapNotNull skip(order, "it names no item")
            val key = MarketKey(itemId, order.subtype, order.rank, order.charges, order.amberStars, order.cyanStars)
            val marketId = resolved.getOrPut(key) { resolve(key, skipUnknownItems) }
                ?: return@mapNotNull skip(order, "item $itemId is not in the catalog")
            ObservedOrder(
                id = order.id,
                marketId = marketId,
                type = order.type,
                platinum = order.platinum,
                perTrade = order.perTrade,
                quantity = order.quantity,
                ownerPlatform = order.user?.platform,
                ownerOnline = order.ownerOnline,
                unitPrice = order.unitPrice,
            )
        }
    }

    private fun resolve(key: MarketKey, skipUnknownItems: Boolean): Long? = try {
        markets.resolve(key)
    } catch (e: UnknownItemException) {
        if (!skipUnknownItems) throw e
        null
    }

    private fun skip(order: Order, reason: String): ObservedOrder? {
        log.warn("skipped order {}: {}", order.id, reason)
        return null
    }
}
