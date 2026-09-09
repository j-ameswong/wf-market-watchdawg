Read the docs. Quick terminology note first: what you need for phase 1 is a REST **client** (outbound), not a `@RestController` (inbound). In Spring Boot 4 that's `RestClient`. You'll only want a controller later if you expose health/query endpoints of your own.

## Which endpoints

Everything you need for price data is public — **no auth**, so you can skip the whole OAuth story for phase 1.

| Purpose                    | Endpoint                         | Notes                                                                                                                                                            |
| -------------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Item catalog (slugs → ids) | `GET /v2/items`                  | `docs/v2/api/manifests.mdx`. One call, all tradable items. Cache it.                                                                                             |
| Cache invalidation         | `GET /v2/versions`               | Returns `collections.items` hash. Poll cheaply; refetch `/v2/items` only when the hash changes.                                                                  |
| **Primary price signal**   | `GET /v2/orders/item/{slug}/top` | Up to 5 buy + 5 sell from *online* users, already ranked. This is the "current market price" call.                                                               |
| Full order book            | `GET /v2/orders/item/{slug}`     | Every visible order, `lastSeen` within 48h. Much bigger payload; use only if you want depth.                                                                     |
| Cheap firehose             | `GET /v2/orders/recent`          | Up to 500 orders from the last 4h, online users, `createdAt` desc. **Server-cached with 1m refresh** — one request per minute gets you global activity for free. |

**The rate limit shapes your design.** `overview.mdx`: 3 req/s, Cloudflare returns `429` (and `509` on too many concurrent connections). There are ~4000 tradable items, so a full sweep of `/top` is ~22 minutes at max rate. So:

