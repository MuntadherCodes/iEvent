#!/usr/bin/env bash
# Nightly iEvent backup: Postgres (custom format, restorable over a live DB)
# plus the uploads volume (receipts, covers, logos, QR images). Keeps the
# newest 14 of each. Optionally mirrors the new files off the server when
# BACKUP_REMOTE is set (any rclone/scp style target, see DEPLOY.md).
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.staging.yml}"
PROJECT="$(docker compose -f "$COMPOSE_FILE" config --format json 2>/dev/null | sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' | head -1 || true)"
PROJECT="${PROJECT:-ievent}"
mkdir -p backups
STAMP="$(date +%Y%m%d-%H%M)"
DB_OUT="backups/ievent-${STAMP}.dump"
UP_OUT="backups/uploads-${STAMP}.tgz"

# -Fc = custom format: compressed, and pg_restore --clean can replay it on top
# of an existing database (a plain .sql dump piped into psql fails on the
# first CREATE TABLE that already exists).
docker compose -f "$COMPOSE_FILE" exec -T db \
  pg_dump -U "${POSTGRES_USER:-ievent}" -Fc "${POSTGRES_DB:-ievent}" > "$DB_OUT"
echo "database backup written: $DB_OUT ($(du -h "$DB_OUT" | cut -f1))"

docker run --rm -v "${PROJECT}_ievent_uploads:/data:ro" -v "$PWD/backups:/out" alpine \
  tar czf "/out/$(basename "$UP_OUT")" -C /data .
echo "uploads backup written: $UP_OUT ($(du -h "$UP_OUT" | cut -f1))"

# off-site copy (a backup on the same disk as the database is not a backup)
if [ -n "${BACKUP_REMOTE:-}" ]; then
  if command -v rclone >/dev/null 2>&1; then
    rclone copy "$DB_OUT" "$BACKUP_REMOTE" && rclone copy "$UP_OUT" "$BACKUP_REMOTE"
  else
    scp -q "$DB_OUT" "$UP_OUT" "$BACKUP_REMOTE"
  fi
  echo "copied to $BACKUP_REMOTE"
fi

# retention: keep newest 14 of each
ls -1t backups/ievent-*.dump 2>/dev/null | tail -n +15 | xargs -r rm --
ls -1t backups/uploads-*.tgz 2>/dev/null | tail -n +15 | xargs -r rm --
