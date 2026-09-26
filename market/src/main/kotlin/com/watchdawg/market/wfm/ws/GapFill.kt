package com.watchdawg.market.wfm.ws

import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.wfm.GapFillOutcome
import com.watchdawg.market.wfm.ThrottledException
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fills the gap a reconnect leaves from `/v2/orders/recent` (R5.4, decision 5). The socket runs it
 * once each subscription is confirmed, so an order posted meanwhile arrives on the socket, and one
 * seen by both is recorded once. Its orders go through the partial path as `source=recent`, seen
 * at the request time, and reach the rules like the socket's.
 *
 * `/recent` is cached for a minute, so a gap-fill within [SPACING] of the last one is skipped, and
 * so is one before a throttle's `Retry-After` has passed. That is the only clock a gap-fill moves:
 * the poll loop keeps its own cadence and hold-off.
 *
 * Best effort: a gap-fill that fails or is throttled is logged and counted, and the socket stays
 * up. The next book poll of each watched item recovers its current state.
 */
class GapFill(
    private val wfm: WfmClient,
    private val ingest: OrderIngest,
    private val metrics: WfmMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var notBefore = Instant.MIN

    fun run() {
        val requestedAt = clock.instant()
        if (requestedAt.isBefore(notBefore)) return metrics.gapFill(GapFillOutcome.SKIPPED)
        notBefore = requestedAt.plus(SPACING)
        val outcome = try {
            val added = ingest.ingestPartial(wfm.getRecentOrders(), Source.RECENT, requestedAt)
            log.info("gap-filled {} new orders from /recent", added)
            GapFillOutcome.FILLED
        } catch (e: ThrottledException) {
            e.retryAfter?.let { notBefore = maxOf(notBefore, requestedAt.plus(it)) }
            log.warn("gap-fill throttled, none before {}: {}", notBefore, e.message)
            GapFillOutcome.THROTTLED
        } catch (e: Exception) {
            log.warn("gap-fill failed: {}", e.message)
            GapFillOutcome.FAILED
        }
        metrics.gapFill(outcome)
    }

    companion object {
        val SPACING: Duration = Duration.ofMinutes(1)
    }
}
