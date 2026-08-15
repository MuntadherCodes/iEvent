// @ts-check
/**
 * iEvent end-to-end walkthrough.
 *
 * Assumes the docker-compose stack is up with the demo seed loaded:
 *   SEED_DEMO=true  -> organizer "Zain Events Co." + 8 showcase events
 *                      (incl. "Baghdad Nights Music Festival",
 *                       slug baghdad-nights-music-festival, GA 35,000 IQD,
 *                       Early Bird sold out, booking fee 1,500 IQD/ticket)
 *   SEED_SCALE=300  -> 300 synthetic "Scale Event #N — <City>" events
 *
 * Selectors lean on semantic anchors (roles, accessible names, visible text)
 * taken from the v3 wireframes, since those are what the templates implement.
 */
const { test, expect } = require('@playwright/test');

test.describe.configure({ mode: 'serial' });

const RUN_ID = process.env.GITHUB_RUN_ID || String(Date.now());
const E2E_EMAIL = `e2e+${RUN_ID}@test.iq`;
const E2E_NAME = 'E2E Tester';
const E2E_PASSWORD = 'E2ePassw0rd!';

function log(msg) {
  // eslint-disable-next-line no-console
  console.log(`\n[walkthrough] ${msg}`);
}

/** On failure, dump where we were and what the page showed. */
async function logFail(page) {
  try {
    // eslint-disable-next-line no-console
    console.error(`[walkthrough][FAIL] current URL: ${page.url()}`);
    const body = await page.locator('body').innerText({ timeout: 5000 });
    // eslint-disable-next-line no-console
    console.error(
      `[walkthrough][FAIL] visible body text (first 2000 chars):\n${body.slice(0, 2000)}`
    );
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(`[walkthrough][FAIL] could not capture page state: ${err.message}`);
  }
}

test.afterEach(async ({ page }, testInfo) => {
  if (testInfo.status !== testInfo.expectedStatus) {
    await logFail(page);
  }
});

test('a. health endpoint reports UP', async ({ request }) => {
  log('GET /actuator/health');
  const res = await request.get('/actuator/health');
  expect(res.status(), 'health endpoint should answer 200').toBe(200);
  const body = await res.json();
  log(`health payload: ${JSON.stringify(body)}`);
  expect(body.status).toBe('UP');
});

test('b. home page shows hero, seeded event, trending and Sign in', async ({ page }) => {
  log('opening home page /');
  await page.goto('/');

  log('hero h1 should be visible');
  await expect(page.locator('h1').first()).toBeVisible();

  log('seeded "Baghdad Nights Music Festival" card should be on the home page');
  await expect(page.getByText('Baghdad Nights Music Festival').first()).toBeVisible();

  log('a "Trending" section should be present');
  await expect(page.getByText(/Trending/i).first()).toBeVisible();

  log('header should offer "Sign in" for anonymous visitors');
  await expect(page.locator('header').getByText(/Sign in/i).first()).toBeVisible();
});

test('c. browse: scale volume, pagination, category / search / free filters', async ({ page }) => {
  log('navigating to browse via the header "Browse events" link');
  await page.goto('/');
  await page
    .locator('header')
    .getByRole('link', { name: /Browse events/i })
    .first()
    .click();
  await expect(page).toHaveURL(/\/browse/);

  log('with SEED_SCALE=300 the first page should be full: >= 12 event cards');
  const cards = page.locator('main a[href^="/events/"]');
  await expect
    .poll(async () => cards.count(), { message: 'expected at least 12 event cards' })
    .toBeGreaterThanOrEqual(12);

  log('pagination "Next" control should be visible');
  await expect(
    page
      .getByRole('link', { name: /next/i })
      .or(page.getByRole('button', { name: /next/i }))
      .first()
  ).toBeVisible();

  log('clicking the Music category chip should filter to category=MUSIC');
  const musicChip = page
    .locator('a[href*="category=MUSIC"]')
    .or(page.getByRole('link', { name: /^Music$/ }))
    .first();
  await musicChip.click();
  await expect(page).toHaveURL(/category=MUSIC/);

  log('Baghdad Nights (MUSIC, starts in ~5 days) should be on page 1 of the Music filter');
  await expect(page.getByText('Baghdad Nights Music Festival').first()).toBeVisible();

  log('searching q=Erbil Tech should surface the Erbil Tech Summit');
  await page.goto('/browse?q=Erbil%20Tech');
  await expect(page.getByText('Erbil Tech Summit 2026').first()).toBeVisible();

  log('free=true filter should be reachable and list free events');
  await page.goto('/browse?free=true');
  await expect(page).toHaveURL(/free=true/);
  await expect
    .poll(async () => page.locator('main a[href^="/events/"]').count(), {
      message: 'free filter should return event cards',
    })
    .toBeGreaterThan(0);

  // With 300 scale events (~60 free ones sorted by start date) the Basra
  // carnival is not guaranteed to land on page 1 of the bare free filter,
  // so pin it down with a search term while keeping free=true.
  log('free=true + q=Basra Corniche should show "Basra Corniche Food Carnival"');
  await page.goto('/browse?free=true&q=Basra%20Corniche');
  await expect(page.getByText('Basra Corniche Food Carnival').first()).toBeVisible();
});

