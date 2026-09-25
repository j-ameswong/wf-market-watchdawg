# Plan: correct the foundation, then order-book ingest (C4)

> Source: the 2026-09-25 project review, and the author's answers to it the same day. Scope: the
> review's first deliverable, plus C4 without a scheduler. Task bodies and acceptance checks are in
> `tasks/todo.md`.

## Scope

1. **The limiter paces actual starts.** A caller takes its connection slot before its turn, and
   the request is metered when it starts.
2. **The WFM transport covers the WFM clients only.** Pacing, context headers and the error
   boundary are applied to the v2 and v1 clients by name, not to every `RestClient.Builder`, so
   C10's ntfy client will not inherit them.
3. **The detail sweep is off by default.** No rule or query reads its fields yet.
4. **The spec says what the service can actually observe, and in what order it is built.**
   These changes are listed under T4 in `tasks/todo.md`.
5. **C4 ingest.** A full book is reconciled against stored state; a partial observation (`ws`,
   `recent`) can only add orders. Quotes carry the minimal contract. Nothing calls either path on
   a schedule yet, so C4 spends no request budget.

## Out of scope

The poll loop, the WebSocket, rules and notifications (the next milestone). The Kafka dependency
removal waits for a planned dependency update, because it needs `nix/deps.json` regenerated on a
machine with Nix.

## Decisions (author, 2026-09-25)

- Best prices are **per unit** (`platinum / perTrade`) and **numeric**. The ayatan capture shows
  `platinum` prices a lot of `perTrade` units.
- `order_event` gains the order's **side** and **lot size**, so the log alone reconstructs each
  market's visible book at poll resolution.
- The quote contract is the minimum: best bid and ask over all visible orders and over online
  owners only, order counts, and online counts. Depth and total quantities wait for a consumer.

## Captures (2026-09-25)

`/v2/orders/item/{serration,khra,ayatan_anasa_sculpture}` and `/v2/orders/recent`, crossplay on.
They answered three questions before any field was committed to:

- **Requiem mods trade by rank.** `khra` orders carry `rank` 0, 2 and 3 and never `charges`
  (SPEC §10 Q1).
- **`perTrade` is a lot size.** Ayatan sells at `perTrade` 6 have a median of 60 against 10 at 1.
- **Every order carries its dimensions explicitly.** Ayatan orders carry both star counts,
  including `0`, and serration orders carry `subtype` and `rank`.

The committed fixtures are those captures with trader identity replaced (SPEC §9); see
`bruno/scrub-orders.mjs`.
