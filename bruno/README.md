# warframe.market — Bruno collection

Every request here returns `200` with a well-formed body from the live API — 37
requests, 37 assertions passing, last verified **2026-09-11**:

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

## Order captures as test fixtures

Order responses name real traders. Before a capture is committed as a fixture, run it through
`scrub-orders.mjs`, which replaces each owner with a numbered pseudonym and keeps every order
field as captured:

```
node bruno/scrub-orders.mjs < response.json > market/src/test/resources/fixtures/v2-orders/<name>.json
```

## Live socket capture (C5 T1)

From the repository root, with JDK 21+ and Node already on the path:

```sh
java bruno/CaptureNewOrders.java 30 > /tmp/wfm-socket-raw.json
node bruno/scrub-orders.mjs --socket < /tmp/wfm-socket-raw.json > /tmp/wfm-socket-scrubbed.json
```

`CaptureNewOrders.java` uses the JDK WebSocket client without adding a dependency. It opens one
connection to `wss://ws.warframe.market/socket` with the `wfm` subprotocol and the project's
`User-Agent`, then sends `subscribe/newOrders` with `platform: "pc"` and `crossplay: true`
explicitly (the application defaults). The argument is the capture duration in seconds, default
30; the script closes the connection at the end and does not reconnect. Stdout is a JSON array of
complete text messages in arrival order, with fragments assembled; diagnostics go to stderr.

Check that the command succeeded and the capture contains `subscribe/newOrders:ok`,
`reports/online` and `subscriptions/newOrder` before keeping it. `--socket` preserves every
envelope and scrubs each new order's user exactly as for REST, including stable pseudonyms within
the capture. Keep only the scrubbed file; remove the raw file after checking it.

The 2026-09-26 capture is committed at
[`fixtures/v2-socket/new-orders.json`](../market/src/test/resources/fixtures/v2-socket/new-orders.json).
Its field observations and the resulting status decision are recorded in
[`tasks/plan.md`](../tasks/plan.md#live-capture-2026-09-26).

## Notes worth keeping

- `/v2/order/{id}` resolves ids taken from `/v2/orders/item/{slug}`, but ids scraped
  from `/v2/orders/recent` frequently `404` there.
- `/v2/achievements/user/{slug}` and `/v1/auctions/entry/{id}/bids` return empty
  collections for many subjects — that is a success, not a failure.
- The seeded `auctionId` points at a live riven auction and will close eventually;
  refresh it from **Search Riven Auctions** if that request starts failing.
- v2 spells it `ephemeras`; the `sister/ephermeras` typo in `docs/v1.yml` is dead
  along with the rest of those v1 routes.
