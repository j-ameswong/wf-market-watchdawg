package com.watchdawg.market.harness

import com.watchdawg.market.SCHEDULING_ENABLED
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.support.DelegatingSmartContextLoader
import org.springframework.test.context.support.TestPropertySourceUtils
import kotlin.test.assertEquals

/** Where the harness's scheduling default ranks against the other property sources. */
class TestHarnessTest {

    @Test
    fun `scheduling is off even when application config turns it on`() {
        val context = GenericApplicationContext()
        context.environment.propertySources.addLast(
            MapPropertySource("application.yaml", mapOf(SCHEDULING_ENABLED to "true")),
        )

        applyHarness(context)

        assertEquals("false", context.environment.getProperty(SCHEDULING_ENABLED))
    }

    @Test
    fun `a test's own inlined properties still turn scheduling back on`() {
        val context = GenericApplicationContext()
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "$SCHEDULING_ENABLED=true")

        applyHarness(context)

        assertEquals("true", context.environment.getProperty(SCHEDULING_ENABLED))
    }

    private fun applyHarness(context: GenericApplicationContext) {
        val loader = DelegatingSmartContextLoader()
        val config = MergedContextConfiguration(javaClass, emptyArray(), emptyArray(), emptyArray(), loader)
        val harness = TestHarnessContextCustomizerFactory().createContextCustomizer(javaClass, emptyList())
        harness.customizeContext(context, config)
    }
}
