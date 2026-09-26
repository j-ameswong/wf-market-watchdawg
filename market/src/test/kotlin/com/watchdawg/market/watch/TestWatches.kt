package com.watchdawg.market.watch

import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.store.OrderStore
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.Order
import com.watchdawg.market.wfm.OrderOwner
import com.watchdawg.market.wfm.OrderType
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal

/** Khra, ranked 0 to 3: the item the alert tests watch. */
val KHRA = ItemRecord(id = "khra-id", slug = "khra", name = "Khra", maxRank = 3)

/** Resolves [entries] against the catalog in [items], which must already hold their items. */
fun ItemRepository.watchesOf(vararg entries: WatchEntry): Watches {
    upsert(KHRA)
    val resolution = resolveWatches(entries.toList(), ::findBySlug)
    return Watches((resolution as Resolution.Resolved).watches)
}

fun khraWatch(name: String = "cheap-khra", maxUnitPrice: Int = 12, rank: String = "3", topic: String = "default") =
    WatchEntry(
        name,
        "khra",
        rank = rank,
        maxUnitPrice = BigDecimal(maxUnitPrice),
        priority = Priority.HIGH,
        topic = topic,
    )

/** An ingest whose rules see [watches] rather than the context's (empty) ones. */
fun ingestWith(
    watches: Watches,
    store: OrderStore,
    markets: MarketResolver,
    signals: SignalStore,
    transactions: PlatformTransactionManager,
) = OrderIngest(store, markets, Alerts(watches, signals), transactions)

/** A visible Khra order, sold by default, from an owner in game by default. */
fun khraOrder(
    id: String,
    platinum: Int,
    rank: Int = 3,
    online: Boolean = true,
    type: OrderType = OrderType.SELL,
    perTrade: Int? = null,
) = Order(
    id = id,
    type = type,
    platinum = platinum,
    quantity = 1,
    perTrade = perTrade,
    rank = rank,
    visible = true,
    itemId = KHRA.id,
    user = OrderOwner(platform = "pc", status = if (online) "ingame" else "offline"),
)
