-- The fact tables reference the market they describe. C2 created them before market existed. A
-- foreign key onto a regular table works on a hypertable with compression enabled, and it is
-- enforced on inserts into compressed chunks too.
alter table order_event
    add constraint order_event_market_fk foreign key (market_id) references market (id);

alter table market_quote
    add constraint market_quote_market_fk foreign key (market_id) references market (id);
