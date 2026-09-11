package com.watchdawg.market.wfm

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * The seam R1.1 asks for: pacing sits between the client and the connection, so a call site cannot
 * issue an unpaced request even by mistake. Registered on every `RestClient.Builder` in the context
 * (see `WfmConfig`), which is what makes "no code path may bypass it" a property of the wiring
 * rather than a rule people have to remember.
 *
 * It is also where the `429`/`509` retry lives (R1.3). A retry has to *spend* budget rather than
 * skip it, and this is the only place that can re-acquire a turn before reissuing — a status
 * handler runs above the transport, with the limiter already behind it.
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

            // Each attempt takes its own turn: the retry is paced like any other call, and the
            // acquire is sequential rather than nested -- re-acquiring while still holding a
            // connection slot would deadlock against max-concurrency.
            val response = limiter.acquire(bucket) { execution.execute(request, body) }
            val refusal = refusalOf(response) ?: return response
            val status = response.statusCode.value()
            response.close()

            if (refusal is ConcurrencyLimitedException) limiter.narrowConcurrency()

            val wait = refusal.retryAfter
            // A cooloff longer than the ceiling is not worth parking a worker thread for; the poll
            // scheduler (C6) decides what to do with a long one, not an interceptor.
            if (--attemptsLeft == 0 || (wait != null && wait > maxRetryAfter)) throw refusal
            metrics.retryIssued(bucket, status)
            cooloff = wait
        }
    }

    /** Null for anything the transport has no opinion on — that response belongs to the caller. */
    private fun refusalOf(response: ClientHttpResponse): ThrottledException? {
        val retryAfter = retryAfterOf(response.headers, clock)
        return when (response.statusCode.value()) {
            429 -> RateLimitedException(retryAfter)
            509 -> ConcurrencyLimitedException(retryAfter)
            else -> null
        }
    }

    companion object {
        /**
         * One initial call and exactly one retry (R1.3, plan Decision 4). The spec's own acceptance
         * bullet names the bound, and a second refusal means the server is not merely busy.
         */
        private const val ATTEMPTS = 2

        /**
         * Route class, not API version (ADR-0005). v1 `statistics` paces on [Bucket.PUBLIC]
         * alongside every v2 route, as SPEC 2.1's arithmetic assumes; only auction search draws on
         * the separate contract-search budget of SPEC 2.5.
         */
        fun bucketFor(uri: URI): Bucket =
            if (uri.path.orEmpty().contains("/auctions/search")) Bucket.CONTRACT_SEARCH else Bucket.PUBLIC
    }
}
