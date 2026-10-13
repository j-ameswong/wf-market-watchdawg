package com.watchdawg.market.operator

import com.watchdawg.market.contracts.ContractProperties
import com.watchdawg.market.contracts.asWatch
import com.watchdawg.market.notify.NotifyProperties
import com.watchdawg.market.notify.checkTopics
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.watch.AlertProperties
import com.watchdawg.market.watch.InvalidWatchException
import com.watchdawg.market.watch.Resolution
import com.watchdawg.market.watch.WatchProperties
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.watch.resolveWatches
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

@Service
class OperatorConfiguration(
    private val state: OperatorState,
    private val initial: WatchProperties,
    private val contracts: ContractProperties,
    private val alerts: AlertProperties,
    private val notify: NotifyProperties,
    private val items: ItemRepository,
    private val jdbc: JdbcTemplate,
    private val mapper: JsonMapper,
    transactions: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactions)

    fun current(): ConfigurationView = state.snapshot?.view ?: ConfigurationView(
        0,
        OperatorDocument(initial.watches, contracts.watches, alerts.dailyCeiling, contracts.allWeapons),
    )

    /** Serializes publication with competing HTTP edits; DB locking also rejects stale revisions. */
    @Synchronized
    fun save(request: ConfigurationView): ConfigurationView {
        val doc = request.document
        require(doc.dailyCeiling in 0..10000) { "Daily alert ceiling must be between 0 and 10000." }
        require(doc.watches.size + doc.contracts.size <= 500) { "At most 500 watches are supported." }
        val names = doc.watches.map { it.name } + doc.contracts.map { it.name }
        require(names.distinct().size == names.size) { "Item and contract watch names must be unique." }
        require(names.all { it.length in 1..80 && it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*")) }) {
            "Watch names must be 1–80 letters, digits, hyphens or underscores."
        }
        val resolution = resolveWatches(doc.watches, items::findBySlug)
        val resolved = when (resolution) {
            is Resolution.Resolved -> resolution.watches

            is Resolution.MissingItems -> throw InvalidWatchException(
                "Unknown catalog items: ${resolution.slugs.joinToString()}",
            )
        }
        for (watch in doc.contracts) {
            require(
                jdbc.queryForObject(
                    "select count(*) from contract_weapon where kind = ? and slug = ?",
                    Int::class.java,
                    watch.kind,
                    watch.weapon,
                ) == 1,
            ) { "Unknown ${watch.kind} weapon '${watch.weapon}'." }
            for (attribute in watch.positive.keys + watch.negative) {
                require(
                    jdbc.queryForObject(
                        "select count(*) from contract_attribute where slug = ?",
                        Int::class.java,
                        attribute,
                    ) == 1,
                ) { "Unknown riven attribute '$attribute'." }
            }
        }
        checkTopics(Watches(resolved + doc.contracts.map { it.asWatch() }), notify.topics)
        val enabled = doc.watches.filter { it.enabled }.map { it.name }.toSet()
        val view = ConfigurationView(request.revision + 1, doc)
        tx.executeWithoutResult {
            // Same lock as admission: configuration invalidation cannot race baseline state writes.
            jdbc.execute("select pg_advisory_xact_lock(726193504)")
            val latest = jdbc.queryForObject(
                "select coalesce(max(revision), 0) from operator_configuration",
                Long::class.java,
            )!!
            if (latest != request.revision) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Configuration changed. Reload before saving again.")
            }
            jdbc.update(
                "insert into operator_configuration(revision, document) values (?, ?::jsonb)",
                view.revision,
                mapper.writeValueAsString(doc),
            )
            jdbc.update("delete from baseline_budget_epoch")
            jdbc.update("delete from baseline_shadow")
        }
        state.publish(view, resolved.filter { it.name in enabled })
        return view
    }
}
