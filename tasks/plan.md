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
> The author confirmed decisions 2, 4 and 5 on 2026-09-26 and bounded 4 (see there). The rest
> are proposals, each with the default the tasks assume, until the author confirms or changes them.

## Goal

The milestone is done when:

1. a real underpriced listing on a watched item reaches the operator's phone within seconds of
   being posted, through the socket rather than the next poll;
2. a dropped connection comes back by itself, gap-fills from `/v2/orders/recent`, and records no
   duplicate events;
3. the socket and REST quote the same `crossplay` (R1.8), pinned by a test; and
4. over 72 hours with the socket on, notification volume sits inside the alert budget (R9a.8).
   This is the C9a acceptance still owed from the last milestone.

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
4. **Staying up (R5.3, R5.4).** Reconnect with backoff and jitter, detect a silent dead
   connection, and gap-fill from `/recent` after each subscription is confirmed.
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
4. **Admission is serialised per watch with a transaction-scoped advisory lock** (confirmed, if it
   stays small). Until now one
   poll thread reconciled one book at a time, so admissions never raced (`Alerts`' own note). The
   socket ingests on another thread, so two candidates for one watch could both pass the
   cooldown check. A `pg_advisory_xact_lock` keyed by the watch, taken inside the ingest
   transaction before the cooldown read, restores that. The alternative, handing socket orders to
   the poll thread, would queue them behind a whole poll round and give back the latency this
   milestone is for. The lock is one `select pg_advisory_xact_lock(hashtext(:watch))` at the top
   of a watch's admission. If it needs more than that, it is dropped: the race then admits at most
   one extra signal within a cooldown, still bounded by the dedup key and the daily ceiling.
5. **Gap-fill once per confirmed subscription, and never within a minute of the last one**
   (confirmed). After
   `subscribe/newOrders:ok`, one `/v2/orders/recent` goes through `ingestPartial` as
   `source=recent`, and its orders reach the rule like the socket's. Subscribing first means an
   order posted during the gap-fill is not missed; one seen by both is recorded once. `/recent` is
   cached for a minute, so a reconnect within a minute of the last gap-fill skips it. There is no
   standing `/recent` poll while the socket is up: it would only repeat what the socket already
   delivered. A gap-fill that fails or is throttled is logged and counted, and the socket stays
   up; the next book poll recovers current state (R5.4). A gap-fill resets no timer: the poll
   loop keeps its cadence, and its throttle hold-off (R6.6) is set only by its own polls; the
   only clock a gap-fill starts is its own one-minute skip window.
6. **Reconnect with full jitter, doubling from 1 second to a 5-minute cap, and treat 90 seconds of
   silence as a dead connection.** The server broadcasts `reports/online` about every 30 seconds,
   so three missed broadcasts close the connection and reconnect. Without it a half-open TCP
   connection would silence the socket with nothing to show for it. The backoff resets once a
   connection has stayed subscribed for a minute, so a connection that flaps right after
   subscribing still backs off.
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
- **Frames are parsed as the v2 envelope `route`/`payload`/`id`**, and only
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
- **Metrics** join `WfmMetrics`: `wfm.socket.connected` (gauge, 0 or 1), `wfm.socket.reconnects`,
  `wfm.socket.frames` by outcome (`order`, `control`, `skipped`), and `wfm.socket.gapfills` by
  outcome. All registered at startup, so a service with the socket off reads zero.

## Risks

- **The socket payload may differ from REST's `Order`.** T1 exists for this; decision 3 covers the
  likeliest gap.
- **Write volume from the whole-market feed is unmeasured.** Every new order is a market lookup
  and two inserts. T5 reads `wfm.socket.frames` over an hour, before T6 runs for 72.
- **A crossplay mismatch fails silently** (ADR-0002). The cross-channel test pins it in code; T5
  also checks that the non-PC share of socket orders is near the ~7% `/recent` showed.
- **Gap-fill is best effort** (R5.4): an outage longer than `/recent`'s window loses `appeared`
  events for good. The reconnect meter shows how often that could have happened.
- **Socket alerts may push the budget.** The socket also sees listings that sell before the next
  poll, which polling never saw, so admissions can rise. Cooldown and the daily ceiling are
  unchanged and still bound them; T6 is where the real volume is measured rather than assumed.
