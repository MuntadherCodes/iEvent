# iEvent — Deployment Runbook

## Environments

| | Local dev | Staging / Production |
|---|---|---|
| Compose file | `docker-compose.yml` | `docker-compose.staging.yml` |
| URL | http://localhost:8080 | https://$DOMAIN (Caddy auto-HTTPS) |
| Email | Mailpit UI at :8025 | Mailjet SMTP |
| Seed | demo + optional scale | none (`SEED_DEMO=false`) |

## First deployment (staging or production)

1. Server: any Linux box with Docker + Docker Compose, ports 80/443 open.
2. DNS: point `A` record for your domain (e.g. `app.ievent.iq`) at the server IP.
3. Clone the repo, then `cp .env.example .env` and set — at minimum:
   `POSTGRES_PASSWORD` (strong), `DOMAIN`, `APP_BASE_URL=https://<domain>`,
   `MAIL_MODE=mailjet`, `MAILJET_API_KEY`, `MAILJET_SECRET_KEY`,
   `SPRING_MAIL_HOST=in-v3.mailjet.com`, `SPRING_MAIL_PORT=587`,
   `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true`, `SEED_DEMO=false`,
   `SUPER_ADMIN_PASSWORD` (strong — gates the `/admin` super-admin console).
   Optional: `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (Sign in with Google —
   remember to add `https://<domain>/login/oauth2/code/google` as an authorized
   redirect URI in the Google Cloud console), `GOOGLE_MAPS_API_KEY`
   (venue maps + venue search autocomplete; restrict the key to your domain,
   and confirm the Maps Embed API, Maps JavaScript API, and Places API are
   all enabled for it), `OPENAI_API_KEY` (the "Write it for me" AI content
   button; hides when unset), and `PEXELS_API_KEY` (the cover-picker's
   "search photos" tab; hides when unset).
4. `docker compose -f docker-compose.staging.yml up -d --build`
5. Verify: `https://<domain>/actuator/health` → `{"status":"UP"}`; register a
   user; send a test order; check the email arrives via Mailjet.

## Upgrades

```bash
git pull origin main
docker compose -f docker-compose.staging.yml up -d --build app
```
Flyway migrates the database automatically on startup. Never edit an applied
migration; new schema changes always arrive as new `V<n>__*.sql` files.

## Backups

Nightly database dump (run from cron on the host):

```bash
./scripts/backup.sh            # writes ./backups/ievent-YYYYmmdd-HHMM.sql.gz, keeps 14
```
Cron example: `15 2 * * * cd /opt/ievent && ./scripts/backup.sh >> backups/backup.log 2>&1`

Also back up the uploads volume (transfer receipts):
`docker run --rm -v ievent_ievent_uploads:/data -v $PWD/backups:/out alpine tar czf /out/uploads-$(date +%F).tgz /data`

Restore: `gunzip -c backups/<file>.sql.gz | docker compose exec -T db psql -U ievent ievent`

## Safety rails

- **NEVER run `docker compose down -v`** — it destroys the database and receipts.
- Secrets live only in `.env` on the server (git-ignored). Rotate any credential
  that ever appears in a chat, log or screenshot.
- The `ievent_pgdata` and `ievent_uploads` volumes are the only state — protect them.

## Rollback

```bash
git log --oneline               # find the previous good commit
git checkout <sha> -- .         # or: git reset --hard <sha> (if you're sure)
docker compose -f docker-compose.staging.yml up -d --build app
```
Note: rolling back code does NOT roll back applied DB migrations. Migrations are
additive; older app versions tolerate newer columns. For anything riskier,
restore last night's dump first.
