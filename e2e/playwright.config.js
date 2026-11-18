// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * iEvent E2E configuration.
 *
 * Runs against an already-started stack (docker compose up -d --build).
 * Override the target with BASE_URL, e.g. BASE_URL=http://localhost:8080.
 *
 * Email assertions talk to the Mailpit HTTP API (compose maps it to the host
 * on ${MAILPIT_UI_PORT:-8025}). Override with MAILPIT_API, e.g.
 * MAILPIT_API=http://localhost:8025. In CI the mail checks are mandatory;
 * local runs without Mailpit skip them with a test annotation.
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 30_000,
  // No retries. The walkthrough is ONE serial chain whose tests build on each other's
  // data (test e registers a user, later tests sign in as that user, and so on), and a
  // retry starts the whole chain again from test a. The second pass can never get past
  // test e, because its user already exists, so a retry cannot rescue anything: it only
  // adds a "flaky e" to the report and hides which test really failed.
  retries: 0,
  fullyParallel: false,
  workers: 1,
  reporter: [['line'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
