#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
task_container="watchdawg-ui-check-$$"
task_pid=''
cleanup() {
  if [[ -n "$task_pid" ]]; then kill "$task_pid" 2>/dev/null || true; wait "$task_pid" 2>/dev/null || true; fi
  docker rm -fv "$task_container" >/dev/null 2>&1 || true
  rm -rf "$task_tmp"
}
trap cleanup EXIT
test -f market/build/libs/market-0.0.1-SNAPSHOT.jar || { echo 'Build the jar with ./gradlew bootJar first.'; exit 1; }
docker run -d --name "$task_container" -e POSTGRES_USER=watchdawg -e POSTGRES_PASSWORD=ui-check \
  -e POSTGRES_DB=watchdawg -p 127.0.0.1::5432 timescale/timescaledb:2.30.1-pg18 >/dev/null
for attempt in {1..60}; do
  if docker exec "$task_container" pg_isready -U watchdawg >/dev/null 2>&1; then break; fi
  sleep 0.5
done
task_port=$(docker port "$task_container" 5432/tcp | sed 's/.*://')
printf 'watchdawg:\n  watches: []\n' > "$task_tmp/watches.yaml"
start_service() {
task_url=''
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$task_port/watchdawg" \
SPRING_DATASOURCE_USERNAME=watchdawg SPRING_DATASOURCE_PASSWORD=ui-check \
WATCHDAWG_WATCHES_FILE="file:$task_tmp/watches.yaml" \
java -jar market/build/libs/market-0.0.1-SNAPSHOT.jar \
  --server.port=0 --server.address=127.0.0.1 --spring.docker.compose.enabled=false \
  --watchdawg.scheduling.enabled=false --watchdawg.socket.enabled=false \
  --watchdawg.statistics.enabled=false --watchdawg.contracts.enabled=false > "$task_tmp/server.log" 2>&1 &
task_pid=$!
for attempt in {1..100}; do
  task_http_port=$(sed -n 's/.*Tomcat started on port \([0-9][0-9]*\).*/\1/p' "$task_tmp/server.log" | tail -1)
  if [[ -n "$task_http_port" ]]; then task_url="http://127.0.0.1:$task_http_port"; break; fi
  if ! kill -0 "$task_pid" 2>/dev/null; then cat "$task_tmp/server.log"; exit 1; fi
  sleep 0.5
done
test -n "$task_url" || { cat "$task_tmp/server.log"; exit 1; }
curl -fsS "$task_url"/api/operator/configuration > /dev/null
}
start_service
docker exec -i "$task_container" psql -v ON_ERROR_STOP=1 -U watchdawg -d watchdawg < frontend/seed.sql > /dev/null
cd frontend
WATCHDAWG_TEST_URL="$task_url" npm test "$@"

# Verify a real process restart restores the saved document and resolved live watches.
curl -fsS "$task_url"/api/operator/configuration > "$task_tmp/before.json"
kill "$task_pid"
wait "$task_pid" || true
cd ..
start_service
curl -fsS "$task_url"/api/operator/configuration > "$task_tmp/after.json"
cmp "$task_tmp/before.json" "$task_tmp/after.json"
curl -fsS "$task_url"/api/operator/overview > "$task_tmp/overview.json"
node --input-type=module - "$task_tmp/after.json" "$task_tmp/overview.json" <<'JS'
import fs from 'node:fs';
import assert from 'node:assert/strict';
const doc=JSON.parse(fs.readFileSync(process.argv[2])).document;
const overview=JSON.parse(fs.readFileSync(process.argv[3]));
assert.equal(overview.activeWatches,[...doc.watches,...doc.contracts].filter(w=>w.enabled).length);
assert.deepEqual(overview.watchedItemSlugs.sort(), [...new Set(doc.watches.filter(w=>w.enabled).map(w=>w.item))].sort());
console.log('Process restart restored configuration and active watches.');
JS
