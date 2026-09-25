package com.watchdawg.market.store

import com.watchdawg.market.ingest.KnownOrder
import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.ingest.OrderEvent
import com.watchdawg.market.ingest.Quote
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.wfm.OrderType
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Order state, the event log and quotes. Every method runs in the caller's transaction; the ingest
 * service decides the boundaries.
 */
@Component
class OrderStore(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Records [observedAt] as the latest book for [itemId] and returns true, unless a book at least
     * as new is already recorded (R4.9). The claimed row stays locked until the transaction ends,
     * so a second reconciliation of the same item waits, then finds itself stale.
     */
    fun claimBook(itemId: String, observedAt: Instant): Boolean = jdbc.query(
        """
        insert into order_book (item_id, observed_at) values (:itemId, :observedAt)
        on conflict (item_id) do update set observed_at = excluded.observed_at
        where order_book.observed_at < excluded.observed_at
        returning item_id
        """,
        MapSqlParameterSource("itemId", itemId).addValue("observedAt", Timestamp.from(observedAt)),
    ) { rs, _ -> rs.getString(1) }.isNotEmpty()

    /** Every live order of [itemId], plus the known orders among [ids], live or gone. */
    fun knownOrders(itemId: String, ids: Collection<String>): List<KnownOrder> {
        val params = MapSqlParameterSource("itemId", itemId).addValue("ids", ids)
        val orInBook = if (ids.isEmpty()) "" else " or o.id in (:ids)"
        return jdbc.query(
            """
            select o.id, o.market_id, o.type, o.platinum, o.per_trade, o.quantity, o.changed_at,
                   o.gone_at is not null as gone
            from wfm_order o
            join market m on m.id = o.market_id
            where m.item_id = :itemId and (o.gone_at is null$orInBook)
            """,
            params,
        ) { rs, _ -> rs.toKnownOrder() }
    }

    /**
     * Inserts an order not seen before and returns true, or returns false if it already exists.
     * Two observers meeting the same new order therefore record one appearance between them.
     */
    fun insertIfAbsent(order: ObservedOrder, observedAt: Instant): Boolean = jdbc.query(
        """
        insert into wfm_order (id, market_id, type, platinum, per_trade, quantity, owner_platform,
                               first_seen_at, changed_at)
        values (:id, :marketId, :type, :platinum, :perTrade, :quantity, :ownerPlatform,
                :observedAt, :observedAt)
        on conflict (id) do nothing
        returning id
        """,
        order.params(observedAt),
    ) { rs, _ -> rs.getString(1) }.isNotEmpty()

    /** A known order takes the observed values and is live again, whatever it was before. */
    fun update(order: ObservedOrder, observedAt: Instant) {
        jdbc.update(
            """
            update wfm_order
            set market_id = :marketId, type = :type, platinum = :platinum, per_trade = :perTrade,
                quantity = :quantity, owner_platform = :ownerPlatform, changed_at = :observedAt,
                gone_at = null
            where id = :id
            """,
            order.params(observedAt),
        )
    }

    fun markGone(ids: Collection<String>, observedAt: Instant) {
        if (ids.isEmpty()) return
        jdbc.update(
            "update wfm_order set gone_at = :observedAt, changed_at = :observedAt where id in (:ids)",
            MapSqlParameterSource("ids", ids).addValue("observedAt", Timestamp.from(observedAt)),
        )
    }

    /** Appends to the event log. A row already there is kept, so a replay adds nothing (R4.4). */
    fun append(events: Collection<OrderEvent>, source: Source, observedAt: Instant) {
        if (events.isEmpty()) return
        jdbc.batchUpdate(
            """
            insert into order_event (observed_at, market_id, order_id, event, source, type, platinum,
                                     per_trade, quantity, prev_platinum, prev_quantity)
            values (:observedAt, :marketId, :orderId, :event, :source, :type, :platinum,
                    :perTrade, :quantity, :prevPlatinum, :prevQuantity)
            on conflict do nothing
            """,
            events.map { event ->
                MapSqlParameterSource()
                    .addValue("observedAt", Timestamp.from(observedAt))
                    .addValue("marketId", event.marketId)
                    .addValue("orderId", event.orderId)
                    .addValue("event", event.kind.db)
                    .addValue("source", source.db)
                    .addValue("type", event.type.db)
                    .addValue("platinum", event.platinum)
                    .addValue("perTrade", event.perTrade)
                    .addValue("quantity", event.quantity)
                    .addValue("prevPlatinum", event.prevPlatinum)
                    .addValue("prevQuantity", event.prevQuantity)
            }.toTypedArray(),
        )
    }

    fun insertQuotes(quotes: Collection<Quote>, observedAt: Instant) {
        if (quotes.isEmpty()) return
        jdbc.batchUpdate(
            """
            insert into market_quote (market_id, observed_at, best_buy, best_sell, best_buy_online,
                                      best_sell_online, buy_count, sell_count, buy_online_count,
                                      sell_online_count)
            values (:marketId, :observedAt, :bestBuy, :bestSell, :bestBuyOnline,
                    :bestSellOnline, :buyCount, :sellCount, :buyOnlineCount,
                    :sellOnlineCount)
            """,
            quotes.map { quote ->
                MapSqlParameterSource()
                    .addValue("marketId", quote.marketId)
                    .addValue("observedAt", Timestamp.from(observedAt))
                    .addValue("bestBuy", quote.bestBuy)
                    .addValue("bestSell", quote.bestSell)
                    .addValue("bestBuyOnline", quote.bestBuyOnline)
                    .addValue("bestSellOnline", quote.bestSellOnline)
                    .addValue("buyCount", quote.buyCount)
                    .addValue("sellCount", quote.sellCount)
                    .addValue("buyOnlineCount", quote.buyOnlineCount)
                    .addValue("sellOnlineCount", quote.sellOnlineCount)
            }.toTypedArray(),
        )
    }

    private fun ObservedOrder.params(observedAt: Instant) = MapSqlParameterSource()
        .addValue("id", id)
        .addValue("marketId", marketId)
        .addValue("type", type.db)
        .addValue("platinum", platinum)
        .addValue("perTrade", perTrade)
        .addValue("quantity", quantity)
        .addValue("ownerPlatform", ownerPlatform)
        .addValue("observedAt", Timestamp.from(observedAt))

    private fun ResultSet.toKnownOrder() = KnownOrder(
        id = getString("id"),
        marketId = getLong("market_id"),
        type = OrderType.fromDb(getString("type")),
        platinum = getInt("platinum"),
        perTrade = getObject("per_trade") as Int?,
        quantity = getInt("quantity"),
        changedAt = getTimestamp("changed_at").toInstant(),
        gone = getBoolean("gone"),
    )
}
