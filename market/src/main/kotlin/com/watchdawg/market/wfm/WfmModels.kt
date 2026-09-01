package com.watchdawg.market.wfm

import java.time.Instant

data class Envelope<T>(
	val apiVersion: String,
	val data: T? = null,
	val error: ApiError? = null,
)

data class ApiError(
	val request: List<String>? = null,
	val inputs: Map<String, String>? = null,
)

// GET /v2/versions
data class Versions(
	val apps: VersionApps,
	val collections: VersionCollections,
	val updatedAt: Instant,
)

data class VersionApps(
	val ios: String? = null,
	val android: String? = null,
	val minIos: String? = null,
	val minAndroid: String? = null,
)

data class VersionCollections(
	val items: String? = null,
	val rivens: String? = null,
	val liches: String? = null,
	val sisters: String? = null,
	val missions: String? = null,
	val npcs: String? = null,
	val locations: String? = null,
)

data class Items(
	val id: String,
	val slug: String,
	val gameRef: String? = null,
	val ducats: Int? = null,
	val maxRank: Int? = null,
	val vaulted: Boolean = false,
	val tags: List<String> = emptyList(),
	val updatedAt: Instant = Instant.EPOCH,
)

fun VersionCollections.asMap(): Map<String, String> = buildMap {
    items?.let { put("items", it) }
    rivens?.let { put("rivens", it) }
    liches?.let { put("liches", it) }
    sisters?.let { put("sisters", it) }
    missions?.let { put("missions", it) }
    npcs?.let { put("npcs", it) }
    locations?.let { put("locations", it) }
 }
