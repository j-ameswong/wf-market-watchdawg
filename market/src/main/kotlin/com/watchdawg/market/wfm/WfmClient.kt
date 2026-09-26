package com.watchdawg.market.wfm

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/** The v2 channel. [WfmLegacyClient] is the v1 one, and the qualifier is what tells them apart. */
@Component
class WfmClient(@Qualifier(WfmConfig.V2_CLIENT) private val client: RestClient) {
    fun getVersions(): Versions = get("/versions")

    fun getItems(): List<Item> = get("/items")

    /** One item in full. It carries fields the list leaves out, such as `tradable` and `rarity`. */
    fun getItem(slug: String): Item = get("/item/{slug}", slug)

    /** Every visible order for one item, across all its markets: a full book (R4.1). */
    fun getOrders(slug: String): List<Order> = get("/orders/item/{slug}", slug)

    /** Up to 500 orders created in the last 4h by users online now. Partial, never a book (R4.5). */
    fun getRecentOrders(): List<Order> = get("/orders/recent")

    private inline fun <reified T : Any> get(uri: String, vararg vars: Any): T {
        val envelope = client.get()
            .uri(uri, *vars)
            .retrieve()
            .body<Envelope<T>>()
            ?: error("empty body from $uri")
        return envelope.data ?: error("wfm api error on $uri: ${envelope.error}")
    }
}
