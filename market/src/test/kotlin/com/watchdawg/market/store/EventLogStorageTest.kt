package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.Policies.Companion.COMPRESSION
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

    private val policies by lazy { Policies(jdbc) }

    @Test
    fun `an event older than the compression age is compressed when the policy runs`() {
        val old = oldestCompressible()
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
    fun `event and source take only the documented values`() {
        assertFailsWith<DataIntegrityViolationException> { insert(Instant.now(), orderId = "a", event = "sold") }
        assertFailsWith<DataIntegrityViolationException> { insert(Instant.now(), orderId = "b", source = "guess") }
    }

    /**
     * An instant whose whole chunk is past the compression age. Read from the policy and the chunk
     * interval rather than hardcoded, so this test follows `R__storage_policies.sql`.
     */
    private fun oldestCompressible(): Instant = jdbc.queryForObject(
        """
        select now() - (j.config ->> 'compress_after')::interval - d.time_interval - interval '1 hour'
        from timescaledb_information.jobs j
        join timescaledb_information.dimensions d on d.hypertable_name = j.hypertable_name
        where j.proc_name = ? and j.hypertable_name = ?
        """,
        Timestamp::class.java,
        COMPRESSION,
        EVENT_LOG,
    )!!.toInstant()

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
    ) {
        jdbc.update(
            """
            insert into order_event (observed_at, market_id, order_id, event, source, platinum, quantity)
            values (?, 1, ?, ?, ?, ?, 1)
            """,
            Timestamp.from(observedAt),
            orderId,
            event,
            source,
            platinum,
        )
    }

    private companion object {
        const val EVENT_LOG = "order_event"
    }
}
