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
    val gameRef: String? = null,
    val ducats: Int? = null,
    val maxRank: Int? = null,
    val vaulted: Boolean = false,
    val tags: List<String> = emptyList(),
    val updatedAt: Instant = Instant.EPOCH,
)
