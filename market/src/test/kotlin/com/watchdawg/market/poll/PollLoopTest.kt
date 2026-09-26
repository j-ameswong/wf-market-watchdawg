package com.watchdawg.market.poll

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderBookPoll
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.watch.Resolution
import com.watchdawg.market.watch.WatchEntry
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.resolveWatches
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.support.SimpleTriggerContext
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** One poll round over watched items, through the production client and a mock server (R2.6). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PollLoopTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var ingest: OrderIngest

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var contextRegistry: MeterRegistry

    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val builder by lazy { wfmRestClient.mutate() }
    private val server by lazy { MockRestServiceServer.bindTo(builder).build() }

    @BeforeEach
    fun catalog() {
        CATALOG.forEach(items::upsert)
    }

    @Test
    fun `each watched item is polled once a round, however many watches name it (R6_1)`() {
        expectBook("khra")
        expectBook("serration")
        expectBook("ayatan_anasa_sculpture")

        loop(WATCHES + WatchEntry("khra-again", "khra", rank = "0", maxUnitPrice = BigDecimal.ONE)).round()

        server.verify()
        assertEquals(3, jdbc.queryForObject("select count(*) from order_book", Int::class.java))
        assertEquals(3.0, polls("reconciled"))
    }

    @Test
    fun `a failed fetch for one item does not stop the others`() {
        expectBook("khra")
        server.expect(requestTo("$ORDERS/serration")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        expectBook("ayatan_anasa_sculpture")

        loop().round()

        server.verify()
        assertEquals(2.0, polls("reconciled"))
        assertEquals(1.0, polls("failed"))
    }

    @Test
    fun `a throttle ends the round and holds off the next until its Retry-After (R6_6)`() {
        expectBook("khra")
        server.expect(requestTo("$ORDERS/serration"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"))

        val loop = loop()
        loop.round()

        server.verify() // ayatan was never requested
        assertEquals(1.0, polls("throttled"))
        val next = loop.cadence.nextExecution(SimpleTriggerContext(now, now, now))
        assertEquals(now.plusSeconds(600), next)
    }

    @Test
    fun `a book already reconciled is counted stale`() {
        expectBook("khra")
        expectBook("serration")
        expectBook("ayatan_anasa_sculpture")
        // Every book is dated when requested, so a later book is never stale; put one in the future.
        jdbc.update("insert into order_book (item_id, observed_at) values ('khra-id', now() + interval '1 day')")

        loop().round()

        assertEquals(1.0, polls("stale"))
        assertEquals(2.0, polls("reconciled"))
    }

    @Test
    fun `poll meters are registered at startup (R6_4)`() {
        assertNotNull(contextRegistry.find("wfm.poll.lateness").timer())
        listOf("reconciled", "stale", "failed", "throttled").forEach {
            assertNotNull(contextRegistry.find("wfm.polls").tag("outcome", it).counter(), "wfm.polls{outcome=$it}")
        }
    }

    @Test
    fun `a round records its lateness`() {
        val loop = loop(entries = emptyList())
        loop.cadence.nextExecution(SimpleTriggerContext(now.minusSeconds(150), now.minusSeconds(150), now))

        loop.round()

        val lateness = registry.find("wfm.poll.lateness").timer()!!
        assertEquals(1L, lateness.count())
        assertEquals(30.0, lateness.totalTime(SECONDS))
    }

    private fun loop(entries: List<WatchEntry> = WATCHES): PollLoop {
        val watches = assertIs<Resolution.Resolved>(resolveWatches(entries, items::findBySlug)).watches
        return PollLoop(
            Watches(watches),
            OrderBookPoll(WfmClient(builder.build()), items, ingest),
            WfmMetrics(registry),
            Duration.ofMinutes(2),
            Duration.ofMinutes(1),
            Clock.fixed(now, UTC),
        )
    }

    private fun expectBook(slug: String) {
        server.expect(requestTo("$ORDERS/$slug"))
            .andRespond(withSuccess(ClassPathResource("fixtures/v2-orders/$slug.json"), APPLICATION_JSON))
    }

    private fun polls(outcome: String) = registry.find("wfm.polls").tag("outcome", outcome).counter()?.count()

    private companion object {
        const val ORDERS = "https://api.warframe.market/v2/orders/item"

        val CATALOG = listOf(
            ItemRecord(id = "khra-id", slug = "khra", maxRank = 3),
            ItemRecord(
                id = "serration-id",
                slug = "serration",
                subtypes = listOf("regular", "atragraph"),
                maxRank = 10,
            ),
            ItemRecord(id = "ayatan-id", slug = "ayatan_anasa_sculpture", maxAmberStars = 2, maxCyanStars = 2),
        )

        val WATCHES = listOf(
            WatchEntry("khra", "khra", rank = "any", maxUnitPrice = BigDecimal.ONE),
            WatchEntry("serration", "serration", subtype = "any", rank = "any", maxUnitPrice = BigDecimal.ONE),
            WatchEntry(
                "ayatan",
                "ayatan_anasa_sculpture",
                amberStars = "any",
                cyanStars = "any",
                maxUnitPrice = BigDecimal.ONE,
            ),
        )
    }
}
