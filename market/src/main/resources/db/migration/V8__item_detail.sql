-- /v2/items leaves out tradable, rarity and maxCharges (live capture, 2026-09-24), so ItemDetailSync
-- fetches them item by item from /v2/item/{slug}. This records when it last did, per item; null
-- means never. Every row starts null, so the first sweeps work through the whole catalog.
alter table item add column detail_synced_at timestamptz;
