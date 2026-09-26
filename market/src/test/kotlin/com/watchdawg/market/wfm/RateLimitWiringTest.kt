package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.notify.NotifyConfig
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
 * The standing no-bypass guard for R1.1 and R1.8, and the boundary that keeps the WFM transport
 * off clients for other hosts.
 *
 * It enumerates the `RestClient` beans rather than naming them, so a client added later is covered
 * without anyone editing this test: it either carries the transport or is named in [NON_WFM].
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RateLimitWiringTest {

    @Autowired lateinit var clients: Map<String, RestClient>

    @Autowired lateinit var builder: RestClient.Builder

    @Test
    fun `every WFM client carries the transport interceptors`() {
        assertEquals(WFM, clients.keys - NON_WFM, "a RestClient bean is neither a WFM client nor named as non-WFM")

        WFM.forEach { name ->
            val interceptors = interceptorsOf(clients.getValue(name))
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
    fun `a client for another host carries none of it`() {
        val clean = interceptorsOf(builder.build()) + NON_WFM.flatMap { interceptorsOf(clients.getValue(it)) }

        assertTrue(clean.none { it is WfmRateLimitInterceptor || it is WfmContextInterceptor }, "$clean")
    }

    @Test
    fun `the bucket is chosen by route class, not by API version`() {
        // SPEC 2.1's arithmetic counts v1 statistics inside the v2 bucket total and lists only
        // auction search separately. So the route decides the bucket, not the calling bean
        // (ADR-0005).
        assertEquals(Bucket.PUBLIC, bucketOf("https://api.warframe.market/v2/orders/item/frost_prime_set"))
        assertEquals(Bucket.PUBLIC, bucketOf("https://api.warframe.market/v1/items/frost_prime_set/statistics"))
        assertEquals(Bucket.CONTRACT_SEARCH, bucketOf("https://api.warframe.market/v1/auctions/search?type=riven"))
    }

    private fun bucketOf(uri: String) = WfmRateLimitInterceptor.bucketFor(URI(uri))

    private companion object {
        val WFM = setOf(WfmConfig.V2_CLIENT, WfmConfig.LEGACY_CLIENT)

        /** `RestClient` beans for hosts other than warframe.market. */
        val NON_WFM = setOf(NotifyConfig.NTFY_CLIENT)
    }

    private fun interceptorsOf(client: RestClient): List<ClientHttpRequestInterceptor> {
        val found = mutableListOf<ClientHttpRequestInterceptor>()
        client.mutate().requestInterceptors { found += it }
        return found
    }
}
