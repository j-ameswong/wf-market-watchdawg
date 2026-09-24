package com.watchdawg.market.sync

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.WfmClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.MediaType.TEXT_PLAIN
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R3.1: the detail sweep fills what `/v2/items` leaves out, from `/v2/item/{slug}`, at a bounded
 * pace and without being undone by the next catalog refresh.
 *
 * The slugs and values here are synthetic. They test the sweep, not the upstream shape, which a
 * capture of `/v2/item/{slug}` has yet to confirm (plan Open Question 5).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ItemDetailSyncTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    private val builder by lazy { wfmRestClient.mutate() }
    private val server by lazy { MockRestServiceServer.bindTo(builder).build() }

    private fun sweeper(batchSize: Int = 10) = ItemDetailSync(WfmClient(builder.build()), items, batchSize)

    @Test
    fun `a sweep writes the detail fields, null where the item page leaves one out`() {
        catalog("item_a", "item_b")
        expectDetail("item_a", """"tradable":true,"rarity":"rare","maxCharges":3""")
        expectDetail("item_b", """"tradable":false""")

        sweeper().sweep()

        server.verify()
        val a = items.findBySlug("item_a")!!
        assertEquals(true, a.tradable)
        assertEquals("rare", a.rarity)
        assertEquals(3, a.maxCharges)
        assertNotNull(a.detailSyncedAt)
        val b = items.findBySlug("item_b")!!
        assertEquals(false, b.tradable)
        assertNull(b.rarity)
        assertNull(b.maxCharges)
    }

    @Test
    fun `only missing or stale details are fetched, never-fetched first`() {
        // Named so that alphabetical order is the opposite of the order the sweep must use.
        catalog("a_fresh", "b_stale", "c_never")
        jdbc.update(
            "update item set synced_at = now() - interval '1 hour', detail_synced_at = now() where slug = 'a_fresh'",
        )
        jdbc.update("update item set detail_synced_at = now() - interval '1 hour' where slug = 'b_stale'")
        // Ordered expectations: fetching "a_fresh", or "b_stale" before "c_never", fails the test.
        expectDetail("c_never", """"tradable":true""")
        expectDetail("b_stale", """"tradable":true""")

        sweeper().sweep()

        server.verify()
    }

    @Test
    fun `one sweep fetches at most one batch, and the next picks up the rest`() {
        catalog("item_a", "item_b", "item_c")
        expectDetail("item_a", """"tradable":true""")
        expectDetail("item_b", """"tradable":true""")

        sweeper(batchSize = 2).sweep()

        server.verify()
        assertNull(items.findBySlug("item_c")!!.detailSyncedAt)
        assertEquals(listOf("item_c"), items.needingDetail(10).map { it.slug })
    }

    @Test
    fun `a catalog refresh keeps the details, and makes them due for a fresh fetch`() {
        catalog("item_a")
        val id = items.findBySlug("item_a")!!.id
        items.recordDetail(id, tradable = true, rarity = "rare", maxCharges = 3)
        assertEquals(emptyList(), items.needingDetail(10))

        // What ItemSync writes: the list has none of the three detail fields.
        items.upsert(ItemRecord(id = id, slug = "item_a", name = "Item A"))

        val refreshed = items.findBySlug("item_a")!!
        assertEquals(true, refreshed.tradable)
        assertEquals("rare", refreshed.rarity)
        assertEquals(3, refreshed.maxCharges)
        assertEquals(listOf("item_a"), items.needingDetail(10).map { it.slug })
    }

    @Test
    fun `an item the API no longer has keeps its old details, and the sweep moves on`() {
        catalog("never", "gone", "later")
        items.recordDetail("id_gone", tradable = true, rarity = "rare", maxCharges = null)
        // Both stale; "gone" the longer, so it is fetched before "later".
        jdbc.update("update item set detail_synced_at = now() - interval '2 hours' where slug = 'gone'")
        jdbc.update("update item set detail_synced_at = now() - interval '1 hour' where slug = 'later'")
        expectDetail("never", """"tradable":true""")
        server.expect(requestTo("$ITEM/gone"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not Found").contentType(TEXT_PLAIN))
        expectDetail("later", """"tradable":false""")

        sweeper().sweep()

        server.verify()
        val gone = items.findBySlug("gone")!!
        assertEquals(true, gone.tradable)
        assertEquals("rare", gone.rarity)
        assertEquals(false, items.findBySlug("later")!!.tradable)
        assertEquals(emptyList(), items.needingDetail(10), "all three were checked, so none is due")
    }

    @Test
    fun `a failing call ends the sweep and leaves the rest due`() {
        catalog("item_a", "item_b")
        server.expect(requestTo("$ITEM/item_a")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        sweeper().sweep()

        server.verify()
        assertEquals(listOf("item_a", "item_b"), items.needingDetail(10).map { it.slug })
    }

    @Test
    fun `a throttle ends the sweep without spending more budget`() {
        catalog("item_a", "item_b")
        // The transport retries a 429 once on its own; the second refusal reaches the sweep.
        server.expect(requestTo("$ITEM/item_a")).andRespond(withTooManyRequests())
        server.expect(requestTo("$ITEM/item_a")).andRespond(withTooManyRequests())

        sweeper().sweep()

        server.verify()
        assertEquals(listOf("item_a", "item_b"), items.needingDetail(10).map { it.slug })
    }

    private fun catalog(vararg slugs: String) = slugs.forEach { items.upsert(ItemRecord(id = "id_$it", slug = it)) }

    private fun expectDetail(slug: String, fields: String) {
        server.expect(requestTo("$ITEM/$slug")).andRespond(
            withSuccess(
                """{"apiVersion":"0.25.0","error":null,"data":{"id":"id_$slug","slug":"$slug",$fields}}""",
                APPLICATION_JSON,
            ),
        )
    }

    private companion object {
        const val ITEM = "https://api.warframe.market/v2/item"
    }
}
