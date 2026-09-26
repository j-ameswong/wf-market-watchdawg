package com.watchdawg.market.watch

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.BookOutcome
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.wfm.OrderType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Signals are admitted by the book reconcile that found them, in its transaction (R9a.3, R9a.7). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AlertsTest {

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    private val ingest: OrderIngest by lazy {
        ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
    }

    @Test
    fun `a cheap listing from an online owner is one pending signal`() {
        val outcome = ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T1)

        assertEquals(1, assertIs<BookOutcome.Reconciled>(outcome).signals)
        assertEquals(listOf("cheap" to "pending"), signalRows())
        assertEquals(
            T1,
            jdbc.queryForObject("select seen_at from signal", java.sql.Timestamp::class.java)!!.toInstant(),
        )
    }

    @Test
    fun `an offline owner, a buy order, another rank or a dearer listing is none`() {
        val book = listOf(
            khraOrder("away", platinum = 10, online = false),
            khraOrder("bid", platinum = 10, type = OrderType.BUY),
            khraOrder("unranked", platinum = 10, rank = 0),
            khraOrder("dear", platinum = 13),
        )

        ingest.reconcileBook(KHRA.id, book, T1)

        assertEquals(emptyList(), signalRows())
    }

    @Test
    fun `an owner coming online with an unchanged cheap listing signals on the next poll`() {
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("waking", platinum = 10, online = false)), T1)
        assertEquals(emptyList(), signalRows())

        ingest.reconcileBook(KHRA.id, listOf(khraOrder("waking", platinum = 10)), T2)

        assertEquals(listOf("waking" to "pending"), signalRows())
    }

    @Test
    fun `rolling back the reconcile transaction leaves no signal (R9a7)`() {
        val ingest = ingest // commits the catalog first: markets resolve in a transaction of their own
        TransactionTemplate(transactions).executeWithoutResult { status ->
            ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T1)
            assertEquals(1, signalRows().size)
            status.setRollbackOnly()
        }

        assertEquals(emptyList(), signalRows())
        assertEquals(0, jdbc.queryForObject("select count(*) from wfm_order", Int::class.java))
    }

    @Test
    fun `a stale book evaluates nothing`() {
        ingest.reconcileBook(KHRA.id, emptyList(), T2)

        val outcome = ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T1)

        assertEquals(BookOutcome.Stale, outcome)
        assertEquals(emptyList(), signalRows())
    }

    private fun signalRows() = jdbc.query("select order_id, state from signal order by id") { rs, _ ->
        rs.getString(1) to rs.getString(2)
    }

    private companion object {
        val T1: Instant = Instant.parse("2026-09-26T12:00:00Z")
        val T2: Instant = T1.plusSeconds(120)
    }
}
