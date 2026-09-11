package com.watchdawg.market.wfm

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * The v1 channel. It exists only for the three routes v2 never replaced — auctions,
 * `/items/{slug}/statistics` and `/items/{slug}/dropsources`; everything else in `docs/v1.yml` is
 * dead (`bruno/README.md` has the route-by-route table).
 *
 * It is a separate bean from [WfmClient] because the two speak different envelopes and different
 * property casing (R1.6), not because they need different governance: both are built from the same
 * customized `RestClient.Builder`, so pacing, the retry, the crossplay context and the typed error
 * boundary are the same on both. `RateLimitWiringTest` is what keeps that true.
 */
@Component
class WfmLegacyClient(@Qualifier(WfmConfig.LEGACY_CLIENT) private val client: RestClient) {

    fun getStatistics(slug: String): ItemStatistics = get("/items/{slug}/statistics", slug)

    private inline fun <reified T : Any> get(uri: String, vararg vars: Any): T = client.get()
        .uri(uri, *vars)
        .retrieve()
        .body<LegacyEnvelope<T>>()
        ?.payload
        ?: error("empty payload from $uri")
}
