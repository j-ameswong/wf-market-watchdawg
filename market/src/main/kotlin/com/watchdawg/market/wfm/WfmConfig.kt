package com.watchdawg.market.wfm

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@ConfigurationProperties(prefix = "wfm")
data class WfmProperties(
	val baseUrl: String,
	val baseUrlLegacy: String,
	val userAgent: String,
	val platform: String = "pc",
	val crossplay: Boolean = false,
    val rateLimit: Int = 3,
)

@Configuration
@EnableConfigurationProperties(WfmProperties::class)
class WfmConfig {

	@Bean
	fun wfmRestClient(builder: RestClient.Builder, props: WfmProperties): RestClient =
		builder
			.baseUrl(props.baseUrl)
			.defaultHeader("User-Agent", props.userAgent)
			.defaultHeader("Platform", props.platform)
			.defaultHeader("Crossplay", props.crossplay.toString())
			.defaultStatusHandler({ it.value() == 429 || it.value() == 509 }) { _, res ->
				throw RateLimitedException(res.headers.getFirst("Retry-After")?.toLongOrNull())
			}
			.build()
}

class RateLimitedException(val retryAfterSeconds: Long?) :
	RuntimeException("warframe.market rate limited, retry after ${retryAfterSeconds ?: "?"}s")
