# Plan: one alert, end to end, by polling (C6, C9a, C10)

> Source: `SPEC.md` §3.4 build order step 2, §4 C6, C9a and C10, §10 questions 4–6, and ADRs
> [0013](../docs/adr/0013-watches-in-version-controlled-yaml.md),
> [0014](../docs/adr/0014-alert-budget-is-a-requirement.md),
> [0015](../docs/adr/0015-at-least-once-delivery-through-an-outbox.md),
> [0016](../docs/adr/0016-ntfy-high-entropy-topic-as-credential.md) and
> [0021](../docs/adr/0021-limiter-owns-the-ceiling-poll-loop-owns-freshness.md). Drafted
> 2026-09-26 on top of C4 (PR #4). Task bodies and acceptance checks are in `tasks/todo.md`.
> **No code until the author has confirmed the decisions below.**

## Goal

The milestone is done when (SPEC §3.4):

1. a real push arrives on the operator's phone for a real underpriced listing,
2. a repeat within cooldown is suppressed, and
3. a restart between a signal being written and being sent still delivers it.

## Scope

1. **Watches (C9a, first half).** `watches.yaml` declares what to watch, loads at startup and
   fails startup naming the bad entry (R9a.1, R9a.2, R9a.4). C6 reads its poll targets from it,
   so it comes first.
2. **Poll loop (C6).** One loop, one instance, a fixed cadence over the watched items, never
   overlapping itself, with lateness and request use measured (R6.1–R6.5).
3. **Underpriced-listing rule and signal outbox (C9a, second half).** Evaluated inside the
   book-reconcile transaction, so a rolled-back ingest leaves no signal (R9a.3, R9a.5–R9a.7).
   A cooldown and a daily ceiling keep volume inside the alert budget (R9a.8).
4. **Notifications (C10).** A dispatcher drains the outbox to ntfy.sh, at least once, with
   bounded retries, and the topic is never committed or logged (R10.1–R10.7).
5. **Metrics for all of it (C12).** Poll lateness (R12.4); signal and notification counts
   (R12.3).

## Order

`T1 watches → T2 poll loop → T3 rule + outbox → T4 cooldown + ceiling → T5 dispatcher + ntfy →
T6 live run`. Each task leaves `mbuild` green and the service runnable:

- After T2 the service polls watched items for real and fills the warehouse, which is useful on
  its own and exercises C4 against live traffic before any rule depends on it.
- After T3–T4 signals accumulate in the outbox and can be inspected with `mpsql`, with nothing
  sent yet. Tuning thresholds against real data costs no pushes.
- T5 turns delivery on. It is last because it adds the only secret and the only non-WFM client.

T1–T5 fit one PR each or one stacked series; T6 is a manual checkpoint, like C3's live runs.

## Out of scope

The spread and crossing rules (R9a.3's other two families), C5 (the socket; it will feed the same
rule later), relative or baseline thresholds (C9b), adaptive scheduling and database poll targets
(deferred by ADR-0021), and a self-hosted ntfy. The 72h alert-budget run (C9a acceptance) is a
follow-up once T6 has passed, not a gate on this milestone.

## Budget

C6 is the first scheduled consumer of `/v2/orders/item/{slug}`, an endpoint already called and
already paced, so no new upstream endpoint and no new dependency is introduced (§9 ask-first).
`N` watched items at interval `I` cost `N / I` req/s on the `public` bucket (2 req/s configured):
20 items at the proposed 2-minute default is 0.17 req/s. Over-demand shows up as poll lateness,
never as a `429` (R6.5), and the lateness metric is how it is seen.

## Decisions to confirm (author)

Each has a recommended default; the plan is written against it.

1. **Threshold form: an absolute per-unit price per watch.** A watch says "sell listings at or
   below 12 plat a unit". A threshold relative to the current book (say, 30% under the
   second-best ask) self-calibrates but fires on thin books and needs history to tune, which is
   C9b's territory (R9b.3).
2. **Only online sellers qualify, by default.** Offline owners' listings stay up for 48h and
   cannot be acted on (SPEC §11). A watch can opt in with `includeOffline: true`.
