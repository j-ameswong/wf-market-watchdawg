package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.Policies.Companion.REFRESH
import com.watchdawg.market.store.Policies.Companion.RETENTION
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import java.time.temporal.ChronoUnit.MICROS
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R2.4: raw quote snapshots are kept for a bounded window, and their hourly and daily rollups are
 * kept forever.
 *
 * The hazard here is quiet. Refreshing a rollup over a range whose raw chunks are gone deletes the
 * rollup's rows for that range. So a refresh window reaching past raw retention would erase exactly
 * the history the rollups exist to keep, with nothing failing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class QuoteStorageTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var resolver: MarketResolver

    @Autowired lateinit var items: ItemRepository

    private val policies by lazy { Policies(jdbc) }

    private var market = 0L

    @BeforeEach
    fun market() {
        market = resolver.marketFor(items)
    }

    @Test
    fun `a poll past the raw window is dropped but its rollups keep it`() {
        val old = policies.beyondAge(RETENTION, QUOTES, "drop_after")
        // Truncated to what Postgres stores, so it compares equal when read back.
        val recent = Instant.now().truncatedTo(MICROS).minus(3, HOURS)
        insert(old, bestSell = 80)
        insert(recent, bestSell = 90)

        // In production the refresh policies roll a poll up while it is fresh. This does the same
        // for a poll that was inserted already old.
        ROLLUPS.keys.forEach { jdbc.execute("call refresh_continuous_aggregate('$it', null, null)") }

        policies.run(RETENTION, QUOTES)
        assertEquals(listOf(Timestamp.from(recent)), rawObservations())

        ROLLUPS.keys.forEach { policies.run(REFRESH, it) }
        ROLLUPS.keys.forEach { assertEquals(80, closingBestSell(it, old), "$it lost the dropped poll's bucket") }
    }

    @Test
    fun `a quote for a market that does not exist is refused`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                insert into market_quote (market_id, observed_at, buy_count, sell_count, buy_online_count, sell_online_count)
                values (999999, now(), 0, 0, 0, 0)
                """,
            )
        }
    }

    @Test
    fun `raw quotes have a retention policy and the rollups have none`() {
        assertEquals(listOf(RETENTION), policies.on(QUOTES))
        ROLLUPS.keys.forEach { assertEquals(listOf(REFRESH), policies.on(it)) }
    }

    @Test
    fun `every rollup refresh window starts inside its source's raw retention`() {
        val windows = jdbc.query(
            """
            select c.view_name,
                   (p.config ->> 'start_offset')::interval < (r.config ->> 'drop_after')::interval
            from timescaledb_information.continuous_aggregates c
            join timescaledb_information.jobs p
              on p.hypertable_name = c.view_name and p.proc_name = 'policy_refresh_continuous_aggregate'
            join timescaledb_information.jobs r
              on r.hypertable_name = c.hypertable_name and r.proc_name = 'policy_retention'
            """,
        ) { rs, _ -> rs.getString(1) to rs.getBoolean(2) }

        // Enumerated rather than listed, so a rollup added later is checked too.
        assertTrue(windows.map { it.first }.containsAll(ROLLUPS.keys), "found only $windows")
        assertEquals(emptyList(), windows.filterNot { it.second }.map { it.first })
    }

    private fun rawObservations(): List<Timestamp> =
        jdbc.queryForList("select observed_at from market_quote order by observed_at", Timestamp::class.java)
            .filterNotNull()

    /** The closing best ask of the [rollup] bucket that holds [at], or null if there is none. */
    private fun closingBestSell(rollup: String, at: Instant): Int? = jdbc.queryForList(
        "select best_sell_close from $rollup where market_id = ? and bucket = time_bucket(?::interval, ?::timestamptz)",
        Int::class.java,
        market,
        ROLLUPS.getValue(rollup),
        Timestamp.from(at),
    ).singleOrNull()

    private fun insert(observedAt: Instant, bestSell: Int) {
        jdbc.update(
            """
            insert into market_quote (market_id, observed_at, best_buy, best_sell, buy_count, sell_count,
                                      buy_online_count, sell_online_count)
            values (?, ?, 70, ?, 3, 5, 1, 2)
            """,
            market,
            Timestamp.from(observedAt),
            bestSell,
        )
    }

    private companion object {
        const val QUOTES = "market_quote"

        /** Each rollup, and its bucket width. */
        val ROLLUPS = mapOf("market_quote_hourly" to "1 hour", "market_quote_daily" to "1 day")
    }
}
