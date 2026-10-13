create table baseline_calibration (
    watch text not null,
    market_id bigint not null references market(id),
    rule text not null,
    crossplay boolean not null,
    threshold numeric,
    samples integer not null,
    holdout_rate numeric,
    calibrated_at timestamptz not null,
    status text not null check(status in ('ready', 'insufficient', 'over-budget')),
    primary key(watch, market_id, rule, crossplay)
);
