package com.watchdawg.market.wfm.ws

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Opens a socket to [url]. An interface only so the test harness can refuse a live host, as it
 * refuses every `RestClient` (R2.6, ADR-0017).
 */
fun interface SocketConnector {
    fun connect(url: URI, listener: WebSocket.Listener): CompletableFuture<WebSocket>
}

/**
 * The JDK's client, so no dependency is added (decision 1). It offers the `wfm` subprotocol the
 * server requires (R5.1) and the project's `User-Agent` (R1.5).
 *
 * The JDK waits forever for a connection by default, so [connectTimeout] bounds both the TCP
 * connection and the opening handshake.
 */
class JdkSocketConnector(private val userAgent: String, private val connectTimeout: Duration) : SocketConnector {
    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun connect(url: URI, listener: WebSocket.Listener): CompletableFuture<WebSocket> =
        client.newWebSocketBuilder()
            .subprotocols(WFM_SUBPROTOCOL)
            .header("User-Agent", userAgent)
            .connectTimeout(connectTimeout)
            .buildAsync(url, listener)
}
