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
2. DNS: point `A` record for your domain (e.g. `app.ievent.events`) at the server IP.
3. Clone the repo, then `cp .env.example .env` and set — at minimum:
   `POSTGRES_PASSWORD` (strong), `DOMAIN`, `APP_BASE_URL=https://<domain>`,
   `MAIL_MODE=mailjet`, `MAILJET_API_KEY`, `MAILJET_SECRET_KEY`,
   `SPRING_MAIL_HOST=in-v3.mailjet.com`, `SPRING_MAIL_PORT=587`,
   `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true`, `SEED_DEMO=false`,
   `SUPER_ADMIN_PASSWORD` (strong — gates the `/admin` super-admin console),
   `REMEMBER_ME_KEY` (`openssl rand -hex 32`, keeps "remember me" logins across
   deploys), `BACKUP_REMOTE` (off-site target for nightly backups, see below).
   Leave `RATE_LIMIT_ENABLED=true`; the staging compose file already forces the
   session cookie to `Secure` and pins the container to `Asia/Baghdad`.
   Optional: `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (Sign in with Google —
   remember to add `https://<domain>/login/oauth2/code/google` as an authorized
   redirect URI in the Google Cloud console), `GOOGLE_MAPS_API_KEY`
   (venue maps + venue search autocomplete; restrict the key to your domain,
   and confirm the Maps Embed API, Maps JavaScript API, and Places API are
   all enabled for it), `OPENAI_API_KEY` (the "Write it for me" AI content
   button; hides when unset), `PEXELS_API_KEY` (the cover-picker's
   "search photos" tab; hides when unset), and `GOOGLE_TRANSLATE_API_KEY`
   (auto-translates title/summary/description/lineup to the other language
   when a host publishes; events just display in whichever language they
   were written in when unset).
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

Nightly database + uploads backup (run from cron on the host):

```bash
./scripts/backup.sh            # writes ./backups/ievent-YYYYmmdd-HHMM.dump and uploads-YYYYmmdd-HHMM.tgz, keeps 14 of each
```
Cron example: `15 2 * * * cd /opt/ievent && ./scripts/backup.sh >> backups/backup.log 2>&1`

Off-site copy: set `BACKUP_REMOTE` in the cron environment (an rclone remote such
as `b2:ievent-backups` or an scp target such as `backup@host:/srv/ievent`) and the
script mirrors each new file there. A backup that only lives on the same disk as
the database does not count as a backup.

Restore the database (the dump is in pg_dump custom format, so it replays cleanly
over a live database; stop the app first so nothing writes during the restore):

```bash
docker compose -f docker-compose.staging.yml stop app
docker compose -f docker-compose.staging.yml exec -T db \
  pg_restore -U ievent -d ievent --clean --if-exists --no-owner < backups/<file>.dump
docker compose -f docker-compose.staging.yml start app
```

Restore the uploads volume:
`docker run --rm -v ievent_ievent_uploads:/data -v $PWD/backups:/in alpine sh -c "cd /data && tar xzf /in/<file>.tgz"`

Test a restore on a throwaway machine before go-live and again every few months.

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
