# Architecture Decision Records

Why the design is shaped the way it is. `SPEC.md` describes *what* the system does; these records
hold the context, rejected alternatives and trade-offs behind it.

ADRs 0001–0018 were backfilled on 2026-09-10 from the decision log in `SPEC.md`. The dates below
are the original decision dates, not the dates the records were written.

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-pc-observer-context-with-crossplay.md) | PC observer context with crossplay enabled | accepted | 2026-09-09 |
| [0002](0002-crossplay-single-global-setting.md) | Crossplay is one global setting across REST and WebSocket | accepted | 2026-09-09 |
| [0003](0003-market-is-a-mutually-tradable-pool.md) | A market is a mutually-tradable pool, not a seller-platform partition | accepted | 2026-09-09 |
| [0004](0004-rate-limit-discipline-is-a-hard-boundary.md) | Rate-limit compliance is a hard boundary | accepted | 2026-09-09 |
| [0005](0005-rate-buckets-keyed-by-route-class.md) | Rate-limit buckets are keyed by route class, not API version | accepted | 2026-09-09 |
| [0006](0006-poll-scheduler-owns-budget-compliance.md) | The poll scheduler owns rate-budget compliance | accepted | 2026-09-09 |
| [0007](0007-timescaledb-with-indefinite-event-log.md) | TimescaleDB with an indefinite event log and rollups | accepted | 2026-09-09 |
| [0008](0008-vanished-only-from-full-book-polls.md) | `vanished` is inferred only from full book polls | accepted | 2026-09-09 |
| [0009](0009-detection-on-order-book-events.md) | Move detection runs on order-book events, not the trade series | accepted | 2026-09-09 |
| [0010](0010-item-stat-market-keyed-two-series.md) | `item_stat` is market-keyed, two-series, and crossplay-identified | accepted | 2026-09-09 |
| [0011](0011-no-query-api-postgres-is-the-read-surface.md) | No query API; Postgres is the read surface | accepted | 2026-09-09 |
| [0012](0012-kafka-stays-unwired.md) | Kafka stays unwired | accepted | 2026-09-09 |
| [0013](0013-watches-in-version-controlled-yaml.md) | Watches in version-controlled YAML with a keyed rule registry | accepted | 2026-09-09 |
| [0014](0014-alert-budget-is-a-requirement.md) | A 10–50/day alert budget is a requirement, not a preference | accepted | 2026-09-09 |
| [0015](0015-at-least-once-delivery-through-an-outbox.md) | At-least-once notification delivery through an outbox | accepted | 2026-09-09 |
| [0016](0016-ntfy-high-entropy-topic-as-credential.md) | ntfy.sh with a high-entropy topic treated as a bearer credential | accepted | 2026-09-09 |
| [0017](0017-tests-never-reach-the-live-api.md) | Tests never reach the live API | accepted | 2026-09-09 |
| [0018](0018-contracts-sequenced-after-the-item-spine.md) | Contracts and auctions are sequenced after the item spine | accepted | 2026-09-09 |

Decisions scoped to a single capability's implementation are recorded in that capability's plan —
see `tasks/plan.md` for C1. A decision graduates to an ADR here when it constrains the design
beyond the capability that raised it.

`template.md` is a blank record for manual use.
