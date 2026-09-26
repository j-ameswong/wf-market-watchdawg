package com.watchdawg.market.watch

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.BookOutcome
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.Order
import com.watchdawg.market.wfm.OrderOwner
import com.watchdawg.market.wfm.OrderType
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import com.watchdawg.market.wfm.ws.FakeSocketServer
import com.watchdawg.market.wfm.ws.NEW_ORDER
import com.watchdawg.market.wfm.ws.SocketConnector
import com.watchdawg.market.wfm.ws.SocketFeed
import com.watchdawg.market.wfm.ws.WfmSocket
import com.watchdawg.market.wfm.ws.eventually
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * C5 T3: an order the socket adds reaches the same rule and admission as a polled one, in the
 * transaction that recorded it (decision 3), and admission is serialised across threads by one
 * advisory lock (decision 4).
 *
 * The race tests hold that lock from a connection of their own until both ingests are waiting on
 * it. That proves both reached admission at once, before either committed; without the lock they
 * would never wait, and the test would fail rather than pass by luck.
 *
 * The first write into a new TimescaleDB chunk locks the hypertable until its transaction ends, so
 * racers writing events into a chunk that does not exist yet would queue behind the first of them
 * before ever reaching admission. Each race therefore starts with its chunk already written.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SocketAlertsTest {

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var connector: SocketConnector

    @Autowired lateinit var context: WfmContext

    @Autowired lateinit var mapper: JsonMapper

    private val executor = Executors.newFixedThreadPool(2)

    @AfterEach
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun `a cheap listing on the socket admits one signal, seen when its message arrived`() {
        val ingest = ingest(khraWatch())
        val metrics = WfmMetrics(SimpleMeterRegistry())
        val server = FakeSocketServer()
        val arrived = Clock.fixed(T1, UTC)
        val socket =
            WfmSocket(connector, server.url, context, SocketFeed(ingest, mapper, metrics), metrics, mapper, arrived)
        try {
            socket.start()
            server.nextSession().basicRemote.sendText(newOrder(khraOrder("cheap", platinum = 10)))
            eventually(what = "the signal") { signalRows().isNotEmpty() }
        } finally {
            socket.stop()
            server.close()
        }

        assertEquals(listOf("cheap" to "pending"), signalRows())
        assertEquals(T1, jdbc.queryForObject("select seen_at from signal", Timestamp::class.java)!!.toInstant())
        assertEquals(listOf("appeared" to "ws"), events())
    }

    @Test
    fun `an order already known admits nothing, even with its owner now online`() {
        val ingest = ingest(khraWatch())
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("known", platinum = 10, online = false)), T1)

        assertEquals(0, ingest.ingestPartial(listOf(khraOrder("known", platinum = 10)), Source.WS, T2))

        assertEquals(emptyList(), signalRows())
    }

    @Test
    fun `the next book poll holding the same listing at the same price admits nothing`() {
        // No cooldown, so only the dedup key can keep the book from signalling the listing again.
        val ingest = ingest(khraWatch().copy(cooldown = Duration.ZERO))
        ingest.ingestPartial(listOf(khraOrder("cheap", platinum = 10)), Source.WS, T1)

        val outcome = ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T2)

        assertEquals(BookOutcome.Reconciled(events = 0, quotes = 1, signals = 0), outcome)
        assertEquals(listOf("cheap" to "pending"), signalRows())
    }

    @Test
    fun `rolling back a partial ingest leaves neither its events nor its signal (R9a_7)`() {
        val ingest = ingest(khraWatch()) // commits the catalog first: markets resolve in a transaction of their own
        TransactionTemplate(transactions).executeWithoutResult { status ->
            ingest.ingestPartial(listOf(khraOrder("cheap", platinum = 10)), Source.WS, T1)
            assertEquals(1, signalRows().size)
            status.setRollbackOnly()
        }

        assertEquals(emptyList(), signalRows())
        assertEquals(emptyList(), events())
        assertEquals(0, jdbc.queryForObject("select count(*) from wfm_order", Int::class.java))
    }

    @Test
    fun `a socket ingest and a book racing with equal candidates for one watch admit one signal`() {
        val ingest = ingest(khraWatch())
        writeChunk(ingest)

        val (socket, book) = whileAdmissionIsLocked {
            val socket = executor.submit<Int> {
                ingest.ingestPartial(listOf(khraOrder("posted", platinum = 10)), Source.WS, T1)
            }
            val book = executor.submit<BookOutcome> {
                ingest.reconcileBook(KHRA.id, listOf(khraOrder("polled", platinum = 10)), T1)
            }
            awaitAdmissionWaiters(2)
            socket to book
        }
        socket.get(10, SECONDS)
        book.get(10, SECONDS)

        assertEquals(1, signalRows().size, "the cooldown let both through: ${signalRows()}")
    }

    @Test
    fun `two ingests racing for the day's last admission admit one and suppress the other (R9a_8)`() {
        val ingest = ingest(khraWatch("ranked", rank = "3"), khraWatch("unranked", rank = "0"), dailyCeiling = 1)
        writeChunk(ingest)

        val racers = whileAdmissionIsLocked {
            val racers = listOf(khraOrder("ranked", platinum = 10), khraOrder("unranked", platinum = 10, rank = 0))
                .map { order -> executor.submit<Int> { ingest.ingestPartial(listOf(order), Source.WS, T1) } }
            awaitAdmissionWaiters(2)
            racers
        }
        racers.forEach { it.get(10, SECONDS) }

        assertEquals(listOf("pending", "suppressed"), signalRows().map { it.second }.sorted())
    }

    @Test
    fun `orders that find no candidate are recorded, evaluate nothing and never wait for admission`() {
        val ingest = ingest(khraWatch())
        items.upsert(ItemRecord(id = "serration-id", slug = "serration"))
        val unwatched = Order(
            id = "unwatched",
            type = OrderType.SELL,
            platinum = 1,
            quantity = 1,
            visible = true,
            itemId = "serration-id",
            user = OrderOwner(platform = "pc", status = "ingame"),
        )

        val added = whileAdmissionIsLocked {
            executor.submit<Int> {
                ingest.ingestPartial(listOf(unwatched, khraOrder("dear", platinum = 13)), Source.WS, T1)
            }.get(10, SECONDS)
        }

        assertEquals(2, added)
        assertEquals(emptyList(), signalRows())
    }

    private fun ingest(vararg watches: WatchEntry, dailyCeiling: Int = 50): OrderIngest =
        ingestWith(items.watchesOf(*watches), store, markets, signals, transactions, dailyCeiling)

    /** Commits an event at [T1] from a listing no watch wants, so the racers' chunk exists. */
    private fun writeChunk(ingest: OrderIngest) {
        ingest.ingestPartial(listOf(khraOrder("dear", platinum = 13)), Source.WS, T1)
    }

    private fun newOrder(order: Order) = mapper.writeValueAsString(mapOf("route" to NEW_ORDER, "payload" to order))

    /** Runs [block] while a connection of the test's own holds the admission lock. */
    private fun <T> whileAdmissionIsLocked(block: () -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { it.execute("select pg_advisory_xact_lock(${SignalStore.ADMISSION_LOCK})") }
        try {
            block()
        } finally {
            connection.rollback()
        }
    }

    private fun awaitAdmissionWaiters(count: Int) = eventually(what = "$count ingests waiting to admit") {
        jdbc.queryForObject(
            """
            select count(*) from pg_locks
            where locktype = 'advisory' and objid = ${SignalStore.ADMISSION_LOCK} and not granted
            """,
            Int::class.java,
        ) == count
    }

    private fun signalRows() = jdbc.query("select order_id, state from signal order by id") { rs, _ ->
        rs.getString(1) to rs.getString(2)
    }

    private fun events() = jdbc.query("select event, source from order_event order by order_id") { rs, _ ->
        rs.getString(1) to rs.getString(2)
    }

    private companion object {
        val T1: Instant = Instant.parse("2026-09-26T12:00:00Z")
        val T2: Instant = T1.plusSeconds(120)
    }
}
