# ADR-0014: A 10–50/day alert budget is a requirement, not a preference

**Date**: 2026-09-09
**Status**: accepted
**Deciders**: project author (spec review)

## Context

The success criterion is a push notification arriving on a phone with enough information to act. A
watchdog that fires 400 times a day fails that criterion just as completely as one that never
fires — the operator mutes it, and every subsequent alert is lost. Without a stated ceiling,
cooldowns and thresholds are tuned by feel, and there is no definition of "too noisy" to test
against.

Baseline rules make this sharper: they fire on statistical unusualness, which is the class of rule
that floods.

## Decision

The alert budget is **10–50 notifications/day across all watches**. Cooldowns and thresholds are
tuned against that ceiling, and sustained breach is a **defect**, not a configuration preference.
Baseline rules share this budget rather than adding to it, and are the first candidates for
tightening if it is breached.

## Alternatives Considered

### Alternative 1: No stated budget, tune by feel
- **Pros**: No number to defend or revisit.
- **Cons**: "Too noisy" becomes a matter of opinion, and there is nothing to backtest against.
- **Why not**: Makes signal quality unmeasurable, and leaves baseline-rule calibration with no
  target at all.

### Alternative 2: A per-watch rate cap instead of a global one
- **Pros**: One misbehaving watch cannot starve the others.
- **Cons**: N watches × a per-watch cap is still unbounded in aggregate, which is what the phone
  actually experiences.
- **Why not**: The constraint being protected is the operator's attention, which is global.

## Consequences

### Positive
- Cooldown and threshold choices become testable: a 72h live run either sits inside the budget or
  does not, and a backtest over recorded events either fits or does not.
- Gives baseline-rule calibration a concrete target instead of an aspiration.

### Negative
- Requires notification counting to be instrumented before the budget can be enforced — the
  measurement is a dependency, not an afterthought.

### Risks
- This is currently the only number constraining signal quality. If the ceiling turns out to be
  wrong, most of the threshold rules and all of the baseline rules get retuned.
