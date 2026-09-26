package com.watchdawg.market.watch

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.MarketKey
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Watches bind from YAML and are checked against the catalog without Spring or a database. */
class WatchesTest {

    @Test
    fun `a watch binds from properties with no YAML library of its own`() {
        val props = bind(
            mapOf(
                "watchdawg.watches[0].name" to "cheap-khra",
                "watchdawg.watches[0].item" to "khra",
                "watchdawg.watches[0].rank" to "3",
                "watchdawg.watches[0].max-unit-price" to "12.5",
                "watchdawg.watches[0].priority" to "high",
                "watchdawg.watches[0].topic" to "trades",
            ),
        )

        assertEquals(
            WatchEntry(
                name = "cheap-khra",
                item = "khra",
                rank = "3",
                maxUnitPrice = BigDecimal("12.5"),
                priority = Priority.HIGH,
                topic = "trades",
            ),
            props.watches.single(),
        )
    }

    @Test
    fun `the committed watches file resolves and names only logical topics`() {
        val source = YamlPropertySourceLoader().load("watches", FileSystemResource("src/main/resources/watches.yaml"))
        val props = Binder(
            ConfigurationPropertySources.from(source),
        ).bind("watchdawg", WatchProperties::class.java).get()

        val watches = loadWatches(props.watches, CATALOG::get) { error("the catalog is complete") }

        assertTrue(watches.isNotEmpty())
        // A real topic is a high-entropy credential (R10.6); a logical one is a short word.
        watches.forEach { assertTrue(it.topic.matches(LOGICAL_TOPIC), "watch ${it.name} names topic '${it.topic}'") }
    }

    @Test
    fun `a missing slug refreshes the catalog once, then resolves`() {
        val catalog = mutableMapOf<String, ItemRecord>()
        var refreshes = 0

        val watches = loadWatches(listOf(entry(item = "khra", rank = "any")), catalog::get) {
            refreshes++
            catalog["khra"] = KHRA
        }

        assertEquals(1, refreshes)
        assertEquals("khra", watches.single().slug)
    }

    @Test
    fun `a slug still missing after the refresh fails, naming the watch and the slug (R9a2)`() {
        var refreshes = 0
        val e = assertFailsWith<InvalidWatchException> {
            loadWatches(listOf(entry(name = "typo", item = "khraa")), CATALOG::get) { refreshes++ }
        }

        assertEquals(1, refreshes)
        assertContains(e.message!!, "'typo' (khraa)")
    }

    @Test
    fun `an omitted dimension the item has fails, naming the watch and the dimension (R9a4)`() {
        val e = assertFailsWith<InvalidWatchException> { resolve(entry(name = "no-rank", item = "khra")) }

        assertContains(e.message!!, "'no-rank'")
        assertContains(e.message!!, "rank")
    }

    @Test
    fun `a dimension the item lacks fails when named`() {
        val e = assertFailsWith<InvalidWatchException> {
            resolve(entry(name = "ranked-set", item = "frost_prime_set", rank = "0"))
        }

        assertContains(e.message!!, "'ranked-set'")
        assertContains(e.message!!, "no rank")
    }

    @Test
    fun `any is accepted, and selects every value but none`() {
        val watch = resolve(entry(item = "serration", subtype = "any", rank = "any"))

        assertEquals(Dimension.Any, watch.rank)
        assertTrue(watch.selects(MarketKey("serration-id", subtype = "atragraph", rank = 10)))
        assertTrue(watch.selects(MarketKey("serration-id", subtype = "regular", rank = 0)))
        assertFalse(watch.selects(MarketKey("serration-id", subtype = "regular", rank = null)))
    }

