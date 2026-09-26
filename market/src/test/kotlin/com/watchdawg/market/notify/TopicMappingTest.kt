package com.watchdawg.market.notify

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.assertEquals

/** Real topics come from the environment, keyed by the logical name a watch uses (R10.6). */
class TopicMappingTest {

    @Test
    fun `WATCHDAWG_NOTIFY_TOPICS_DEFAULT maps the logical topic default`() {
        val env = SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            mapOf<String, Any>(
                "WATCHDAWG_NOTIFY_TOPICS_DEFAULT" to "real-one",
                "WATCHDAWG_NOTIFY_TOPICS_TRADES2" to "real-two",
            ),
        )

        val topics = Binder(ConfigurationPropertySource.from(env)!!)
            .bind("watchdawg.notify.topics", Bindable.mapOf(String::class.java, String::class.java))
            .get()

        assertEquals(mapOf("default" to "real-one", "trades2" to "real-two"), topics)
    }
}
