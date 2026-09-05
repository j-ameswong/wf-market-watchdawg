package com.watchdawg.market.sync

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.WfmClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ItemSync(
	private val wfm: WfmClient,
	private val items: ItemRepository,
) : CollectionSync {
	private val log = LoggerFactory.getLogger(javaClass)

	override val collection = "items"

	override fun refresh() {
		val fetched = wfm.getItems()
		fetched.forEach { dto ->
			items.upsert(
				ItemRecord(
					id = dto.id,
					slug = dto.slug,
					gameRef = dto.gameRef,
					ducats = dto.ducats,
					maxRank = dto.maxRank,
					vaulted = dto.vaulted,
					tags = dto.tags,
					updatedAt = dto.updatedAt,
				),
			)
		}
		log.info("upserted {} items", fetched.size)
	}
}
