-- The dashboard counts one UTC day without scanning the entire outbox history.
create index signal_seen_at_idx on signal(seen_at, state);
create index auction_recent_target_idx on auction(kind, weapon, observed_at desc, id);
