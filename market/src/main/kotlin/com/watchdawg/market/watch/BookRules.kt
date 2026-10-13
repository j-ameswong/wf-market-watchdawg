package com.watchdawg.market.watch

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.wfm.OrderType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

const val PRICE_CROSSING = "price-crossing"
const val SPREAD = "spread"

/** Keyed pure strategies. A result describes one exact market, never blended wildcard dimensions. */
val BOOK_RULES: Map<String, (Watch, Collection<ObservedOrder>) -> Pair<ObservedOrder, String>?> = mapOf(
    PRICE_CROSSING to { watch, orders ->
        val side = OrderType.fromDb(watch.side)
        val online = orders.filter { it.ownerOnline && it.type == side }
        val best = if (side ==
            OrderType.SELL
        ) {
            online.minByOrNull { it.unitPrice }
        } else {
            online.maxByOrNull { it.unitPrice }
        }
        best?.takeIf {
            if (side ==
                OrderType.SELL
            ) {
                it.unitPrice <= watch.maxUnitPrice
            } else {
                it.unitPrice >= watch.maxUnitPrice
            }
        }
            ?.let {
                it to "Online best ${watch.side} crossed ${watch.maxUnitPrice} plat; now ${it.unitPrice} plat/unit"
            }
    },
    SPREAD to { watch, orders ->
        val online = orders.filter { it.ownerOnline }
        val ask = online.filter { it.type == OrderType.SELL }.minByOrNull { it.unitPrice }
        val bid = online.filter { it.type == OrderType.BUY }.maxByOrNull { it.unitPrice }
        if (ask == null || bid == null || ask.unitPrice.signum() <= 0) {
            null
        } else {
            val margin = bid.unitPrice - ask.unitPrice
            val percent = margin.multiply(BigDecimal(100)).divide(ask.unitPrice, 4, RoundingMode.HALF_EVEN)
            if ((watch.minSpread == null || margin >= watch.minSpread) &&
                (
                    watch.minSpreadPercent == null ||
                        margin.multiply(BigDecimal(100)) >= watch.minSpreadPercent.multiply(ask.unitPrice)
                    )
            ) {
                ask to "Online bid ${bid.unitPrice}, ask ${ask.unitPrice}: spread $margin plat ($percent%)"
            } else {
                null
            }
        }
    },
)

fun bookCandidate(watch: Watch, market: Long, result: Pair<ObservedOrder, String>, seenAt: Instant) = Candidate(
    watch,
    watch.rule,
    result.first,
    key = "${watch.name}|${watch.definitionKey()}|$market|$seenAt",
    title = "${watch.itemName}: ${watch.rule}",
    message = result.second,
)

/** Changing a rule definition starts its own condition state; an unchanged restart retains it. */
fun Watch.definitionKey(): String = listOf(
    rule,
    side,
    maxUnitPrice.stripTrailingZeros(),
    minSpread?.stripTrailingZeros(),
    minSpreadPercent?.stripTrailingZeros(),
).joinToString("|")