3. **The rule reads the reconciled book, not just its events.** C4's events record nothing about
   online status (R4.2), so a cheap listing whose seller comes online produces no event. Checking
   every live sell order of a watched market on each poll catches it; the dedup key stops it
   firing again on the next poll. Later, the socket feeds `appeared` orders through the same rule.
4. **Dedup key and cooldown (R9a.5).** The dedup key is watch + order + unit price, so one
   listing at one price signals once, ever. The cooldown is per watch: within it, a new signal is
   written only if it is cheaper than the last one that watch sent. A cheaper listing still gets
   through; a second listing at the same price does not.
5. **Alert budget (§10 Q4): a ceiling, not a floor.** A configurable daily ceiling (default 50)
   across all watches. A signal over it is still written, marked `suppressed`, and counted, so
   the budget breach is visible without the phone ringing. A quiet day is not a defect.
6. **An omitted dimension (§10 Q5) is an error, not a guess.** A watch on an item that has ranks
   must name `rank: 5` or `rank: any`; one naming a dimension the item lacks fails too. Neither
   "no rank" nor "any rank" is ever inferred, so they cannot be confused.
7. **Terminal delivery failure (§10 Q6).** After 5 attempts with backoff a signal is marked
   `failed`, logged at WARN without the topic, and counted. A signal older than 30 minutes when
   its turn comes is marked `expired` instead of sent: a stale price is not actionable. Both
   numbers are configuration.
8. **Where watches.yaml lives.** `market/src/main/resources/watches.yaml`, imported with
   `spring.config.import` and bound by `@ConfigurationProperties`, so no YAML dependency is added.
   `WATCHDAWG_WATCHES` can point at a file elsewhere for the packaged jar.
9. **No trader name in the notification.** The order model binds no identity (T5 of C4, §9), so
   the push carries item, dimensions, unit price, lot size and a click-through to the item page,
   where the listing is visible. Adding the seller's name would mean storing it in `signal`.

## Design notes

- **Catalog before watches.** Validation needs the catalog, which a fresh database does not have
  until the first sync. On startup, if a watched slug is missing, run one hash-gated catalog
  refresh synchronously (two requests) and validate again; only then fail.
- **The rule is a pure function**, `underpriced(watch, book) → candidates`, tested without Spring,
  like `reconcile()`. `OrderIngest.reconcileBook` calls the rule engine inside its transaction and
  inserts signals with `on conflict (dedup_key) do nothing` (R9a.7). A `Stale` book evaluates
  nothing.
- **`signal` is an ordinary table, not a hypertable**: it is small, and the dispatcher updates
  rows. Columns: watch, rule, market, order, dedup key, unit price, threshold, state
  (`pending`, `sent`, `failed`, `expired`, `suppressed`), attempts, next attempt, last error,
  created and notified times.
- **The poll loop gets its own scheduler thread.** Spring's default scheduler has one thread, so
  a catalog refresh or detail batch would otherwise add to poll lateness.
- **One tick polls every watched item in turn.** A failed fetch logs, counts and moves on (R4.12
  already guarantees it wrote nothing); a `ThrottledException` ends the tick, since the rest would
  meet the same limit.
- **The topic never appears in a URL.** ntfy accepts a JSON publish to its root with `topic` in
  the body, so the request URL and every `RestClient` exception message stay topic-free, and so
  does `last_error`. The ntfy client is its own `RestClient`, named in `RateLimitWiringTest` as
  non-WFM, and inherits no WFM pacing or headers.
- **Topics resolve at runtime.** Watches name a logical topic (`default`); the real name comes
  from `WATCHDAWG_NOTIFY_TOPICS_<NAME>` in the environment. A watch naming an unmapped topic fails
  startup, naming the logical name only.
- **The dispatcher claims rows with `for update skip locked`**, so it is safe even though only one
  instance runs.

## Risks

- **A first poll signals every cheap listing already up.** That is correct once, but the
  ceiling and cooldown are what keep it to a handful. Watches should be few and thresholds tight
  until T6 has shown real volumes.
- **The absolute threshold drifts as prices move.** Retuning is a YAML edit and a restart, which
  is what ADR-0013 accepted.
- **Poll latency is the alert latency** until C5: at a 2-minute cadence a listing can be up to
  two minutes old when it is seen.
