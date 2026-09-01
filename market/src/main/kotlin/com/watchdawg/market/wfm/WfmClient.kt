package com.watchdawg.market.wfm

import com.watchdawg.market.store.ItemRecord
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class WfmClient(private val client: RestClient) {
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
