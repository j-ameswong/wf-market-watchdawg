package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.Policies.Companion.COMPRESSION
import com.watchdawg.market.store.Policies.Companion.REFRESH
import com.watchdawg.market.store.Policies.Companion.RETENTION
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * A migration that recreates a table or rollup drops its policy with it, and only a change to
 * `R__storage_policies.sql` makes Flyway re-add it. A fresh database never shows the gap, because
 * the repeatable runs last anyway, so this upgrades one that already has the policies.
 *
 * Every pre-C4 database applied an earlier version of that file. Here both steps read the current
 * one, so the test stands in for the difference by altering the recorded checksum. What it proves
 * is that the file restores every policy the new migration dropped. That the file changes whenever
 * such a migration is added is a rule stated at the top of the file, not something a test can see.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class StoragePolicyUpgradeTest {

    @Autowired lateinit var container: PostgreSQLContainer

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var appFlyway: Flyway

    private val scratch by lazy { ScratchDatabases(container, jdbc) }

    @Test
    fun `an upgraded database keeps every storage policy`() = scratch.use { db ->
        migrate(db, to = BEFORE_C4)
        JdbcTemplate(db).update(
            "update flyway_schema_history set checksum = checksum + 1 where script = 'R__storage_policies.sql'",
        )
        migrate(db, to = "latest")

        val policies = Policies(JdbcTemplate(db))
        assertEquals(listOf(COMPRESSION), policies.on("order_event"))
        assertEquals(listOf(RETENTION), policies.on("market_quote"))
        assertEquals(listOf(REFRESH), policies.on("market_quote_hourly"))
        assertEquals(listOf(REFRESH), policies.on("market_quote_daily"))
    }

    private fun migrate(db: DataSource, to: String) {
        Flyway.configure().configuration(appFlyway.configuration).dataSource(db).target(to).load().migrate()
    }

    private companion object {
        /** The last migration before C4 recreated the quote rollups. */
        const val BEFORE_C4 = "8"
    }
}
