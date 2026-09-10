# ADR-0017: Tests never reach the live API

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

Every test is a `@SpringBootTest` booting a Testcontainers Postgres, and the application contains
scheduled components that call warframe.market on a timer. A test suite that starts those
schedulers spends real rate budget against a community service — on every developer machine and
every CI run — which conflicts directly with
[ADR-0004](0004-rate-limit-discipline-is-a-hard-boundary.md).

This could have been written as an inviolable project boundary or as a scoped requirement of the
storage/test-harness capability. The work needed is identical either way.

## Decision

No test may reach the live warframe.market API. It is recorded as a **requirement of the test
harness capability** (with acceptance criteria and a verifying test) rather than as a boundary
rule. Scheduled components are disabled by default under test, and HTTP is exercised through
`MockRestServiceServer` bound to `RestClient.Builder`.

## Alternatives Considered

### Alternative 1: Record it only as a project boundary rule
- **Pros**: Frames it as inviolable rather than as a task someone might not get to.
- **Cons**: A rule with no test is a rule that gets broken silently.
- **Why not**: Recording it as scoped work means a test asserts the context starts with scheduling
  off and records zero outbound HTTP. The rule is still listed under project boundaries; this
  decision is about where the *enforcement* lives.

### Alternative 2: Allow live calls in a tagged, opt-in test group
- **Pros**: Contract drift against the real API gets caught automatically.
- **Cons**: Opt-in groups get enabled in CI eventually, and then every run spends budget.
- **Why not**: Contract re-verification is handled by replaying the `bruno` collection manually
  after any DTO change — never in CI.

## Consequences

### Positive
- The suite is runnable offline and spends no rate budget.
- Fixtures captured from the `bruno` collection match reality, so mocked responses are not
  invented.

### Negative
- DTO drift against the live API is caught by a manual step, not by the suite.

### Risks
- Identical annotation sets share one Spring context **and one container**, so tests must be
  order-independent and reset shared state between runs. An order-dependent test passes alone and
  fails in a suite, or vice versa.
