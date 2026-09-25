# Tasks: correct the foundation, then order-book ingest (C4)

Plan: `tasks/plan.md`. Requirement ids refer to `SPEC.md` §4. Each task lists its acceptance
checks and nothing else; how a check is met lives in the code and its tests.

## Foundation

### T1: The limiter paces actual starts
- [x] Calls queued behind a slow one start a full turn apart once the slot frees.
- [x] A queued call is not counted in `wfm.requests` until it starts.
- [x] `WfmRateLimiterTest.calls queued behind a slow one…` fails against the old order (turn,
      then slot).

### T2: The WFM transport covers the WFM clients only
- [ ] The v2 and v1 clients carry the rate-limit and context interceptors and the WFM error
      handler.
- [ ] A client built from the context's `RestClient.Builder` carries none of them.
- [ ] Any other `RestClient` bean fails `RateLimitWiringTest` unless it is named there as
      non-WFM, so a new WFM client still cannot bypass the limiter.

### T3: The detail sweep is off by default
- [ ] With no `wfm.sync.item-details.enabled`, no `ItemDetailSync` bean exists.
- [ ] Setting it to `true` restores the sweep unchanged.

### T4: The spec matches what the service can observe
- [ ] R4.2 says every detected state change appends, not every observation.
- [ ] C4's rules for reappearance, stale books, socket events during a book fetch, empty books
      and failed fetches are requirements, each with an acceptance check.
- [ ] Quote acceptance counts rows per reconciled market, including markets that became empty.
- [ ] R5.4 and §2.1 describe gap-fill as best effort: `/recent` holds at most 500 orders from
      online users, and the socket carries new orders only.
- [ ] §8 no longer requires a Spring context for parsing and diff tests.
- [ ] The build order is the review's sequence. C6 is one instance on a fixed cadence over
      watched items. ADR-0006 separates freshness (scheduler) from the ceiling (limiter).
- [ ] §10 records the review's open questions for C9a and C10; §11 records the untested backup.

## C4 — order-book ingest

### T5: Orders parse from the captures
- [ ] `/v2/orders/item/{slug}` and `/v2/orders/recent` bind into one `Order` model, without
      Spring or a database.
- [ ] A dimension absent from an order binds as null; `rank: 0` and `amberStars: 0` bind as 0.
- [ ] The model binds nothing about the owner beyond platform and online status (SPEC §9).
- [ ] The committed fixtures carry no trader names, slugs, ids or avatars.

### T6: A full book is reconciled against stored state
- [ ] Book A then A: no new events.
- [ ] A then A' with one price change: exactly one `price_changed`, carrying the previous price.
- [ ] A lot-size change alone is a `price_changed`.
- [ ] A then A'' missing an order: exactly one `vanished`, carrying the last known values.
- [ ] An order that returns after vanishing: `appeared`, carrying its last known values as the
      previous ones.
- [ ] An order that moves market or side: `vanished` from the old, `appeared` on the new.
- [ ] A book no newer than the last one reconciled for its item: no events, no quotes.
- [ ] An order first observed after a book was fetched is not vanished by that book.
- [ ] The captured serration book splits across one market per `(subtype, rank)` it contains.
- [ ] Classification is tested without Spring or a database.

### T7: A reconciled book writes one quote row per market
- [ ] One row for every market of the item, including one that became empty (counts 0, prices
      null).
- [ ] Best prices are per unit; the captured ayatan book is not crossed.
- [ ] Online measures count only owners whose status is `online` or `ingame`.
- [ ] Both rollups carry the new measures, and a database upgraded from V8 keeps every storage
      policy.

### T8: Partial observations only add orders
- [ ] An order never seen before: `appeared`, with source `ws` or `recent`.
- [ ] A known order, live or gone, records nothing, even with different values.
- [ ] Replaying the captured `/recent` twice: events on the first pass only.
- [ ] An order for an item missing from the catalog is skipped, not fatal.

### T9: Polling one book
- [ ] A successful fetch is reconciled with source `book`.
- [ ] A failed fetch writes no event, quote or order state.

## Checkpoint
- [ ] `mbuild` green under several seeds
- [ ] `SPEC.md` status and `CHANGELOG.md` updated
- [ ] Review with human
