#!/usr/bin/env bash
# Nightly Postgres backup for iEvent. Keeps the newest 14 dumps.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.staging.yml}"
mkdir -p backups
STAMP="$(date +%Y%m%d-%H%M)"
OUT="backups/ievent-${STAMP}.sql.gz"

docker compose -f "$COMPOSE_FILE" exec -T db \
  pg_dump -U "${POSTGRES_USER:-ievent}" "${POSTGRES_DB:-ievent}" | gzip > "$OUT"

echo "backup written: $OUT ($(du -h "$OUT" | cut -f1))"

# retention: keep newest 14
ls -1t backups/ievent-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm --
