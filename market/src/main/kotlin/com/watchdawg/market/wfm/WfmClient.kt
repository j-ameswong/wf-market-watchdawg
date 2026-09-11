package com.watchdawg.market.wfm

import com.watchdawg.market.store.ItemRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/** The v2 channel. [WfmLegacyClient] is the v1 one; the qualifier is what keeps them apart. */
@Component
class WfmClient(@Qualifier(WfmConfig.V2_CLIENT) private val client: RestClient) {
    fun getVersions(): Versions = get("/versions")

    fun getItems(): List<Items> = get("/items")

    private inline fun <reified T : Any> get(uri: String, vararg vars: Any): T {
        val envelope = client.get()
            .uri(uri, *vars)
            .retrieve()
            .body<Envelope<T>>()
            ?: error("empty body from $uri")
        return envelope.data ?: error("wfm api error on $uri: ${envelope.error}")
    }
}
