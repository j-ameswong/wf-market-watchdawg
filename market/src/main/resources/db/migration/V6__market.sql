-- One order book: an item seen from the observer's platform, split by every subtype dimension an
-- order carries (SPEC 2.3, ADR-0003). platform is where this service trades from, never the
-- seller's platform, which belongs to the order.
create table market (
    id          bigint  generated always as identity primary key,
    item_id     text    not null references item (id),
    platform    text    not null,
    subtype     text,
    rank        integer,
    charges     integer,
    amber_stars integer,
    cyan_stars  integer,
    -- A dimension an item lacks is null, and two nulls must count as equal here (R3.4). With
    -- Postgres's default, every order for an item without subtypes would create a new market.
    constraint market_tuple unique nulls not distinct
        (item_id, platform, subtype, rank, charges, amber_stars, cyan_stars)
);
