package com.watchdawg.market.store

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

// One row per collection, holding the hash last seen, so a tick only refetches what changed.
@Table("collection_version")
data class CollectionVersionRecord(
    @Id val name: String,
    val hash: String,
    val updatedAt: Instant,
    @Transient val new: Boolean = false,
) : Persistable<String> {
    override fun getId(): String = name
    override fun isNew(): Boolean = new
}

@Table("item")
data class ItemRecord(
    @Id val id: String,
    val slug: String,
    /** English display name and icon path, for notifications. */
    val name: String? = null,
    val icon: String? = null,
    val gameRef: String? = null,
    val tags: List<String> = emptyList(),
    val subtypes: List<String> = emptyList(),
    val maxRank: Int? = null,
    val maxCharges: Int? = null,
    val maxAmberStars: Int? = null,
    val maxCyanStars: Int? = null,
    val ducats: Int? = null,
    val vaulted: Boolean = false,
    val bulkTradable: Boolean? = null,
    val tradable: Boolean? = null,
    val rarity: String? = null,
    /**
     * When a refresh last wrote this row. The database stamps it, so [ItemRepository.upsert]
     * ignores whatever is set here. Null for a row no refresh has written since migration V5.
     */
    val syncedAt: Instant? = null,
)
