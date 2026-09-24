package com.watchdawg.market

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.util.concurrent.TimeUnit.DAYS
import kotlin.test.assertEquals

/**
 * The scheduling switch, on a bare context with one scheduled method. No application beans and no
 * database, so nothing here can call out even with scheduling on.
 */
class SchedulingConfigTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(
        SchedulingConfig::class.java,
        Ticker::class.java,
    )

    @Test
    fun `scheduled methods run when the property is unset`() {
        runner.run { assertEquals(1, scheduledTasks(it)) }
    }

    @Test
    fun `nothing is scheduled when the property is false`() {
        runner.withPropertyValues("$SCHEDULING_ENABLED=false").run { assertEquals(0, scheduledTasks(it)) }
    }

    private fun scheduledTasks(context: ApplicationContext): Int =
        context.getBeansOfType(ScheduledTaskHolder::class.java).values.sumOf { it.scheduledTasks.size }

    class Ticker {
        @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = DAYS)
        fun tick() {
        }
    }
}
