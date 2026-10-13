-- Old observations have unknown context and are intentionally ineligible for lifetime calibration.
alter table order_event add column crossplay boolean;
alter table baseline_calibration add column holdout_start timestamptz;
alter table baseline_calibration add column holdout_end timestamptz;
alter table baseline_calibration add column budget_suppressed integer;
create table baseline_trial (
    watch text not null,
    market_id bigint not null references market(id),
    rule text not null,
    crossplay boolean not null,
    seen_at timestamptz not null,
    dedup_key text not null,
    cooldown_seconds bigint not null,
    primary key(watch, market_id, rule, crossplay, dedup_key)
);
