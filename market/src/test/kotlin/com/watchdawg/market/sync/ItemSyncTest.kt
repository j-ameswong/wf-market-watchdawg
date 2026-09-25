package com.watchdawg.market.sync

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.wfm.WfmClient
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
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R3.1, R3.2: a refresh fills the catalog columns later capabilities read, and stamps when it did.
 *
 * `fixtures/v2-items.json` is eight entries copied unchanged from a live capture of `GET /v2/items`
 * (2026-09-24). They were chosen to cover each dimension: relics with refinements, a mod with
 * variants, ayatan stars, a parazon mod, and an explicit `vaulted: false` beside an absent one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ItemSyncTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a refresh fills every catalog column the payload carries`() {
        refresh()

        val serration = items.findBySlug("serration")!!
        assertEquals("Serration", serration.name)
        assertEquals("items/images/en/serration.711b19665b2acbac445c68c9e8f8b550.png", serration.icon)
        assertEquals(10, serration.maxRank)
        assertEquals(listOf("regular", "atragraph"), serration.subtypes)

        val relic = items.findBySlug("axi_a1_relic")!!
        assertEquals(listOf("intact", "exceptional", "flawless", "radiant"), relic.subtypes)
        assertTrue(relic.vaulted)
        assertEquals(true, relic.bulkTradable)
        assertFalse(items.findBySlug("axi_a2_relic")!!.vaulted)

        val anasa = items.findBySlug("ayatan_anasa_sculpture")!!
        assertEquals(2, anasa.maxAmberStars)
        assertEquals(2, anasa.maxCyanStars)

        assertEquals(3, items.findBySlug("khra")!!.maxRank)
        assertEquals(5, items.findBySlug("arcane_energize")!!.maxRank)
        assertEquals(175, items.findBySlug("frost_prime_set")!!.ducats)
    }

    @Test
    fun `a field the payload leaves out is stored as null, never as zero or false`() {
        refresh()

        val frost = items.findBySlug("frost_prime_set")!!
        assertNull(frost.maxRank, "rank 0 is a real market; an unranked item must not look like one (R7.8)")
        assertNull(frost.maxAmberStars)
        assertNull(frost.maxCyanStars)
        assertNull(frost.bulkTradable)
        assertEquals(emptyList(), frost.subtypes)
        assertFalse(frost.vaulted, "vaulted keeps its false default when absent")

        // Cyan stars without amber: the absent one stays null, not zero.
        val ayr = items.findBySlug("ayatan_ayr_sculpture")!!
        assertEquals(3, ayr.maxCyanStars)
        assertNull(ayr.maxAmberStars)

        // /v2/items never carries these three, so every row leaves them null.
        assertEquals(
            listOf(0, 0, 0),
            jdbc.queryForObject("select array[count(max_charges), count(tradable), count(rarity)] from item") { rs, _ ->
                (rs.getArray(1).array as Array<*>).map { (it as Number).toInt() }
            },
        )
    }

    @Test
    fun `every row one refresh writes carries that refresh's time`() {
        val refreshedAt = refresh()

        val stamps = jdbc.queryForList("select distinct synced_at from item", Timestamp::class.java)

        assertEquals(listOf(refreshedAt), stamps.map { it!!.toInstant() })
    }

    /** Runs one refresh against the fixture, in a transaction as the scheduler does. Returns its time. */
    private fun refresh(): Instant {
        val builder = wfmRestClient.mutate()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.warframe.market/v2/items"))
            .andRespond(withSuccess(ClassPathResource("fixtures/v2-items.json"), APPLICATION_JSON))
        val sync = ItemSync(WfmClient(builder.build()), items)

        val refreshedAt = tx.execute {
            sync.refresh()
            jdbc.queryForObject("select now()", Timestamp::class.java)!!.toInstant()
        }!!
        server.verify()
        return refreshedAt
    }
}
