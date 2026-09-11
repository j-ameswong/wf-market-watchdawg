package com.watchdawg.market

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R12.5: actuator is the service's **only** HTTP surface, and no data endpoint is ever added.
 *
 * Postgres is the read surface for the warehouse
 * ([ADR-0011](docs/adr/0011-no-query-api-postgres-is-the-read-surface.md)). So a controller
 * turning up here is a design decision that was never taken, not an oversight.
 *
 * This is a standing guard rather than a one-off check. It enumerates the context, so it fails on
 * a controller added later without anyone editing this file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HttpSurfaceTest {

    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `the service exposes no controllers of its own`() {
        val controllers = context.getBeansWithAnnotation(Controller::class.java) +
            context.getBeansWithAnnotation(RestController::class.java)

        val ours = controllers.filterValues { it.javaClass.packageName.startsWith("com.watchdawg") }

        assertTrue(ours.isEmpty(), "R12.5 allows actuator and nothing else, but found ${ours.keys}")
    }

    @Test
    fun `only health and metrics are exposed over HTTP`() {
        assertEquals(
            setOf("health", "metrics"),
            context.environment.getRequiredProperty("management.endpoints.web.exposure.include")
                .split(",")
                .map { it.trim() }
                .toSet(),
        )
    }
}
