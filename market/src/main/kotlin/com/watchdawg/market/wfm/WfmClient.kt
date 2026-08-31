package com.watchdawg.market.wfm

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class WfmClient(private val client: RestClient) {

	// GET /v2/versions
	fun getVersions(): Versions {
		val envelope = client.get()
			.uri("/versions")
			.retrieve()
			.body<Envelope<Versions>>()
			?: error("empty body from /versions")

		return envelope.data ?: error("warframe.market error on /versions: ${envelope.error}")
	}
}
