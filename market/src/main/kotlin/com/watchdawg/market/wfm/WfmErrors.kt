package com.watchdawg.market.wfm

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.text.Charsets.UTF_8

/**
 * Every failure the warframe.market transport raises. One base type so a caller can catch the
 * boundary rather than enumerate its causes; the subtypes exist because C6's poll scheduler has to
 * tell "slow down" (R1.3) from "too many connections" (R1.4) from "the route answered badly" (R1.7).
 */
sealed class WfmException(message: String) : RuntimeException(message)

/**
 * The server refused this call for spending too much budget. Carries the cooloff it asked for, when
 * it named one — a `429` frequently omits `Retry-After` entirely.
 */
sealed class ThrottledException(val retryAfter: Duration?, message: String) : WfmException(message)

/** `429` — the pacing itself was too fast. SPEC 9 treats one of these as a bug in our limiter. */
class RateLimitedException(retryAfter: Duration?) :
    ThrottledException(
        retryAfter,
        "warframe.market rate limited (429), retry after ${retryAfter ?: "<unstated>"}",
    )

/**
 * `509` — too many connections open at once, which Cloudflare signals separately from `429`
 * (R1.4). Distinct from [RateLimitedException] because the remedy is fewer concurrent calls, not
 * slower ones.
 */
class ConcurrencyLimitedException(retryAfter: Duration?) :
    ThrottledException(
        retryAfter,
        "warframe.market refused a concurrent connection (509), retry after ${retryAfter ?: "<unstated>"}",
    )

/**
 * The two refusals the transport answers with a retry, and the only ones — every other status
 * surfaces on the first attempt.
 *
 * Declared once because two places need the pair and they must not drift: [WfmRateLimitInterceptor]
 * decides whether a response is a refusal at all, and [WfmMetrics] pre-registers a counter per
 * bucket and status at startup. A status added to one but not the other is either an unmetered
 * retry or a meter that can never move.
 */
enum class Throttle(val status: Int, val refusal: (Duration?) -> ThrottledException) {
    RATE_LIMITED(429, ::RateLimitedException),
    CONCURRENCY_LIMITED(509, ::ConcurrencyLimitedException),
    ;

    companion object {
        private val byStatus = entries.associateBy(Throttle::status)

        /** Null for a status the transport has no opinion on — that response belongs to the caller. */
        fun of(status: Int): Throttle? = byStatus[status]
    }
}

/**
 * An error status whose body is not a v2 envelope, and often not JSON at all: v1
 * `/items/{slug}/orders` answers `403` as plain text and an upstream `502` arrives as Cloudflare's
 * HTML (R1.7). Handled at the transport so that body never reaches Jackson, where it would surface
 * as a deserialization crash naming a field rather than as the HTTP failure it is.
 *
 * [excerpt] is capped at [EXCERPT_LIMIT] characters. SPEC 9 forbids accumulating what the service
 * does not need, and a couple of lines is enough to tell a Cloudflare block from a route that moved.
 */
class WfmHttpException(val status: HttpStatusCode, val contentType: MediaType?, val excerpt: String) :
    WfmException(describe(status, contentType, excerpt)) {

    companion object {
        const val EXCERPT_LIMIT = 200

        /** Reads at most a few hundred bytes of [response]; the rest of the body is dropped unread. */
        fun of(response: ClientHttpResponse): WfmHttpException {
            val contentType = response.headers.contentType
            val charset = contentType?.charset ?: UTF_8
            // 4 bytes per character is UTF-8's worst case, so this always covers the cap.
            val head = runCatching { response.body.readNBytes(EXCERPT_LIMIT * 4) }.getOrDefault(ByteArray(0))
            val excerpt = head.toString(charset).replace(WHITESPACE, " ").trim().take(EXCERPT_LIMIT)
            return WfmHttpException(response.statusCode, contentType, excerpt)
        }
    }
}

/** Newlines in an HTML error page would otherwise spread one failure over 40 log lines. */
private val WHITESPACE = Regex("\\s+")

private fun describe(status: HttpStatusCode, contentType: MediaType?, excerpt: String): String {
    val route = "warframe.market answered $status" + (contentType?.let { " ($it)" } ?: "")
    return if (excerpt.isEmpty()) "$route with an empty body" else "$route: $excerpt"
}

/**
 * `RFC 9110 §10.2.3` allows `Retry-After` in two forms — delta-seconds or an HTTP-date — and the
 * upstream sends both. Reading only the first silently yields `null` for the second, which is how a
 * stated cooloff turns into an immediate retry.
 *
 * Returns `null` when the header is absent or unparseable, and never a negative duration: a date
 * already in the past means "now".
 */
fun retryAfterOf(headers: HttpHeaders, clock: Clock): Duration? {
    val raw = headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim().orEmpty()
    if (raw.isEmpty()) return null

    raw.toLongOrNull()?.let { return maxOf(Duration.ZERO, Duration.ofSeconds(it)) }

    return try {
        val until = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        maxOf(Duration.ZERO, Duration.between(clock.instant(), until))
    } catch (_: DateTimeParseException) {
        null
    }
}
