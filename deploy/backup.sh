#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
# Let Compose resolve .env and shell overrides exactly as it does for the application.
database="$(docker compose config --format json | python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["postgres"]["environment"]["POSTGRES_DB"])')"
mkdir -p backups
temporary="$(mktemp backups/.dump.XXXXXXXX)"
trap 'rm -f "$temporary"' EXIT
archive="backups/watchdawg-$(date -u +%Y%m%dT%H%M%SZ)-${temporary##*.}.dump"
docker compose exec -T postgres pg_dump -U watchdawg -d "$database" -Fc > "$temporary"
test -s "$temporary"
mv "$temporary" "$archive"
printf 'Backup written: %s\n' "$archive"
