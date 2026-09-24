-- A migration that cannot run inside a transaction: a continuous aggregate created WITH DATA, the
-- default. The sibling .sql.conf marks it. MigrationPathsTest applies it with and without that file.
create extension if not exists timescaledb;

create table reading (
    observed_at timestamptz not null,
    value       integer     not null
);

select create_hypertable('reading', by_range('observed_at'));

insert into reading values (now(), 1);

create materialized view reading_hourly
with (timescaledb.continuous) as
select time_bucket(interval '1 hour', observed_at) as bucket, sum(value) as total
from reading
group by bucket;
