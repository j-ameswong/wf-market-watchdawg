# Plan: seconds-level alerts from the socket (C5)

> Source: `SPEC.md` §2.1, §2.7, §3.4 build order step 3, §4 C5 (R5.1–R5.6), R1.8, R4.5, R4.10,
> R4.11, R9a.3, R9a.8, R12.2, and ADRs
> [0002](../docs/adr/0002-crossplay-single-global-setting.md),
> [0008](../docs/adr/0008-vanished-only-from-full-book-polls.md),
> [0014](../docs/adr/0014-alert-budget-is-a-requirement.md),
> [0017](../docs/adr/0017-tests-never-reach-the-live-api.md) and
> [0021](../docs/adr/0021-limiter-owns-the-ceiling-poll-loop-owns-freshness.md). Drafted
> 2026-09-26 on top of the alert milestone (PR #8). Task bodies and acceptance checks are in
> `tasks/todo.md`.
> The author confirmed the plan on 2026-09-26, after a review that amended decisions 4, 5 and 6
> and the T2–T6 checks.

## Goal

The milestone is done when:

1. a real underpriced listing on a watched item reaches the operator's phone within seconds of
   being posted, through the socket rather than the next poll;
2. a dropped connection comes back by itself, gap-fills from `/v2/orders/recent`, and records no
   duplicate events;
3. the socket and REST quote the same `crossplay` (R1.8), pinned by a test; and
4. over 72 hours with the socket on, admissions each UTC day stay at or under the daily ceiling
   (R9a.8), with deliveries reported beside them. This is the C9a acceptance still owed from the
   last milestone.

## Scope

1. **A live capture first.** No socket frame has been captured yet, and fixtures are live
   captures taken before a field is committed to. One scrubbed capture of real `newOrder` frames
   settles what the payload carries (`itemId`, `user.status`, `user.platform`) before any code
   binds it.
2. **Connect, subscribe, ingest (R5.1, R5.2, R5.5, R5.6).** One socket, the `wfm` subprotocol,
   `newOrders` subscribed with `platform` and `crossplay` taken from `WfmContext`, and each new
   order recorded through `OrderIngest.ingestPartial` as `source=ws`. Malformed frames and unknown
   routes are logged and skipped.
3. **Socket orders reach the rule.** An order that appears through the socket is evaluated by the
   same underpriced rule and admission as a polled one, in the same transaction. This is the step
   that brings alert latency from minutes to seconds.
4. **Staying up (R5.3, R5.4).** Reconnect with backoff and jitter, give up on a connection that
   never connects, never confirms its subscription or goes silent, and gap-fill from `/recent`
   after each subscription is confirmed, honouring a throttle.
5. **Observable (R12.2).** Connection state, reconnects, frames by outcome and gap-fills, as
   meters registered at startup.
6. **The live checkpoint, then the 72h budget run.**

## Order

`T1 capture → T2 connect and ingest → T3 socket orders reach the rule → T4 reconnect and gap-fill
→ T5 live checkpoint → T6 72h budget run`. Make it work, then make it right:

- After T2 the service records new orders from the socket in the warehouse, with the poll loop
  unchanged. A dropped connection just stays down until restart, which T4 fixes.
- After T3 a cheap listing is pushed seconds after it is posted, end to end.
- T4 hardens a path that already works: reconnecting, liveness and gap-fill.
- Meters land with the task whose behaviour they measure, as in the last milestone.

T2–T4 fit one PR each or one stacked series. T1, T5 and T6 happen on the author's machine: the
cloud sandbox cannot reach `ws.warframe.market`, `api.warframe.market` or `ntfy.sh`.

## Out of scope

The spread and crossing rules (R9a.3's other two families): both are book-scoped, so the socket,
which carries single new orders, does not make them faster, and no watch asks for them yet. They
stay with SPEC §3.4 step 4, built when operation gives a reason. Also out: a standing `/recent`
poll while the socket is up (decision 5), socket-driven poll priority (deferred by ADR-0021),
authenticated socket routes, item and profile subscriptions (unregistered stubs upstream, R5.6),
C7, C8 and C9b.

## Budget

The socket spends no REST budget (§2.1). The only new REST traffic is one `/v2/orders/recent` per
confirmed subscription, on the `public` bucket, and none more often than once a minute
(decision 5). A healthy connection costs nothing a day; one reconnect an hour costs 24 requests a
day, against the 1,440 a day §2.1 budgeted for polling `/recent` every minute.

`/v2/orders/recent` is already a client call (C4) but has never been called on a schedule, so this
plan is the §9 ask-first for it. No new dependency is added (decision 1).

## Decisions

1. **The client is the JDK's `java.net.http.WebSocket`; no new dependency.** It supports
   subprotocols and handshake headers, so R5.1 and R1.5 hold. Spring's `WebSocketClient` would add
   `spring-websocket` (§9 ask-first, and a `nix/deps.json` regeneration) for nothing the JDK lacks.
   The fake server in tests is Tomcat's websocket support, already on the test classpath
   (`tomcat-embed-websocket`, via the Tomcat starter).
2. **Every new order the socket carries is recorded, not only watched items'** (confirmed). The
   socket is the only market-wide source of new orders, and it is free (§2.1); an `appeared` not
   recorded now cannot be recovered later, and the event log is what future analysis reads. An
   unpolled item's `wfm_order` rows stay live until some book poll of that item vanishes them,
   and that is accepted: current state matters only for polled items, and the rest is history.
   ADR-0022 records it, since it changes what `wfm_order` means for unpolled items.
3. **The rule runs over the orders a partial observation adds, not over known ones.**
   `ingestPartial` may only add orders (R4.11), so only an order it adds is evaluated; a known
   order's changes still come from book polls. The dedup key (watch, order, unit price) is the
   same whichever source saw the listing first, so the next book poll does not signal it again.
   Online status is read from `user.status` as today. If T1 shows socket frames carry no status,
   an order from the socket counts as online, since upstream sends only non-offline owners'
   orders; `/recent` carries status (its committed fixture does) and keeps using it.
4. **Admission is serialised by one transaction-scoped advisory lock** (confirmed, amended in
   review). Until now one poll thread reconciled one book at a time, so admissions never raced
   (`Alerts`' own note). The socket ingests on another thread, so two transactions could both
   pass a check before either commits. A lock per watch would protect a cooldown but not the
   daily ceiling, which counts across watches: two watches could each read 49 admissions
   against a ceiling of 50 and each admit another. So there is one lock for all admission,
   `select pg_advisory_xact_lock(<one constant key>)`, taken in the ingest transaction before
   the first cooldown or ceiling read, and only when the rule found candidates, so ingesting an
   unwatched item's order never waits on it. It is held to commit, which comes right after:
   admission is the last step of both ingest paths, and signals are written only under the lock,
   so a transaction holding it needs no row another one holds, and it cannot deadlock against the
   book claim or order rows. That protects the cooldowns and the ceiling together. The alternative, handing socket orders to the poll thread, would queue them behind a
   whole poll round and give back the latency this milestone is for.
5. **Gap-fill once per confirmed subscription, never within a minute of the last one, and never
   before a throttle's `Retry-After` has passed** (confirmed, amended in review). After
   `subscribe/newOrders:ok`, one `/v2/orders/recent` goes through `ingestPartial` as
   `source=recent`, and its orders reach the rule like the socket's. Subscribing first means an
   order posted during the gap-fill is not missed; one seen by both is recorded once. `/recent` is
   cached for a minute, so a reconnect within a minute of the last gap-fill skips it. A throttled
   gap-fill moves that next-allowed time out to the end of its `Retry-After` when that is later.
   A `Retry-After` above `wfm.limits.max-retry-after` (60 seconds today, so `Retry-After: 300`
   qualifies) surfaces from the client at once (ADR-0019), and without this a reconnect 61
   seconds later would ask again too early. A throttle with no `Retry-After` keeps the one-minute
   window. There is no standing `/recent` poll while the socket is up: it would only repeat what
   the socket already delivered. A gap-fill that fails or is throttled is logged and counted, and the socket stays
   up; the next book poll recovers current state (R5.4). A gap-fill resets no other timer: the
   poll loop keeps its cadence, and its throttle hold-off (R6.6) is set only by its own polls. The
   one clock a gap-fill moves is its own next-allowed time.
6. **Reconnect with full jitter, doubling from 1 second to a 5-minute cap, with three deadlines**
   (amended in review). Each deadline closes the attempt and reconnects through the backoff:
   - **Connect: 10 seconds** for the TCP connection and handshake, set on both the `HttpClient`
     and the `WebSocket.Builder`. The JDK waits forever by default.
   - **Subscription: 10 seconds** from sending `subscribe/newOrders` to its `:ok` (or
     `:error alreadySubscribed`). Heartbeats do not count toward it, so a server that keeps
     broadcasting `reports/online` but never confirms cannot leave the socket up and useless.
   - **Silence: 90 seconds** without any frame once subscribed. The server broadcasts
     `reports/online` about every 30 seconds, so three missed broadcasts mean a dead connection;
     without this a half-open TCP connection would silence the socket with nothing to show.

   The backoff resets once a connection has stayed subscribed for a minute, so a connection that
   flaps right after subscribing still backs off.
7. **One setting to turn it off, and off under test.** `watchdawg.socket.enabled` (default
   `true`), and `wfm.socket.url` (default `wss://ws.warframe.market/socket`) so a test can point
   at its fake. The socket is declared only where `watchdawg.scheduling.enabled` allows, as the
   poll loop is, so the harness keeps it off. `LiveApiGuard` only covers `RestClient`, so the
   harness also checks that no socket connects to a non-local host (R2.6, ADR-0017).
8. **A socket signal is seen when its frame arrives.** `seen_at` is the arrival time, which is
   what cooldown and the daily ceiling count, like a book's request time today. A gap-filled
   order is seen at the `/recent` request time.
9. **The owed items.** The 72h alert-budget run is T6 of this set, with the socket on: the socket
   adds a second path into the outbox, so a run without it would measure a configuration about to
   change. The spread and crossing rules stay out (see Out of scope).

## Design notes

- **One socket, one connection at a time.** A `SmartLifecycle` bean opens it on start and closes
  it on stop. Frames arrive on the JDK client's executor; each `newOrder` frame is ingested in its
  own transaction, which is short because the partial path takes no book lock.
- **A message is parsed only once it is whole.** The JDK can deliver one text message across
  several `onText` calls; the listener appends each part and parses when `last` is true. It asks
  for the next message after every callback, including one whose message was malformed, so a bad
  message never stalls the ones behind it.
- **Messages are parsed as the v2 envelope `route`/`payload`/`id`**, and only
  `@wfm|event/subscriptions/newOrder`, `@wfm|cmd/subscribe/newOrders:ok|:error` and
  `@wfm|event/reports/online` are acted on. `:error` with `alreadySubscribed` counts as
  subscribed; any other `:error` closes the connection and reconnects through the backoff.
- **The subscribe payload is built from `WfmContext` alone**, and `platform` and `crossplay` are
  always present in it. The cross-channel test that `WfmContext`'s `TODO(C5)` asks for asserts the
  subscribe frame and the REST `Crossplay` header carry the same value, for both values.
- **The handshake carries the project `User-Agent`** (R1.5), from `wfm.user-agent`.
- **Partial ingest with the rule** is `ingestPartial` calling `Alerts` over the orders it added,
  inside its existing transaction, with the markets it resolved. `Alerts` grows no second rule: it
  evaluates the watches of each added order's item over those orders only.
- **Metrics** join `WfmMetrics`: `wfm.socket.connected` (gauge, 0 or 1), `wfm.socket.reconnects`
  (by the reason the last connection ended), `wfm.socket.frames` by outcome (`order`, `control`,
  `skipped`), and `wfm.socket.gapfills` by outcome. All registered at startup, so a service with
  the socket off reads zero.
- **Every throttled response is counted.** `wfm.retries` counts only the refusals answered with a
  retry; a `Retry-After` over the ceiling, or a second refusal, throws before it increments. A new
  `wfm.throttles` (by bucket and status) counts every `429` and `509` the interceptor sees,
  retried or surfaced, so T6 can show there were none.

## Risks

- **The socket payload may differ from REST's `Order`.** T1 exists for this; decision 3 covers the
  likeliest gap.
- **Write volume from the whole-market feed is unmeasured.** Every new order is a market lookup
  and two inserts. T5 reads `wfm.socket.frames` over an hour, before T6 runs for 72.
- **A crossplay mismatch fails silently** (ADR-0002). The cross-channel test pins it in code. T5
  also reports the non-PC share of socket orders beside the ~7% `/recent` showed, as a diagnostic:
  that figure is one historical sample, not a property of the protocol.
- **Gap-fill is best effort** (R5.4): an outage longer than `/recent`'s window loses `appeared`
  events for good. The reconnect meter shows how often that could have happened.
- **Socket alerts may push the budget.** The socket also sees listings that sell before the next
  poll, which polling never saw, so admissions can rise. Cooldown and the daily ceiling are
  unchanged and still bound them; T6 is where the real volume is measured rather than assumed.
