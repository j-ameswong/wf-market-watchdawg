package com.watchdawg.market.wfm.ws

import com.watchdawg.market.wfm.WfmContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SocketProtocolTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `every captured listing parses and offline status remains offline`() {
        val protocol = SocketProtocol(mapper, WfmContext("pc", true))
        val frames = mapper.readTree(javaClass.getResourceAsStream("/fixtures/v2-socket/new-orders.json"))
        val parsed = (0 until frames.size()).map { protocol.parse(mapper.writeValueAsString(frames[it]), "t1-capture") }
        val listings = parsed.filterIsInstance<SocketMessage.Listing>()
        assertEquals(74, listings.size)
        assertEquals(29, listings.count { !it.order.ownerOnline })
        assertEquals(1, parsed.count { it == SocketMessage.Subscribed })
    }

    @Test
    fun `rejections and mismatched acknowledgements have distinct outcomes`() {
        val protocol = SocketProtocol(mapper, WfmContext("pc", false))
        assertIs<SocketMessage.Rejected>(
            protocol.parse("""{"route":"$SUBSCRIBE:error","id":"id","payload":"bad"}""", "id"),
        )
        assertIs<SocketMessage.Unknown>(protocol.parse("""{"route":"$SUBSCRIBE:ok","id":"old"}""", "id"))
    }
}
