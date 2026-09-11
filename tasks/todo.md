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
- [x] Every request through the v2 client acquires from the `public` bucket before the connection
      opens, and releases on completion **including on exception**.
- [x] A context test asserts every `RestClient` bean carries the limiter interceptor — this is the
      standing no-bypass guard for R1.1, and it covers beans added later for free.
- [x] `WfmClient.getVersions()` and `getItems()` still pass against `MockRestServiceServer`, with
      no change to their signatures.

**Verification:**
- [x] `mtest --tests '*WfmClientTest' --tests '*RateLimitWiringTest'`
- [x] `mbuild` green
- [x] Manual: `mrun` completes one catalog sync with no behavioral change — one live
      `/v2/versions` call, then `items unchanged (MjAyNi0wOC0yNVQwMToxMzoyMw==)`

**Notes:** the interceptor is registered through a `RestClientCustomizer`, not added to
`wfmRestClient` directly, so every `RestClient.Builder` the context hands out is paced and T7's v1
bean inherits pacing without a wiring step. `WfmClientTest` binds `MockRestServiceServer` to the
**real** bean via `RestClient.mutate()`, so only the request factory is faked. `bucketFor` is
implemented in full here (both directions asserted) rather than hardcoding `PUBLIC` for T7 to
revisit.

**Dependencies:** T2
**Files likely touched:** `market/src/main/kotlin/com/watchdawg/market/wfm/WfmConfig.kt`,
`.../wfm/WfmRateLimitInterceptor.kt`, `market/src/test/kotlin/.../wfm/WfmClientTest.kt`,
`.../wfm/RateLimitWiringTest.kt`
**Estimated scope:** M

---

## Checkpoint A — governed transport
- [x] `mbuild` green; all tests pass in any order (R2.7) — 19 tests, also green with `WfmClientTest`
      run in isolation and as a `wfm`-only subset
- [x] A paced v2 call works end to end and demonstrably cannot be bypassed — removing the
      customizer fails both `RateLimitWiringTest` and the pacing assertion (0.0016s vs ≥0.5s)
- [ ] Review with human before Phase 2

**Resolved during this checkpoint:** `@EnableScheduling` is active in `@SpringBootTest` and
`wfm.sync.initial-delay` was 30s, so once the suite outgrew that window the scheduler would have
ticked mid-test and called the **live** API (R2.6, §9). Fixed by a `systemProperty` on the Gradle
test task, guarded by `MarketApplicationTests.the sync scheduler cannot fire during a test run`.
Not a `src/test/resources/application.yaml` — that file would shadow the main one by classpath
name, dropping `base-url` and `limits` from every test and leaving `WfmPropertiesTest` asserting a
test copy of the config instead of the real one.

---

## Phase 2 — Failing correctly, and one context

### Task 4: `429`/`509` → bounded retry that honors `Retry-After` and consumes budget

**Description:** Replace the current straight-to-throw status handler. `429` (rate) and `509`
(concurrency) become distinct typed failures. One retry waits out `Retry-After`, then **re-acquires
from the limiter** before reissuing, so retries spend budget rather than bypass it (R1.3, R1.4).

**Acceptance criteria:**
- [x] A `429` fixture with `Retry-After: 2` yields exactly one retry, no sooner than 2s, then
      success — the spec's second acceptance bullet.
- [x] A second consecutive `429` surfaces a typed `RateLimitedException`; there is no third attempt.
- [x] `509` produces a distinct type from `429` and narrows effective concurrency rather than only
      waiting (R1.4).
- [x] `Retry-After` parses as both delta-seconds and HTTP-date; a value above
      `wfm.limits.max-retry-after` (60s) surfaces immediately instead of parking a thread
      (Decision 4).
- [x] The retry is visible to the limiter — asserted via a limiter counter, not by inspection.

**Verification:**
- [x] `mtest --tests '*WfmRetryTest' --tests '*WfmRateLimiterTest'` — 13 tests
- [x] `mbuild` green

**Notes:** the retry lives in the interceptor rather than in a `defaultStatusHandler`, because only
the interceptor sits *above* the limiter and can re-acquire a turn before reissuing; a status
handler runs after the transport has already spent its budget. Both attempts share one
`limiter.acquire` call site inside the loop, so "the retry spends budget" is structural rather than
remembered. A refusal carrying **no** `Retry-After` gets no extra cooloff — the second attempt's own
turn already spaces it by the bucket rate, so no new config was invented for the gap. `509` narrows
the connection cap by one per occurrence, floored at one and never widened again (Decision 8). Each
acceptance criterion was mutation-checked: no-op the cooloff sleep, `ATTEMPTS = 3`, drop the
`narrowConcurrency` call, drop the ceiling test, and let the retry call `execution.execute` directly
— all five fail exactly the test that claims them.

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
- [x] A `403` with a `text/plain` body surfaces a typed error, not a Jackson exception — the spec's
      third acceptance bullet.
