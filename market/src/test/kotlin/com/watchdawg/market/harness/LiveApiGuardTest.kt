package com.watchdawg.market.harness

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.sync.CollectionSyncScheduler
import com.watchdawg.market.wfm.WfmClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * R2.6: an unmocked call from a test context is refused before it leaves the process.
 *
 * Each test ends by running the same check [LiveApiGuardListener] runs after every test, which
 * both proves that check fails and clears the record so this class does not fail itself.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LiveApiGuardTest {

    @Autowired lateinit var wfm: WfmClient

    @Autowired lateinit var scheduler: CollectionSyncScheduler

    @Autowired lateinit var guard: LiveApiGuard

    @Test
    fun `an unmocked call is refused and recorded, never sent`() {
        assertFailsWith<LiveApiRefused> { wfm.getVersions() }

        assertEquals(listOf("GET $VERSIONS"), guard.refused)
        assertFailsWith<AssertionError> { guard.assertUntouched() }
    }

    @Test
    fun `a refusal the code under test swallows is still recorded`() {
        // tick() catches every exception, so the refusal never reaches this test.
        scheduler.tick()

        assertEquals(listOf("GET $VERSIONS"), guard.refused)
        assertFailsWith<AssertionError> { guard.assertUntouched() }
    }

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"
    }
}
