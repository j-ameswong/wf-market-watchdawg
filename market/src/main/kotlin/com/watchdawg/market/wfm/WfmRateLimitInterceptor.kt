package com.watchdawg.market.wfm

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.net.URI

/**
 * The seam R1.1 asks for: pacing sits between the client and the connection, so a call site cannot
 * issue an unpaced request even by mistake. Registered on every `RestClient.Builder` in the context
 * (see `WfmConfig`), which is what makes "no code path may bypass it" a property of the wiring
 * rather than a rule people have to remember.
 */
class WfmRateLimitInterceptor(private val limiter: WfmRateLimiter) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse = limiter.acquire(bucketFor(request.uri)) { execution.execute(request, body) }

    companion object {
        /**
         * Route class, not API version (ADR-0005). v1 `statistics` paces on [Bucket.PUBLIC]
         * alongside every v2 route, as SPEC 2.1's arithmetic assumes; only auction search draws on
         * the separate contract-search budget of SPEC 2.5.
         */
        fun bucketFor(uri: URI): Bucket =
            if (uri.path.orEmpty().contains("/auctions/search")) Bucket.CONTRACT_SEARCH else Bucket.PUBLIC
    }
}
