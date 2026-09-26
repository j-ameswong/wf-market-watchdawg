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
)

/** The `signal` outbox. Every method runs in the caller's transaction. */
@Component
class SignalStore(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Writes [candidate] in [state] and returns true, or returns false when its dedup key is
     * already taken, whatever that signal's state (R9a.5).
     */
    fun admit(candidate: Candidate, state: SignalState, seenAt: Instant): Boolean = jdbc.query(
        """
        insert into signal (watch, rule, market_id, order_id, dedup_key, unit_price, threshold,
                            platinum, per_trade, priority, topic, state, seen_at)
        values (:watch, :rule, :marketId, :orderId, :dedupKey, :unitPrice, :threshold,
                :platinum, :perTrade, :priority, :topic, :state, :seenAt)
        on conflict (dedup_key) do nothing
        returning id
        """,
        MapSqlParameterSource()
            .addValue("watch", candidate.watch.name)
            .addValue("rule", candidate.rule)
            .addValue("marketId", candidate.order.marketId)
            .addValue("orderId", candidate.order.id)
            .addValue("dedupKey", candidate.dedupKey)
            .addValue("unitPrice", candidate.order.unitPrice)
            .addValue("threshold", candidate.watch.maxUnitPrice)
            .addValue("platinum", candidate.order.platinum)
            .addValue("perTrade", candidate.order.perTrade)
            .addValue("priority", candidate.watch.priority.ntfy)
            .addValue("topic", candidate.watch.topic)
            .addValue("state", state.db)
            .addValue("seenAt", Timestamp.from(seenAt)),
    ) { rs, _ -> rs.getLong(1) }.isNotEmpty()

    /** Pending signals whose next attempt is due at [now], oldest first. */
    fun due(now: Instant, limit: Int): List<Outgoing> = jdbc.query(
        """
        select s.id, s.watch, coalesce(i.name, i.slug) as item_name, i.slug, m.subtype, m.rank,
               m.charges, m.amber_stars, m.cyan_stars, s.unit_price, s.platinum, s.per_trade,
               s.priority, s.topic, s.seen_at, s.attempts
        from signal s
        join market m on m.id = s.market_id
        join item i on i.id = m.item_id
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
    fun recordFailure(id: Long, error: String, retryAt: Instant?) {
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
        itemName = getString("item_name"),
        slug = getString("slug"),
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
    )
}
