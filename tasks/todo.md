# Tasks: one alert, end to end, by polling (C6, C9a, C10)

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
checks and nothing else; how a check is met lives in the code and its tests.

## Before any code
- [ ] The author has confirmed or changed the nine decisions in `tasks/plan.md`.
- [ ] `SPEC.md` §10 questions 4–6 record the answers, and C9a/C10 requirements match them.

## C9a, first half — watches

### T1: Watches load from YAML and fail loudly
- [ ] A watch names an item slug, optional dimensions, a per-unit threshold, a priority and a
      logical topic; `watches.yaml` binds into a typed list with no new dependency.
- [ ] A watch naming a nonexistent slug fails startup with that slug in the message (R9a.2).
- [ ] A missing slug triggers one catalog refresh before failing, so a fresh database starts.
- [ ] A watch omitting a dimension its item has, or naming one it lacks, fails startup naming
      both the watch and the dimension; `any` is accepted (R9a.4).
- [ ] Two watches with one name fail startup.
- [ ] The committed `watches.yaml` holds no topic name, only logical ones (R10.6).

## C6 — poll scheduler

### T2: Watched items are polled on a fixed cadence
- [ ] With N watched items and interval I, each is polled once per I (R6.1, R6.3).
- [ ] No two polls run at once, and a tick that overruns delays the next (R6.3).
- [ ] A failed fetch for one item does not stop the others; a throttle ends the tick.
- [ ] Polling is off under test (R2.6), and runs on a thread the catalog sync does not share.
- [ ] `wfm.poll.lateness` and per-outcome poll counts are registered at startup (R6.4, R12.4).

## C9a, second half — the rule and the outbox

### T3: An underpriced listing writes one signal, in the ingest transaction
- [ ] A live sell order at or below the watch's per-unit threshold, from an online owner: one
      signal. An offline owner: none, unless the watch sets `includeOffline`.
- [ ] Buy orders, and sell orders in markets the watch does not select, never signal.
- [ ] Reconciling the same book again: no new signal (dedup key).
- [ ] An owner coming online with an unchanged cheap listing: one signal on the next poll.
- [ ] Rolling back the reconcile transaction leaves no signal (R9a.7).
- [ ] A `Stale` book evaluates nothing.
- [ ] The rule is a pure function, tested without Spring or a database.

### T4: Cooldown and the daily ceiling
- [ ] Within a watch's cooldown, a second listing at the same or a higher price writes no
      signal; a cheaper one does (R9a.5).
- [ ] Past the daily ceiling, a signal is written as `suppressed` and counted, and never sent
      (R9a.8).
- [ ] Signal counts by watch and state are registered at startup (R12.3).

## C10 — notifications

### T5: The dispatcher delivers the outbox to ntfy
- [ ] Mock ntfy: one pending signal, one POST, then `sent` with `notified_at` (R10.1).
- [ ] A 2xx is the only thing that marks a signal sent (R10.2).
- [ ] A forced failure retries with backoff to the cap, records the last error, then `failed`
      (R10.3).
- [ ] A signal older than the expiry when its turn comes is `expired`, not sent.
- [ ] A signal written before a restart is delivered after it.
- [ ] The push carries the item's display name, its dimensions, the unit price and lot size, and
      a click-through to the item page (R10.4); watch priority maps to ntfy priority (R10.5).
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
