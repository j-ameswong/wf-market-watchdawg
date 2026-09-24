-- Every compression, retention and aggregate-refresh policy, in one place.
--
-- A repeatable migration: Flyway re-applies it whenever this file changes, through bootRun and
-- mflyway alike. Each policy is removed and re-added, so the whole file is safe to re-run.
--
-- Changing a value here changes retention or compression policy, which SPEC 9 makes ask-first.

-- The order event log is never dropped, and is compressed once it is a week old (R2.3).
call remove_columnstore_policy('order_event', if_exists => true);
call add_columnstore_policy('order_event', after => interval '7 days');

-- Raw quote snapshots are dropped after 90 days; the hourly and daily rollups have no retention
-- and are kept forever (R2.4).
select remove_retention_policy('market_quote', if_exists => true);
select add_retention_policy('market_quote', drop_after => interval '90 days');

-- Each refresh window must start well inside those 90 days. Refreshing a range whose raw chunks
-- are gone deletes the rollup's rows for that range, so a wider window would erase history.
-- QuoteStorageTest fails if a window reaches that far.
select remove_continuous_aggregate_policy('market_quote_hourly', if_exists => true);
select add_continuous_aggregate_policy(
    'market_quote_hourly',
    start_offset => interval '2 days',
    end_offset => interval '1 hour',
    schedule_interval => interval '30 minutes'
);

select remove_continuous_aggregate_policy('market_quote_daily', if_exists => true);
select add_continuous_aggregate_policy(
    'market_quote_daily',
    start_offset => interval '4 days',
    end_offset => interval '1 hour',
    schedule_interval => interval '30 minutes'
);