- Poll `/v2/orders/recent` every 60s (matches its cache TTL) — cheap, broad coverage.
- Maintain a **watchlist** of slugs and sweep `/top` for those on a schedule, budgeted under 3 req/s (I'd run at ~2/s to leave headroom).
- Don't try to sweep the whole catalog every cycle.

Also relevant headers (`overview.mdx`): `Platform: pc` (default), `Crossplay: true` to fold in other platforms, `Language` for i18n. Set `Platform`/`Crossplay` as *default headers* on the client since your whole app is presumably one platform.

## Wiring it up

**`application.yaml`**

```yaml
wfm:
  base-url: https://api.warframe.market/v2
  platform: pc
  crossplay: false
  requests-per-second: 2
  poll-interval: 60s
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

**Config properties + client bean** — `WfmConfig.kt`:

```kotlin
package com.watchdawg.market.wfm

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties(prefix = "wfm")
data class WfmProperties(
    val baseUrl: String,
    val platform: String = "pc",
    val crossplay: Boolean = false,
    val requestsPerSecond: Int = 2,
    val pollInterval: Duration = Duration.ofSeconds(60),
)

@Configuration
@EnableConfigurationProperties(WfmProperties::class)
class WfmConfig {

    @Bean
    fun wfmRestClient(builder: RestClient.Builder, props: WfmProperties): RestClient =
        builder
            .baseUrl(props.baseUrl)
            .defaultHeader("Platform", props.platform)
            .defaultHeader("Crossplay", props.crossplay.toString())
            .defaultHeader("User-Agent", "wf-market-watchdawg/0.1 (contact: you@example.com)")
            .defaultStatusHandler({ it.value() == 429 || it.value() == 509 }) { _, res ->
                throw RateLimitedException(res.headers.getFirst("Retry-After")?.toLongOrNull())
            }
            .build()
}

class RateLimitedException(val retryAfterSeconds: Long?) : RuntimeException("wfm rate limited")
```

`RestClient.Builder` is auto-configured by `spring-boot-starter-webmvc`; you just customize it. Everything is blocking, which is fine — one scheduled thread doing ≤3 req/s doesn't need WebFlux.

**The envelope + DTOs.** Every response is `{apiVersion, data, error}`, so model it once as a generic and unwrap it. Kotlin data classes work directly with `jackson-module-kotlin` (already in your `build.gradle.kts`), and the API is camelCase so no naming strategy needed. Only declare the fields you actually care about — Jackson ignores unknown ones by default under Spring Boot.

```kotlin
package com.watchdawg.market.wfm

import java.time.Instant

data class Envelope<T>(val apiVersion: String, val data: T?, val error: ApiError?)
data class ApiError(val request: List<String>?, val inputs: Map<String, String>?)

data class Item(
    val id: String,
    val slug: String,
    val gameRef: String? = null,
    val tags: List<String> = emptyList(),
    val vaulted: Boolean? = null,
    val ducats: Int? = null,
    val maxRank: Int? = null,
    val subtypes: List<String> = emptyList(),
)

data class Order(
    val id: String,
    val type: String,          // "buy" | "sell"
    val platinum: Int,
    val quantity: Int,
    val perTrade: Int? = null,
    val rank: Int? = null,
    val charges: Int? = null,
    val subtype: String? = null,
    val visible: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val itemId: String? = null,
    val user: UserShort? = null,
)

data class UserShort(val id: String, val ingameName: String? = null, val status: String? = null)

data class TopOrders(val sell: List<Order>, val buy: List<Order>)
```

Note `Instant` — timestamps come back as ISO-8601 (`2021-05-21T14:59:02Z`), and Boot registers the JSR-310 module automatically.

**The client** — the generic-unwrapping bit is the only fiddly part in Kotlin, because you need `ParameterizedTypeReference` to keep the generic through erasure:

```kotlin
package com.watchdawg.market.wfm

import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class WfmClient(private val client: RestClient) {

    fun listItems(): List<Item> = get("/items")

    fun topOrders(slug: String): TopOrders = get("/orders/item/{slug}/top", slug)

    fun ordersForItem(slug: String): List<Order> = get("/orders/item/{slug}", slug)

    fun recentOrders(): List<Order> = get("/orders/recent")

    private inline fun <reified T : Any> get(uri: String, vararg vars: Any): T {
        val envelope = client.get()
            .uri(uri, *vars)
            .retrieve()
            .body<Envelope<T>>()               // reified extension, keeps the generic
            ?: error("empty body from $uri")
        return envelope.data ?: error("wfm error on $uri: ${envelope.error}")
    }
}
```

The `body<Envelope<T>>()` extension from `org.springframework.web.client` is the Kotlin-specific piece — it builds the `ParameterizedTypeReference` for you via `reified`. That's the idiom worth internalizing; it's how you avoid `LinkedHashMap` coming back instead of your data class.

**Throttling.** Do not skip this — 3 req/s is low and Cloudflare will `429` you. Simplest correct thing, a permit-per-interval gate the poller calls before each request:

```kotlin
@Component
class WfmRateLimiter(props: WfmProperties) {
    private val minIntervalNanos = 1_000_000_000L / props.requestsPerSecond
    private var lastCallNanos = 0L

    @Synchronized
    fun acquire() {
        val wait = lastCallNanos + minIntervalNanos - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        lastCallNanos = System.nanoTime()
    }
}
```

Wrap it into `WfmClient.get()` (call `acquire()` first) so it's impossible to bypass. If you'd rather not hand-roll it, Resilience4j's `RateLimiter` + `Retry` gives you backoff on `RateLimitedException` too.

**The poller** — `@EnableScheduling` on your `MarketApplication`, then:

```kotlin
@Component
class RecentOrdersPoller(
    private val wfm: WfmClient,
    private val kafka: KafkaTemplate<String, Order>,
) {
    @Scheduled(fixedDelayString = "\${wfm.poll-interval}")
    fun poll() {
        wfm.recentOrders().forEach { order ->
            kafka.send("wfm.orders.recent", order.itemId ?: order.id, order)
        }
    }
}
```

Use `fixedDelay`, not `fixedRate` — with a rate limiter in the path you never want overlapping runs piling up.

## Suggested build order

1. `WfmClient.listItems()` against a plain integration test (or `./gradlew bootRun` + a `CommandLineRunner`) — confirms the envelope unwrapping and your DTOs match reality. This is the step where deserialization bugs surface.
2. Add the rate limiter, then `/orders/recent` on a 60s schedule, logging to console.
3. Swap the logger for `KafkaTemplate`. Key by `itemId` so all orders for an item land in the same partition — that ordering guarantee will matter for whatever consumer computes price stats.
4. Add the `/versions`-gated item-catalog refresh and the watchlist `/top` sweep.

One thing to decide early: whether your Kafka topic carries raw `Order` events (append-only, replayable, my recommendation) or pre-aggregated per-item price snapshots. Raw events keep phase 2 open.