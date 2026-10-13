-- Append-only operator edits. Absence of revisions means YAML still owns configuration.
create table operator_configuration (
    revision bigint primary key check (revision > 0),
    document jsonb not null,
    saved_at timestamptz not null default now()
);
