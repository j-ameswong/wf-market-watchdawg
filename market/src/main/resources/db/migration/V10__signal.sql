-- C9a/C10: the outbox. A rule admits a signal in the transaction that reconciled the book behind it
-- (R9a.7); the dispatcher sends it later and records the outcome (R10.1). An ordinary table, not a
-- hypertable: it stays small, and the dispatcher updates its rows.
create table signal (
    id              bigint      generated always as identity primary key,
    watch           text        not null,              -- the watch's name in watches.yaml
    rule            text        not null,
    market_id       bigint      not null references market (id),
    order_id        text        not null,
    -- watch, order and unit price: one listing at one price is admitted once, whatever its state
    -- (R9a.5). A failed or suppressed signal keeps its key, so it is never re-admitted.
    dedup_key       text        not null unique,
    unit_price      numeric     not null,
    threshold       numeric     not null,
    platinum        integer     not null,              -- price of one lot
    per_trade       integer,                           -- lot size; null when upstream omits it
    priority        integer     not null,              -- ntfy priority, 1 to 5 (R10.5)
    topic           text        not null,              -- logical topic; the real one is never stored
    state           text        not null check (state in ('pending', 'sent', 'failed', 'suppressed')),
    attempts        integer     not null default 0,
    next_attempt_at timestamptz,                       -- null: send as soon as the dispatcher runs
    last_error      text,
    seen_at         timestamptz not null,              -- when the book showing the listing was requested
    created_at      timestamptz not null default now(),
    notified_at     timestamptz
);

create index signal_pending_idx on signal (id) where state = 'pending';
create index signal_watch_idx on signal (watch, created_at);
