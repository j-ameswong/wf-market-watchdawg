package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R12.1: the rate-limit boundary is proven at runtime, not assumed. ADR-0004 makes a `429` a bug
 * in our own pacing, so "are we under the limit" has to be answerable from a meter — which means
 * the meters have to exist, be tagged per bucket, and actually move.
 *
 * Every meter here is read from a registry this test owns rather than the context's, because the
 * context's is shared with every other class in the suite and a count would then depend on run
 * order (R2.7).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Timeout(value = 60, unit = SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WfmMetricsTest {

    @Autowired lateinit var props: WfmProperties

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var contextRegistry: MeterRegistry

    @Test
    fun `requests and time spent waiting are metered per bucket`() {
        val registry = SimpleMeterRegistry()
        val limiter = WfmRateLimiter(props.limits, WfmMetrics(registry))

        repeat(3) { limiter.acquire(Bucket.PUBLIC) {} }
        limiter.acquire(Bucket.CONTRACT_SEARCH) {}

        assertEquals(3.0, registry.requests("public"))
        assertEquals(1.0, registry.requests("contract-search"))

        // public is 2 req/s: the 2nd and 3rd acquires are held ~500ms each, the 1st not at all.
        val waited = registry.find("wfm.request.wait").tag("bucket", "public").timer()
        assertNotNull(waited)
        assertEquals(3L, waited.count())
        assertTrue(
            waited.totalTime(MILLISECONDS) >= 900.0,
            "public waited only ${waited.totalTime(MILLISECONDS)}ms in total",
        )
        // The buckets are independent, so contract-search never waited behind them.
        val unheld = registry.find("wfm.request.wait").tag("bucket", "contract-search").timer()
        assertEquals(0.0, unheld?.totalTime(SECONDS), "contract-search waited behind the public bucket")
    }

    @Test
    fun `a retry is metered against its bucket and its status`() {
        val registry = SimpleMeterRegistry()
        val metrics = WfmMetrics(registry)
        val limiter = WfmRateLimiter(props.limits, metrics)
        val builder = wfmRestClient.mutate().requestInterceptors { chain ->
            chain.replaceAll {
                if (it is WfmRateLimitInterceptor) {
                    WfmRateLimitInterceptor(limiter, metrics, props.limits.maxRetryAfter)
                } else {
                    it
                }
            }
        }
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(VERSIONS)).andRespond(withTooManyRequests().header(RETRY_AFTER, "0"))
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        WfmClient(builder.build()).getVersions()

        assertEquals(1.0, registry.find("wfm.retries").tag("bucket", "public").tag("status", "429").counter()?.count())
        // Two attempts, so two turns -- the retry spent budget rather than bypassing it (R1.3).
        assertEquals(2.0, registry.requests("public"))
        server.verify()
    }

    @Test
    fun `the concurrency cap is a gauge, so a 509 leaves a visible trace`() {
        val registry = SimpleMeterRegistry()
        val limiter = WfmRateLimiter(props.limits, WfmMetrics(registry))
        val gauge = registry.find("wfm.concurrency.limit").gauge()
        assertNotNull(gauge)
        assertEquals(props.limits.maxConcurrency.toDouble(), gauge.value())

        limiter.narrowConcurrency()

        assertEquals((props.limits.maxConcurrency - 1).toDouble(), gauge.value())
    }

    @Test
    fun `the context's own registry carries every bucket and status from startup`() {
        // A meter that only appears after the first call is no use to an alert on a quiet service:
        // "no data" and "under the limit" would look the same, and so would "no data" and "no
        // retries" -- which is the one an ADR-0004 alert would actually watch.
        listOf("public", "contract-search").forEach { bucket ->
            assertNotNull(
                contextRegistry.find("wfm.requests").tag("bucket", bucket).counter(),
                "wfm.requests is not registered for '$bucket' until something calls",
            )
            listOf("429", "509").forEach { status ->
                assertNotNull(
                    contextRegistry.find("wfm.retries").tag("bucket", bucket).tag("status", status).counter(),
                    "wfm.retries is not registered for '$bucket'/'$status' until something is refused",
                )
            }
        }
    }

    private fun MeterRegistry.requests(bucket: String): Double? =
        find("wfm.requests").tag("bucket", bucket).counter()?.count()

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"

        val VERSIONS_JSON = """
            {"apiVersion":"2.0","data":{"apps":{"ios":"1.2.3"},
             "collections":{"items":"abc123"},"updatedAt":"2026-09-10T00:00:00Z"}}
        """.trimIndent()
    }
}
