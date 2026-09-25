package com.watchdawg.market.ingest

import com.watchdawg.market.wfm.OrderType
import java.math.BigDecimal
import java.time.Instant

/** Where an observation came from. Only a full [BOOK] can change or vanish an order (R4.5). */
enum class Source(val db: String) {
    BOOK("book"),
    WS("ws"),
    RECENT("recent"),
}

enum class EventKind(val db: String) {
    APPEARED("appeared"),
    PRICE_CHANGED("price_changed"),
    QUANTITY_CHANGED("quantity_changed"),
    VANISHED("vanished"),
}

/** An order as a book or feed reported it, with its market already resolved. */
data class ObservedOrder(
    val id: String,
    val marketId: Long,
    val type: OrderType,
    val platinum: Int,
    val perTrade: Int?,
    val quantity: Int,
    val ownerPlatform: String?,
    val ownerOnline: Boolean,
    val unitPrice: BigDecimal,
)

/** What the store holds for one order. [changedAt] is when its current state was observed. */
data class KnownOrder(
    val id: String,
    val marketId: Long,
    val type: OrderType,
    val platinum: Int,
    val perTrade: Int?,
    val quantity: Int,
    val changedAt: Instant,
    val gone: Boolean,
)

/**
 * One row of the event log (R4.2): the order's values after the event and before it. A vanished
 * order has no "after", so both carry its last known values; only a first appearance has no
 * "before".
 */
data class OrderEvent(
    val orderId: String,
    val marketId: Long,
    val kind: EventKind,
    val type: OrderType,
    val platinum: Int,
    val perTrade: Int?,
    val quantity: Int,
    val prevPlatinum: Int?,
    val prevQuantity: Int?,
)

/**
 * What reconciling one book changes. [appeared] are orders the store has never seen, [changed] are
 * known orders whose stored state becomes the observed one, and [vanished] are known orders the
 * book no longer has.
 */
data class Reconciliation(
    val events: List<OrderEvent>,
    val appeared: List<ObservedOrder>,
    val changed: List<ObservedOrder>,
    val vanished: List<KnownOrder>,
)

/**
 * Classifies a full book of one item against what the store knows (R4.1). [known] must hold every
 * live order of the item, plus any known order in [book], live or gone.
 *
 * A known order whose state was observed at or after [observedAt] is left alone, whether the book
 * has it or not: the book cannot say anything newer about it (R4.10).
 */
fun reconcile(known: Collection<KnownOrder>, book: Collection<ObservedOrder>, observedAt: Instant): Reconciliation {
    val stored = known.associateBy { it.id }
    val events = mutableListOf<OrderEvent>()
    val appeared = mutableListOf<ObservedOrder>()
    val changed = mutableListOf<ObservedOrder>()

    val inBook = book.distinctBy { it.id }
    for (order in inBook) {
        val before = stored[order.id]
        when {
            before == null -> {
                events += order.event(EventKind.APPEARED, before = null)
                appeared += order
            }

            !before.changedAt.isBefore(observedAt) -> Unit

            before.gone -> {
                events += order.event(EventKind.APPEARED, before)
                changed += order
            }

            before.marketId != order.marketId || before.type != order.type -> {
                events += before.vanishedEvent()
                events += order.event(EventKind.APPEARED, before)
                changed += order
            }

            else -> {
                val priceMoved = before.platinum != order.platinum || before.perTrade != order.perTrade
                val quantityMoved = before.quantity != order.quantity
                if (priceMoved) events += order.event(EventKind.PRICE_CHANGED, before)
                if (quantityMoved) events += order.event(EventKind.QUANTITY_CHANGED, before)
                if (priceMoved || quantityMoved) changed += order
            }
        }
    }

    val bookIds = inBook.mapTo(HashSet()) { it.id }
    val vanished = known.filter { !it.gone && it.id !in bookIds && it.changedAt.isBefore(observedAt) }
    events += vanished.map { it.vanishedEvent() }

    return Reconciliation(events, appeared, changed, vanished)
}

private fun ObservedOrder.event(kind: EventKind, before: KnownOrder?) = OrderEvent(
    orderId = id,
    marketId = marketId,
    kind = kind,
    type = type,
    platinum = platinum,
    perTrade = perTrade,
    quantity = quantity,
    prevPlatinum = before?.platinum,
    prevQuantity = before?.quantity,
)

private fun KnownOrder.vanishedEvent() = OrderEvent(
    orderId = id,
    marketId = marketId,
    kind = EventKind.VANISHED,
    type = type,
    platinum = platinum,
    perTrade = perTrade,
    quantity = quantity,
    prevPlatinum = platinum,
    prevQuantity = quantity,
)
