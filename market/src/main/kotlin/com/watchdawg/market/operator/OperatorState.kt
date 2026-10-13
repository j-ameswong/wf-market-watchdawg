package com.watchdawg.market.operator

import com.watchdawg.market.contracts.ContractWatch
import com.watchdawg.market.watch.Watch
import com.watchdawg.market.watch.WatchEntry
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

data class OperatorDocument(
    val watches: List<WatchEntry> = emptyList(),
    val contracts: List<ContractWatch> = emptyList(),
    val dailyCeiling: Int = 50,
    val allWeapons: Boolean = false,
)

data class ConfigurationView(val revision: Long, val document: OperatorDocument)

/** One immutable, committed snapshot. Startup YAML remains the fallback until the first edit. */
@Component
@DependsOnDatabaseInitialization
class OperatorState(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {
    data class Snapshot(val view: ConfigurationView, val watches: List<Watch>?)

    @Volatile private var current: Snapshot? = readPersisted()
    val snapshot: Snapshot? get() = current

    fun publish(view: ConfigurationView, watches: List<Watch>) {
        current = Snapshot(view, watches)
    }

    fun activeContracts(fallback: List<ContractWatch>): List<ContractWatch> =
        (snapshot?.view?.document?.contracts ?: fallback).filter { it.enabled }

    fun ceiling(fallback: Int): Int = snapshot?.view?.document?.dailyCeiling ?: fallback

    fun allWeapons(fallback: Boolean): Boolean = snapshot?.view?.document?.allWeapons ?: fallback

    /** Also used by the database-reset test harness, so in-memory state follows the restored DB. */
    fun reload() {
        current = readPersisted()
    }

    private fun readPersisted(): Snapshot? = jdbc.query(
        "select revision, document::text from operator_configuration order by revision desc limit 1",
    ) { rs, _ ->
        Snapshot(
            ConfigurationView(rs.getLong(1), mapper.readValue(rs.getString(2), OperatorDocument::class.java)),
            null,
        )
    }.singleOrNull()
}
