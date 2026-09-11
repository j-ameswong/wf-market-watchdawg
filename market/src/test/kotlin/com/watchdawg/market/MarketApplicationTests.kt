package com.watchdawg.market

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MarketApplicationTests {

    @Autowired lateinit var env: Environment

    @Test
    fun contextLoads() {
    }

    @Test
    fun `the sync scheduler cannot fire during a test run`() {
        // @EnableScheduling is active under @SpringBootTest, so a tick inside the test JVM would
        // call the live API, which R2.6 and SPEC 9 forbid outright. The test task pushes the delay
        // out of reach with a system property. This test fails if that ever goes away.
        val delay = assertNotNull(env.getProperty("wfm.sync.initial-delay", Duration::class.java))

        assertTrue(delay >= Duration.ofDays(1), "sync would tick $delay into a test run")
    }
}
