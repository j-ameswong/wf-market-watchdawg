package com.watchdawg.market.wfm.ws

import com.watchdawg.market.ingest.OrderIngest
import com.watchdawg.market.ingest.Source
import com.watchdawg.market.wfm.Order
import com.watchdawg.market.wfm.SocketFrame
import com.watchdawg.market.wfm.WfmMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** What one whole message meant, and how [WfmMetrics] counts it. */
enum class Received(val frame: SocketFrame) {
    ORDER(SocketFrame.ORDER),
    HEARTBEAT(SocketFrame.CONTROL),
    SUBSCRIBED(SocketFrame.CONTROL),

    /** The subscription was refused for a reason other than already having it. */
    REFUSED(SocketFrame.CONTROL),
    SKIPPED(SocketFrame.SKIPPED),
}

/**
 * Reads whole socket messages and records each new order through the partial ingest path, as
 * `source=ws` (R5.5). Every item's orders are recorded, watched or not (decision 2, ADR-0022).
 *
 * Nothing a message holds is fatal: a malformed message, an unknown route or an order that will not
 * bind is logged and skipped, and the connection carries on (R5.6).
 */
@Component
class SocketFeed(private val ingest: OrderIngest, private val mapper: JsonMapper, private val metrics: WfmMetrics) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [arrivedAt] is when the message's last frame arrived: what its order is observed at. */
    fun receive(text: String, arrivedAt: Instant): Received =
        classify(text, arrivedAt).also { metrics.socketFrame(it.frame) }

    private fun classify(text: String, arrivedAt: Instant): Received {
        val message = try {
            mapper.readValue(text, SocketMessage::class.java)
        } catch (e: JacksonException) {
            return skip("a malformed message: ${e.originalMessage}")
        }
        return when (message.route) {
            NEW_ORDER -> order(message, arrivedAt)
            SUBSCRIBED -> Received.SUBSCRIBED
            SUBSCRIBE_FAILED -> subscribeFailed(message)
            ONLINE_REPORT -> Received.HEARTBEAT
            else -> skip("unknown route ${message.route}")
        }
    }

    private fun order(message: SocketMessage, arrivedAt: Instant): Received {
        val order = try {
            mapper.treeToValue(message.payload ?: return skip("a new order with no payload"), Order::class.java)
        } catch (e: JacksonException) {
            return skip("a new order that does not bind: ${e.originalMessage}")
        }
        return try {
            ingest.ingestPartial(listOf(order), Source.WS, arrivedAt)
            Received.ORDER
        } catch (e: Exception) {
            skip("order ${order.id} was not recorded: ${e.message}")
        }
    }

    private fun subscribeFailed(message: SocketMessage): Received {
        val reason = message.payload?.takeIf { it.isString }?.stringValue()
        if (reason == ALREADY_SUBSCRIBED) return Received.SUBSCRIBED
        log.warn("the socket refused the new-order subscription: {}", reason ?: message.payload)
        return Received.REFUSED
    }

    private fun skip(what: String): Received {
        log.warn("skipped {} from the socket", what)
        return Received.SKIPPED
    }
}
