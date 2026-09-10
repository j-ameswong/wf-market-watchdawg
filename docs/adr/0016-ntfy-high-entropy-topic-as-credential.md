# ADR-0016: ntfy.sh with a high-entropy topic treated as a bearer credential

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Notifications need to reach a phone. Public `ntfy.sh` requires no account and no server, but it
has no access control beyond the topic name: anyone who knows a topic can subscribe to it and read
every notification published there. The notifications carry item names, prices and market
positions the operator is acting on.

## Decision

Deliver to public `ntfy.sh` using a **high-entropy topic name**, treated as a bearer credential. It
is supplied by environment or external config, never committed and never logged. Watch YAML
references a topic by *logical name* only; the mapping to a real topic resolves at runtime. The
`Notifier` abstraction stays host-agnostic.

## Alternatives Considered

### Alternative 1: Self-host ntfy
- **Pros**: Real access control; the topic name stops being a secret.
- **Cons**: A server to run, expose, TLS-terminate and keep patched, for one user.
- **Why not**: Disproportionate for the current scale. Kept as a future option — the
  host-agnostic `Notifier` means it is a drop-in later, without reworking the rule engine.

### Alternative 2: A memorable topic name on public ntfy.sh
- **Pros**: Easy to type into a phone once.
- **Cons**: Guessable, therefore public.
- **Why not**: The topic *is* the access control. A guessable one has none.

## Consequences

### Positive
- Zero infrastructure: no account, no server, no certificate.
- Swapping to a self-hosted instance later changes configuration, not rule-engine code.

### Negative
- A secret now exists in a project that otherwise has none, with the handling discipline that
  implies: not in the watch YAML, not in `application.yaml`, not in a log line.

### Risks
- Leaking the topic name exposes every past and future notification to anyone who finds it, with
  no revocation short of changing the topic. Guarded by asserting that `git grep` over the repo
  finds no real topic name and that DEBUG-level logs contain none.
