package com.watchdawg.market.watch

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.wfm.OrderType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** The underpriced-listing rule, without Spring or a database. */
class UnderpricedTest {

    @Test
    fun `a live sell order at or under the unit threshold from an online owner qualifies`() {
        val book = listOf(order("at", platinum = 12), order("under", platinum = 11), order("over", platinum = 13))

        assertEquals(listOf("under", "at"), underpriced(WATCH, book, setOf(MARKET)).map { it.order.id })
    }

    @Test
    fun `an offline owner's listing never qualifies`() {
        assertEquals(
            emptyList(),
            underpriced(WATCH, listOf(order("away", platinum = 1, online = false)), setOf(MARKET)),
        )
    }

    @Test
    fun `buy orders, and markets the watch does not select, never qualify`() {
        val book =
            listOf(order("bid", platinum = 1, type = OrderType.BUY), order("elsewhere", platinum = 1, market = 2))

        assertEquals(emptyList(), underpriced(WATCH, book, setOf(MARKET)))
    }

    @Test
    fun `the threshold is per unit, not per lot`() {
        // 60 for a lot of six is 10 a unit.
        val book = listOf(order("lot", platinum = 60, perTrade = 6), order("single", platinum = 13))

        assertEquals(listOf("lot"), underpriced(WATCH, book, setOf(MARKET)).map { it.order.id })
    }

    @Test
    fun `equally cheap listings come out in a fixed order`() {
        val book = listOf(order("b", platinum = 10), order("a", platinum = 10))

        assertEquals(listOf("a", "b"), underpriced(WATCH, book, setOf(MARKET)).map { it.order.id })
    }

    @Test
    fun `the dedup key is watch, order and unit price`() {
        val key = underpriced(WATCH, listOf(order("o1", platinum = 60, perTrade = 6)), setOf(MARKET)).single().dedupKey

        assertEquals("cheap|o1|10", key)
    }

    private companion object {
        const val MARKET = 1L

        val WATCH = Watch(
            name = "cheap",
            itemId = "item",
            slug = "item",
            itemName = "Item",
            subtype = Dimension.Absent,
            rank = Dimension.Absent,
            charges = Dimension.Absent,
            amberStars = Dimension.Absent,
            cyanStars = Dimension.Absent,
            maxUnitPrice = BigDecimal(12),
            priority = Priority.DEFAULT,
            topic = "default",
        )

        fun order(
            id: String,
            platinum: Int,
            perTrade: Int? = null,
            online: Boolean = true,
            type: OrderType = OrderType.SELL,
            market: Long = MARKET,
        ) = ObservedOrder(
            id = id,
            marketId = market,
            type = type,
            platinum = platinum,
            perTrade = perTrade,
            quantity = 1,
            ownerPlatform = "pc",
            ownerOnline = online,
            unitPrice = BigDecimal(platinum).divide(BigDecimal(perTrade ?: 1), 4, java.math.RoundingMode.HALF_EVEN),
        )
    }
}
