package com.watchdawg.market.operator

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/** Tailnet access is the authentication boundary. Browser mutations must be same-origin JSON. */
@Component
class OperatorHttpBoundary(
    @Value("\${watchdawg.operator.allowed-hosts:localhost,127.0.0.1,::1,homelab}") hosts: String,
) : OncePerRequestFilter() {
    private val allowed = hosts.split(',').map { it.trim().lowercase() }.toSet()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "same-origin")
        response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
        )
        response.setHeader("Cache-Control", "no-store")
        if (request.serverName.lowercase().removeSurrounding("[", "]") !in allowed) {
            response.sendError(403, "Unrecognized operator host")
            return
        }
        // Apply to every mutation, including encoded paths before MVC route normalization.
        if (request.method !in setOf("GET", "HEAD")) {
            val origin = request.getHeader("Origin")
            val expected = URI("${request.scheme}://${request.getHeader("Host")}")
            val sameOrigin = origin == null || runCatching {
                val actual = URI(origin)
                actual.scheme == expected.scheme && actual.host == expected.host && port(actual) == port(expected) &&
                    actual.rawUserInfo == null && actual.rawQuery == null && actual.rawFragment == null &&
                    actual.rawPath.isNullOrEmpty()
            }.getOrDefault(false)
            val fetchSite = request.getHeader("Sec-Fetch-Site")
            if (request.getHeader("X-Watchdawg-Request") != "1" || !sameOrigin ||
                (fetchSite != null && fetchSite !in setOf("same-origin", "none"))
            ) {
                response.sendError(403, "A same-origin operator request is required")
                return
            }
        }
        chain.doFilter(request, response)
    }

    private fun port(uri: URI): Int = if (uri.port >= 0) {
        uri.port
    } else if (uri.scheme == "https") {
        443
    } else {
        80
    }
}
