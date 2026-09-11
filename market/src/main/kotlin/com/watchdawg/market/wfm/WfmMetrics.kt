package com.watchdawg.market.wfm

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * Meters for the rate limiter, so its behaviour can be checked at runtime (R12.1).
 *
 * SPEC 9 calls a `429` a bug in our own pacing, and
 * [ADR-0004](docs/adr/0004-rate-limit-discipline-is-a-hard-boundary.md) makes that a hard
 * boundary. "Are we under the limit?" therefore has to be answerable from a metric, not from
 * grepping logs after the fact.
 *
 * Every meter is tagged by [Bucket]. The two budgets are independent, so a combined total would
 * not tell you whether either one was breached.
 *
 * All the meter names are defined here and nowhere else. Dashboards and alerts depend on them, so
 * a rename anywhere else would break them silently.
 */
class WfmMetrics(private val registry: MeterRegistry) {

    /** Counts every outbound attempt, retries included. This is the "req/s consumed" numerator. */
    private val requests = byBucket { Counter.builder(REQUESTS).tag(BUCKET, it).register(registry) }

    /** How long callers waited for a turn. When this climbs, the rate budget is the bottleneck. */
    private val waits = byBucket { Timer.builder(WAIT).tag(BUCKET, it).register(registry) }

    /**
     * Counts the refusals we answered with a second attempt.
     *
     * Tagged by status, because the two mean different things: `429` says our pacing is wrong,
     * `509` says our concurrency is (R1.3, R1.4).
     *
     * Every bucket and status is registered up front, the same way [requests] is, so a healthy
     * service reads as a flat zero rather than as a missing series. This is the metric that shows
     * whether ADR-0004's boundary is holding, and on it "no data" must not look like "no retries".
     */
    private val retries = Bucket.entries.flatMap { bucket ->
        Throttle.entries.map { throttle ->
            (bucket to throttle) to Counter.builder(RETRIES)
                .tag(BUCKET, bucket.tag)
                .tag(STATUS, throttle.status.toString())
                .register(registry)
        }
    }.toMap()

    fun requestIssued(bucket: Bucket, waited: Duration) {
        requests.getValue(bucket).increment()
        waits.getValue(bucket).record(waited)
    }

    fun retryIssued(bucket: Bucket, throttle: Throttle) = retries.getValue(bucket to throttle).increment()

    /** Reads the attempt count back. This is how tests check that a retry really did spend budget. */
    fun requestsIssued(bucket: Bucket): Long = requests.getValue(bucket).count().toLong()

    /**
     * Tracks the connection cap, which only ever narrows (ADR-0004, plan Decision 8).
     *
     * A gauge rather than a counter, because the question it answers is "what is the cap right
     * now". A drop from 2 to 1 is the only visible trace of a `509`, and no request counter can
     * show it.
     */
    fun trackConcurrency(currentLimit: () -> Int) {
        // Micrometer keeps only a weak reference to a gauge's source, so a lambda the caller does
        // not hold itself would be collected and the gauge would start reporting NaN.
        concurrencySource = currentLimit
        registry.gauge(CONCURRENCY, currentLimit) { it().toDouble() }
    }

    private var concurrencySource: (() -> Int)? = null

    private fun <T> byBucket(meter: (String) -> T): Map<Bucket, T> = Bucket.entries.associateWith { meter(it.tag) }

    private companion object {
        const val REQUESTS = "wfm.requests"
        const val WAIT = "wfm.request.wait"
        const val RETRIES = "wfm.retries"
        const val CONCURRENCY = "wfm.concurrency.limit"
        const val BUCKET = "bucket"
        const val STATUS = "status"
    }
}

/** Micrometer tag values are conventionally lower-kebab-case, and `CONTRACT_SEARCH` is not. */
val Bucket.tag: String get() = name.lowercase().replace('_', '-')
