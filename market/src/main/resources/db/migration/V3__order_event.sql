-- The order event log: one row per observed change to an order (R4.2). Kept forever and
-- compressed once old (R2.3, ADR-0007); the compression age lives in R__storage_policies.sql.
--
-- market_id will reference market (C3). A foreign key can be added to this table after
-- compression is enabled, so it waits for that table to exist.
create table order_event (
    observed_at   timestamptz not null,   -- partition column
    market_id     bigint      not null,
    order_id      text        not null,   -- warframe.market order id
    event         text        not null
        check (event in ('appeared', 'price_changed', 'quantity_changed', 'vanished')),
    source        text        not null
        check (source in ('ws', 'recent', 'book')),
    platinum      integer     not null,   -- after the event; for vanished, the last known value
    quantity      integer     not null,
    prev_platinum integer,                -- before the event; null when there was no earlier state
    prev_quantity integer,
    -- A unique key on a hypertable must include its partition column (R2.2).
    primary key (order_id, event, observed_at)
);

select create_hypertable('order_event', by_range('observed_at'));

create index order_event_market_idx on order_event (market_id, observed_at desc);

-- Compressed rows are grouped per market, the unit most reads ask for. The primary key's columns
-- are all in segmentby or orderby, so inserting into a compressed chunk can check the key without
-- decompressing the whole segment.
alter table order_event set (
    timescaledb.enable_columnstore,
    timescaledb.segmentby = 'market_id',
    timescaledb.orderby = 'observed_at desc, order_id, event'
);
