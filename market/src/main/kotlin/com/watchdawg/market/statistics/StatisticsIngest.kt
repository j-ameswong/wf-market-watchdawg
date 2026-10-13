package com.watchdawg.market.statistics

import com.watchdawg.market.store.ItemRecord
import com.watchdawg.market.store.MarketKey
import com.watchdawg.market.store.MarketResolver
import com.watchdawg.market.wfm.ItemStatistics
import com.watchdawg.market.wfm.WfmContext
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/** A complete upstream row; null dimensions are not rank/star zero. */
data class StatRow(
    val key: MarketKey,
    val section: String,
    val granularity: String,
    val bucket: Instant,
    val side: String,
    val upstreamId: String,
    val volume: Int,
    val min: BigDecimal,
    val max: BigDecimal,
    val avg: BigDecimal,
    val weighted: BigDecimal,
    val median: BigDecimal,
    val moving: BigDecimal?,
    val open: BigDecimal? = null,
    val close: BigDecimal? = null,
    val top: BigDecimal? = null,
    val bottom: BigDecimal? = null,
)

fun statisticsRows(item: ItemRecord, payload: ItemStatistics): List<StatRow> {
    // Statistics has no charge dimension. Reject an ambiguous future catalog rather than quietly
    // merging charge levels into rank markets. Current requiems have maxRank and no maxCharges.
    require(item.detailSyncedAt != null && item.maxCharges == null) {
        "statistics charge mapping is unknown for ${item.slug}"
    }
    fun key(rank: Int?, subtype: String?, amber: Int?, cyan: Int?) =
        MarketKey(item.id, subtype, rank, null, amber, cyan)
    return buildList {
        listOf(
            "hourly" to payload.statisticsClosed.hourly,
            "daily" to payload.statisticsClosed.daily,
        ).forEach { (granularity, rows) ->
            rows.forEach {
                add(
                    StatRow(
                        key(it.modRank, it.subtype, it.amberStars, it.cyanStars),
                        "closed", granularity, it.datetime, "", it.id,
                        it.volume, it.minPrice, it.maxPrice, it.avgPrice, it.waPrice, it.median, it.movingAvg,
                        it.openPrice, it.closedPrice, it.donchTop, it.donchBot,
                    ),
                )
            }
        }
        listOf(
            "hourly" to payload.statisticsLive.hourly,
            "daily" to payload.statisticsLive.daily,
        ).forEach { (granularity, rows) ->
            rows.forEach {
                require(it.orderType in setOf("buy", "sell")) { "unknown statistics side" }
                add(
                    StatRow(
                        key(it.modRank, it.subtype, it.amberStars, it.cyanStars),
                        "live", granularity, it.datetime, it.orderType, it.id,
                        it.volume, it.minPrice, it.maxPrice, it.avgPrice, it.waPrice, it.median, it.movingAvg,
                    ),
                )
            }
        }
    }
}

@Component
class StatisticsIngest(
    private val markets: MarketResolver,
    private val jdbc: NamedParameterJdbcTemplate,
    private val context: WfmContext,
    transactions: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactions)

    fun ingest(item: ItemRecord, payload: ItemStatistics, fetchedAt: Instant): Int {
        val rows = statisticsRows(item, payload)
        val resolved = rows.map { it.key }.distinct().associateWith(markets::resolve)
        tx.executeWithoutResult {
            if (rows.isNotEmpty()) {
                jdbc.batchUpdate(
                    UPSERT,
                    rows.map {
                        MapSqlParameterSource()
                            .addValue("market", resolved.getValue(it.key))
                            .addValue("section", it.section)
                            .addValue("granularity", it.granularity)
                            .addValue("bucket", Timestamp.from(it.bucket))
                            .addValue("side", it.side)
                            .addValue("crossplay", context.crossplay)
                            .addValue("id", it.upstreamId)
                            .addValue("volume", it.volume)
                            .addValue("min", it.min)
                            .addValue("max", it.max)
                            .addValue("avg", it.avg)
                            .addValue("weighted", it.weighted)
                            .addValue("median", it.median)
                            .addValue("moving", it.moving)
                            .addValue("open", it.open)
                            .addValue("close", it.close)
                            .addValue("top", it.top)
                            .addValue("bottom", it.bottom)
                            .addValue("fetched", Timestamp.from(fetchedAt))
                    }.toTypedArray(),
                )
            }
        }
        return rows.size
    }

    private companion object {
        const val UPSERT = """
            insert into item_stat (market_id, section, granularity, bucket, order_type, crossplay,
                upstream_id, volume, min_price, max_price, avg_price, wa_price, median, moving_avg,
                open_price, closed_price, donch_top, donch_bot, fetched_at)
            values (:market, :section, :granularity, :bucket, :side, :crossplay, :id, :volume,
                :min, :max, :avg, :weighted, :median, :moving, :open, :close, :top, :bottom, :fetched)
            on conflict (market_id, section, granularity, bucket, order_type, crossplay) do update set
                upstream_id = excluded.upstream_id, volume = excluded.volume,
                min_price = excluded.min_price, max_price = excluded.max_price,
                avg_price = excluded.avg_price, wa_price = excluded.wa_price, median = excluded.median,
                moving_avg = excluded.moving_avg, open_price = excluded.open_price,
                closed_price = excluded.closed_price, donch_top = excluded.donch_top,
                donch_bot = excluded.donch_bot, fetched_at = excluded.fetched_at
            where item_stat.fetched_at <= excluded.fetched_at
        """
    }
}
