package com.watchdawg.market.operator

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.contracts.ContractWatch
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.watch.InvalidWatchException
import com.watchdawg.market.watch.KHRA
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.khraWatch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OperatorConfigurationTest {
    @Autowired lateinit var config: OperatorConfiguration

    @Autowired lateinit var state: OperatorState

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var watches: Watches

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var ingest: com.watchdawg.market.ingest.OrderIngest

    @Autowired lateinit var contractSync: com.watchdawg.market.contracts.ContractSync

    private fun request() = ConfigurationView(0, OperatorDocument(watches = listOf(khraWatch())))

    @Test
    fun `saved manifest-wide coverage initializes the catalog even with no individual contract watches`() {
        config.save(ConfigurationView(0, OperatorDocument(allWeapons = true)))
        val catalog = org.mockito.Mockito.mock(com.watchdawg.market.sync.CollectionSyncScheduler::class.java)
        contractSync.initialize(catalog)
        org.mockito.Mockito.verify(catalog).tick()
    }

    @Test
    fun `committed live configuration drives actual socket admissions and the shared ceiling`() {
        items.upsert(KHRA)
        var saved = config.save(request())
        val now = java.time.Instant.now()
        fun observe(id: String, price: Int, seconds: Long) = ingest.ingestPartial(
            listOf(com.watchdawg.market.watch.khraOrder(id, price)),
            com.watchdawg.market.ingest.Source.WS,
            now.plusSeconds(seconds),
        )
        observe("first", 10, 0)
        assertEquals(1, jdbc.queryForObject("select count(*) from signal where state = 'pending'", Int::class.java))
        saved =
            config.save(saved.copy(document = saved.document.copy(watches = listOf(khraWatch().copy(enabled = false)))))
        observe("paused", 9, 1)
        assertEquals(1, jdbc.queryForObject("select count(*) from signal", Int::class.java))
        config.save(saved.copy(document = saved.document.copy(watches = listOf(khraWatch()), dailyCeiling = 0)))
        observe("suppressed", 8, 2)
        assertEquals(1, jdbc.queryForObject("select count(*) from signal where state = 'suppressed'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("select count(*) from signal where state = 'pending'", Int::class.java))
    }

    @Test
    fun `saved revisions survive restart and live selection honors pause and deletion`() {
        items.upsert(KHRA)
        val saved = config.save(request())
        assertEquals(listOf("khra"), watches.itemSlugs)
        assertEquals(saved, OperatorState(jdbc, mapper).snapshot!!.view)
        val paused = config.save(
            saved.copy(
                document = saved.document.copy(
                    watches = listOf(khraWatch().copy(enabled = false)),
                    dailyCeiling = 0,
                ),
            ),
        )
        assertTrue(watches.all.isEmpty())
        assertEquals(0, state.ceiling(50))
        config.save(paused.copy(document = paused.document.copy(watches = emptyList())))
        assertEquals(3, jdbc.queryForObject("select count(*) from operator_configuration", Int::class.java))
    }

    @Test
    fun `invalid dimensions names and contracts leave both persisted and active state unchanged`() {
        items.upsert(KHRA)
        assertFailsWith<InvalidWatchException> {
            config.save(request().copy(document = OperatorDocument(watches = listOf(khraWatch().copy(rank = null)))))
        }
        assertFailsWith<IllegalArgumentException> {
            config.save(
                request().copy(
                    document = request().document.copy(
                        contracts = listOf(ContractWatch("cheap-khra", "riven", "soma")),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            config.save(
                request().copy(
                    document = request().document.copy(
                        contracts = listOf(ContractWatch("soma", "riven", "soma")),
                    ),
                ),
            )
        }
        assertEquals(0L, config.current().revision)
        assertTrue(watches.all.isEmpty())
        assertEquals(0, jdbc.queryForObject("select count(*) from operator_configuration", Int::class.java))
    }

    @Test
    fun `two tabs editing the same revision cannot overwrite each other`() {
        items.upsert(KHRA)
        Executors.newFixedThreadPool(2).use { pool ->
            val results = pool.invokeAll(
                List(2) {
                    Callable { runCatching { config.save(request()) } }
                },
            ).map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            val error = results.single { it.isFailure }.exceptionOrNull() as ResponseStatusException
            assertEquals(409, error.statusCode.value())
        }
        assertEquals(1L, config.current().revision)
        assertEquals(1, jdbc.queryForObject("select count(*) from operator_configuration", Int::class.java))
    }

    @Test
    fun `contract changes validate manifests and publish filters and coverage settings`() {
        jdbc.update("insert into contract_weapon(kind, slug, name) values ('riven', 'soma', 'Soma')")
        jdbc.update("insert into contract_attribute(slug) values ('critical_chance')")
        val watch = ContractWatch(
            "soma",
            "riven",
            "soma",
            maxPrice = 100,
            positive = mapOf("critical_chance" to 50.toBigDecimal()),
        )
        val saved = config.save(ConfigurationView(0, OperatorDocument(contracts = listOf(watch), allWeapons = true)))
        assertEquals(listOf(watch), state.activeContracts(emptyList()))
        assertTrue(state.allWeapons(false))
        config.save(saved.copy(document = saved.document.copy(contracts = listOf(watch.copy(enabled = false)))))
        assertTrue(state.activeContracts(emptyList()).isEmpty())
    }
}
