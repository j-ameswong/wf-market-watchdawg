package com.watchdawg.market.watch

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.sync.CollectionSyncScheduler
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The configured watches, checked against the catalog. */
class Watches(private val initial: List<Watch>, private val source: (() -> List<Watch>?)? = null) {
    val all: List<Watch> get() = source?.invoke() ?: initial

    /** The items to poll (R6.1), each once however many watches name it. */
    val itemSlugs: List<String> get() = all.map { it.slug }.distinct()

    fun forItem(itemId: String): List<Watch> = all.filter { it.itemId == itemId }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatchProperties::class)
class WatchConfig {
    /** Resolved while the context starts, so a bad watch stops the service starting (R9a.2). */
    @Bean
    fun watches(
        props: WatchProperties,
        items: ItemRepository,
        catalog: CollectionSyncScheduler,
        state: com.watchdawg.market.operator.OperatorState,
    ): Watches {
        val saved = state.snapshot?.view
        val entries = saved?.document?.watches ?: props.watches
        val enabled = entries.filter { it.enabled }.map { it.name }.toSet()
        val loaded = loadWatches(entries, items::findBySlug, catalog::tick).filter { it.name in enabled }
        if (saved != null) state.publish(saved, loaded)
        return Watches(loaded) { state.snapshot?.watches }
    }
}

private val log = LoggerFactory.getLogger(Watches::class.java)

/**
 * Resolves [entries] against the catalog.
 *
 * A fresh database has no catalog until the first sync, which would fail every watch. So when a
 * watched slug is missing, [refreshCatalog] runs once (a hash-gated refresh: two requests), and
 * only a slug still missing after it fails.
 *
 * @throws InvalidWatchException naming the bad entry.
 */
fun loadWatches(entries: List<WatchEntry>, itemOf: (String) -> ItemRecord?, refreshCatalog: () -> Unit): List<Watch> {
    var resolution = resolveWatches(entries, itemOf)
    if (resolution is Resolution.MissingItems) {
        log.info("watched items {} are not in the catalog; refreshing it once", resolution.slugs)
        refreshCatalog()
        resolution = resolveWatches(entries, itemOf)
    }
    return when (resolution) {
        is Resolution.Resolved -> resolution.watches.also { log.info("{} watches loaded", it.size) }

        is Resolution.MissingItems -> {
            val bad = entries.filter { it.item in resolution.slugs }.joinToString { "'${it.name}' (${it.item})" }
            throw InvalidWatchException("no such item in the catalog for watches $bad")
        }
    }
}
