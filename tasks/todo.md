# Tasks: seconds-level alerts from the socket (C5)

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
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
- [ ] Against a local fake server, the handshake offers the `wfm` subprotocol and the project
      `User-Agent` (R5.1, R1.5).
- [ ] The subscribe frame carries `platform` and `crossplay` from `WfmContext`, both always
      present; a test pins that it and the REST `Crossplay` header agree, for `true` and `false`
      (R1.8, R5.2), and `WfmContext`'s `TODO(C5)` is gone.
- [ ] A captured `newOrder` frame produces one `appeared` event with `source=ws` (R5.5); the same
      frame again produces none.
- [ ] `:error` with `alreadySubscribed` counts as subscribed; any other `:error` closes the
      connection.
- [ ] A message split across several text frames is ingested once, as one order.
- [ ] A malformed message and an unknown route are logged and skipped, the connection stays open,
      and a valid `newOrder` right after the malformed one is ingested (R5.6).
- [ ] An order for an item the catalog lacks is skipped, as `ingestPartial` already does.
- [ ] The socket is off under test and with `watchdawg.socket.enabled=false`; the harness fails a
      test whose socket reaches a non-local host (R2.6).
- [ ] `wfm.socket.connected` and `wfm.socket.frames` (by outcome) are registered at startup (R12.2).

### T3: Socket orders reach the rule
- [ ] A qualifying listing arriving on the socket for a watched market admits one signal, seen at
      the frame's arrival; an order that was already known admits nothing.
- [ ] The next book poll holding the same listing at the same price admits nothing (dedup key).
- [ ] Rolling back a partial ingest leaves neither its events nor its signal (R9a.7).
- [ ] A socket ingest and a book reconcile each holding an equally priced candidate for one watch,
      committing at once, admit exactly one signal within its cooldown.
- [ ] Two ingests with candidates for different watches, competing for the last admission under
      the daily ceiling, admit one `pending` and write the other `suppressed`.
- [ ] Orders for unwatched items are recorded and evaluate nothing.

### T4: The socket stays up and fills its gaps
- [ ] A dropped connection reconnects with doubling, jittered backoff to the cap, and the backoff
      resets only after a connection has stayed subscribed for a minute (R5.3).
- [ ] A server that accepts the connection but never completes the handshake is abandoned after
      the 10-second connect deadline, and the socket reconnects.
- [ ] A server that sends `reports/online` but withholds `:ok` is abandoned after the 10-second
      subscription deadline, and the socket reconnects.
- [ ] 90 seconds without a frame once subscribed closes the connection and reconnects.
- [ ] Each confirmed subscription gap-fills once from `/v2/orders/recent` as `source=recent`,
      unless the last gap-fill was under a minute ago; its orders reach the rule (R5.4).
- [ ] Killing the connection and reconnecting records no duplicate events.
- [ ] A gap-fill that fails or is throttled is logged and counted, and the socket stays up.
- [ ] After a gap-fill throttled with `Retry-After: 300`, a reconnect 61 seconds later makes no
      `/recent` request, and the first reconnect after 300 seconds makes one.
- [ ] `wfm.throttles` counts every `429` and `509` by bucket and status, including one that
      surfaces without a retry, and is registered at startup.
- [ ] `wfm.socket.reconnects` and `wfm.socket.gapfills` (by outcome) are registered at startup.

## Live checkpoints

### T5: The socket, live
- [ ] The service subscribes on the live socket, and `wfm.socket.connected` reads 1.
- [ ] A real underpriced listing on a watched item is pushed within seconds of its `createdAt`,
      before the next poll of that item.
- [ ] Cutting the network and restoring it reconnects and gap-fills without a restart.
- [ ] Over an hour, `wfm.socket.frames` gives the feed's write volume, and the non-PC share of
      socket orders is reported beside the ~7% `/recent` showed (§2.7). The share is a diagnostic,
      not a pass/fail check.

### T6: 72 hours inside the alert budget
- [ ] Over 72 hours with the socket and the poll loop on, admissions (`pending`, `sent` and
      `failed` signals by `seen_at`) stay at or under the daily ceiling for every UTC day, read
      from `signal` with `mpsql` (R9a.8).
- [ ] Deliveries a day, by outcome, are reported separately from admissions, not held to the
      ceiling: a signal admitted one day can be delivered the next, and at-least-once delivery can
      repeat one (R10.2).
- [ ] Over the same run, `wfm.throttles` counts no `429` or `509`, and the socket was up for all
      but its reconnects.

## Checkpoint
- [ ] `mbuild` green under several seeds
- [ ] `SPEC.md` status, `CLAUDE.md` and `CHANGELOG.md` updated; ADR-0022 records decision 2
- [ ] Review with human
