# Operator dashboard acceptance — 2026-10-13

The author requested a compact dark frontend hosted on homelab, with supported configuration
changes applied immediately. ADR-0024 records the new private HTTP surface and runtime ownership.

## Delivered

- Responsive Watches, Statistics and Settings screens, packaged directly in the existing jar.
- Item watches for all five rules; riven/lich/sister forms with weapon, attribute, damage,
  ephemera, price, priority, cooldown and logical-topic selectors. Create/edit/pause/resume/delete
  use the existing alert and collection paths, not a separate frontend model.
- Versioned, validated configuration in PostgreSQL; concurrent tabs cannot silently overwrite
  one another. YAML bootstraps until the first save. Pending notifications and history survive
  watch removal. Collector cadence, credentials and observer context remain explicit host settings.
- Exact-market, closed/live, side, resolution and crossplay statistics with price/volume charts,
  keyboard bucket inspection, accessible data tables, freshness and baseline evidence. Sparse
  buckets remain gaps; isolated points remain visible. Bounded contract listing inspection.
- Same-origin JSON mutation checks, hostname allowlist, browser security headers and indexed,
  bounded warehouse reads. No page read reaches upstream, and topic credentials remain server-side.

## Independent reviews

Two independent agents reviewed runtime configuration and the read API/frontend. Findings fixed:

- Encoded-route boundary bypass; mixed revisions in contract target selection; failed statistics
  retries starving newly added watches; saved manifest-wide collection ignored during initialization.
- Stale form lookups and save completions affecting a different editor; delayed refreshes discarding
  drafts; watch toggles pairing a stale document with a new revision; delayed saves moving local
  revision backward; old population evidence surviving a failed filter change.
- Isolated chart observations hidden beyond 100 points; missing auction ordering index.

Final targeted rechecks reported no remaining findings. A review identified a restart-test coverage
gap; the overview now reports actual resolved watch slugs, and restart verification compares them
against the saved enabled definitions rather than comparing two counts from the same document.

## Automated and visual verification

- Full Gradle build: **263 tests, zero failures/errors/skips**, randomized seed `20261019`.
- The final live-state overview refinement passed all 12 operator tests with seed `20261020`.
- **11 Playwright checks** against the packaged application and disposable TimescaleDB: watch
  lifecycle, contract filters, validation/conflicts, preferences, series/population filtering,
  keyboard chart inspection, empty/outage/retry behavior, late requests, sparse charts, desktop
  and mobile layouts. All collectors were disabled; synthetic facts tested mechanics only.
- A real JVM restart preserved the exact configuration and restored its resolved live item targets.
- Prettier checks, shell syntax, staged whitespace checks and deployment backup-script checks passed.
- Desktop statistics/watch and 390px phone screenshots inspected; corrected mobile horizontal
  page overflow and inherited all-caps chart styling. Tables retain their own horizontal scrolling.

## Homelab delivery

- Installed at `http://homelab:8081`, also reachable through the configured tailnet IP.
- Jar SHA-256: `bd6023637feb6708bc21bbf5f39591597da306d1b2aaa4e17398b7bb1e5baa2f`.
- Migrations V18 and V19 applied successfully. Health is UP; socket connected; existing five
  watches retained, with resolved item targets `serration` and `frost_prime_set`; outbox pending 0
  at the verification sample. All three collectors and notification delivery remained enabled.
- Read-only live browser smoke loaded five watches, 28 Frost Prime Set daily statistics buckets,
  Settings and the phone layout, with zero page errors and no horizontal document overflow.
- Production configuration was not changed by the browser mutation tests; those ran only against
  disposable databases. The live service still uses its YAML until the operator's first UI save.
- Pre-update backup: `backups/watchdawg-20261013T201947Z-qYw1mov9.dump` on homelab. The prior jar
  and Compose file are retained there as `market.jar.pre-dashboard` and `compose.yaml.pre-dashboard`.

Statistical calibration still requires real evidence. The dashboard reports readiness; it does
not bypass gates or turn disappeared listings into confirmed sales. New collection targets join
the next scheduled collector round; an in-flight observation can finish using its previous selection.
