package com.watchdawg.market.operator

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/operator")
class OperatorReadController(private val queries: OperatorQueries) {
    @GetMapping("/overview")
    fun overview() = queries.overview()

    @GetMapping("/host-settings")
    fun settings() = queries.hostSettings()

    @GetMapping("/items")
    fun items(@RequestParam(defaultValue = "") q: String) = queries.items(q)

    @GetMapping("/contract-catalog")
    fun contracts() = queries.contractCatalog()

    @GetMapping("/markets")
    fun markets(@RequestParam item: String) = queries.markets(item)

    @GetMapping("/statistics")
    fun statistics(
        @RequestParam market: Long,
        @RequestParam(defaultValue = "30") days: Int,
        @RequestParam(defaultValue = "closed") section: String,
        @RequestParam(defaultValue = "daily") granularity: String,
        @RequestParam(defaultValue = "") side: String,
        @RequestParam(defaultValue = "true") crossplay: Boolean,
    ) = queries.statistics(market, days, section, granularity, side, crossplay)

    @GetMapping("/auctions")
    fun auctions(@RequestParam kind: String, @RequestParam weapon: String) = queries.auctions(kind, weapon)
}
