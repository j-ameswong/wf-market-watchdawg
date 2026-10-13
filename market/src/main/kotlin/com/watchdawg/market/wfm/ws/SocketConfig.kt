package com.watchdawg.market.wfm.ws

import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmContext
import com.watchdawg.market.wfm.WfmMetrics
import com.watchdawg.market.wfm.WfmProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture

@ConfigurationProperties("wfm.socket")
data class SocketProperties(
    val url: URI = URI("wss://ws.warframe.market/socket"),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val subscriptionTimeout: Duration = Duration.ofSeconds(10),
    val silenceTimeout: Duration = Duration.ofSeconds(90),
    val stableAfter: Duration = Duration.ofMinutes(1),
    val backoffInitial: Duration = Duration.ofSeconds(1),
    val backoffMax: Duration = Duration.ofMinutes(5),
) {
    init {
        require(url.scheme in setOf("ws", "wss") && url.host != null) { "invalid wfm.socket.url" }
        require(
            listOf(connectTimeout, subscriptionTimeout, silenceTimeout, stableAfter, backoffInitial, backoffMax).all {
                it.toMillis() >
                    0
            },
        ) {
            "socket durations must be at least one millisecond"
        }
        require(backoffMax >= backoffInitial) { "socket backoff-max must be at least backoff-initial" }
    }
}

/** Injectable network boundary; every Spring test replaces this with a loopback-only guard. */
fun interface SocketConnector {
    fun connect(
        uri: URI,
        timeout: Duration,
        userAgent: String,
        listener: WebSocket.Listener,
    ): CompletableFuture<WebSocket>
}

class JdkSocketConnector : SocketConnector {
    override fun connect(
        uri: URI,
        timeout: Duration,
        userAgent: String,
        listener: WebSocket.Listener,
    ): CompletableFuture<WebSocket> = HttpClient.newBuilder().connectTimeout(timeout).build().newWebSocketBuilder()
        .connectTimeout(timeout).subprotocols("wfm").header("User-Agent", userAgent).buildAsync(uri, listener)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocketProperties::class)
class SocketConfig {
    @Bean
    @ConditionalOnMissingBean(SocketConnector::class)
    fun socketConnector(): SocketConnector = JdkSocketConnector()

    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, "watchdawg.socket.enabled", matchIfMissing = true)
    fun wfmSocket(
        props: SocketProperties,
        wfm: WfmProperties,
        context: WfmContext,
        mapper: JsonMapper,
        connector: SocketConnector,
        ingest: OrderIngest,
        client: WfmClient,
        metrics: WfmMetrics,
        baselineBudget: com.watchdawg.market.baseline.BaselineBudget,
    ) = WfmSocket(
        props,
        wfm.userAgent,
        SocketProtocol(mapper, context),
        connector,
        ingest,
        SocketGapFill(client, ingest, metrics),
        metrics,
        baselineBudget = baselineBudget,
    )
}
