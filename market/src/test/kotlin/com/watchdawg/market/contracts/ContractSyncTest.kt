package com.watchdawg.market.contracts

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.notify.NotifyProperties
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.WfmLegacyClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.support.SimpleTriggerContext
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ContractSyncTest {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var ingest: ContractIngest

    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var wfmLegacyRestClient: RestClient

    @Autowired lateinit var notify: NotifyProperties

    private var now = Instant.parse("2026-10-13T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }

    @Test
    fun `search throttle preserves queued details across restart and resumes before searches`() {
        val auction = seed("a", "alpha")
        seed("b", "beta")
        val (server, client) = client()
        val props = properties(1, "alpha", "beta")
        emptySearch(server, "alpha")
        throttle(server, search("beta"))
        detail(server, auction.copy(closed = true))
        emptySearch(server, "alpha")
        throttle(server, search("beta"))

        val sync = sync(client, props)
        sync.round()
        assertTrue(pending("a"))
        assertFalse(closed("a"))
        assertEquals(now.plusSeconds(120), sync.cadence.nextExecution(SimpleTriggerContext(now, now, now)))

        now = now.plusSeconds(120)
        sync(client, props).round()
        assertTrue(closed("a"))
        assertFalse(pending("a"))
        server.verify()
    }

    @Test
    fun `failed oldest detail backs off without changing facts and next ID gets the budget after restart`() {
        val failed = seed("a", "alpha")
        val next = seed("b", "alpha")
        val observed = now.minusSeconds(60)
        val (server, client) = client()
        val props = properties(1, "alpha")
        emptySearch(server, "alpha")
        server.expect(requestTo(entry(failed.id))).andRespond(withStatus(INTERNAL_SERVER_ERROR))
        detail(server, next.copy(closed = true))
        emptySearch(server, "alpha")

        sync(client, props).round()
        assertTrue(pending("a"))
        assertEquals(
            observed,
            jdbc.queryForObject(
                "select observed_at from auction where id = 'a'",
                java.sql.Timestamp::class.java,
            )!!.toInstant(),
        )
        assertTrue(ingest.pendingVerification("riven", "alpha", now).none { it.id == "a" })
        assertFalse(jdbc.queryForObject("select missing from auction where id = 'a'", Boolean::class.java)!!)

        now = now.plusSeconds(60)
        sync(client, props).round()
        assertTrue(closed("b"))
        assertTrue(pending("a"))
        assertEquals(3, jdbc.queryForObject("select count(*) from auction_event", Int::class.java))
        server.verify()
    }

    @Test
    fun `detail throttle retains attempted and unattempted IDs and honors backoff`() {
        val first = seed("a", "alpha")
        val second = seed("b", "alpha")
        ingest.queueVerification("riven", "alpha", emptySet())
        val (server, client) = client()
        val props = properties(2, "alpha")
        throttle(server, entry(first.id))
        detail(server, second.copy(closed = true))
        detail(server, first.copy(closed = true))
        emptySearch(server, "alpha")

        val sync = sync(client, props)
        sync.round()
        assertTrue(pending("a"))
        assertTrue(pending("b"))
        assertEquals(now.plusSeconds(120), sync.cadence.nextExecution(SimpleTriggerContext(now, now, now)))
        now = now.plusSeconds(120)
        sync.round()
        assertTrue(closed("a"))
        assertTrue(closed("b"))
        server.verify()
    }

    @Test
    fun `missing details remain queued and target rotation respects a one-request budget`() {
        val first = seed("a", "alpha")
        val second = seed("b", "beta")
        ingest.queueVerification("riven", "alpha", emptySet())
        ingest.queueVerification("riven", "beta", emptySet())
        val (server, client) = client()
        server.expect(requestTo(entry(first.id))).andRespond(withStatus(NOT_FOUND))
        emptySearch(server, "alpha")
        emptySearch(server, "beta")
        detail(server, second.copy(closed = true))
        emptySearch(server, "alpha")
        emptySearch(server, "beta")
        detail(server, first.copy(closed = true))
        emptySearch(server, "alpha")
        emptySearch(server, "beta")

        val sync = sync(client, properties(1, "alpha", "beta"))
        sync.round()
        assertTrue(pending("a"))
        assertFalse(closed("a"))
        assertTrue(jdbc.queryForObject("select missing from auction where id = 'a'", Boolean::class.java)!!)
        now = now.plusSeconds(60)
        sync.round()
        assertTrue(closed("b"))
        assertFalse(closed("a"))
        now = now.plusSeconds(60)
        sync.round()
        assertTrue(closed("a"))
        assertEquals(
            listOf("appeared", "disappeared", "closed"),
            jdbc.query("select event from auction_event where auction_id = 'a' order by observed_at") { rs, _ ->
                rs.getString(1)
            },
        )
        server.verify()
    }

    private fun client(): Pair<MockRestServiceServer, WfmLegacyClient> {
        val builder = wfmLegacyRestClient.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        return server to WfmLegacyClient(builder.build())
    }

    private fun properties(budget: Int, vararg weapons: String) = ContractProperties(
        watches = weapons.map { ContractWatch(it, "riven", it) },
        interval = Duration.ofMinutes(1),
        initialDelay = Duration.ZERO,
        detailBudget = budget,
    )

    private fun sync(client: WfmLegacyClient, props: ContractProperties) = ContractSync(
        props,
        client,
        ingest,
        jdbc,
        Watches(emptyList()),
        notify,
        SimpleMeterRegistry(),
        clock,
    )

    private fun seed(id: String, weapon: String): Auction {
        jdbc.update(
            "insert into contract_weapon(kind, slug, name) values ('riven', ?, ?) on conflict do nothing",
            weapon,
            weapon,
        )
        return Auction(id, 10, 10, visible = true, closed = false, item = AuctionItem("riven", weapon)).also {
            ingest.ingest(listOf(it), now.minusSeconds(60))
        }
    }

    private fun pending(id: String) = jdbc.queryForObject(
        "select verification_pending from auction where id = ?",
        Boolean::class.java,
        id,
    )!!

    private fun closed(id: String) = jdbc.queryForObject(
        "select closed from auction where id = ?",
        Boolean::class.java,
        id,
    )!!

    private fun emptySearch(server: MockRestServiceServer, weapon: String) {
        server.expect(
            requestTo(search(weapon)),
        ).andRespond(withSuccess("""{"payload":{"auctions":[]}}""", APPLICATION_JSON))
    }

    private fun throttle(server: MockRestServiceServer, uri: String) {
        // Above the transport's retry ceiling, so no real sleep or retry is needed.
        server.expect(requestTo(uri)).andRespond(withStatus(TOO_MANY_REQUESTS).header("Retry-After", "120"))
    }

    private fun detail(server: MockRestServiceServer, auction: Auction) {
        val json = mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
            .writeValueAsString(mapOf("payload" to AuctionEntry(auction)))
        server.expect(requestTo(entry(auction.id))).andRespond(withSuccess(json, APPLICATION_JSON))
    }

    private fun search(weapon: String) =
        "https://api.warframe.market/v1/auctions/search?type=riven&weapon_url_name=$weapon"

    private fun entry(id: String) = "https://api.warframe.market/v1/auctions/entry/$id"
}
