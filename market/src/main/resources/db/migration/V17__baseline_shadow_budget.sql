-- Prospective combined-rule evidence; historical signal absence is not evidence of zero demand.
create table baseline_budget_epoch (
    singleton boolean primary key default true check (singleton),
    signature text not null,
    started_at timestamptz not null,
    checked_until timestamptz,
    budget_suppressed integer,
    status text not null
);
create table baseline_shadow (
    id bigint generated always as identity primary key,
    watch text not null,
    dedup_key text not null,
    seen_at timestamptz not null,
    cooldown_seconds bigint not null,
    unit_price numeric not null,
    cheaper_bypasses boolean not null,
    batch text not null
);
create index baseline_shadow_time on baseline_shadow(seen_at);
create table baseline_coverage (
    stream text primary key,
    run text not null,
    started_at timestamptz not null,
    last_at timestamptz not null,
    max_gap_seconds bigint not null
);
alter table baseline_calibration drop constraint baseline_calibration_status_check;
alter table baseline_calibration add constraint baseline_calibration_status_check
    check(status in ('ready', 'insufficient', 'over-budget', 'stale'));
