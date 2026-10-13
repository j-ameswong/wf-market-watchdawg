package com.watchdawg.market.contracts

import com.watchdawg.market.ingest.ObservedOrder
import com.watchdawg.market.watch.Alerts
import com.watchdawg.market.watch.Candidate
import com.watchdawg.market.watch.Dimension
import com.watchdawg.market.watch.Watch
import com.watchdawg.market.wfm.OrderType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
class ContractIngest(
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
    private val props: ContractProperties,
    private val alerts: Alerts,
    transactions: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactions)

    fun ingest(auctions: List<Auction>, at: Instant, missing: Boolean = false) {
        tx.executeWithoutResult {
            val candidates = mutableListOf<Candidate>()
            val inverted = jdbc.query(
                "select slug from contract_attribute where positive_is_negative",
            ) { rs, _ -> rs.getString(1) }.toSet()
            for (auction in auctions.distinctBy { it.id }.sortedBy { it.id }) {
                jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 13))", auction.id)
                val previous = jdbc.query(
                    "select * from auction where id = ?",
                    { rs, _ ->
                        StoredAuction(
                            rs.auction(),
                            rs.getBoolean("missing"),
                            rs.getTimestamp("observed_at").toInstant(),
                        )
                    },
                    auction.id,
                ).singleOrNull()
                if (previous != null && !at.isAfter(previous.at)) continue
                save(auction, at, missing)
                if (previous == null || previous.auction != auction || previous.missing != missing) {
                    event(auction, previous, at, missing)
                }
                if (!missing) {
                    props.watches.filter { it.matches(auction, inverted) }.forEach { target ->
                        candidates += candidate(target, auction)
                    }
                }
            }
            // No auction locks are acquired after the shared admission lock.
            candidates.groupBy { it.watch }.forEach { (watch, matches) ->
                alerts.admitCandidates(watch, matches.sortedBy { it.order.unitPrice }, at)
            }
        }
    }

    private fun save(auction: Auction, at: Instant, missing: Boolean) {
        jdbc.update(
            """
            insert into auction(id, kind, weapon, starting_price, buyout_price, top_bid,
                visible, closed, missing, item, first_seen_at, observed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            on conflict(id) do update set starting_price = excluded.starting_price,
                buyout_price = excluded.buyout_price, top_bid = excluded.top_bid,
                visible = excluded.visible, closed = excluded.closed, missing = excluded.missing,
                item = excluded.item, observed_at = excluded.observed_at,
                verification_pending = excluded.missing
            """,
            auction.id, auction.item.type, auction.item.weaponUrlName, auction.startingPrice,
            auction.buyoutPrice, auction.topBid, auction.visible, auction.closed, missing,
            mapper.writeValueAsString(auction.item), Timestamp.from(at), Timestamp.from(at),
        )
        jdbc.update("delete from auction_riven_attribute where auction_id = ?", auction.id)
        auction.item.attributes.forEach {
            jdbc.update(
                "insert into auction_riven_attribute(auction_id, name, positive, value) values (?, ?, ?, ?)",
                auction.id,
                it.urlName,
                it.positive,
                it.value,
            )
        }
    }

    private fun event(auction: Auction, previous: StoredAuction?, at: Instant, missing: Boolean) {
        val kind = when {
            missing -> "disappeared"
            auction.closed -> "closed"
            previous == null || previous.missing -> "appeared"
            else -> "changed"
        }
        jdbc.update(
            """
            insert into auction_event(observed_at, auction_id, event, before_state, after_state)
            values (?, ?, ?, ?::jsonb, ?::jsonb) on conflict do nothing
            """,
            Timestamp.from(at),
            auction.id,
            kind,
            previous?.let { mapper.writeValueAsString(it.auction) },
            mapper.writeValueAsString(auction),
        )
    }

    private fun candidate(target: ContractWatch, auction: Auction): Candidate {
        val price = auction.buyoutPrice!!
        val order = ObservedOrder(auction.id, 0, OrderType.SELL, price, null, 1, null, true, price.toBigDecimal())
        return Candidate(
            target.asWatch(),
            "contract-underpriced",
            order,
            key = "${target.name}|auction|${auction.id}|$price",
            title = "${target.weapon}: $price plat buyout",
            message = "${target.kind} auction matches configured attributes; buyout $price plat",
            click = "https://warframe.market/auction/${auction.id}",
            signalMarketId = null,
        )
    }

    fun known(kind: String, weapon: String): List<Auction> = jdbc.query(
        """
        select * from auction where kind = ? and weapon = ? and not closed
        order by observed_at, id
        """,
        { rs, _ -> rs.auction() },
        kind,
        weapon,
    )

    /** Queue only after a successful search; absence is not a lifecycle observation. */
    fun queueVerification(kind: String, weapon: String, present: Set<String>) {
        tx.executeWithoutResult {
            for (auction in known(kind, weapon).filter { it.id !in present }) {
                jdbc.update("update auction set verification_pending = true where id = ? and not closed", auction.id)
            }
        }
    }

    fun pendingVerification(kind: String, weapon: String, at: Instant): List<Auction> = jdbc.query(
        """
        select * from auction where kind = ? and weapon = ? and not closed and verification_pending
            and (verification_after is null or verification_after <= ?)
        order by verification_attempted_at nulls first, observed_at, id
        """,
        { rs, _ -> rs.auction() },
        kind,
        weapon,
        Timestamp.from(at),
    )

    /** Separate attempt bookkeeping preserves facts even when HTTP or decoding fails. */
    fun verificationAttempt(id: String, at: Instant, retryAt: Instant) {
        jdbc.update(
            "update auction set verification_attempted_at = ?, verification_after = ? where id = ?",
            Timestamp.from(at),
            Timestamp.from(retryAt),
            id,
        )
    }

    private fun ResultSet.auction() = Auction(
        getString("id"),
        getInt("starting_price"),
        getObject("buyout_price") as Int?,
        getObject("top_bid") as Int?,
        getBoolean("visible"),
        getBoolean("closed"),
        mapper.readValue(getString("item"), AuctionItem::class.java),
    )

    private data class StoredAuction(val auction: Auction, val missing: Boolean, val at: Instant)
}

fun ContractWatch.asWatch() = Watch(
    name, "$kind:$weapon", weapon, weapon,
    Dimension.Absent, Dimension.Absent, Dimension.Absent, Dimension.Absent, Dimension.Absent,
    (maxPrice ?: 0).toBigDecimal(), priority, cooldown, topic,
)
