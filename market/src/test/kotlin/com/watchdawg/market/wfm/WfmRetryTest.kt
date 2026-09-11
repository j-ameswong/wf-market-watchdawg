package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1.3 and R1.4 at the transport: a refusal is retried once, on budget, and then surfaces as a
 * typed failure. Every response is a `MockRestServiceServer` fixture -- no test reaches the live
 * API (R2.6).
 *
 * The limiter under test is built per test rather than autowired. Narrowing concurrency is
 * permanent by design and the context's limiter bean outlives this class, so sharing it would make
 * the suite order-dependent (R2.7). That the *production* client carries this interceptor at all is
 * `RateLimitWiringTest`'s job; this class tests what the interceptor then does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Timeout(value = 60, unit = SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WfmRetryTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var props: WfmProperties

    private lateinit var limiter: WfmRateLimiter
    private lateinit var metrics: WfmMetrics
    private lateinit var server: MockRestServiceServer
    private lateinit var client: WfmClient

    @BeforeEach
    fun bindMockServer() {
        metrics = WfmMetrics(SimpleMeterRegistry())
        limiter = WfmRateLimiter(props.limits, metrics)
        val builder = wfmRestClient.mutate().requestInterceptors { chain ->
            chain.replaceAll { if (it is WfmRateLimitInterceptor) freshLimiterInterceptor() else it }
        }
        server = MockRestServiceServer.bindTo(builder).build()
        client = WfmClient(builder.build())
    }

    private fun freshLimiterInterceptor() = WfmRateLimitInterceptor(limiter, metrics, props.limits.maxRetryAfter)

    @Test
    fun `a 429 carrying Retry-After is retried once, no sooner than it asked, then succeeds`() {
        server.expect(requestTo(VERSIONS)).andRespond(withTooManyRequests().header(RETRY_AFTER, "2"))
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        val startedAt = System.nanoTime()
        val versions = client.getVersions()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertEquals("abc123", versions.collections.items)
        assertTrue(elapsed >= Duration.ofSeconds(2), "the retry reissued after only $elapsed, ignoring Retry-After")
        server.verify()
    }

    @Test
    fun `the retry spends budget rather than bypassing it`() {
        // No Retry-After, so nothing but the limiter's own turn spaces the second attempt.
        server.expect(requestTo(VERSIONS)).andRespond(withTooManyRequests())
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        val before = metrics.requestsIssued(Bucket.PUBLIC)
        client.getVersions()

        assertEquals(2, metrics.requestsIssued(Bucket.PUBLIC) - before, "the retry reissued without taking a turn")
        server.verify()
    }

    @Test
    fun `a second consecutive 429 surfaces a typed failure, and there is no third attempt`() {
        // A third request would fail this test as an unexpected call, not merely go uncounted.
        server.expect(times(2), requestTo(VERSIONS)).andRespond(withTooManyRequests())

        val thrown = assertFailsWith<ThrottledException> { client.getVersions() }

        assertIs<RateLimitedException>(thrown)
        server.verify()
    }

    @Test
    fun `a 509 is a type distinct from 429 and narrows effective concurrency`() {
        server.expect(times(2), requestTo(VERSIONS)).andRespond(withRawStatus(509))
        assertEquals(2, limiter.maxConcurrency, "this test needs room to narrow")

        val thrown = assertFailsWith<ThrottledException> { client.getVersions() }

        // R1.4: 509 is a concurrency signal, so the remedy is fewer connections, not a longer wait.
        assertIs<ConcurrencyLimitedException>(thrown)
        assertEquals(1, limiter.maxConcurrency, "a 509 only waited; the connection cap did not move")
        server.verify()
    }

    @Test
    fun `a Retry-After above the ceiling surfaces immediately instead of parking a thread`() {
        server.expect(requestTo(VERSIONS)).andRespond(withTooManyRequests().header(RETRY_AFTER, "120"))

        val startedAt = System.nanoTime()
        val thrown = assertFailsWith<RateLimitedException> { client.getVersions() }
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertEquals(Duration.ofSeconds(120), thrown.retryAfter)
        assertTrue(elapsed < Duration.ofSeconds(5), "waited $elapsed on a cooloff over ${props.limits.maxRetryAfter}")
        server.verify() // one request only -- the ceiling means no retry at all
    }

    @Test
    fun `Retry-After parses as delta-seconds and as an HTTP-date`() {
        val clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), UTC)

        assertEquals(Duration.ofSeconds(2), retryAfterOf(retryAfter("2"), clock))
        assertEquals(Duration.ofSeconds(30), retryAfterOf(retryAfter("Thu, 10 Sep 2026 00:00:30 GMT"), clock))
        // A date already past means "now", never a negative wait.
        assertEquals(Duration.ZERO, retryAfterOf(retryAfter("Wed, 09 Sep 2026 23:59:00 GMT"), clock))
        assertNull(retryAfterOf(HttpHeaders(), clock))
        assertNull(retryAfterOf(retryAfter("soon"), clock), "an unparseable value must not become a wait")
    }

    private fun retryAfter(value: String) = HttpHeaders().apply { set(RETRY_AFTER, value) }

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"

        val VERSIONS_JSON = """
            {"apiVersion":"2.0","data":{"apps":{"ios":"1.2.3"},
             "collections":{"items":"abc123","rivens":"def456"},"updatedAt":"2026-09-10T00:00:00Z"}}
        """.trimIndent()
    }
}
