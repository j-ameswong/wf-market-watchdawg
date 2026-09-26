package com.watchdawg.market.ingest

import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.wfm.WfmClient
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Fetches one item's full book and reconciles it (source `book`). Nothing calls this on a schedule
 * yet; the poll loop (C6) will.
 *
 * The book is dated when it was requested, the earliest moment its snapshot could have been taken.
 * Anything the socket reports after that is newer than the book, so the book cannot vanish it
 * (R4.10).
 */
@Component
class OrderBookPoll(
    private val wfm: WfmClient,
    private val items: ItemRepository,
    private val ingest: OrderIngest,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** A failed fetch throws before anything is written, and leaves every market as it was (R4.12). */
    fun poll(slug: String): BookOutcome {
        val item = requireNotNull(items.findBySlug(slug)) { "no item $slug in the catalog" }
        val requestedAt = clock.instant()
        return ingest.reconcileBook(item.id, wfm.getOrders(slug), requestedAt)
    }
}
