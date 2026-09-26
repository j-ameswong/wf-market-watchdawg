package com.watchdawg.market.wfm

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@ConfigurationProperties(prefix = "wfm")
data class WfmProperties(
    val baseUrl: String,
    val baseUrlLegacy: String,
    val userAgent: String,
    val limits: Limits,
    /**
     * These two have no default, on purpose. If the property is missing, startup should fail
     * rather than quietly fall back to a population nobody chose (R1.8).
     *
     * A default here could also drift out of step with `application.yaml` without anything
     * noticing.
     */
    val platform: String,
    val crossplay: Boolean,
) {
    /**
     * How much outbound traffic we allow ourselves.
     *
     * There are two rate buckets, chosen by route class rather than by API version (R1.2). The
     * concurrency cap is a single global number, because a `509` is about open connections rather
     * than about any one route (R1.4).
     */
    data class Limits(val public: Rate, val contractSearch: Rate, val maxConcurrency: Int, val maxRetryAfter: Duration)

    /** [permits] requests per [per]. These are the units the upstream rules use, so we keep them. */
    data class Rate(val permits: Int, val per: Duration)
}

@Configuration
@EnableConfigurationProperties(WfmProperties::class)
class WfmConfig {

    /** Runtime visibility into the rate limiter (R12.1). [WfmMetrics] explains the per-bucket tags. */
    @Bean
    fun wfmMetrics(registry: MeterRegistry): WfmMetrics = WfmMetrics(registry)

    @Bean
    fun wfmRateLimiter(props: WfmProperties, metrics: WfmMetrics): WfmRateLimiter =
        WfmRateLimiter(props.limits, metrics)

    /** The single place crossplay is read from. C5's socket client has to use it too (R5.2). */
    @Bean
    fun wfmContext(props: WfmProperties): WfmContext = WfmContext(props.platform, props.crossplay)

    /** The v2 channel. [wfmTransport] adds the headers where no call site can undo them. */
    @Bean(V2_CLIENT)
    fun wfmRestClient(builder: RestClient.Builder, transport: WfmTransport): RestClient =
        transport.applyTo(builder).baseUrl(transport.props.baseUrl).build()

    /**
     * The v1 channel (R1.6). It gets the same transport as the v2 client, so only the base URL and
     * the JSON property casing differ.
     *
     * The snake_case strategy is set on this client's own converter, not on the shared mapper. v2
     * is camelCase, so a global strategy would quietly stop `updatedAt` and `gameRef` binding
     * (SPEC 7). **Do not move it.**
     *
     * [JsonMapper.rebuild] starts from the mapper Boot already configured, so the Kotlin module,
     * the `java.time` handling and the deserialization defaults all carry over. Only the naming
     * changes.
     */
    @Bean(LEGACY_CLIENT)
    fun wfmLegacyRestClient(builder: RestClient.Builder, transport: WfmTransport, mapper: JsonMapper): RestClient {
        val snakeCase = mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
        return transport.applyTo(builder)
            .baseUrl(transport.props.baseUrlLegacy)
            .configureMessageConverters { it.withJsonConverter(JacksonJsonHttpMessageConverter(snakeCase)) }
            .build()
    }

    @Bean
    fun wfmTransport(limiter: WfmRateLimiter, metrics: WfmMetrics, context: WfmContext, props: WfmProperties) =
        WfmTransport(limiter, metrics, context, props)

    companion object {
        /** Bean names, so a client can name its channel with a constant instead of a literal. */
        const val V2_CLIENT = "wfmRestClient"
        const val LEGACY_CLIENT = "wfmLegacyRestClient"
    }
}

/**
 * The parts of the transport no call site may opt out of: rate limiting, the context headers, and
 * the error boundary. Every client that talks to warframe.market is built through [applyTo], and
 * nothing else is, so a client for another host (C10's ntfy) never inherits them.
 * `RateLimitWiringTest` fails if a `RestClient` bean skips it without being named there as
 * non-WFM (R1.1).
 *
 * Order matters. The limiter goes on first so that it wraps the whole attempt, retries included.
 * The context interceptor goes on second so that each retried attempt has its headers stamped
 * again, rather than inheriting what the previous attempt set.
 *
 * The status handler then covers `4xx` and `5xx`, apart from `429` and `509`. The limiter has
 * already turned those into a [ThrottledException] further down the chain.
 */
class WfmTransport(
    private val limiter: WfmRateLimiter,
    private val metrics: WfmMetrics,
    private val context: WfmContext,
    val props: WfmProperties,
) {
    fun applyTo(builder: RestClient.Builder): RestClient.Builder = builder
        .requestInterceptor(WfmRateLimitInterceptor(limiter, metrics, props.limits.maxRetryAfter))
        .requestInterceptor(WfmContextInterceptor(context, props.userAgent))
        .defaultStatusHandler({ it.isError }) { _, response -> throw WfmHttpException.of(response) }
}
