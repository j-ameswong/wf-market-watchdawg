alter table contract_attribute add column positive_is_negative boolean not null default false;
-- Re-fetch metadata even when the upstream collection hash has not changed.
delete from collection_version where name = 'rivens';

alter table auction add column verification_pending boolean not null default false;
alter table auction add column verification_attempted_at timestamptz;
alter table auction add column verification_after timestamptz;
update auction set verification_pending = true where missing and not closed;
create index auction_verification_idx on auction(kind, weapon, verification_attempted_at, observed_at)
    where verification_pending and not closed;
