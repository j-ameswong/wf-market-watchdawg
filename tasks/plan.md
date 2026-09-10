# Implementation Plan: C1 — API access

> Source: `SPEC.md` §4 "C1 — API access" (R1.1–R1.8), bounded by §2.1 (rate budget), §2.5 (two
> upstream limits) and §2.7 (crossplay). Drafted 2026-09-09. **Approved and built through Phase 2**
> (T1–T6); Checkpoint B's review is the open gate before Phase 3.

## Overview

C1 makes every outbound call to warframe.market pass through one compliant, paced, observable
path. It has no dependencies and sits at the head of the spine (`C1 → C2 → C3 → C4 → C5 → C9a →
C10`), so it is the first thing built and everything else inherits its correctness.

C1 started from a single unpaced `RestClient` with a declared-but-unused `wfm.requests-per-second`,
a bare `User-Agent`, no v1 client, and a status handler that converted `429`/`509` straight into a
throw with no retry. T1–T6 have replaced all of that: every call is paced on one of two route-keyed
buckets, retried once on budget, typed on failure, and stamped with the one crossplay setting. What
remains is the second channel (T7's v1 client) and the runtime proof (T8's metrics and live run).

## Assumptions

1. C1 delivers the **REST** half of R1.8. The socket half (`subscribe/newOrders` payload) has no
   code to attach to until C5, where R5.2 already requires the value be sent explicitly from the
   global setting. C1's job is to make that setting the only readable source. The cross-channel
   acceptance test was **dropped from `SPEC.md` on 2026-09-09** (Decision 7), so all four remaining
   C1 acceptance bullets are satisfiable inside C1.
2. Outbound concurrency (R1.4) is capped **globally** at 2, not per bucket — `509` is a
   connection-level signal from Cloudflare, not a per-route one.
3. The limiter is hand-rolled. §9 makes any new dependency an ask-first item, and it also forces a
   `nix/deps.json` regeneration. A token gate plus a semaphore is ~40 lines.
4. Pure-logic classes (the limiter) get plain JUnit tests with no Spring context and no container.
   §8 describes every current test as a `@SpringBootTest`; that is a description of what exists,
   not a prohibition. See Open Question 4.
5. "Bounded retry" (R1.3) means **exactly one** retry. The spec's own acceptance bullet says
   "exactly one retry", so that is the bound.

## Architecture Decisions

- **Enforcement lives in a `ClientHttpRequestInterceptor`, not at call sites.** R1.1 says
  "enforced at the transport layer". An interceptor is the only seam a call site cannot skip, and
  it is where the retry (R1.3) can re-acquire budget rather than bypass it.
- **`Platform`/`Crossplay` move into that same interceptor stack.** They are currently
  `defaultHeader`s, which any call site can override — that is exactly what R1.8 forbids. Same
  seam, same guarantee.
- **Buckets are keyed by route predicate, not by which client bean issued the call.** R1.2's
  wording and §2.1's arithmetic disagreed; the arithmetic wins. **Decided 2026-09-09** — see
  Resolved Decision 1.
- **Vertical slicing, with one deviation.** The vertical unit here is "a real call to a real route
  completes through the whole governed path": T3 delivers that for v2, T7 for v1. T1 and T2 are
  genuine sub-slice steps — the limiter is worth isolating so its pacing can be proven
  deterministically before HTTP timing noise is in the picture.
- **No new dependency until T8**, which needs actuator for R12.1 and must be asked for first.

## Dependency Graph

```
T1 config surface
     │
T2 WfmRateLimiter (pure)
     │
T3 transport interceptor + no-bypass guard   ◄── the first governed v2 call
     ├──────────────┬──────────────┐
T4 retry/429/509   T5 error bodies  T6 crossplay one-setting
     │              │
     └──────┬───────┘
          T7 v1 legacy client + envelope      ◄── the first governed v1 call
            │
          T8 per-bucket metrics + 1h live proof
```

## Task List

### Phase 1 — A governed transport
- [x] T1: Bucket-aware `WfmProperties`
- [x] T2: `WfmRateLimiter` with two independent buckets
- [x] T3: Route every v2 call through the limiter at the transport layer

**Checkpoint A** — a paced v2 call works end to end and cannot be bypassed. *Met; awaiting human
review. One blocker surfaced and was fixed during the checkpoint — the test-run scheduler could
reach the live API; see `tasks/todo.md`.*

### Phase 2 — Failing correctly, and one context
- [x] T4: `429`/`509` → bounded retry that honors `Retry-After` and consumes budget
- [x] T5: Non-JSON and 5xx bodies never reach Jackson
- [x] T6: Crossplay as one setting, structurally un-omittable

**Checkpoint B** — every documented C1 failure mode surfaces as a typed error. *Met; awaiting human
review. One caveat carried forward — an unset `wfm.crossplay` fails startup with Spring's
primitive-binding NPE rather than a named property; see `tasks/todo.md`.*

### Phase 3 — The second channel
- [ ] T7: v1 legacy client and the `payload`/`include` envelope

**Checkpoint C** — both API versions run through one limiter, on the right buckets.

### Phase 4 — Proof at runtime
- [ ] T8: Per-bucket req/s metrics and the 1h live run

**Checkpoint D** — C1 acceptance met; C3 may begin.

