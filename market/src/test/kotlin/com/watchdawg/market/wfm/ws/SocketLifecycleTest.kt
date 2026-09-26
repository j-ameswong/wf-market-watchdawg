package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.khraOrder
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import com.watchdawg.market.wfm.WfmProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.websocket.Session
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C5 T4: the socket stays up by itself and fills its gaps (R5.3, R5.4), against a local fake
 * server with deadlines short enough to wait out.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SocketLifecycleTest {

    @Autowired lateinit var connector: SocketConnector

    @Autowired lateinit var ingest: OrderIngest

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var context: WfmContext

    @Autowired lateinit var props: WfmProperties

    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var wfmRestClient: RestClient

    private val server = FakeSocketServer()
    private val registry = SimpleMeterRegistry()
    private val metrics = WfmMetrics(registry)
    private val recent by lazy { RecentOrders(wfmRestClient) }
    private val sockets = mutableListOf<WfmSocket>()

    @AfterEach
    fun stop() {
        sockets.forEach { it.stop() }
        server.close()
    }

    @Test
    fun `a dropped connection reconnects and subscribes again (R5_3)`() {
        recent.answer()
        socket().start()
        val first = server.nextSession()
        eventually(what = "the subscription") { connected() == 1.0 }

        first.close()

        server.nextSession()
        eventually(what = "the second subscription") { reconnects("closed") == 1.0 && connected() == 1.0 }
    }

    @Test
    fun `a server that never completes the handshake is abandoned at the connect deadline`() {
        val silent = ServerSocket(0)
        val accepted = CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) { runCatching { while (true) accepted += silent.accept() } }
        val deadline = Duration.ofMillis(300)
        val url = URI.create("ws://localhost:${silent.localPort}/socket")
        try {
            socket(
                socketProperties(url, connectTimeout = deadline),
                JdkSocketConnector(props.userAgent, deadline),
            ).start()

            eventually(what = "a second connection") { accepted.size >= 2 }
            assertTrue(reconnects("unreachable") >= 1.0)
        } finally {
            silent.close()
            accepted.forEach { it.close() }
        }
    }

    @Test
    fun `a server that sends heartbeats but withholds its confirmation is abandoned at the subscription deadline`() {
        server.subscribeReply = { null }
        socket(socketProperties(server.url, subscribeTimeout = Duration.ofMillis(500))).start()
        val first = server.nextSession()
        heartbeats(first)

        eventually(what = "the close") { !first.isOpen }
        server.nextSession()
        assertTrue(reconnects("unconfirmed") >= 1.0)
        assertEquals(0.0, connected())
    }

    @Test
    fun `heartbeats keep a subscribed connection up, and silence ends it`() {
        recent.answer()
        socket(socketProperties(server.url, silenceTimeout = Duration.ofMillis(500))).start()
        val first = server.nextSession()
        eventually(what = "the subscription") { connected() == 1.0 }

        heartbeats(first, until = System.nanoTime() + Duration.ofMillis(1500).toNanos()).join()
        assertTrue(first.isOpen, "a connection still hearing heartbeats was ended as silent")

        eventually(what = "the close") { !first.isOpen }
        server.nextSession()
        assertEquals(1.0, reconnects("silent"))
    }

    @Test
    fun `each confirmed subscription gap-fills once, unless the last was under a minute ago (R5_4)`() {
        items.upsert(KHRA)
        recent.answer(once(), mapper.writeValueAsString(listOf(khraOrder("missed", platinum = 20))))
        socket().start()
        val first = server.nextSession()
        eventually(what = "the gap-fill") { gapFills("filled") == 1.0 }

        first.close()

        server.nextSession()
        eventually(what = "the second subscription") { gapFills("skipped") == 1.0 }
        recent.server.verify()
        assertEquals(listOf("missed" to "recent"), events())
    }

    @Test
    fun `killing the connection and reconnecting records no duplicate events`() {
        items.upsert(KHRA)
        val order = khraOrder("posted", platinum = 20)
        val message = mapper.writeValueAsString(mapOf("route" to NEW_ORDER, "payload" to order))
        recent.answer(orders = mapper.writeValueAsString(listOf(order)))
        socket().start()
        val first = server.nextSession()
        eventually(what = "the gap-fill") { gapFills("filled") == 1.0 }
        first.basicRemote.sendText(message)
        eventually(what = "the order message") { frames("order") == 1.0 }

        first.close()
        val second = server.nextSession()
        eventually(what = "the second subscription") { connected() == 1.0 }
        second.basicRemote.sendText(message)
        eventually(what = "the second order message") { frames("order") == 2.0 }

        assertEquals(listOf("posted" to "recent"), events())
    }

    @Test
    fun `a gap-fill that fails is counted, and the socket stays up`() {
        recent.server.expect(once(), requestTo(RecentOrders.URL)).andRespond(withServerError())
        socket().start()
        val first = server.nextSession()

        eventually(what = "the failed gap-fill") { gapFills("failed") == 1.0 }
        assertEquals(1.0, connected())
        assertTrue(first.isOpen)
    }

    private fun socket(
        settings: SocketProperties = socketProperties(server.url),
        opener: SocketConnector = connector,
    ): WfmSocket {
        val feed = SocketFeed(ingest, mapper, metrics)
        return WfmSocket(opener, settings, context, feed, GapFill(recent.client, ingest, metrics), metrics, mapper)
            .also { sockets += it }
    }

    /** Sends an online report every 100ms on [session] until it closes or [until] passes. */
    private fun heartbeats(session: Session, until: Long = Long.MAX_VALUE) = thread(isDaemon = true) {
        while (session.isOpen && System.nanoTime() < until) {
            runCatching { session.basicRemote.sendText("""{"route":"$ONLINE_REPORT","payload":{"connections":1}}""") }
            Thread.sleep(100)
        }
    }

    private fun events() = jdbc.query("select order_id, source from order_event order by order_id") { rs, _ ->
        rs.getString(1) to rs.getString(2)
    }

    private fun connected() = registry.get("wfm.socket.connected").gauge().value()

    private fun reconnects(reason: String) =
        registry.get("wfm.socket.reconnects").tag("reason", reason).counter().count()

    private fun gapFills(outcome: String) =
        registry.get("wfm.socket.gapfills").tag("outcome", outcome).counter().count()

    private fun frames(outcome: String) = registry.get("wfm.socket.frames").tag("outcome", outcome).counter().count()
}
