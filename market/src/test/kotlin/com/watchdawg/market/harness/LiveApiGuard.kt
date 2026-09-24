package com.watchdawg.market.harness

import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The request factory behind every `RestClient` in a Spring test context. It sends nothing: each
 * request is recorded and refused (R2.6, ADR-0017).
 *
 * A test that exercises HTTP binds `MockRestServiceServer` to a mutated builder, which swaps this
 * factory out, so mocked calls never arrive here. Anything that does arrive was headed for the
 * live API.
 *
 * Refusing alone is not enough, because production code may swallow the exception —
 * `CollectionSyncScheduler` catches everything. So every refusal is also recorded, and
 * [LiveApiGuardListener] fails a test that leaves the record non-empty.
 */
class LiveApiGuard : ClientHttpRequestFactory {
    private val attempts = CopyOnWriteArrayList<String>()

    /** Every request refused since the last [assertUntouched], as `METHOD uri`. */
    val refused: List<String> get() = attempts.toList()

    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
        attempts += "$httpMethod $uri"
        throw LiveApiRefused(uri)
    }

    /** Throws if anything was refused since the last call, and starts a fresh record either way. */
    fun assertUntouched() {
        val seen = refused
        attempts.clear()
        if (seen.isNotEmpty()) {
            throw AssertionError("the live API was reached during this test or its context's startup (R2.6): $seen")
        }
    }
}

class LiveApiRefused(uri: URI) :
    IllegalStateException("refused $uri: tests never reach the live API (R2.6). Bind MockRestServiceServer instead.")
