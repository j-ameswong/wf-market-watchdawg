-- C4: order state, the book watermark, and the quote and event columns ingest writes.

-- The current state of every order ingest has seen. Rows are never deleted: a vanished order keeps
-- its last known values and gets gone_at, and comes back if a later book shows it again (R4.7).
create table wfm_order (
    id             text        primary key,   -- warframe.market order id
    market_id      bigint      not null references market (id),
    type           text        not null check (type in ('buy', 'sell')),
    platinum       integer     not null,      -- price of one lot
    per_trade      integer,                   -- lot size; null when upstream omits it
    quantity       integer     not null,
    owner_platform text,                      -- platform of whoever posted it, buyer or seller
    first_seen_at  timestamptz not null,
    changed_at     timestamptz not null,      -- when the current state, gone or not, was observed
    gone_at        timestamptz                -- null while the order is live
);

create index wfm_order_live_idx on wfm_order (market_id) where gone_at is null;

-- The latest full book reconciled for each item. A book no newer than this is ignored (R4.9), and
-- claiming the row locks it, so two reconciliations of one item run one after the other.
create table order_book (
    item_id     text        primary key references item (id),
    observed_at timestamptz not null
);

-- Events carry the order's side and lot size, so the log alone reconstructs each market's visible
-- book (R4.2). TimescaleDB refuses a NOT NULL column without a default once the columnstore is on,
-- so the column gets a placeholder default that is dropped at once. No row can have taken it:
do $$
begin
    if exists (select 1 from order_event) then
        raise exception 'order_event has rows; they would all be labelled sell';
    end if;
end
$$;

alter table order_event add column type text not null default 'sell' check (type in ('buy', 'sell'));
alter table order_event alter column type drop default;
alter table order_event add column per_trade integer;   -- lot size after the event

-- Quotes are per unit, over all visible orders and over orders whose owner is online or in game
-- (R4.3). Neither rollup holds history yet, so both are dropped and recreated with the new
-- measures. Dropping them drops their refresh policies too; R__storage_policies.sql restores them,
-- which is why it changes alongside this file.
drop materialized view market_quote_daily;
drop materialized view market_quote_hourly;

alter table market_quote
    alter column best_buy type numeric,
    alter column best_sell type numeric,
    add column best_buy_online numeric,
    add column best_sell_online numeric,
    add column buy_online_count integer not null,
    add column sell_online_count integer not null;

-- Each rollup averages over polls, so an _avg is a mean of sampled quotes, not a time-weighted
-- price: a period polled more often weighs more. Both read the raw table directly rather than
-- daily reading hourly, so no average is an average of averages.
create materialized view market_quote_hourly
with (timescaledb.continuous) as
select market_id,
       time_bucket(interval '1 hour', observed_at) as bucket,
       count(*)                             as polls,
       first(best_buy, observed_at)         as best_buy_open,
       max(best_buy)                        as best_buy_high,
       min(best_buy)                        as best_buy_low,
       last(best_buy, observed_at)          as best_buy_close,
       avg(best_buy)                        as best_buy_avg,
       first(best_sell, observed_at)        as best_sell_open,
       max(best_sell)                       as best_sell_high,
       min(best_sell)                       as best_sell_low,
       last(best_sell, observed_at)         as best_sell_close,
       avg(best_sell)                       as best_sell_avg,
       first(best_buy_online, observed_at)  as best_buy_online_open,
       max(best_buy_online)                 as best_buy_online_high,
       min(best_buy_online)                 as best_buy_online_low,
       last(best_buy_online, observed_at)   as best_buy_online_close,
       avg(best_buy_online)                 as best_buy_online_avg,
       first(best_sell_online, observed_at) as best_sell_online_open,
       max(best_sell_online)                as best_sell_online_high,
       min(best_sell_online)                as best_sell_online_low,
       last(best_sell_online, observed_at)  as best_sell_online_close,
       avg(best_sell_online)                as best_sell_online_avg,
       avg(buy_count)                       as buy_count_avg,
       avg(sell_count)                      as sell_count_avg,
       avg(buy_online_count)                as buy_online_count_avg,
       avg(sell_online_count)               as sell_online_count_avg
from market_quote
group by market_id, bucket
with no data;

create materialized view market_quote_daily
with (timescaledb.continuous) as
select market_id,
       time_bucket(interval '1 day', observed_at) as bucket,
       count(*)                             as polls,
       first(best_buy, observed_at)         as best_buy_open,
       max(best_buy)                        as best_buy_high,
       min(best_buy)                        as best_buy_low,
       last(best_buy, observed_at)          as best_buy_close,
       avg(best_buy)                        as best_buy_avg,
       first(best_sell, observed_at)        as best_sell_open,
       max(best_sell)                       as best_sell_high,
       min(best_sell)                       as best_sell_low,
       last(best_sell, observed_at)         as best_sell_close,
       avg(best_sell)                       as best_sell_avg,
       first(best_buy_online, observed_at)  as best_buy_online_open,
       max(best_buy_online)                 as best_buy_online_high,
       min(best_buy_online)                 as best_buy_online_low,
       last(best_buy_online, observed_at)   as best_buy_online_close,
       avg(best_buy_online)                 as best_buy_online_avg,
       first(best_sell_online, observed_at) as best_sell_online_open,
       max(best_sell_online)                as best_sell_online_high,
       min(best_sell_online)                as best_sell_online_low,
       last(best_sell_online, observed_at)  as best_sell_online_close,
       avg(best_sell_online)                as best_sell_online_avg,
       avg(buy_count)                       as buy_count_avg,
       avg(sell_count)                      as sell_count_avg,
       avg(buy_online_count)                as buy_online_count_avg,
       avg(sell_online_count)               as sell_online_count_avg
from market_quote
group by market_id, bucket
with no data;
