package com.watchdawg.market.wfm

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * Applies the rate limiter between the client and the connection, so no call site can issue an
 * unpaced request even by mistake (R1.1).
 *
 * `WfmConfig` registers this on every `RestClient.Builder` in the context. That is what makes
 * "nothing bypasses the limiter" a fact about the wiring rather than a rule people have to
 * remember.
 *
 * The `429`/`509` retry lives here too (R1.3). A retry has to *spend* budget rather than skip it,
 * and this is the only place that can take a fresh turn before reissuing. A status handler would
 * be too late: by the time one runs, the limiter is already behind us.
 */
class WfmRateLimitInterceptor(
    private val limiter: WfmRateLimiter,
    private val metrics: WfmMetrics,
    private val maxRetryAfter: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val bucket = bucketFor(request.uri)
        var attemptsLeft = ATTEMPTS
        var cooloff: Duration? = null

        while (true) {
            cooloff?.let { Thread.sleep(it) }

            // Every attempt takes its own turn, so a retry is paced like any other call.
            // Acquire sequentially, never nested: re-acquiring while still holding a connection
            // slot would deadlock against max-concurrency.
            val response = limiter.acquire(bucket) { execution.execute(request, body) }
            val throttle = Throttle.of(response.statusCode.value()) ?: return response
            val refusal = throttle.refusal(retryAfterOf(response.headers, clock))
            response.close()

            if (throttle == Throttle.CONCURRENCY_LIMITED) limiter.narrowConcurrency()

            val wait = refusal.retryAfter
            // Not worth parking a worker thread on a cooloff longer than the ceiling. Deciding
            // what to do with a long one is the poll scheduler's job (C6), not an interceptor's.
            if (--attemptsLeft == 0 || (wait != null && wait > maxRetryAfter)) throw refusal
            metrics.retryIssued(bucket, throttle)
            cooloff = wait
        }
    }

    companion object {
        /**
         * One call plus at most one retry (R1.3, plan Decision 4). The spec's acceptance bullet
         * fixes this number. If the server refuses us twice, it is saying more than "busy".
         */
        private const val ATTEMPTS = 2

        /**
         * Picks the bucket from the route, not from the API version (ADR-0005).
         *
         * Auction search is the only thing that draws on the contract-search budget (SPEC 2.5).
         * Everything else shares [Bucket.PUBLIC], v1 `statistics` included, which is what
         * SPEC 2.1's arithmetic assumes.
         */
        fun bucketFor(uri: URI): Bucket =
            if (uri.path.orEmpty().contains("/auctions/search")) Bucket.CONTRACT_SEARCH else Bucket.PUBLIC
    }
}
