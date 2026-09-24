-- Every compression, retention and aggregate-refresh policy, in one place.
--
-- A repeatable migration: Flyway re-applies it whenever this file changes, through bootRun and
-- mflyway alike. Each policy is removed and re-added, so the whole file is safe to re-run.
--
-- Changing a value here changes retention or compression policy, which SPEC 9 makes ask-first.

-- The order event log is never dropped, and is compressed once it is a week old (R2.3).
call remove_columnstore_policy('order_event', if_exists => true);
call add_columnstore_policy('order_event', after => interval '7 days');
