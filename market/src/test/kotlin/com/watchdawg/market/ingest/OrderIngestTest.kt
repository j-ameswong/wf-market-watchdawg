package com.watchdawg.market.ingest

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.UnknownItemException
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.Envelope
import com.watchdawg.market.wfm.Order
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * C4's rules against the store, on the captured books (2026-09-25). `ReconcileTest` covers the
 * classification itself without a database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OrderIngestTest {

    @Autowired lateinit var ingest: OrderIngest

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var mapper: JsonMapper

    @BeforeEach
    fun catalog() {
        items.upsert(ItemRecord(id = SERRATION, slug = "serration"))
        items.upsert(ItemRecord(id = AYATAN, slug = "ayatan_anasa_sculpture"))
    }

    @Test
    fun `a captured mixed-rank book splits across one market per subtype and rank`() {
        val book = captured("serration")

        val outcome = ingest.reconcileBook(SERRATION, book, T1)

        val tuples = book.map { it.subtype to it.rank }.toSet()
        assertEquals(tuples.size, count("select count(*) from market where item_id = '$SERRATION'"))
        assertEquals(BookOutcome.Reconciled(events = 440, quotes = tuples.size), outcome)
        assertEquals(440, count("select count(*) from wfm_order"))
        // Every order sits in the market its own subtype and rank name.
        val stored = jdbc.query(
            "select o.id, m.subtype, m.rank from wfm_order o join market m on m.id = o.market_id",
        ) { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getObject(3) as Int?) }.toMap()
        assertEquals(book.associate { it.id to (it.subtype to it.rank) }, stored)
        assertEquals(
            253,
            count("select count(*) from wfm_order o join market m on m.id = o.market_id where m.rank = 10"),
        )
    }

    @Test
    fun `the same book again records no event, and is still a poll`() {
        val book = captured("serration")
        ingest.reconcileBook(SERRATION, book, T1)

        val again = ingest.reconcileBook(SERRATION, book, T2)

        assertEquals(0, (again as BookOutcome.Reconciled).events)
        assertEquals(440, count("select count(*) from order_event"))
        assertEquals(
            listOf(2),
            jdbc.queryForList("select distinct count(*) from market_quote group by market_id", Int::class.java),
        )
    }

    @Test
    fun `a book no newer than the last one records nothing at all`() {
        val book = captured("serration")
        ingest.reconcileBook(SERRATION, book, T2)
        val quotes = count("select count(*) from market_quote")

        assertIs<BookOutcome.Stale>(ingest.reconcileBook(SERRATION, book.drop(1), T1), "an older book")
        assertIs<BookOutcome.Stale>(ingest.reconcileBook(SERRATION, book.drop(1), T2), "the same book replayed")

        assertEquals(440, count("select count(*) from order_event"))
        assertEquals(quotes, count("select count(*) from market_quote"))
    }

    @Test
    fun `a price change, a vanished order and an emptied market, as the store records them`() {
        val book = captured("serration")
        val atragraph = book.filter { it.subtype == "atragraph" }
        val repriced = book.first { it.subtype == "regular" }
        ingest.reconcileBook(SERRATION, book, T1)

        val next = book.filter { it.subtype != "atragraph" }.map {
            if (it ==
                repriced
            ) {
                it.copy(platinum = it.platinum + 5)
            } else {
                it
            }
        }
        ingest.reconcileBook(SERRATION, next, T2)

        val changes = jdbc.queryForList(
            "select event, count(*) from order_event where observed_at = ? group by event order by event",
            Timestamp.from(T2),
        ).associate { it["event"] as String to (it["count"] as Long).toInt() }
        assertEquals(mapOf("price_changed" to 1, "vanished" to atragraph.size), changes)
        assertEquals(
            repriced.platinum,
            jdbc.queryForObject("select prev_platinum from order_event where event = 'price_changed'", Int::class.java),
        )
        // The atragraph markets are empty now, and still get a row for this poll.
        assertEquals(
            0,
            count(
                """
                select count(*) from market_quote q join market m on m.id = q.market_id
                where m.subtype = 'atragraph' and q.observed_at = '$T2' and (q.buy_count + q.sell_count) > 0
                """,
            ),
        )
        assertEquals(
            count("select count(*) from market where subtype = 'atragraph'"),
            count(
                "select count(*) from market_quote q join market m on m.id = q.market_id where m.subtype = 'atragraph' and q.observed_at = '$T2'",
            ),
        )
    }

    @Test
    fun `an empty book empties every market, and a failed one would have left them alone`() {
        ingest.reconcileBook(SERRATION, captured("serration"), T1)

        assertEquals(
            BookOutcome.Reconciled(events = 440, quotes = markets.marketsOf(SERRATION).size),
            ingest.reconcileBook(SERRATION, emptyList(), T2),
        )

        assertEquals(440, count("select count(*) from order_event where event = 'vanished'"))
        assertEquals(0, count("select count(*) from wfm_order where gone_at is null"))
        assertEquals(
            0,
            count("select coalesce(sum(buy_count + sell_count), 0) from market_quote where observed_at = '$T2'"),
        )
    }

    @Test
    fun `an order that comes back appears again, carrying its last known values`() {
        val book = captured("serration")
        val order = book.first()
        ingest.reconcileBook(SERRATION, book, T1)
        ingest.reconcileBook(SERRATION, book - order, T2)

        ingest.reconcileBook(SERRATION, book, T3)

        val events = jdbc.queryForList(
            "select event, prev_platinum from order_event where order_id = ? order by observed_at",
            order.id,
        ).map { it["event"] to it["prev_platinum"] }
        assertEquals(listOf("appeared" to null, "vanished" to order.platinum, "appeared" to order.platinum), events)
        assertEquals(0, count("select count(*) from wfm_order where gone_at is not null"))
    }

    @Test
    fun `lot sizes, sides and per-unit quotes reach the store`() {
        ingest.reconcileBook(AYATAN, captured("ayatan_anasa_sculpture"), T1)

        assertEquals(200, count("select count(*) from order_event where per_trade = 6"))
        assertEquals(154, count("select count(*) from order_event where type = 'buy'"))
        val quote = jdbc.queryForMap(
            """
            select q.best_buy, q.best_sell, q.best_buy_online, q.best_sell_online
            from market_quote q join market m on m.id = q.market_id
            where m.amber_stars = 2 and m.cyan_stars = 2
            """,
        ).mapValues { it.value.toString() }
        assertEquals(
            mapOf(
                "best_buy" to "7.0000",
                "best_sell" to "6.0000",
                "best_buy_online" to "7.0000",
                "best_sell_online" to "7.0000",
            ),
            quote,
        )
    }

    @Test
    fun `a book for an item the catalog lacks writes nothing`() {
        assertFailsWith<UnknownItemException> { ingest.reconcileBook("not_an_item", captured("khra"), T1) }

        assertEquals(0, count("select count(*) from order_book") + count("select count(*) from order_event"))
    }

    @Test
    fun `a socket order that arrived while a book was in flight survives that book`() {
        val book = captured("serration")
        val newcomer = book.first().copy(id = "arrived_by_socket")
        ingest.ingestPartial(listOf(newcomer.copy(itemId = SERRATION)), Source.WS, T2)

        ingest.reconcileBook(SERRATION, book, T1)
        assertEquals(0, count("select count(*) from order_event where event = 'vanished'"))

        // A book requested after the socket saw it, and without it, does vanish it.
        ingest.reconcileBook(SERRATION, book, T3)
        assertEquals(
            1,
            count("select count(*) from order_event where event = 'vanished' and order_id = 'arrived_by_socket'"),
        )
    }

    @Test
    fun `a book and a socket event meeting the same new order record one appearance`() {
        val order = captured("serration").first()
        val marketId = markets.resolve(MarketKey(SERRATION, order.subtype, order.rank))
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { socket ->
                // The socket's ingest has inserted the order but not committed yet.
                socket.autoCommit = false
                socket.prepareStatement(
                    """
                    insert into wfm_order (id, market_id, type, platinum, quantity, first_seen_at, changed_at)
                    values (?, ?, 'sell', 1, 1, ?, ?)
                    """,
                ).apply {
                    setString(1, order.id)
                    setLong(2, marketId)
                    setTimestamp(3, Timestamp.from(T1))
                    setTimestamp(4, Timestamp.from(T1))
                }.executeUpdate()

                val book = pool.submit<BookOutcome> { ingest.reconcileBook(SERRATION, listOf(order), T2) }
                Thread.sleep(500)
                assertFalse(book.isDone, "the book should be waiting on the uncommitted order")

                socket.commit()
                assertEquals(BookOutcome.Reconciled(events = 0, quotes = 1), book.get(30, SECONDS))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `replaying the captured recent orders adds them once, and skips items the catalog lacks`() {
        val recent = captured("recent")
        val cataloged = recent.mapNotNull { it.itemId }.distinct().take(100)
        cataloged.forEach { items.upsert(ItemRecord(id = it, slug = it)) }
        val expected = recent.count { it.itemId in cataloged }

        assertEquals(expected, ingest.ingestPartial(recent, Source.RECENT, T1))
        assertEquals(0, ingest.ingestPartial(recent, Source.RECENT, T2))

        assertEquals(expected, count("select count(*) from order_event where source = 'recent' and event = 'appeared'"))
    }

    @Test
    fun `a partial observation of a known order records nothing, even with new values`() {
        val book = captured("serration")
        ingest.reconcileBook(SERRATION, book, T1)
        ingest.reconcileBook(SERRATION, book.drop(1), T2)
        val changed = book.take(2).map {
            it.copy(
                itemId = SERRATION,
                platinum = it.platinum + 1,
                quantity =
                it.quantity + 1,
            )
        }
        val before = stateOf(changed.map { it.id })

        // One of these is live, the other vanished at T2; neither may change.
        assertEquals(0, ingest.ingestPartial(changed, Source.WS, T3))

        assertEquals(0, count("select count(*) from order_event where source = 'ws'"))
        assertEquals(before, stateOf(changed.map { it.id }))
    }

    private fun stateOf(ids: List<String>) = ids.map { id ->
        jdbc.queryForMap("select platinum, quantity, changed_at, gone_at from wfm_order where id = ?", id)
    }

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun captured(fixture: String): List<Order> = ClassPathResource("fixtures/v2-orders/$fixture.json")
        .inputStream.use { mapper.readValue(it, object : TypeReference<Envelope<List<Order>>>() {}) }
        .data!!

    private companion object {
        const val SERRATION = "54a74454e779892d5e515596"
        const val AYATAN = "588a789c3cf52c408a2f88dd"
        val T1: Instant = Instant.parse("2026-09-25T12:00:00Z")
        val T2: Instant = T1 + Duration.ofMinutes(5)
        val T3: Instant = T2 + Duration.ofMinutes(5)
    }
}
