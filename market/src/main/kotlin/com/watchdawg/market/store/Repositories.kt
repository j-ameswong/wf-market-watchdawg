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

    /**
     * The list refresh's write. `synced_at` is `now()`: the refresh transaction's start, so one
     * refresh stamps every row alike.
     *
     * `tradable`, `rarity` and `max_charges` are not here: `/v2/items` never carries them, and
     * [recordDetail] owns them. Writing them here would null out the detail sweep on every refresh.
     */
    @Modifying
    @Query(
        """
        insert into item (id, slug, name, icon, game_ref, tags, subtypes, max_rank, max_amber_stars,
                          max_cyan_stars, ducats, vaulted, bulk_tradable, synced_at)
        values (:id, :slug, :name, :icon, :gameRef, :tags, :subtypes, :maxRank, :maxAmberStars,
                :maxCyanStars, :ducats, :vaulted, :bulkTradable, now())
        on conflict (id) do update set
            slug            = excluded.slug,
            name            = excluded.name,
            icon            = excluded.icon,
            game_ref        = excluded.game_ref,
            tags            = excluded.tags,
            subtypes        = excluded.subtypes,
            max_rank        = excluded.max_rank,
            max_amber_stars = excluded.max_amber_stars,
            max_cyan_stars  = excluded.max_cyan_stars,
            ducats          = excluded.ducats,
            vaulted         = excluded.vaulted,
            bulk_tradable   = excluded.bulk_tradable,
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
        maxAmberStars: Int?,
        maxCyanStars: Int?,
        ducats: Int?,
        vaulted: Boolean,
        bulkTradable: Boolean?,
    )

    /**
     * Items whose details are missing or older than their last catalog refresh, never-fetched
     * first. A row no refresh has written (`synced_at` null) is not stale once fetched.
     */
    @Query(
        """
        select * from item
        where detail_synced_at is null or detail_synced_at < synced_at
        order by detail_synced_at nulls first, slug
        limit :limit
        """,
    )
    fun needingDetail(limit: Int): List<ItemRecord>

    /** The detail sweep's write, for the fields only `/v2/item/{slug}` carries. */
    @Modifying
    @Query(
        """
        update item
        set tradable = :tradable, rarity = :rarity, max_charges = :maxCharges, detail_synced_at = now()
        where id = :id
        """,
    )
    fun recordDetail(id: String, tradable: Boolean?, rarity: String?, maxCharges: Int?)

    /** Marks an item checked without touching its details, for an item the API no longer has. */
    @Modifying
    @Query("update item set detail_synced_at = now() where id = :id")
    fun markDetailChecked(id: String)
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
    item.maxAmberStars,
    item.maxCyanStars,
    item.ducats,
    item.vaulted,
    item.bulkTradable,
)
