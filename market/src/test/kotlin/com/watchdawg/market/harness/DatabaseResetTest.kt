package com.watchdawg.market.harness

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.Policies
import com.watchdawg.market.store.Policies.Companion.COMPRESSION
import com.watchdawg.market.store.marketFor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2.7: each test starts from an empty database. The two tests are identical, so whichever runs
 * second fails unless [DatabaseResetListener] emptied what the first one wrote.
 *
 * Each one leaves a compressed event-log chunk behind. Truncating `market` with `cascade` would not
 * empty that chunk, so this also shows the reset truncates the hypertables themselves.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DatabaseResetTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var resolver: MarketResolver

    @Autowired lateinit var items: ItemRepository

    @Test
    fun `one test's rows are gone before the next`() = startsEmptyThenWrites()

    @Test
    fun `and the same holds the other way round`() = startsEmptyThenWrites()

    private fun startsEmptyThenWrites() {
        assertEquals(0, count("item"))
        assertEquals(0, count("collection_version"))
        assertEquals(0, count("market"))
        assertEquals(0, count("order_event"))
        assertEquals(0, count("market_quote"))
        assertEquals(0, count("market_quote_hourly"))
        assertTrue(count("flyway_schema_history") > 0, "the reset must keep the migration history")

        jdbc.update("insert into collection_version (name, hash, updated_at) values ('items', 'h', now())")
        val market = resolver.marketFor(items)
        // A rollup keeps its rows when its source table is emptied, so it needs its own reset.
        jdbc.update(
            """
            insert into market_quote (market_id, observed_at, buy_count, sell_count, buy_online_count, sell_online_count)
            values (?, now(), 0, 0, 0, 0)
            """,
            market,
        )
        jdbc.execute("call refresh_continuous_aggregate('market_quote_hourly', null, null)")
        val policies = Policies(jdbc)
        jdbc.update(
            """
            insert into order_event (observed_at, market_id, order_id, event, source, type, platinum, quantity)
            values (?, ?, 'o', 'appeared', 'book', 'sell', 1, 1)
            """,
            Timestamp.from(policies.beyondAge(COMPRESSION, "order_event", "compress_after")),
            market,
        )
        policies.run(COMPRESSION, "order_event")
    }

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!
}
