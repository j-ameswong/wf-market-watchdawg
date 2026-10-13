package com.watchdawg.market.baseline

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.watch.AlertMetrics
import com.watchdawg.market.watch.AlertProperties
import com.watchdawg.market.watch.Alerts
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import com.watchdawg.market.wfm.WfmContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BaselineRulesTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test
    fun `volume rule excludes current and future buckets but historical statistics alone cannot open budget gate`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VOLUME_SPIKE))
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        val rules = BaselineRules(jdbc, WfmContext("pc", true))
        assertEquals(emptyList(), rules.candidates(watches.all.single(), market, AT))
        assertEquals("insufficient", jdbc.queryForObject("select status from baseline_calibration", String::class.java))
        repeat(70) { index ->
            val bucket = AT.minus((70 - index).toLong(), ChronoUnit.DAYS)
            stat(market, bucket, if (index == 69) 100 else 10)
        }
        stat(market, AT, 1000000) // unfinished/future observation cannot alter calibration
        val alerts = Alerts(watches, signals, AlertProperties(50), AlertMetrics(SimpleMeterRegistry(), watches), rules)
        val ingest = OrderIngest(store, markets, alerts, transactions)
        ingest.reconcileBook(KHRA.id, emptyList(), AT)
        ingest.reconcileBook(KHRA.id, emptyList(), AT.plusSeconds(1))
        assertEquals(0, jdbc.queryForObject("select count(*) from signal", Int::class.java))
        assertEquals(1, rules.candidates(watches.all.single(), market, AT).size)
        assertEquals("ready", jdbc.queryForObject("select status from baseline_calibration", String::class.java))
        assertEquals(
            0,
            jdbc.queryForObject(
                "select threshold from baseline_calibration",
                java.math.BigDecimal::class.java,
            )!!.compareTo(java.math.BigDecimal.ONE),
        )
    }

    @Test
    fun `lifetime rules require recorded evidence and only evaluate this observations disappearances`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VANISH_FAST))
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        val rules = BaselineRules(jdbc, WfmContext("pc", true))
        assertEquals(emptyList(), rules.candidates(watches.all.single(), market, AT))
        repeat(60) { index ->
            val end = AT.minusSeconds((61 - index) * 86400L)
            event(market, "past-$index", end.minusSeconds(100), "appeared")
            event(market, "past-$index", end, "vanished")
        }
        event(market, "quick", AT.minusSeconds(10), "appeared")
        event(market, "quick", AT, "vanished")
        assertEquals(1, rules.candidates(watches.all.single(), market, AT).size)
        assertEquals(0, rules.candidates(watches.all.single(), market, AT.plusSeconds(1)).size)
    }

    @Test
    fun `lifetime never pairs with an older compatible episode across an incompatible appearance`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VANISH_FAST))
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        val rules = BaselineRules(jdbc, WfmContext("pc", true))
        repeat(60) { index ->
            val end = AT.minusSeconds((61 - index) * 86400L)
            event(market, "past-$index", end.minusSeconds(100), "appeared")
            event(market, "past-$index", end, "vanished")
        }
        event(market, "mixed", AT.minusSeconds(20), "appeared")
        event(market, "mixed", AT.minusSeconds(10), "appeared")
        jdbc.update(
            "update order_event set crossplay = false where order_id = 'mixed' and observed_at = ?",
            Timestamp.from(AT.minusSeconds(10)),
        )
        event(market, "mixed", AT, "vanished")
        assertEquals(emptyList(), rules.candidates(watches.all.single(), market, AT))
    }

    @Test
    fun `stale market volume invalidates readiness even when old history still calibrates`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VOLUME_SPIKE))
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        repeat(70) { stat(market, AT.minus((70 - it).toLong(), ChronoUnit.DAYS), 10) }
        val rules = BaselineRules(jdbc, WfmContext("pc", true))
        rules.candidates(watches.all.single(), market, AT)
        assertEquals("ready", jdbc.queryForObject("select status from baseline_calibration", String::class.java))
        assertEquals(emptyList(), rules.candidates(watches.all.single(), market, AT.plus(3, ChronoUnit.DAYS)))
        assertEquals("stale", jdbc.queryForObject("select status from baseline_calibration", String::class.java))
    }

    private fun stat(market: Long, bucket: Instant, volume: Int) {
        jdbc.update(
            """
            insert into item_stat(market_id, section, granularity, bucket, order_type, crossplay, upstream_id,
                volume, min_price, max_price, avg_price, wa_price, median, open_price, closed_price,
                donch_top, donch_bot, fetched_at)
            values (?, 'closed', 'daily', ?, '', true, 'sample', ?, 1, 1, 1, 1, 1, 1, 1, 1, 1, ?)
            """,
            market,
            Timestamp.from(bucket),
            volume,
            Timestamp.from(AT),
        )
    }

    private fun event(market: Long, id: String, at: Instant, kind: String) {
        jdbc.update(
            """
            insert into order_event(market_id, order_id, observed_at, event, source, type, platinum, quantity, crossplay)
            values (?, ?, ?, ?, 'book', 'sell', 1, 1, true)
            """,
            market,
            id,
            Timestamp.from(at),
            kind,
        )
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-13T00:00:00Z")
    }
}
