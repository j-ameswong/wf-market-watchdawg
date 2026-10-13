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

/** ADR-0024 permits the private operator surface while keeping actuator bounded. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HttpSurfaceTest {

    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `application controllers belong to the operator surface`() {
        val controllers = context.getBeansWithAnnotation(Controller::class.java) +
            context.getBeansWithAnnotation(RestController::class.java)

        val ours = controllers.filterValues { it.javaClass.packageName.startsWith("com.watchdawg") }

        assertTrue(ours.isNotEmpty())
        assertTrue(ours.values.all { it.javaClass.packageName == "com.watchdawg.market.operator" })
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
