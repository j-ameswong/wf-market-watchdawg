package com.watchdawg.market.watch

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Dedup, cooldown and the daily ceiling (R9a.5, R9a.8). The dispatcher never runs here, so every
 * admitted signal stays pending, which is the case the cooldown must hold for.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SuppressionTest {

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var store: OrderStore

    @Autowired lateinit var markets: MarketResolver

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var jdbc: JdbcTemplate

    private val registry = SimpleMeterRegistry()

    private fun ingest(vararg watches: WatchEntry = arrayOf(khraWatch()), dailyCeiling: Int = 50): OrderIngest {
        val resolved = items.watchesOf(*watches)
        return ingestWith(
            resolved,
            store,
            markets,
            signals,
            transactions,
            dailyCeiling,
            AlertMetrics(registry, resolved),
        )
    }

    @Test
    fun `the same book again admits nothing new`() {
        val ingest = ingest()
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T0)

        ingest.reconcileBook(KHRA.id, listOf(khraOrder("cheap", platinum = 10)), T0.plusSeconds(7200))

        assertEquals(listOf("cheap" to "pending"), rows())
    }

    @Test
    fun `two equally cheap listings in one poll admit exactly one (R9a5)`() {
        ingest().reconcileBook(KHRA.id, listOf(khraOrder("b", platinum = 10), khraOrder("a", platinum = 10)), T0)

        assertEquals(listOf("a" to "pending"), rows())
    }

    @Test
    fun `within the cooldown only a cheaper listing is admitted`() {
        val ingest = ingest()
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("first", platinum = 10)), T0)

        val same = khraOrder("same", platinum = 10)
        val dearer = khraOrder("dearer", platinum = 11)
        ingest.reconcileBook(KHRA.id, listOf(same, dearer), T0.plusSeconds(120))
        assertEquals(listOf("first"), rows().map { it.first })

        ingest.reconcileBook(KHRA.id, listOf(same, dearer, khraOrder("cheaper", platinum = 9)), T0.plusSeconds(240))
        assertEquals(listOf("first", "cheaper"), rows().map { it.first })
    }

    @Test
    fun `a sent signal holds the cooldown as a pending one does`() {
        val ingest = ingest()
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("first", platinum = 10)), T0)
        jdbc.update("update signal set state = 'sent'")

        ingest.reconcileBook(KHRA.id, listOf(khraOrder("second", platinum = 10)), T0.plusSeconds(120))

        assertEquals(listOf("first"), rows().map { it.first })
    }

    @Test
    fun `a listing the cooldown held back is admitted once it ends`() {
        val ingest = ingest()
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("first", platinum = 10)), T0)
        val held = khraOrder("held", platinum = 10)
        ingest.reconcileBook(KHRA.id, listOf(held), T0.plusSeconds(120))

        ingest.reconcileBook(KHRA.id, listOf(held), T0.plusSeconds(3600))

        assertEquals(listOf("first", "held"), rows().map { it.first })
    }

    @Test
    fun `past the daily ceiling a candidate is suppressed once, counted, and never pending (R9a8)`() {
        val ingest = ingest(khraWatch("rank-3"), khraWatch("rank-2", rank = "2"), dailyCeiling = 1)
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("r3", platinum = 10)), T0)

        val over = khraOrder("r2", platinum = 10, rank = 2)
        ingest.reconcileBook(KHRA.id, listOf(over), T0.plusSeconds(120))
        ingest.reconcileBook(KHRA.id, listOf(over), T0.plusSeconds(240))

        assertEquals(listOf("r3" to "pending", "r2" to "suppressed"), rows())
        assertEquals(1.0, count("rank-2", "suppressed"))
        assertEquals(1.0, count("rank-3", "pending"))
    }

    @Test
    fun `the ceiling counts one UTC day`() {
        val ingest = ingest(khraWatch("rank-3"), khraWatch("rank-2", rank = "2"), dailyCeiling = 1)
        ingest.reconcileBook(KHRA.id, listOf(khraOrder("today", platinum = 10)), T0)

        ingest.reconcileBook(KHRA.id, listOf(khraOrder("tomorrow", platinum = 10, rank = 2)), TOMORROW)

        assertEquals(listOf("today" to "pending", "tomorrow" to "pending"), rows())
    }

    @Test
    fun `signal counts are registered for every watch and state at startup (R12_3)`() {
        val metrics = SimpleMeterRegistry()
        AlertMetrics(metrics, items.watchesOf(khraWatch("a"), khraWatch("b")))

        listOf("a", "b").forEach { watch ->
            listOf("pending", "sent", "failed", "suppressed").forEach { state ->
                assertNotNull(metrics.find("watchdawg.signals").tag("watch", watch).tag("state", state).counter())
            }
        }
        listOf("sent", "retried", "failed").forEach {
            assertNotNull(metrics.find("watchdawg.deliveries").tag("outcome", it).counter())
        }
    }

    private fun count(watch: String, state: String) =
        registry.find("watchdawg.signals").tag("watch", watch).tag("state", state).counter()?.count()

    private fun rows() = jdbc.query("select order_id, state from signal order by id") { rs, _ ->
        rs.getString(1) to rs.getString(2)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-09-26T12:00:00Z")
        val TOMORROW: Instant = Instant.parse("2026-09-27T00:00:00Z")
    }
}
