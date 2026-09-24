package com.watchdawg.market.harness

import com.watchdawg.market.SCHEDULING_ENABLED
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener
import org.springframework.test.context.support.TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME

/**
 * Applies the test harness to every Spring test context (R2.6).
 *
 * It is registered in `META-INF/spring.factories` rather than imported, so a test class cannot
 * leave it out by forgetting an annotation.
 */
class TestHarnessContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer = TestHarness
}

/**
 * - Scheduling is off. A test can still turn it on with `@SpringBootTest(properties = …)`, whose
 *   inlined properties rank above this.
 * - Every `RestClient` the context builds sends through a [LiveApiGuard].
 *
 * A single object, so every test class shares the same customizer and context caching still works.
 */
private object TestHarness : ContextCustomizer {
    override fun customizeContext(context: ConfigurableApplicationContext, mergedConfig: MergedContextConfiguration) {
        val defaults = MapPropertySource("watchdawgTestHarness", mapOf(SCHEDULING_ENABLED to "false"))
        val sources = context.environment.propertySources
        if (sources.contains(INLINED_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(INLINED_PROPERTIES_PROPERTY_SOURCE_NAME, defaults)
        } else {
            sources.addFirst(defaults)
        }

        // Boot's own builder backs off when one is already registered, so RestClient.Builder gets
        // this one instead.
        val guard = LiveApiGuard()
        context.beanFactory.registerSingleton("liveApiGuard", guard)
        context.beanFactory.registerSingleton(
            "liveApiGuardRequestFactoryBuilder",
            ClientHttpRequestFactoryBuilder<LiveApiGuard> { guard },
        )
    }
}

/** Fails any Spring test that reached [LiveApiGuard], including through code that swallowed it. */
class LiveApiGuardListener : AbstractTestExecutionListener() {
    override fun afterTestMethod(testContext: TestContext) {
        if (!testContext.hasApplicationContext()) return
        testContext.applicationContext.getBeanProvider(LiveApiGuard::class.java).ifAvailable { it.assertUntouched() }
    }
}
