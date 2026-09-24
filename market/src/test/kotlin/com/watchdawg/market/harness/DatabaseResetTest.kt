package com.watchdawg.market.harness

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2.7: each test starts from an empty database. The two tests are identical, so whichever runs
 * second fails unless [DatabaseResetListener] emptied what the first one wrote.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DatabaseResetTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `one test's rows are gone before the next`() = startsEmptyThenWrites()

    @Test
    fun `and the same holds the other way round`() = startsEmptyThenWrites()

    private fun startsEmptyThenWrites() {
        assertEquals(0, count("item"))
        assertEquals(0, count("collection_version"))
        assertEquals(0, count("market_quote"))
        assertEquals(0, count("market_quote_hourly"))
        assertTrue(count("flyway_schema_history") > 0, "the reset must keep the migration history")

        jdbc.update("insert into item (id, slug) values ('id', 'slug')")
        jdbc.update("insert into collection_version (name, hash, updated_at) values ('items', 'h', now())")
        // A rollup keeps its rows when its source table is emptied, so it needs its own reset.
        jdbc.update("insert into market_quote (market_id, observed_at, buy_count, sell_count) values (1, now(), 0, 0)")
        jdbc.execute("call refresh_continuous_aggregate('market_quote_hourly', null, null)")
    }

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!
}
