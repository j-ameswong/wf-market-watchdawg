package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import kotlin.test.assertEquals

/** R2.1: TimescaleDB is available in dev and test, and they run the same version of it. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TimescaleTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `the test database runs the image the dev database does`() {
        // Gradle runs tests from the project directory, which is where compose.yaml lives.
        val devImage = File("compose.yaml").readLines()
            .map { it.trim() }
            .single { it.startsWith("image:") }
            .removePrefix("image:")
            .trim()

        assertEquals(devImage, TestcontainersConfiguration.IMAGE)
    }

    @Test
    fun `migrations install the TimescaleDB version the image ships`() {
        val installed = jdbc.queryForObject(
            "select extversion from pg_extension where extname = 'timescaledb'",
            String::class.java,
        )

        // The tag is "<timescale version>-pg<major>".
        assertEquals(TestcontainersConfiguration.IMAGE.substringAfter(':').substringBefore("-pg"), installed)
    }
}
