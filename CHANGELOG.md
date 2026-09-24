# Changelog

Project-level changes. Implementation detail lives in the capability task logs under `tasks/`, and
design rationale in [`docs/adr/`](docs/adr/README.md).

## [Unreleased]

### Added

- **C1 — API access.** Every outbound call to warframe.market goes through one paced,
  observable transport:
  - A two-bucket rate limiter keyed by route class (`public` 2 req/s, `contract-search`
    12 req/min) with a global concurrency cap of 2, installed on every `RestClient`.
  - One retry on `429`/`509`, honouring `Retry-After` in both RFC 9110 forms up to
    `wfm.limits.max-retry-after`. A `509` permanently narrows the concurrency cap.
  - A typed `WfmException` hierarchy. Non-JSON and HTML error bodies never reach Jackson.
  - `Platform`, `Crossplay` and `User-Agent` stamped on every request from one setting.
  - A v1 client for `/items/{slug}/statistics`.
  - Actuator, exposing `health` and `metrics` only, with per-bucket rate-limit meters.
- Item catalog sync: an hourly, version-hash-gated refresh of `/v2/items`.
- Nix flake with a dev shell and a hermetic jar build.
- `SPEC.md`, ADRs, the API reference under `docs/`, and the Bruno collection.

### Changed

- `wfm.requests-per-second` is replaced by `wfm.limits.*`.
- `wfm.platform` and `wfm.crossplay` have no defaults; startup fails if either is unset.
- Kotlin sources use 4-space indentation, enforced by Spotless + ktlint as part of `build`.
- The dev Postgres listens on port 5432.

### Fixed

- The sync scheduler could tick inside a test run and call the live API.
