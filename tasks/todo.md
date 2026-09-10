# Tasks: C1 — API access

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4 C1.
Paths are relative to the repo root; the Gradle root is `market/`.

---

## Phase 1 — A governed transport

### Task 1: Bucket-aware `WfmProperties`

**Description:** Replace the declared-but-unused `wfm.requests-per-second` scalar with per-bucket
limits and the concurrency cap, and give `User-Agent` the contact URL R1.5 requires. Config surface
only — the limiter binds to it in T2.

**Acceptance criteria:**
- [x] `wfm.limits.public` (2 req/s) and `wfm.limits.contract-search` (12 req/min) bind from
      `application.yaml`, with the upstream ceilings (3 req/s, 20 req/min) recorded in a comment
      beside them (R1.2).
- [x] `wfm.limits.max-concurrency` (2) binds (R1.4).
- [x] `wfm.limits.max-retry-after` (60s) binds — the ceiling above which a `Retry-After` surfaces
      immediately rather than parking a thread (Decision 4, consumed by T4).
- [x] `requests-per-second` is gone from the record *and* the yaml — no dead property survives.
- [x] `user-agent` names the project and the contact URL
      `https://github.com/j-ameswong/wf-market-watchdawg` (R1.5).

**Verification:**
- [x] `mtest --tests '*WfmPropertiesTest'`
- [x] `mbuild` green (includes `spotlessCheck`)
- [x] `grep -rn 'requests-per-second\|requestsPerSecond' market/src` returns nothing

**Notes:** limits bind as a nested `WfmProperties.Rate(permits, per)`, so the yaml states each
bucket in the units the upstream rules use rather than as a pre-divided `Double`. The ceilings are
also asserted in `WfmPropertiesTest`, not only commented — §9 makes exceeding them a hard boundary.

**Dependencies:** None
**Files likely touched:** `market/src/main/kotlin/com/watchdawg/market/wfm/WfmConfig.kt`,
`market/src/main/resources/application.yaml`, `market/src/test/kotlin/.../wfm/WfmPropertiesTest.kt`
**Estimated scope:** S

---

### Task 2: `WfmRateLimiter` with two independent buckets

**Description:** A hand-rolled limiter exposing `acquire(bucket)`, backed by two independently
paced token buckets plus one global semaphore capping in-flight calls. Clock and sleeper are
injected so pacing is deterministic under test. No HTTP in this task.

**Acceptance criteria:**
- [x] Two buckets pace independently: exhausting `contract-search` does not delay a `public`
      acquire (R1.2).
- [x] N sequential acquires on a bucket at limit L return no sooner than (N−1)/L — the spec's
      first acceptance bullet, tested both on the injected clock and once in real time.
- [x] With `max-concurrency` = 2, a third concurrent acquire blocks until a permit is released,
      and a permit is released even when the caller throws (R1.4).

**Verification:**
- [x] `mtest --tests '*WfmRateLimiterTest'` — plain JUnit, no Spring context, no container
      (Decision 5)
- [x] `mbuild` green

**Notes:** the API is `acquire(bucket) { … }` rather than a bare `acquire(bucket)` — release-on-throw
is only structural if the limiter owns the `try`/`finally`. T4's retry therefore calls `acquire`
**again, sequentially**, never nested inside the first: nesting would deadlock against
`max-concurrency`. Turns are evenly spaced with **no burst allowance**, and are taken before the
semaphore so a thread waiting out pacing doesn't hold a connection slot. Each acceptance criterion
was mutation-checked (no-op the sleep; widen the cap to 3) to confirm the tests discriminate.

**Dependencies:** T1
**Files likely touched:** `market/src/main/kotlin/com/watchdawg/market/wfm/WfmRateLimiter.kt`,
`market/src/test/kotlin/.../wfm/WfmRateLimiterTest.kt`
**Estimated scope:** S

---

### Task 3: Route every v2 call through the limiter at the transport layer

**Description:** Install the limiter as a `ClientHttpRequestInterceptor` on `wfmRestClient`, so no
call site can issue an unpaced request (R1.1). Bucket selection is a function of the request URI,
not of which bean issued the call (Decision 1). This is the first vertical slice: a real v2
call, paced, end to end.

**Acceptance criteria:**
- [ ] Every request through the v2 client acquires from the `public` bucket before the connection
      opens, and releases on completion **including on exception**.
- [ ] A context test asserts every `RestClient` bean carries the limiter interceptor — this is the
      standing no-bypass guard for R1.1, and it covers beans added later for free.
- [ ] `WfmClient.getVersions()` and `getItems()` still pass against `MockRestServiceServer`, with
      no change to their signatures.

