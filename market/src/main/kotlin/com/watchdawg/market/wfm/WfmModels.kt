package com.watchdawg.market.wfm

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.RoundingMode.HALF_EVEN
import java.time.Instant

data class Envelope<T>(val apiVersion: String, val data: T? = null, val error: ApiError? = null)

data class ApiError(val request: List<String>? = null, val inputs: Map<String, String>? = null)

// GET /v2/versions
data class Versions(val apps: VersionApps, val collections: VersionCollections, val updatedAt: Instant)

data class VersionApps(
    val ios: String? = null,
    val android: String? = null,
    val minIos: String? = null,
    val minAndroid: String? = null,
)

data class VersionCollections(
    val items: String? = null,
    val rivens: String? = null,
    val liches: String? = null,
    val sisters: String? = null,
    val missions: String? = null,
    val npcs: String? = null,
    val locations: String? = null,
)

/**
 * One entry of `GET /v2/items`.
 *
 * The v2 model marks every field after [slug] optional, so each binds as null or empty when absent
 * rather than as a value that looks like data (R3.1, R7.8). There is no `updatedAt` on an item.
 */
data class Item(
    val id: String,
    val slug: String,
    val gameRef: String? = null,
    val tags: List<String> = emptyList(),
    /** Keyed by language code. Notifications use [english]. */
    val i18n: Map<String, ItemI18n> = emptyMap(),
    /** Present only on items traded in variants, such as relic refinements. */
    val subtypes: List<String> = emptyList(),
    val maxRank: Int? = null,
    val maxCharges: Int? = null,
    val maxAmberStars: Int? = null,
    val maxCyanStars: Int? = null,
    val ducats: Int? = null,
    val vaulted: Boolean = false,
    val bulkTradable: Boolean? = null,
    val tradable: Boolean? = null,
    val rarity: String? = null,
) {
    val english: ItemI18n? get() = i18n["en"]
}

data class ItemI18n(val name: String? = null, val icon: String? = null)

/**
 * One order, as `/v2/orders/item/{slug}`, `/v2/orders/recent` and the socket carry it.
 *
 * [platinum] prices one lot of [perTrade] units, so orders are compared by [unitPrice]. A dimension
 * the item lacks is absent and binds as null; `rank: 0` is a real value (R7.8).
 *
 * Only what ingest reads is declared. Of the owner that is the platform and online status, never a
 * name or id (SPEC 9).
 */
data class Order(
    val id: String,
    val type: OrderType,
    val platinum: Int,
    val quantity: Int,
    val perTrade: Int? = null,
    val subtype: String? = null,
    val rank: Int? = null,
    val charges: Int? = null,
    val amberStars: Int? = null,
    val cyanStars: Int? = null,
    val visible: Boolean,
    val itemId: String? = null,
    val user: OrderOwner? = null,
) {
    /** Platinum per unit, to four places. An order without a usable lot size sells one at a time. */
    val unitPrice: BigDecimal
        get() = platinum.toBigDecimal().divide((perTrade?.takeIf { it > 0 } ?: 1).toBigDecimal(), 4, HALF_EVEN)

    /** `offline` covers invisible owners too; upstream does not tell them apart. */
    val ownerOnline: Boolean get() = user?.status in ONLINE_STATUSES

    private companion object {
        val ONLINE_STATUSES = setOf("online", "ingame")
    }
}

enum class OrderType(val db: String) {
    @JsonProperty("buy")
    BUY("buy"),

    @JsonProperty("sell")
    SELL("sell"),
    ;

    companion object {
        fun fromDb(value: String): OrderType = entries.first { it.db == value }
    }
}

data class OrderOwner(val platform: String? = null, val status: String? = null)

fun VersionCollections.asMap(): Map<String, String> = buildMap {
    items?.let { put("items", it) }
    rivens?.let { put("rivens", it) }
    liches?.let { put("liches", it) }
    sisters?.let { put("sisters", it) }
    missions?.let { put("missions", it) }
    npcs?.let { put("npcs", it) }
    locations?.let { put("locations", it) }
}
