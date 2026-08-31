create table collection_version (
    name       text        primary key,
    hash       text        not null,
    updated_at timestamptz not null
);

create table item (
    id         text        primary key,   -- wf.market item id
    slug       text        not null unique,
    game_ref   text,
    ducats     integer,
    max_rank   integer,
    vaulted    boolean     not null default false,
    tags       text[]      not null default '{}',
    updated_at timestamptz not null default now()
);

create index item_slug_idx on item (slug);
