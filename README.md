# iEvent

Events discovery, ticketing and host management for Iraq — [ievent.iq](https://ievent.iq).
Inspired by Humanitix and Eventbrite, built for the Iraqi market (IQD pricing, Arabic/Kurdish-ready).

## Quickstart

```bash
git clone <repo-url> ievent && cd ievent
cp .env.example .env
# edit .env and set POSTGRES_PASSWORD (required — compose refuses to start without it)
docker compose up -d --build app
```

Then open:

| URL | What |
|---|---|
| http://localhost:8080 | the iEvent app |
| http://localhost:8080/actuator/health | health probe (used by the compose healthcheck and CI) |
| http://localhost:8025 | Mailpit — every email the app sends, in dev |

Seeding is controlled from `.env`:

- `SEED_DEMO=true` (default) creates the demo organizer **Zain Events Co.**, demo user accounts and 8 showcase events (idempotent).
- `SEED_SCALE=N` additionally generates N synthetic "Scale Event #…" events for production-like volume (`0` = off; CI uses `300`).

## Stack

- **Spring Boot 3.4** (Java 21, Maven) — web + Thymeleaf server-rendered UI, Spring Security (form login), Spring Data JPA, validation, mail, actuator
- **PostgreSQL 16** with **Flyway** migrations (`src/main/resources/db/migration`)
- **Docker Compose** services: `db` (postgres), `mailpit` (dev SMTP + web UI), `app`
- **Playwright** end-to-end suite in `e2e/` (Chromium, run against the compose stack)

## Project layout

```
src/main/java/iq/ievent/
  config/     Spring Security configuration
  domain/     JPA entities (User, Organization, Event, TicketType)
  repo/       Spring Data repositories
  seed/       SeedRunner — demo + scale data seeding
  service/    catalog, users, formatting
  web/        controllers (pages, auth) and view DTOs
src/main/resources/
  db/migration/   Flyway SQL migrations
  templates/      Thymeleaf templates
  static/         CSS/JS/images
src/test/java/iq/ievent/SmokeTest.java   MockMvc smoke tests
e2e/            Playwright walkthrough (config, tests, package.json)
.github/workflows/ci.yml                 CI pipeline
```

## CI

`.github/workflows/ci.yml` runs on every push to `main` (and on manual dispatch), in two jobs:

1. **build-test** — spins up a `postgres:16` service, runs `mvn -B verify` (unit + MockMvc smoke tests with the demo seed).
2. **compose-e2e** — builds and starts the full docker-compose stack (`SEED_DEMO=true`, `SEED_SCALE=300`), waits for `/actuator/health` to report `UP`, then runs the Playwright walkthrough in `e2e/`.

**Where the reports live:** each job always publishes a plain-text report as the body of a GitHub Release —

- release tagged **`ci-report`** → build-test (surefire summary + last 200 lines of build output)
- release tagged **`ci-report-compose`** → compose-e2e (Playwright results + last 300 lines of app logs; failure screenshots attached as release assets)

The tags are force-moved on every run, so each release always reflects the latest run. The release body is capped (GitHub limits notes to 125,000 characters), so it carries the summary, the failed-test lines and the tail; the complete `report-full.txt`, the surefire reports and the Playwright HTML report/test-results are uploaded as workflow artifacts (`build-test-report`, `playwright-results`). compose-e2e runs even when build-test is red, so both reports always move to the latest commit. Both jobs set `RATE_LIMIT_ENABLED=false`.

### Running the e2e walkthrough locally

```bash
# .env: SEED_DEMO=true, SEED_SCALE=300, RATE_LIMIT_ENABLED=false (the suite signs in dozens of times a minute)
docker compose up -d --build
cd e2e && npm ci && npx playwright install chromium && npx playwright test
```

## Bundle workflow

When the repo is shared as a git bundle instead of a hosted remote, pull updates with:

```bash
git pull <path-to-bundle-file> main
```

## Deployment

Not yet — see [DEPLOY.md](DEPLOY.md).

## Rebuilding the CSS

The site uses a compiled Tailwind stylesheet committed at `src/main/resources/static/css/app.css`.
After changing templates, rebuild it with:

```bash
cd frontend
npx tailwindcss@3.4.17 -c tailwind.config.js -i input.css -o ../src/main/resources/static/css/app.css --minify
```
