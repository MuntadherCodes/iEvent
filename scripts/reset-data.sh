#!/usr/bin/env sh
# iEvent — wipe ALL application data for a fresh start (Linux/macOS).
# Same behavior as scripts/reset-data.ps1; see that file for details.
# Usage: sh scripts/reset-data.sh
set -e

printf 'This will DELETE ALL iEvent data (accounts, events, orders, tickets, uploads).\nType RESET to continue: '
read -r confirm
[ "$confirm" = "RESET" ] || { echo "Aborted."; exit 1; }

docker compose exec -T db psql -U ievent -d ievent -c "
DO \$\$ DECLARE stmt text;
BEGIN
  SELECT 'TRUNCATE TABLE ' || string_agg(quote_ident(tablename), ', ') || ' RESTART IDENTITY CASCADE'
    INTO stmt
  FROM pg_tables
  WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
  EXECUTE stmt;
END \$\$;"

docker compose exec -T app sh -c "rm -rf /app/data/uploads/* 2>/dev/null || true"
docker compose restart app

echo "Done. The system is empty. Keep SEED_DEMO=false in .env so it stays that way."
