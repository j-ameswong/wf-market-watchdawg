package com.watchdawg.market.sync

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.Item
import com.watchdawg.market.wfm.WfmClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ItemSync(private val wfm: WfmClient, private val items: ItemRepository) : CollectionSync {
    private val log = LoggerFactory.getLogger(javaClass)

    override val collection = "items"

    override fun refresh() {
        val fetched = wfm.getItems()
        fetched.forEach { items.upsert(it.toRecord()) }
        log.info("upserted {} items", fetched.size)
    }
}

private fun Item.toRecord() = ItemRecord(
    id = id,
    slug = slug,
    name = english?.name,
    icon = english?.icon,
    gameRef = gameRef,
    tags = tags,
    subtypes = subtypes,
    maxRank = maxRank,
    maxAmberStars = maxAmberStars,
    maxCyanStars = maxCyanStars,
    ducats = ducats,
    vaulted = vaulted,
    bulkTradable = bulkTradable,
)
