package com.watchdawg.market.wfm.ws

import com.watchdawg.market.wfm.WfmClient
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration

/** Socket settings for a test: the fake's [url], deadlines a test can wait out, and quick reconnects. */
fun socketProperties(
    url: URI,
    connectTimeout: Duration = Duration.ofSeconds(5),
    subscribeTimeout: Duration = Duration.ofSeconds(5),
    silenceTimeout: Duration = Duration.ofSeconds(30),
) = SocketProperties(
    url,
    connectTimeout,
    subscribeTimeout,
    silenceTimeout,
    SocketProperties.Reconnect(initial = Duration.ofMillis(20), max = Duration.ofMillis(100)),
)

/**
 * `/v2/orders/recent` for a gap-fill under test: a v2 client with the real transport, bound to
 * [server] rather than the live API.
 */
class RecentOrders(v2: RestClient) {
    private val builder = v2.mutate()
    val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    val client = WfmClient(builder.build())

    /** Expects [count] requests and answers each with [orders], a JSON array of orders. */
    fun answer(count: ExpectedCount = ExpectedCount.manyTimes(), orders: String = "[]") {
        server.expect(count, requestTo(URL))
            .andRespond(withSuccess("""{"apiVersion":"0.25.0","data":$orders}""", APPLICATION_JSON))
    }

    companion object {
        const val URL = "https://api.warframe.market/v2/orders/recent"
    }
}
