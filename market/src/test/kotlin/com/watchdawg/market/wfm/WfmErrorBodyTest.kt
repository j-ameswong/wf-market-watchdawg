package com.watchdawg.market.wfm

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus.BAD_GATEWAY
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.MediaType.TEXT_HTML
import org.springframework.http.MediaType.TEXT_PLAIN
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R1.7: an error body should surface as an HTTP failure, not a parse failure.
 *
 * Without that, every case below reaches Jackson and comes back as a deserialization crash naming
 * some missing field, which tells you nothing about the `403` that actually happened.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WfmErrorBodyTest {

    @Autowired lateinit var wfmRestClient: RestClient

    private lateinit var server: MockRestServiceServer
    private lateinit var client: WfmClient

    @BeforeEach
    fun bindMockServer() {
        val builder = wfmRestClient.mutate()
        server = MockRestServiceServer.bindTo(builder).build()
        client = WfmClient(builder.build())
    }

    @Test
    fun `a plain-text 403 surfaces a typed error, not a Jackson exception`() {
        // Exactly what v1 /items/{slug}/orders answers, and that is the route C7 will want.
        server.expect(requestTo(VERSIONS))
            .andRespond(withStatus(FORBIDDEN).body(V1_FORBIDDEN).contentType(TEXT_PLAIN))

        val thrown = assertFailsWith<WfmHttpException> { client.getVersions() }

        assertEquals(FORBIDDEN, thrown.status)
        assertEquals(TEXT_PLAIN, thrown.contentType)
        assertTrue(thrown.excerpt.startsWith("You do not have permission"), thrown.excerpt)
        server.verify()
    }

    @Test
    fun `an HTML 502 surfaces the same typed error`() {
        server.expect(requestTo(VERSIONS))
            .andRespond(withStatus(BAD_GATEWAY).body(CLOUDFLARE_HTML).contentType(TEXT_HTML))

        val thrown = assertFailsWith<WfmHttpException> { client.getVersions() }

        assertEquals(BAD_GATEWAY, thrown.status)
        // Collapsed to one line: an HTML page would otherwise spread one failure across the log.
        assertFalse(thrown.excerpt.contains("\n"), thrown.excerpt)
        assertTrue(thrown.excerpt.contains("Bad gateway"), thrown.excerpt)
        server.verify()
    }

    @Test
    fun `the excerpt is capped and the full body is not carried`() {
        val body = "detail ".repeat(1_000) // ~7kB
        server.expect(requestTo(VERSIONS)).andRespond(withServerError().body(body).contentType(TEXT_PLAIN))

        val thrown = assertFailsWith<WfmHttpException> { client.getVersions() }

        assertEquals(WfmHttpException.EXCERPT_LIMIT, thrown.excerpt.length)
        assertTrue(
            thrown.message.orEmpty().length < body.length,
            "the message carries the whole body -- SPEC 9 forbids accumulating what we don't need",
        )
        server.verify()
    }

    @Test
    fun `an error status with no body at all still reads cleanly`() {
        server.expect(requestTo(VERSIONS)).andRespond(withServerError())

        val thrown = assertFailsWith<WfmHttpException> { client.getVersions() }

        assertEquals("", thrown.excerpt)
        assertTrue(thrown.message.orEmpty().endsWith("with an empty body"), thrown.message.orEmpty())
    }

    @Test
    fun `a well-formed envelope carrying an error still throws the envelope error, unchanged`() {
        // A 200 carrying error/data-null is a different failure from a bad HTTP status. T5 must
        // not have folded it into the transport's own type.
        server.expect(requestTo(VERSIONS)).andRespond(withSuccess(ERROR_JSON, APPLICATION_JSON))

        val thrown = assertFails { client.getVersions() }

        assertFalse(thrown is WfmHttpException, "an envelope error is not an HTTP error")
        assertTrue(thrown.message.orEmpty().contains("wfm api error"), thrown.message.orEmpty())
        server.verify()
    }

    private companion object {
        const val VERSIONS = "https://api.warframe.market/v2/versions"

        const val V1_FORBIDDEN = "You do not have permission to access this resource."

        val CLOUDFLARE_HTML = """
            <html>
            <head><title>502 Bad Gateway</title></head>
            <body>
            <h1>Bad gateway</h1>
            </body>
            </html>
        """.trimIndent()

        const val ERROR_JSON = """{"apiVersion":"2.0","error":{"request":["versions"]}}"""
    }
}
