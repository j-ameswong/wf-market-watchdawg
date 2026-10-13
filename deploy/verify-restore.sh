#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
archive="${1:?usage: verify-restore.sh backups/file.dump}"
test -s "$archive"
scratch="restore_check_$(date +%s)_$$"
cleanup() { docker compose exec -T postgres dropdb -U watchdawg --if-exists "$scratch"; }
trap cleanup EXIT
docker compose exec -T postgres createdb -U watchdawg "$scratch"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U watchdawg -d "$scratch" -c 'CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore();'
docker compose exec -T postgres pg_restore -U watchdawg -d "$scratch" --exit-on-error --no-owner < "$archive"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U watchdawg -d "$scratch" -c 'SELECT timescaledb_post_restore(); SELECT count(*) AS observations FROM order_event; SELECT count(*) AS statistics FROM item_stat; SELECT count(*) AS auctions FROM auction;'
printf 'Restore verified in isolated scratch database.\n'
