# Tasks: seconds-level alerts from the socket (C5)

Plan: `tasks/archive/c5-plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
checks and nothing else; how a check is met lives in the code and its tests.

## Before any code
- [x] The author has confirmed or changed the nine decisions in the plan (2026-09-26; review
      amended 4, 5 and 6).
- [x] `SPEC.md` §10 question 7 records the answer to decision 2 (every item, 2026-09-26).
- [x] C5's requirements (R5.3, R5.4, R5.5, R5.6) and R9a.8 match the confirmed decisions.

## Payload first

### T1: A live socket capture
- [x] A script under `bruno/` connects with the `wfm` subprotocol, subscribes to `newOrders` with
      `platform` and `crossplay` sent explicitly, and writes the frames it receives; it adds no
      dependency.
- [x] Its capture of real `newOrder` frames, plus the `:ok` and one `reports/online`, is scrubbed
      like `scrub-orders.mjs` scrubs orders and committed as a fixture.
- [x] The plan records whether a `newOrder` payload carries `itemId`, `user.status` and
      `user.platform`, and decision 3 is settled against it.

## C5 — realtime feed

### T2: The socket connects, subscribes and records new orders
- [x] Against a local fake server, the handshake offers the `wfm` subprotocol and the project
      `User-Agent` (R5.1, R1.5).
- [x] The subscribe frame carries `platform` and `crossplay` from `WfmContext`, both always
      present; a test pins that it and the REST `Crossplay` header agree, for `true` and `false`
      (R1.8, R5.2), and `WfmContext`'s `TODO(C5)` is gone.
- [x] A captured `newOrder` frame produces one `appeared` event with `source=ws` (R5.5); the same
      frame again produces none.
- [x] `:error` with `alreadySubscribed` counts as subscribed; any other `:error` closes the
      connection.
- [x] A message split across several text frames is ingested once, as one order.
- [x] A malformed message and an unknown route are logged and skipped, the connection stays open,
      and a valid `newOrder` right after the malformed one is ingested (R5.6).
- [x] An order for an item the catalog lacks is skipped, as `ingestPartial` already does.
- [x] The socket is off under test and with `watchdawg.socket.enabled=false`; the harness fails a
      test whose socket reaches a non-local host (R2.6).
- [x] `wfm.socket.connected` and `wfm.socket.frames` (by outcome) are registered at startup (R12.2).

### T3: Socket orders reach the rule
- [x] A qualifying listing arriving on the socket for a watched market admits one signal, seen at
      the frame's arrival; an order that was already known admits nothing.
- [x] The next book poll holding the same listing at the same price admits nothing (dedup key).
- [x] Rolling back a partial ingest leaves neither its events nor its signal (R9a.7).
- [x] A socket ingest and a book reconcile each holding an equally priced candidate for one watch,
      committing at once, admit exactly one signal within its cooldown.
- [x] Two ingests with candidates for different watches, competing for the last admission under
      the daily ceiling, admit one `pending` and write the other `suppressed`.
- [x] Orders for unwatched items are recorded and evaluate nothing.

### T4: The socket stays up and fills its gaps
- [x] A dropped connection reconnects with doubling, jittered backoff to the cap, and the backoff
      resets only after a connection has stayed subscribed for a minute (R5.3).
- [x] A server that accepts the connection but never completes the handshake is abandoned after
      the 10-second connect deadline, and the socket reconnects.
- [x] A server that sends `reports/online` but withholds `:ok` is abandoned after the 10-second
      subscription deadline, and the socket reconnects.
- [x] 90 seconds without a frame once subscribed closes the connection and reconnects.
- [x] Each confirmed subscription gap-fills once from `/v2/orders/recent` as `source=recent`,
      unless the last gap-fill was under a minute ago; its orders reach the rule (R5.4).
- [x] Killing the connection and reconnecting records no duplicate events.
- [x] A gap-fill that fails or is throttled is logged and counted, and the socket stays up.
- [x] After a gap-fill throttled with `Retry-After: 300`, a reconnect 61 seconds later makes no
      `/recent` request, and the first reconnect after 300 seconds makes one.
- [x] `wfm.throttles` counts every `429` and `509` by bucket and status, including one that
      surfaces without a retry, and is registered at startup.
- [x] `wfm.socket.reconnects` and `wfm.socket.gapfills` (by outcome) are registered at startup.

## Live checkpoints

### T5: The socket, live
- [x] The service subscribes on the live socket, and `wfm.socket.connected` reads 1.
- [x] A real underpriced listing on a watched item reaches an ntfy listener in under 10 seconds of service observation (upstream clock skew prevents a reliable `createdAt` comparison),
      before the next poll of that item.
- [x] Severing the established live socket TCP connection reconnects and gap-fills without a restart.
- [x] A shortened live sample reports `wfm.socket.frames` and poll/rate metrics. The hour-long
      platform-share diagnostic is waived under the author's accelerated-run instruction; no
      production population share is inferred from the captured fixture.

### T6: Accelerated multi-day alert budget (author amended 2026-10-13)
- [x] With injected observation times spanning multiple UTC days and both ingest paths, admissions (`pending`, `sent` and
      `failed` signals by `seen_at`) stay at or under the daily ceiling for every UTC day, read
      from `signal` with `mpsql` (R9a.8).
- [x] Deliveries a day, by outcome, are reported separately from admissions, not held to the
      ceiling: a signal admitted one day can be delivered the next, and at-least-once delivery can
      repeat one (R10.2).
- [x] Over the same run, `wfm.throttles` counts no `429` or `509`, and the socket was up for all
      but its reconnects.

## Checkpoint
- [x] `mbuild` green under several seeds
- [x] `SPEC.md` status, `CLAUDE.md` and `CHANGELOG.md` updated; ADR-0022 records decision 2
- [x] Independent review completed and operator handoff prepared

Evidence: `tasks/full-scope-acceptance.md`. Long-duration/phone checks follow the 2026-10-13
author amendment. Live throttle/recovery samples and injected multi-day budget tests are separate
checks; no continuous 72-hour availability claim is made.
