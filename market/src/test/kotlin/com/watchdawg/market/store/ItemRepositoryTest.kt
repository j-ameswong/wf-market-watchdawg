package com.watchdawg.market.store

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ItemRepositoryTest {

	@Autowired lateinit var items: ItemRepository

	@Autowired lateinit var versions: CollectionVersionRepository

	@Test
	fun `upsert inserts then updates the same row`() {
		val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		val mirage = ItemRecord(
			id = "54a73e65e779893a797fff9c",
			slug = "mirage_prime_set",
			ducats = 0,
			maxRank = 0,
			tags = listOf("set", "prime"),
			updatedAt = now,
		)

		items.upsert(mirage)
		assertEquals(mirage, items.findBySlug("mirage_prime_set"))

		items.upsert(mirage.copy(vaulted = true, ducats = 15))
		val reloaded = items.findById(mirage.id).orElseThrow()
		assertTrue(reloaded.vaulted)
		assertEquals(15, reloaded.ducats)
		assertEquals(1, items.count())
	}

	@Test
	fun `collection version round-trips with a natural id`() {
		val record = CollectionVersionRecord(
			name = "items",
			hash = "d41d8cd98f00b204e9800998ecf8427e",
			updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
			new = true,
		)

		versions.save(record)
		assertEquals(record.hash, versions.findById("items").orElseThrow().hash)

		versions.save(record.copy(hash = "changed", new = false))
		assertEquals("changed", versions.findById("items").orElseThrow().hash)
	}
}
