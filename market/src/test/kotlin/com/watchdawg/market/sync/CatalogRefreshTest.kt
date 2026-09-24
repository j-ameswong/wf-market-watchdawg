package com.watchdawg.market.sync

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.CollectionVersionRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R3.5: the catalog refresh stays gated on the upstream version hash, and a refresh commits whole
 * or not at all.
 *
 * This drives the real scheduler and the real `ItemSync` through one mocked transport. Every
 * expected request is declared up front and in order, so a tick that fetched `/items` when it
 * should not have fails the test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogRefreshTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var versions: CollectionVersionRepository

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    private val builder by lazy { wfmRestClient.mutate() }
    private val server by lazy { MockRestServiceServer.bindTo(builder).build() }
    private val scheduler by lazy {
        val wfm = WfmClient(builder.build())
        CollectionSyncScheduler(wfm, versions, listOf(ItemSync(wfm, items)), tx)
    }

    @Test
    fun `an unchanged hash fetches the versions and nothing else`() {
        expectVersions("h1")
        expectItems(FIXTURE)
        expectVersions("h1")

        scheduler.tick()
        val firstSync = syncedAt("serration")
        scheduler.tick()

        server.verify()
        assertEquals(6, items.count())
        assertEquals("h1", storedHash())
        assertEquals(firstSync, syncedAt("serration"), "an unchanged hash must not rewrite the catalog")
    }

    @Test
    fun `a changed hash refetches and restamps the catalog`() {
        expectVersions("h1")
        expectItems(FIXTURE)
        expectVersions("h2")
        expectItems(FIXTURE)

        scheduler.tick()
        val firstSync = syncedAt("serration")
        scheduler.tick()

        server.verify()
        assertEquals("h2", storedHash())
        assertTrue(syncedAt("serration") > firstSync, "the second refresh did not restamp the row")
    }

    @Test
    fun `a refresh that fails part-way leaves nothing behind and retries next tick`() {
        // The second item reuses the first's slug, so its upsert fails after the first succeeded.
        expectVersions("h1")
        expectItems(
            """
            {"apiVersion":"0.25.0","error":null,"data":[
              {"id":"000000000000000000000001","slug":"serration"},
              {"id":"000000000000000000000002","slug":"serration"}]}
            """,
        )
        expectVersions("h1")
        expectItems(FIXTURE)

        scheduler.tick()
        assertEquals(0, items.count(), "the first item's upsert should have rolled back with the second")
        assertNull(storedHash(), "a failed refresh must leave the hash stale")

        scheduler.tick()
        server.verify()
        assertEquals(6, items.count())
        assertEquals("h1", storedHash())
    }

    private fun expectVersions(itemsHash: String) {
        server.expect(requestTo("https://api.warframe.market/v2/versions")).andRespond(
            withSuccess(
                """
                {"apiVersion":"0.25.0","error":null,"data":{"apps":{},
                 "collections":{"items":"$itemsHash"},"updatedAt":"2026-09-24T00:00:00Z"}}
                """,
                APPLICATION_JSON,
            ),
        )
    }

    private fun expectItems(body: ClassPathResource) {
        server.expect(requestTo("https://api.warframe.market/v2/items")).andRespond(withSuccess(body, APPLICATION_JSON))
    }

    private fun expectItems(json: String) {
        server.expect(requestTo("https://api.warframe.market/v2/items")).andRespond(withSuccess(json, APPLICATION_JSON))
    }

    private fun storedHash(): String? = versions.findById("items").map { it.hash }.orElse(null)

    private fun syncedAt(slug: String): Timestamp =
        jdbc.queryForObject("select synced_at from item where slug = ?", Timestamp::class.java, slug)!!

    private companion object {
        val FIXTURE = ClassPathResource("fixtures/v2-items.json")
    }
}
