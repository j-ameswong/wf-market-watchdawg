package com.watchdawg.market.statistics

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.store.ItemRepository
import com.watchdawg.market.watch.Watches
import com.watchdawg.market.wfm.ThrottledException
import com.watchdawg.market.wfm.WfmLegacyClient
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("watchdawg.statistics")
data class StatisticsProperties(
    val interval: Duration = Duration.ofDays(1),
    val initialDelay: Duration = Duration.ofSeconds(10),
) {
    init {
        require(!interval.isNegative && !interval.isZero && !initialDelay.isNegative)
    }
}

@Component
@EnableConfigurationProperties(StatisticsProperties::class)
class StatisticsSync(
    private val watches: Watches,
    private val items: ItemRepository,
    private val client: WfmLegacyClient,
    private val ingest: StatisticsIngest,
    private val details: com.watchdawg.market.wfm.WfmClient,
    registry: MeterRegistry,
    private val props: StatisticsProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val counts = listOf("success", "failed", "throttled").associateWith {
        Counter.builder("wfm.statistics.fetches").tag("outcome", it).register(registry)
    }
    private val pending = java.util.ArrayDeque<String>()
    private var nextRun = clock.instant().plus(props.initialDelay)
    val trigger = org.springframework.scheduling.Trigger { nextRun }

    fun round() {
        if (pending.isEmpty()) pending.addAll(watches.itemSlugs)
        repeat(pending.size) {
            val slug = pending.removeFirst()
            try {
                var item = checkNotNull(items.findBySlug(slug)) { "unknown item $slug" }
                if (item.detailSyncedAt == null) {
                    val detail = details.getItem(slug)
                    items.recordDetail(item.id, detail.tradable, detail.rarity, detail.maxCharges)
                    item = checkNotNull(items.findBySlug(slug))
                }
                val at = clock.instant()
                ingest.ingest(item, client.getStatistics(slug), at)
                counts.getValue("success").increment()
            } catch (e: ThrottledException) {
                pending.addFirst(slug)
                nextRun = clock.instant().plus(maxOf(Duration.ofMinutes(1), e.retryAfter ?: Duration.ZERO))
                counts.getValue("throttled").increment()
                return
            } catch (e: Exception) {
                pending.addLast(slug)
                counts.getValue("failed").increment()
                log.warn("statistics for {} failed: {}", slug, e.javaClass.simpleName)
            }
        }
        nextRun = clock.instant().plus(if (pending.isEmpty()) props.interval else Duration.ofMinutes(1))
    }
}

@Configuration(proxyBeanMethods = false)
class StatisticsConfig {
    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, "watchdawg.statistics.enabled", matchIfMissing = true)
    fun statisticsSchedule(sync: StatisticsSync) = OwnThreadSchedule("statistics", sync::round, sync.trigger)
}
