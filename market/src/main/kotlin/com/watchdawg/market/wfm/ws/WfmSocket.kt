package com.watchdawg.market.wfm.ws

import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.wfm.SocketEnd
import com.watchdawg.market.wfm.SocketFrame
import com.watchdawg.market.wfm.WfmClient
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

/**
 * @property url where the socket connects; a test points it at its fake.
 * @property connectTimeout for the TCP connection and the opening handshake together.
 * @property subscribeTimeout from sending the subscription to its confirmation. Heartbeats do not
 *   count toward it.
 * @property silenceTimeout without any message once subscribed. The server reports every 30
 *   seconds or so, so a silent connection is a dead one, however open it looks.
 */
@ConfigurationProperties(prefix = "wfm.socket")
data class SocketProperties(
    val url: URI,
    val connectTimeout: Duration,
    val subscribeTimeout: Duration,
    val silenceTimeout: Duration,
    val reconnect: Reconnect,
) {
    /** The ceiling on the delay before a reconnect doubles from [initial] to [max]. */
    data class Reconnect(val initial: Duration, val max: Duration)
}

/**
 * The realtime feed (C5): one connection to the socket, subscribed to every new order on the
 * configured platform and crossplay (R5.2), each of which [SocketFeed] records. It stays up by
 * itself (R5.3): a connection that does not connect, is not confirmed or goes silent in time is
 * abandoned, like one that drops, and the next is made after a [Reconnects] delay. Each confirmed
 * subscription runs a [GapFill] (R5.4).
 *
 * Messages arrive on the JDK client's threads, one callback at a time. A message may come in several
 * parts, so each part is appended and the message is read only once its last part has arrived
 * (R5.6). The next message is asked for after every callback, whatever this one held, so a bad
 * message never stalls the ones behind it.
 *
 * Everything else, connecting, the deadlines, reconnecting and gap-filling, runs on the socket's
 * own thread, one thing at a time, so none of it needs a lock.
 */
class WfmSocket(
    private val connector: SocketConnector,
    private val props: SocketProperties,
    private val context: WfmContext,
    private val feed: SocketFeed,
    private val gapFill: GapFill,
    private val metrics: WfmMetrics,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reconnects = Reconnects(props.reconnect.initial, props.reconnect.max)
    private val subscriptions = AtomicLong()

    @Volatile private var thread: ScheduledThreadPoolExecutor? = null

    /** The connection being made or held. Used on [thread] only, and by [stop] once it has ended. */
    private var current: Connection? = null

    override fun start() {
        thread = ScheduledThreadPoolExecutor(1) { Thread(it, "socket").apply { isDaemon = true } }.apply {
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
        later(Duration.ZERO) { connect() }
    }

    override fun stop() {
        val stopping = thread ?: return
        thread = null
        stopping.shutdown()
        if (!stopping.awaitTermination(5, SECONDS)) stopping.shutdownNow()
        current?.close()
        current = null
        metrics.socketConnected(false)
    }

    override fun isRunning(): Boolean = thread != null

    /** Runs [action] on the socket's thread after [delay], unless the socket has stopped. */
    private fun later(delay: Duration, action: () -> Unit) {
        val thread = thread ?: return
        val task = Runnable {
            try {
                action()
            } catch (e: Exception) {
                log.warn("socket task failed", e)
            }
        }
        try {
            thread.schedule(task, delay.toNanos(), NANOSECONDS)
        } catch (_: RejectedExecutionException) {
            // The socket stopped meanwhile.
        }
    }

    private fun connect() {
        val connection = Connection()
        current = connection
        val opening = try {
            connector.connect(props.url, connection)
        } catch (e: Exception) {
            CompletableFuture.failedFuture(e)
        }
        opening.whenComplete { _, error ->
            if (error != null) later(Duration.ZERO) { connection.end(SocketEnd.UNREACHABLE, rootMessage(error)) }
        }
    }

    private fun rootMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private inner class Connection : WebSocket.Listener {
        private val parts = StringBuilder()

        @Volatile private var socket: WebSocket? = null

        @Volatile private var ended = false

        @Volatile private var lastHeard = System.nanoTime()

        /** When the subscription was confirmed, by [System.nanoTime]. Used on the socket's thread. */
        private var subscribedSince: Long? = null

        override fun onOpen(socket: WebSocket) {
            this.socket = socket
            if (ended) return socket.abort()
            heard()
            val id = "watchdawg-${subscriptions.incrementAndGet()}"
            socket.sendText(mapper.writeValueAsString(subscribe(context, id)), true)
            socket.request(1)
            later(props.subscribeTimeout) {
                if (subscribedSince ==
                    null
                ) {
                    end(SocketEnd.UNCONFIRMED, "not confirmed within ${props.subscribeTimeout}")
                }
            }
        }

        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            heard()
            try {
                parts.append(data)
                if (last) {
                    val message = parts.toString()
                    parts.setLength(0)
                    when (feed.receive(message, clock.instant())) {
                        Received.SUBSCRIBED -> later(Duration.ZERO) { subscribed() }
                        Received.REFUSED -> later(Duration.ZERO) { end(SocketEnd.REFUSED, "subscription refused") }
                        else -> {}
                    }
                }
            } finally {
                socket.request(1)
            }
            return null
        }

        override fun onBinary(socket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            heard()
            if (last) {
                log.warn("skipped a binary message from the socket")
                metrics.socketFrame(SocketFrame.SKIPPED)
            }
            socket.request(1)
            return null
        }

        override fun onPing(socket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            heard()
            socket.request(1)
            return null
        }

        override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            later(Duration.ZERO) { end(SocketEnd.CLOSED, "$statusCode $reason") }
            return null
        }

        override fun onError(socket: WebSocket, error: Throwable) {
            later(Duration.ZERO) { end(SocketEnd.FAILED, rootMessage(error)) }
        }

        /** Abandons this connection and schedules the next. On the socket's thread. */
        fun end(reason: SocketEnd, detail: String) {
            if (ended) return
            ended = true
            socket?.abort()
            if (current !== this) return
            metrics.socketConnected(false)
            metrics.socketReconnect(reason)
            val delay = reconnects.next(subscribedSince?.let { Duration.ofNanos(System.nanoTime() - it) })
            log.warn("the socket connection ended ({}: {}), reconnecting in {}", reason.tag, detail, delay)
            later(delay) { connect() }
        }

        /** Closes this connection for good, as the socket stops. */
        fun close() {
            ended = true
            val socket = socket ?: return
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down").get(2, SECONDS) }
            socket.abort()
        }

        private fun subscribed() {
            if (ended || subscribedSince != null) return
            subscribedSince = System.nanoTime()
            metrics.socketConnected(true)
            log.info("subscribed to new orders on {}, crossplay {}", context.platform, context.crossplay)
            watchSilence()
            gapFill.run()
        }

        private fun watchSilence() {
            if (ended) return
            val quiet = Duration.ofNanos(System.nanoTime() - lastHeard)
            if (quiet >= props.silenceTimeout) {
                end(SocketEnd.SILENT, "nothing received for $quiet")
            } else {
                later(props.silenceTimeout.minus(quiet)) { watchSilence() }
            }
        }

        private fun heard() {
            lastHeard = System.nanoTime()
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
        wfm: WfmClient,
        ingest: OrderIngest,
        metrics: WfmMetrics,
        mapper: JsonMapper,
    ) = WfmSocket(connector, props, context, feed, GapFill(wfm, ingest, metrics), metrics, mapper)
}

const val SOCKET_ENABLED = "watchdawg.socket.enabled"
