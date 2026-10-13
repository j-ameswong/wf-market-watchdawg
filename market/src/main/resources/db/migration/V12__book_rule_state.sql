create table watch_condition (
    watch text not null,
    market_id bigint not null references market(id),
    definition text not null,
    active boolean not null,
    observed_at timestamptz not null,
    primary key(watch, market_id, definition)
);
-- Book and contract rules carry their own human-readable evidence and destination.
alter table signal add column title text;
alter table signal add column message text;
alter table signal add column click text;
alter table signal alter column market_id drop not null;
