package com.watchdawg.market.poll

import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * When the next poll round starts: one [interval] after the last one was due to start, so each
 * item is polled once per interval (R6.3).
 *
 * A round that overruns makes the next one start as soon as it ends, and the cadence carries on
 * from there: missed rounds are not made up in a burst. No round starts before a throttle's
 * `Retry-After` has passed (R6.6). A throttle that names no cooloff leaves the cadence alone,
 * since the limiter already spaced its retry.
 */
class Cadence(private val interval: Duration, private val initialDelay: Duration, private val clock: Clock) : Trigger {
    @Volatile private var due: Instant? = null

    @Volatile private var notBefore: Instant? = null

    override fun nextExecution(context: TriggerContext): Instant {
        val ideal = context.lastScheduledExecution()?.plus(interval) ?: clock.instant().plus(initialDelay)
        due = ideal
        return listOfNotNull(ideal, context.lastCompletion(), notBefore).max()
    }

    /** How long after its due time a round starting at [start] began. */
    fun lateness(start: Instant): Duration = due?.let { Duration.between(it, start) }?.takeIf { !it.isNegative }
        ?: Duration.ZERO

    fun throttled(retryAfter: Duration?) {
        if (retryAfter != null) notBefore = clock.instant().plus(retryAfter)
    }
}
