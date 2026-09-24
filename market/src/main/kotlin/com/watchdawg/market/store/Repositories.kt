package com.watchdawg.market.store

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface CollectionVersionRepository : CrudRepository<CollectionVersionRecord, String> {
    @Modifying
    @Query(
        """
        insert into collection_version (name, hash, updated_at)
        values (:name, :hash, :updatedAt)
        on conflict (name) do update set
            hash       = excluded.hash,
            updated_at = excluded.updated_at
        """,
    )
    fun upsert(name: String, hash: String, updatedAt: Instant)
}

fun CollectionVersionRepository.upsert(record: CollectionVersionRecord) =
    upsert(record.name, record.hash, record.updatedAt)

@Repository
interface ItemRepository : CrudRepository<ItemRecord, String> {

    fun findBySlug(slug: String): ItemRecord?

    /** `synced_at` is `now()`: the refresh transaction's start, so one refresh stamps every row alike. */
    @Modifying
    @Query(
        """
        insert into item (id, slug, name, icon, game_ref, tags, subtypes, max_rank, max_charges,
                          max_amber_stars, max_cyan_stars, ducats, vaulted, bulk_tradable, tradable,
                          rarity, synced_at)
        values (:id, :slug, :name, :icon, :gameRef, :tags, :subtypes, :maxRank, :maxCharges,
                :maxAmberStars, :maxCyanStars, :ducats, :vaulted, :bulkTradable, :tradable,
                :rarity, now())
        on conflict (id) do update set
            slug            = excluded.slug,
            name            = excluded.name,
            icon            = excluded.icon,
            game_ref        = excluded.game_ref,
            tags            = excluded.tags,
            subtypes        = excluded.subtypes,
            max_rank        = excluded.max_rank,
            max_charges     = excluded.max_charges,
            max_amber_stars = excluded.max_amber_stars,
            max_cyan_stars  = excluded.max_cyan_stars,
            ducats          = excluded.ducats,
            vaulted         = excluded.vaulted,
            bulk_tradable   = excluded.bulk_tradable,
            tradable        = excluded.tradable,
            rarity          = excluded.rarity,
            synced_at       = excluded.synced_at
        """,
    )
    fun upsert(
        id: String,
        slug: String,
        name: String?,
        icon: String?,
        gameRef: String?,
        tags: Array<String>,
        subtypes: Array<String>,
        maxRank: Int?,
        maxCharges: Int?,
        maxAmberStars: Int?,
        maxCyanStars: Int?,
        ducats: Int?,
        vaulted: Boolean,
        bulkTradable: Boolean?,
        tradable: Boolean?,
        rarity: String?,
    )
}

fun ItemRepository.upsert(item: ItemRecord) = upsert(
    item.id,
    item.slug,
    item.name,
    item.icon,
    item.gameRef,
    item.tags.toTypedArray(),
    item.subtypes.toTypedArray(),
    item.maxRank,
    item.maxCharges,
    item.maxAmberStars,
    item.maxCyanStars,
    item.ducats,
    item.vaulted,
    item.bulkTradable,
    item.tradable,
    item.rarity,
)
