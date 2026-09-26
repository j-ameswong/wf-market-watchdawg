package com.watchdawg.market.wfm.ws

import com.watchdawg.market.wfm.WfmContext
import tools.jackson.databind.JsonNode

/**
 * The socket's envelope (`docs/v2/websockets/overview.mdx`). Only what the feed reads is declared.
 *
 * A new order's `payload` is the order itself, in the same shape as REST's, with no `data` envelope
 * around it (T1's capture, `fixtures/v2-socket/new-orders.json`).
 */
data class SocketMessage(val route: String, val payload: JsonNode? = null, val id: String? = null)

/** The `subscribe/newOrders` command. */
data class Subscribe(val id: String, val payload: OrderFilter) {
    val route: String get() = SUBSCRIBE
}

/**
 * Both fields are always written. The socket defaults `crossplay` to `true` and REST to `false`,
 * so leaving it out would watch a different population on each channel (R1.8, R5.2).
 */
data class OrderFilter(val platform: String, val crossplay: Boolean)

fun subscribe(context: WfmContext, id: String) = Subscribe(id, OrderFilter(context.platform, context.crossplay))

const val WFM_SUBPROTOCOL = "wfm"
const val SUBSCRIBE = "@wfm|cmd/subscribe/newOrders"
const val SUBSCRIBED = "$SUBSCRIBE:ok"
const val SUBSCRIBE_FAILED = "$SUBSCRIBE:error"
const val ALREADY_SUBSCRIBED = "app.errors.alreadySubscribed"
const val NEW_ORDER = "@wfm|event/subscriptions/newOrder"

/** Broadcast about every 30 seconds to every connection, subscribed or not. */
const val ONLINE_REPORT = "@wfm|event/reports/online"
