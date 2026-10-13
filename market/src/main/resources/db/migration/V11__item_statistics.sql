-- C7: two distinct populations and two distinct series, never merged by upstream row id.
create table item_stat (
    market_id bigint not null references market(id),
    section text not null check (section in ('closed', 'live')),
    granularity text not null check (granularity in ('hourly', 'daily')),
    bucket timestamptz not null,
    order_type text not null check (order_type in ('', 'buy', 'sell')),
    crossplay boolean not null,
    upstream_id text not null,
    volume integer not null check (volume >= 0),
    min_price numeric not null,
    max_price numeric not null,
    avg_price numeric not null,
    wa_price numeric not null,
    median numeric not null,
    moving_avg numeric,
    open_price numeric,
    closed_price numeric,
    donch_top numeric,
    donch_bot numeric,
    fetched_at timestamptz not null,
    primary key (market_id, section, granularity, bucket, order_type, crossplay),
    check ((section = 'closed' and order_type = '' and open_price is not null and closed_price is not null
            and donch_top is not null and donch_bot is not null)
        or (section = 'live' and order_type in ('buy', 'sell') and open_price is null and closed_price is null
            and donch_top is null and donch_bot is null))
);
select create_hypertable('item_stat', by_range('bucket'));
create index item_stat_baseline_idx on item_stat(market_id, crossplay, bucket desc)
    where section = 'closed' and granularity = 'daily';
-- Mutable upstream windows extend to 90 days: columnstore begins only beyond that window.
alter table item_stat set (
    timescaledb.enable_columnstore,
    timescaledb.segmentby = 'market_id, section, granularity, order_type, crossplay',
    timescaledb.orderby = 'bucket desc'
);
