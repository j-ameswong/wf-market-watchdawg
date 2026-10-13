package com.watchdawg.market.contracts

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.notify.NotifyProperties
import com.watchdawg.market.notify.checkTopics
import com.watchdawg.market.poll.Cadence
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.ThrottledException
import com.watchdawg.market.wfm.WfmHttpException
import com.watchdawg.market.wfm.WfmLegacyClient
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ContractSync(
    private val props: ContractProperties,
    private val client: WfmLegacyClient,
    private val ingest: ContractIngest,
    private val jdbc: JdbcTemplate,
    watches: Watches,
    private val notify: NotifyProperties,
    registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val cadence = Cadence(props.interval, props.initialDelay, clock)
    private val counts = listOf("success", "failed", "throttled").associateWith {
        Counter.builder("wfm.contracts.sweeps").tag("outcome", it).register(registry)
    }
    init {
        require(
            props.watches.none { target ->
                watches.all.any { it.name == target.name }
            },
        ) { "item and contract watch names must be distinct" }
        // Topics are validated only when the collector is enabled, in initialize().
    }

    fun initialize(catalog: com.watchdawg.market.sync.CollectionSyncScheduler) {
        if (props.allWeapons || props.watches.isNotEmpty()) catalog.tick()
        for (watch in props.watches) {
            require(
                jdbc.queryForObject(
                    "select count(*) from contract_weapon where kind = ? and slug = ?",
                    Int::class.java,
                    watch.kind,
                    watch.weapon,
                ) == 1,
            ) { "contract watch '${watch.name}' names unknown weapon '${watch.weapon}'" }
            for (attribute in watch.positive.keys + watch.negative) {
                require(
                    jdbc.queryForObject(
                        "select count(*) from contract_attribute where slug = ?",
                        Int::class.java,
                        attribute,
                    ) ==
                        1,
                ) {
                    "contract watch '${watch.name}' names unknown attribute '$attribute'"
                }
            }
        }
        checkTopics(Watches(props.watches.map { it.asWatch() }), notify.topics)
    }

    private var detailCursor = 0

    fun targets(): List<Pair<String, String>> {
        val prioritized = props.watches.sortedByDescending { it.priority.ntfy }.map { it.kind to it.weapon }
        val all = if (props.allWeapons) {
            jdbc.query("select kind, slug from contract_weapon order by kind, slug") { rs, _ ->
                rs.getString(1) to
                    rs.getString(2)
            }
        } else {
            emptyList()
        }
        return (prioritized + all).distinct()
    }

    fun round() {
        val selected = targets()
        // Resume durable work before another search can throttle the sweep again.
        val detailsLeft = verifyPending(selected, props.detailBudget) ?: return
        for ((kind, weapon) in selected) {
            try {
                check(
                    jdbc.queryForObject(
                        "select count(*) from contract_weapon where kind = ? and slug = ?",
                        Int::class.java,
                        kind,
                        weapon,
                    ) ==
                        1,
                ) { "unknown contract weapon $kind/$weapon" }
                val at = clock.instant()
                val auctions = client.getAuctions(kind, weapon).auctions
                require(
                    auctions.all {
                        it.item.type == kind && it.item.weaponUrlName == weapon
                    },
                ) { "search returned a different target" }
                ingest.ingest(auctions, at)
                val present = auctions.mapTo(HashSet()) { it.id }
                // Search absence is only a verification candidate, never evidence of closure.
                ingest.queueVerification(kind, weapon, present)
                counts.getValue("success").increment()
            } catch (e: ThrottledException) {
                cadence.throttled(e.retryAfter)
                counts.getValue("throttled").increment()
                return
            } catch (e: Exception) {
                counts.getValue("failed").increment()
                log.warn("contract sweep {}/{} failed: {}", kind, weapon, e.javaClass.simpleName)
            }
        }
        verifyPending(selected, detailsLeft)
    }

    /** Null means throttled: retain the queue and let Cadence enforce the upstream backoff. */
    private fun verifyPending(selected: List<Pair<String, String>>, budget: Int): Int? {
        if (budget == 0 || selected.isEmpty()) return budget
        val at = clock.instant()
        val pending = selected.map { (kind, weapon) -> ingest.pendingVerification(kind, weapon, at) }
        if (pending.all { it.isEmpty() }) return budget
        val start = detailCursor % pending.size
        detailCursor = (start + 1) % pending.size
        val queues = (pending.drop(start) + pending.take(start)).map { java.util.ArrayDeque(it) }
        var left = budget
        while (left > 0 && queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (queue.isEmpty() || left == 0) continue
                val known = queue.removeFirst()
                left--
                val observed = clock.instant()
                // Failures must rotate too, without pretending that an auction was observed.
                ingest.verificationAttempt(known.id, observed, observed.plus(props.interval))
                try {
                    val auction = client.getAuction(known.id).auction
                    require(
                        auction.id == known.id && auction.item.type == known.item.type &&
                            auction.item.weaponUrlName == known.item.weaponUrlName,
                    ) {
                        "detail returned a different auction"
                    }
                    ingest.ingest(listOf(auction), observed)
                } catch (e: ThrottledException) {
                    cadence.throttled(e.retryAfter)
                    counts.getValue("throttled").increment()
                    return null
                } catch (e: WfmHttpException) {
                    if (e.status.value() == 404) {
                        ingest.ingest(listOf(known), observed, missing = true)
                    } else {
                        counts.getValue("failed").increment()
                    }
                } catch (e: Exception) {
                    counts.getValue("failed").increment()
                    log.warn("auction detail check failed: {}", e.javaClass.simpleName)
                }
            }
        }
        return left
    }
}

@Configuration(proxyBeanMethods = false)
class ContractConfig {
    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, "watchdawg.contracts.enabled")
    fun contractSchedule(
        sync: ContractSync,
        catalog: com.watchdawg.market.sync.CollectionSyncScheduler,
    ): OwnThreadSchedule {
        sync.initialize(catalog)
        return OwnThreadSchedule("contracts", sync::round, sync.cadence)
    }
}
