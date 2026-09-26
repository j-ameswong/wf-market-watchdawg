package com.watchdawg.market.wfm

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * Who this service watches the market as: which platform, and whether crossplay orders count.
 *
 * Bound once from `wfm.platform` and `wfm.crossplay`, and applied the same way on every channel
 * (R1.8, `docs/adr/0002-crossplay-single-global-setting.md`).
 *
 * The socket reads [crossplay] from here and puts it in the `subscribe/newOrders` payload
 * explicitly (R5.2). The two channels default the opposite way upstream: REST assumes `false`, the
 * socket assumes `true`. So a channel that leaves the value out does not fail. It just watches a
 * different set of orders than the other channel, and the diff classifier then invents a
 * `vanished` for about 7% of ingested orders, which is exactly the event SPEC 2.2 reads as evidence
 * of a sale. `CrossplayHeaderTest` pins that both channels quote the same value.
 */
data class WfmContext(val platform: String, val crossplay: Boolean)

/**
 * Puts [context] on every outbound request, overwriting whatever the call site asked for.
 *
 * A `defaultHeader` on the client would be the obvious home for them, but a default header is
 * precisely the kind a call site *can* override, and R1.8 says none of them may be. So they live
 * here, in the same un-skippable place as the limiter.
 *
 * `User-Agent` rides along for the same reason: R1.5 makes it the project's identity to the
 * upstream, not something an individual call gets to choose.
 */
class WfmContextInterceptor(private val context: WfmContext, private val userAgent: String) :
    ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.apply {
            // set, not add: this replaces a value the call site chose rather than appending to it.
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
