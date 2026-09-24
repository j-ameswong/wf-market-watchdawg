package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R2.5: `bootRun` reads migrations from the classpath, and `mflyway` reads the same files from
 * `src/main/resources/db/migration`. Both write one `flyway_schema_history`, so a database either
 * one migrated must look fully migrated to the other.
 *
 * Each test migrates a scratch database of its own in the shared container, leaving the one the
 * rest of the suite uses alone.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MigrationPathsTest {

    @Autowired lateinit var container: PostgreSQLContainer

    @Autowired lateinit var appFlyway: Flyway

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a database the CLI migrated has nothing left for the app to apply`() = withScratchDatabase { db ->
        cli(db).migrate()

        assertUpToDate(app(db))
    }

    @Test
    fun `a database the app migrated has nothing left for the CLI to apply`() = withScratchDatabase { db ->
        app(db).migrate()

        assertUpToDate(cli(db))
    }

    @Test
    fun `a migration that cannot run in a transaction fails unless it is marked`(@TempDir dir: Path) {
        File(NON_TRANSACTIONAL_DIR).copyRecursively(dir.toFile())
        dir.resolve("V1__rollup_with_data.sql.conf").toFile().delete()

        withScratchDatabase { db ->
            val failure = assertFailsWith<FlywayException> { fixture(db, "filesystem:$dir").migrate() }
            assertContains(failure.message.orEmpty(), "cannot run inside a transaction block")
        }
    }

    @Test
    fun `a marked migration applies from the classpath and from the filesystem alike`() {
        listOf("classpath:db/non-transactional", "filesystem:$NON_TRANSACTIONAL_DIR").forEach { location ->
            withScratchDatabase { db ->
                fixture(db, location).migrate()

                assertEquals(
                    1,
                    JdbcTemplate(dataSource(db)).queryForObject("select count(*) from reading_hourly", Int::class.java),
                )
            }
        }
    }

    /** The app's own Flyway settings, exactly as Boot configured them, pointed at [db]. */
    private fun app(db: String): Flyway = Flyway.configure()
        .configuration(appFlyway.configuration)
        .dataSource(dataSource(db))
        .load()

    /** What `mflyway` passes, and nothing else: a URL, credentials and a filesystem location. */
    private fun cli(db: String): Flyway = Flyway.configure()
        .dataSource(url(db), container.username, container.password)
        .locations("filesystem:src/main/resources/db/migration")
        .load()

    private fun fixture(db: String, location: String): Flyway = Flyway.configure()
        .dataSource(dataSource(db))
        .locations(location)
        .load()

    private fun assertUpToDate(flyway: Flyway) {
        val validation = flyway.validateWithResult()
        assertTrue(validation.validationSuccessful, validation.errorDetails?.errorMessage)
        assertEquals(emptyList(), flyway.info().pending().map { it.script })
        // Not vacuous: every versioned migration and the repeatable one are there to compare.
        assertTrue(flyway.info().applied().any { it.version == null }, "the repeatable migration was never applied")
    }

    private fun withScratchDatabase(test: (String) -> Unit) {
        val db = "scratch_" + UUID.randomUUID().toString().replace("-", "").take(12)
        jdbc.execute("create database $db")
        try {
            test(db)
        } finally {
            jdbc.execute("drop database $db with (force)")
        }
    }

    private fun url(db: String) =
        "jdbc:postgresql://${container.host}:${container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$db"

    private fun dataSource(db: String) = DriverManagerDataSource(url(db), container.username, container.password)

    private companion object {
        /** Gradle runs tests from the project directory, which is also where `mflyway` runs. */
        const val NON_TRANSACTIONAL_DIR = "src/test/resources/db/non-transactional"
    }
}
