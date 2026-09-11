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
     * No Kotlin defaults, deliberately: an unset value must fail startup rather than silently pick
     * a population (R1.8). A default here would also disagree with `application.yaml` the moment
     * one of them moved — which is how `crossplay` came to be `false` in code and `true` in config.
     */
    val platform: String,
    val crossplay: Boolean,
) {
    /**
     * The outbound budget. Two buckets keyed by route class rather than API version (R1.2), one
     * global concurrency cap because `509` is a connection-level signal (R1.4).
     */
    data class Limits(val public: Rate, val contractSearch: Rate, val maxConcurrency: Int, val maxRetryAfter: Duration)

    /** [permits] requests per [per] — the units the upstream rules state their limits in. */
    data class Rate(val permits: Int, val per: Duration)
}

@Configuration
@EnableConfigurationProperties(WfmProperties::class)
class WfmConfig {

    /** R12.1's read-out on the rate-limit boundary; see [WfmMetrics] for why it is per bucket. */
    @Bean
    fun wfmMetrics(registry: MeterRegistry): WfmMetrics = WfmMetrics(registry)

    @Bean
    fun wfmRateLimiter(props: WfmProperties, metrics: WfmMetrics): WfmRateLimiter =
        WfmRateLimiter(props.limits, metrics)

    /** The one read point for crossplay, which C5's socket client is obliged to quote (R5.2). */
    @Bean
    fun wfmContext(props: WfmProperties): WfmContext = WfmContext(props.platform, props.crossplay)

    /**
     * Everything about the transport that no call site may opt out of, applied to *every*
     * `RestClient.Builder` the context hands out — so a client added later is governed without
     * anyone remembering to wire it (R1.1). The service talks to warframe.market and nothing else,
     * so a blanket customizer is the right blast radius.
     *
     * The status handler covers `4xx`/`5xx` *except* `429`/`509`, which the interceptor below it
     * has already turned into a [ThrottledException] before the response could get this far.
     *
     * The limiter is registered first so it wraps the whole attempt, and the context second so each
     * of its retried attempts is re-stamped rather than trusting a header set on a previous pass.
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

    /** Headers are absent on purpose — the customizer above stamps them where no call site can. */
    @Bean(V2_CLIENT)
    fun wfmRestClient(builder: RestClient.Builder, props: WfmProperties): RestClient =
        builder.baseUrl(props.baseUrl).build()

    /**
     * The v1 channel (R1.6). Same builder, so it inherits the whole transport stack above without
     * a wiring step — only the base URL and the property casing differ.
     *
     * The snake_case strategy is scoped to this client's own JSON converter rather than set on the
     * context's mapper: v2 is camelCase, and a global strategy would silently stop `updatedAt` and
     * `gameRef` binding (SPEC 7). [JsonMapper.rebuild] starts from the mapper Boot configured, so
     * the Kotlin module, the `java.time` handling and the deserialization defaults all carry over
     * and only the naming changes.
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
        /** Bean names, so a client picks its channel by constant rather than by a repeated string. */
        const val V2_CLIENT = "wfmRestClient"
        const val LEGACY_CLIENT = "wfmLegacyRestClient"
    }
}