    @Test
    fun `a named value selects that market only, and rank 0 is not no rank`() {
        val watch = resolve(entry(item = "serration", subtype = "regular", rank = "0"))

        assertTrue(watch.selects(MarketKey("serration-id", subtype = "regular", rank = 0)))
        assertFalse(watch.selects(MarketKey("serration-id", subtype = "regular", rank = 10)))
        assertFalse(watch.selects(MarketKey("serration-id", subtype = "atragraph", rank = 0)))
        assertFalse(watch.selects(MarketKey("other-id", subtype = "regular", rank = 0)))
    }

    @Test
    fun `an item without dimensions selects its dimensionless market`() {
        val watch = resolve(entry(item = "frost_prime_set"))

        assertTrue(watch.selects(MarketKey("frost-id")))
        assertFalse(watch.selects(MarketKey("frost-id", rank = 0)))
    }

    @Test
    fun `values outside what the item has fail`() {
        assertFailsWith<InvalidWatchException> { resolve(entry(item = "khra", rank = "4")) }
        assertFailsWith<InvalidWatchException> { resolve(entry(item = "khra", rank = "max")) }
        assertFailsWith<InvalidWatchException> { resolve(entry(item = "serration", subtype = "shiny", rank = "0")) }
    }

    @Test
    fun `charges are checked once the detail sweep knows them, and taken as written before`() {
        val unknown = KHRA.copy(slug = "requiem", maxRank = null, detailSyncedAt = null)
        val none = unknown.copy(detailSyncedAt = Instant.EPOCH)
        val three = none.copy(maxCharges = 3)

        assertEquals(Dimension.Is(2), resolve(entry(item = "requiem", charges = "2")) { unknown }.charges)
        assertEquals(Dimension.Absent, resolve(entry(item = "requiem")) { unknown }.charges)
        assertFailsWith<InvalidWatchException> { resolve(entry(item = "requiem", charges = "2")) { none } }
        assertFailsWith<InvalidWatchException> { resolve(entry(item = "requiem")) { three } }
        assertEquals(Dimension.Any, resolve(entry(item = "requiem", charges = "any")) { three }.charges)
    }

    @Test
    fun `two watches with one name fail`() {
        val e = assertFailsWith<InvalidWatchException> {
            resolveWatches(listOf(entry(name = "twin"), entry(name = "twin")), CATALOG::get)
        }
        assertContains(e.message!!, "'twin'")
    }

    @Test
    fun `a logical topic an environment variable cannot name fails`() {
        assertFailsWith<InvalidWatchException> { resolve(entry().copy(topic = "trades-high")) }
    }

    @Test
    fun `a threshold of zero or less fails`() {
        assertFailsWith<InvalidWatchException> { resolve(entry(maxUnitPrice = BigDecimal.ZERO)) }
    }

    private fun resolve(entry: WatchEntry, itemOf: (String) -> ItemRecord? = CATALOG::get): Watch =
        assertIs<Resolution.Resolved>(resolveWatches(listOf(entry), itemOf)).watches.single()

    private fun bind(properties: Map<String, String>): WatchProperties =
        Binder(MapConfigurationPropertySource(properties)).bind("watchdawg", WatchProperties::class.java).get()

    private companion object {
        val LOGICAL_TOPIC = Regex("[a-z][a-z0-9]{0,19}")

        val KHRA = ItemRecord(id = "khra-id", slug = "khra", name = "Khra", maxRank = 3)

        /** The dimensions of the captured `/v2/items` entries these tests use. */
        val CATALOG = listOf(
            KHRA,
            ItemRecord(
                id = "serration-id",
                slug = "serration",
                subtypes = listOf("regular", "atragraph"),
                maxRank = 10,
            ),
            ItemRecord(id = "frost-id", slug = "frost_prime_set"),
        ).associateBy { it.slug }

        fun entry(
            name: String = "w",
            item: String = "frost_prime_set",
            subtype: String? = null,
            rank: String? = null,
            charges: String? = null,
            maxUnitPrice: BigDecimal = BigDecimal.TEN,
        ) = WatchEntry(name, item, subtype, rank, charges, maxUnitPrice = maxUnitPrice)
    }
}