**Verification:**
- [ ] `mtest --tests '*WfmClientTest' --tests '*RateLimitWiringTest'`
- [ ] `mbuild` green
- [ ] Manual: `mrun` completes one catalog sync with no behavioral change

**Dependencies:** T2
**Files likely touched:** `market/src/main/kotlin/com/watchdawg/market/wfm/WfmConfig.kt`,
`.../wfm/WfmRateLimitInterceptor.kt`, `market/src/test/kotlin/.../wfm/WfmClientTest.kt`,
`.../wfm/RateLimitWiringTest.kt`
**Estimated scope:** M

---

## Checkpoint A — governed transport
- [ ] `mbuild` green; all tests pass in any order (R2.7)
- [ ] A paced v2 call works end to end and demonstrably cannot be bypassed
- [ ] Review with human before Phase 2

---

## Phase 2 — Failing correctly, and one context

### Task 4: `429`/`509` → bounded retry that honors `Retry-After` and consumes budget

**Description:** Replace the current straight-to-throw status handler. `429` (rate) and `509`
(concurrency) become distinct typed failures. One retry waits out `Retry-After`, then **re-acquires
from the limiter** before reissuing, so retries spend budget rather than bypass it (R1.3, R1.4).

**Acceptance criteria:**
- [ ] A `429` fixture with `Retry-After: 2` yields exactly one retry, no sooner than 2s, then
      success — the spec's second acceptance bullet.
- [ ] A second consecutive `429` surfaces a typed `RateLimitedException`; there is no third attempt.
- [ ] `509` produces a distinct type from `429` and narrows effective concurrency rather than only
      waiting (R1.4).
- [ ] `Retry-After` parses as both delta-seconds and HTTP-date; a value above
      `wfm.limits.max-retry-after` (60s) surfaces immediately instead of parking a thread
      (Decision 4).
- [ ] The retry is visible to the limiter — asserted via a limiter counter, not by inspection.

**Verification:**
- [ ] `mtest --tests '*WfmRetryTest'`
- [ ] `mbuild` green

**Dependencies:** T3
**Files likely touched:** `.../wfm/WfmRateLimitInterceptor.kt`, `.../wfm/WfmErrors.kt`,
`.../wfm/WfmConfig.kt`, `market/src/test/kotlin/.../wfm/WfmRetryTest.kt`
**Estimated scope:** M

---

### Task 5: Non-JSON and 5xx bodies never reach Jackson

**Description:** A plain-text `403` (v1 `/items/{slug}/orders` answers exactly this) or an HTML
`502` must surface as a typed error carrying status and a bounded body excerpt, never as a
deserialization crash (R1.7).

**Acceptance criteria:**
- [ ] A `403` with a `text/plain` body surfaces a typed error, not a Jackson exception — the spec's
      third acceptance bullet.
- [ ] A `502` with an HTML body does the same.
- [ ] The error carries the status and an excerpt capped at 200 characters; the full body is not
      logged (§9 — no PII accumulation).
- [ ] A well-formed v2 envelope with `error` populated and `data` null still throws the existing
      envelope error, unchanged.

**Verification:**
- [ ] `mtest --tests '*WfmErrorBodyTest'`
- [ ] `mbuild` green

**Dependencies:** T3
**Files likely touched:** `.../wfm/WfmConfig.kt`, `.../wfm/WfmErrors.kt`, `.../wfm/WfmClient.kt`,
`market/src/test/kotlin/.../wfm/WfmErrorBodyTest.kt`
**Estimated scope:** S

---

### Task 6: Crossplay as one setting, structurally un-omittable

**Description:** `Platform` and `Crossplay` are currently `defaultHeader`s, which any call site can
override — precisely what R1.8 forbids. Move them into the interceptor stack alongside the limiter,
and expose the value as one injectable `WfmContext` that C5's socket client is obliged to read
(R5.2). Also closes the live trap where the Kotlin default is `false` while `application.yaml`
says `true`.

**Acceptance criteria:**
- [ ] Every outbound REST request carries `Crossplay` equal to `wfm.crossplay` **even when the call
      site sets its own value** — asserted by a test that attempts the override.
- [ ] `WfmProperties.crossplay` and `.platform` have no Kotlin defaults; an unset value fails
      startup, so no channel can silently inherit an upstream default (R1.8, §2.7).
- [ ] `WfmContext` is the single read point, and its KDoc states the C5 obligation.
- [ ] The socket-side obligation is carried by C5/R5.2, not by a C1 test — the cross-channel
      acceptance bullet was dropped from `SPEC.md` (Decision 7). `WfmContext`'s KDoc is the only
      thing pointing C5 at it, so it has to say so plainly.

