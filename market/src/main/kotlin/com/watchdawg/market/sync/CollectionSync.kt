package com.watchdawg.market.sync

/**
 * One warframe.market collection that can be re-fetched when its version hash changes.
 *
 * Implementations only fetch and store. [CollectionSyncScheduler] owns the hash comparison, the
 * transaction, and recording the new hash once a refresh succeeds.
 */
interface CollectionSync {

    /** Key in [com.watchdawg.market.wfm.VersionCollections], such as "items" or "rivens". */
    val collection: String

    /** Fetches the collection and upserts it. Called only when the hash changed, in a transaction. */
    fun refresh()
}
