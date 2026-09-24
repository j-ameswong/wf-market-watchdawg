package com.watchdawg.market

import com.watchdawg.market.harness.LiveApiGuard
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.scheduling.config.ScheduledTaskHolder
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MarketApplicationTests {

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var guard: LiveApiGuard

    @Test
    fun contextLoads() {
    }

    /**
     * R2.6. Nothing in a test context runs on a timer, and nothing has tried to reach the network.
     * `SchedulingConfigTest` shows the task probe does see a scheduled method when one is active.
     */
    @Test
    fun `the context starts with scheduling off and has made no outbound HTTP`() {
        val scheduled = context.getBeansOfType(ScheduledTaskHolder::class.java).values.flatMap { it.scheduledTasks }

        assertEquals(emptyList(), scheduled.map { it.task.toString() })
        assertEquals(emptyList(), guard.refused)
    }
}
