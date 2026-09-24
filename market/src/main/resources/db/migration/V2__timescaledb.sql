-- TimescaleDB backs the fact tables (ADR-0007). The server must preload it
-- (shared_preload_libraries = 'timescaledb'); compose.yaml and the test container both do.
--
-- `if not exists` lets a database where an administrator already created the extension migrate as
-- a role that could not create it.
create extension if not exists timescaledb;
