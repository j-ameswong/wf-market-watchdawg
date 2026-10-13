package com.watchdawg.market.watch

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.Source
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BookRulesTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `crossing fires on entry rearming after an empty book and survives evaluator restart`() {
        val watches = items.watchesOf(khraWatch().copy(rule = PRICE_CROSSING, cooldown = Duration.ZERO))
        fun ingest() = ingestWith(watches, store, markets, signals, transactions)
        val order = khraOrder("cheap", 10)
        ingest().ingestPartial(listOf(order), Source.WS, AT)
        assertEquals(0, count())
        ingest().reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(1))
        ingest().reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(2))
        assertEquals(1, count())
        ingest().reconcileBook(KHRA.id, emptyList(), AT.plusSeconds(3))
        ingest().reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(4))
        assertEquals(2, count())
        val pending = signals.due(AT.plusSeconds(5), 10)
        assertEquals(PRICE_CROSSING, jdbc.queryForObject("select rule from signal limit 1", String::class.java))
        assertEquals("Khra: price-crossing", pending.first().title)
    }

    @Test
    fun `spread requires both online sides in the same market and rolls state back with signal`() {
        val watches = items.watchesOf(
            khraWatch(rank = "any").copy(rule = SPREAD, minSpread = BigDecimal(3), cooldown = Duration.ZERO),
        )
        val ingest = ingestWith(watches, store, markets, signals, transactions)
        val ask = khraOrder("ask", 10)
        val bid = khraOrder("bid", 15, type = OrderType.BUY)
        ingest.reconcileBook(KHRA.id, listOf(ask, bid.copy(rank = 2)), AT)
        ingest.reconcileBook(
            KHRA.id,
            listOf(ask, bid.copy(user = bid.user!!.copy(status = "offline"))),
            AT.plusSeconds(1),
        )
        assertEquals(0, count())
        TransactionTemplate(transactions).executeWithoutResult {
            ingest.reconcileBook(KHRA.id, listOf(ask, bid), AT.plusSeconds(2))
            assertEquals(1, count())
            it.setRollbackOnly()
        }
        assertEquals(0, count())
        ingest.reconcileBook(KHRA.id, listOf(ask, bid), AT.plusSeconds(2))
        assertEquals(1, count())
    }

    @Test
    fun `cooldown defers reentry until eligible and changed rule definitions start independently`() {
        val entry = khraWatch().copy(rule = PRICE_CROSSING, cooldown = Duration.ofHours(1))
        val watches = items.watchesOf(entry)
        val ingest = ingestWith(watches, store, markets, signals, transactions)
        val order = khraOrder("cheap", 10)
        ingest.reconcileBook(KHRA.id, listOf(order), AT)
        ingest.reconcileBook(KHRA.id, emptyList(), AT.plusSeconds(60))
        ingest.reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(120))
        assertEquals(1, count())
        ingest.reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(3601))
        assertEquals(2, count())
        val changed = ingestWith(items.watchesOf(entry.copy(side = "buy")), store, markets, signals, transactions)
        changed.reconcileBook(KHRA.id, listOf(khraOrder("bid", 15, type = OrderType.BUY)), AT.plusSeconds(7202))
        assertEquals(3, count())
    }

    @Test
    fun `percentage margin compares before rounding`() {
        val watches = items.watchesOf(khraWatch().copy(rule = SPREAD, minSpreadPercent = BigDecimal("16.6667")))
        val ingest = ingestWith(watches, store, markets, signals, transactions)
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("ask", 6), khraOrder("bid", 7, type = OrderType.BUY)), AT)
        assertEquals(0, count())
    }

    private fun count(): Int = jdbc.queryForObject("select count(*) from signal", Int::class.java)!!

    private companion object {
        val AT: Instant = Instant.parse("2026-10-13T00:00:00Z")
    }
}
