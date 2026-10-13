package com.watchdawg.market.observability

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.EventKind
import com.watchdawg.market.ingest.OrderEvent
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.store.upsert
import com.watchdawg.market.watch.SignalState
import com.watchdawg.market.wfm.OrderType
import com.watchdawg.market.wfm.WfmContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ObservabilityTest {
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var context: WfmContext

    private val registry = SimpleMeterRegistry()
    private val clock = TestClock(MIDNIGHT.minusSeconds(1), ZoneId.of("Pacific/Auckland"))
    private lateinit var store: OrderStore
    private lateinit var tx: TransactionTemplate
    private var market = 0L

    @BeforeEach
    fun setup() {
        items.upsert(ItemRecord(id = "metrics-item", slug = "metrics-item"))
        market = markets.resolve(MarketKey("metrics-item"))
        tx = TransactionTemplate(transactions)
        store = OrderStore(jdbc, context, OrderEventMetrics(registry))
        DailySignalMetrics(jdbc, transactions, registry, clock)
    }

    @Test
    fun `each source counts only inserted rows after the outer transaction commits`() {
        Source.entries.forEach { source ->
            assertEquals(0.0, events(source))
            tx.executeWithoutResult {
                val first = event("${source.db}-first")
                store.append(listOf(first), source, MIDNIGHT)
                // One conflict and one new row in the same JDBC batch.
                store.append(listOf(first, event("${source.db}-second")), source, MIDNIGHT)
                assertEquals(0.0, events(source), "uncommitted rows must not advance counters")
            }
            assertEquals(2.0, events(source))
            tx.executeWithoutResult {
                store.append(listOf(event("${source.db}-first")), source, MIDNIGHT)
                store.append(emptyList(), source, MIDNIGHT)
            }
            assertEquals(2.0, events(source), "replays and empty appends must not count")
        }
        assertEquals(6, rows("order_event"))
    }

    @Test
    fun `rollback-only and exceptions discard both rows and throughput`() {
        tx.executeWithoutResult { status ->
            store.append(listOf(event("rollback-only")), Source.WS, MIDNIGHT)
            status.setRollbackOnly()
        }
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                store.append(listOf(event("exception")), Source.RECENT, MIDNIGHT)
                error("later ingest step failed")
            }
        }
        assertEquals(0, rows("order_event"))
        assertEquals(0.0, events(Source.WS))
        assertEquals(0.0, events(Source.RECENT))
        tx.executeWithoutResult { store.append(listOf(event("rollback-only")), Source.WS, MIDNIGHT) }
        assertEquals(1.0, events(Source.WS), "a successful retry counts once")
    }

    @Test
    fun `savepoint rollback excludes nested rows but retains the surrounding appends`() {
        val nested = TransactionTemplate(transactions).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }
        tx.executeWithoutResult {
            store.append(listOf(event("before")), Source.BOOK, MIDNIGHT)
            nested.executeWithoutResult { status ->
                store.append(listOf(event("nested")), Source.BOOK, MIDNIGHT)
                status.setRollbackOnly()
            }
            store.append(listOf(event("after")), Source.BOOK, MIDNIGHT)
            assertEquals(0.0, events(Source.BOOK))
        }
        assertEquals(2, rows("order_event"))
        assertEquals(2.0, events(Source.BOOK))
    }

    @Test
    fun `missing transaction is rejected before an event can autocommit`() {
        assertFailsWith<IllegalStateException> {
            store.append(listOf(event("no-transaction")), Source.WS, MIDNIGHT)
        }
        assertEquals(0, rows("order_event"))
        assertEquals(0.0, events(Source.WS))
    }

    @Test
    fun `fresh metrics recover every state and final successes from durable rows`() {
        clock.at = MIDNIGHT.plusSeconds(60)
        SignalState.entries.forEach { state ->
            signal(state.db, state, MIDNIGHT, if (state == SignalState.SENT) MIDNIGHT else null)
        }
        // A prior day's admission finalized today counts only as a success today.
        signal("late", SignalState.SENT, MIDNIGHT.minusSeconds(1), MIDNIGHT.plusSeconds(1))
        // Attempts, even many of them, are not finalized successes.
        jdbc.jdbcTemplate.update("update signal set attempts = 9 where state = 'pending'")
        val restarted = SimpleMeterRegistry()
        DailySignalMetrics(jdbc, transactions, restarted, clock)
        SignalState.entries.forEach { assertEquals(1.0, admissions(it, restarted)) }
        assertEquals(2.0, notifications(restarted))
        assertEquals(1.0, admissions(SignalState.PENDING))
    }

    @Test
    fun `gauges hide uncommitted admissions and finalization and recover after rollback`() {
        clock.at = MIDNIGHT.plusSeconds(60)
        signal("pending", SignalState.PENDING, MIDNIGHT)
        tx.executeWithoutResult { status ->
            signal("uncommitted", SignalState.SUPPRESSED, MIDNIGHT)
            finalizePending()
            assertEquals(1.0, admissions(SignalState.PENDING))
            assertEquals(0.0, admissions(SignalState.SENT))
            assertEquals(0.0, admissions(SignalState.SUPPRESSED))
            assertEquals(0.0, notifications())
            status.setRollbackOnly()
        }
        assertEquals(1.0, admissions(SignalState.PENDING))
        assertEquals(0.0, notifications())
        tx.executeWithoutResult { finalizePending() }
        assertEquals(0.0, admissions(SignalState.PENDING))
        assertEquals(1.0, admissions(SignalState.SENT))
        assertEquals(1.0, notifications())
    }

    @Test
    fun `UTC midnight changes both gauges without restart regardless of clock zone`() {
        val before = MIDNIGHT.minusNanos(1000)
        signal("before", SignalState.SENT, before, before)
        signal("boundary", SignalState.SENT, MIDNIGHT, MIDNIGHT)
        signal("delayed", SignalState.SENT, before, MIDNIGHT)
        signal("next-day", SignalState.SENT, MIDNIGHT.plusSeconds(86400), MIDNIGHT.plusSeconds(86400))
        clock.at = before
        assertEquals(2.0, admissions(SignalState.SENT))
        assertEquals(1.0, notifications())
        clock.at = MIDNIGHT
        assertEquals(1.0, admissions(SignalState.SENT))
        assertEquals(2.0, notifications())
        clock.at = MIDNIGHT.plusSeconds(86400)
        assertEquals(1.0, admissions(SignalState.SENT))
        assertEquals(1.0, notifications())
        clock.at = MIDNIGHT.plusSeconds(172800)
        SignalState.entries.forEach { assertEquals(0.0, admissions(it)) }
        assertEquals(0.0, notifications())
    }

    private fun events(source: Source): Double =
        registry.get("watchdawg.order.events").tag("source", source.db).counter().count()

    private fun admissions(state: SignalState, meters: SimpleMeterRegistry = registry): Double =
        meters.get("watchdawg.admissions.today").tag("state", state.db).gauge().value()

    private fun notifications(meters: SimpleMeterRegistry = registry): Double =
        meters.get("watchdawg.notifications.today").gauge().value()

    private fun rows(table: String): Int =
        jdbc.jdbcTemplate.queryForObject("select count(*) from $table", Int::class.java)!!

    private fun event(id: String) = OrderEvent(id, market, EventKind.APPEARED, OrderType.SELL, 10, null, 1, null, null)

    private fun signal(id: String, state: SignalState, seenAt: Instant, notifiedAt: Instant? = null) {
        jdbc.update(
            """
            insert into signal(watch, rule, market_id, order_id, dedup_key, unit_price, threshold,
                               platinum, priority, topic, state, seen_at, notified_at)
            values ('removed-watch', 'underpriced', :market, :id, :id, 10, 12, 10, 3, 'default',
                    :state, :seenAt, :notifiedAt)
            """,
            mapOf(
                "market" to market,
                "id" to id,
                "state" to state.db,
                "seenAt" to Timestamp.from(seenAt),
                "notifiedAt" to notifiedAt?.let(Timestamp::from),
            ),
        )
    }

    private fun finalizePending() {
        jdbc.update(
            "update signal set state = 'sent', notified_at = :at where state = 'pending'",
            mapOf("at" to Timestamp.from(clock.at)),
        )
    }

    private class TestClock(var at: Instant, private val zone: ZoneId = UTC) : Clock() {
        override fun instant(): Instant = at

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = TestClock(at, zone)
    }

    private companion object {
        val MIDNIGHT: Instant = Instant.parse("2026-10-14T00:00:00Z")
    }
}
