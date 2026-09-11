package com.watchdawg.market.wfm

// Jackson 3 moved databind to `tools.jackson`, but the annotations stayed where they were.
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant

/**
 * v1's envelope (R1.6): a `payload`, and an `include` present only when the call asked for one.
 * Nothing like v2's `apiVersion`/`data`/`error` triple — v1 states failure in the HTTP status
 * alone, so T5's typed status handler is the whole error boundary on this channel.
 *
 * [include] stays an untyped tree because no route we call consumes it yet. The item manifest it
 * carries duplicates what C3 already takes from `/v2/items`, so typing it now would be modelling a
 * shape nothing reads.
 */
data class LegacyEnvelope<T>(val payload: T? = null, val include: JsonNode? = null)

/**
 * `GET /v1/items/{slug}/statistics` — price history, and the only source of real traded prices
 * anywhere in the API (SPEC 2.2). Shape recorded in `docs/v1-statistics.md`; there is no v2
 * equivalent and v1's own OpenAPI document omits the route entirely.
 */
data class ItemStatistics(
    val statisticsClosed: StatisticsWindows<ClosedStat>,
    val statisticsLive: StatisticsWindows<LiveStat>,
)

/**
 * The two windows every section is reported in. The upstream names them by span and the spans
 * differ in granularity — `48hours` buckets hourly, `90days` daily — which is the dimension the
 * natural key actually turns on, so the properties are named for it.
 */
data class StatisticsWindows<T>(
    @JsonProperty("48hours") val hourly: List<T> = emptyList(),
    @JsonProperty("90days") val daily: List<T> = emptyList(),
)

/**
 * A bucket of completed trades. Sparse — a bucket exists only where trades occurred — and has no
 * side, because a closed trade has none.
 *
 * Every price binds as [BigDecimal]: the upstream sends `150` and `80.0` for the same field
 * depending on the value. An `Int` binding does not fail on a fractional one — Jackson truncates
 * it silently, so `wa_price` 45.417 would land as 45 and no test downstream would notice.
 */
data class ClosedStat(
    val id: String,
    val datetime: Instant,
    val volume: Int,
    val openPrice: BigDecimal,
    val closedPrice: BigDecimal,
    val minPrice: BigDecimal,
    val maxPrice: BigDecimal,
    val avgPrice: BigDecimal,
    /** Volume-weighted. */
    val waPrice: BigDecimal,
    val median: BigDecimal,
    /** Absent on the earliest buckets of a series, where there is nothing yet to average over. */
    val movingAvg: BigDecimal? = null,
    val donchTop: BigDecimal,
    val donchBot: BigDecimal,
    /*
     * Subtype dimensions: the same ones orders are split by, which is what makes a row key to a
     * *market* rather than to an item (SPEC 2.3). A field is absent, not null, when the item has no
     * such dimension. v1 spells two of them differently from v2 -- `mod_rank` for `rank`,
     * `amber_stars` for `amberStars` -- and SPEC 10's first open question is whether `mod_rank`
     * carries what v2 calls `charges` for requiem mods. Nothing here maps them; C7 does, once that
     * question is settled.
     */
    val modRank: Int? = null,
    val subtype: String? = null,
    val amberStars: Int? = null,
    val cyanStars: Int? = null,
)

/**
 * A bucket of *currently listed* orders, split by side.
 *
 * [volume] here counts open orders, not trades, and runs orders of magnitude above the closed
 * series for the same bucket. The two are not comparable and must not be summed.
 */
data class LiveStat(
    val id: String,
    val datetime: Instant,
    /** `buy` or `sell`. */
    val orderType: String,
    val volume: Int,
    val minPrice: BigDecimal,
    val maxPrice: BigDecimal,
    val avgPrice: BigDecimal,
    val waPrice: BigDecimal,
    val median: BigDecimal,
    val movingAvg: BigDecimal? = null,
    /** See the note on [ClosedStat]. */
    val modRank: Int? = null,
    val subtype: String? = null,
    val amberStars: Int? = null,
    val cyanStars: Int? = null,
)
