package com.watchdawg.market.wfm.ws

import com.watchdawg.market.wfm.Order
import com.watchdawg.market.wfm.WfmContext
import tools.jackson.databind.json.JsonMapper

const val SUBSCRIBE = "@wfm|cmd/subscribe/newOrders"
const val NEW_ORDER = "@wfm|event/subscriptions/newOrder"
const val ONLINE_REPORT = "@wfm|event/reports/online"

sealed interface SocketMessage {
    data class Listing(val order: Order) : SocketMessage
    data object Subscribed : SocketMessage
    data object Rejected : SocketMessage
    data object Heartbeat : SocketMessage
    data object Unknown : SocketMessage
}

class SocketProtocol(private val mapper: JsonMapper, private val context: WfmContext) {
    fun subscribe(id: String): String = mapper.writeValueAsString(
        mapOf(
            "route" to SUBSCRIBE,
            "id" to id,
            "payload" to mapOf("platform" to context.platform, "crossplay" to context.crossplay),
        ),
    )

    fun parse(text: String, subscriptionId: String): SocketMessage {
        val frame = mapper.readTree(text)
        require(frame.isObject) { "socket envelope must be an object" }
        return when (frame.path("route").asText()) {
            NEW_ORDER -> SocketMessage.Listing(mapper.treeToValue(frame.path("payload"), Order::class.java))

            "$SUBSCRIBE:ok" -> if (frame.path("id").asText() ==
                subscriptionId
            ) {
                SocketMessage.Subscribed
            } else {
                SocketMessage.Unknown
            }

            "$SUBSCRIBE:error" -> when {
                frame.path("id").asText() != subscriptionId -> SocketMessage.Unknown
                frame.path("payload").asText() == "app.errors.alreadySubscribed" -> SocketMessage.Subscribed
                else -> SocketMessage.Rejected
            }

            ONLINE_REPORT -> SocketMessage.Heartbeat

            else -> SocketMessage.Unknown
        }
    }
}
