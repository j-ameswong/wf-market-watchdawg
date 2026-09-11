package com.watchdawg.market.wfm

// Jackson 3 moved databind to `tools.jackson`, but the annotations stayed where they were.
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant

/**
 * v1's envelope (R1.6): a `payload`, plus an `include` that is only present when the call asked
 * for one.
 *
 * There is no equivalent of v2's `apiVersion`/`data`/`error` triple. v1 states failure in the HTTP
 * status alone, so on this channel T5's typed status handler is the whole error boundary.
 *
 * [include] stays an untyped tree because no route we call reads it yet. The item manifest it
 * carries duplicates what C3 already takes from `/v2/items`, so typing it now would model a shape
 * nothing looks at.
 */
data class LegacyEnvelope<T>(val payload: T? = null, val include: JsonNode? = null)

/**
 * `GET /v1/items/{slug}/statistics`: price history, and the only source anywhere in the API of
 * prices things actually traded at (SPEC 2.2).
 *
 * There is no v2 equivalent, and v1's own OpenAPI document omits the route entirely, so the shape
 * is recorded by hand in `docs/v1-statistics.md`.
 */
data class ItemStatistics(
    val statisticsClosed: StatisticsWindows<ClosedStat>,
    val statisticsLive: StatisticsWindows<LiveStat>,
)

/**
 * The two windows every section is reported in.
 *
 * The upstream names them by span, but the spans differ in granularity: `48hours` buckets hourly,
 * `90days` daily. Granularity is the dimension the natural key actually turns on, so the
 * properties are named for that rather than for the span.
 */
data class StatisticsWindows<T>(
    @JsonProperty("48hours") val hourly: List<T> = emptyList(),
    @JsonProperty("90days") val daily: List<T> = emptyList(),
)

/**
 * A bucket of completed trades. The series is sparse: a bucket exists only where trades occurred.
 * There is no buy/sell side here, because a completed trade does not have one.
 *
 * Every price binds as [BigDecimal] on purpose. The upstream sends `150` for one value and `80.0`
 * for another in the same field. An `Int` binding would not fail on the fractional ones; Jackson
 * truncates silently, so `wa_price` 45.417 would land as 45 and nothing downstream would notice.
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
     * Subtype dimensions. These are the same ones orders are split by, which is what makes a row
     * key to a *market* rather than to an item (SPEC 2.3). Where an item has no such dimension the
     * field is absent rather than null.
     *
     * v1 spells two of them differently from v2: `mod_rank` for `rank`, `amber_stars` for
     * `amberStars`. SPEC 10's first open question is whether `mod_rank` also carries what v2 calls
     * `charges` for requiem mods. Nothing here maps them; C7 will, once that is settled.
     */
    val modRank: Int? = null,
    val subtype: String? = null,
    val amberStars: Int? = null,
    val cyanStars: Int? = null,
)

/**
 * A bucket of orders *currently listed*, split by buy/sell side.
 *
 * Watch out for [volume]: here it counts open orders rather than trades, and it runs orders of
 * magnitude above the closed series for the same bucket. The two are not comparable and must never
 * be summed.
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
