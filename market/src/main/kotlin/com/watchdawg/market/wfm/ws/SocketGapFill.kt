package com.watchdawg.market.wfm.ws

import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.wfm.ThrottledException
import com.watchdawg.market.wfm.WfmClient
import com.watchdawg.market.wfm.WfmMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Its own clock and worker: a slow REST response never blocks socket delivery or poll cadence. */
class SocketGapFill(
    private val client: WfmClient,
    private val ingest: OrderIngest,
    private val metrics: WfmMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var nextAllowed = Instant.MIN

    @Synchronized
    fun run() {
        val at = clock.instant()
        if (at.isBefore(nextAllowed)) {
            metrics.socketGapFill("skipped")
            return
        }
        nextAllowed = at.plusSeconds(60)
        try {
            ingest.ingestPartial(client.getRecentOrders(), Source.RECENT, at)
            metrics.socketGapFill("success")
        } catch (e: ThrottledException) {
            nextAllowed = maxOf(nextAllowed, clock.instant().plus(e.retryAfter ?: Duration.ZERO))
            metrics.socketGapFill("throttled")
            log.warn("socket gap-fill throttled")
        } catch (e: Exception) {
            metrics.socketGapFill("failed")
            log.warn("socket gap-fill failed: {}", e.javaClass.simpleName)
        }
    }
}
