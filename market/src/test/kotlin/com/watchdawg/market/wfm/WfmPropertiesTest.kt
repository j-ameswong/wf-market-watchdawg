package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WfmPropertiesTest {

    @Autowired lateinit var props: WfmProperties

    @Test
    fun `each bucket binds its own rate, in the units the spec states them`() {
        assertEquals(WfmProperties.Rate(permits = 2, per = Duration.ofSeconds(1)), props.limits.public)
        assertEquals(WfmProperties.Rate(permits = 12, per = Duration.ofMinutes(1)), props.limits.contractSearch)
    }

    @Test
    fun `concurrency cap and retry-after ceiling bind`() {
        assertEquals(2, props.limits.maxConcurrency)
        assertEquals(Duration.ofSeconds(60), props.limits.maxRetryAfter)
    }

    @Test
    fun `configured rates stay under the documented upstream ceilings`() {
        // docs/v2/rules/overview.md: 3 req/s in general, contract search 10-20 req/min.
        // SPEC 9 makes exceeding either a hard boundary, so it is checked, not just commented.
        assertTrue(props.limits.public.perSecond <= 3.0, "public: ${props.limits.public}")
        assertTrue(
            props.limits.contractSearch.perSecond <= 20.0 / 60,
            "contract-search: ${props.limits.contractSearch}",
        )
    }

    @Test
    fun `user agent names the project and a contact url`() {
        assertTrue(props.userAgent.startsWith("wf-market-watchdawg/"), props.userAgent)
        assertTrue(props.userAgent.contains("https://github.com/j-ameswong/wf-market-watchdawg"), props.userAgent)
    }

    private val WfmProperties.Rate.perSecond: Double
        get() = permits / (per.toMillis() / 1000.0)
}
