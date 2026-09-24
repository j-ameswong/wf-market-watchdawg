package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.wfm.WfmContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** R3.3, R3.4: a market tuple maps to one id, however often and however concurrently it is resolved. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Timeout(value = 60, unit = SECONDS)
class MarketResolverTest {

    @Autowired lateinit var resolver: MarketResolver

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var context: WfmContext

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var tx: TransactionTemplate

    @BeforeEach
    fun catalog() {
        listOf("serration", "frost_prime_set").forEach { items.upsert(ItemRecord(id = it, slug = it)) }
    }

    @Test
    fun `resolving one tuple twice returns one id`() {
        val key = MarketKey("serration", rank = 10)

        assertEquals(resolver.resolve(key), resolver.resolve(key))
        assertEquals(1, markets())
    }

    @Test
    fun `a tuple with no dimensions at all is still one market`() {
        val key = MarketKey("frost_prime_set")

        assertEquals(resolver.resolve(key), resolver.resolve(key))
        assertEquals(1, markets())
        // The resolver's lookup copes with nulls on its own, so check the table does too: a writer
        // that bypasses the resolver must not be able to add a second all-null market (R3.4).
        // Postgres treats nulls as distinct unless the constraint says otherwise.
        assertFailsWith<DuplicateKeyException> {
            jdbc.update("insert into market (item_id, platform) values ('frost_prime_set', ?)", context.platform)
        }
    }

    @Test
    fun `rank 0 and rank 10 of serration are two markets`() {
        assertNotEquals(
            resolver.resolve(MarketKey("serration", rank = 0)),
            resolver.resolve(MarketKey("serration", rank = 10)),
        )
        assertEquals(2, markets())
    }

    @Test
    fun `every market is keyed by the observer's platform`() {
        resolver.resolve(MarketKey("serration", rank = 0))

        assertEquals(listOf(context.platform), jdbc.queryForList("select platform from market", String::class.java))
    }

    @Test
    fun `concurrent resolutions of a new tuple create one market`() {
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Long> {
                    start.await()
                    resolver.resolve(MarketKey("serration", rank = 3))
                }
            }
            start.countDown()

            assertEquals(1, results.map { it.get(30, SECONDS) }.toSet().size)
            assertEquals(1, markets())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a resolution waits for an uncommitted insert of its tuple and returns that row`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { other ->
                other.autoCommit = false
                val theirs = other.prepareStatement(
                    "insert into market (item_id, platform, rank) values ('serration', ?, 5) returning id",
                ).apply { setString(1, context.platform) }.executeQuery().run {
                    next()
                    getLong(1)
                }

                val ours = pool.submit<Long> { resolver.resolve(MarketKey("serration", rank = 5)) }
                Thread.sleep(500)
                assertFalse(ours.isDone, "the resolution should be waiting on the uncommitted row")

                other.commit()
                assertEquals(theirs, ours.get(30, SECONDS))
            }
            assertEquals(1, markets())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a tuple for an item the catalog lacks is refused, and creates nothing`() {
        val refused = assertFailsWith<UnknownItemException> { resolver.resolve(MarketKey("not_an_item")) }

        assertEquals("not_an_item", refused.itemId)
        assertEquals(0, markets())
    }

    @Test
    fun `a market outlives the transaction that resolved it rolling back`() {
        tx.executeWithoutResult { status ->
            resolver.resolve(MarketKey("serration", rank = 1))
            status.setRollbackOnly()
        }

        assertEquals(1, markets())
    }

    private fun markets(): Int = jdbc.queryForObject("select count(*) from market", Int::class.java)!!
}
