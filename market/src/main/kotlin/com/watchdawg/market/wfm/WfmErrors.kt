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
 * Base type for every failure the warframe.market transport raises, so a caller can catch the
 * whole boundary in one place instead of listing its causes.
 *
 * The subtypes exist because C6's poll scheduler needs to tell three cases apart: "slow down"
 * (R1.3), "too many connections" (R1.4), and "the route answered badly" (R1.7).
 */
sealed class WfmException(message: String) : RuntimeException(message)

/**
 * The server refused the call because we asked for too much.
 *
 * [retryAfter] is the cooloff the server asked for, or null when it did not name one. A `429`
 * often omits `Retry-After` altogether.
 */
sealed class ThrottledException(val retryAfter: Duration?, message: String) : WfmException(message)

/** `429`: we were calling too fast. SPEC 9 treats one of these as a bug in our own limiter. */
class RateLimitedException(retryAfter: Duration?) :
    ThrottledException(
        retryAfter,
        "warframe.market rate limited (429), retry after ${retryAfter ?: "<unstated>"}",
    )

/**
 * `509`: too many connections open at once. Cloudflare signals this separately from `429` (R1.4).
 *
 * It is a distinct type from [RateLimitedException] because the remedy is different. We need
 * fewer calls at once, not slower ones.
 */
class ConcurrencyLimitedException(retryAfter: Duration?) :
    ThrottledException(
        retryAfter,
        "warframe.market refused a concurrent connection (509), retry after ${retryAfter ?: "<unstated>"}",
    )

/**
 * The only two statuses we retry. Everything else fails on the first attempt.
 *
 * Two places need this pair and they must not disagree. [WfmRateLimitInterceptor] uses it to spot
 * a refusal, and [WfmMetrics] uses it to register a counter per bucket and status at startup. Add
 * a status to one and not the other and you get either an unmetered retry or a counter that can
 * never move, so the pair is declared here once.
 */
enum class Throttle(val status: Int, val refusal: (Duration?) -> ThrottledException) {
    RATE_LIMITED(429, ::RateLimitedException),
    CONCURRENCY_LIMITED(509, ::ConcurrencyLimitedException),
    ;

    companion object {
        private val byStatus = entries.associateBy(Throttle::status)

        /** Null for any other status. Those responses are the caller's business, not ours. */
        fun of(status: Int): Throttle? = byStatus[status]
    }
}

/**
 * An error status whose body is not a v2 envelope, and often is not JSON at all. v1
 * `/items/{slug}/orders` answers `403` in plain text, and an upstream `502` arrives as a
 * Cloudflare HTML page (R1.7).
 *
 * We handle these at the transport so the body never reaches Jackson. If it did, a `403` would
 * surface as a deserialization crash naming some missing field, which says nothing about what
 * actually went wrong.
 *
 * [excerpt] is capped at [EXCERPT_LIMIT] characters. SPEC 9 says not to keep what the service does
 * not need, and a line or two is enough to tell a Cloudflare block from a route that has moved.
 */
class WfmHttpException(val status: HttpStatusCode, val contentType: MediaType?, val excerpt: String) :
    WfmException(describe(status, contentType, excerpt)) {

    companion object {
        const val EXCERPT_LIMIT = 200

        /** Reads at most 800 bytes of [response]. The rest of the body is discarded unread. */
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

/** Without this, the newlines in an HTML error page spread one failure over 40 log lines. */
private val WHITESPACE = Regex("\\s+")

private fun describe(status: HttpStatusCode, contentType: MediaType?, excerpt: String): String {
    val route = "warframe.market answered $status" + (contentType?.let { " ($it)" } ?: "")
    return if (excerpt.isEmpty()) "$route with an empty body" else "$route: $excerpt"
}

/**
 * Parses `Retry-After`, which RFC 9110 §10.2.3 allows in two forms: delta-seconds, or an HTTP-date.
 * The upstream sends both, so we handle both. Handling only delta-seconds would return `null` for
 * every date-form header, turning a cooloff the server asked for into an immediate retry.
 *
 * Returns `null` when the header is missing or unparseable. Never returns a negative duration; a
 * date already in the past counts as "now".
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
