package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import com.watchdawg.market.wfm.WfmProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C5 T2: the socket connects, subscribes and records new orders, against a local fake server. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WfmSocketTest {

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
    private val socket by lazy {
        val metrics = WfmMetrics(registry)
        val recent = RecentOrders(wfmRestClient).also { it.answer() }
        val gapFill = GapFill(recent.client, ingest, metrics)
        WfmSocket(
            connector,
            socketProperties(server.url),
            context,
            SocketFeed(ingest, mapper, metrics),
            gapFill,
            metrics,
            mapper,
        )
    }

    @AfterEach
    fun stop() {
        socket.stop()
        server.close()
    }

    @Test
    fun `the handshake offers the wfm subprotocol and the project's User-Agent (R5_1, R1_5)`() {
        socket.start()

        val headers = server.handshakes.take()
        assertEquals(listOf(WFM_SUBPROTOCOL), headers["sec-websocket-protocol"])
        assertEquals(listOf(props.userAgent), headers["user-agent"])
        assertEquals(WFM_SUBPROTOCOL, server.nextSession().negotiatedSubprotocol)
    }

    @Test
    fun `the subscription names the configured platform and crossplay (R5_2)`() {
        socket.start()

        assertEquals(mapOf("platform" to context.platform, "crossplay" to context.crossplay), server.nextSubscription())
        eventually(what = "the subscription") { connected() == 1.0 }
    }

    @Test
    fun `a captured new order appears once, as ws, and the same message again adds nothing (R5_5)`() {
        val order = capturedOrders().first()
        catalog(order)
        val session = subscribed()

        session.basicRemote.sendText(order.toString())
        eventually(what = "the order's appearance") { events(order) == listOf("appeared" to "ws") }
        session.basicRemote.sendText(order.toString())
        eventually(what = "the second message") { frames("order") == 2.0 }

        assertEquals(listOf("appeared" to "ws"), events(order))
    }

    @Test
    fun `alreadySubscribed counts as subscribed`() {
        server.subscribeReply = { """{"route":"$SUBSCRIBE_FAILED","payload":"$ALREADY_SUBSCRIBED","id":"$it"}""" }
        socket.start()

        eventually(what = "the subscription") { connected() == 1.0 }
        assertTrue(server.nextSession().isOpen)
    }

    @Test
    fun `any other subscription error closes the connection, and the socket reconnects`() {
        server.subscribeReply = { """{"route":"$SUBSCRIBE_FAILED","payload":"app.errors.somethingElse","id":"$it"}""" }
        socket.start()

        val session = server.nextSession()
        eventually(what = "the close") { !session.isOpen }
        server.nextSession()
        assertEquals(0.0, connected())
    }

    @Test
    fun `a message split across several frames is read once, as one order (R5_6)`() {
        val order = capturedOrders().first()
        catalog(order)
        val session = subscribed()

        session.sendInParts(order.toString(), partSize = 40)

        eventually(what = "the order's appearance") { events(order).isNotEmpty() }
        assertEquals(1.0, frames("order"))
        assertEquals(0.0, frames("skipped"))
    }

    @Test
    fun `a malformed message and an unknown route are skipped, and the next order is still recorded (R5_6)`() {
        val order = capturedOrders().first()
        catalog(order)
        val session = subscribed()

        session.basicRemote.sendText("""{"route":"@wfm|event/subscriptions/newOrder","payload":""")
        session.basicRemote.sendText("""{"route":"@wfm|event/somethingNew","payload":{}}""")
        session.basicRemote.sendText(order.toString())

        eventually(what = "the order's appearance") { events(order).isNotEmpty() }
        assertEquals(2.0, frames("skipped"))
        assertTrue(session.isOpen)
    }

    @Test
    fun `an order for an item the catalog lacks is skipped`() {
        val (unknown, known) = capturedOrders().distinctBy { it.itemId() }
        catalog(known)
        val session = subscribed()

        session.basicRemote.sendText(unknown.toString())
        session.basicRemote.sendText(known.toString())

        eventually(what = "the known order's appearance") { events(known).isNotEmpty() }
        assertEquals(1, jdbc.queryForObject("select count(*) from wfm_order", Int::class.java))
        assertTrue(session.isOpen)
    }

    /** Starts the socket and returns the server's side of it once the subscription is confirmed. */
    private fun subscribed() = socket.start().let {
        val session = server.nextSession()
        eventually(what = "the subscription") { connected() == 1.0 }
        session
    }

    /** The captured `newOrder` messages, in arrival order (T1, 2026-09-26). */
    private fun capturedOrders(): List<JsonNode> =
        mapper.readTree(ClassPathResource("fixtures/v2-socket/new-orders.json").inputStream)
            .filter { it["route"].stringValue() == NEW_ORDER }

    private fun JsonNode.itemId(): String = this["payload"]["itemId"].stringValue()

    private fun catalog(order: JsonNode) =
        items.upsert(ItemRecord(id = order.itemId(), slug = "item-${order.itemId()}"))

    private fun events(order: JsonNode) = jdbc.query(
        "select event, source from order_event where order_id = ?",
        { rs, _ -> rs.getString(1) to rs.getString(2) },
        order["payload"]["id"].stringValue(),
    )

    private fun connected() = registry.get("wfm.socket.connected").gauge().value()

    private fun frames(outcome: String) = registry.get("wfm.socket.frames").tag("outcome", outcome).counter().count()
}
