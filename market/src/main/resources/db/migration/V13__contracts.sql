create table contract_weapon (
    kind text not null check(kind in ('riven', 'lich', 'sister')),
    slug text not null,
    name text not null,
    primary key(kind, slug)
);
create table auction (
    id text primary key,
    kind text not null,
    weapon text not null,
    starting_price integer not null,
    buyout_price integer,
    top_bid integer,
    visible boolean not null,
    closed boolean not null,
    missing boolean not null default false,
    item jsonb not null,
    first_seen_at timestamptz not null,
    observed_at timestamptz not null,
    foreign key(kind, weapon) references contract_weapon(kind, slug)
);
create index auction_target_idx on auction(kind, weapon);
create table auction_riven_attribute (
    auction_id text not null references auction(id),
    name text not null,
    positive boolean not null,
    value numeric not null,
    primary key(auction_id, name)
);
create table auction_event (
    observed_at timestamptz not null,
    auction_id text not null,
    event text not null check(event in ('appeared', 'changed', 'closed', 'disappeared')),
    before_state jsonb,
    after_state jsonb not null,
    primary key(auction_id, observed_at, event)
);
select create_hypertable('auction_event', by_range('observed_at'));
alter table auction_event set (
    timescaledb.enable_columnstore,
    timescaledb.segmentby = 'auction_id',
    timescaledb.orderby = 'observed_at desc, event'
);

create table contract_attribute (
    slug text primary key
);
