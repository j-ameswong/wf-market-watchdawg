package com.watchdawg.market.notify

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.watch.AlertMetrics
import com.watchdawg.market.watch.InvalidWatchException
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.ingestWith
import com.watchdawg.market.watch.khraOrder
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The outbox reaches (mock) ntfy, and only a 2xx marks a signal sent (R10.1, R10.2, R10.4). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DispatcherTest {

    @Autowired
    @Qualifier(NotifyConfig.NTFY_CLIENT)
    lateinit var ntfyRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var mapper: JsonMapper

    private val builder by lazy { ntfyRestClient.mutate() }
    private val server by lazy { MockRestServiceServer.bindTo(builder).build() }

    @Test
    fun `a pending signal is posted once, topic in the body, and marked sent`() {
        admitOne()
        server.expect(requestTo("https://ntfy.sh/"))
            .andExpect(method(POST))
            .andExpect(jsonPath("$.topic").value(TOPIC))
            .andExpect(jsonPath("$.title").value("Khra (rank 3) at 10 plat"))
            .andExpect(
                jsonPath("$.message").value("60 plat for a lot of 6. Seen 2026-09-26 12:00 UTC. Watch: cheap-khra."),
            )
            .andExpect(jsonPath("$.priority").value(4))
            .andExpect(jsonPath("$.click").value("https://warframe.market/items/khra"))
            .andRespond(withSuccess())

        dispatcher().dispatch()
        dispatcher().dispatch()

        server.verify()
        val row = jdbc.queryForMap("select state, attempts, notified_at from signal")
        assertEquals("sent", row["state"])
        assertEquals(1, row["attempts"])
        assertNotNull(row["notified_at"])
    }

    @Test
    fun `a non-2xx leaves the signal pending with the error recorded`() {
        admitOne()
        server.expect(requestTo("https://ntfy.sh/")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        dispatcher().dispatch()

        server.verify()
        val row = jdbc.queryForMap("select state, attempts, last_error, notified_at from signal")
        assertEquals("pending", row["state"])
        assertEquals(1, row["attempts"])
        assertNotNull(row["last_error"])
        assertNull(row["notified_at"])
    }

    @Test
    fun `a signal whose topic is not mapped is not sent, and counts as a failed attempt`() {
        admitOne()

        dispatcher(topics = mapOf("other" to TOPIC)).dispatch()

        server.verify()
        val row = jdbc.queryForMap("select state, last_error from signal")
        assertEquals("pending", row["state"])
        assertContains(row["last_error"] as String, "'default' is not mapped")
    }

    @Test
    fun `failures back off to the attempt cap, keep the last error, then fail for good (R10_3)`() {
        admitOne()
        repeat(5) {
            server.expect(requestTo("https://ntfy.sh/")).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        }
        val registry = SimpleMeterRegistry()
        val waits = mutableListOf<Long>()

        var now = SEEN
        repeat(5) {
            dispatcher(clock = Clock.fixed(now, UTC), registry = registry).dispatch()
            val next = jdbc.queryForObject("select next_attempt_at from signal", Timestamp::class.java)?.toInstant()
            if (next != null) {
                waits += Duration.between(now, next).seconds
                // Not yet due: nothing is sent.
                dispatcher(clock = Clock.fixed(next.minusSeconds(1), UTC), registry = registry).dispatch()
                now = next
            }
        }

        server.verify()
        assertEquals(listOf(30L, 60L, 120L, 240L), waits)
        val row = jdbc.queryForMap("select state, attempts, last_error from signal")
        assertEquals("failed", row["state"])
        assertEquals(5, row["attempts"])
        assertContains(row["last_error"] as String, "502")
        assertEquals(4.0, registry.find("watchdawg.deliveries").tag("outcome", "retried").counter()!!.count())
        assertEquals(1.0, registry.find("watchdawg.deliveries").tag("outcome", "failed").counter()!!.count())
        assertEquals(0.0, registry.find("watchdawg.deliveries").tag("outcome", "sent").counter()!!.count())
    }

    @Test
    fun `a signal written before a restart is sent after it, however late`() {
        admitOne()

        // A new dispatcher is all a restart leaves: the outbox is the only queue.
        server.expect(requestTo("https://ntfy.sh/")).andRespond(withSuccess())
        dispatcher(clock = Clock.fixed(SEEN.plus(Duration.ofDays(3)), UTC)).dispatch()

        server.verify()
        assertEquals("sent", jdbc.queryForObject("select state from signal", String::class.java))
    }

    @Test
    fun `the topic is in no URL, no recorded error and no log line, down to DEBUG (R10_6)`() {
        admitOne()
        server.expect(requestTo("https://ntfy.sh/"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""{"error":"no access to $TOPIC"}"""))

        val logged = capturingDebugLogs { dispatcher().dispatch() }

        server.verify()
        val error = jdbc.queryForObject("select last_error from signal", String::class.java)!!
        assertContains(error, "403")
        assertFalse(error.contains(TOPIC), error)
        assertTrue(logged.isNotEmpty())
        logged.forEach { assertFalse(it.contains(TOPIC), it) }
    }

    @Test
    fun `with a topic mapped, a watch naming an unmapped one fails startup`() {
        val watches = items.watchesOf(khraWatch("mapped"), khraWatch("stray", topic = "trades"))

        val e = assertFailsWith<InvalidWatchException> { checkTopics(watches, mapOf("default" to TOPIC)) }

        assertContains(e.message!!, "'stray'")
        assertContains(e.message!!, "WATCHDAWG_NOTIFY_TOPICS_TRADES")
        assertFalse(e.message!!.contains(TOPIC))
        checkTopics(watches, emptyMap()) // no topic at all: delivery is off, nothing to check
    }

    private fun capturingDebugLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val level = root.level
        root.level = Level.DEBUG
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = level
        }
        return appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
    }

    private fun admitOne() {
        val ingest = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 60, perTrade = 6)), SEEN)
    }

    private fun dispatcher(
        topics: Map<String, String> = mapOf("default" to TOPIC),
        clock: Clock = Clock.fixed(SEEN.plusSeconds(5), UTC),
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): Dispatcher {
        val watches = Watches(emptyList())
        return Dispatcher(
            signals,
            NtfyNotifier(builder.build(), mapper),
            NotifyProperties("https://ntfy.sh", Duration.ofSeconds(10), 20, 5, Duration.ofSeconds(30), topics),
            AlertMetrics(registry, watches),
            watches,
            clock,
        )
    }

    private companion object {
        const val TOPIC = "wd-test-4f9a1c0e8b7d"
        val SEEN: Instant = Instant.parse("2026-09-26T12:00:00Z")
    }
}
