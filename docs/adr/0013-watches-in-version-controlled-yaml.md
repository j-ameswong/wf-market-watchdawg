# ADR-0013: Watches in version-controlled YAML with a keyed rule registry

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Watches define what the service alerts on: which market, which rule family, which thresholds. They
are the part of the system most likely to be edited, tuned and reverted. There is one operator, so
there is no multi-user editing story to support and no reason for the definitions to be
runtime-mutable.

## Decision

Watches are declared in a version-controlled YAML file loaded at startup, and invalid config fails
startup loudly, naming the offending entry. Rules are a **keyed strategy registry** — three
families: underpriced listing (order-scoped, socket-driven), best price crosses (book-scoped), and
spread above margin (book-scoped) — deliberately not an expression language.

## Alternatives Considered

### Alternative 1: Watches as database rows
- **Pros**: Editable at runtime without a restart or deploy.
- **Cons**: No diff, no review, no history, no revert.
- **Why not**: Threshold tuning is exactly the activity that benefits from `git log` and `git
  revert`. A row change leaves no record of what the threshold used to be or why it moved.

### Alternative 2: An expression language for rule conditions
- **Pros**: Arbitrary new rules without code changes.
- **Cons**: An evaluator, a grammar, a sandbox, and a class of runtime errors that config
  validation cannot catch.
- **Why not**: Three rule families are known and none of them is close to needing arbitrary
  expressions. A registry of named strategies is validated at startup, in full.

## Consequences

### Positive
- Every threshold change is reviewable in git alongside the code it drives.
- A watch naming a nonexistent item slug fails startup with that slug in the message, rather than
  silently never firing.

### Negative
- Tuning a threshold requires a restart.

### Risks
- The ntfy topic is a bearer credential and must **not** appear in this file. Watch YAML references
  a topic by *logical name* only; the mapping resolves at runtime. See
  [ADR-0016](0016-ntfy-high-entropy-topic-as-credential.md).
