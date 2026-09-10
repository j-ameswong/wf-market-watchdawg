# ADR-0012: Kafka stays unwired

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Kafka starters are on the classpath with zero producers and zero consumers. Earlier design notes
reached for Kafka as the replayable append-only log the ingestion pipeline needs. The order event
log ([ADR-0007](0007-timescaledb-with-indefinite-event-log.md)) already is that log: immutable,
append-only, retained indefinitely, and replayable. Volume is ~100k events/day.

## Decision

Do not wire Kafka. Leave the starters on the classpath — they connect lazily, so they are
harmless — and use the event log as the replayable log.

## Alternatives Considered

### Alternative 1: Wire Kafka as the ingestion backbone
- **Pros**: Independent consumer groups, replay from offset, ingest and analysis scale separately.
- **Cons**: An additional broker to run, monitor and back up, for a single-operator service.
- **Why not**: There are no independent consumers and no separate scaling need. ~100k events/day
  is not a Postgres problem.

### Alternative 2: Remove the starters from the build
- **Pros**: A dependency list that reflects what is actually used.
- **Cons**: Requires a `nix/deps.json` regeneration for no functional gain, and re-adding them
  later costs the same again.
- **Why not**: They are inert. The cost of leaving them is lower than the cost of churning the
  dependency lock.

## Consequences

### Positive
- One fewer piece of infrastructure to operate; the durability story is entirely Postgres.

### Negative
- The dependency list overstates what the service uses, which can mislead a reader into thinking
  events are published somewhere.

### Risks
- Revisit when independent consumers or separate ingest/analysis scaling actually exist — that is
  when Kafka earns its place, not before.
