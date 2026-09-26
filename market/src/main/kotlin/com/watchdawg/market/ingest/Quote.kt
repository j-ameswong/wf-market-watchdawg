package com.watchdawg.market.ingest

import com.watchdawg.market.wfm.OrderType
import java.math.BigDecimal

/** One market's book at one poll (R4.3). Prices are per unit; a side with no orders has none. */
data class Quote(
    val marketId: Long,
    val bestBuy: BigDecimal?,
    val bestSell: BigDecimal?,
    val bestBuyOnline: BigDecimal?,
    val bestSellOnline: BigDecimal?,
    val buyCount: Int,
    val sellCount: Int,
    val buyOnlineCount: Int,
    val sellOnlineCount: Int,
)

/**
 * A quote for every market in [marketIds], including those [book] has no orders for: an empty book
 * is recorded as empty, not skipped as if it had not been polled.
 */
fun quotes(marketIds: Collection<Long>, book: Collection<ObservedOrder>): List<Quote> {
    val byMarket = book.distinctBy { it.id }.groupBy { it.marketId }
    return marketIds.distinct().map { marketId ->
        val orders = byMarket[marketId].orEmpty()
        val buys = orders.filter { it.type == OrderType.BUY }
        val sells = orders.filter { it.type == OrderType.SELL }
        val onlineBuys = buys.filter { it.ownerOnline }
        val onlineSells = sells.filter { it.ownerOnline }
        Quote(
            marketId = marketId,
            bestBuy = buys.maxOfOrNull { it.unitPrice },
            bestSell = sells.minOfOrNull { it.unitPrice },
            bestBuyOnline = onlineBuys.maxOfOrNull { it.unitPrice },
            bestSellOnline = onlineSells.minOfOrNull { it.unitPrice },
            buyCount = buys.size,
            sellCount = sells.size,
            buyOnlineCount = onlineBuys.size,
            sellOnlineCount = onlineSells.size,
        )
    }
}
