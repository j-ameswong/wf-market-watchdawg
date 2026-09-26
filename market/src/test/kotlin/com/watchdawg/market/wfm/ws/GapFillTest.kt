package com.watchdawg.market.wfm.ws

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.ingestWith
import com.watchdawg.market.watch.khraOrder
import com.watchdawg.market.watch.khraWatch
import com.watchdawg.market.watch.watchesOf
import com.watchdawg.market.wfm.WfmMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import kotlin.test.assertEquals

/**
 * C5 T4: the gap-fill after each confirmed subscription (R5.4, decision 5), through the real
 * transport. A run that should make no request would take the next expected response, and the run
 * after it would then fail on an unexpected request.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class GapFillTest {

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var wfmRestClient: RestClient

    private val clock = MutableClock(T0)
    private val registry = SimpleMeterRegistry()
    private val recent by lazy { RecentOrders(wfmRestClient) }
    private val gapFill by lazy {
        val ingest = ingestWith(items.watchesOf(khraWatch()), store, markets, signals, transactions)
        GapFill(recent.client, ingest, WfmMetrics(registry), clock)
    }

    @Test
    fun `recent orders are recorded as recent, seen at the request, and reach the rule`() {
        val orders = listOf(khraOrder("cheap", platinum = 10), khraOrder("dear", platinum = 13))
        recent.answer(once(), mapper.writeValueAsString(orders))

        gapFill.run()

        recent.server.verify()
        assertEquals(
            listOf(Triple("cheap", "recent", T0), Triple("dear", "recent", T0)),
            jdbc.query("select order_id, source, observed_at from order_event order by order_id") { rs, _ ->
                Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant())
            },
        )
        assertEquals(
            listOf("cheap" to T0),
            jdbc.query("select order_id, seen_at from signal") { rs, _ ->
                rs.getString(1) to rs.getTimestamp(2).toInstant()
            },
        )
        assertEquals(1.0, gapFills("filled"))
    }

    @Test
    fun `a gap-fill under a minute after the last is skipped`() {
        gapFill // the catalog, before any expectation
        recent.answer(once())
        recent.answer(once())

        gapFill.run()
        clock.advance(Duration.ofSeconds(59))
        gapFill.run()
        clock.advance(Duration.ofSeconds(1))
        gapFill.run()

        recent.server.verify()
        assertEquals(2.0, gapFills("filled"))
        assertEquals(1.0, gapFills("skipped"))
    }

    @Test
    fun `after a throttle with Retry-After 300, none 61 seconds later and one after 300`() {
        gapFill
        recent.server.expect(once(), requestTo(RecentOrders.URL))
            .andRespond(withTooManyRequests().header(RETRY_AFTER, "300"))
        recent.answer(once())

        gapFill.run()
        clock.advance(Duration.ofSeconds(61))
        gapFill.run()
        clock.advance(Duration.ofSeconds(239))
        gapFill.run()

        recent.server.verify()
        assertEquals(listOf(1.0, 1.0, 1.0), listOf("throttled", "skipped", "filled").map(::gapFills))
    }

    @Test
    fun `a failed gap-fill is counted and holds off the next for a minute`() {
        gapFill
        recent.server.expect(once(), requestTo(RecentOrders.URL)).andRespond(withServerError())
        recent.answer(once())

        gapFill.run()
        clock.advance(Duration.ofSeconds(30))
        gapFill.run()
        clock.advance(Duration.ofSeconds(30))
        gapFill.run()

        recent.server.verify()
        assertEquals(listOf(1.0, 1.0, 1.0), listOf("failed", "skipped", "filled").map(::gapFills))
    }

    private fun gapFills(outcome: String) =
        registry.get("wfm.socket.gapfills").tag("outcome", outcome).counter().count()

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-09-26T12:00:00Z")
    }
}
