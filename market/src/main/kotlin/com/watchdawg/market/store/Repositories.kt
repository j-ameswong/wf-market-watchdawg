package com.watchdawg.market.store

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

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

     fun upsert(name: String, hash: String, updatedAt: java.time.Instant)
}

fun CollectionVersionRepository.upsert(record: CollectionVersionRecord) =
     upsert(record.name, record.hash, record.updatedAt)

@Repository
interface ItemRepository : CrudRepository<ItemRecord, String> {

	fun findBySlug(slug: String): ItemRecord?

	@Modifying
	@Query(
		"""
		insert into item (id, slug, game_ref, ducats, max_rank, vaulted, tags, updated_at)
		values (:id, :slug, :gameRef, :ducats, :maxRank, :vaulted, :tags, :updatedAt)
		on conflict (id) do update set
			slug       = excluded.slug,
			game_ref   = excluded.game_ref,
			ducats     = excluded.ducats,
			max_rank   = excluded.max_rank,
			vaulted    = excluded.vaulted,
			tags       = excluded.tags,
			updated_at = excluded.updated_at
		""",
	)
	fun upsert(
		id: String,
		slug: String,
		gameRef: String?,
		ducats: Int?,
		maxRank: Int?,
		vaulted: Boolean,
		tags: Array<String>,
		updatedAt: java.time.Instant,
	)
}

fun ItemRepository.upsert(item: ItemRecord) = upsert(
	item.id, item.slug, item.gameRef, item.ducats, item.maxRank, item.vaulted,
	item.tags.toTypedArray(), item.updatedAt,
)
