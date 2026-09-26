package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import com.watchdawg.market.wfm.ws.FakeSocketServer
import com.watchdawg.market.wfm.ws.SocketConnector
import com.watchdawg.market.wfm.ws.SocketFeed
import com.watchdawg.market.wfm.ws.WfmSocket
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * R1.8: crossplay is one setting. No call site may omit it, override it, or let a channel fall
 * back to its upstream default.
 *
 * The failure this guards against is silent: a mixed population invents a `vanished` for about 7%
 * of ingested orders (SPEC 2.7, ADR-0002). That is why the guard is structural rather than a
 * convention people are asked to remember.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CrossplayHeaderTest {

    @Autowired lateinit var wfmRestClient: RestClient

    @Autowired lateinit var props: WfmProperties

    @Autowired lateinit var context: WfmContext

    @Autowired lateinit var connector: SocketConnector

    @Autowired lateinit var feed: SocketFeed

    @Autowired lateinit var mapper: JsonMapper

    private lateinit var server: MockRestServiceServer
    private lateinit var client: RestClient

    @BeforeEach
    fun bindMockServer() {
        val builder = wfmRestClient.mutate()
        server = MockRestServiceServer.bindTo(builder).build()
        client = builder.build()
    }

    @Test
    fun `a request carries the configured context even when the call site sets its own`() {
        server.expect(requestTo(VERSIONS))
            .andExpect(onlyHeader(WfmContextInterceptor.CROSSPLAY, context.crossplay.toString()))
            .andExpect(onlyHeader(WfmContextInterceptor.PLATFORM, context.platform))
            .andExpect(onlyHeader(HttpHeaders.USER_AGENT, props.userAgent))
            .andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        client.get()
            .uri("/versions")
            .header(WfmContextInterceptor.CROSSPLAY, "false")
            .header(WfmContextInterceptor.PLATFORM, "xbox")
            .header(HttpHeaders.USER_AGENT, "curl/8.0")
            .retrieve()
            .body<String>()

        server.verify()
    }

    @Test
    fun `a request that names no context at all still carries it`() {
        server.expect(requestTo(VERSIONS))
            .andExpect(onlyHeader(WfmContextInterceptor.CROSSPLAY, context.crossplay.toString()))
            .andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))

        client.get().uri("/versions").retrieve().body<String>()

        server.verify()
    }

    @Test
    fun `WfmContext reads the one setting, so REST and the socket cannot diverge`() {
        assertEquals(props.crossplay, context.crossplay)
        assertEquals(props.platform, context.platform)
    }

    @Test
    fun `platform and crossplay have no Kotlin defaults, so an unset value fails startup`() {
        // A Kotlin default is how the old client came to send `false` while application.yaml
        // said `true`. If a parameter is optional here, a missing property binds silently to
        // whatever the code guessed.
        val optional = WfmProperties::class.primaryConstructor!!.parameters.filter { it.isOptional }.map { it.name }

        assertFalse("crossplay" in optional, "wfm.crossplay would bind to a Kotlin default")
        assertFalse("platform" in optional, "wfm.platform would bind to a Kotlin default")
    }

    @Test
    fun `the socket's subscription and the REST header quote the same crossplay, both ways (R1_8, R5_2)`() {
        for (crossplay in listOf(true, false)) {
            val quoted = WfmContext(props.platform, crossplay)
            val builder = RestClient.builder().requestInterceptor(WfmContextInterceptor(quoted, props.userAgent))
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo(VERSIONS))
                .andExpect(onlyHeader(WfmContextInterceptor.CROSSPLAY, crossplay.toString()))
                .andRespond(withSuccess(VERSIONS_JSON, APPLICATION_JSON))
            builder.build().get().uri(VERSIONS).retrieve().body<String>()
            server.verify()

            val socketServer = FakeSocketServer()
            val socket = WfmSocket(connector, socketServer.url, quoted, feed, WfmMetrics(SimpleMeterRegistry()), mapper)
            try {
                socket.start()
                assertEquals(crossplay, socketServer.nextSubscription()["crossplay"], "crossplay=$crossplay")
            } finally {
                socket.stop()
                socketServer.close()
            }
        }
    }

    /** [MockRestRequestMatchers.header] tolerates extra values, and appending is the failure to catch. */
    private fun onlyHeader(name: String, value: String) = RequestMatcher {
        assertEquals(listOf(value), it.headers[name], "header $name")
    }

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"

        val VERSIONS_JSON = """
            {"apiVersion":"2.0","data":{"apps":{"ios":"1.2.3"},
             "collections":{"items":"abc123","rivens":"def456"},"updatedAt":"2026-09-10T00:00:00Z"}}
        """.trimIndent()
    }
}
