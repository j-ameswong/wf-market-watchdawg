package com.watchdawg.market

import com.watchdawg.market.store.CollectionVersionRepository
import com.watchdawg.market.wfm.WfmClient
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.time.Instant

@SpringBootApplication
class MarketApplication(
    private val wfmClient: WfmClient,
    private val versions: CollectionVersionRepository
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(MarketApplication::class.java)

    override fun run(vararg args: String) {
        var fresh = wfmClient.getVersions()
        var c = fresh.collections
        var rows = listOfNotNull(
            c.items?.let { "items" to it },
            c.liches?.let { "liches" to it },
            c.locations?.let { "locations" to it },
            c.missions?.let { "missions" to it },
            c.npcs?.let { "npcs" to it },
            c.rivens?.let { "rivens" to it },
            c.sisters?.let { "sisters" to it },
        )
        
        rows.forEach { (name, hash) ->
            log.info("$hash $name")
            versions.upsert(name, hash, fresh.updatedAt)
        }
    }
}

fun main(args: Array<String>) {
	runApplication<MarketApplication>(*args)
}
