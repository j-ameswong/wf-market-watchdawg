# warframe.market — Bruno collection

Every request here was run against the live API on **2026-08-31** and returned
`200` with a well-formed body. 37 requests, 37 assertions passing:

```
npx @usebruno/cli run --env production --delay 400 -r
```

`--delay 400` keeps the run under the public **3 req/s** limit (Cloudflare answers
`429` above it, and `509` on too many concurrent connections).

## Layout

| Folder            | Contents                                                                  |
| ----------------- | ------------------------------------------------------------------------- |
| `v2/manifests`    | versions, items, item sets, riven/lich/sister weapons, locations, npcs, missions |
| `v2/orders`       | recent, by item (slug + itemId), top (slug + itemId), single order         |
| `v2/users`        | public user, user's orders, user's achievements                            |
| `v2/misc`         | achievements list, dashboard showcase                                      |
| `v1/items`        | item statistics, drop sources                                              |
| `v1/auctions`     | riven / lich / sister search, auction entry, auction bids                  |
| `v1/users`        | user statistics                                                            |

Headers (`Platform`, `Crossplay`, `Language`) and a `User-Agent` come from the
`production` environment; sample slugs and ids are variables there too, so you can
repoint the whole collection at a different item or user in one place.

## Why v1 is nearly empty

Most of `docs/v1.yml` is dead. Verified live:

| v1 path                          | Result                                       |
| -------------------------------- | -------------------------------------------- |
| `/items`, `/items/{slug}`        | `404` route not found                        |
| `/items/{slug}/orders`           | `403 Deprecated` (plain text, not JSON)      |
| `/items/{slug}/dropsources`      | ✅ 200 — in the collection                    |
| `/items/{slug}/statistics`       | ✅ 200 — in the collection (undocumented in v1.yml) |
| `/lich/*`, `/sister/*`, `/riven/*` | `404` — moved to v2                        |
| `/locations`, `/npc`, `/missions` | `404` — moved to v2 (`/v2/npcs` is plural)  |
| `/profile/{user}`                | `404`                                        |
| `/profile/{user}/statistics`     | ✅ 200 — in the collection (undocumented in v1.yml) |
| `/auctions/*`                    | ✅ 200 — in the collection; **no v2 equivalent exists** |

So the only reasons to still call v1 are **auctions**, **price history
(`statistics`)**, and **drop sources**.

## Deliberately excluded

Auth-gated endpoints cannot be verified without credentials, so they are not
shipped as requests. Their routes are alive (they answer `401`, not `404`):

- `POST /v2/auth/signin`, `/auth/signup`, `/auth/refresh`, `/auth/signout`
- `GET|PATCH /v2/me`, `POST /v2/me/avatar`, `POST /v2/me/background`
- `GET /v2/orders/my`
- `POST /v2/order`, `PATCH|DELETE /v2/order/{id}`, `POST /v2/order/{id}/close`
- `PATCH /v2/orders/group/{id}`
- v1 `/profile/orders`, `/settings/notifications/push`, `/auctions/create`, `/im/chats`

Write endpoints (`POST`/`PATCH`/`DELETE` on orders and auctions) are also excluded
because "verifying" them means mutating a real account's live listings.

## Notes worth keeping

- `/v2/order/{id}` resolves ids taken from `/v2/orders/item/{slug}`, but ids scraped
  from `/v2/orders/recent` frequently `404` there.
- `/v2/achievements/user/{slug}` and `/v1/auctions/entry/{id}/bids` return empty
  collections for many subjects — that is a success, not a failure.
- The seeded `auctionId` points at a live riven auction and will close eventually;
  refresh it from **Search Riven Auctions** if that request starts failing.
- v2 spells it `ephemeras`; the `sister/ephermeras` typo in `docs/v1.yml` is dead
  along with the rest of those v1 routes.
