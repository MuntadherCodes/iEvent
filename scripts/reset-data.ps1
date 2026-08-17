# iEvent — wipe ALL application data for a fresh start (Windows PowerShell).
#
# Empties every table (users, events, orders, tickets, notifications, ...) and
# deletes uploaded files (covers, logos, receipts, QR images). The database
# SCHEMA is kept, so no migrations re-run and the app keeps working.
#
# To stay empty, set in your .env BEFORE running:   SEED_DEMO=false
# (otherwise the demo organizer and 8 showcase events are re-seeded on restart)
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\reset-data.ps1

$ErrorActionPreference = "Stop"

Write-Host "This will DELETE ALL iEvent data (accounts, events, orders, tickets, uploads)." -ForegroundColor Yellow
$confirm = Read-Host "Type RESET to continue"
if ($confirm -ne "RESET") { Write-Host "Aborted."; exit 1 }

$truncateSql = @"
DO `$`$ DECLARE stmt text;
BEGIN
  SELECT 'TRUNCATE TABLE ' || string_agg(quote_ident(tablename), ', ') || ' RESTART IDENTITY CASCADE'
    INTO stmt
  FROM pg_tables
  WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
  EXECUTE stmt;
END `$`$;
"@

Write-Host "Truncating all application tables..."
docker compose exec -T db psql -U ievent -d ievent -c $truncateSql

Write-Host "Deleting uploaded files (covers, logos, receipts, QR images)..."
docker compose exec -T app sh -c "rm -rf /app/data/uploads/* 2>/dev/null || true"

Write-Host "Restarting the app..."
docker compose restart app

Write-Host ""
Write-Host "Done. The system is empty." -ForegroundColor Green
Write-Host "Reminder: with SEED_DEMO=false in .env it stays empty; with true the demo content returns."
