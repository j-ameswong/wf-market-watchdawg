package com.watchdawg.market.watch

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PartialAlertsTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `partial ingest signals once across feed and book and rolls back with its evidence`() {
        val ingest = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
        val order = khraOrder("new", 10)
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            ingest.ingestPartial(listOf(order), Source.WS, AT)
            assertEquals(1, count("signal"))
            tx.setRollbackOnly()
        }
        assertEquals(0, count("signal"))
        assertEquals(0, count("order_event"))
        ingest.ingestPartial(listOf(order), Source.WS, AT)
        ingest.ingestPartial(listOf(order.copy(platinum = 1)), Source.RECENT, AT.plusSeconds(1))
        ingest.reconcileBook(KHRA.id, listOf(order), AT.plusSeconds(2))
        assertEquals(1, count("signal"))
        assertEquals(
            AT,
            jdbc.queryForObject("select seen_at from signal", java.sql.Timestamp::class.java)!!.toInstant(),
        )
    }

    @Test
    fun `socket and book racing for one cooldown admit exactly one`() {
        val ingest = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
        markets.resolve(MarketKey(KHRA.id, rank = 3))
        race(
            { ingest.ingestPartial(listOf(khraOrder("socket", 10)), Source.WS, AT) },
            { ingest.reconcileBook(KHRA.id, listOf(khraOrder("book", 10)), AT) },
        )
        assertEquals(1, count("signal"))
    }

    @Test
    fun `different watches race for final admission across three accelerated UTC days`() {
        val watches = items.watchesOf(khraWatch("rank2", rank = "2"), khraWatch("rank3", rank = "3"))
        val ingest = ingestWith(watches, store, markets, signals, transactions, dailyCeiling = 1)
        markets.resolve(MarketKey(KHRA.id, rank = 2))
        markets.resolve(MarketKey(KHRA.id, rank = 3))
        repeat(3) { day ->
            val at = AT.plusSeconds(day * 86400L)
            race(
                { ingest.ingestPartial(listOf(khraOrder("a-$day", 10, rank = 2)), Source.WS, at) },
                { ingest.ingestPartial(listOf(khraOrder("b-$day", 10, rank = 3)), Source.RECENT, at) },
            )
            assertEquals(1, signals.admittedBetween(at, at.plusSeconds(86400)))
        }
        assertEquals(3, jdbc.queryForObject("select count(*) from signal where state = 'suppressed'", Int::class.java))
        assertEquals(3, jdbc.queryForObject("select count(*) from signal where state = 'pending'", Int::class.java))
    }

    @Test
    fun `three accelerated days combine book and socket admissions and next-day deliveries`() {
        val watches = items.watchesOf(khraWatch().copy(cooldown = java.time.Duration.ZERO))
        val ingest = ingestWith(watches, store, markets, signals, transactions, dailyCeiling = 1)
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val metrics = AlertMetrics(registry, watches)
        var delivered = 0
        repeat(3) { day ->
            val at = AT.plusSeconds(day * 86400L)
            ingest.reconcileBook(KHRA.id, listOf(khraOrder("book-$day", 10)), at)
            ingest.ingestPartial(listOf(khraOrder("socket-$day", 9)), Source.WS, at.plusSeconds(1))
            assertEquals(1, signals.admittedBetween(at, at.plusSeconds(86400)))
            val dispatcher = com.watchdawg.market.notify.Dispatcher(
                signals,
                com.watchdawg.market.notify.Notifier { _, _ -> delivered++ },
                com.watchdawg.market.notify.NotifyProperties(
                    "http://local",
                    java.time.Duration.ofSeconds(1),
                    20,
                    5,
                    java.time.Duration.ofSeconds(1),
                    mapOf("default" to "test-topic"),
                ),
                metrics,
                watches,
                java.time.Clock.fixed(at.plusSeconds(86400), java.time.ZoneOffset.UTC),
            )
            dispatcher.dispatch()
        }
        assertEquals(3, delivered)
        assertEquals(3.0, registry.get("watchdawg.deliveries").tag("outcome", "sent").counter().count())
        assertEquals(
            listOf(1L, 1L, 1L),
            jdbc.query(
                """
            select count(*) from signal where state = 'sent'
            group by (notified_at at time zone 'UTC')::date order by 1
        """,
            ) { rs, _ -> rs.getLong(1) },
        )
        assertEquals(3, jdbc.queryForObject("select count(*) from signal where state = 'suppressed'", Int::class.java))
    }

    @Test
    fun `daily ceiling resets at midnight and includes failed admissions`() {
        val watches = items.watchesOf(khraWatch().copy(cooldown = java.time.Duration.ZERO))
        val ingest = ingestWith(watches, store, markets, signals, transactions, dailyCeiling = 1)
        val midnight = Instant.parse("2026-10-14T00:00:00Z")
        ingest.ingestPartial(listOf(khraOrder("before", 10)), Source.WS, midnight.minusMillis(1))
        jdbc.update("update signal set state = 'failed'")
        ingest.ingestPartial(listOf(khraOrder("before-too", 10)), Source.WS, midnight.minusNanos(1000))
        ingest.ingestPartial(listOf(khraOrder("after", 10)), Source.WS, midnight)
        assertEquals(
            listOf("failed", "suppressed", "pending"),
            jdbc.query("select state from signal order by id") {
                    r,
                    _,
                ->
                r.getString(1)
            },
        )
    }

    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Int::class.java)

    private fun race(a: () -> Any, b: () -> Any) {
        val barrier = CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            listOf(a, b).map { work ->
                pool.submit {
                    barrier.await(5, SECONDS)
                    work()
                }
            }.forEach { it.get(10, SECONDS) }
        }
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-13T00:00:00Z")
    }
}
