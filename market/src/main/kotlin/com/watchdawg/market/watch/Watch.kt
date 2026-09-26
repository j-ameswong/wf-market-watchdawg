package com.watchdawg.market.watch

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.MarketKey
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * The watches, as `watches.yaml` declares them (R9a.1). Nothing here is checked against the
 * catalog yet; [resolveWatches] does that and turns each entry into a [Watch].
 */
@ConfigurationProperties(prefix = "watchdawg")
data class WatchProperties(val watches: List<WatchEntry> = emptyList())

/**
 * One watch as written. A dimension is a value, `any`, or omitted, and whether it may be omitted
 * depends on the item, so each binds as text and is checked later (R9a.4).
 */
data class WatchEntry(
    val name: String,
    val item: String,
    val subtype: String? = null,
    val rank: String? = null,
    val charges: String? = null,
    val amberStars: String? = null,
    val cyanStars: String? = null,
    /** Platinum a unit. A sell listing at or below it qualifies. */
    val maxUnitPrice: BigDecimal,
    val priority: Priority = Priority.DEFAULT,
    /** After a signal is admitted, only a cheaper listing is admitted for this long (R9a.5). */
    val cooldown: Duration = Duration.ofHours(1),
    /** A logical topic. The real ntfy topic comes from the environment, never from this file (R10.6). */
    val topic: String = "default",
)

/** ntfy's five priorities, by the names ntfy gives them (R10.5). */
enum class Priority(val ntfy: Int) {
    MIN(1),
    LOW(2),
    DEFAULT(3),
    HIGH(4),
    MAX(5),
}

/** How a watch constrains one market dimension. */
sealed interface Dimension<out T> {
    /** The item has no such dimension, so the market's value is null. */
    data object Absent : Dimension<Nothing>

    /** The watch accepts every value the item has. */
    data object Any : Dimension<Nothing>

    data class Is<T>(val value: T) : Dimension<T>

    fun accepts(value: kotlin.Any?): Boolean = when (this) {
        Absent -> value == null
        Any -> value != null
        is Is -> value == this.value
    }
}

/** A watch checked against the catalog: its item exists and each dimension is stated. */
data class Watch(
    val name: String,
    val itemId: String,
    val slug: String,
    /** English display name, for the notification. Falls back to the slug. */
    val itemName: String,
    val subtype: Dimension<String>,
    val rank: Dimension<Int>,
    val charges: Dimension<Int>,
    val amberStars: Dimension<Int>,
    val cyanStars: Dimension<Int>,
    val maxUnitPrice: BigDecimal,
    val priority: Priority,
    val cooldown: Duration,
    val topic: String,
) {
    /** Whether a market of this watch's item is one the watch selects. */
    fun selects(market: MarketKey): Boolean = market.itemId == itemId &&
        subtype.accepts(market.subtype) &&
        rank.accepts(market.rank) &&
        charges.accepts(market.charges) &&
        amberStars.accepts(market.amberStars) &&
        cyanStars.accepts(market.cyanStars)
}

/** `watches.yaml` is wrong. The message names the watch and what is wrong with it (R9a.2). */
class InvalidWatchException(message: String) : RuntimeException(message)

/** What resolving the watches against the catalog found. */
sealed interface Resolution {
    data class Resolved(val watches: List<Watch>) : Resolution

    /** These slugs are not in the catalog, which may only be out of date. */
    data class MissingItems(val slugs: Set<String>) : Resolution
}

/**
 * Checks [entries] against the catalog, which [itemOf] reads by slug.
 *
 * A dimension the item has must be named, as a value or as `any`, and one it lacks must not be:
 * neither "no rank" nor "any rank" is ever guessed from an omission (SPEC §10 Q5). Charges are the
 * exception while the item's detail is unknown, because only the detail sweep learns them; a
 * watch may then name them or not, unchecked.
 *
 * @throws InvalidWatchException naming the first bad entry, for anything a catalog refresh cannot
 *   fix. Unknown slugs are returned as [Resolution.MissingItems] instead.
 */
fun resolveWatches(entries: List<WatchEntry>, itemOf: (String) -> ItemRecord?): Resolution {
    entries.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        throw InvalidWatchException("two watches are named '$it'; watch names must be unique")
    }
    val items = entries.associate { it.item to itemOf(it.item) }
    val missing = items.filterValues { it == null }.keys
    if (missing.isNotEmpty()) return Resolution.MissingItems(missing)
    return Resolution.Resolved(entries.map { it.resolve(items.getValue(it.item)!!) })
}

private fun WatchEntry.resolve(item: ItemRecord): Watch {
    fun fail(problem: String): Nothing = throw InvalidWatchException("watch '$name' on ${item.slug}: $problem")

    if (name.isBlank()) throw InvalidWatchException("a watch on ${item.slug} has no name")
    if (maxUnitPrice.signum() <= 0) fail("max-unit-price must be above zero, not $maxUnitPrice")
    if (!topic.matches(LOGICAL_TOPIC)) {
        fail("topic '$topic' must be lowercase letters and digits, to map from WATCHDAWG_NOTIFY_TOPICS_<NAME>")
    }
    if (cooldown.isNegative) fail("cooldown is negative")

    val chargesKnown = item.maxCharges != null || item.detailSyncedAt != null
    return Watch(
        name = name,
        itemId = item.id,
        slug = item.slug,
        itemName = item.name ?: item.slug,
        subtype = dimension("subtype", subtype, item.subtypes.takeIf { it.isNotEmpty() }, ::fail) { raw, values ->
            raw.takeIf { it in values } ?: fail("subtype '$raw' is not one of ${values.joinToString()}, or any")
        },
        rank = numeric("rank", rank, item.maxRank, ::fail),
        charges = if (chargesKnown || charges == null) {
            numeric("charges", charges, item.maxCharges, ::fail)
        } else if (charges.equals(ANY, ignoreCase = true)) {
            Dimension.Any
        } else {
            parseNumber("charges", charges, Int.MAX_VALUE, ::fail)
        },
        amberStars = numeric("amber-stars", amberStars, item.maxAmberStars, ::fail),
        cyanStars = numeric("cyan-stars", cyanStars, item.maxCyanStars, ::fail),
        maxUnitPrice = maxUnitPrice,
        priority = priority,
        cooldown = cooldown,
        topic = topic,
    )
}

private fun numeric(key: String, raw: String?, max: Int?, fail: (String) -> Nothing): Dimension<Int> =
    dimension(key, raw, max, fail) { value, limit -> parseNumber(key, value, limit, fail).value }

private fun parseNumber(key: String, raw: String, max: Int, fail: (String) -> Nothing): Dimension.Is<Int> {
    val value = raw.toIntOrNull() ?: fail("$key must be a number or any, not '$raw'")
    if (value !in 0..max) fail("$key $value is outside 0..$max")
    return Dimension.Is(value)
}

/**
 * [range] is what the item has for this dimension, or null when it has none. [parse] turns a
 * named value into a checked one.
 */
private fun <R : kotlin.Any, T> dimension(
    key: String,
    raw: String?,
    range: R?,
    fail: (String) -> Nothing,
    parse: (String, R) -> T,
): Dimension<T> = when {
    range == null && raw == null -> Dimension.Absent
    range == null -> fail("the item has no $key, so the watch cannot name one")
    raw == null -> fail("the item has a $key, so name one or write $key: any")
    raw.equals(ANY, ignoreCase = true) -> Dimension.Any
    else -> Dimension.Is(parse(raw, range))
}

private const val ANY = "any"

private val LOGICAL_TOPIC = Regex("[a-z][a-z0-9]{0,19}")
