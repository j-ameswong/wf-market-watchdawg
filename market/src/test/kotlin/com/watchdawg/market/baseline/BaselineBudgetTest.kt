package com.watchdawg.market.baseline

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.contracts.ContractProperties
import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.watch.AlertProperties
import com.watchdawg.market.watch.Candidate
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.Watch
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import com.watchdawg.market.wfm.OrderType
import com.watchdawg.market.wfm.WfmContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BaselineBudgetTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `missing calibration and missing collection cannot establish shared coverage`() {
        val watches = items.watchesOf(
            khraWatch().copy(name = "volume", rule = VOLUME_SPIKE),
            khraWatch().copy(name = "lifetime", rule = VANISH_FAST),
        )
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        val gate = gate(watches, 5)
        ready(watches.all[0], market)
        repeat(49) { hour -> assertFalse(sample(gate, watches.all[0], AT.plusSeconds(hour * 3600L))) }
        ready(watches.all[1], market)
        assertFalse(gate.observe(watches.all[0], emptyList(), AT.plusSeconds(86400 * 4)))
        // All calendar time alone still supplies no fresh collection or coherent calibration.
        assertFalse(gate.observe(watches.all[0], emptyList(), AT.plusSeconds(86400 * 6)))
    }

    @Test
    fun `complete evaluation day includes late threshold demand and threshold changes invalidate evidence`() {
        val watches = items.watchesOf(
            khraWatch().copy(name = "volume", rule = VOLUME_SPIKE, cooldown = Duration.ZERO),
            khraWatch().copy(name = "threshold", cooldown = Duration.ZERO),
        )
        val baseline = watches.all[0]
        val threshold = watches.all[1]
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        ready(baseline, market)
        val gate = gate(watches, 1)
        repeat(47) { hour ->
            val candidates = if (hour == 24) listOf(candidate(baseline, market, "volume")) else emptyList()
            assertFalse(sample(gate, baseline, AT.plusSeconds(hour * 3600L), candidates))
        }
        val midnight = AT.plusSeconds(47 * 3600)
        assertFalse(
            gate.observe(threshold, listOf(candidate(threshold, market, "threshold")), midnight.minusSeconds(1)),
        )
        assertFalse(sample(gate, baseline, midnight))
        kotlin.test.assertEquals(
            "over-budget",
            jdbc.queryForObject("select status from baseline_budget_epoch", String::class.java),
        )
        jdbc.update("update baseline_calibration set threshold = 2")
        assertFalse(sample(gate, baseline, midnight.plusSeconds(1)))
        kotlin.test.assertEquals(0, jdbc.queryForObject("select count(*) from baseline_shadow", Int::class.java))
    }

    @Test
    fun `covered quiet day permits baseline only after midnight and gaps or restarts invalidate it`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VOLUME_SPIKE))
        val watch = watches.all.single()
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        ready(watch, market)
        val gate = gate(watches, 5)
        repeat(47) { hour -> assertFalse(sample(gate, watch, AT.plusSeconds(hour * 3600L))) }
        assertTrue(sample(gate, watch, AT.plusSeconds(47 * 3600)))
        val gap = AT.plusSeconds(86400 * 4)
        assertFalse(sample(gate, watch, gap))
        assertFalse(sample(gate(watches, 5), watch, gap.plusSeconds(1)))
    }

    @Test
    fun `fresh coverage cannot reuse another markets stale calibration`() {
        val watches = items.watchesOf(khraWatch().copy(rule = VOLUME_SPIKE))
        val watch = watches.all.single()
        val market = markets.resolve(MarketKey(KHRA.id, rank = 3))
        ready(watch, market)
        val gate = gate(watches, 5)
        repeat(48) { hour -> sample(gate, watch, AT.plusSeconds(hour * 3600L)) }
        val at = AT.plusSeconds(48 * 3600)
        gate.evaluated("item|${KHRA.id}", at, Duration.ofHours(2))
        gate.evaluated("statistics|${watch.slug}", at, Duration.ofHours(2))
        assertFalse(gate.observe(watch, emptyList(), at))
    }

    private fun sample(
        gate: BaselineBudget,
        watch: Watch,
        at: Instant,
        candidates: List<Candidate> = emptyList(),
    ): Boolean {
        gate.evaluated("item|${KHRA.id}", at, Duration.ofHours(2))
        gate.evaluated("statistics|${watch.slug}", at, Duration.ofHours(2))
        jdbc.update("update baseline_calibration set calibrated_at = ?", Timestamp.from(at))
        return gate.observe(watch, candidates, at)
    }

    private fun gate(watches: Watches, ceiling: Int) = BaselineBudget(
        jdbc,
        watches,
        ContractProperties(),
        WfmContext("pc", true),
        markets,
        AlertProperties(ceiling),
        socketEnabled = false,
    )

    private fun ready(watch: Watch, market: Long) {
        jdbc.update(
            """
            insert into baseline_calibration(watch, market_id, rule, crossplay, threshold, samples, calibrated_at, status)
            values (?, ?, ?, true, 1, 60, ?, 'ready')
        """,
            watch.name,
            market,
            watch.rule,
            Timestamp.from(AT),
        )
    }

    private fun candidate(watch: Watch, market: Long, id: String) = Candidate(
        watch,
        watch.rule,
        ObservedOrder(id, market, OrderType.SELL, 1, null, 1, null, true, BigDecimal.ONE),
    )

    companion object {
        val AT: Instant = Instant.parse("2026-10-10T01:00:00Z")
    }
}
