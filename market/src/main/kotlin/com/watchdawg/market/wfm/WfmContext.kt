package com.watchdawg.market.wfm

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * The observer's context: the platform this service watches the market *as*, and whether crossplay
 * orders are in scope. One value, bound from `wfm.platform` and `wfm.crossplay`, applied identically
 * to every channel (R1.8, `docs/adr/0002-crossplay-single-global-setting.md`).
 *
 * **C5's socket client must read [crossplay] from here** and put it in the `subscribe/newOrders`
 * payload explicitly (R5.2). The two channels take opposite upstream defaults — REST `false`, the
 * socket `true` — so a channel that omits the value does not fail. It quietly observes a different
 * population than the other one, and the diff classifier then fabricates a `vanished` on ~7% of
 * ingested orders: on exactly the event SPEC 2.2 treats as evidence of a sale. Nothing but this
 * obligation prevents that. There is no cross-channel test to catch it, because there is no socket
 * until C5.
 */
data class WfmContext(val platform: String, val crossplay: Boolean)

/**
 * Stamps [context] onto every outbound request, overwriting whatever the call site asked for.
 *
 * A `defaultHeader` on the client would be the obvious home for these and was where they lived, but
 * a default header is exactly what a call site *can* override, and R1.8 says none may. So they sit
 * in the same un-skippable seam as the limiter. `User-Agent` rides along for the same reason: R1.5
 * makes it the project's identity to the upstream, not a per-call choice.
 */
class WfmContextInterceptor(private val context: WfmContext, private val userAgent: String) :
    ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.apply {
            // set, not add: a call site that named its own value is replaced, not appended to.
            set(PLATFORM, context.platform)
            set(CROSSPLAY, context.crossplay.toString())
            set(HttpHeaders.USER_AGENT, userAgent)
        }
        return execution.execute(request, body)
    }

    companion object {
        const val PLATFORM = "Platform"
        const val CROSSPLAY = "Crossplay"
    }
}
