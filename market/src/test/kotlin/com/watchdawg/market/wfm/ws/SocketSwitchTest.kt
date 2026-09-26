package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.harness.LiveApiGuard
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The socket calls out as soon as it starts, so the harness keeps it off, and refuses it a live host (R2.6). */
class SocketSwitchTest {

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration::class)
    inner class UnderTest {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `no socket is declared`() {
            assertEquals(0, context.getBeansOfType(WfmSocket::class.java).size)
        }
    }

    @Nested
    @SpringBootTest(properties = [SCHEDULING_ON, NO_POLL, NO_SYNC, "watchdawg.socket.enabled=false"])
    @Import(TestcontainersConfiguration::class)
    inner class SwitchedOff {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `no socket is declared, even with scheduling on`() {
            assertEquals(0, context.getBeansOfType(WfmSocket::class.java).size)
        }
    }

    @Nested
    @SpringBootTest(properties = [SCHEDULING_ON, NO_POLL, NO_SYNC])
    @Import(TestcontainersConfiguration::class)
    @DirtiesContext
    inner class SwitchedOn {
        @Autowired lateinit var context: ApplicationContext

        @Autowired lateinit var guard: LiveApiGuard

        @Test
        fun `the socket starts, and the harness refuses it the live host`() {
            assertTrue(context.getBean(WfmSocket::class.java).isRunning)
            assertEquals(listOf("WS wss://ws.warframe.market/socket"), guard.refused)
            // What LiveApiGuardListener runs after every test: it fails, and clears the record.
            assertFailsWith<AssertionError> { guard.assertUntouched() }
        }
    }
}

private const val SCHEDULING_ON = "watchdawg.scheduling.enabled=true"
private const val NO_POLL = "wfm.poll.initial-delay=1h"
private const val NO_SYNC = "wfm.sync.initial-delay=1h"
