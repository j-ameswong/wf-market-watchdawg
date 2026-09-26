package com.watchdawg.market.wfm

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Counts every `429` and `509` the transport sees, retried or not. [retries] misses a refusal
     * that surfaces at once, with a `Retry-After` over the ceiling or on the second attempt, so this
     * is the meter that shows whether ADR-0004's boundary held. Registered up front like [retries].
     */
    private val throttles = Bucket.entries.flatMap { bucket ->
        Throttle.entries.map { throttle ->
            (bucket to throttle) to Counter.builder(THROTTLES)
                .tag(BUCKET, bucket.tag)
                .tag(STATUS, throttle.status.toString())
                .register(registry)
        }
    }.toMap()

    /**
     * How late each poll round started against its fixed cadence (R6.4). Demand above the budget
     * shows up here, never as a `429` (R6.5).
     */
    private val pollLateness = Timer.builder(POLL_LATENESS).register(registry)

    /** Book polls by outcome. A throttled poll ends its round, so the items after it are not counted. */
    private val polls = PollOutcome.entries.associateWith {
        Counter.builder(POLLS).tag(OUTCOME, it.tag).register(registry)
    }

    /**
     * 1 while the socket holds a confirmed subscription, 0 otherwise (C5). A connection that is open
     * but not yet subscribed delivers nothing, so it reads 0.
     */
    private val socketConnected = AtomicInteger().also { registry.gauge(SOCKET_CONNECTED, it) }

    /** Whole socket messages by what they held (R12.2). */
    private val socketFrames = SocketFrame.entries.associateWith {
        Counter.builder(SOCKET_FRAMES).tag(OUTCOME, it.tag).register(registry)
    }

    /** Socket connections that ended while the socket was running, by why, each followed by a reconnect (R5.3). */
    private val socketReconnects = SocketEnd.entries.associateWith {
        Counter.builder(SOCKET_RECONNECTS).tag(REASON, it.tag).register(registry)
    }

    /** Gap-fills from `/v2/orders/recent` by outcome (R5.4). */
    private val gapFills = GapFillOutcome.entries.associateWith {
        Counter.builder(SOCKET_GAPFILLS).tag(OUTCOME, it.tag).register(registry)
    }

    fun pollRoundStarted(lateness: Duration) = pollLateness.record(lateness)

    fun socketReconnect(reason: SocketEnd) = socketReconnects.getValue(reason).increment()

    fun gapFill(outcome: GapFillOutcome) = gapFills.getValue(outcome).increment()

    fun socketConnected(connected: Boolean) = socketConnected.set(if (connected) 1 else 0)

    fun socketFrame(frame: SocketFrame) = socketFrames.getValue(frame).increment()

    fun polled(outcome: PollOutcome) = polls.getValue(outcome).increment()

    fun requestIssued(bucket: Bucket, waited: Duration) {
        requests.getValue(bucket).increment()
        waits.getValue(bucket).record(waited)
    }

    fun retryIssued(bucket: Bucket, throttle: Throttle) = retries.getValue(bucket to throttle).increment()

    fun throttled(bucket: Bucket, throttle: Throttle) = throttles.getValue(bucket to throttle).increment()

    /** Reads the attempt count back. This is how tests check that a retry really did spend budget. */
    fun requestsIssued(bucket: Bucket): Long = requests.getValue(bucket).count().toLong()

    /**
     * Tracks the connection cap, which only ever narrows (ADR-0004).
     *
     * A gauge rather than a counter, because the question it answers is "what is the cap right
     * now". A drop from 2 to 1 is the only visible trace of a `509`, and no request counter can
     * show it.
     */
    fun trackConcurrency(currentLimit: () -> Int) {
        // Micrometer only holds a weak reference to a gauge's source. Keep a strong one here, or
        // the lambda gets collected and the gauge starts reporting NaN.
        concurrencySource = currentLimit
        registry.gauge(CONCURRENCY, currentLimit) { it().toDouble() }
    }

    private var concurrencySource: (() -> Int)? = null

    private fun <T> byBucket(meter: (String) -> T): Map<Bucket, T> = Bucket.entries.associateWith { meter(it.tag) }

    private companion object {
        const val REQUESTS = "wfm.requests"
        const val WAIT = "wfm.request.wait"
        const val RETRIES = "wfm.retries"
        const val THROTTLES = "wfm.throttles"
        const val CONCURRENCY = "wfm.concurrency.limit"
        const val POLL_LATENESS = "wfm.poll.lateness"
        const val POLLS = "wfm.polls"
        const val SOCKET_CONNECTED = "wfm.socket.connected"
        const val SOCKET_FRAMES = "wfm.socket.frames"
        const val SOCKET_RECONNECTS = "wfm.socket.reconnects"
        const val SOCKET_GAPFILLS = "wfm.socket.gapfills"
        const val REASON = "reason"
        const val OUTCOME = "outcome"
        const val BUCKET = "bucket"
        const val STATUS = "status"
    }
}

/** What one book poll came to (C6). */
enum class PollOutcome {
    RECONCILED,

    /** A book at least as new was already reconciled for the item (R4.9). */
    STALE,
    FAILED,
    THROTTLED,
    ;

    val tag: String get() = name.lowercase()
}

/** What one whole socket message held (C5). */
enum class SocketFrame {
    /** A new order, recorded or skipped by ingest as any partial observation is. */
    ORDER,

    /** A subscription reply or a heartbeat. */
    CONTROL,

    /** Malformed, an unknown route, or an order that would not bind or record (R5.6). */
    SKIPPED,
    ;

    val tag: String get() = name.lowercase()
}

/** Why a socket connection ended (C5, decision 6). */
enum class SocketEnd {
    /** It did not connect, or not within the connect deadline. */
    UNREACHABLE,

    /** Its subscription was not confirmed within the deadline. Heartbeats do not count. */
    UNCONFIRMED,

    /** The subscription was refused for a reason other than already having it. */
    REFUSED,

    /** Subscribed, it received nothing for the silence deadline. */
    SILENT,

    /** The server closed it. */
    CLOSED,

    /** The transport failed. */
    FAILED,
    ;

    val tag: String get() = name.lowercase()
}

/** What one gap-fill came to (R5.4). */
enum class GapFillOutcome {
    FILLED,

    /** The last gap-fill was under a minute ago, or a throttle's `Retry-After` has not passed. */
    SKIPPED,
    THROTTLED,
    FAILED,
    ;

    val tag: String get() = name.lowercase()
}

/** Micrometer tag values are conventionally lower-kebab-case, and `CONTRACT_SEARCH` is not. */
val Bucket.tag: String get() = name.lowercase().replace('_', '-')
