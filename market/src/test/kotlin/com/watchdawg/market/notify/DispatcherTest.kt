package com.watchdawg.market.notify

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.ingestWith
import com.watchdawg.market.watch.khraOrder
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import org.junit.jupiter.api.Test
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun `a signal whose topic is not mapped is not sent`() {
        admitOne()

        dispatcher(topics = emptyMap()).dispatch()

        server.verify()
        assertEquals("pending", jdbc.queryForObject("select state from signal", String::class.java))
    }

    private fun admitOne() {
        val ingest = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 60, perTrade = 6)), SEEN)
    }

    private fun dispatcher(topics: Map<String, String> = mapOf("default" to TOPIC)) = Dispatcher(
        signals,
        NtfyNotifier(builder.build()),
        NotifyProperties("https://ntfy.sh", Duration.ofSeconds(10), 20, topics),
        Clock.fixed(SEEN.plusSeconds(5), UTC),
    )

    private companion object {
        const val TOPIC = "wd-test-4f9a1c0e8b7d"
        val SEEN: Instant = Instant.parse("2026-09-26T12:00:00Z")
    }
}
