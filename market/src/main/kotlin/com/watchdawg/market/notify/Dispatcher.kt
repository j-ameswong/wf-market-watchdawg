package com.watchdawg.market.notify

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.watch.SignalStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

/**
 * @property topics logical topic to real ntfy topic. Supplied by the environment, as
 *   `WATCHDAWG_NOTIFY_TOPICS_<NAME>`, and never committed or logged (R10.6).
 */
@ConfigurationProperties(prefix = "watchdawg.notify")
data class NotifyProperties(
    val baseUrl: String,
    val interval: Duration,
    val batchSize: Int,
    val topics: Map<String, String> = emptyMap(),
)

/**
 * Sends pending signals, oldest first, and marks each sent only on a 2xx (R10.1, R10.2). One
 * dispatcher runs, on a thread of its own, and never overlaps itself; with no topic mapped it does
 * not run at all, and signals wait in the outbox.
 */
@Component
class Dispatcher(
    private val signals: SignalStore,
    private val notifier: Notifier,
    private val props: NotifyProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatch() {
        for (signal in signals.due(clock.instant(), props.batchSize)) {
            val topic = props.topics[signal.topic]
            if (topic == null) {
                log.warn("signal {} names topic '{}', which is not mapped; it stays pending", signal.id, signal.topic)
                continue
            }
            try {
                notifier.send(topic, signal.toPush())
                signals.markSent(signal.id, clock.instant())
            } catch (e: Exception) {
                signals.recordFailure(signal.id, errorOf(e), clock.instant().plus(props.interval))
                log.warn("signal {} not sent: {}", signal.id, errorOf(e))
            }
        }
    }

    private fun errorOf(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}".take(ERROR_LIMIT)

    private companion object {
        const val ERROR_LIMIT = 500
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifyProperties::class)
class NotifyConfig {
    /**
     * Built from the context's builder, not through the WFM transport: ntfy gets none of
     * warframe.market's pacing or headers (R1.1).
     */
    @Bean(NTFY_CLIENT)
    fun ntfyRestClient(builder: RestClient.Builder, props: NotifyProperties): RestClient =
        builder.baseUrl(props.baseUrl).build()

    @Bean
    fun notifier(@Qualifier(NTFY_CLIENT) client: RestClient): Notifier = NtfyNotifier(client)

    @Bean
    @ConditionalOnBooleanProperty(SCHEDULING_ENABLED, matchIfMissing = true)
    @Conditional(TopicsMapped::class)
    fun dispatchSchedule(dispatcher: Dispatcher, props: NotifyProperties) = OwnThreadSchedule(
        "dispatch",
        dispatcher::dispatch,
        PeriodicTrigger(props.interval).apply { setInitialDelay(props.interval) },
    )

    companion object {
        const val NTFY_CLIENT = "ntfyRestClient"
    }
}

/** At least one logical topic has a real one behind it. */
class TopicsMapped : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        Binder.get(context.environment)
            .bind("watchdawg.notify.topics", Bindable.mapOf(String::class.java, String::class.java))
            .map { it.isNotEmpty() }
            .orElse(false) == true
}
