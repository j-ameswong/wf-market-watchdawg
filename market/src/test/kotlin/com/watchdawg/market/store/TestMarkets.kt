package com.watchdawg.market.store

/**
 * A real market to hang fact rows on. The fact tables reference `market`, which references
 * `item`, so a test that writes events or quotes needs both.
 */
fun MarketResolver.marketFor(items: ItemRepository, slug: String = "serration"): Long {
    items.upsert(ItemRecord(id = slug, slug = slug))
    return resolve(MarketKey(slug))
}