**Verification:**
- [ ] `mtest --tests '*CrossplayHeaderTest'`
- [ ] `mbuild` green
- [ ] Manual: startup fails with a clear message when `wfm.crossplay` is unset

**Dependencies:** T3
**Files likely touched:** `.../wfm/WfmConfig.kt`, `.../wfm/WfmContext.kt`,
`.../wfm/WfmRateLimitInterceptor.kt`, `market/src/main/resources/application.yaml`,
`market/src/test/kotlin/.../wfm/CrossplayHeaderTest.kt`
**Estimated scope:** S

---

## Checkpoint B — failure modes and context
- [ ] `mbuild` green; tests order-independent
- [ ] Each of R1.3, R1.4, R1.7, R1.8 has a named test asserting it
- [ ] Three of the spec's four C1 acceptance bullets pass; the fourth needs T8
- [ ] Review with human before Phase 3

---

## Phase 3 — The second channel

### Task 7: v1 legacy client and the `payload`/`include` envelope

**Description:** A second `RestClient` bean on `baseUrlLegacy` sharing the same interceptor stack,
with snake_case binding and the v1 `payload`/`include` envelope (R1.6). Unblocks C7 and C8; nothing
in C1 calls a v1 route yet, so this task proves the shape against a recorded fixture.

**Acceptance criteria:**
- [ ] A recorded `/v1/items/{slug}/statistics` fixture (captured per `docs/v1-statistics.md`)
      deserializes through the v1 envelope with snake_case fields.
- [ ] v2 camelCase binding is unaffected — no global Jackson naming strategy is introduced (§7).
- [ ] An `/auctions/search` URI draws from the `contract-search` bucket while
      `/items/{slug}/statistics` draws from `public`, asserted in both directions (Decision 1,
      §2.5).
- [ ] The T3 no-bypass test covers the new bean without being modified.

**Verification:**
- [ ] `mtest --tests '*WfmLegacyClientTest' --tests '*RateLimitWiringTest'`
- [ ] `mbuild` green

**Dependencies:** T3, T5
**Files likely touched:** `.../wfm/WfmConfig.kt`, `.../wfm/WfmLegacyClient.kt`,
`.../wfm/WfmLegacyModels.kt`, `market/src/test/resources/fixtures/v1-statistics.json`,
`market/src/test/kotlin/.../wfm/WfmLegacyClientTest.kt`
**Estimated scope:** M

---

## Checkpoint C — both channels
- [ ] `mbuild` green
- [ ] Both API versions run through one limiter on the correct buckets
- [ ] `bruno-run` re-verifies the live v1 contract after the DTO addition (§8, manual, never CI)
- [ ] Review with human before Phase 4

---

## Phase 4 — Proof at runtime

### Task 8: Per-bucket req/s metrics and the 1h live run

**Description:** Meter each bucket (R12.1) through actuator, then run the service for an hour to
satisfy C1's live acceptance bullet. `spring-boot-starter-actuator` is approved (Decision 6); the
`nix/deps.json` regeneration lands in the same commit.

**Acceptance criteria:**
- [ ] Per bucket: requests issued, retries, and time spent waiting for a token are exposed as
      metrics (R12.1).
- [ ] Actuator is the only HTTP surface added; no data endpoints (R12.5).
- [ ] A 1h live run records sustained req/s ≤ configured and **zero** `429`/`509` — the spec's
      fourth acceptance bullet — with the numbers written into the Results section of
      `tasks/plan.md`.
- [ ] `nix build .#market` succeeds after the lock regeneration.

**Verification:**
- [ ] `mbuild` green
- [ ] `mrun`, then read `/actuator/metrics/...` for each bucket
- [ ] `$(nix build --no-link --print-out-paths .#market.mitmCache.updateScript)` from the repo root,
      then `nix build .#market`

**Dependencies:** T4, T7
**Files likely touched:** `market/build.gradle.kts`, `nix/deps.json`,
`.../wfm/WfmRateLimitInterceptor.kt`, `market/src/main/resources/application.yaml`
**Estimated scope:** S

---

## Checkpoint D — C1 complete
- [ ] All four of the spec's C1 acceptance bullets pass
- [ ] R1.1–R1.8 each map to a named passing test or a recorded live measurement
- [ ] `mbuild` green, tests order-independent, `nix build .#market` succeeds
- [ ] `SPEC.md` status note updated — C1 is broken into tasks and built
- [ ] C3 may begin (C2 is parallel and independent)
