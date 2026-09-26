package com.watchdawg.market.notify

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dispatcher runs only with scheduling on and a real topic mapped. */
class NotifySwitchTest {

    @Nested
    @SpringBootTest(properties = [SCHEDULING_ON, NO_SOCKET, NO_POLL, NO_SYNC, NO_DISPATCH])
    @Import(TestcontainersConfiguration::class)
    inner class WithNoTopic {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `nothing dispatches, so signals stay pending`() {
            assertFalse(context.containsBean("dispatchSchedule"))
        }
    }

    @Nested
    @SpringBootTest(
        properties = [
            SCHEDULING_ON,
            NO_SOCKET,
            NO_POLL,
            NO_SYNC,
            NO_DISPATCH,
            "watchdawg.notify.topics.default=wd-test-topic",
        ],
    )
    @Import(TestcontainersConfiguration::class)
    inner class WithATopic {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `the dispatcher runs on a schedule of its own`() {
            assertTrue(context.getBean("dispatchSchedule", OwnThreadSchedule::class.java).isRunning)
        }
    }
}

// These contexts stay cached for the whole run, so long delays keep their schedules from ever
// calling out or touching rows another test is using.
private const val SCHEDULING_ON = "watchdawg.scheduling.enabled=true"
private const val NO_SOCKET = "watchdawg.socket.enabled=false"
private const val NO_POLL = "wfm.poll.initial-delay=1h"
private const val NO_SYNC = "wfm.sync.initial-delay=1h"
private const val NO_DISPATCH = "watchdawg.notify.interval=1h"