- [x] A `502` with an HTML body does the same.
- [x] The error carries the status and an excerpt capped at 200 characters; the full body is not
      logged (§9 — no PII accumulation).
- [x] A well-formed v2 envelope with `error` populated and `data` null still throws the existing
      envelope error, unchanged.

**Verification:**
- [x] `mtest --tests '*WfmErrorBodyTest'` — 5 tests
- [x] `mbuild` green

**Notes:** the status handler is installed by the same `RestClientCustomizer` as the limiter rather
than on `wfmRestClient` directly, so T7's v1 bean — the one route that actually answers `403` in
plain text — inherits it with no wiring step. The two customizer beans merged into one
`wfmTransportCustomizer`: they make the same guarantee about the same seam, and splitting them
invites a client that gets one and not the other. Only the first 800 bytes of the body are read
(UTF-8's worst case for a 200-character cap) and whitespace is collapsed, so a Cloudflare HTML page
is one log line rather than forty. Spring's own default already throws before Jackson sees an error
body, but it throws `RestClientResponseException` with an untyped 512-character body — the work here
is the *typed* boundary C6 can branch on plus the tighter cap. Mutation-checked: drop the cap, drop
the whitespace collapse, drop the status handler.

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
- [x] Every outbound REST request carries `Crossplay` equal to `wfm.crossplay` **even when the call
      site sets its own value** — asserted by a test that attempts the override.
- [x] `WfmProperties.crossplay` and `.platform` have no Kotlin defaults; an unset value fails
      startup, so no channel can silently inherit an upstream default (R1.8, §2.7).
- [x] `WfmContext` is the single read point, and its KDoc states the C5 obligation.
- [x] The socket-side obligation is carried by C5/R5.2, not by a C1 test — the cross-channel
      acceptance bullet was dropped from `SPEC.md` (Decision 7). `WfmContext`'s KDoc is the only
      thing pointing C5 at it, so it has to say so plainly.

**Verification:**
- [x] `mtest --tests '*CrossplayHeaderTest' --tests '*RateLimitWiringTest'` — 6 tests
- [x] `mbuild` green
- [x] Manual: startup fails when `wfm.crossplay` is unset — *but see the caveat below; the message
      is Spring's, and it is only clear for the reference-typed property*

**Notes:** `User-Agent` moved into the interceptor alongside `Platform`/`Crossplay`, which the task
body did not ask for. Same argument as R1.8's: a `defaultHeader` is exactly what a call site can
override, and leaving it on `wfmRestClient` would have had T7's v1 bean ship a bare `User-Agent`
unless someone remembered to re-add it — the wiring bug the customizer exists to prevent (R1.5).
`RateLimitWiringTest` was generalized to assert both interceptors on every bean, so R1.8 gets the
same standing no-bypass guard R1.1 has, covering T7 for free. The header matcher asserts the
header's *whole* value list, because `MockRestRequestMatchers.header` tolerates extra values and
appending rather than replacing is the failure worth catching. Mutation-checked: `add` instead of
`set`, revert to `defaultHeader`s, and restore a Kotlin default.

**Caveat on the startup message:** an unset `wfm.platform` fails with `Parameter specified as
non-null is null: … parameter platform`, which names the property. An unset `wfm.crossplay` fails
with `NullPointerException: Cannot invoke "java.lang.Number.intValue()"` — Kotlin's non-null
`Boolean` is a JVM primitive, so the binder cannot attribute the failure to a name. Startup does
stop either way, which is what R1.8 needs. Binding it as `Boolean?` would buy the better message at
the cost of a type that can be `?: false`-ed at a call site, which is the wrong trade for a
requirement whose point is that it cannot be defaulted. The same applies to every `Int` in
`Limits`, so this is a property of Kotlin constructor binding, not of crossplay. Left as is; revisit
only if a `FailureAnalyzer` is ever worth its keep.

**Dependencies:** T3
**Files likely touched:** `.../wfm/WfmConfig.kt`, `.../wfm/WfmContext.kt`,
`.../wfm/WfmRateLimitInterceptor.kt`, `market/src/main/resources/application.yaml`,
`market/src/test/kotlin/.../wfm/CrossplayHeaderTest.kt`
**Estimated scope:** S

---

## Checkpoint B — failure modes and context
- [x] `mbuild` green; tests order-independent — 37 tests, also green as a `wfm`-only subset and with
      each new class run in isolation
- [x] Each of R1.3, R1.4, R1.7, R1.8 has a named test asserting it:
      - R1.3 → `WfmRetryTest.a 429 carrying Retry-After is retried once…` and
        `…the retry spends budget rather than bypassing it`
      - R1.4 → `WfmRetryTest.a 509 is a type distinct from 429 and narrows effective concurrency`,
        `WfmRateLimiterTest.narrowing concurrency gives up a slot, down to a floor of one`
      - R1.7 → `WfmErrorBodyTest` (all five)
      - R1.8 → `CrossplayHeaderTest.a request carries the configured context even when the call site
        sets its own`, plus `RateLimitWiringTest.every RestClient bean carries the transport
        interceptors`
- [x] Three of the spec's four C1 acceptance bullets pass; the fourth needs T8
      - "N sequential calls at limit L take ≥ (N−1)/L" — `WfmRateLimiterTest`, `WfmClientTest`
      - "a `429` with `Retry-After: 2` yields exactly one retry, after ≥2s, then success" —
        `WfmRetryTest`
      - "a plain-text `403` surfaces a typed error, not a Jackson exception" — `WfmErrorBodyTest`
      - the 1h live run is T8's
- [x] Review with human before Phase 3

**Open for review:** the startup message for an unset `wfm.crossplay` is Spring's primitive-binding
NPE rather than a named property — see the caveat under T6. Startup still fails; only the diagnosis
is poor.

---

## Phase 3 — The second channel

### Task 7: v1 legacy client and the `payload`/`include` envelope

**Description:** A second `RestClient` bean on `baseUrlLegacy` sharing the same interceptor stack,
with snake_case binding and the v1 `payload`/`include` envelope (R1.6). Unblocks C7 and C8; nothing
in C1 calls a v1 route yet, so this task proves the shape against a recorded fixture.

**Acceptance criteria:**
- [x] A recorded `/v1/items/{slug}/statistics` fixture (captured per `docs/v1-statistics.md`)
      deserializes through the v1 envelope with snake_case fields.
- [x] v2 camelCase binding is unaffected — no global Jackson naming strategy is introduced (§7).
- [x] An `/auctions/search` URI draws from the `contract-search` bucket while
      `/items/{slug}/statistics` draws from `public`, asserted in both directions (Decision 1,
      §2.5).
- [x] The T3 no-bypass test covers the new bean without being modified.

**Verification:**
- [x] `mtest --tests '*WfmLegacyClientTest' --tests '*RateLimitWiringTest'` — 8 tests
- [x] `mbuild` green — 43 tests

**Notes:** snake_case is scoped to the v1 client's own JSON converter rather than to the DTOs.
`@JsonNaming` per class would leave every future v1 DTO one forgotten annotation away from binding
nothing; the converter makes it a property of the channel. The mapper is
`JsonMapper.rebuild()`-ed from the context's own, so Boot's Kotlin module, `java.time` handling and
deserialization defaults carry over and only the naming changes.

A second `RestClient` bean makes injection by type ambiguous, so both clients now name their channel
with `@Qualifier` against bean-name constants on `WfmConfig`. Marking the v2 bean `@Primary` would
have been one line less and would silently hand a third client the wrong channel.

The bucket criterion was already discharged by T3's `RateLimitWiringTest`, which asserts `bucketFor`
in both directions and was *not* edited here — which is also how the fourth criterion is verified.
What that test cannot say is whether the wired v1 bean behaves that way, so
`WfmLegacyClientTest` times two real statistics calls: ≥500ms apart (paced) and <3s (not paced on
contract-search).

`include` binds as a raw `JsonNode`. It is populated only by `?include=item`, no route we call asks
for it, and the item manifest it carries duplicates what C3 takes from `/v2/items` — typing it now
would model a shape nothing reads.

**Correction to `docs/v1-statistics.md`:** its "Type caution" said an integer binding "will fail on
the first fractional value". It does not. Mutation-checking the price types showed Jackson
**silently truncates** — `min_price: 32.0` bound as `32` into an `Int` field and the test passed.
That is strictly worse than a failure: `wa_price` 45.417 would land as 45 with nothing downstream
reporting a problem. The doc and the `ClosedStat` KDoc now say so, and the fractional `wa_price`
assertion is what actually pins the decimal type.

Mutation-checked: drop the v1 converter (5 of 6 fail), leak the naming strategy onto the v2 client
(that test plus all of `WfmClientTest` fail), key buckets by `/v1` instead of route class (the
pacing test plus `RateLimitWiringTest` fail), and make `moving_avg` non-null (its own test fails).

**Dependencies:** T3, T5
**Files likely touched:** `.../wfm/WfmConfig.kt`, `.../wfm/WfmLegacyClient.kt`,
`.../wfm/WfmLegacyModels.kt`, `market/src/test/resources/fixtures/v1-statistics.json`,
`market/src/test/kotlin/.../wfm/WfmLegacyClientTest.kt`
**Estimated scope:** M

---

## Checkpoint C — both channels
- [x] `mbuild` green — 43 tests, also green as a `wfm`-only subset and with `WfmLegacyClientTest`
      run in isolation (R2.7)
- [x] Both API versions run through one limiter on the correct buckets — `RateLimitWiringTest`
      enumerates both beans and was not edited to see the new one; `WfmLegacyClientTest.a v1 call is
      paced on the public bucket` proves the wired bean, not just the predicate
- [x] `bruno-run` re-verifies the live v1 contract after the DTO addition (§8, manual, never CI) —
      37/37 requests, 37/37 assertions, 2026-09-11
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
