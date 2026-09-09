# `GET /v1/items/{slug}/statistics` — price history

Undocumented upstream: absent from `docs/v1.yml`, and no v2 equivalent exists. This file records
the shape observed from live captures so it does not have to be rediscovered.

**Captured** 2026-09-09 against `https://api.warframe.market/v1`, headers `Platform: pc`,
`Language: en`. Sampled slugs, chosen to exercise every subtype dimension:
`frost_prime_set` (no dimensions), `serration` (mod rank), `axi_a1_relic` (subtype),
`ayatan_anasa_sculpture` (amber/cyan stars), `khra` + `vome` (requiem mods).
898 `statistics_closed` rows and 2,488 `statistics_live` rows in total.

Optional query param `include=item` additionally returns the item manifest entry in `include.item`.

## Envelope

v1 shape — `payload`, not the v2 `apiVersion`/`data`/`error`:

```
{ "payload": {
    "statistics_closed": { "48hours": [...], "90days": [...] },
    "statistics_live":   { "48hours": [...], "90days": [...] } } }
```

- `48hours` — **hourly** buckets (`datetime` on the hour).
- `90days` — **daily** buckets (`datetime` at `00:00:00`).
- `datetime` is ISO-8601 with milliseconds and a numeric offset: `2026-09-07T19:00:00.000+00:00`.
  Note this differs from v2, which uses `2021-05-21T14:59:02Z`.

## `statistics_closed` — completed trades

Derived from orders that actually closed. This is the **only** source of real traded prices and
volumes anywhere in the API; the v2 `Transaction` model is auth-gated to your own orders.
Sparse: a bucket exists only where trades occurred. Has no `order_type` — a completed trade has
no side.

| Field | Type | Presence |
| --- | --- | --- |
| `id` | string (ObjectId) | always — unique per row |
| `datetime` | string | always |
| `volume` | int | always |
| `open_price` | number | always |
| `closed_price` | number | always |
| `min_price` | number | always |
| `max_price` | number | always |
| `avg_price` | number | always |
| `wa_price` | number | always — volume-weighted |
| `median` | number | always |
| `moving_avg` | number | **nullable** (absent on ~2% of rows) |
| `donch_top` | number | always — Donchian channel high |
| `donch_bot` | number | always — Donchian channel low |
| `mod_rank`, `subtype`, `amber_stars`, `cyan_stars` | see Dimensions | iff the item has it |

```json
{"datetime":"2026-06-12T00:00:00.000+00:00","volume":51,"min_price":75.0,"max_price":80.0,
 "open_price":80.0,"closed_price":78.0,"avg_price":77.5,"wa_price":77.941,"median":79.0,
 "moving_avg":88.9,"donch_top":150,"donch_bot":70,"id":"6a2c9d8815830c0010418f11"}
```

## `statistics_live` — open order book

Aggregates of *currently listed* orders, split by side. **`volume` here is not trade volume** —
it is a count over open orders, and runs orders of magnitude larger than the closed series for the
same bucket (2008 vs 1 on `frost_prime_set`). Do not mix the two. Dense: every bucket carries both
sides. Has no OHLC and no Donchian fields.

| Field | Type | Presence |
| --- | --- | --- |
| `id` | string (ObjectId) | always — unique per row |
| `datetime` | string | always |
| `order_type` | string | always — `buy` or `sell` |
| `volume` | int | always |
| `min_price` | number | always |
| `max_price` | number | always |
| `avg_price` | number | always |
| `wa_price` | number | always |
| `median` | number | always |
| `moving_avg` | number | **nullable** (absent on ~54% of rows) |
| `mod_rank`, `subtype`, `amber_stars`, `cyan_stars` | see Dimensions | iff the item has it |

```json
{"datetime":"2026-09-07T19:00:00.000+00:00","volume":2008,"min_price":35,"max_price":70,
 "avg_price":52.5,"wa_price":61.961,"median":57.5,"order_type":"buy","moving_avg":64.5,
 "id":"6a9f17c5659ec8000f9ba308"}
```

## Dimensions

Statistics are split by the **same subtype dimensions as orders**, so rows key to a market rather
than merely to an item. Names are snake_case and one differs from v2:

| v1 statistics | v2 `Order` | Observed on |
| --- | --- | --- |
| `mod_rank` | `rank` | `serration` (0, 10), `khra`/`vome` (3) |
| `subtype` | `subtype` | `axi_a1_relic` (`intact`, `radiant`) |
| `amber_stars` | `amberStars` | `ayatan_anasa_sculpture` (0, 2) |
| `cyan_stars` | `cyanStars` | `ayatan_anasa_sculpture` (0, 2) |
| *(none observed)* | `charges` | — see hazard below |

A dimension field is present only when the item has that dimension; it is absent, not null,
otherwise.

> **Mapping hazard.** No `charges` field appeared on any sampled item. Requiem mods, which v2
> models with `maxCharges`, report **`mod_rank: 3`** here. So v1 `mod_rank` appears to carry what
> v2 calls `charges` for those items. Confirm before mapping `mod_rank` → `rank` unconditionally,
> or dimension-mismatched rows will collapse onto the wrong market.

## Natural key

`(section, granularity, datetime, mod_rank, subtype, amber_stars, cyan_stars)` — plus `order_type`
for `statistics_live` — was **unique with zero duplicates** across all 3,386 sampled rows.

Each row also carries its own `id` (an ObjectId), unique within every window. Prefer the logical
key as the upsert target and store `id` alongside; the logical key is verifiable from the payload,
whereas `id` stability across refetches is assumed rather than confirmed.

## Type caution

`donch_top`, `donch_bot`, `median`, `min_price` and `max_price` arrive as **either** JSON int or
float depending on the value (`"donch_top": 150` vs `80.0`). Bind every price field as a decimal
type; an integer binding will fail on the first fractional value. `volume` is always an int.
