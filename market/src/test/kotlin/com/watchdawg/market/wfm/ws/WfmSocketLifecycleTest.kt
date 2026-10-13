package com.watchdawg.market.wfm.ws

import com.watchdawg.market.baseline.BaselineBudget
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WfmSocketLifecycleTest {
    @Test
    fun `flaps reach backoff cap and stable disconnect resets without a tick`() {
        Fixture().use { f ->
            f.socket.start()
            val bounds = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 64000L, 128000L, 256000L, 300000L, 300000L)
            for (bound in bounds) {
                val attempt = f.next()
                // Hold the callback monitor so the watchdog cannot observe the advanced clock first.
                synchronized(f.socket) {
                    attempt.subscribe()
                    f.clock.at = f.clock.at.plusSeconds(59)
                    attempt.close()
                }
                assertEquals(bound, f.bounds.poll(2, SECONDS))
            }
            val stable = f.next()
            synchronized(f.socket) {
                stable.subscribe()
                f.clock.at = f.clock.at.plusSeconds(60)
                stable.close()
            }
            assertEquals(1000L, f.bounds.poll(2, SECONDS))
        }
    }

    @Test
    fun `reconnect waits for the selected jitter delay including its upper bound`() {
        for (delay in listOf(500L, 1000L)) {
            Fixture(delay = { delay }).use { f ->
                f.socket.start()
                val attempt = f.next()
                synchronized(f.socket) {
                    attempt.close()
                    f.clock.at = f.clock.at.plusMillis(delay - 1)
                }
                assertEquals(1000L, f.bounds.poll(2, SECONDS))
                assertNull(f.attempts.poll(100, MILLISECONDS))
                f.clock.at = f.clock.at.plusMillis(1)
                f.next()
            }
        }
    }

    @Test
    fun `restart waits for stop to drain and stale callbacks cannot affect the replacement`() {
        Fixture(withCoverage = true).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.blockCoverage(entered, release)
            val pool = Executors.newVirtualThreadPerTaskExecutor()
            try {
                f.socket.start()
                val old = f.next()
                val receiving = pool.submit { old.subscribe() }
                assertTrue(entered.await(2, SECONDS))
                val stopping = pool.submit { f.socket.stop() }
                eventually { !f.socket.isRunning() }
                val starting = pool.submit { f.socket.start() }
                assertFailsWith<TimeoutException> { starting.get(100, MILLISECONDS) }
                assertNull(f.attempts.poll())
                release.countDown()
                receiving.get(2, SECONDS)
                stopping.get(2, SECONDS)
                starting.get(2, SECONDS)
                val replacement = f.next()
                replacement.subscribe()
                old.listener.onOpen(old.webSocket)
                old.close()
                old.listener.onError(old.webSocket, IllegalStateException("late"))
                old.text("""{"route":"$NEW_ORDER","payload":{}}""")
                assertTrue(f.socket.isRunning())
                assertEquals(1.0, f.registry.get("wfm.socket.connected").gauge().value())
                assertEquals(0.0, f.registry.get("wfm.socket.frames").tag("outcome", "skipped").counter().count())
                verifyNoInteractions(f.ingest)
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, SECONDS))
            }
        }
    }

    @Test
    fun `drain timeout is bounded and restart remains blocked until old work finishes`() {
        Fixture(withCoverage = true, drainTimeout = Duration.ofMillis(50)).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.blockCoverage(entered, release)
            val pool = Executors.newVirtualThreadPerTaskExecutor()
            try {
                f.socket.start()
                val old = f.next()
                val receiving = pool.submit { old.subscribe() }
                assertTrue(entered.await(2, SECONDS))
                val stopping = pool.submit<Boolean> {
                    assertFailsWith<IllegalStateException> { f.socket.stop() }
                    true
                }
                assertTrue(stopping.get(2, SECONDS))
                assertFalse(f.socket.isRunning())
                assertFailsWith<IllegalStateException> { f.socket.start() }
                assertNull(f.attempts.poll())
                release.countDown()
                receiving.get(2, SECONDS)
                f.socket.stop()
                f.socket.start()
                f.next().subscribe()
                assertEquals(1.0, f.registry.get("wfm.socket.connected").gauge().value())
            } finally {
                release.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, SECONDS))
            }
        }
    }

    @Test
    fun `coverage needs subscription and successful frames and failures do not close the socket`() {
        Fixture(withCoverage = true).use { f ->
            val recorded = mutableListOf<java.time.Instant>()
            f.coverage = { stream, at, maxGap ->
                assertEquals("socket", stream)
                assertEquals(Duration.ofSeconds(180), maxGap)
                recorded += at
                if (recorded.size == 2) error("telemetry unavailable")
            }
            f.socket.start()
            val attempt = f.next()
            attempt.heartbeat()
            assertTrue(recorded.isEmpty())
            attempt.subscribe()
            assertEquals(listOf(f.clock.at), recorded)
            repeat(5) { attempt.heartbeat() }
            f.clock.at = f.clock.at.plusSeconds(29)
            attempt.heartbeat()
            assertEquals(1, recorded.size)
            f.clock.at = f.clock.at.plusSeconds(1)
            attempt.heartbeat() // throwing telemetry write must not escape the callback
            repeat(5) { attempt.heartbeat() }
            assertEquals(2, recorded.size)
            assertEquals(1.0, f.registry.get("wfm.socket.connected").gauge().value())
            f.clock.at = f.clock.at.plusSeconds(30)
            attempt.text("not json")
            attempt.text("""{"route":"unknown"}""")
            assertEquals(2, recorded.size)
            attempt.heartbeat()
            assertEquals(3, recorded.size)
            f.socket.stop()
            attempt.heartbeat()
            assertEquals(3, recorded.size)
        }
    }

    private class Fixture(
        withCoverage: Boolean = false,
        drainTimeout: Duration = Duration.ofSeconds(2),
        delay: (Long) -> Long = { 0 },
    ) : AutoCloseable {
        val clock = MutableClock()
        val registry = SimpleMeterRegistry()
        val ingest = mock(OrderIngest::class.java)
        var coverage: (String, java.time.Instant, Duration) -> Unit = { _, _, _ -> }
        val budget = mock(BaselineBudget::class.java) { call ->
            if (call.method.name == "evaluated") {
                coverage(call.getArgument(0), call.getArgument(1), call.getArgument(2))
            }
            null
        }
        val attempts = LinkedBlockingQueue<Attempt>()
        val bounds = LinkedBlockingQueue<Long>()
        private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val socket = WfmSocket(
            SocketProperties(url = URI("ws://127.0.0.1/socket")),
            "test",
            SocketProtocol(mapper, WfmContext("pc", true)),
            SocketConnector { _, _, _, listener ->
                val webSocket = mock(WebSocket::class.java)
                val attempt = Attempt(listener, webSocket)
                `when`(
                    webSocket.sendText(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true)),
                )
                    .thenAnswer { call ->
                        attempt.id = mapper.readTree(call.getArgument<String>(0)).path("id").asText()
                        CompletableFuture.completedFuture(webSocket)
                    }
                listener.onOpen(webSocket)
                attempts.add(attempt)
                CompletableFuture.completedFuture(webSocket)
            },
            ingest,
            mock(SocketGapFill::class.java),
            WfmMetrics(registry),
            clock,
            baselineBudget = if (withCoverage) budget else null,
            jitter = { upper ->
                bounds.add(upper)
                delay(upper)
            },
            drainTimeout = drainTimeout,
        )

        fun next(): Attempt = checkNotNull(attempts.poll(2, SECONDS)) { "no connection attempt" }

        fun blockCoverage(entered: CountDownLatch, release: CountDownLatch) {
            coverage = { _, _, _ ->
                entered.countDown()
                check(release.await(5, SECONDS)) { "coverage was not released" }
            }
        }

        override fun close() = socket.stop()
    }

    private class Attempt(val listener: WebSocket.Listener, val webSocket: WebSocket) {
        var id = ""
        fun text(value: String) {
            listener.onText(webSocket, value, true)
        }
        fun subscribe() = text("""{"route":"$SUBSCRIBE:ok","id":"$id"}""")
        fun heartbeat() = text("""{"route":"$ONLINE_REPORT"}""")
        fun close() {
            listener.onClose(webSocket, 1000, "test")
        }
    }
}
