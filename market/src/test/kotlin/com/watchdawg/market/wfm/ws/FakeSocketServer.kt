package com.watchdawg.market.wfm.ws

import jakarta.servlet.ServletContainerInitializer
import jakarta.servlet.http.HttpServlet
import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.HandshakeResponse
import jakarta.websocket.MessageHandler
import jakarta.websocket.Session
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerContainer
import jakarta.websocket.server.ServerEndpointConfig
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.websocket.server.WsSci
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A local stand-in for `ws.warframe.market`, on Tomcat's websocket support, which the test
 * classpath already carries (decision 1). It speaks the `wfm` subprotocol, records every handshake
 * and message it receives, and answers each subscription with [subscribeReply].
 */
class FakeSocketServer : AutoCloseable {
    /** The request headers of each handshake, by lowercase name. */
    val handshakes = LinkedBlockingQueue<Map<String, List<String>>>()

    /** Every whole text message a client sent, in order. */
    val received = LinkedBlockingQueue<String>()

    /** The session of each connection, in the order they opened. */
    val sessions = LinkedBlockingQueue<Session>()

    /** The reply to a `subscribe/newOrders` with this id, or null for none. */
    @Volatile var subscribeReply: (id: String) -> String? = { """{"route":"$SUBSCRIBED","id":"$it"}""" }

    private val mapper = JsonMapper.builder().build()
    private val tomcat = Tomcat().apply {
        setBaseDir(Files.createTempDirectory("fake-socket").toString())
        setPort(0)
        connector
        val context = addContext("", null)
        // The upgrade filter runs only for a request some servlet maps.
        Tomcat.addServlet(context, "none", object : HttpServlet() {})
        context.addServletMappingDecoded("/", "none")
        context.addServletContainerInitializer(WsSci(), null)
        context.addServletContainerInitializer(
            ServletContainerInitializer { _, servlet ->
                (servlet.getAttribute(ServerContainer::class.java.name) as ServerContainer).addEndpoint(endpoint())
            },
            null,
        )
        start()
    }

    val url: URI get() = URI.create("ws://localhost:${tomcat.connector.localPort}/socket")

    /** Waits for the next connection a client opens. */
    fun nextSession(timeout: Duration = WAIT): Session =
        assertNotNull(sessions.poll(timeout.toMillis(), MILLISECONDS), "no connection within $timeout")

    /** Waits for the next message a client sends. */
    fun nextReceived(timeout: Duration = WAIT): String =
        assertNotNull(received.poll(timeout.toMillis(), MILLISECONDS), "no message within $timeout")

    /** Waits for the next subscribe command and returns its payload's fields. */
    fun nextSubscription(): Map<String, Any?> {
        val message = mapper.readTree(nextReceived())
        if (message["route"]?.stringValue() != SUBSCRIBE) fail("expected a subscription, got $message")
        return mapper.treeToValue(message["payload"], Map::class.java).mapKeys { it.key as String }
    }

    override fun close() {
        tomcat.stop()
        tomcat.destroy()
    }

    private fun endpoint(): ServerEndpointConfig = ServerEndpointConfig.Builder.create(Endpoint::class.java, "/socket")
        .subprotocols(listOf(WFM_SUBPROTOCOL))
        .configurator(
            object : ServerEndpointConfig.Configurator() {
                override fun modifyHandshake(
                    config: ServerEndpointConfig,
                    request: HandshakeRequest,
                    response: HandshakeResponse,
                ) {
                    handshakes.put(request.headers.mapKeys { it.key.lowercase() })
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> getEndpointInstance(endpointClass: Class<T>): T = Fake() as T
            },
        )
        .build()

    private inner class Fake : Endpoint() {
        override fun onOpen(session: Session, config: EndpointConfig) {
            session.addMessageHandler(
                String::class.java,
                MessageHandler.Whole { text ->
                    received.put(text)
                    val message = mapper.readTree(text)
                    if (message["route"]?.stringValue() == SUBSCRIBE) {
                        subscribeReply(message["id"].stringValue())?.let { session.basicRemote.sendText(it) }
                    }
                },
            )
            sessions.put(session)
        }

        override fun onClose(session: Session, reason: CloseReason) {}
    }

    companion object {
        val WAIT: Duration = Duration.ofSeconds(10)
    }
}

/** Sends [text] as one message split across frames of [partSize] characters. */
fun Session.sendInParts(text: String, partSize: Int) {
    val parts = text.chunked(partSize)
    parts.forEachIndexed { i, part -> basicRemote.sendText(part, i == parts.lastIndex) }
}

/** Polls [condition] until it holds or [timeout] passes. */
fun eventually(timeout: Duration = FakeSocketServer.WAIT, what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("$what did not happen within $timeout")
        Thread.sleep(20)
    }
}
