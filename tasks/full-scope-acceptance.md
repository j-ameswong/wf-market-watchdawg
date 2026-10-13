# Full-scope delivery evidence — 2026-10-13

Scope follows the author's delivery agreement in SPEC. Tests use recorded fixtures, loopback
peers and injected observation times; no automated test contacts the live API.

## Implemented and reviewed

| Capability | Evidence and review corrections |
| --- | --- |
| C5 | Local RFC WebSocket handshake, explicit matching REST/socket context, fragmented/replayed messages, deadlines, reconnect/gapfill, jitter cap/reset, stop/drain/restart and stale callbacks. Mill and Gauss reviews corrected locking/lifecycle races; Locke independently reviewed final lifecycle changes. |
| C7 | Captured statistics replay, exact dimensions/crossplay, decimals/nulls, mutable upstream identity, pending throttle continuation and metadata requirements. Galileo review corrections incorporated. |
| C9a | Crossing/spread entry/reentry, cooldown deferral, changed definitions, empty/offline books, precision and rollback. Halley review corrections incorporated. |
| C8 | Three captured auction variants, normalized raw attributes and inverted recoil benefit, transactions/outbox, explicit missing-vs-closed evidence, durable verification queue, throttle continuation and fair failed-ID backoff. Feynman fixes independently re-reviewed by Helmholtz. |
| C9b | Train/holdout time batches, stale/insufficient history gates, latest compatible lifetime episode, prospective all-rule candidate replay with recorded ordering/batches and complete UTC days. Source coverage, configuration/calibration generation and delivery-failure resets prevent missing history from certifying a budget fit. Ramanujan review corrections incorporated. |
| C12 | Committed event throughput with rollback/savepoint checks; UTC-day signal-state and finalized-notification gauges survive process restart. Independent review by fresh read-only Codex session `01a1368a-759b-7843-b515-0e43dcfcc9d6` completed. |
| Operations | Single-instance tailnet deployment, secret-free repository/config templates, atomic private backups, common recovery database target, isolated restore script. James corrections independently reviewed by Helmholtz. |

## Live acceptance on homelab

- One application instance with persistent TimescaleDB and existing tailnet ntfy server.
- Real `source=ws` signal `acceptance-citrine_prime_blueprint` received by a streaming ntfy
  listener **0.969 seconds after service observation**. Exactly one signal existed for that
  temporary watch, avoiding ambiguous correlation. Temporary permissive watches were restored.
  This measures service latency, not upstream creation-to-WebSocket transport latency.
- Separate direct ntfy transport publication/readback: 0.023 seconds (not used as application proof).
- Severed only the application's established WebSocket TCP connection via its container network
  namespace. Connected gauge returned to 1, successful gap fills increased from 1 to 2, and the
  JVM PID and container restart count were unchanged. No host/Tailscale connection was interrupted.
- Runtime throttle counter was zero for both 429 and 509. One sample showed 19 outbound requests,
  800 socket frames and five poll rounds with total lateness 0.001396444 seconds. These are an
  observation sample, not a 72-hour availability claim. Long real-time waits were waived.
- Custom-format backup restored with Timescale pre/post hooks into an isolated scratch database:
  2,075 order events, 8,102 statistics rows and 532 auctions readable; scratch database dropped.
- Accelerated tests cover three UTC days using book plus socket admissions and next-day deliveries,
  midnight, concurrent budget contention, retry caps, recovery and deterministic replay.

## Evidence limits

Synthetic histories validate mechanics, never production thresholds. Statistics are retrospective
API snapshots; old unrecorded revisions cannot be reconstructed. The live instance has no qualified
long-lived event history yet. Baseline delivery remains gated until real per-market calibration and
complete shared observation coverage qualify it. Collection gaps and changed settings restart that
coverage. This is intentional R9b.3 behavior, not a guessed baseline.

A manual lookup of the accepted order's upstream `createdAt` returned a time about four seconds
later than the service's observation timestamp, indicating cross-clock skew. Therefore upstream
creation-to-listener latency cannot be reported reliably; service observation-to-receipt is the
measured interval. No hour-long seller-platform diagnostic or 72-hour soak
is claimed; captured platform fields and live throughput remain diagnostic only.

See deploy/OPERATIONS.md for deployment, watches, metrics and recovery commands.

## Final verification

- `nix develop --command bash -c 'cd market && ./gradlew spotlessApply build -PtestSeed=20261014'`
  passed: **250 tests, zero failures/errors/skips**. Earlier full build seed `20261013` and targeted
  randomized feature runs also passed; final migration compatibility is included in the final run.
- `git diff --check`, deployment shell syntax, and `python3 deploy/check-backup.py` passed.
- Deployed jar SHA-256: `af8db0ce5dea60c2a2c229333af57bdcba36489d84f1f01136e0c8ffe260e99a`.

Final artifact deployment applied migrations 16 and 17 successfully. Health was `UP`, socket
connected was 1, throttle count was zero, and durable gauges recovered four existing admissions
and four finalized notifications after restart. A second backup at `2026-10-13T18:46:02Z` restored
successfully with 4,169 events, 8,102 statistics rows and 532 auctions; the scratch database was removed.

The final artifact was then rechecked with the normal baseline/contract settings retained and
12 temporary permissive item watches added. A unique `source=ws` signal for
`finalacceptance-arcane_persistence` reached the streaming listener in **0.196 seconds**.
The original watches were restored automatically; final health and socket readiness were checked.