test('d. event detail: tickets, sold out, organizer, related, stepper total', async ({ page }) => {
  log('opening Baghdad Nights from the browse grid');
  await page.goto('/browse?q=Baghdad%20Nights');
  await page
    .locator('a[href*="baghdad-nights-music-festival"]')
    .first()
    .click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival/);

  log('h1 should carry the event title');
  await expect(page.locator('h1')).toContainText('Baghdad Nights Music Festival');

  log('a date line should be visible (month name or weekday near the title)');
  await expect(
    page
      .getByText(/\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\b/i)
      .first()
  ).toBeVisible();

  log('"General Admission" at 35,000 IQD should be listed');
  await expect(page.getByText('General Admission').first()).toBeVisible();
  await expect(page.getByText(/35,000\s*IQD/).first()).toBeVisible();

  log('"Early Bird" should be marked Sold out');
  await expect(page.getByText('Early Bird').first()).toBeVisible();
  await expect(page.getByText(/Sold out/i).first()).toBeVisible();

  log('organizer "Zain Events Co." should be credited');
  await expect(page.getByText('Zain Events Co.').first()).toBeVisible();

  log('related events ("More like this") grid should be non-empty');
  const relatedHeading = page.getByText(/More like this|Related/i).first();
  await expect(relatedHeading).toBeVisible();
  await expect
    .poll(
      async () =>
        page
          .locator('main a[href^="/events/"]:not([href*="baghdad-nights-music-festival"])')
          .count(),
      { message: 'expected related event cards' }
    )
    .toBeGreaterThan(0);

  log('ticket stepper: +1 General Admission should total 36,500 IQD (35,000 + 1,500 fee)');
  const plusButton = page
    .getByRole('button', { name: /add one general admission/i })
    .or(
      page
        .locator('div, li')
        .filter({ hasText: /^General Admission/ })
        .locator('button')
        .last()
    )
    .first();
  await plusButton.click();
  await expect(page.getByText(/36,500\s*IQD/).first()).toBeVisible();
});

test('e. auth: register, login shows initials, logout restores Sign in', async ({ page }) => {
  log(`registering ${E2E_EMAIL}`);
  await page.goto('/auth/register');
  await page.locator('input[name="fullName"]').fill(E2E_NAME);
  await page.locator('input[name="email"]').fill(E2E_EMAIL);
  await page.locator('input[name="password"]').fill(E2E_PASSWORD);
  await page
    .locator('form')
    .filter({ has: page.locator('input[name="fullName"]') })
    .locator('button[type="submit"], input[type="submit"]')
    .first()
    .click();

  log('registration should redirect to /auth/login?registered with a success banner');
  await expect(page).toHaveURL(/\/auth\/login\?registered/);
  await expect(page.getByText(/(account|registered|created|success)/i).first()).toBeVisible();

  log('logging in with the new account');
  await page.locator('input[name="email"]').fill(E2E_EMAIL);
  await page.locator('input[name="password"]').fill(E2E_PASSWORD);
  await page
    .locator('form')
    .filter({ has: page.locator('input[name="password"]') })
    .locator('button[type="submit"], input[type="submit"]')
    .first()
    .click();

  log('after login we should land on home and the header should show user initials');
  await expect(page).toHaveURL(/\/(\?.*)?$/);
  const header = page.locator('header');
  await expect(header.getByRole('link', { name: /Sign in/i })).toHaveCount(0);
  // "E2E Tester" -> initials "ET"
  await expect(header.getByText('ET', { exact: true }).first()).toBeVisible();

  log('signing out via the header sign-out form');
  const logoutForm = page.locator('form[action*="/auth/logout"]');
  if (!(await logoutForm.first().isVisible().catch(() => false))) {
    log('logout form not directly visible — opening the account menu first');
    await header.getByText('ET', { exact: true }).first().click();
  }
  await logoutForm
    .locator('button, [type="submit"]')
    .first()
    .click();

  log('after logout the header should offer "Sign in" again');
  await expect(page.locator('header').getByText(/Sign in/i).first()).toBeVisible();
});

test('f. unknown event slug returns a branded 404', async ({ page }) => {
  log('opening /events/does-not-exist');
  const response = await page.goto('/events/does-not-exist');
  expect(response, 'navigation should produce a response').toBeTruthy();
  expect(response.status(), 'unknown slug should be a 404').toBe(404);

  log('error page should be branded (iEvent look, not a bare Whitelabel page)');
  await expect(
    page.getByText(/(couldn.t find|not found|doesn.t exist|404)/i).first()
  ).toBeVisible();
});
