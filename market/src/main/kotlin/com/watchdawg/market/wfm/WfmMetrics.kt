package com.watchdawg.market.wfm

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * What the rate-limit boundary looks like from outside (R12.1). SPEC 9 calls a `429` a bug in our
 * own pacing, and [ADR-0004](docs/adr/0004-rate-limit-discipline-is-a-hard-boundary.md) makes that
 * a hard boundary — so "are we under the limit" has to be answerable from a metric rather than
 * from a log grep after the fact.
 *
 * Every meter is tagged by [Bucket], because the two budgets are independent and a total tells you
 * nothing about whether either was breached. Names are defined here and nowhere else: a dashboard
 * or an alert that watches one is a contract, and a renamed meter breaks it silently.
 */
class WfmMetrics(private val registry: MeterRegistry) {

    /** One per outbound attempt, retries included — this is the numerator of "req/s consumed". */
    private val requests = byBucket { Counter.builder(REQUESTS).tag(BUCKET, it).register(registry) }

    /** Time a caller spent held at the turnstile. Rising means the budget is the binding constraint. */
    private val waits = byBucket { Timer.builder(WAIT).tag(BUCKET, it).register(registry) }

    fun requestIssued(bucket: Bucket, waited: Duration) {
        requests.getValue(bucket).increment()
        waits.getValue(bucket).record(waited)
    }

    /**
     * A refusal we answered with a second attempt. Tagged by status because the two mean different
     * things: `429` says our pacing is wrong, `509` says our concurrency is (R1.3, R1.4).
     *
     * Registered up front for every bucket and status, as [requests] is, so a healthy service reads
     * as a flat zero rather than as a missing series. On the metric that says whether ADR-0004's
     * boundary is holding, "no data" and "no retries" must not look alike.
     */
    private val retries = Bucket.entries.flatMap { bucket ->
        THROTTLED.map { status ->
            (bucket to status) to
                Counter.builder(RETRIES).tag(BUCKET, bucket.tag).tag(STATUS, status.toString()).register(registry)
        }
    }.toMap()

    fun retryIssued(bucket: Bucket, status: Int) = retries.getValue(bucket to status).increment()

    /** Turns handed out on [bucket] — the evidence that a retry spent budget rather than skipping it. */
    fun requestsIssued(bucket: Bucket): Long = requests.getValue(bucket).count().toLong()

    /**
     * The connection cap, which only ever narrows (ADR-0004, plan Decision 8). A gauge rather than
     * a counter: the question it answers is "what is the cap now", and a drop from 2 to 1 is the
     * visible trace of a `509` that a request counter cannot show.
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

        /** The only statuses the interceptor retries; every other failure surfaces immediately. */
        val THROTTLED = listOf(429, 509)
    }
}

/** Micrometer tag values are conventionally lower-kebab; `CONTRACT_SEARCH` is not. */
val Bucket.tag: String get() = name.lowercase().replace('_', '-')
