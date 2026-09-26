package com.watchdawg.market.store

import com.watchdawg.market.wfm.WfmContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/**
 * A market's dimensions (SPEC 2.3). A dimension the item does not have is null, and rank 0 is a
 * different market from no rank at all.
 *
 * There is no platform here on purpose: [MarketResolver] supplies the observer's, so a caller
 * cannot key a book by the seller's platform (ADR-0003).
 */
data class MarketKey(
    val itemId: String,
    val subtype: String? = null,
    val rank: Int? = null,
    val charges: Int? = null,
    val amberStars: Int? = null,
    val cyanStars: Int? = null,
)

/** A market was asked for an item the catalog does not have. */
class UnknownItemException(val itemId: String, cause: Throwable) :
    RuntimeException("no item $itemId in the catalog, so it has no markets yet", cause)

/**
 * Maps a [MarketKey] to its `market.id`, creating the market the first time it is seen (R3.3,
 * R4.6).
 *
 * Resolution is a lookup, then an insert-if-absent, then the lookup again. The insert commits in a
 * transaction of its own, for two reasons. A market is a fact about the book, not about the ingest
 * that first saw it, so it should survive that ingest rolling back. And two ingests that meet the
 * same new market never wait on each other's locks.
 *
 * Two resolutions racing on a new tuple are safe. The loser's insert waits for the winner's to
 * commit, finds the conflict and inserts nothing, and its second lookup then sees the winner's
 * row. That lookup relies on read committed, Postgres's default and this service's: a caller
 * holding a repeatable-read snapshot would not see the row.
 *
 * Nothing is cached. Test resets restart the id sequence, so a cached id could point at a different
 * market, and one indexed lookup per resolution is cheap.
 */
@Component
class MarketResolver(
    private val jdbc: NamedParameterJdbcTemplate,
    context: WfmContext,
    transactions: PlatformTransactionManager,
) {
    private val platform = context.platform

    private val ownTransaction = TransactionTemplate(transactions).apply {
        propagationBehavior = PROPAGATION_REQUIRES_NEW
    }

    /** @throws UnknownItemException if [MarketKey.itemId] is not in the catalog. */
    fun resolve(key: MarketKey): Long = find(key)
        ?: create(key)
        ?: find(key)
        ?: error("market $key conflicted on insert but cannot be found")

    /** Every market [itemId] has on the observer's platform, whichever capability created it. */
    fun marketsOf(itemId: String): List<Long> = jdbc.query(
        "select id from market where item_id = :itemId and platform = :platform order by id",
        MapSqlParameterSource("itemId", itemId).addValue("platform", platform),
    ) { rs, _ -> rs.getLong(1) }

    private fun find(key: MarketKey): Long? = jdbc.query(FIND, parameters(key)) { rs, _ ->
        rs.getLong(1)
    }.singleOrNull()

    /** The new market's id, or null when another resolution created it first. */
    private fun create(key: MarketKey): Long? = try {
        ownTransaction.execute { jdbc.query(INSERT, parameters(key)) { rs, _ -> rs.getLong(1) }.singleOrNull() }
    } catch (e: DataIntegrityViolationException) {
        if ((e.mostSpecificCause as? SQLException)?.sqlState == FOREIGN_KEY_VIOLATION) {
            throw UnknownItemException(key.itemId, e)
        }
        throw e
    }

    private fun parameters(key: MarketKey) = MapSqlParameterSource()
        .addValue("itemId", key.itemId)
        .addValue("platform", platform)
        .addValue("subtype", key.subtype)
        .addValue("rank", key.rank)
        .addValue("charges", key.charges)
        .addValue("amberStars", key.amberStars)
        .addValue("cyanStars", key.cyanStars)

    private companion object {
        const val FOREIGN_KEY_VIOLATION = "23503"

        // `is not distinct from` treats two nulls as equal, like the constraint does. The casts
        // type a null parameter, which Postgres cannot infer on its own here.
        const val FIND = """
            select id from market
            where item_id = :itemId
              and platform = :platform
              and subtype is not distinct from cast(:subtype as text)
              and rank is not distinct from cast(:rank as integer)
              and charges is not distinct from cast(:charges as integer)
              and amber_stars is not distinct from cast(:amberStars as integer)
              and cyan_stars is not distinct from cast(:cyanStars as integer)
            """

        const val INSERT = """
            insert into market (item_id, platform, subtype, rank, charges, amber_stars, cyan_stars)
            values (:itemId, :platform, cast(:subtype as text), cast(:rank as integer),
                    cast(:charges as integer), cast(:amberStars as integer), cast(:cyanStars as integer))
            on conflict on constraint market_tuple do nothing
            returning id
            """
    }
}
