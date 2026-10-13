package com.watchdawg.market.watch

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

enum class SignalState(val db: String) {
    PENDING("pending"),
    SENT("sent"),
    FAILED("failed"),

    /** Over the daily ceiling: recorded and counted, never sent (R9a.8). */
    SUPPRESSED("suppressed"),
    ;

    companion object {
        fun fromDb(value: String): SignalState = entries.first { it.db == value }
    }
}

/** When a signal was seen and at what unit price. */
data class Admitted(val seenAt: Instant, val unitPrice: BigDecimal)

/** A pending signal with what its notification needs (R10.4). */
data class Outgoing(
    val id: Long,
    val watch: String,
    val itemName: String,
    val slug: String,
    val dimensions: String,
    val unitPrice: BigDecimal,
    val platinum: Int,
    val perTrade: Int?,
    val priority: Int,
    val topic: String,
    val seenAt: Instant,
    val attempts: Int,
    val title: String? = null,
    val message: String? = null,
    val click: String? = null,
)

/** The `signal` outbox. Every method runs in the caller's transaction. */
@Component
class SignalStore(private val jdbc: NamedParameterJdbcTemplate) {

    /** Acquire only after all order writes, held until the surrounding ingest commits. */
    fun lockAdmission() {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            "signal admission requires an ingest transaction"
        }
        jdbc.jdbcTemplate.execute("select pg_advisory_xact_lock(726193504)")
    }

    /** Returns true once per false-to-true transition; called under the admission lock. */
    fun transition(watch: String, market: Long, definition: String, active: Boolean, seenAt: Instant): Boolean {
        val previous = jdbc.query(
            "select active, observed_at from watch_condition where watch = :watch and market_id = :market and definition = :definition",
            mapOf("watch" to watch, "market" to market, "definition" to definition),
        ) { rs, _ -> rs.getBoolean(1) to rs.getTimestamp(2).toInstant() }.singleOrNull()
        if (previous != null && !seenAt.isAfter(previous.second)) return false
        jdbc.update(
            """
            insert into watch_condition(watch, market_id, definition, active, observed_at)
            values (:watch, :market, :definition, :active, :at)
            on conflict(watch, market_id, definition) do update set active = excluded.active, observed_at = excluded.observed_at
            """,
            mapOf(
                "watch" to watch,
                "market" to market,
                "definition" to definition,
                "active" to active,
                "at" to Timestamp.from(seenAt),
            ),
        )
        return active && previous?.first != true
    }

    fun retryCondition(watch: String, market: Long, definition: String) {
        jdbc.update(
            """
            update watch_condition set active = false
            where watch = :watch and market_id = :market and definition = :definition
            """,
            mapOf("watch" to watch, "market" to market, "definition" to definition),
        )
    }

    /**
     * Writes [candidate] in [state] and returns true, or returns false when its dedup key is
     * already taken, whatever that signal's state (R9a.5).
     */
    fun admit(candidate: Candidate, state: SignalState, seenAt: Instant): Boolean = jdbc.query(
        """
        insert into signal (watch, rule, market_id, order_id, dedup_key, unit_price, threshold,
                            platinum, per_trade, priority, topic, state, seen_at, title, message, click)
        values (:watch, :rule, :marketId, :orderId, :dedupKey, :unitPrice, :threshold,
                :platinum, :perTrade, :priority, :topic, :state, :seenAt, :title, :message, :click)
        on conflict (dedup_key) do nothing
        returning id
        """,
        MapSqlParameterSource()
            .addValue("watch", candidate.watch.name)
            .addValue("rule", candidate.rule)
            .addValue("marketId", candidate.signalMarketId)
            .addValue("orderId", candidate.order.id)
            .addValue("dedupKey", candidate.dedupKey)
            .addValue("unitPrice", candidate.order.unitPrice)
            .addValue("threshold", candidate.watch.maxUnitPrice)
            .addValue("platinum", candidate.order.platinum)
            .addValue("perTrade", candidate.order.perTrade)
            .addValue("priority", candidate.watch.priority.ntfy)
            .addValue("topic", candidate.watch.topic)
            .addValue("state", state.db)
            .addValue("seenAt", Timestamp.from(seenAt))
            .addValue("title", candidate.title)
            .addValue("message", candidate.message)
            .addValue("click", candidate.click),
    ) { rs, _ -> rs.getLong(1) }.isNotEmpty()

    fun exists(dedupKey: String): Boolean = jdbc.queryForObject(
        "select exists (select 1 from signal where dedup_key = :dedupKey)",
        MapSqlParameterSource("dedupKey", dedupKey),
        Boolean::class.java,
    ) == true

    /** The last signal admitted for [watch], pending or sent: what its cooldown is counted from. */
    fun lastAdmitted(watch: String): Admitted? = jdbc.query(
        """
        select seen_at, unit_price from signal
        where watch = :watch and state in ('pending', 'sent')
        order by seen_at desc, id desc
        limit 1
        """,
        MapSqlParameterSource("watch", watch),
    ) { rs, _ -> Admitted(rs.getTimestamp(1).toInstant(), rs.getBigDecimal(2)) }.singleOrNull()

    /**
     * Signals seen in [from, until) that were admitted to be sent, across all watches: what the
     * daily ceiling counts (R9a.8). A failed signal was admitted, so it counts; a suppressed one
     * was not.
     */
    fun admittedBetween(from: Instant, until: Instant): Int = jdbc.queryForObject(
        """
        select count(*) from signal
        where state in ('pending', 'sent', 'failed') and seen_at >= :from and seen_at < :until
        """,
        MapSqlParameterSource("from", Timestamp.from(from)).addValue("until", Timestamp.from(until)),
        Int::class.java,
    )!!

    /** Pending signals whose next attempt is due at [now], oldest first. */
    fun due(now: Instant, limit: Int): List<Outgoing> = jdbc.query(
        """
        select s.id, s.watch, coalesce(i.name, i.slug) as item_name, i.slug, m.subtype, m.rank,
               m.charges, m.amber_stars, m.cyan_stars, s.unit_price, s.platinum, s.per_trade,
               s.priority, s.topic, s.seen_at, s.attempts, s.title, s.message, s.click
        from signal s
        left join market m on m.id = s.market_id
        left join item i on i.id = m.item_id
        where s.state = 'pending' and (s.next_attempt_at is null or s.next_attempt_at <= :now)
        order by s.id
        limit :limit
        """,
        MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit),
    ) { rs, _ -> rs.toOutgoing() }

    fun markSent(id: Long, at: Instant) {
        jdbc.update(
            """
            update signal set state = 'sent', attempts = attempts + 1, notified_at = :at, last_error = null
            where id = :id
            """,
            MapSqlParameterSource("id", id).addValue("at", Timestamp.from(at)),
        )
    }

    /** Records a failed attempt. [retryAt] null marks the signal failed for good (R10.3). */
    @org.springframework.transaction.annotation.Transactional
    fun recordFailure(id: Long, error: String, retryAt: Instant?) {
        lockAdmission()
        jdbc.update(
            """
            update signal
            set attempts = attempts + 1, last_error = :error, next_attempt_at = :retryAt,
                state = case when cast(:retryAt as timestamptz) is null then 'failed' else state end
            where id = :id
            """,
            MapSqlParameterSource("id", id)
                .addValue("error", error)
                .addValue("retryAt", retryAt?.let(Timestamp::from)),
        )
    }

    private fun ResultSet.toOutgoing() = Outgoing(
        id = getLong("id"),
        watch = getString("watch"),
        itemName = getString("item_name") ?: getString("title") ?: "Market alert",
        slug = getString("slug") ?: "",
        dimensions = listOfNotNull(
            getString("subtype"),
            (getObject("rank") as Int?)?.let { "rank $it" },
            (getObject("charges") as Int?)?.let { "$it charges" },
            (getObject("amber_stars") as Int?)?.let { "$it amber" },
            (getObject("cyan_stars") as Int?)?.let { "$it cyan" },
        ).joinToString(", "),
        unitPrice = getBigDecimal("unit_price"),
        platinum = getInt("platinum"),
        perTrade = getObject("per_trade") as Int?,
        priority = getInt("priority"),
        topic = getString("topic"),
        seenAt = getTimestamp("seen_at").toInstant(),
        attempts = getInt("attempts"),
        title = getString("title"),
        message = getString("message"),
        click = getString("click"),
    )
}
