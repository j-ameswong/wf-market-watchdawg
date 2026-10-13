package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SocketGapFillTest {
    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var ingest: OrderIngest

    @Test
    fun `long throttle holds off gapfills without sleeping and counts even without a retry`() {
        val builder = wfmRestClient.mutate()
        val registry = SimpleMeterRegistry()
        val metrics = WfmMetrics(registry)
        builder.requestInterceptors { chain ->
            chain.replaceAll {
                if (it is com.watchdawg.market.wfm.WfmRateLimitInterceptor) {
                    val limits = com.watchdawg.market.wfm.WfmProperties.Limits(
                        com.watchdawg.market.wfm.WfmProperties.Rate(2, java.time.Duration.ofSeconds(1)),
                        com.watchdawg.market.wfm.WfmProperties.Rate(12, java.time.Duration.ofMinutes(1)),
                        2,
                        java.time.Duration.ofSeconds(60),
                    )
                    com.watchdawg.market.wfm.WfmRateLimitInterceptor(
                        com.watchdawg.market.wfm.WfmRateLimiter(limits, metrics),
                        metrics,
                        limits.maxRetryAfter,
                    )
                } else {
                    it
                }
            }
        }
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(
            requestTo("https://api.warframe.market/v2/orders/recent"),
        ).andRespond(withTooManyRequests().header("Retry-After", "300"))
        server.expect(
            requestTo("https://api.warframe.market/v2/orders/recent"),
        ).andRespond(withSuccess("""{"apiVersion":"2","data":[]}""", APPLICATION_JSON))
        val clock = MutableClock()
        val gap = SocketGapFill(WfmClient(builder.build()), ingest, metrics, clock)
        gap.run()
        clock.at = clock.at.plusSeconds(61)
        gap.run()
        clock.at = clock.at.plusSeconds(239)
        gap.run()
        server.verify()
        assertEquals(1.0, registry.get("wfm.throttles").tag("bucket", "public").tag("status", "429").counter().count())
        assertEquals(0.0, registry.get("wfm.retries").tag("bucket", "public").tag("status", "429").counter().count())
        assertEquals(1.0, registry.get("wfm.socket.gapfills").tag("outcome", "skipped").counter().count())
    }
}

class MutableClock(@Volatile var at: Instant = Instant.parse("2026-10-13T00:00:00Z")) : Clock() {
    override fun instant(): Instant = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
