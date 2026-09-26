package com.watchdawg.market.poll

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The poll loop calls out on a timer, so the harness keeps it off (R2.6). */
class PollSwitchTest {

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration::class)
    inner class UnderTest {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `nothing polls`() {
            assertEquals(0, context.getBeansOfType(OwnThreadSchedule::class.java).size)
        }
    }

    @Nested
    @SpringBootTest(
        properties = ["watchdawg.scheduling.enabled=true", "wfm.poll.initial-delay=1h", "wfm.sync.initial-delay=1h"],
    )
    @Import(TestcontainersConfiguration::class)
    inner class WithSchedulingOn {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `the loop runs on a schedule of its own`() {
            val schedule = context.getBean("pollSchedule", OwnThreadSchedule::class.java)
            assertTrue(schedule.isRunning)
        }
    }
}
