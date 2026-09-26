package com.watchdawg.market.notify

import com.watchdawg.market.watch.Outgoing
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.math.RoundingMode
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter

/** One push, as any notification host would take it. */
data class Push(val title: String, val message: String, val priority: Int, val click: String)

/**
 * Sends a push to a real topic. Host-agnostic, so a self-hosted ntfy replaces [NtfyNotifier]
 * without touching the rules (R10.7).
 *
 * Returns only once the host accepted the push with a 2xx, and throws otherwise (R10.2).
 */
fun interface Notifier {
    fun send(topic: String, push: Push)
}

/**
 * Publishes to ntfy as JSON, to the server's root with the topic in the body. The topic is a
 * credential (R10.6), and a URL is what exception messages and access logs carry, so it never goes
 * in one.
 *
 * The body is serialised here and sent as bytes: `RestClient` logs an object body's contents at
 * DEBUG, topic included, but a byte array only by its type.
 */
class NtfyNotifier(private val client: RestClient, private val mapper: JsonMapper) : Notifier {
    override fun send(topic: String, push: Push) {
        val body = mapper.writeValueAsBytes(
            mapOf(
                "topic" to topic,
                "title" to push.title,
                "message" to push.message,
                "priority" to push.priority,
                "click" to push.click,
            ),
        )
        client.post()
            .uri("/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity()
    }
}

/**
 * What the operator sees (R10.4): the item, its dimensions, the unit price and the lot, when the
 * listing was seen, and a click-through to the item page, where the listing and its seller are.
 * The seller's name is not stored, so it is not here.
 */
fun Outgoing.toPush(): Push {
    val unit = unitPrice.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    val dims = if (dimensions.isEmpty()) "" else " ($dimensions)"
    val lot = when (val size = perTrade ?: 1) {
        1 -> "$platinum plat"
        else -> "$platinum plat for a lot of $size"
    }
    return Push(
        title = "$itemName$dims at $unit plat",
        message = "$lot. Seen ${SEEN.format(seenAt)} UTC. Watch: $watch.",
        priority = priority,
        click = "https://warframe.market/items/$slug",
    )
}

private val SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(UTC)
