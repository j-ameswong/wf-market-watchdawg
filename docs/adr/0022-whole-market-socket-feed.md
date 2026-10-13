# 0022 — Record the whole-market socket feed

Status: accepted (author 2026-09-26; implemented 2026-10-13)

The socket carries a market-wide stream, including items outside the configured watches. Record
all valid new orders using insert-only partial ingest, and evaluate alerts only for watches that
select them. This retains evidence of short-lived listings without multiplying subscriptions or
REST requests. Persisted owner status is not required; evaluate the supplied status at arrival.
The live capture includes offline owners, so arrival alone never means online.

One JDK WebSocket connects with the `wfm` subprotocol and explicit platform/crossplay. Fragmented
messages are assembled with a size limit; each valid new order uses the existing transaction.
Subscription confirmation starts a best-effort `/recent` gap fill, with a one-minute cache and
independent Retry-After holdoff. Reconnect uses capped full jitter and resets after a stable minute.

The consequence is whole-market write volume. Metrics and Timescale compression make that cost
visible; this does not guarantee reconstruction of activity outside the recent endpoint's window.
