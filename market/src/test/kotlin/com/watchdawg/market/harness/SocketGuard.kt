package com.watchdawg.market.harness

import com.watchdawg.market.wfm.ws.JdkSocketConnector
import com.watchdawg.market.wfm.ws.SocketConnector
import java.net.URI
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class SocketGuard : SocketConnector {
    private val attempts = AtomicInteger()
    private val delegate = JdkSocketConnector()

    override fun connect(
        uri: URI,
        timeout: Duration,
        userAgent: String,
        listener: WebSocket.Listener,
    ): CompletableFuture<WebSocket> {
        // Literal loopback addresses only: no DNS resolution or externally configured hostname.
        if (uri.host !in setOf("127.0.0.1", "[::1]", "::1")) {
            attempts.incrementAndGet()
            error("test refused a non-loopback socket")
        }
        return delegate.connect(uri, timeout, userAgent, listener)
    }

    fun assertUntouched() = assertEquals(0, attempts.getAndSet(0), "a test attempted a non-local socket")
}
