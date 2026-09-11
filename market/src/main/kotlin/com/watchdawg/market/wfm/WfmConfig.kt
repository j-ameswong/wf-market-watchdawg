package com.watchdawg.market.wfm

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.RestClientCustomizer
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
     * A default here can also drift out of step with `application.yaml`. That has already happened
     * once: the code said `crossplay = false` while the config said `true`.
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

    /**
     * Applies the parts of the transport that no call site may opt out of: rate limiting, the
     * context headers, and the error boundary.
     *
     * This runs on every `RestClient.Builder` the context hands out, so a client added later picks
     * all of it up without anyone wiring it by hand (R1.1). Applying it that broadly is safe here
     * because the service only ever talks to warframe.market.
     *
     * Order matters. The limiter goes on first so that it wraps the whole attempt, retries
     * included. The context interceptor goes on second so that each retried attempt has its
     * headers stamped again, rather than inheriting what the previous attempt set.
     *
     * The status handler then covers `4xx` and `5xx`, apart from `429` and `509`. The limiter has
     * already turned those into a [ThrottledException] further down the chain.
     */
    @Bean
    fun wfmTransportCustomizer(
        limiter: WfmRateLimiter,
        metrics: WfmMetrics,
        context: WfmContext,
        props: WfmProperties,
    ): RestClientCustomizer = RestClientCustomizer { builder ->
        builder
            .requestInterceptor(WfmRateLimitInterceptor(limiter, metrics, props.limits.maxRetryAfter))
            .requestInterceptor(WfmContextInterceptor(context, props.userAgent))
            .defaultStatusHandler({ it.isError }) { _, response -> throw WfmHttpException.of(response) }
    }

    /** No headers here on purpose: the customizer above adds them where no call site can undo it. */
    @Bean(V2_CLIENT)
    fun wfmRestClient(builder: RestClient.Builder, props: WfmProperties): RestClient =
        builder.baseUrl(props.baseUrl).build()

    /**
     * The v1 channel (R1.6). It comes from the same builder as the v2 client, so it inherits the
     * whole transport stack for free. Only the base URL and the JSON property casing differ.
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
    fun wfmLegacyRestClient(builder: RestClient.Builder, props: WfmProperties, mapper: JsonMapper): RestClient {
        val snakeCase = mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
        return builder
            .baseUrl(props.baseUrlLegacy)
            .configureMessageConverters { it.withJsonConverter(JacksonJsonHttpMessageConverter(snakeCase)) }
            .build()
    }

    companion object {
        /** Bean names, so a client can name its channel with a constant instead of a literal. */
        const val V2_CLIENT = "wfmRestClient"
        const val LEGACY_CLIENT = "wfmLegacyRestClient"
    }
}
