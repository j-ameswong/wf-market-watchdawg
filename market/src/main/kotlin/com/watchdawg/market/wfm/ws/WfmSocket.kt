package com.watchdawg.market.wfm.ws

import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.wfm.WfmMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/** One connection generation at a time. Old callbacks cannot revive or close a later attempt. */
class WfmSocket(
    private val props: SocketProperties,
    private val userAgent: String,
    private val protocol: SocketProtocol,
    private val connector: SocketConnector,
    private val ingest: OrderIngest,
    private val gapFill: SocketGapFill,
    private val metrics: WfmMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
    private val drainTimeout: Duration = Duration.ofSeconds(10),
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lifecycle = ReentrantLock()
    private val workGate = java.util.concurrent.locks.ReentrantReadWriteLock(true)
    private var scheduler: ScheduledExecutorService? = null
    private var gapWorker: java.util.concurrent.ExecutorService? = null

    @Volatile private var running = false

    @Volatile private var current: Connection? = null
    private var nextConnect = Instant.MIN
    private var backoff = props.backoffInitial.toMillis()

    override fun start() {
        lifecycle.withLock {
            synchronized(this) {
                if (running) return
                check(workGate.readLockCount == 0 && gapWorker?.isTerminated != false) {
                    "previous socket work has not drained"
                }
                running = true
                nextConnect = Instant.MIN
                backoff = props.backoffInitial.toMillis()
                gapWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("socket-gapfill").factory())
                scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("socket-watchdog").factory(),
                    ).also {
                        it.scheduleWithFixedDelay({ tick() }, 0, 25, MILLISECONDS)
                    }
            }
        }
    }

    override fun stop() {
        // Serialize lifecycle calls across the drain, but leave the callback monitor available.
        lifecycle.withLock {
            val worker = synchronized(this) {
                running = false
                val old = current
                current = null
                old?.socket?.abort()
                old?.future?.cancel(true)
                metrics.socketConnected(false)
                scheduler?.shutdownNow()
                gapWorker?.shutdownNow()
                scheduler = null
                gapWorker
            }
            val drain = workGate.writeLock()
            check(drain.tryLock(drainTimeout.toMillis(), MILLISECONDS)) {
                "socket work did not drain within $drainTimeout"
            }
            drain.unlock()
            check(worker == null || worker.awaitTermination(drainTimeout.toMillis(), MILLISECONDS)) {
                "socket gap-fill worker did not stop"
            }
            // Retain the old worker on timeout so start cannot bypass unfinished shutdown.
            synchronized(this) { gapWorker = null }
        }
    }

    private fun work(connection: Connection, action: () -> Unit) {
        val gate = workGate.readLock()
        gate.lock()
        try {
            if (running && current === connection) action()
        } finally {
            gate.unlock()
        }
    }

    override fun isRunning(): Boolean = running

    @Synchronized
    private fun tick() {
        if (!running) return
        val now = clock.instant()
        val active = current
        if (active == null) {
            if (now.isBefore(nextConnect)) return
            val attempt = Connection(now)
            current = attempt
            try {
                attempt.future = connector.connect(props.url, props.connectTimeout, userAgent, attempt)
                attempt.future!!.whenComplete { _, error -> if (error != null) ended(attempt, "connect") }
            } catch (e: Exception) {
                ended(attempt, "connect")
            }
            return
        }
        val subscribedAt = active.subscribedAt
        val openedAt = active.openedAt
        when {
            openedAt == null && !now.isBefore(active.started.plus(props.connectTimeout)) -> ended(active, "connect")

            openedAt != null && subscribedAt == null && !now.isBefore(
                openedAt.plus(props.subscriptionTimeout),
            ) -> ended(active, "subscription")

            subscribedAt != null && !now.isBefore(
                active.lastFrame.plus(props.silenceTimeout),
            ) -> ended(active, "silence")
        }
    }

    @Synchronized
    private fun ended(connection: Connection, reason: String) {
        if (current !== connection) return
        current = null
        metrics.socketConnected(false)
        connection.socket?.abort()
        connection.future?.cancel(true)
        if (!running) return
        metrics.socketReconnect(reason)
        val now = clock.instant()
        if (connection.subscribedAt?.let { !now.isBefore(it.plus(props.stableAfter)) } == true) {
            backoff = props.backoffInitial.toMillis()
        }
        nextConnect = now.plusMillis(jitter(backoff))
        val cap = props.backoffMax.toMillis()
        backoff = if (backoff >= cap - backoff) cap else backoff * 2
        log.info("socket disconnected: {}; reconnect scheduled", reason)
    }

    @Synchronized
    private fun subscribed(connection: Connection) {
        if (current !== connection || connection.subscribedAt != null || !running) return
        connection.subscribedAt = clock.instant()
        metrics.socketConnected(true)
        gapWorker?.submit { work(connection) { gapFill.run() } }
    }

    private inner class Connection(val started: Instant) : WebSocket.Listener {
        val id = UUID.randomUUID().toString()

        @Volatile var socket: WebSocket? = null

        @Volatile var future: CompletableFuture<WebSocket>? = null

        @Volatile var openedAt: Instant? = null

        @Volatile var subscribedAt: Instant? = null

        @Volatile var lastFrame: Instant = started
        private val text = StringBuilder()
        private var oversized = false

        override fun onOpen(webSocket: WebSocket) {
            synchronized(this@WfmSocket) {
                if (!running || current !== this) {
                    webSocket.abort()
                    return
                }
                socket = webSocket
                openedAt = clock.instant()
                webSocket.sendText(protocol.subscribe(id), true).whenComplete { _, error ->
                    if (error != null) ended(this, "error")
                }
                webSocket.request(1)
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (current !== this || !running) return null
            val arrived = clock.instant()
            lastFrame = arrived
            try {
                if (text.length + data.length > MAX_MESSAGE) oversized = true
                if (!oversized) text.append(data)
                if (last) {
                    if (oversized) {
                        metrics.socketFrame("skipped")
                        log.warn("skipped oversized socket message")
                    } else {
                        work(this) { receive(text.toString(), arrived) }
                    }
                }
            } finally {
                if (last) {
                    text.setLength(0)
                    oversized = false
                }
                webSocket.request(1)
            }
            return null
        }

        private fun receive(text: String, at: Instant) {
            val message = try {
                protocol.parse(text, id)
            } catch (e: Exception) {
                metrics.socketFrame("skipped")
                log.warn("skipped malformed socket message: {}", e.javaClass.simpleName)
                return
            }
            when (message) {
                is SocketMessage.Listing -> {
                    try {
                        ingest.ingestPartial(listOf(message.order), Source.WS, at)
                        metrics.socketFrame("order")
                    } catch (e: Exception) {
                        metrics.socketFrame("failed")
                        log.warn("socket ingest failed: {}", e.javaClass.simpleName)
                        ended(this, "ingest")
                    }
                }

                SocketMessage.Subscribed -> {
                    metrics.socketFrame("control")
                    subscribed(this)
                }

                SocketMessage.Rejected -> {
                    metrics.socketFrame("control")
                    ended(this, "subscription")
                }

                SocketMessage.Heartbeat -> {
                    metrics.socketFrame("control")
                }

                SocketMessage.Unknown -> {
                    metrics.socketFrame("skipped")
                    log.debug("skipped unknown socket route")
                }
            }
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            val at = clock.instant()
            lastFrame = at
            webSocket.request(1)
            return webSocket.sendPong(message)
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            val at = clock.instant()
            lastFrame = at
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            lastFrame = clock.instant()
            metrics.socketFrame("skipped")
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            ended(this, "closed")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) = ended(this, "error")
    }

    private companion object {
        const val MAX_MESSAGE = 1_048_576
    }
}
