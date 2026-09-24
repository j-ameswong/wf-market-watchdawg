-- One snapshot of a market's book per poll (R4.3). Raw rows are kept for a bounded window, and the
-- hourly and daily rollups below are kept forever (R2.4, ADR-0007). The windows live in
-- R__storage_policies.sql.
--
-- R4.3's remaining measures (quantities, depth, online counts) are C4's to add. The rollups have
-- to be dropped and recreated then, which costs nothing until they hold history older than the raw
-- window. After that, a changed rollup means a new one alongside.
create table market_quote (
    market_id   bigint      not null,
    observed_at timestamptz not null,   -- partition column
    best_buy    integer,                -- highest buy price; null when nobody is buying
    best_sell   integer,                -- lowest sell price; null when nobody is selling
    buy_count   integer     not null,
    sell_count  integer     not null,
    primary key (market_id, observed_at)
);

select create_hypertable('market_quote', by_range('observed_at'));

-- Both rollups read the raw table directly rather than daily reading hourly, so each average is
-- over the polls themselves, not an average of averages.
--
-- WITH NO DATA lets this run inside the migration's transaction. The table is empty here anyway,
-- and the refresh policies fill the rollups from then on.
create materialized view market_quote_hourly
with (timescaledb.continuous) as
select market_id,
       time_bucket(interval '1 hour', observed_at) as bucket,
       count(*)                      as polls,
       first(best_buy, observed_at)  as best_buy_open,
       max(best_buy)                 as best_buy_high,
       min(best_buy)                 as best_buy_low,
       last(best_buy, observed_at)   as best_buy_close,
       avg(best_buy)                 as best_buy_avg,
       first(best_sell, observed_at) as best_sell_open,
       max(best_sell)                as best_sell_high,
       min(best_sell)                as best_sell_low,
       last(best_sell, observed_at)  as best_sell_close,
       avg(best_sell)                as best_sell_avg,
       avg(buy_count)                as buy_count_avg,
       avg(sell_count)               as sell_count_avg
from market_quote
group by market_id, bucket
with no data;

create materialized view market_quote_daily
with (timescaledb.continuous) as
select market_id,
       time_bucket(interval '1 day', observed_at) as bucket,
       count(*)                      as polls,
       first(best_buy, observed_at)  as best_buy_open,
       max(best_buy)                 as best_buy_high,
       min(best_buy)                 as best_buy_low,
       last(best_buy, observed_at)   as best_buy_close,
       avg(best_buy)                 as best_buy_avg,
       first(best_sell, observed_at) as best_sell_open,
       max(best_sell)                as best_sell_high,
       min(best_sell)                as best_sell_low,
       last(best_sell, observed_at)  as best_sell_close,
       avg(best_sell)                as best_sell_avg,
       avg(buy_count)                as buy_count_avg,
       avg(sell_count)               as sell_count_avg
from market_quote
group by market_id, bucket
with no data;