Full task bodies with acceptance criteria live in `tasks/todo.md`.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Buckets keyed by client bean rather than route class — the v1 statistics sweep (3,800/day) lands in the 12 req/min contract bucket and starves, or `/auctions/search` lands in the 2 req/s bucket and breaches §2.5 | **High** — silently wrong pacing on both sides | Resolved: key by URI predicate (Decision 1); T7 asserts both directions |
| Hand-rolled limiter subtly wrong under concurrency | **High** — a `429` is a bug in our pacing (§9, "Never") | Deterministic T2 tests via injected clock, then the T8 live run proves it against the real server |
| `Retry-After` may be an HTTP-date, not seconds. The original code did `toLongOrNull()`, which silently yields `null` | Med | Closed in T4: `retryAfterOf` parses both forms, and `WfmRetryTest` asserts both plus the unparseable case |
| Wall-clock pacing tests make the suite slow or flaky | Med | Injected sleeper for the logic tests; one real-time test at an inflated rate so it costs ~200ms |
| No test asserts REST and socket agree on crossplay (Decision 7), so §2.7's fabricated-`vanished` failure has no direct guard | Med | Mitigated in T6, **not closed** — the Kotlin defaults are gone so no channel can take an upstream default, `WfmContext` is the single read point, and `RateLimitWiringTest` guards the REST side. The socket half stays an obligation on R5.2 until C5 exists |
| Actuator dependency in T8 forces `nix/deps.json` regeneration | Low | Approved (Decision 6); regenerate with the documented `updateScript` in the same commit, and T8 verifies `nix build .#market` |

## Resolved Decisions

1. **The bucket boundary is route class, not API version.** *(Approved 2026-09-09.)* R1.2 names
   the buckets "v2 public" and "v1 contract search", but §2.1's arithmetic puts v1 `statistics`
   (3,800 req/day, 0.044/s) *inside* the "v2 bucket total" of ~112,500 and lists only
   `/auctions/search` as "separate bucket". The arithmetic wins, and it matches §2.5's wording
   ("contract search is expected to be limited to 10–20 req/minute"). Buckets are therefore keyed
   by a URI predicate: `contract-search` for auction-search routes, `public` for everything else
   regardless of API version. **`SPEC.md` R1.2 should be reworded to match** — flag it in the next
   spec pass so the text stops contradicting the table.
2. **`User-Agent` contact URL is `https://github.com/j-ameswong/wf-market-watchdawg`.**
   *(Approved 2026-09-09.)* Satisfies R1.5; lands in T1.
3. **The limiter is hand-rolled — no new dependency.** *(Approved 2026-09-09.)* Two fixed rates,
   one process, no distributed coordination. A library would supply well-tested token arithmetic
   but leave the error-prone part — releasing the permit when a call throws, making the retry
   re-acquire, keeping the buckets independent — exactly where it is. Revisit only if T8's live
   run shows pacing drift.
4. **Retry bound: exactly one retry; `Retry-After` ceiling 60s.** *(Delegated, decided
   2026-09-09.)* R1.3 says "bounded" without a number, and the spec's own acceptance bullet says
   "exactly one retry" — so one it is. A `Retry-After` above 60s surfaces as a typed failure
   immediately rather than parking a worker thread for minutes; the poll scheduler (C6) is the
   component that should decide what to do with a long cooloff, not an interceptor. The ceiling is
   configurable (`wfm.limits.max-retry-after`).
5. **Pure-logic tests may skip the Spring context.** *(Delegated, decided 2026-09-09.)* §8's
   "every test is a `@SpringBootTest`" describes what exists, not a rule. `WfmRateLimiter` touches
   no Spring machinery and no database; booting Testcontainers Postgres to test a token bucket
   adds seconds per run and buys nothing. Scope is narrow on purpose: **plain JUnit only for
   classes with no Spring or JDBC dependency.** Everything that touches HTTP still goes through
   `MockRestServiceServer` in a Spring context (R2.6), and everything touching storage still gets
   a container.
6. **`spring-boot-starter-actuator` is approved for T8.** *(Delegated, decided 2026-09-09.)* R12.5
   already presupposes it — "Actuator only; no data endpoints" — so this is the spec's own chosen
   mechanism rather than a new direction. It is a Spring Boot BOM-managed starter, so no version
   pinning. The `nix/deps.json` regeneration happens in the same commit, and T8 verifies
   `nix build .#market` afterwards.
7. **The cross-channel crossplay acceptance test is dropped.** *(Approved 2026-09-09.)* It
   asserted that a recorded REST call and a recorded socket subscribe frame carry the same
   crossplay value. It was untestable inside C1 — there is no socket until C5 — and R5.2 already
   obliges the socket to send the value explicitly from the global setting. Removed from
   `SPEC.md` §4 C1. **R1.8 itself is unchanged**; only its acceptance test is gone.
8. **A `509` permanently narrows the connection cap; it never widens back.** *(Delegated, decided
   2026-09-10 during T4.)* R1.4's acceptance asks that `509` "narrow effective concurrency rather
   than only wait", which leaves open whether the cap recovers. It does not: the limiter gives up
   one slot per `509`, floored at one, and only a restart resets it. Under
   [ADR-0004](docs/adr/0004-rate-limit-discipline-is-a-hard-boundary.md) a `509` is a bug in our own
   budgeting, so creeping back toward a concurrency the server has already refused is precisely the
   "traffic pattern" the upstream rules police. The configured cap is 2, so the only move available
   is 2 → 1; a decaying cap would be machinery with one step to walk.

## Open Questions

**None outstanding.** All eight are recorded above as Resolved Decisions.

Decision 1's carried-forward obligation is **discharged**: `SPEC.md` R1.2 now states the buckets as
route classes and cites [ADR-0005](docs/adr/0005-rate-buckets-keyed-by-route-class.md), so its
wording no longer contradicts §2.1's arithmetic.

## Results

_(T8 records the 1h live-run numbers here: sustained req/s per bucket, `429`/`509` counts.)_
