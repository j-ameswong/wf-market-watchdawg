package com.watchdawg.market.wfm.ws

import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.wfm.SocketFrame
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import com.watchdawg.market.wfm.WfmProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

/** @property url where the socket connects; a test points it at its fake. */
@ConfigurationProperties(prefix = "wfm.socket")
data class SocketProperties(val url: URI, val connectTimeout: Duration)

/**
 * The realtime feed (C5): one connection to the socket, subscribed to every new order on the
 * configured platform and crossplay (R5.2), each of which [SocketFeed] records.
 *
 * Messages arrive on the JDK client's threads, one callback at a time. A message may come in several
 * parts, so each part is appended and the message is read only once its last part has arrived
 * (R5.6). The next message is asked for after every callback, whatever this one held, so a bad
 * message never stalls the ones behind it.
 */
class WfmSocket(
    private val connector: SocketConnector,
    private val url: URI,
    private val context: WfmContext,
    private val feed: SocketFeed,
    private val metrics: WfmMetrics,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val subscriptions = AtomicLong()

    @Volatile private var running = false

    @Volatile private var socket: WebSocket? = null

    override fun start() {
        running = true
        connect()
    }

    override fun stop() {
        running = false
        socket?.let { close(it) }
        socket = null
        metrics.socketConnected(false)
    }

    override fun isRunning(): Boolean = running

    private fun connect() {
        connector.connect(url, Connection()).whenComplete { opened, error ->
            when {
                error != null -> log.warn("the socket did not connect to {}: {}", url, error.message)
                !running -> opened.abort()
            }
        }
    }

    private fun close(socket: WebSocket) {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down").get(2, SECONDS) }
        socket.abort()
    }

    private inner class Connection : WebSocket.Listener {
        private val parts = StringBuilder()

        override fun onOpen(socket: WebSocket) {
            this@WfmSocket.socket = socket
            val id = "watchdawg-${subscriptions.incrementAndGet()}"
            socket.sendText(mapper.writeValueAsString(subscribe(context, id)), true)
            socket.request(1)
        }

        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            try {
                parts.append(data)
                if (last) {
                    val message = parts.toString()
                    parts.setLength(0)
                    act(socket, feed.receive(message, clock.instant()))
                }
            } finally {
                socket.request(1)
            }
            return null
        }

        override fun onBinary(socket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            if (last) {
                log.warn("skipped a binary message from the socket")
                metrics.socketFrame(SocketFrame.SKIPPED)
            }
            socket.request(1)
            return null
        }

        override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            ended(socket, "closed ($statusCode $reason)")
            return null
        }

        override fun onError(socket: WebSocket, error: Throwable) {
            ended(socket, "failed: ${error.message}")
        }

        private fun act(socket: WebSocket, received: Received) {
            when (received) {
                Received.SUBSCRIBED -> {
                    log.info("subscribed to new orders on {}, crossplay {}", context.platform, context.crossplay)
                    metrics.socketConnected(true)
                }

                Received.REFUSED -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "subscription refused")

                else -> {}
            }
        }

        private fun ended(socket: WebSocket, how: String) {
            metrics.socketConnected(false)
            if (this@WfmSocket.socket === socket) this@WfmSocket.socket = null
            if (running) log.warn("the socket {}", how)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocketProperties::class)
class SocketConfig {

    @Bean
    fun socketConnector(props: SocketProperties, wfm: WfmProperties): SocketConnector =
        JdkSocketConnector(wfm.userAgent, props.connectTimeout)

    /** Off under test, like every schedule (R2.6), and off wherever [SOCKET_ENABLED] is false. */
    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, matchIfMissing = true)
    @ConditionalOnBooleanProperty(SOCKET_ENABLED, matchIfMissing = true)
    fun wfmSocket(
        connector: SocketConnector,
        props: SocketProperties,
        context: WfmContext,
        feed: SocketFeed,
        metrics: WfmMetrics,
        mapper: JsonMapper,
    ) = WfmSocket(connector, props.url, context, feed, metrics, mapper)
}

const val SOCKET_ENABLED = "watchdawg.socket.enabled"
