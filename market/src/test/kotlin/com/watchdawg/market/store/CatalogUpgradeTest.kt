package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Migration V5 on a database that already holds a catalog, which is the state a dev database is in
 * before C3. The old binding left `updated_at` at the epoch on every row (R3.2).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogUpgradeTest {

    @Autowired lateinit var container: PostgreSQLContainer

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var appFlyway: Flyway

    private val scratch by lazy { ScratchDatabases(container, jdbc) }

    @Test
    fun `an epoch timestamp is not carried over as a sync time`() = scratch.use { db ->
        migrate(db, to = BEFORE_C3)
        val old = JdbcTemplate(db)
        old.update("insert into item (id, slug, updated_at) values ('a', 'epoch_row', 'epoch')")
        old.update("insert into item (id, slug, updated_at) values ('b', 'dated_row', '2026-09-01T00:00:00Z')")

        migrate(db, to = "latest")

        val syncedAt = JdbcTemplate(db).query("select slug, synced_at from item") { rs, _ ->
            rs.getString(1) to rs.getTimestamp(2)?.toInstant()
        }.toMap()
        assertEquals(mapOf("epoch_row" to null, "dated_row" to Instant.parse("2026-09-01T00:00:00Z")), syncedAt)
        assertEquals(
            0,
            JdbcTemplate(db).queryForObject("select count(*) from item where synced_at = 'epoch'", Int::class.java),
        )
    }

    @Test
    fun `upgrading forgets the stored items hash so the next tick refetches`() = scratch.use { db ->
        migrate(db, to = BEFORE_C3)
        JdbcTemplate(db).update(
            "insert into collection_version (name, hash, updated_at) values ('items', 'h1', ?), ('rivens', 'h2', ?)",
            Timestamp.from(Instant.EPOCH),
            Timestamp.from(Instant.EPOCH),
        )

        migrate(db, to = "latest")

        // Only the catalog whose columns changed is refetched; other collections keep their hash.
        assertEquals(
            listOf("rivens"),
            JdbcTemplate(db).queryForList("select name from collection_version", String::class.java),
        )
    }

    private fun migrate(db: DataSource, to: String) {
        Flyway.configure().configuration(appFlyway.configuration).dataSource(db).target(to).load().migrate()
    }

    private companion object {
        /** The last migration before C3 changed the catalog. */
        const val BEFORE_C3 = "4"
    }
}
