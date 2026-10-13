package com.watchdawg.market.contracts

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.watch.AlertMetrics
import com.watchdawg.market.watch.AlertProperties
import com.watchdawg.market.watch.Alerts
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.LegacyEnvelope
import com.watchdawg.market.wfm.WfmClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ContractIngestTest {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var mapper: JsonMapper

    @Autowired lateinit var transactions: PlatformTransactionManager

    @Autowired lateinit var signals: SignalStore

    @Autowired lateinit var wfmRestClient: RestClient

    private fun fixture(kind: String): List<Auction> =
        mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
            .readValue(
                javaClass.getResourceAsStream("/fixtures/contracts/$kind.json"),
                object : TypeReference<LegacyEnvelope<AuctionSearch>>() {},
            ).payload!!.auctions

    private fun ingest(target: ContractWatch? = null): ContractIngest {
        val watches = Watches(emptyList())
        return ContractIngest(
            jdbc,
            mapper,
            ContractProperties(watches = listOfNotNull(target)),
            Alerts(watches, signals, AlertProperties(50), AlertMetrics(SimpleMeterRegistry(), watches)),
            transactions,
        )
    }

    private fun manifest(auction: Auction) {
        jdbc.update(
            "insert into contract_weapon(kind, slug, name) values (?, ?, ?) on conflict do nothing",
            auction.item.type,
            auction.item.weaponUrlName,
            auction.item.weaponUrlName,
        )
    }

    @Test
    fun `all three captured auction variants replay and riven decimals remain queryable`() {
        val ingest = ingest()
        val all = CONTRACT_KINDS.flatMap(::fixture)
        all.forEach(::manifest)
        ingest.ingest(all, AT)
        ingest.ingest(all, AT.plusSeconds(1))
        assertEquals(all.size, count("auction"))
        assertEquals(all.size, count("auction_event"))
        val attr = all.first { it.item.attributes.isNotEmpty() }.item.attributes.first()
        assertTrue(
            jdbc.queryForObject(
                "select count(*) from auction_riven_attribute where value = ?",
                Int::class.java,
                attr.value,
            )!! >
                0,
        )
    }

    @Test
    fun `matching buyout signals transactionally and lifecycle evidence stays explicit`() {
        val auction = fixture("riven").first { it.buyoutPrice != null && it.visible && !it.closed }
        manifest(auction)
        val target = ContractWatch("riven-watch", "riven", auction.item.weaponUrlName, maxPrice = auction.buyoutPrice)
        val ingest = ingest(target)
        TransactionTemplate(transactions).executeWithoutResult {
            ingest.ingest(listOf(auction), AT)
            assertEquals(1, count("signal"))
            it.setRollbackOnly()
        }
        assertEquals(0, count("signal"))
        assertEquals(0, count("auction"))
        ingest.ingest(listOf(auction), AT)
        ingest.ingest(listOf(auction.copy(topBid = 1)), AT.plusSeconds(1))
        ingest.ingest(listOf(auction.copy(closed = true)), AT.plusSeconds(2))
        assertEquals(
            listOf("appeared", "changed", "closed"),
            jdbc.query("select event from auction_event order by observed_at") {
                    rs,
                    _,
                ->
                rs.getString(1)
            },
        )
        assertEquals(1, count("signal"))
        val outgoing = signals.due(AT, 5).single()
        assertEquals("https://warframe.market/auction/${auction.id}", outgoing.click)
        assertTrue(outgoing.title!!.contains("buyout"))
    }

    @Test
    fun `user attributes and damage preferences constrain matching and missing has distinct evidence`() {
        val riven = fixture("riven").first { it.buyoutPrice != null && it.visible && !it.closed }
        val target =
            ContractWatch(
                "r",
                "riven",
                riven.item.weaponUrlName,
                maxPrice = Int.MAX_VALUE,
                positive = mapOf("nonexistent" to BigDecimal.ONE),
            )
        assertFalse(target.matches(riven))
        val lich = fixture("lich").first { it.buyoutPrice != null && it.visible && !it.closed }
        assertTrue(
            ContractWatch(
                "l",
                "lich",
                lich.item.weaponUrlName,
                Int.MAX_VALUE,
                element = lich.item.element,
                minDamage = 25,
            ).matches(lich),
        )
        manifest(lich)
        val ingest = ingest()
        ingest.ingest(listOf(lich), AT)
        ingest.ingest(listOf(lich), AT.plusSeconds(1), missing = true)
        ingest.ingest(listOf(lich), AT.plusSeconds(2), missing = true)
        ingest.ingest(listOf(lich), AT.plusSeconds(3))
        assertEquals(
            listOf("appeared", "disappeared", "appeared"),
            jdbc.query("select event from auction_event order by observed_at") {
                    rs,
                    _,
                ->
                rs.getString(1)
            },
        )
    }

    @Test
    fun `captured inversion metadata enables recoil alerts without changing raw evidence`() {
        jdbc.update("insert into contract_attribute(slug, positive_is_negative) values ('recoil', false)")
        val builder = wfmRestClient.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.warframe.market/v2/riven/attributes"))
            .andRespond(withSuccess(ClassPathResource("fixtures/contracts/riven-attributes.json"), APPLICATION_JSON))
        server.expect(requestTo("https://api.warframe.market/v2/riven/weapons"))
            .andRespond(withSuccess(ClassPathResource("fixtures/contracts/riven-weapons.json"), APPLICATION_JSON))
        ContractManifest("riven", ContractProperties(), WfmClient(builder.build()), jdbc).refresh()
        server.verify()
        assertTrue(
            jdbc.queryForObject(
                "select positive_is_negative from contract_attribute where slug = 'recoil'",
                Boolean::class.java,
            )!!,
        )
        assertFalse(
            jdbc.queryForObject(
                "select positive_is_negative from contract_attribute where slug = 'critical_chance'",
                Boolean::class.java,
            )!!,
        )

        val auctions = fixture("riven")
        val auction = auctions.first { a ->
            a.visible && !a.closed && a.buyoutPrice != null && a.item.attributes.any {
                it.urlName == "recoil" && it.positive && it.value == BigDecimal("-94.6")
            }
        }
        val watch = ContractWatch(
            "recoil",
            "riven",
            auction.item.weaponUrlName,
            Int.MAX_VALUE,
            positive = mapOf("recoil" to BigDecimal("90")),
        )
        assertTrue(watch.matches(auction, setOf("recoil")))
        assertFalse(watch.copy(positive = mapOf("recoil" to BigDecimal("95"))).matches(auction, setOf("recoil")))
        val harmful = auctions.first { a -> a.item.attributes.any { it.urlName == "recoil" && !it.positive } }
        assertFalse(watch.matches(harmful, setOf("recoil")))
        val ordinary = auction.item.attributes.first { it.positive && it.urlName != "recoil" }
        assertTrue(watch.copy(positive = mapOf(ordinary.urlName to ordinary.value)).matches(auction, setOf("recoil")))

        val ingest = ingest(watch)
        ingest.ingest(listOf(auction), AT)
        ingest.ingest(listOf(auction), AT.plusSeconds(1))
        assertEquals(1, count("signal"))
        assertEquals(1, count("auction_event"))
        assertEquals(
            BigDecimal("-94.6"),
            jdbc.queryForObject(
                "select value from auction_riven_attribute where auction_id = ? and name = 'recoil'",
                BigDecimal::class.java,
                auction.id,
            ),
        )
    }

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

    private companion object {
        val AT: Instant = Instant.parse("2026-10-13T00:00:00Z")
    }
}
