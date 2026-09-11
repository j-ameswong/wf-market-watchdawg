package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The standing no-bypass guard for R1.1 and R1.8.
 *
 * It enumerates the `RestClient` beans rather than naming them, so a client added later (T7's v1
 * legacy client, for instance) is covered without anyone editing this test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RateLimitWiringTest {

    @Autowired lateinit var clients: Map<String, RestClient>

    @Test
    fun `every RestClient bean carries the transport interceptors`() {
        assertTrue(clients.isNotEmpty(), "no RestClient beans in the context -- this guard is vacuous")

        clients.forEach { (name, client) ->
            val interceptors = interceptorsOf(client)
            assertTrue(
                interceptors.any { it is WfmRateLimitInterceptor },
                "bean '$name' can issue an unpaced request (R1.1)",
            )
            assertTrue(
                interceptors.any { it is WfmContextInterceptor },
                "bean '$name' can issue a request with no crossplay context (R1.8)",
            )
        }
    }

    @Test
    fun `the bucket is chosen by route class, not by API version`() {
        // ADR-0005: SPEC 2.1's arithmetic puts v1 statistics inside the v2 bucket total and lists
        // only auction search separately, so the route decides -- not which client bean called.
        assertEquals(Bucket.PUBLIC, bucketOf("https://api.warframe.market/v2/orders/item/frost_prime_set"))
        assertEquals(Bucket.PUBLIC, bucketOf("https://api.warframe.market/v1/items/frost_prime_set/statistics"))
        assertEquals(Bucket.CONTRACT_SEARCH, bucketOf("https://api.warframe.market/v1/auctions/search?type=riven"))
    }

    private fun bucketOf(uri: String) = WfmRateLimitInterceptor.bucketFor(URI(uri))

    private fun interceptorsOf(client: RestClient): List<ClientHttpRequestInterceptor> {
        val found = mutableListOf<ClientHttpRequestInterceptor>()
        client.mutate().requestInterceptors { found += it }
        return found
    }
}
