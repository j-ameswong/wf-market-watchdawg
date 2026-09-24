# ADR-0020: Storage policies are declared in one repeatable migration

**Date**: 2026-09-24
**Status**: proposed
**Deciders**: delegated to the implementer during C2 task planning (Decision 3 in
`tasks/plan.md`); awaiting review at C2's final checkpoint

## Context

R2.3 wants the event log "compressed beyond a configurable age" and R2.4 wants raw quotes kept
for "a bounded window". TimescaleDB applies both through policies created by SQL. R2.5 requires
every migration to apply identically through the app (classpath) and the `mflyway` CLI
(filesystem), which share one history table. SPEC §9 makes changing retention or compression
policy an ask-first change.

## Decision

Every compression, retention and continuous-aggregate refresh policy lives in
`R__storage_policies.sql`, a Flyway repeatable migration. Each policy is removed and re-added, so
the file is safe to re-run whole. Flyway re-applies it whenever its content changes, through
either path. Changing a value means editing that one reviewed file. Fact tables that later
capabilities add declare their policies there too.

## Alternatives Considered

### Alternative 1: Flyway placeholders fed from `application.yaml`
- **Pros**: Values sit beside the rest of the configuration and can be overridden per environment.
- **Cons**: The CLI does not read `application.yaml`, so `mflyway` would need the same values
  supplied separately.
- **Why not**: Two sources for one value can drift, on exactly the surface R2.5 says must agree.

### Alternative 2: Policies in the versioned migration that creates each table
- **Pros**: Each table's storage behaviour sits next to its definition.
- **Cons**: Changing a value needs a new versioned migration that alters the job, and the current
  value is then spread across several files.
- **Why not**: The current policy would no longer be readable in one place.

## Consequences

### Positive
- One file answers "what is kept, for how long, and when is it compressed".
- A policy change is a reviewable diff to that file, which suits §9's ask-first rule.

### Negative
- The values cannot be overridden per environment without editing the file.
- Every re-run removes and re-adds the policies, which gives their jobs new ids.

### Risks
- A refresh window that reaches past raw retention silently deletes rolled-up history.
  `QuoteStorageTest` fails if any rollup's window does, including rollups added later.
