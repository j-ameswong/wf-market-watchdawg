package com.watchdawg.market.statistics

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.ItemStatistics
import com.watchdawg.market.wfm.LegacyEnvelope
import com.watchdawg.market.wfm.WfmContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class StatisticsIngestTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var mapper: JsonMapper

    private fun fixture(): ItemStatistics =
        mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
            .readValue(
                javaClass.getResourceAsStream("/fixtures/v1-statistics.json"),
                object : TypeReference<LegacyEnvelope<ItemStatistics>>() {},
            ).payload!!

    @Test
    fun `captured rows replay by logical key with separate series and crossplay populations`() {
        val item = ItemRecord("frost", "frost_prime_set", detailSyncedAt = Instant.now())
        items.upsert(item)
        val payload = fixture()
        val at = Instant.now()
        val expected = statisticsRows(item, payload).size
        val yes = StatisticsIngest(markets, jdbc, WfmContext("pc", true), transactions)
        val no = StatisticsIngest(markets, jdbc, WfmContext("pc", false), transactions)
        yes.ingest(item, payload, at)
        yes.ingest(item, payload, at.plusSeconds(1))
        assertEquals(expected, count())
        no.ingest(item, payload, at)
        assertEquals(expected * 2, count())
        assertEquals(
            2,
            jdbc.jdbcTemplate.queryForObject("select count(distinct section) from item_stat", Int::class.java),
        )
        val row = payload.statisticsClosed.daily.first()
        assertEquals(
            0,
            row.waPrice.compareTo(
                jdbc.jdbcTemplate.queryForObject(
                    "select wa_price from item_stat where upstream_id = ? limit 1",
                    java.math.BigDecimal::class.java,
                    row.id,
                ),
            ),
        )
    }

    @Test
    fun `rank star subtype keys remain distinct and ambiguous charges fail before writes`() {
        val item = ItemRecord("ranked", "ranked", maxRank = 10, detailSyncedAt = Instant.now())
        items.upsert(item)
        val fixture = fixture()
        val row = fixture.statisticsClosed.daily.first()
        val payload = fixture.copy(
            statisticsClosed = fixture.statisticsClosed.copy(
                hourly = emptyList(),
                daily = listOf(
                    row.copy(modRank = 0, movingAvg = null),
                    row.copy(modRank = 10),
                    row.copy(subtype = "radiant"),
                    row.copy(amberStars = 2, cyanStars = 3),
                ),
            ),
            statisticsLive = fixture.statisticsLive.copy(hourly = emptyList(), daily = emptyList()),
        )
        StatisticsIngest(markets, jdbc, WfmContext("pc", true), transactions).ingest(item, payload, Instant.now())
        assertEquals(
            4,
            jdbc.jdbcTemplate.queryForObject("select count(distinct market_id) from item_stat", Int::class.java),
        )
        assertTrue(
            jdbc.jdbcTemplate.queryForObject(
                "select count(*) from item_stat where moving_avg is null",
                Int::class.java,
            )!! >
                0,
        )
        assertFailsWith<IllegalArgumentException> { statisticsRows(item.copy(maxCharges = 3), payload) }
        assertFailsWith<IllegalArgumentException> { statisticsRows(item.copy(detailSyncedAt = null), payload) }
    }

    private fun count(): Int = jdbc.jdbcTemplate.queryForObject("select count(*) from item_stat", Int::class.java)!!
}
