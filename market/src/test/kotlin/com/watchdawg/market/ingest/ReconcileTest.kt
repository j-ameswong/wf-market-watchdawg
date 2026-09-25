package com.watchdawg.market.ingest

import com.watchdawg.market.ingest.EventKind.APPEARED
import com.watchdawg.market.ingest.EventKind.PRICE_CHANGED
import com.watchdawg.market.ingest.EventKind.QUANTITY_CHANGED
import com.watchdawg.market.ingest.EventKind.VANISHED
import com.watchdawg.market.wfm.OrderType
import com.watchdawg.market.wfm.OrderType.BUY
import com.watchdawg.market.wfm.OrderType.SELL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The classification core (R4.1–R4.10), in plain JUnit: no Spring, no database (SPEC 8).
 * `OrderIngestTest` runs the same rules against the store.
 */
class ReconcileTest {

    @Test
    fun `a first book makes every order appear, with nothing before it`() {
        val result = reconcile(emptyList(), listOf(order("a"), order("b")), T1)

        assertEquals(listOf("a" to APPEARED, "b" to APPEARED), result.kinds())
        assertEquals(listOf(null), result.events.map { it.prevPlatinum }.distinct())
        assertEquals(listOf("a", "b"), result.appeared.map { it.id })
    }

    @Test
    fun `the same book again records nothing`() {
        val book = listOf(order("a"), order("b", type = BUY))

        val result = reconcile(book.map { it.known() }, book, T1)

        assertEquals(emptyList(), result.events)
        assertEquals(emptyList(), result.changed)
    }

    @Test
    fun `one price change is exactly one price_changed, carrying the previous price`() {
        val before = listOf(order("a", platinum = 10), order("b"))

        val result = reconcile(before.map { it.known() }, listOf(order("a", platinum = 12), order("b")), T1)

        val event = result.events.single()
        assertEquals(PRICE_CHANGED to "a", event.kind to event.orderId)
        assertEquals(12 to 10, event.platinum to event.prevPlatinum)
        assertEquals(listOf("a"), result.changed.map { it.id })
    }

    @Test
    fun `a new lot size alone is a price change`() {
        val result = reconcile(listOf(order("a", perTrade = 1).known()), listOf(order("a", perTrade = 6)), T1)

        val event = result.events.single()
        assertEquals(PRICE_CHANGED, event.kind)
        assertEquals(6, event.perTrade)
    }

    @Test
    fun `a quantity change is a quantity_changed, and both at once are two events`() {
        val known = listOf(order("a", quantity = 5).known(), order("b", platinum = 10, quantity = 5).known())

        val result = reconcile(known, listOf(order("a", quantity = 4), order("b", platinum = 9, quantity = 4)), T1)

        assertEquals(listOf("a" to QUANTITY_CHANGED, "b" to PRICE_CHANGED, "b" to QUANTITY_CHANGED), result.kinds())
        assertEquals(listOf(5), result.events.map { it.prevQuantity }.distinct())
    }

    @Test
    fun `an order missing from the book is exactly one vanished, carrying its last known values`() {
        val known = listOf(order("a").known(), order("b", platinum = 30, quantity = 2).known())

        val result = reconcile(known, listOf(order("a")), T1)

        val event = result.events.single()
        assertEquals(VANISHED to "b", event.kind to event.orderId)
        assertEquals(
            listOf(30, 2, 30, 2),
            listOf(event.platinum, event.quantity, event.prevPlatinum, event.prevQuantity),
        )
        assertEquals(listOf("b"), result.vanished.map { it.id })
    }

    @Test
    fun `an order already gone is not vanished again`() {
        val result = reconcile(listOf(order("a").known(gone = true)), emptyList(), T1)

        assertEquals(emptyList(), result.events)
    }

    @Test
    fun `an order that comes back appears again, with its last known values as the previous ones`() {
        val known = order("a", platinum = 10, quantity = 3).known(gone = true)

        val result = reconcile(listOf(known), listOf(order("a", platinum = 8, quantity = 3)), T1)

        val event = result.events.single()
        assertEquals(APPEARED, event.kind)
        assertEquals(8 to 10, event.platinum to event.prevPlatinum)
        assertEquals(listOf("a"), result.changed.map { it.id }, "a returning order is stored state, not a new row")
        assertEquals(emptyList(), result.appeared)
    }

    @Test
    fun `an order that moves market vanishes from the old one and appears on the new one`() {
        val result = reconcile(listOf(order("a", market = 1).known()), listOf(order("a", market = 2)), T1)

        assertEquals(listOf(VANISHED to 1L, APPEARED to 2L), result.events.map { it.kind to it.marketId })
        assertEquals(10, result.events.last().prevPlatinum)
    }

    @Test
    fun `an order that changes side vanishes from one side and appears on the other`() {
        val result = reconcile(listOf(order("a", type = SELL).known()), listOf(order("a", type = BUY)), T1)

        assertEquals(listOf(VANISHED to SELL, APPEARED to BUY), result.events.map { it.kind to it.type })
    }

    @Test
    fun `an order first observed after the book was requested is neither changed nor vanished by it`() {
        // A socket event that arrived while the book was in flight (R4.10).
        val later = T1 + Duration.ofSeconds(1)
        val known =
            listOf(order("seen_by_socket").known(changedAt = later), order("changed_later").known(changedAt = later))

        val result = reconcile(known, listOf(order("changed_later", platinum = 99)), T1)

        assertEquals(emptyList(), result.events)
    }

    @Test
    fun `an order listed twice in one book counts once`() {
        val result = reconcile(emptyList(), listOf(order("a"), order("a")), T1)

        assertEquals(listOf("a" to APPEARED), result.kinds())
    }

    private fun Reconciliation.kinds() = events.map { it.orderId to it.kind }

    private fun ObservedOrder.known(changedAt: Instant = T0, gone: Boolean = false) =
        KnownOrder(id, marketId, type, platinum, perTrade, quantity, changedAt, gone)

    private fun order(
        id: String,
        market: Long = 1,
        type: OrderType = SELL,
        platinum: Int = 10,
        perTrade: Int? = 1,
        quantity: Int = 1,
    ) = ObservedOrder(id, market, type, platinum, perTrade, quantity, "pc", ownerOnline = false, BigDecimal(platinum))

    private companion object {
        val T0: Instant = Instant.parse("2026-09-25T12:00:00Z")
        val T1: Instant = T0 + Duration.ofMinutes(5)
    }
}
