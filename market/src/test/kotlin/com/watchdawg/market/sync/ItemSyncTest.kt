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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R3.1, R3.2: a refresh fills the catalog columns later capabilities read, and stamps when it did.
 *
 * `fixtures/v2-items.json` is shaped from the `Item` model in `docs/v2/data-models.mdx`, not
 * captured: the environment C3 was written in could not reach the API (plan Decision 1). Its ids
 * are placeholders, and its dimension values follow the observations in `docs/v1-statistics.md`.
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
        assertEquals("Serration", serration.name, "the name comes from i18n.en, not another language")
        assertEquals("items/images/en/serration.png", serration.icon)
        assertEquals(10, serration.maxRank)
        assertEquals("rare", serration.rarity)
        assertEquals(true, serration.tradable)

        val relic = items.findBySlug("axi_a1_relic")!!
        assertEquals(listOf("intact", "exceptional", "flawless", "radiant"), relic.subtypes)
        assertTrue(relic.vaulted)

        val ayatan = items.findBySlug("ayatan_anasa_sculpture")!!
        assertEquals(2, ayatan.maxAmberStars)
        assertEquals(2, ayatan.maxCyanStars)

        assertEquals(3, items.findBySlug("khra")!!.maxCharges)
        assertEquals(true, items.findBySlug("arcane_energize")!!.bulkTradable)
    }

    @Test
    fun `a field the payload leaves out is stored as null, never as zero or false`() {
        refresh()

        val frost = items.findBySlug("frost_prime_set")!!
        assertNull(frost.maxRank, "rank 0 is a real market; an unranked item must not look like one (R7.8)")
        assertNull(frost.maxCharges)
        assertNull(frost.maxAmberStars)
        assertNull(frost.maxCyanStars)
        assertNull(frost.bulkTradable)
        assertNull(frost.rarity)
        assertEquals(emptyList(), frost.subtypes)
        assertNull(items.findBySlug("ayatan_anasa_sculpture")!!.tradable)
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
