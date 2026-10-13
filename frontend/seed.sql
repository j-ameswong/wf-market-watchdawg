-- Synthetic development facts for UI mechanics, never calibration or production evidence.
insert into item(id, slug, name, max_rank, subtypes, detail_synced_at)
values ('ui-khra', 'khra', 'Khra', 3, '{}', now()),
    ('ui-serration', 'serration', 'Serration', 10, '{base}', now()),
    ('ui-frost', 'frost_prime_set', 'Frost Prime Set', null, '{}', now());
insert into market(item_id, platform, rank) values ('ui-khra', 'pc', 3), ('ui-khra', 'pc', 0);
insert into contract_weapon(kind, slug, name) values
    ('riven', 'soma', 'Soma'), ('lich', 'kuva_bramma', 'Kuva Bramma'), ('sister', 'tenet_arca_plasmor', 'Tenet Arca Plasmor');
insert into contract_attribute(slug, positive_is_negative) values
    ('critical_chance', false), ('critical_damage', false), ('recoil', true), ('zoom', false);
insert into item_stat(market_id, section, granularity, bucket, order_type, crossplay, upstream_id,
    volume, min_price, max_price, avg_price, wa_price, median, fetched_at,
    open_price, closed_price, donch_top, donch_bot)
select m.id, 'closed', 'daily', date_trunc('day', now()) - n * interval '1 day', '', cp,
    'ui-stat', 40 + n * 3, 8, 24, 14, 14, case when cp then 12 + sin(n::numeric / 2)*3 else 80 end,
    now(), 10, 14, 24, 8
from market m cross join generate_series(1,29) n cross join (values (true), (false)) population(cp)
where m.rank = 3 and n <> 9;
