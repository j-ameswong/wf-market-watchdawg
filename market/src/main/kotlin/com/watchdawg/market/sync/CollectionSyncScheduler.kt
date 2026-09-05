package com.watchdawg.market.sync

import com.watchdawg.market.store.CollectionVersionRepository
import com.watchdawg.market.store.upsert
import com.watchdawg.market.wfm.RateLimitedException
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.asMap
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Polls GET /v2/versions once per tick and refreshes the collections whose hash moved.
 */
@Service
class CollectionSyncScheduler(
	private val wfm: WfmClient,
	private val versions: CollectionVersionRepository,
	private val syncs: List<CollectionSync>,
	private val tx: TransactionTemplate,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		initialDelayString = "\${wfm.sync.initial-delay}",
		fixedDelayString = "\${wfm.sync.interval}",
	)
	fun tick() {
		val fresh = try {
			wfm.getVersions()
		} catch (e: RateLimitedException) {
			log.warn("rate limited fetching /versions, retrying next tick: {}", e.message)
			return
		} catch (e: Exception) {
			log.error("could not fetch /versions", e)
			return
		}

		val hashes = fresh.collections.asMap()

		syncs.forEach { sync ->
			val hash = hashes[sync.collection]
			if (hash == null) {
				log.warn("/versions carried no hash for {}, skipping", sync.collection)
				return@forEach
			}

			val known = versions.findById(sync.collection).orElse(null)
			if (known?.hash == hash) {
				log.debug("{} unchanged ({})", sync.collection, hash)
				return@forEach
			}

			log.info("{}: {} -> {}, resyncing", sync.collection, known?.hash ?: "<none>", hash)
			try {
				tx.executeWithoutResult {
					sync.refresh()
					versions.upsert(sync.collection, hash, fresh.updatedAt)
				}
			} catch (e: Exception) {
				log.error("{} sync failed, hash left stale", sync.collection, e)
			}
		}
	}
}
