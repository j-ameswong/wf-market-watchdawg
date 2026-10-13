package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.harness.SocketGuard
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.ingestWith
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmContextInterceptor
import com.watchdawg.market.wfm.WfmMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.ExpectedCount.manyTimes
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WfmSocketTest {
    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var app: ApplicationContext

    private fun ingest() = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)

    @Test
    fun `real handshake matches REST context and fragmented orders reach the outbox only once`() {
        val ingest = ingest()
        for (crossplay in listOf(true, false)) {
            LocalSocketServer().use { server ->
                val registry = SimpleMeterRegistry()
                val metrics = WfmMetrics(registry)
                val socket = socket(server, ingest, metrics, crossplay)
                try {
                    socket.start()
                    val peer = server.peer()
                    assertEquals("wfm", peer.headers["sec-websocket-protocol"])
                    assertEquals("watchdawg-test", peer.headers["user-agent"])
                    val subscribe = mapper.readTree(peer.receive())
                    assertEquals("pc", subscribe.path("payload").path("platform").asText())
                    assertEquals(crossplay, subscribe.path("payload").path("crossplay").asBoolean())
                    val restBuilder = RestClient.builder().requestInterceptor(
                        WfmContextInterceptor(WfmContext("pc", crossplay), "watchdawg-test"),
                    )
                    val rest = MockRestServiceServer.bindTo(restBuilder).build()
                    rest.expect(
                        requestTo("http://local/context"),
                    ).andExpect(header("Crossplay", crossplay.toString())).andRespond(withSuccess())
                    restBuilder.build().get().uri("http://local/context").retrieve().toBodilessEntity()
                    rest.verify()
                    peer.send("""{"route":"$SUBSCRIBE:ok","id":"${subscribe.path("id").asText()}"}""")
                    peer.send("not json")
                    peer.send("""{"route":"unknown"}""")
                    val frame = orderFrame("order-$crossplay")
                    peer.send(frame.take(40), last = false)
                    peer.send(frame.drop(40), continuation = true)
                    peer.send(frame)
                    eventually { count("order_event") == if (crossplay) 1 else 2 }
                    assertEquals(1, count("signal")) // second same-price listing is inside cooldown
                    assertEquals(
                        "ws",
                        jdbc.queryForObject("select source from order_event limit 1", String::class.java),
                    )
                    eventually { registry.get("wfm.socket.frames").tag("outcome", "order").counter().count() == 2.0 }
                    assertEquals(2.0, registry.get("wfm.socket.frames").tag("outcome", "skipped").counter().count())
                } finally {
                    socket.stop()
                }
            }
        }
    }

    @Test
    fun `already subscribed is accepted and a dropped connection reconnects without duplicating orders`() {
        val ingest = ingest()
        LocalSocketServer().use { server ->
            val registry = SimpleMeterRegistry()
            val socket = socket(server, ingest, WfmMetrics(registry))
            try {
                socket.start()
                repeat(2) {
                    val peer = server.peer()
                    val id = mapper.readTree(peer.receive()).path("id").asText()
                    peer.send("""{"route":"$SUBSCRIBE:error","payload":"app.errors.alreadySubscribed","id":"$id"}""")
                    peer.send(orderFrame("same"))
                    eventually {
                        registry.get("wfm.socket.frames").tag("outcome", "order").counter().count() >= it + 1.0
                    }
                    eventually {
                        registry.get("wfm.socket.gapfills").tag("outcome", if (it == 0) "success" else "skipped")
                            .counter().count() >= 1.0
                    }
                    peer.close()
                }
                assertEquals(1, count("order_event"))
                assertEquals(1, count("signal"))
                eventually { registry.get("wfm.socket.gapfills").tag("outcome", "skipped").counter().count() >= 1.0 }
            } finally {
                socket.stop()
            }
        }
    }

    @Test
    fun `heartbeats do not rescue an unconfirmed subscription and silence reconnects`() {
        val ingest = ingest()
        LocalSocketServer().use { server ->
            val registry = SimpleMeterRegistry()
            val socket = socket(server, ingest, WfmMetrics(registry), deadlines = Duration.ofMillis(200))
            try {
                socket.start()
                val unconfirmed = server.peer()
                unconfirmed.receive()
                unconfirmed.send("""{"route":"$ONLINE_REPORT"}""")
                val confirmed = server.peer()
                val id = mapper.readTree(confirmed.receive()).path("id").asText()
                confirmed.send("""{"route":"$SUBSCRIBE:ok","id":"$id"}""")
                server.peer()
                assertTrue(registry.get("wfm.socket.reconnects").tag("reason", "subscription").counter().count() >= 1)
                assertTrue(registry.get("wfm.socket.reconnects").tag("reason", "silence").counter().count() >= 1)
            } finally {
                socket.stop()
            }
        }
    }

    @Test
    fun `handshake deadline reconnects and tests have no scheduled socket`() {
        assertTrue(app.getBeansOfType(WfmSocket::class.java).isEmpty())
        val ingest = ingest()
        LocalSocketServer(handshake = false).use { server ->
            val registry = SimpleMeterRegistry()
            val socket = socket(server, ingest, WfmMetrics(registry), deadlines = Duration.ofMillis(150))
            try {
                socket.start()
                server.peer()
                server.peer()
                assertTrue(registry.get("wfm.socket.reconnects").tag("reason", "connect").counter().count() >= 1)
            } finally {
                socket.stop()
            }
        }
        val guard = SocketGuard()
        assertFailsWith<IllegalStateException> {
            guard.connect(
                java.net.URI("wss://ws.warframe.market/socket"),
                Duration.ofSeconds(1),
                "test",
                object : java.net.http.WebSocket.Listener {},
            )
        }
        assertFailsWith<AssertionError> { guard.assertUntouched() }
    }

    @Test
    fun `qualifying gapfill overlaps socket delivery without duplicate evidence or signals`() {
        val ingest = ingestWith(
            items.watchesOf(khraWatch().copy(cooldown = Duration.ZERO)),
            store,
            markets,
            signals,
            transactions,
        )
        LocalSocketServer().use { server ->
            val registry = SimpleMeterRegistry()
            val metrics = WfmMetrics(registry)
            val clock = MutableClock()
            val requested = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val builder = wfmRestClient.mutate()
            val rest = MockRestServiceServer.bindTo(builder).build()
            val overlap = mapper.readTree(orderFrame("overlap")).path("payload")
            val recentOnly = mapper.readTree(orderFrame("recent-only")).path("payload")
            rest.expect(requestTo("https://api.warframe.market/v2/orders/recent")).andRespond { request ->
                requested.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "gapfill was not released" }
                withSuccess(
                    mapper.writeValueAsString(mapOf("apiVersion" to "2", "data" to listOf(overlap, recentOnly))),
                    APPLICATION_JSON,
                ).createResponse(request)
            }
            val socket = WfmSocket(
                SocketProperties(server.uri),
                "watchdawg-test",
                SocketProtocol(mapper, WfmContext("pc", true)),
                app.getBean(SocketGuard::class.java),
                ingest,
                SocketGapFill(WfmClient(builder.build()), ingest, metrics, clock),
                metrics,
                clock,
            )
            try {
                socket.start()
                val peer = server.peer()
                val id = mapper.readTree(peer.receive()).path("id").asText()
                peer.send("""{"route":"$SUBSCRIBE:ok","id":"$id"}""")
                assertTrue(requested.await(2, java.util.concurrent.TimeUnit.SECONDS))
                peer.send(orderFrame("overlap"))
                eventually { count("signal") == 1 }
                release.countDown()
                eventually { registry.get("wfm.socket.gapfills").tag("outcome", "success").counter().count() == 1.0 }
                assertEquals(2, count("order_event"))
                assertEquals(2, count("signal"))
                assertEquals(
                    listOf("recent", "ws"),
                    jdbc.query("select source from order_event order by source") { r, _ -> r.getString(1) },
                )
                peer.send(orderFrame("recent-only"))
                peer.send(orderFrame("overlap"))
                eventually { registry.get("wfm.socket.frames").tag("outcome", "order").counter().count() == 3.0 }
                assertEquals(2, count("order_event"))
                assertEquals(2, count("signal"))
                assertEquals(1.0, registry.get("wfm.socket.connected").gauge().value())
                rest.verify()
            } finally {
                release.countDown()
                socket.stop()
            }
        }
    }

    private fun socket(
        server: LocalSocketServer,
        ingest: OrderIngest,
        metrics: WfmMetrics,
        crossplay: Boolean = true,
        deadlines: Duration = Duration.ofSeconds(5),
    ): WfmSocket {
        val builder = wfmRestClient.mutate()
        val rest = MockRestServiceServer.bindTo(builder).build()
        rest.expect(manyTimes(), requestTo("https://api.warframe.market/v2/orders/recent"))
            .andRespond(withSuccess("""{"apiVersion":"2","data":[]}""", APPLICATION_JSON))
        return WfmSocket(
            SocketProperties(
                server.uri,
                deadlines,
                deadlines,
                deadlines,
                backoffInitial = Duration.ofMillis(10),
                backoffMax = Duration.ofMillis(50),
            ),
            "watchdawg-test",
            SocketProtocol(mapper, WfmContext("pc", crossplay)),
            app.getBean(SocketGuard::class.java),
            ingest,
            SocketGapFill(WfmClient(builder.build()), ingest, metrics),
            metrics,
        )
    }

    private fun orderFrame(id: String): String = """
        {"route":"$NEW_ORDER","payload":{"id":"$id","itemId":"khra-id","type":"sell",
        "platinum":10,"quantity":1,"rank":3,"visible":true,"user":{"platform":"pc","status":"ingame"}}}
    """.trimIndent()

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!
}

fun eventually(check: () -> Boolean) {
    val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while (!check()) {
        check(System.nanoTime() < until) { "condition did not become true" }
        Thread.sleep(10)
    }
}
