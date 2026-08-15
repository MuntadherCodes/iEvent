// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * iEvent E2E configuration.
 *
 * Runs against an already-started stack (docker compose up -d --build).
 * Override the target with BASE_URL, e.g. BASE_URL=http://localhost:8080.
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: 1,
  fullyParallel: false,
  workers: 1,
  reporter: [['line'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
