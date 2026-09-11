package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Binds a mock server to the real `wfmRestClient` bean with [RestClient.mutate], so only the
 * request factory is replaced. The base URL, the headers and the limiter interceptor under test
 * are all the production ones.
 *
 * No test here reaches the live API (R2.6).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Timeout(value = 30, unit = SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WfmClientTest {

    @Autowired lateinit var wfmRestClient: RestClient

    private lateinit var server: MockRestServiceServer
    private lateinit var client: WfmClient

    @BeforeEach
    fun bindMockServer() {
        val builder = wfmRestClient.mutate()
        server = MockRestServiceServer.bindTo(builder).build()
        client = WfmClient(builder.build())
    }

    @Test
    fun `getVersions unwraps the envelope`() {
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        val versions = client.getVersions()

        assertEquals("abc123", versions.collections.items)
        server.verify()
    }

    @Test
    fun `getItems unwraps the envelope`() {
        server.expect(requestTo(ITEMS)).andRespond(withSuccess(ITEMS_JSON, APPLICATION_JSON))

        val items = client.getItems()

        assertEquals(listOf("mirage_prime_set"), items.map { it.slug })
        server.verify()
    }

    @Test
    fun `an envelope carrying an error throws instead of yielding null data`() {
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(ERROR_JSON, APPLICATION_JSON))

        val thrown = assertFails { client.getVersions() }

        assertTrue(thrown.message.orEmpty().contains("wfm api error"), thrown.message.orEmpty())
    }

    @Test
    fun `consecutive calls are paced by the public bucket before the connection opens`() {
        server.expect(times(2), requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        val startedAt = System.nanoTime()
        client.getVersions()
        client.getVersions()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        // public is 2 req/s, so the second call waits 500ms. Unpaced, this is ~0ms.
        assertTrue(elapsed >= Duration.ofMillis(500), "two calls took only $elapsed -- the limiter was bypassed")
        // contract-search would space them 5s apart; the route must not have landed in that bucket.
        assertTrue(elapsed < Duration.ofSeconds(3), "two calls took $elapsed -- paced on the wrong bucket")
        server.verify()
    }

    @Test
    fun `a failed call releases its connection slot`() {
        // max-concurrency is 2, so two leaked permits would park this third call forever.
        server.expect(times(2), requestTo(VERSIONS)).andRespond(withServerError())
        server.expect(times(1), requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        repeat(2) { assertFails { client.getVersions() } }

        assertEquals("abc123", client.getVersions().collections.items)
        server.verify()
    }

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"
        const val ITEMS = "https://api.warframe.market/v2/items"

        val VERSIONS_JSON = """
            {"apiVersion":"2.0","data":{"apps":{"ios":"1.2.3"},
             "collections":{"items":"abc123","rivens":"def456"},"updatedAt":"2026-09-10T00:00:00Z"}}
        """.trimIndent()

        val ITEMS_JSON = """
            {"apiVersion":"2.0","data":[{"id":"54a73e65e779893a797fff9c","slug":"mirage_prime_set",
             "ducats":0,"maxRank":0,"tags":["set","prime"],"updatedAt":"2026-09-10T00:00:00Z"}]}
        """.trimIndent()

        val ERROR_JSON = """{"apiVersion":"2.0","error":{"request":["versions"]}}"""
    }
}
