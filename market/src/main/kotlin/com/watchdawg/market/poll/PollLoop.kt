package com.watchdawg.market.poll

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.ingest.BookOutcome
import com.watchdawg.market.ingest.OrderBookPoll
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.PollOutcome
import com.watchdawg.market.wfm.ThrottledException
import com.watchdawg.market.wfm.WfmMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Polls the book of every watched item, one after another, once per round (C6, R6.1). One instance
 * runs, on a thread of its own, and a round never overlaps the previous one (R6.2, R6.3).
 *
 * Every request waits its turn at the limiter, so more items than the budget allows make rounds
 * late rather than refused (R6.5), and [WfmMetrics] records how late (R6.4).
 */
@Component
class PollLoop(
    private val watches: Watches,
    private val poll: OrderBookPoll,
    private val metrics: WfmMetrics,
    @Value("\${wfm.poll.interval}") interval: Duration,
    @Value("\${wfm.poll.initial-delay}") initialDelay: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val cadence = Cadence(interval, initialDelay, clock)

    /**
     * One round. A failed fetch writes nothing (R4.12), so it is logged and the round moves on. A
     * throttle ends the round, and its `Retry-After` holds off the next one (R6.6).
     */
    fun round() {
        metrics.pollRoundStarted(cadence.lateness(clock.instant()))
        for (slug in watches.itemSlugs) {
            val outcome = try {
                when (poll.poll(slug)) {
                    is BookOutcome.Reconciled -> PollOutcome.RECONCILED
                    BookOutcome.Stale -> PollOutcome.STALE
                }
            } catch (e: ThrottledException) {
                metrics.polled(PollOutcome.THROTTLED)
                cadence.throttled(e.retryAfter)
                log.warn("poll round ended at {}: {}", slug, e.message)
                return
            } catch (e: Exception) {
                log.warn("poll of {} failed: {}", slug, e.message)
                PollOutcome.FAILED
            }
            metrics.polled(outcome)
        }
    }
}

@Configuration(proxyBeanMethods = false)
class PollConfig {
    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, matchIfMissing = true)
    fun pollSchedule(loop: PollLoop) = OwnThreadSchedule("poll", loop::round, loop.cadence)
}
