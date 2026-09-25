-- The catalog carries what later capabilities read (R3.1): the subtype dimensions and their
-- maxima, tradability, and the English name and icon a notification shows.
--
-- Every new column is nullable. The v2 Item model marks each of these fields optional, and null
-- says "the catalog did not say" where a zero or false would look like data (R7.8).
alter table item
    add column name            text,
    add column icon            text,
    add column subtypes        text[] not null default '{}',
    add column max_charges     integer,
    add column max_amber_stars integer,
    add column max_cyan_stars  integer,
    add column bulk_tradable   boolean,
    add column tradable        boolean,
    add column rarity          text;

-- v2 has no Item.updatedAt (R3.2), so this column held the epoch on every row. It becomes the time
-- this service last synced the row, which the upsert stamps. An epoch was never a real sync time,
-- so it becomes null: "not synced since this migration".
alter table item rename column updated_at to synced_at;
alter table item alter column synced_at drop not null;
alter table item alter column synced_at drop default;
update item set synced_at = null where synced_at = 'epoch';

-- Rows already here have none of the new columns filled, and the refresh only runs when the
-- upstream items hash changes, which can take weeks. Forgetting the stored hash makes the next
-- tick refetch the catalog.
delete from collection_version where name = 'items';
