package com.watchdawg.market.sync

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

/** The detail sweep spends ~3.9k requests per catalog change, so it runs only when switched on. */
class ItemDetailSyncSwitchTest {

    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration::class)
    inner class ByDefault {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `there is no sweep`() {
            assertEquals(0, context.getBeansOfType(ItemDetailSync::class.java).size)
        }
    }

    @Nested
    @SpringBootTest(properties = ["wfm.sync.item-details.enabled=true"])
    @Import(TestcontainersConfiguration::class)
    inner class WhenEnabled {
        @Autowired lateinit var context: ApplicationContext

        @Test
        fun `the sweep is there`() {
            assertEquals(1, context.getBeansOfType(ItemDetailSync::class.java).size)
        }
    }
}
