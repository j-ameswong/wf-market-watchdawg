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
- [x] The v2 and v1 clients carry the rate-limit and context interceptors and the WFM error
      handler.
- [x] A client built from the context's `RestClient.Builder` carries none of them.
- [x] Any other `RestClient` bean fails `RateLimitWiringTest` unless it is named there as
      non-WFM, so a new WFM client still cannot bypass the limiter.

### T3: The detail sweep is off by default
- [x] With no `wfm.sync.item-details.enabled`, no `ItemDetailSync` bean exists.
- [x] Setting it to `true` restores the sweep unchanged.

### T4: The spec matches what the service can observe
- [x] R4.2 says every detected state change appends, not every observation.
- [x] C4's rules for reappearance, stale books, socket events during a book fetch, empty books
      and failed fetches are requirements, each with an acceptance check.
- [x] Quote acceptance counts rows per reconciled market, including markets that became empty.
- [x] R5.4 and §2.1 describe gap-fill as best effort: `/recent` holds at most 500 orders from
      online users, and the socket carries new orders only.
- [x] §8 no longer requires a Spring context for parsing and diff tests.
- [x] The build order is the review's sequence. C6 is one instance on a fixed cadence over
      watched items. ADR-0006 separates freshness (scheduler) from the ceiling (limiter).
- [x] §10 records the review's open questions for C9a and C10; §11 records the untested backup.

## C4 — order-book ingest

### T5: Orders parse from the captures
- [x] `/v2/orders/item/{slug}` and `/v2/orders/recent` bind into one `Order` model, without
      Spring or a database.
- [x] A dimension absent from an order binds as null; `rank: 0` and `amberStars: 0` bind as 0.
- [x] The model binds nothing about the owner beyond platform and online status (SPEC §9).
- [x] The committed fixtures carry no trader names, slugs, ids or avatars.

### T6: A full book is reconciled against stored state
- [x] Book A then A: no new events.
- [x] A then A' with one price change: exactly one `price_changed`, carrying the previous price.
- [x] A lot-size change alone is a `price_changed`.
- [x] A then A'' missing an order: exactly one `vanished`, carrying the last known values.
- [x] An order that returns after vanishing: `appeared`, carrying its last known values as the
      previous ones.
- [x] An order that moves market or side: `vanished` from the old, `appeared` on the new.
- [x] A book no newer than the last one reconciled for its item: no events, no quotes.
- [x] An order first observed after a book was fetched is not vanished by that book.
- [x] The captured serration book splits across one market per `(subtype, rank)` it contains.
- [x] Classification is tested without Spring or a database.

### T7: A reconciled book writes one quote row per market
- [x] One row for every market of the item, including one that became empty (counts 0, prices
      null).
- [x] Best prices are per unit: the captured ayatan (2,2) market's best bid is 7 a unit, not the
      42 a lot of six costs. Its full book still crosses (7 against 6), because offline owners'
      orders linger; its online pair does not (7 against 7).
- [x] Online measures count only owners whose status is `online` or `ingame`.
- [x] Both rollups carry the new measures, and a database upgraded from V8 keeps every storage
      policy.

### T8: Partial observations only add orders
- [x] An order never seen before: `appeared`, with source `ws` or `recent`.
- [x] A known order, live or gone, records nothing, even with different values.
- [x] Replaying the captured `/recent` twice: events on the first pass only.
- [x] An order for an item missing from the catalog is skipped, not fatal.

### T9: Polling one book
- [x] A successful fetch is reconciled with source `book`.
- [x] A failed fetch writes no event, quote or order state.

## Checkpoint
- [x] `mbuild` green under several seeds — 139 tests
- [ ] `SPEC.md` status and `CHANGELOG.md` updated
- [ ] Review with human
