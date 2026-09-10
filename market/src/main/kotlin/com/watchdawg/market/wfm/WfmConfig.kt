package com.watchdawg.market.wfm

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties(prefix = "wfm")
data class WfmProperties(
    val baseUrl: String,
    val baseUrlLegacy: String,
    val userAgent: String,
    val limits: Limits,
    val platform: String = "pc",
    val crossplay: Boolean = false,
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

    @Bean
    fun wfmRateLimiter(props: WfmProperties): WfmRateLimiter = WfmRateLimiter(props.limits)

    /**
     * Everything about the transport that no call site may opt out of, applied to *every*
     * `RestClient.Builder` the context hands out — so a client added later is governed without
     * anyone remembering to wire it (R1.1). The service talks to warframe.market and nothing else,
     * so a blanket customizer is the right blast radius.
     *
     * The status handler covers `4xx`/`5xx` *except* `429`/`509`, which the interceptor below it
     * has already turned into a [ThrottledException] before the response could get this far.
     */
    @Bean
    fun wfmTransportCustomizer(limiter: WfmRateLimiter, props: WfmProperties): RestClientCustomizer =
        RestClientCustomizer { builder ->
            builder
                .requestInterceptor(WfmRateLimitInterceptor(limiter, props.limits.maxRetryAfter))
                .defaultStatusHandler({ it.isError }) { _, response -> throw WfmHttpException.of(response) }
        }

    @Bean
    fun wfmRestClient(builder: RestClient.Builder, props: WfmProperties): RestClient = builder
        .baseUrl(props.baseUrl)
        .defaultHeader("User-Agent", props.userAgent)
        .defaultHeader("Platform", props.platform)
        .defaultHeader("Crossplay", props.crossplay.toString())
        .build()
}
