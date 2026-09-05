package com.watchdawg.market.sync

/**
 * One warframe.market collection that can be re-fetched when its version hash changes.
 *
 * Implementations only fetch and store; [CollectionSyncScheduler] owns the hash comparison,
 * the transaction, and recording the new hash after a successful refresh.
 */
interface CollectionSync {

	/** Key in [com.watchdawg.market.wfm.VersionCollections] — "items", "rivens", ... */
	val collection: String

	/** Fetch the collection and upsert it. Called only when the hash changed; runs in a transaction. */
	fun refresh()
}
