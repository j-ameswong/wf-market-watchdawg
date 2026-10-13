package com.watchdawg.market.statistics

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.watch.Resolution
import com.watchdawg.market.watch.WatchEntry
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.resolveWatches
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmLegacyClient
import com.watchdawg.market.wfm.ws.MutableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class StatisticsSyncTest {
    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var ingest: StatisticsIngest

    @Autowired lateinit var details: WfmClient

    @Autowired
    @Qualifier("wfmLegacyRestClient")
    lateinit var legacy: RestClient

    @Test
    fun `throttled rounds resume unfinished slugs promptly instead of restarting daily`() {
        val clock = MutableClock()
        listOf("a", "b").forEach {
            items.upsert(ItemRecord(it, it))
            items.recordDetail(it, true, null, null)
        }
        val watches = Watches(
            (
                resolveWatches(
                    listOf("a", "b").map {
                        WatchEntry(it, it, maxUnitPrice = BigDecimal.ONE)
                    },
                    items::findBySlug,
                ) as Resolution.Resolved
                ).watches,
        )
        val builder = legacy.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        val payload = javaClass.getResource("/fixtures/v1-statistics.json")!!.readText()
        val a = "https://api.warframe.market/v1/items/a/statistics"
        val b = "https://api.warframe.market/v1/items/b/statistics"
        server.expect(requestTo(a)).andRespond(withTooManyRequests().header("Retry-After", "300"))
        server.expect(requestTo(a)).andRespond(withSuccess(payload, APPLICATION_JSON))
        server.expect(requestTo(b)).andRespond(withTooManyRequests().header("Retry-After", "300"))
        server.expect(requestTo(b)).andRespond(withSuccess(payload, APPLICATION_JSON))
        val registry = SimpleMeterRegistry()
        val sync = StatisticsSync(
            watches,
            items,
            WfmLegacyClient(builder.build()),
            ingest,
            details,
            registry,
            StatisticsProperties(initialDelay = Duration.ZERO),
            clock,
        )
        sync.round()
        clock.at = clock.at.plusSeconds(300)
        sync.round()
        clock.at = clock.at.plusSeconds(300)
        sync.round()
        server.verify()
        assertEquals(2.0, registry.get("wfm.statistics.fetches").tag("outcome", "success").counter().count())
    }
}
