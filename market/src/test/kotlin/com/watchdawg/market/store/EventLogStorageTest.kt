package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.Policies.Companion.COMPRESSION
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** R2.3: the order event log is compressed beyond an age and never dropped (ADR-0007). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class EventLogStorageTest {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var resolver: MarketResolver

    @Autowired lateinit var items: ItemRepository

    private val policies by lazy { Policies(jdbc) }

    private var market = 0L

    @BeforeEach
    fun market() {
        market = resolver.marketFor(items)
    }

    @Test
    fun `an event older than the compression age is compressed when the policy runs`() {
        val old = policies.beyondAge(COMPRESSION, EVENT_LOG, "compress_after")
        val recent = Instant.now()
        insert(old, orderId = "old", platinum = 40)
        insert(recent, orderId = "recent", platinum = 45)

        policies.run(COMPRESSION, EVENT_LOG)

        assertEquals(true, chunkCompressed(old))
        assertEquals(false, chunkCompressed(recent))
        // Compressed rows still read back like any other.
        assertEquals(
            40,
            jdbc.queryForObject("select platinum from order_event where order_id = 'old'", Int::class.java),
        )
    }

    @Test
    fun `the event log has a compression policy and nothing that drops rows`() {
        assertEquals(listOf(COMPRESSION), policies.on(EVENT_LOG))
    }

    @Test
    fun `an event for a market that does not exist is refused, even in a compressed chunk`() {
        val old = policies.beyondAge(COMPRESSION, EVENT_LOG, "compress_after")
        insert(old, orderId = "old")
        policies.run(COMPRESSION, EVENT_LOG)
        assertEquals(true, chunkCompressed(old))

        assertFailsWith<DataIntegrityViolationException> { insert(old, orderId = "orphan", market = NO_SUCH_MARKET) }
        assertFailsWith<DataIntegrityViolationException> {
            insert(Instant.now(), orderId = "orphan", market = NO_SUCH_MARKET)
        }
    }

    @Test
    fun `event and source take only the documented values`() {
        assertFailsWith<DataIntegrityViolationException> { insert(Instant.now(), orderId = "a", event = "sold") }
        assertFailsWith<DataIntegrityViolationException> { insert(Instant.now(), orderId = "b", source = "guess") }
    }

    private fun chunkCompressed(at: Instant): Boolean = jdbc.queryForObject(
        """
        select is_compressed from timescaledb_information.chunks
        where hypertable_name = ? and ? >= range_start and ? < range_end
        """,
        Boolean::class.java,
        EVENT_LOG,
        Timestamp.from(at),
        Timestamp.from(at),
    )!!

    private fun insert(
        observedAt: Instant,
        orderId: String,
        platinum: Int = 50,
        event: String = "appeared",
        source: String = "book",
        market: Long = this.market,
    ) {
        jdbc.update(
            """
            insert into order_event (observed_at, market_id, order_id, event, source, platinum, quantity)
            values (?, ?, ?, ?, ?, ?, 1)
            """,
            Timestamp.from(observedAt),
            market,
            orderId,
            event,
            source,
            platinum,
        )
    }

    private companion object {
        const val EVENT_LOG = "order_event"
        const val NO_SUCH_MARKET = 999_999L
    }
}
