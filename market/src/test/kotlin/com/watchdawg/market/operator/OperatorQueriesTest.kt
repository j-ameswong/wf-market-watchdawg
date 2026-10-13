package com.watchdawg.market.operator

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.upsert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OperatorQueriesTest {
    @Autowired lateinit var queries: OperatorQueries

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Test
    fun `statistics isolate exact markets series sides resolution and crossplay`() {
        items.upsert(ItemRecord("ranked", "ranked", maxRank = 3))
        val chosen = markets.resolve(MarketKey("ranked", rank = 3))
        val other = markets.resolve(MarketKey("ranked", rank = 0))
        for (market in listOf(chosen, other)) {
            for (crossplay in listOf(true, false)) {
                for (side in listOf("buy", "sell")) {
                    jdbc.update(
                        """
                        insert into item_stat(market_id, section, granularity, bucket, order_type,
                            crossplay, upstream_id, volume, min_price, max_price, avg_price, wa_price,
                            median, fetched_at)
                        values (?, 'live', 'daily', now() - interval '1 day', ?, ?, 'sample',
                            10, 12.25, 20, 16.5, 16.5, 15.25, now())
                        """,
                        market,
                        side,
                        crossplay,
                    )
                }
            }
        }
        val result = queries.statistics(chosen, 7, "live", "daily", "sell", true)
        val rows = result["points"] as List<*>
        assertEquals(1, rows.size)
        assertEquals(BigDecimal("15.25"), (rows.single() as Map<*, *>)["median"])
        assertTrue((queries.statistics(chosen, 7, "closed", "daily", "", true)["points"] as List<*>).isEmpty())
        assertTrue((queries.statistics(chosen, 7, "live", "hourly", "sell", true)["points"] as List<*>).isEmpty())
        assertEquals(2, queries.markets("ranked").size)
    }

    @Test
    fun `queries are bounded and reject incompatible statistics filters`() {
        assertFailsWith<IllegalArgumentException> { queries.statistics(1, 91, "closed", "daily", "", true) }
        assertFailsWith<IllegalArgumentException> { queries.statistics(1, 7, "closed", "daily", "sell", true) }
        assertFailsWith<IllegalArgumentException> { queries.statistics(1, 7, "live", "daily", "", true) }
        assertFailsWith<IllegalArgumentException> { queries.items("a".repeat(81)) }
        repeat(90) { items.upsert(ItemRecord("item-$it", "item_$it", subtypes = listOf("intact", "radiant"))) }
        assertEquals(80, queries.items("").size)
        assertEquals(listOf("intact", "radiant"), queries.items("item_89").single()["subtypes"])
        assertTrue(queries.items("' OR TRUE --").isEmpty())
    }

    @Test
    fun `host settings never expose credential values or database access`() {
        val settings = queries.hostSettings()
        assertFalse(settings.containsKey("password"))
        assertFalse(settings.containsKey("baseUrl"))
        assertTrue(settings["topics"] is List<*>)
        assertEquals(emptyList<Any>(), queries.overview()["signals"])
        assertEquals(0L, queries.overview()["pending"])
    }
}
