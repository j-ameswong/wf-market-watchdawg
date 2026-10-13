package com.watchdawg.market.operator

import com.watchdawg.market.notify.NotifyProperties
import com.watchdawg.market.wfm.WfmContext
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** Bounded warehouse reads only. Opening a dashboard never spends upstream request budget. */
@Service
class OperatorQueries(
    private val jdbc: JdbcTemplate,
    private val registry: MeterRegistry,
    private val configuration: OperatorConfiguration,
    private val notify: NotifyProperties,
    private val context: WfmContext,
    private val environment: Environment,
    private val watches: com.watchdawg.market.watch.Watches,
    private val contracts: com.watchdawg.market.contracts.ContractProperties,
    private val operator: OperatorState,
) {
    fun overview(): Map<String, Any?> {
        val config = configuration.current()
        val today = rows(
            """
            select state, count(*) as count from signal
            where seen_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'
            group by state
            """,
        )
        return mapOf(
            "observedAt" to Instant.now(),
            "socketConnected" to (registry.find("wfm.socket.connected").gauge()?.value() == 1.0),
            "activeWatches" to (watches.all.size + operator.activeContracts(contracts.watches).size),
            "watchedItemSlugs" to watches.itemSlugs,
            "dailyCeiling" to config.document.dailyCeiling,
            "today" to today,
            "catalogItems" to jdbc.queryForObject("select count(*) from item", Long::class.java),
            "pending" to jdbc.queryForObject("select count(*) from signal where state = 'pending'", Long::class.java),
            "baselineBudget" to rows(
                "select status, started_at, checked_until, budget_suppressed from baseline_budget_epoch",
            ).singleOrNull(),
            "signals" to rows(
                """
                select id, watch, rule, state, unit_price, seen_at, notified_at, attempts, title
                from signal order by id desc limit 30
                """,
            ),
        )
    }

    fun hostSettings(): Map<String, Any?> = mapOf(
        "platform" to context.platform,
        "crossplay" to context.crossplay,
        "topics" to notify.topics.keys.sorted(),
        "deliveryEnabled" to notify.topics.isNotEmpty(),
        "pollInterval" to environment.getProperty("wfm.poll.interval", "2m"),
        "statisticsInterval" to environment.getProperty("watchdawg.statistics.interval", "1d"),
        "contractInterval" to environment.getProperty("watchdawg.contracts.interval", "2h"),
        "contractsEnabled" to environment.getProperty("watchdawg.contracts.enabled", Boolean::class.java, false),
        "statisticsEnabled" to environment.getProperty("watchdawg.statistics.enabled", Boolean::class.java, true),
        "socketEnabled" to environment.getProperty("watchdawg.socket.enabled", Boolean::class.java, true),
        "notificationInterval" to notify.interval.toString(),
        "maxAttempts" to notify.maxAttempts,
        "revisions" to rows("select revision, saved_at from operator_configuration order by revision desc limit 20"),
    )

    fun items(query: String): List<Map<String, Any?>> {
        require(query.length <= 80) { "Search must be at most 80 characters." }
        return rows(
            """
            select slug, coalesce(name, slug) as name, subtypes, max_rank, max_charges,
                max_amber_stars, max_cyan_stars, detail_synced_at
            from item where slug ilike ? or name ilike ?
            order by case when slug = ? then 0 else 1 end, coalesce(name, slug), slug limit 80
            """,
            "%${query.trim()}%",
            "%${query.trim()}%",
            query.trim(),
        )
    }

    fun contractCatalog() = mapOf(
        "weapons" to rows("select kind, slug, name from contract_weapon order by kind, name limit 2000"),
        "attributes" to rows("select slug, positive_is_negative from contract_attribute order by slug limit 200"),
    )

    fun markets(slug: String): List<Map<String, Any?>> {
        require(slug.length <= 100) { "Invalid item slug." }
        return rows(
            """
            select m.id, m.platform, m.subtype, m.rank, m.charges, m.amber_stars, m.cyan_stars,
                i.slug, coalesce(i.name, i.slug) as name
            from market m join item i on i.id = m.item_id where i.slug = ?
            order by m.platform, m.subtype nulls first, m.rank nulls first, m.charges nulls first,
                m.amber_stars nulls first, m.cyan_stars nulls first limit 500
            """,
            slug,
        )
    }

    fun statistics(
        market: Long,
        days: Int,
        section: String,
        granularity: String,
        side: String,
        crossplay: Boolean,
    ): Map<String, Any?> {
        require(market > 0 && days in 1..90) { "Choose a market and a range between 1 and 90 days." }
        require(section in setOf("closed", "live") && granularity in setOf("hourly", "daily")) {
            "Invalid statistics series or resolution."
        }
        require((section == "closed" && side.isEmpty()) || (section == "live" && side in setOf("buy", "sell"))) {
            "Closed trades have no order side. Live statistics require buy or sell."
        }
        val series = rows(
            """
            select bucket, volume, min_price, max_price, avg_price, wa_price, median, moving_avg, fetched_at
            from item_stat where market_id = ? and section = ? and granularity = ?
                and order_type = ? and crossplay = ?
                and bucket >= now() - (? * interval '1 day') and bucket <= now()
            order by bucket limit 2161
            """,
            market,
            section,
            granularity,
            side,
            crossplay,
            days,
        )
        return mapOf(
            "market" to market,
            "section" to section,
            "granularity" to granularity,
            "side" to side,
            "crossplay" to crossplay,
            "points" to series,
            "calibrations" to rows(
                """
                select watch, rule, threshold, samples, holdout_rate, status, calibrated_at
                from baseline_calibration where market_id = ? and crossplay = ? order by watch limit 500
                """,
                market,
                crossplay,
            ),
        )
    }

    fun auctions(kind: String, weapon: String): List<Map<String, Any?>> {
        require(kind in setOf("riven", "lich", "sister") && weapon.matches(Regex("[a-z0-9_]{1,100}"))) {
            "Choose a contract type and weapon."
        }
        return rows(
            """
            select id, buyout_price, starting_price, top_bid, visible, closed, missing, observed_at,
                item::text as item
            from auction where kind = ? and weapon = ? order by observed_at desc, id limit 100
            """,
            kind,
            weapon,
        )
    }

    private fun rows(sql: String, vararg args: Any): List<Map<String, Any?>> =
        jdbc.query(sql, { rs, _ -> row(rs) }, *args)

    private fun row(rs: ResultSet): Map<String, Any?> = (1..rs.metaData.columnCount).associate { column ->
        val name = rs.metaData.getColumnLabel(column).replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }
        val value = when (val raw = rs.getObject(column)) {
            is Timestamp -> raw.toInstant().toString()
            is java.sql.Array -> (raw.array as Array<*>).toList()
            else -> raw
        }
        name to value
    }
}
