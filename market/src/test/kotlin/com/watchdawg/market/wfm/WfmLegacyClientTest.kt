package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1.6's second envelope.
 *
 * The fixture is a live capture of `GET /v1/items/serration/statistics` (2026-09-11), trimmed to a
 * few rows per window and otherwise untouched. Serration is the useful slug here because it
 * exercises every shape at once: `mod_rank` on every row, `moving_avg` present on some rows and
 * absent on others, and prices arriving as both JSON ints and floats. `docs/v1-statistics.md`
 * records the shape.
 *
 * Nothing in C1 calls a v1 route, so this is where the channel gets proven. No test reaches the
 * live API (R2.6).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Timeout(value = 30, unit = SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WfmLegacyClientTest {

    @Autowired lateinit var wfmLegacyRestClient: RestClient

    @Autowired lateinit var wfmRestClient: RestClient

    @Test
    fun `the v1 payload envelope binds with snake_case fields`() {
        val (server, client) = legacyClient()
        server.expect(requestTo(STATISTICS)).andRespond(withSuccess(FIXTURE, APPLICATION_JSON))

        val statistics = client.getStatistics("serration")

        // statistics_closed, statistics_live, 48hours, 90days: the v2 camelCase binding would
        // have missed every one of these names.
        val closed = statistics.statisticsClosed.daily.first()
        assertEquals(Instant.parse("2026-06-14T00:00:00Z"), closed.datetime)
        assertEquals(12, closed.volume)
        assertEquals(BigDecimal("45.417"), closed.waPrice)
        assertEquals(BigDecimal("32.0"), closed.openPrice)
        assertEquals(BigDecimal("55.0"), closed.donchTop)
        assertEquals(BigDecimal("32.0"), closed.donchBot)
        assertEquals(10, closed.modRank)

        val live = statistics.statisticsLive.hourly.first()
        assertEquals("sell", live.orderType)
        assertEquals(786, live.volume)
        assertEquals(10, live.modRank)

        assertEquals(2, statistics.statisticsClosed.hourly.size)
        assertEquals(3, statistics.statisticsLive.hourly.size)
        server.verify()
    }

    @Test
    fun `a price arriving as a JSON int binds alongside one arriving as a float`() {
        val (server, client) = legacyClient()
        server.expect(requestTo(STATISTICS)).andRespond(withSuccess(FIXTURE, APPLICATION_JSON))

        val statistics = client.getStatistics("serration")

        // The upstream sends `20` for one value and `32.0` for another in the same field. Note
        // that an Int binding would survive both, because Jackson truncates instead of failing.
        // What actually pins the decimal type is the fractional `wa_price` 45.417, asserted in
        // `the v1 payload envelope binds with snake_case fields`.
        assertEquals(BigDecimal("20"), statistics.statisticsLive.hourly.first().minPrice)
        assertEquals(BigDecimal("32.0"), statistics.statisticsClosed.daily.first().minPrice)
        server.verify()
    }

    @Test
    fun `moving_avg binds as null on the rows that omit it`() {
        val (server, client) = legacyClient()
        server.expect(requestTo(STATISTICS)).andRespond(withSuccess(FIXTURE, APPLICATION_JSON))

        val closed = client.getStatistics("serration").statisticsClosed.hourly

        assertNull(closed.first().movingAvg, "the field is absent on this row, not null-valued")
        assertNotNull(closed.last().movingAvg)
        server.verify()
    }

    @Test
    fun `the include block binds when a route asks for one`() {
        // Only `?include=item` populates this, and no C1 route asks for it. The envelope still
        // has to carry the slot (R1.6), so the body below is written by hand, not captured.
        val builder = wfmLegacyRestClient.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(STATISTICS)).andRespond(withSuccess(INCLUDE_JSON, APPLICATION_JSON))

        // Read as an envelope rather than through WfmLegacyClient. The client unwraps to
        // `payload`, which is right for every caller but would hide the slot under test.
        val envelope = builder.build().get()
            .uri("/items/{slug}/statistics", "serration")
            .retrieve()
            .body<LegacyEnvelope<ItemStatistics>>()

        assertEquals("54a73e65e779893a797fff9d", envelope?.include?.get("item")?.get("id")?.stringValue())
        server.verify()
    }

    @Test
    fun `v2 camelCase binding is unaffected by the v1 naming strategy`() {
        // The regression this guards against is a *global* naming strategy (SPEC 7). Under one,
        // `gameRef` would start binding from `game_ref` and stop binding from `gameRef`. Both
        // halves are asserted below.
        val builder = wfmRestClient.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = WfmClient(builder.build())
        server.expect(requestTo(ITEMS)).andRespond(withSuccess(V2_ITEMS_JSON, APPLICATION_JSON))
        server.expect(requestTo(ITEMS)).andRespond(withSuccess(V2_ITEMS_SNAKE_JSON, APPLICATION_JSON))

        assertEquals("/Lotus/Upgrades/Mods", client.getItems().single().gameRef)
        assertNull(client.getItems().single().gameRef, "snake_case leaked into the v2 client")
        server.verify()
    }

    @Test
    fun `a v1 call is paced on the public bucket, like every v2 call`() {
        // The route decides the bucket, not the API version (ADR-0005). `RateLimitWiringTest`
        // checks that `bucketFor` says so; this checks the wired v1 bean behaves that way.
        val (server, client) = legacyClient()
        server.expect(times(2), requestTo(STATISTICS)).andRespond(withSuccess(FIXTURE, APPLICATION_JSON))

        val startedAt = System.nanoTime()
        repeat(2) { client.getStatistics("serration") }
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        // public is 2 req/s, so the second call waits 500ms. Unpaced, this is ~0ms.
        assertTrue(elapsed >= Duration.ofMillis(500), "two v1 calls took only $elapsed -- the limiter was bypassed")
        // contract-search would space them 5s apart; statistics must not have landed in that bucket.
        assertTrue(elapsed < Duration.ofSeconds(3), "two v1 calls took $elapsed -- paced on the wrong bucket")
        server.verify()
    }

    /** Binds a mock server to the real v1 bean, so only the request factory is faked. */
    private fun legacyClient(): Pair<MockRestServiceServer, WfmLegacyClient> {
        val builder = wfmLegacyRestClient.mutate()
        return MockRestServiceServer.bindTo(builder).build() to WfmLegacyClient(builder.build())
    }

    private companion object {
        const val STATISTICS = "https://api.warframe.market/v1/items/serration/statistics"
        const val ITEMS = "https://api.warframe.market/v2/items"

        val FIXTURE: String = ClassPathResource("fixtures/v1-statistics.json").getContentAsString(Charsets.UTF_8)

        val INCLUDE_JSON = """
            {"payload":{"statistics_closed":{"48hours":[],"90days":[]},
             "statistics_live":{"48hours":[],"90days":[]}},
             "include":{"item":{"id":"54a73e65e779893a797fff9d",
             "items_in_set":[{"url_name":"serration","mod_max_rank":10}]}}}
        """.trimIndent()

        val V2_ITEMS_JSON = """
            {"apiVersion":"2.0","data":[{"id":"54a73e65e779893a797fff9d","slug":"serration",
             "gameRef":"/Lotus/Upgrades/Mods","updatedAt":"2026-09-10T00:00:00Z"}]}
        """.trimIndent()

        /** The body a global snake_case strategy would make bind. It must not bind here. */
        val V2_ITEMS_SNAKE_JSON = """
            {"apiVersion":"2.0","data":[{"id":"54a73e65e779893a797fff9d","slug":"serration",
             "game_ref":"/Lotus/Upgrades/Mods","updated_at":"2026-09-10T00:00:00Z"}]}
        """.trimIndent()
    }
}
