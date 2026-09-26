package com.watchdawg.market.notify

import com.watchdawg.market.OwnThreadSchedule
import com.watchdawg.market.SCHEDULING_ENABLED
import com.watchdawg.market.watch.AlertMetrics
import com.watchdawg.market.watch.Delivery
import com.watchdawg.market.watch.InvalidWatchException
import com.watchdawg.market.watch.Outgoing
import com.watchdawg.market.watch.SignalState
import com.watchdawg.market.watch.SignalStore
import com.watchdawg.market.watch.Watches
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
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * @property topics logical topic to real ntfy topic. Supplied by the environment, as
 *   `WATCHDAWG_NOTIFY_TOPICS_<NAME>`, and never committed or logged (R10.6).
 * @property maxAttempts attempts before a signal is marked failed for good (R10.3).
 * @property backoff the wait after the first failed attempt, doubling after each one after it.
 */
@ConfigurationProperties(prefix = "watchdawg.notify")
data class NotifyProperties(
    val baseUrl: String,
    val interval: Duration,
    val batchSize: Int,
    val maxAttempts: Int,
    val backoff: Duration,
    val topics: Map<String, String> = emptyMap(),
)

/**
 * Sends pending signals, oldest first, and marks each sent only on a 2xx (R10.1, R10.2). A signal
 * is sent however long it waited, including across restarts: the outbox is the only queue.
 *
 * One dispatcher runs, on a thread of its own, and never overlaps itself; with no topic mapped it
 * is not scheduled at all, and signals wait in the outbox.
 *
 * A failed attempt is retried with backoff up to [NotifyProperties.maxAttempts], then the signal is
 * marked failed, logged and counted (R10.3). The real topic is scrubbed from every recorded error.
 */
@Component
class Dispatcher(
    private val signals: SignalStore,
    private val notifier: Notifier,
    private val props: NotifyProperties,
    private val metrics: AlertMetrics,
    watches: Watches,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        checkTopics(watches, props.topics)
    }

    fun dispatch() {
        for (signal in signals.due(clock.instant(), props.batchSize)) {
            val topic = props.topics[signal.topic]
            try {
                checkNotNull(topic) { "topic '${signal.topic}' is not mapped" }
                notifier.send(topic, signal.toPush())
            } catch (e: Exception) {
                failed(signal, scrub("${e.javaClass.simpleName}: ${e.message}", topic))
                continue
            }
            signals.markSent(signal.id, clock.instant())
            metrics.delivery(Delivery.SENT)
            metrics.signal(signal.watch, SignalState.SENT)
        }
    }

    private fun failed(signal: Outgoing, error: String) {
        val attempts = signal.attempts + 1
        if (attempts >= props.maxAttempts) {
            signals.recordFailure(signal.id, error, retryAt = null)
            metrics.delivery(Delivery.FAILED)
            metrics.signal(signal.watch, SignalState.FAILED)
            log.warn("signal {} of watch {} failed after {} attempts: {}", signal.id, signal.watch, attempts, error)
        } else {
            val wait = props.backoff.multipliedBy(1L shl (attempts - 1))
            signals.recordFailure(signal.id, error, clock.instant().plus(wait))
            metrics.delivery(Delivery.RETRIED)
            log.info("signal {} not sent, retrying in {}: {}", signal.id, wait, error)
        }
    }

    private fun scrub(error: String, topic: String?): String =
        (if (topic.isNullOrEmpty()) error else error.replace(topic, "<topic>")).take(ERROR_LIMIT)

    private companion object {
        const val ERROR_LIMIT = 500
    }
}

/**
 * With any topic mapped, every watch's logical topic must be, or its signals could never be sent.
 * With none mapped, delivery is off and nothing is checked.
 *
 * @throws InvalidWatchException naming the watch and its logical topic, never a real one.
 */
fun checkTopics(watches: Watches, topics: Map<String, String>) {
    if (topics.isEmpty()) return
    watches.all.firstOrNull { it.topic !in topics }?.let {
        val name = "WATCHDAWG_NOTIFY_TOPICS_${it.topic.uppercase()}"
        throw InvalidWatchException("watch '${it.name}' names topic '${it.topic}', but $name is not set")
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
    fun notifier(@Qualifier(NTFY_CLIENT) client: RestClient, mapper: JsonMapper): Notifier =
        NtfyNotifier(client, mapper)

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
