package com.watchdawg.market.sync

import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmHttpException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Fills the catalog fields `/v2/items` leaves out (`tradable`, `rarity` and `maxCharges`) from
 * `/v2/item/{slug}`, one item at a time (R3.1).
 *
 * Each run fetches one batch: items never fetched first, then items whose details are older than
 * their last catalog refresh. A catalog change therefore costs one re-sweep and nothing more, at
 * the pace the batch size and interval set. Every call goes through the rate limiter like any
 * other.
 *
 * This runs apart from [CollectionSyncScheduler] on purpose. A whole-catalog sweep takes hours at
 * this pace, far too long to hold the catalog refresh's transaction open.
 */
@Component
class ItemDetailSync(
    private val wfm: WfmClient,
    private val items: ItemRepository,
    @Value("\${wfm.sync.item-details.batch-size}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${wfm.sync.item-details.initial-delay}",
        fixedDelayString = "\${wfm.sync.item-details.interval}",
    )
    fun sweep() {
        for (item in items.needingDetail(batchSize)) {
            val detail = try {
                wfm.getItem(item.slug)
            } catch (e: WfmHttpException) {
                if (e.status.value() == HttpStatus.NOT_FOUND.value()) {
                    // Gone upstream since the last catalog refresh. Keep what we knew, and stop
                    // asking until the catalog changes again.
                    log.warn("{} has no item page any more; keeping its old details", item.slug)
                    items.markDetailChecked(item.id)
                    continue
                }
                log.warn("item detail sweep stopped at {}: {}", item.slug, e.message)
                return
            } catch (e: Exception) {
                // A throttle or a transport failure: spending more budget now would not help. The
                // rest of the batch is still due, so the next run picks it up.
                log.warn("item detail sweep stopped at {}: {}", item.slug, e.message)
                return
            }
            items.recordDetail(item.id, detail.tradable, detail.rarity, detail.maxCharges)
        }
    }
}
