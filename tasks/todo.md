# Tasks: one alert, end to end, by polling (C6, C9a, C10)

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
checks and nothing else; how a check is met lives in the code and its tests.

## Before any code
- [x] The author has confirmed or changed the nine decisions in `tasks/plan.md` (2026-09-26).
- [x] `SPEC.md` §10 questions 4–6 record the answers, and C9a/C10 requirements match them.

## C9a, first half — watches

### T1: Watches load from YAML and fail loudly
- [x] A watch names an item slug, optional dimensions, a per-unit threshold, a priority and a
      logical topic; `watches.yaml` binds into a typed list with no new dependency.
- [x] A watch naming a nonexistent slug fails startup with that slug in the message (R9a.2).
- [x] A missing slug triggers one catalog refresh before failing, so a fresh database starts.
- [x] A watch omitting a dimension its item has, or naming one it lacks, fails startup naming
      both the watch and the dimension; `any` is accepted (R9a.4).
- [x] Two watches with one name fail startup.
- [x] The committed `watches.yaml` holds no topic name, only logical ones (R10.6).

## C6 — poll scheduler

### T2: Watched items are polled on a fixed cadence
- [x] With N watched items and interval I, each is polled once per I (R6.1, R6.3).
- [x] No two polls run at once, and a tick that overruns delays the next (R6.3).
- [x] A failed fetch for one item does not stop the others.
- [x] A throttle ends the tick, and no request is made before its `Retry-After` has passed: a
      ten-minute `Retry-After` at a two-minute cadence means no poll for ten minutes (R6.6, ADR-0019).
- [x] Polling is off under test (R2.6), and runs on a thread the catalog sync does not share.
- [x] `wfm.poll.lateness` and per-outcome poll counts are registered at startup (R6.4, R12.4).

## The first complete path — rule, outbox, notification

### T3: An underpriced listing reaches (mock) ntfy
- [x] A live sell order at or below the watch's per-unit threshold, from an online owner: one
      signal. An offline owner: none.
- [x] Buy orders, and sell orders in markets the watch does not select, never signal.
- [x] An owner coming online with an unchanged cheap listing: one signal on the next poll.
- [x] Rolling back the reconcile transaction leaves no signal (R9a.7); a `Stale` book evaluates
      nothing.
- [x] The rule is a pure function, tested without Spring or a database.
- [x] Mock ntfy: a pending signal is POSTed once and marked `sent` with `notified_at`, and only
      a 2xx marks it (R10.1, R10.2).
- [x] The push carries the item's display name, its dimensions, the unit price and lot size,
      when the listing was seen, and a click-through to the item page (R10.4); watch priority
      maps to ntfy priority (R10.5).
- [x] With no topic mapped, the dispatcher does not run and signals stay `pending`.

## C9a, second half — suppression

### T4: Dedup, cooldown and the daily ceiling
- [x] Reconciling the same book again: no new signal (dedup key).
- [x] With the dispatcher paused, one poll holding two equally cheap qualifying orders admits
      exactly one signal: the cooldown counts pending signals, not only sent ones (R9a.5).
- [x] Within a watch's cooldown, a later listing at the same or a higher price is not admitted;
      a cheaper one is.
- [x] Past the daily ceiling of admissions, a candidate is written as `suppressed`, counted, and
      never sent; it is not written again on the next poll (R9a.8).
- [x] Signal counts by watch and state are registered at startup (R12.3).

## C10 — delivery guarantees

### T5: Delivery survives failure and restart, and is measured
- [ ] A forced failure retries with backoff to the cap, records the last error, then `failed`
      (R10.3).
- [ ] A signal written before a restart is delivered after it, however long the restart took.
- [ ] Deliveries are counted by outcome (`sent`, `retried`, `failed`), separately from signals,
      and registered at startup (R12.3).
- [ ] One dispatcher runs at a time and never overlaps itself.
- [ ] The topic is in the request body, not the URL; neither `last_error` nor any log line at
      DEBUG contains it (R10.6).
- [ ] The ntfy client carries no WFM interceptor, and `RateLimitWiringTest` names it as non-WFM.
- [ ] A watch naming a logical topic with no runtime mapping fails startup.

## Live checkpoint

### T6: One real alert
- [ ] One real push arrives on the operator's phone, from a real listing.
- [ ] A repeat within cooldown is suppressed.
- [ ] Killing the process between a signal and its send still delivers it on restart.
- [ ] `git grep` for the real topic finds nothing.

## Checkpoint
- [ ] `mbuild` green under several seeds
- [ ] `SPEC.md` status, `CLAUDE.md` and `CHANGELOG.md` updated
- [ ] Review with human
