package com.watchdawg.market.ingest

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmHttpException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Fetch and reconcile one book, through the production client and a mock server (R2.6). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OrderBookPollTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var items: ItemRepository

    @Autowired lateinit var ingest: OrderIngest

    @Autowired lateinit var jdbc: JdbcTemplate

    private val builder by lazy { wfmRestClient.mutate() }
    private val server by lazy { MockRestServiceServer.bindTo(builder).build() }
    private val poll by lazy { OrderBookPoll(WfmClient(builder.build()), items, ingest) }

    @BeforeEach
    fun catalog() {
        items.upsert(ItemRecord(id = KHRA, slug = "khra"))
    }

    @Test
    fun `a fetched book is reconciled as a full book`() {
        server.expect(requestTo("$ORDERS/khra"))
            .andRespond(withSuccess(ClassPathResource("fixtures/v2-orders/khra.json"), APPLICATION_JSON))

        assertIs<BookOutcome.Reconciled>(poll.poll("khra"))

        server.verify()
        assertEquals(listOf("book"), jdbc.queryForList("select distinct source from order_event", String::class.java))
        assertEquals(319, count("order_event"))
        // khra's orders carry ranks 0, 2 and 3 and no charges (R7.5).
        assertEquals(listOf(0, 2, 3), jdbc.queryForList("select rank from market order by rank", Int::class.java))
    }

    @Test
    fun `a failed fetch writes nothing`() {
        server.expect(requestTo("$ORDERS/khra")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertFailsWith<WfmHttpException> { poll.poll("khra") }

        server.verify()
        assertEquals(listOf(0, 0, 0, 0), listOf("order_book", "wfm_order", "order_event", "market_quote").map(::count))
    }

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

    private companion object {
        const val KHRA = "5dbe9b097ea27b0ffe3ca26c"
        const val ORDERS = "https://api.warframe.market/v2/orders/item"
    }
}
