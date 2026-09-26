# Tasks: seconds-level alerts from the socket (C5)

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
checks and nothing else; how a check is met lives in the code and its tests.

## Before any code
- [ ] The author has confirmed or changed the nine decisions in the plan.
- [ ] `SPEC.md` §10 question 7 records the answer to decision 2, and C5's requirements match the
      decisions.

## Payload first

### T1: A live socket capture
- [ ] A script under `bruno/` connects with the `wfm` subprotocol, subscribes to `newOrders` with
      `platform` and `crossplay` sent explicitly, and writes the frames it receives; it adds no
      dependency.
- [ ] Its capture of real `newOrder` frames, plus the `:ok` and one `reports/online`, is scrubbed
      like `scrub-orders.mjs` scrubs orders and committed as a fixture.
- [ ] The plan records whether a `newOrder` payload carries `itemId`, `user.status` and
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
- [ ] A malformed frame and an unknown route are logged and skipped, and the connection stays
      open (R5.6).
- [ ] An order for an item the catalog lacks is skipped, as `ingestPartial` already does.
- [ ] The socket is off under test and with `watchdawg.socket.enabled=false`; the harness fails a
      test whose socket reaches a non-local host (R2.6).
- [ ] `wfm.socket.connected` and `wfm.socket.frames` (by outcome) are registered at startup (R12.2).

### T3: Socket orders reach the rule
- [ ] A qualifying listing arriving on the socket for a watched market admits one signal, seen at
      the frame's arrival; an order that was already known admits nothing.
- [ ] The next book poll holding the same listing at the same price admits nothing (dedup key).
- [ ] Rolling back a partial ingest leaves neither its events nor its signal (R9a.7).
- [ ] A socket ingest and a book reconcile admitting for one watch at once admit at most one
      signal within its cooldown.
- [ ] Orders for unwatched items are recorded and evaluate nothing.

### T4: The socket stays up and fills its gaps
- [ ] A dropped connection reconnects with doubling, jittered backoff to the cap, and the backoff
      resets only after a connection has stayed subscribed for a minute (R5.3).
- [ ] 90 seconds without a frame closes the connection and reconnects.
- [ ] Each confirmed subscription gap-fills once from `/v2/orders/recent` as `source=recent`,
      unless the last gap-fill was under a minute ago; its orders reach the rule (R5.4).
- [ ] Killing the connection and reconnecting records no duplicate events.
- [ ] A gap-fill that fails or is throttled is logged and counted, and the socket stays up.
- [ ] `wfm.socket.reconnects` and `wfm.socket.gapfills` (by outcome) are registered at startup.

## Live checkpoints

### T5: The socket, live
- [ ] The service subscribes on the live socket, and `wfm.socket.connected` reads 1.
- [ ] A real underpriced listing on a watched item is pushed within seconds of its `createdAt`,
      before the next poll of that item.
- [ ] Cutting the network and restoring it reconnects and gap-fills without a restart.
- [ ] Over an hour, the non-PC share of socket orders is near the ~7% `/recent` showed (§2.7), and
      `wfm.socket.frames` gives the feed's write volume.

### T6: 72 hours inside the alert budget
- [ ] Over 72 hours with the socket and the poll loop on, deliveries a day stay at or under the
      daily ceiling, and admissions by watch are read from `watchdawg.signals` (R9a.8).
- [ ] Over the same run, `wfm.retries` counts no `429` or `509`, and the socket was up for all
      but its reconnects.

## Checkpoint
- [ ] `mbuild` green under several seeds
- [ ] `SPEC.md` status, `CLAUDE.md` and `CHANGELOG.md` updated; ADR-0022 written if decision 2
      stands
- [ ] Review with human
