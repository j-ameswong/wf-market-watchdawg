package com.watchdawg.market.operator

import com.watchdawg.market.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OperatorHttpTest {
    @Autowired lateinit var mvc: MockMvc

    private val body = """{"revision":0,"document":{"watches":[],"contracts":[],"dailyCeiling":30}}"""

    @Test
    fun `browser JSON updates require a custom header and same origin`() {
        mvc.perform(put("/api/operator/configuration").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        mvc.perform(
            put("/api/operator/configuration").header("X-Watchdawg-Request", "1")
                .header("Origin", "https://untrusted.example").contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isForbidden)
        mvc.perform(
            put("/api/operator/configuration").header("X-Watchdawg-Request", "1")
                .header("Host", "localhost").header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1))
        mvc.perform(
            put("/api/operator/configuration").header("X-Watchdawg-Request", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `untrusted hosts are rejected and reads carry no-store and browser protections`() {
        mvc.perform(
            get("/api/operator/configuration").with {
                it.serverName = "attacker.example"
                it
            },
        )
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/operator/configuration"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Content-Security-Policy"))
            .andExpect(jsonPath("$.document.dailyCeiling").value(50))
    }

    @Test
    fun `encoded and noncanonical mutation routes cannot bypass browser checks`() {
        for (path in listOf(
            "/%61pi/operator/configuration",
            "/api/operator/configuration",
            "/actuator/../api/operator/configuration",
        )) {
            mvc.perform(
                put(java.net.URI(path)).header("Origin", "https://untrusted.example")
                    .contentType(MediaType.APPLICATION_JSON).content(body),
            )
                .andExpect(status().isForbidden)
        }
    }
}
