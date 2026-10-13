package com.watchdawg.market.contracts

import com.watchdawg.market.watch.Priority
import com.watchdawg.market.wfm.ItemI18n
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

val CONTRACT_KINDS = setOf("riven", "lich", "sister")

/** Shared fields of the weapon and riven-attribute manifests. */
data class ContractWeapon(
    val id: String,
    val slug: String,
    val i18n: Map<String, ItemI18n> = emptyMap(),
    val positiveIsNegative: Boolean = false,
)
data class AuctionSearch(val auctions: List<Auction>)
data class AuctionEntry(val auction: Auction)
data class RivenAttribute(val urlName: String, val positive: Boolean, val value: BigDecimal)

/** Only item facts: no owner, notes, winner, reputation or profile data can enter persistence. */
data class AuctionItem(
    val type: String,
    val weaponUrlName: String,
    val attributes: List<RivenAttribute> = emptyList(),
    val name: String? = null,
    val reRolls: Int? = null,
    val polarity: String? = null,
    val masteryLevel: Int? = null,
    val modRank: Int? = null,
    val damage: Int? = null,
    val element: String? = null,
    val havingEphemera: Boolean? = null,
    val quirk: String? = null,
)

data class Auction(
    val id: String,
    val startingPrice: Int,
    val buyoutPrice: Int? = null,
    val topBid: Int? = null,
    val visible: Boolean,
    val closed: Boolean,
    val item: AuctionItem,
)

@ConfigurationProperties("watchdawg.contracts")
data class ContractProperties(
    val watches: List<ContractWatch> = emptyList(),
    val allWeapons: Boolean = false,
    val interval: Duration = Duration.ofHours(2),
    val initialDelay: Duration = Duration.ofSeconds(20),
    val detailBudget: Int = 20,
) {
    init {
        require(watches.map { it.name }.distinct().size == watches.size) { "duplicate contract watch name" }
        require(detailBudget >= 0 && !interval.isNegative && !interval.isZero && !initialDelay.isNegative)
    }
}

data class ContractWatch(
    val name: String,
    val kind: String,
    val weapon: String,
    val maxPrice: Int? = null,
    val positive: Map<String, BigDecimal> = emptyMap(),
    val negative: Set<String> = emptySet(),
    val noNegative: Boolean = false,
    val element: String? = null,
    val minDamage: Int? = null,
    val ephemera: Boolean? = null,
    val maxRerolls: Int? = null,
    val priority: Priority = Priority.DEFAULT,
    val cooldown: Duration = Duration.ofHours(1),
    val topic: String = "default",
) {
    init {
        require(name.isNotBlank() && kind in CONTRACT_KINDS && weapon.matches(Regex("[a-z0-9_]+"))) {
            "invalid contract watch $name"
        }
        require(maxPrice == null || maxPrice > 0) { "contract max-price must be positive" }
        require(!cooldown.isNegative && topic.matches(Regex("[a-z][a-z0-9]{0,19}"))) {
            "invalid cooldown/topic for $name"
        }
        require(!(noNegative && negative.isNotEmpty())) { "negative and no-negative conflict for $name" }
        require(minDamage == null || minDamage in 25..60) { "damage outside 25..60 for $name" }
        require(maxRerolls == null || maxRerolls >= 0)
        require(
            element == null ||
                element in setOf("impact", "heat", "cold", "electricity", "toxin", "magnetic", "radiation"),
        ) {
            "unknown element '$element' for $name"
        }
        require(kind != "riven" || (element == null && minDamage == null && ephemera == null)) {
            "nemesis filters cannot select a riven: $name"
        }
        require(kind == "riven" || (positive.isEmpty() && negative.isEmpty() && !noNegative && maxRerolls == null)) {
            "riven filters cannot select $kind: $name"
        }
        require(positive.values.all { it.signum() >= 0 }) { "negative positive-attribute threshold: $name" }
    }

    fun matches(auction: Auction, invertedAttributes: Set<String> = emptySet()): Boolean {
        val item = auction.item
        return item.type == kind && item.weaponUrlName == weapon && auction.visible && !auction.closed &&
            auction.buyoutPrice != null && maxPrice != null && auction.buyoutPrice <= maxPrice &&
            positive.all { (name, min) ->
                item.attributes.any {
                    val benefit = if (name in invertedAttributes) it.value.negate() else it.value
                    it.urlName == name && it.positive && benefit >= min
                }
            } &&
            negative.all { name -> item.attributes.any { it.urlName == name && !it.positive } } &&
            (!noNegative || item.attributes.none { !it.positive }) &&
            (element == null || element == item.element) &&
            (minDamage == null || (item.damage != null && item.damage >= minDamage)) &&
            (ephemera == null || ephemera == item.havingEphemera) &&
            (maxRerolls == null || (item.reRolls != null && item.reRolls <= maxRerolls))
    }
}
