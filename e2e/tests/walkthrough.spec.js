// @ts-check
/**
 * iEvent end-to-end walkthrough (serial).
 *
 * Assumes the docker-compose stack is up with the demo seed loaded:
 *   SEED_DEMO=true  -> organizer "Zain Events Co." (@zainevents, direct payments
 *                      ENABLED; round 8 seeds one payment method "ZainCash wallet"
 *                      0770 123 4567; host login
 *                      fahad@zainevents.iq / Password123!) + 8 showcase events:
 *                      - "Baghdad Nights Music Festival" (baghdad-nights-music-festival)
 *                        GA 35,000 IQD on sale, Early Bird sold out
 *                      - "Startup Mixer Baghdad" (startup-mixer-baghdad) free "RSVP" ticket
 *                      Booking fee: 1,500 IQD per PAID ticket.
 *   SEED_SCALE=300  -> 300 synthetic "Scale Event #N — <City>" events
 *
 * Selectors are bound to the actual Thymeleaf templates in
 * src/main/resources/templates (aria-labels, visible copy, form field names).
 */
const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');

test.describe.configure({ mode: 'serial' });

// ---------- round 11: Arabic-first URLs ----------
// Bare URLs now serve ARABIC (RTL); English lives under /en/** (a servlet
// filter strips the prefix). The walkthrough asserts the character-identical
// ENGLISH copy, so every test context gets the lang=en cookie up front: the
// first bare page GET 302s onto /en/... and Thymeleaf keeps every rendered
// link /en-prefixed from there. Controller redirects land bare and bounce back
// to /en via the cookie on the next GET — page.goto()/clicks follow both hops.
// API/asset/binary paths (/api, /js, /css, /media, /actuator, *.png, *.pdf,
// *.ics, *.csv) are never language-redirected and keep working on bare paths.
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

test.beforeEach(async ({ context }) => {
  await context.addCookies([{ name: 'lang', value: 'en', url: BASE_URL }]);
});

// Public event-card links render as /en/events/... in English (and /events/...
// on the Arabic side) — selectors must tolerate both.
const EVENT_CARD_LINKS = 'main a[href^="/en/events/"], main a[href^="/events/"]';

const RUN_ID = process.env.GITHUB_RUN_ID || String(Date.now());
const E2E_EMAIL = `e2e+${RUN_ID}@test.iq`;
const E2E_NAME = 'E2E Tester';
const E2E_PASSWORD = 'E2ePassw0rd!';

// Buyer used for the RSVP / direct-transfer / favorites journeys (h–l, o, t).
const BUYER_EMAIL = `e2e-buyer+${RUN_ID}@test.iq`;
const BUYER_NAME = 'E2E Buyer';
const BUYER_PASSWORD = 'BuyerPassw0rd!';
const BUYER_NEW_PASSWORD = 'BuyerNewPass1!';
// Mutable: test t changes the buyer's password.
let buyerPassword = BUYER_PASSWORD;

// Brand-new user who becomes a host in test m.
const HOST2_EMAIL = `e2e-host+${RUN_ID}@test.iq`;
const HOST2_NAME = 'E2E Host';
const HOST2_PASSWORD = 'HostPassw0rd!';

// Throwaway user for the password-reset email round-trip (test af).
const RESET_EMAIL = `e2e-reset+${RUN_ID}@test.iq`;
const RESET_NAME = 'E2E Reset';
const RESET_PASSWORD = 'ResetPassw0rd!';
const RESET_NEW_PASSWORD = 'ResetNewPass1!';

// Throwaway buyer whose order gets REFUNDED (test an).
const REFUND_EMAIL = `e2e-refund+${RUN_ID}@test.iq`;
const REFUND_NAME = 'E2E Refundee';
const REFUND_PASSWORD = 'RefundPassw0rd!';

// Unique subject for the followers email campaign (test ao).
const CAMPAIGN_SUBJECT = `E2E followers update ${RUN_ID}`;

// Fresh buyer whose direct-transfer order drives the notification-center flow (tests aq–as).
const NOTIF_EMAIL = `e2e-notif+${RUN_ID}@test.iq`;
const NOTIF_NAME = 'E2E Notifier';
const NOTIF_PASSWORD = 'NotifPassw0rd!';

// ---------- round 10 users ----------
// Buyer who signs in MID-CHECKOUT via the #11 login-return continuation (test bb).
const LR_EMAIL = `e2e-return+${RUN_ID}@test.iq`;
const LR_NAME = 'E2E Returner';
const LR_PASSWORD = 'ReturnPassw0rd!';

// Fresh host whose brand-new org drives the To-do dismiss flow (test be).
const HOST3_EMAIL = `e2e-host3+${RUN_ID}@test.iq`;
const HOST3_NAME = 'E2E Dismisser';
const HOST3_PASSWORD = 'Host3Passw0rd!';

// Buyer whose pending order proves the open-by-default order detail (test bf).
const PEND_EMAIL = `e2e-open+${RUN_ID}@test.iq`;
const PEND_NAME = 'E2E OpenDetail';
const PEND_PASSWORD = 'OpenPassw0rd!';

// ---------- round 12 users ----------
// Fresh host whose brand-new org proves the checklist LIFECYCLE (test bs):
// round 12 shows the To-do card only until the org's FIRST event goes live.
const HOST4_EMAIL = `e2e-host4+${RUN_ID}@test.iq`;
const HOST4_NAME = 'E2E Lifecycle';
const HOST4_PASSWORD = 'Host4Passw0rd!';

// Online event created by HOST2 in test av. The join URL must NEVER appear in
// public HTML — only on confirmed orders/tickets.
const ONLINE_EVENT_TITLE = 'E2E Online Meetup';
const ONLINE_URL = 'https://meet.example.com/x';
let onlineEventPublicHref = ''; // captured in av, reused in aw

// Seeded demo host with direct payments enabled.
const DEMO_HOST_EMAIL = 'fahad@zainevents.iq';
const DEMO_HOST_PASSWORD = 'Password123!';

// Mailpit HTTP API (compose exposes the UI/API on the runner host, default :8025).
// Override with MAILPIT_API for non-standard setups. In CI mail assertions are
// mandatory; in local runs without Mailpit they are skipped with an annotation.
const MAILPIT_API = process.env.MAILPIT_API || 'http://localhost:8025';
const IS_CI = !!process.env.CI || !!process.env.GITHUB_RUN_ID;

// 1x1 transparent PNG, generated at runtime as the transfer-receipt fixture.
const PNG_1PX_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const FIXTURES_DIR = path.join(__dirname, '..', 'fixtures');
const RECEIPT_PNG = path.join(FIXTURES_DIR, 'receipt.png');

// State shared across the serial walkthrough.
let rsvpTicketCode = ''; // Startup Mixer ticket code from test h
let rsvpOrderCode = ''; // Startup Mixer order code from test h
let baghdadHostEventId = ''; // /host/events/{id} for Baghdad Nights (test k)

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

test.beforeAll(() => {
  fs.mkdirSync(FIXTURES_DIR, { recursive: true });
  fs.writeFileSync(RECEIPT_PNG, Buffer.from(PNG_1PX_BASE64, 'base64'));
});

// ---------- helpers ----------

async function registerUser(page, { name, email, password }) {
  log(`registering ${email}`);
  await page.goto('/auth/register');
  await page.locator('input[name="fullName"]').fill(name);
  await page.locator('input[name="email"]').fill(email);
  await page.locator('input[name="password"]').fill(password);
  // Registration requires accepting the Terms (enforced server-side too).
  await page.locator('input[name="terms"]').check();
  await page
    .locator('form')
    .filter({ has: page.locator('input[name="fullName"]') })
    .locator('button[type="submit"], input[type="submit"]')
    .first()
    .click();
  await expect(page).toHaveURL(/\/auth\/login\?registered/);
}

async function login(page, email, password) {
  log(`logging in as ${email}`);
  await page.goto('/auth/login');
  await page.locator('input[name="email"]').fill(email);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).not.toHaveURL(/\/auth\/login/);
}

async function signOut(page) {
  log('signing out');
  await page.goto('/');
  const logoutForm = page.locator('header form[action*="/auth/logout"]');
  if (await logoutForm.first().isVisible().catch(() => false)) {
    await logoutForm.locator('button, [type="submit"]').first().click();
    await expect(page.locator('header').getByText(/Sign in/i).first()).toBeVisible();
  } else {
    log('already signed out');
  }
}

/**
 * Drives wizard step 1 (basics) on /host/events/new and advances to step 2.
 * The round 10 tests create several HOST2 events — this keeps them uniform.
 */
async function wizardBasics(page, { title, daysAhead, start, venue, city, category }) {
  log(`wizard basics: "${title}" (+${daysAhead}d ${start}, ${venue}, ${city}, ${category})`);
  await page.goto('/host/events/new');
  await expect(page.locator('#stepIndicator')).toHaveText('Step 1 of 5');
  await page.locator('#ev-title').fill(title);
  const date = new Date(Date.now() + daysAhead * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  await page.locator('#ev-date').fill(date);
  await page.locator('#ev-start').fill(start);
  await page.locator('#ev-venue').fill(venue);
  await page.locator('#ev-city').selectOption(city);
  await page.locator('#ev-cat').selectOption(category);
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 2 of 5');
}

/** True when the Mailpit API answers at MAILPIT_API. */
async function mailpitAvailable(request) {
  try {
    const res = await request.get(`${MAILPIT_API}/api/v1/messages?limit=1`);
    return res.ok();
  } catch {
    return false;
  }
}

/**
 * Poll the Mailpit API (up to timeoutMs) for a message whose To contains `to`
 * and whose Subject contains `subjectPart`. Returns the message or null.
 */
async function mailpitFind(request, { to, subjectPart }, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await request.get(`${MAILPIT_API}/api/v1/messages?limit=50`);
      if (res.ok()) {
        const data = await res.json();
        const hit = (data.messages || []).find(
          (m) =>
            (m.To || []).some(
              (a) => (a.Address || '').toLowerCase() === to.toLowerCase()
            ) && (m.Subject || '').includes(subjectPart)
        );
        if (hit) return hit;
      }
    } catch {
      // Mailpit briefly unreachable — keep polling.
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return null;
}

/** Full body (HTML + text) of one Mailpit message, for link extraction. */
async function mailpitMessageBody(request, id) {
  const res = await request.get(`${MAILPIT_API}/api/v1/message/${id}`);
  if (!res.ok()) return '';
  const data = await res.json();
  return `${data.HTML || ''}\n${data.Text || ''}`;
}

/**
 * Assert a mail landed in Mailpit. CI is the referee: there the assertion is
 * mandatory. Local runs without Mailpit skip with an annotation instead.
 */
async function assertMail(request, testInfo, { to, subjectPart }) {
  if (!(await mailpitAvailable(request))) {
    if (IS_CI) {
      throw new Error(
        `Mailpit API not reachable at ${MAILPIT_API} — email delivery cannot be verified in CI`
      );
    }
    testInfo.annotations.push({
      type: 'mail-check-skipped',
      description: `Mailpit not available at ${MAILPIT_API} — skipped assertion for "${subjectPart}"`,
    });
    log(`Mailpit unavailable at ${MAILPIT_API} — skipping mail assertion ("${subjectPart}")`);
    return;
  }
  log(`mailpit: waiting for mail to ${to} with subject containing "${subjectPart}"`);
  const hit = await mailpitFind(request, { to, subjectPart });
  expect(
    hit,
    `expected a Mailpit message to ${to} with subject containing "${subjectPart}"`
  ).toBeTruthy();
  log(`mailpit: found "${hit.Subject}"`);
}

// ---------- walkthrough ----------

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

  log('the "Trending across Iraq" section should be present');
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
  const cards = page.locator(EVENT_CARD_LINKS);
  await expect
    .poll(async () => cards.count(), { message: 'expected at least 12 event cards' })
    .toBeGreaterThanOrEqual(12);

  log('pagination "Next page" arrow should be visible');
  await expect(page.getByRole('link', { name: 'Next page' }).first()).toBeVisible();

  log('clicking the "Music" category chip should filter to category=MUSIC');
  await page.locator('a[href*="category=MUSIC"]').first().click();
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
    .poll(async () => page.locator(EVENT_CARD_LINKS).count(), {
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
  await page.locator('a[href*="baghdad-nights-music-festival"]').first().click();
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

  log('"More events like this" grid should be non-empty');
  await expect(page.getByText(/More events like this/i).first()).toBeVisible();
  await expect
    .poll(
      async () =>
        page
          .locator(
            'main a[href^="/en/events/"]:not([href*="baghdad-nights-music-festival"]), ' +
              'main a[href^="/events/"]:not([href*="baghdad-nights-music-festival"])'
          )
          .count(),
      { message: 'expected related event cards' }
    )
    .toBeGreaterThan(0);

  log('round 9: GA (first buyable type) defaults to qty 1 — rail total starts at 36,500 IQD (35,000 + 1,500 fee)');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await expect(page.locator('#railTotal')).toHaveText(/36,500\s*IQD/);

  log('ticket stepper: +1 General Admission on top of the default → 2 tickets, 73,000 IQD');
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
  await expect(page.locator('.qty-input').first()).toHaveValue('2');
  await expect(page.locator('#railTotal')).toHaveText(/73,000\s*IQD/);

  log('− returns to the single-ticket default');
  await page.getByRole('button', { name: 'Remove one General Admission ticket' }).click();
  await expect(page.locator('#railTotal')).toHaveText(/36,500\s*IQD/);
});

test('e. auth: register, login shows initials, logout restores Sign in', async ({ page }) => {
  await registerUser(page, { name: E2E_NAME, email: E2E_EMAIL, password: E2E_PASSWORD });

  log('registration success banner should be visible on the login page');
  await expect(page.getByText(/(account|registered|created|success)/i).first()).toBeVisible();

  await login(page, E2E_EMAIL, E2E_PASSWORD);

  log('after login the header should show user initials instead of "Sign in"');
  const header = page.locator('header');
  await expect(header.getByRole('link', { name: /Sign in/i })).toHaveCount(0);
  // "E2E Tester" -> initials "ET"
  await expect(header.getByText('ET', { exact: true }).first()).toBeVisible();

  log('signing out via the header sign-out form');
  await page
    .locator('header form[action*="/auth/logout"]')
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

  log('error page should be the branded iEvent error page');
  await expect(
    page.getByRole('heading', { name: /Something went wrong/i })
  ).toBeVisible();
  await expect(page.getByText(/Error 404/i).first()).toBeVisible();
});

test('g. REGRESSION: login/register render cleanly in a cookie-less context', async ({ browser }) => {
  // Guards the CSRF-after-commit crash: a visitor with no session opening
  // /auth/login or /auth/register must get a fully rendered form, never the
  // "Something went wrong" error fragment appended mid-render.
  const context = await browser.newContext();
  const freshPage = await context.newPage();
  try {
    // Cookie-less contexts land on the ARABIC side at bare URLs, so the English
    // assertions below target /en explicitly (same CSRF-after-commit code path —
    // the filter strips the prefix before rendering).
    for (const url of ['/en/auth/login', '/en/auth/register']) {
      log(`fresh context (no cookies) → GET ${url}`);
      const response = await freshPage.goto(url);
      expect(response.status(), `${url} should answer 200`).toBe(200);
      await expect(
        freshPage.locator('input[name="password"]'),
        `${url} should render its password field`
      ).toBeVisible();
      const body = await freshPage.locator('body').innerText();
      expect(
        body,
        `${url} must not contain the error-page marker "Something went wrong"`
      ).not.toContain('Something went wrong');
    }

    log('Google OAuth is OFF in CI — the Google button must be a DISABLED button, not a link');
    await freshPage.goto('/en/auth/login');
    await expect(
      freshPage.getByRole('button', { name: /Continue with Google/ })
    ).toBeDisabled();
    await expect(freshPage.locator('a[href*="/oauth2/authorization/google"]')).toHaveCount(0);
  } finally {
    await context.close();
  }
});

test('h. free RSVP flow: checkout, confirmation, my tickets, public ticket status', async ({ page, request }, testInfo) => {
  await registerUser(page, { name: BUYER_NAME, email: BUYER_EMAIL, password: BUYER_PASSWORD });
  await login(page, BUYER_EMAIL, buyerPassword);

  log('a plain (non-host) user must NOT be redirected to /host after login');
  await expect(page).not.toHaveURL(/\/host/);
  await expect(
    page.locator('header').getByRole('link', { name: 'Host an event' })
  ).toBeVisible();

  log('opening Startup Mixer Baghdad (free RSVP ticket)');
  await page.goto('/events/startup-mixer-baghdad');
  await expect(page.locator('h1')).toContainText('Startup Mixer Baghdad');

  log('round 9: the RSVP stepper already defaults to 1 — submit the rail form as-is ("Get tickets")');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/startup-mixer-baghdad\/checkout/);

  log('buyer details should be prefilled from the account');
  await expect(page.locator('#buyerName')).toHaveValue(BUYER_NAME);
  await expect(page.locator('#buyerEmail')).toHaveValue(BUYER_EMAIL);

  log('holder row: name + optional email inputs and "Copy from buyer details"');
  const holderNameInput = page.locator('input[name="holderName"]').first();
  const holderEmailInput = page.locator('input[name="holderEmail"]').first();
  await expect(holderNameInput).toBeVisible();
  await expect(holderEmailInput).toBeVisible();
  await page.locator('input.copy-buyer').first().check();
  await expect(holderNameInput).toHaveValue(BUYER_NAME);
  await expect(holderEmailInput).toHaveValue(BUYER_EMAIL);

  log('"Keep me updated" checkbox is checked by default');
  await expect(page.locator('input[name="keepUpdated"]')).toBeChecked();

  log('free registration note and "Register free" submit label expected');
  await expect(page.getByText(/Free registration — tickets are issued instantly/)).toBeVisible();
  await expect(page.locator('#submitLabel')).toHaveText('Register free');

  log('submitting the free order');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);

  log('confirmation: "You\'re going!" hero + one ticket stub with a QR svg');
  await expect(page.getByRole('heading', { name: /You're going!/ })).toBeVisible();
  await expect(page.locator('.qr-box svg').first()).toBeVisible();
  rsvpOrderCode = page.url().match(/\/orders\/([A-Z0-9-]+)/)[1];
  log(`captured order code: ${rsvpOrderCode}`);

  log('confirmed orders offer "Download all tickets (PDF)"');
  await expect(
    page.getByRole('link', { name: /Download all tickets \(PDF\)/ })
  ).toBeVisible();

  rsvpTicketCode = (
    await page
      .locator('dl > div')
      .filter({ hasText: 'Ticket code' })
      .locator('dd')
      .first()
      .innerText()
  ).trim();
  log(`captured RSVP ticket code: ${rsvpTicketCode}`);
  expect(rsvpTicketCode.length).toBeGreaterThan(5);

  log('/me/tickets should list the Startup Mixer ticket');
  await page.goto('/me/tickets');
  await expect(page.getByRole('link', { name: 'Startup Mixer Baghdad' })).toBeVisible();
  await expect(page.locator('.qr-box svg').first()).toBeVisible();

  log(`public ticket status /t/${rsvpTicketCode} should show "Valid ticket"`);
  await page.goto(`/t/${rsvpTicketCode}`);
  await expect(page.getByText('Valid ticket')).toBeVisible();

  log('EMAIL: the ticket email for the free order must land in Mailpit');
  await assertMail(request, testInfo, {
    to: BUYER_EMAIL,
    subjectPart: 'Your tickets for Startup Mixer Baghdad',
  });
});

test('i. direct-transfer flow: card number, reference, receipt upload, pending order', async ({ page, request }, testInfo) => {
  log('buyer opens Baghdad Nights — GA already defaults to 1 (round 9), total 36,500 IQD');
  await page.goto('/auth/login');
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await expect(page.locator('#railTotal')).toHaveText(/36,500\s*IQD/);
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);

  log('direct-transfer panel is now a method picker (round 8) — seeded "ZainCash wallet" card');
  await expect(page.getByText('Direct transfer to organizer')).toBeVisible();
  // Fresh databases seed one enabled method for @zainevents: "ZainCash wallet"
  // (0770 123 4567). The legacy single-card block only renders when no methods exist.
  const methodRadios = page.locator('input[name="paymentMethodLabel"]');
  await expect
    .poll(async () => methodRadios.count(), { message: 'expected at least one payment-method radio' })
    .toBeGreaterThan(0);
  await expect(methodRadios.first()).toBeChecked();
  await expect(page.getByText('ZainCash wallet').first()).toBeVisible();
  await expect(page.getByText('0770 123 4567').first()).toBeVisible();
  await expect(page.locator('.copy-acct').first()).toBeVisible();

  log('filling transfer reference and attaching the receipt fixture');
  await page.locator('#transferReference').fill('E2E-REF-1');
  await page.locator('#receiptInput').setInputFiles(RECEIPT_PNG);
  await expect(page.getByText(/receipt\.png attached/)).toBeVisible();

  log('submitting the order for confirmation');
  await expect(page.locator('#submitLabel')).toHaveText('Submit order for confirmation');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);

  log('confirmation should be the amber PENDING state with total 36,500 IQD and no tickets');
  await expect(
    page.getByRole('heading', { name: /pending organizer confirmation/i })
  ).toBeVisible();
  await expect(page.getByText('Pending confirmation').first()).toBeVisible();
  await expect(page.getByText(/36,500\s*IQD/).first()).toBeVisible();
  await expect(page.locator('.qr-box')).toHaveCount(0);

  log('pending orders must NOT offer the tickets PDF download');
  await expect(page.getByRole('link', { name: /Download all tickets \(PDF\)/ })).toHaveCount(0);

  log('EMAIL: the "Order received" pending mail must land in Mailpit');
  await assertMail(request, testInfo, {
    to: BUYER_EMAIL,
    subjectPart: 'Order received',
  });
});

test('j. host approves the direct-transfer order; buyer receives the ticket', async ({ page, request }, testInfo) => {
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('HOST users with no deep-link land on /host after login (HostAwareSuccessHandler)');
  await expect(page).toHaveURL(/\/host(\/)?(\?.*)?$/);

  log('the public header shows "Host console" for host accounts');
  await page.goto('/');
  await expect(
    page.locator('header').getByRole('link', { name: 'Host console' })
  ).toBeVisible();

  log('/host dashboard should flag pending direct-transfer orders');
  await page.goto('/host');
  await expect(
    page.getByText(/direct-transfer orders waiting for your confirmation/).first()
  ).toBeVisible();

  log('opening the pending orders queue');
  await page.goto('/host/orders?status=pending');
  const row = page.locator('tr').filter({ hasText: BUYER_EMAIL }).first();
  await expect(row).toBeVisible();

  log('round 10: PENDING rows render with their detail panel OPEN — receipt preview + reference are visible without any click');
  await expect(row.getByText('Receipt', { exact: true })).toBeVisible();
  // Do NOT click .detail-toggle here: the pending detail is server-rendered open,
  // clicking would close it.
  const detailRow = row.locator('xpath=following-sibling::tr[1]');
  await expect(detailRow).toBeVisible();
  await expect(detailRow.getByText('E2E-REF-1')).toBeVisible();
  await expect(detailRow.locator('img[src*="/receipt"]')).toBeVisible();

  log('approving from the detail panel ("Approve & issue tickets" — round 10: the ONLY approve control) — the pending row must disappear');
  await expect(row.getByRole('button', { name: /Approve/ })).toHaveCount(0); // no row-level button anymore
  await detailRow.getByRole('button', { name: /Approve/ }).click();
  await expect(page).toHaveURL(/status=pending/);
  await expect(page.locator('tr').filter({ hasText: BUYER_EMAIL })).toHaveCount(0);

  log('buyer logs back in — /me/tickets now shows the Baghdad Nights ticket');
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto('/me/tickets');
  await expect(page.getByRole('link', { name: 'Baghdad Nights Music Festival' })).toBeVisible();

  log('EMAIL: approval must send the ticket email for Baghdad Nights');
  await assertMail(request, testInfo, {
    to: BUYER_EMAIL,
    subjectPart: 'Your tickets for Baghdad Nights Music Festival',
  });
});

test('k. host check-in: door list check-in + wrong-event code rejected', async ({ page }) => {
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('navigating /host/events → Baghdad Nights console');
  await page.goto('/host/events');
  await page.getByRole('link', { name: /Baghdad Nights Music Festival/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  baghdadHostEventId = page.url().match(/\/host\/events\/(\d+)/)[1];
  log(`Baghdad Nights host event id: ${baghdadHostEventId}`);

  log('opening the attendees quick-link from the event console');
  await page.locator('a[href*="/host/attendees?event="]').first().click();
  await expect(page).toHaveURL(/\/host\/attendees\?event=/);

  log(`searching the door list for "${BUYER_NAME}"`);
  await page.locator('input[name="q"]').fill(BUYER_NAME);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const attendeeRow = page.locator('tr').filter({ hasText: BUYER_NAME }).first();
  await expect(attendeeRow).toBeVisible();

  log('checking the buyer in');
  await attendeeRow.getByRole('button', { name: /Check in/ }).click();
  const checkedRow = page.locator('tr').filter({ hasText: BUYER_NAME }).first();
  await expect(checkedRow.getByRole('button', { name: 'Undo' })).toBeVisible();

  log('check-in screen: a ticket code from ANOTHER event must be rejected');
  await page.goto(`/host/checkin?event=${baghdadHostEventId}`);
  // Scope to the manual-entry form — the door list has its own "Check in" buttons.
  const manualForm = page.locator('form:has(input[name="code"])');
  await manualForm.locator('input[name="code"]').fill(rsvpTicketCode);
  await manualForm.getByRole('button', { name: /Check in/ }).click();
  // Round 10: the result banners carry ids (#ci-result-ok/#ci-result-err) that
  // also drive the Web Audio chime/buzz. The sound itself can't be asserted in
  // headless CI — the banner id is the testable contract.
  await expect(page.locator('#ci-result-err')).toBeVisible();
  await expect(page.getByText(/belongs to a different event/)).toBeVisible();
  await expect(page.getByText(/Startup Mixer Baghdad/).first()).toBeVisible();
});

test('l. likes and follow: save an event, see it in favorites, follow the organizer', async ({ page }) => {
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);

  log('saving Baghdad Nights via the rail Save button');
  await page.goto('/events/baghdad-nights-music-festival');
  const saveBtn = page.locator('button[form="likeForm"]');
  await expect(saveBtn).toContainText('Save');
  await saveBtn.click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival/);
  await expect(page.locator('button[form="likeForm"]')).toContainText('Saved');

  log('/favorites should show the saved card');
  await page.goto('/favorites');
  await expect(page.getByText('Baghdad Nights Music Festival').first()).toBeVisible();

  log('following @zainevents from the organizer page');
  await page.goto('/organizers/zainevents');
  await page.getByRole('button', { name: 'Follow', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Following' })).toBeVisible();
});

test('m. host onboarding: new user creates an org, publishes an event, it goes public', async ({ page }) => {
  await signOut(page);
  await registerUser(page, { name: HOST2_NAME, email: HOST2_EMAIL, password: HOST2_PASSWORD });
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('/host without an organization should redirect to the onboarding funnel (/host/start)');
  await page.goto('/host');
  await expect(page).toHaveURL(/\/host\/start/);
  await expect(
    page.getByRole('heading', { name: /What kind of events will you host/ })
  ).toBeVisible();

  log('clicking Next through the 4-step funnel to reach the org form');
  await page.locator('#nextBtn').click(); // step 2 · frequency
  await page.locator('#nextBtn').click(); // step 3 · city
  await page.locator('#nextBtn').click(); // step 4 · the real form
  await expect(page.locator('#orgName')).toBeVisible();

  log('creating organizer profile "E2E Test Events"');
  await page.locator('#orgName').fill('E2E Test Events');
  await page.getByRole('button', { name: /Create organizer profile/ }).click();

  // Regression (round 6): a brand-new host's EMPTY dashboard must render — the
  // sales-chart #aggregates.max SpEL crash 500'd every /host visit (CI run 6).
  log('fresh host dashboard renders without error');
  await expect(page).toHaveURL(/\/host/);
  await expect(page.locator('body')).not.toContainText('Something went wrong');
  await expect(page).toHaveURL(/\/host(\/)?$/);
  await expect(page.getByText('E2E Test Events').first()).toBeVisible();

  log('creating "E2E Concert Night" via the 5-step wizard at /host/events/new');
  await page.goto('/host/events/new');
  await expect(page.locator('#stepIndicator')).toHaveText('Step 1 of 5');

  log('wizard step 1 (basics): title, date, start time, city, category');
  await page.locator('#ev-title').fill('E2E Concert Night');
  const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  await page.locator('#ev-date').fill(tomorrow);
  await page.locator('#ev-start').fill('20:00');
  // Round 8: step 1 validation requires a venue name while "Venue" is selected.
  await page.locator('#ev-venue').fill('E2E Hall');
  await page.locator('#ev-city').selectOption('Baghdad');
  await page.locator('#ev-cat').selectOption('MUSIC');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 2 of 5');

  log('wizard step 2 (banner): skipping straight through');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');

  log('wizard step 3 (tickets): GA 10,000 IQD × 50 — required by the step validation');
  await page.locator('input[name="ttName"]').first().fill('GA');
  await page.locator('input[name="ttPrice"]').first().fill('10000');
  await page.locator('input[name="ttQty"]').first().fill('50');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 4 of 5');

  log('wizard step 4 (details): optional — continuing to publish');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');

  log('wizard step 5: review shows the title, then Publish event (sticky action bar)');
  await expect(page.locator('#rv-title')).toHaveText('E2E Concert Night');
  await page.locator('#finalBtns button[value="publish"]').click();

  log('event console should show the event as Live');
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: /View public page/ })).toBeVisible();

  log('the published event should be discoverable on public browse');
  await page.goto('/browse?q=E2E%20Concert');
  await expect(page.getByText('E2E Concert Night').first()).toBeVisible();
});

test('n. unauthenticated /me/tickets and /host are pushed to sign-in', async ({ browser }) => {
  const context = await browser.newContext();
  const freshPage = await context.newPage();
  try {
    for (const url of ['/me/tickets', '/host']) {
      log(`fresh context → GET ${url} should land on the login page`);
      await freshPage.goto(url);
      await expect(freshPage).toHaveURL(/\/auth\/login/);
    }
  } finally {
    await context.close();
  }
});

test('o. promo code EARLY20: apply at checkout, discount carries to order and ticket holder', async ({ page }) => {
  await login(page, BUYER_EMAIL, buyerPassword);

  log('buyer keeps the default GA ×1 on Baghdad Nights (round 9) and goes to checkout');
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);

  log('applying promo code EARLY20 via the rail Apply mini-form');
  await page.getByLabel('Promo code').fill('EARLY20');
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  await expect(page).toHaveURL(/promo=EARLY20/);

  log('green confirmation, discount line −7,000 IQD, total 29,500 IQD (35,000 − 7,000 + 1,500)');
  await expect(page.getByText(/EARLY20 applied/).first()).toBeVisible();
  await expect(page.locator('#discountLine')).toContainText('EARLY20');
  await expect(page.locator('#discountLine')).toContainText('7,000 IQD');
  await expect(page.getByText(/29,500\s*IQD/).first()).toBeVisible();

  log('naming the ticket holder and completing the direct-transfer order');
  await page.locator('input[name="holderName"]').first().fill('Holder One');
  await page.locator('#transferReference').fill('E2E-REF-2');
  await page.locator('#receiptInput').setInputFiles(RECEIPT_PNG);
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);

  log('confirmation shows the discount with the EARLY20 chip and total 29,500 IQD');
  await expect(page.getByText('Discount').first()).toBeVisible();
  await expect(page.getByText('EARLY20').first()).toBeVisible();
  await expect(page.getByText(/7,000 IQD/).first()).toBeVisible();
  await expect(page.getByText(/29,500\s*IQD/).first()).toBeVisible();

  log('fahad approves the promo order (round 10: Approve lives in the open detail panel of the pending row)');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?status=pending');
  const row = page.locator('tr').filter({ hasText: BUYER_EMAIL }).first();
  await expect(row).toBeVisible();
  await row
    .locator('xpath=following-sibling::tr[1]')
    .getByRole('button', { name: /Approve/ })
    .click();
  // Round 8: /host/orders funnels through ?f=1, so assert the durable outcome
  // (row leaves the pending queue) instead of the redirect flash.
  await expect(page).toHaveURL(/status=pending/);
  await expect(page.locator('tr').filter({ hasText: BUYER_EMAIL })).toHaveCount(0);

  log('buyer /me/tickets shows the ticket held by "Holder One"');
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto('/me/tickets');
  await expect(page.getByText('Holder One').first()).toBeVisible();
});

test('p. newsletter signup from the home band', async ({ page }) => {
  log('subscribing from the home newsletter band');
  await page.goto('/');
  await page.getByLabel('Email address').fill(`news+${RUN_ID}@test.iq`);
  await page.getByRole('button', { name: 'Subscribe' }).click();
  await expect(page).toHaveURL(/\?subscribed/);
  await expect(page.getByText(/You're on the list/).first()).toBeVisible();
});

test('q. calendar .ics, short link and embeddable widget', async ({ page, request }) => {
  log('GET /events/baghdad-nights-music-festival/calendar.ics');
  const ics = await request.get('/events/baghdad-nights-music-festival/calendar.ics');
  expect(ics.status(), 'calendar download should be 200').toBe(200);
  expect(ics.headers()['content-type']).toContain('text/calendar');
  const icsBody = await ics.text();
  expect(icsBody).toContain('BEGIN:VEVENT');
  expect(icsBody).toContain('Baghdad Nights Music Festival');

  log('short link /e/{slug} should land on the event page');
  await page.goto('/e/baghdad-nights-music-festival');
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival/);
  await expect(page.locator('h1')).toContainText('Baghdad Nights Music Festival');

  log('GET /js/widget.js should serve the embeddable widget');
  const widget = await request.get('/js/widget.js');
  expect(widget.status(), 'widget script should be 200').toBe(200);
  expect(await widget.text()).toContain('iEvent');
});

test('r. event edit: rename + add a ticket type, changes reach the public page', async ({ page }) => {
  // The E2E-created event belongs to the E2E host org, so edit as that owner
  // (leaves the seeded Baghdad Nights untouched for later tests).
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the E2E Concert Night console and its edit page');
  await page.goto('/host/events');
  await page.getByRole('link', { name: /E2E Concert Night/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const editEventId = page.url().match(/\/host\/events\/(\d+)/)[1];
  await page.getByRole('link', { name: /Edit event/ }).click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);

  log('renaming the event with the " (Updated)" suffix');
  await page.locator('#ev-title').fill('E2E Concert Night (Updated)');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();

  log('console and public page should show the updated title');
  await page.goto(`/host/events/${editEventId}`);
  await expect(page.getByText('E2E Concert Night (Updated)').first()).toBeVisible();
  const publicLink = page.getByRole('link', { name: /View public page/ });
  const publicHref = await publicLink.getAttribute('href');
  await page.goto(publicHref);
  await expect(page.locator('h1')).toContainText('E2E Concert Night (Updated)');

  log('adding ticket type "Backstage" 50,000 IQD × 10 via the add row');
  await page.goto(`/host/events/${editEventId}/edit`);
  const addForm = page.locator('form').filter({ hasText: 'Add ticket type' });
  await addForm.locator('input[name="name"]').fill('Backstage');
  await addForm.locator('input[name="price"]').fill('50000');
  await addForm.locator('input[name="quantity"]').fill('10');
  await addForm.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();

  log('the new type should appear in the edit list and on the public page');
  await expect(page.locator('input[name="name"][value="Backstage"]')).toHaveCount(1);
  await page.goto(publicHref);
  await expect(page.getByText('Backstage').first()).toBeVisible();
  await expect(page.getByText(/50,000\s*IQD/).first()).toBeVisible();
});

test('s. team: seeded STAFF member, invite flash, staff access boundaries', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('/host/settings members list should contain Sara Kareem with a STAFF badge');
  await page.goto('/host/settings');
  const saraRow = page.locator('li').filter({ hasText: 'Sara Kareem' }).first();
  await expect(saraRow).toBeVisible();
  await expect(saraRow.getByText('STAFF', { exact: true })).toBeVisible();

  log('re-inviting the already-seeded staff member shows the invited/already flash');
  await page.getByLabel('Invite email').fill('sara@zainevents.iq');
  await page.getByRole('button', { name: 'Send invite' }).click();
  await expect(
    page.getByText(/(was added to your team|already)/).first()
  ).toBeVisible();

  log('sara (STAFF) can open the host dashboard and check-in pages');
  await signOut(page);
  await login(page, 'sara@zainevents.iq', 'Password123!');
  await page.goto('/host');
  await expect(page.getByText("Zain Events Co.").first()).toBeVisible();
  await page.goto('/host/checkin');
  await expect(page.getByText('Scan or type a ticket code')).toBeVisible();

  log('GET /host/events/new still renders for staff (POSTs are what is gated)');
  await page.goto('/host/events/new');
  await expect(page.getByText("What's your event called?")).toBeVisible();

  log('/host/marketing for staff: no create form, locked notice shown');
  await page.goto('/host/marketing');
  await expect(page.getByText('New promo code')).toHaveCount(0);
  await expect(
    page.getByText('Only owners and managers can email attendees.')
  ).toBeVisible();
});

test('t. profile: change password and notification preferences', async ({ page }) => {
  await login(page, BUYER_EMAIL, buyerPassword);

  log('changing the buyer password from /me/profile');
  await page.goto('/me/profile');
  await page.locator('#sec-current').fill(buyerPassword);
  await page.locator('#sec-new').fill(BUYER_NEW_PASSWORD);
  await page.getByRole('button', { name: /Change password/ }).click();
  await expect(page.getByText('Changes saved.').first()).toBeVisible();
  buyerPassword = BUYER_NEW_PASSWORD;

  log('toggling marketing notifications off');
  const marketingToggle = page.locator('input[name="notifyMarketing"]');
  if (await marketingToggle.isChecked()) {
    await marketingToggle.uncheck();
  }
  await page.getByRole('button', { name: 'Save preferences' }).click();
  await expect(page.getByText('Changes saved.').first()).toBeVisible();
  await expect(page.locator('input[name="notifyMarketing"]')).not.toBeChecked();

  log('signing out and back in with the NEW password');
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);
  await expect(page.locator('header').getByText('EB', { exact: true }).first()).toBeVisible();
});

test('u. earnings table and camera check-in page', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('/host/earnings should list Baghdad Nights with revenue');
  await page.goto('/host/earnings');
  const earningsRow = page
    .locator('tr')
    .filter({ hasText: 'Baghdad Nights Music Festival' })
    .first();
  await expect(earningsRow).toBeVisible();
  await expect(earningsRow.getByText(/IQD/).first()).toBeVisible();

  log('/host/checkin should offer the camera scanner (script + Start button) and manual entry');
  await page.goto('/host/checkin');
  await expect(page.getByText('Scan with camera')).toBeVisible();
  await expect(page.locator('#qr-start')).toBeVisible();
  await expect(page.locator('script[src*="html5-qrcode"]')).toHaveCount(1);
  await expect(page.locator('form:has(input[name="code"]) input[name="code"]')).toBeVisible();
});

test('v. attendees regression: auto-select, seeded demo rows, search, no 500s', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('/host/attendees without params auto-selects the first event and must not error');
  await page.goto('/host/attendees');
  await expect(page).toHaveURL(/\/host\/attendees\?event=/);
  let body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');

  log('selecting Baghdad Nights shows the seeded EVT-DEMO attendee rows');
  const baghdadOption = page
    .locator('#event-scope option')
    .filter({ hasText: 'Baghdad Nights Music Festival' })
    .first();
  const baghdadValue = await baghdadOption.getAttribute('value');
  await page.goto(`/host/attendees?event=${baghdadValue}`);
  const rows = page.locator('tbody tr');
  await expect
    .poll(async () => rows.count(), { message: 'expected seeded attendee rows' })
    .toBeGreaterThan(0);
  const demoGuests =
    /(Ali Hassan|Noor Al-Saadi|Omar Dawood|Huda Jassim|Mustafa Karim|Zainab Qasim|Rania Faris|Yousif Salman|Layla Ibrahim|Ahmed Rashid|Sarah Mahmoud|Bilal Hameed|Dina Kareem|Hasan Jabbar|Mariam Adel)/;
  await expect(page.getByText(demoGuests).first()).toBeVisible();

  log('searching a partial holder name filters the list');
  // The first td holds an avatar-initial span + the name span (.font-semibold).
  const fullName = (await rows.first().locator('span.font-semibold').first().innerText()).trim();
  const firstName = fullName.split(/\s+/)[0];
  await page.locator('input[name="q"]').fill(firstName);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(
    page.locator('tbody tr').filter({ hasText: firstName }).first()
  ).toBeVisible();

  log('the first 3 event options must render without a 500 ("Something went wrong")');
  const firstThree = await page
    .locator('#event-scope option')
    .evaluateAll((opts) => opts.slice(0, 3).map((o) => o.value));
  for (const value of firstThree) {
    await page.goto(`/host/attendees?event=${value}`);
    body = await page.locator('body').innerText();
    expect(body, `event option ${value} should render cleanly`).not.toContain(
      'Something went wrong'
    );
  }
});

test('w. check-in door list: seeded names, search, one-click check-in bumps the counter', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('opening check-in for Baghdad Nights (picked from the event dropdown)');
  await page.goto('/host/checkin');
  const baghdadValue = await page
    .locator('#ci-event option')
    .filter({ hasText: 'Baghdad Nights Music Festival' })
    .first()
    .getAttribute('value');
  await page.goto(`/host/checkin?event=${baghdadValue}`);

  log('the Door list column should be visible with seeded ticket rows');
  await expect(page.getByText('Door list', { exact: true })).toBeVisible();
  const uncheckedRow = page
    .locator('li')
    .filter({ has: page.getByRole('button', { name: 'Check in', exact: true }) })
    .first();
  await expect(uncheckedRow).toBeVisible();

  const counterText = await page.getByText(/Checked in\s*\d+/).first().innerText();
  const before = parseInt(counterText.replace(/[^0-9/]/g, '').split('/')[0], 10);
  log(`counter before: ${before} (badge: "${counterText.trim()}")`);

  log('searching the door list by holder name');
  const holderName = (await uncheckedRow.locator('p').first().innerText()).trim();
  const searchTerm = holderName.split(/\s+/)[0];
  await page.getByLabel('Search door list').fill(searchTerm);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const foundRow = page
    .locator('li')
    .filter({ hasText: holderName })
    .filter({ has: page.getByRole('button', { name: 'Check in', exact: true }) })
    .first();
  await expect(foundRow).toBeVisible();

  log(`checking in "${holderName}" from the door list`);
  await foundRow.getByRole('button', { name: 'Check in', exact: true }).click();
  await expect(
    page
      .locator('li')
      .filter({ hasText: holderName })
      .filter({ has: page.getByRole('button', { name: 'Undo' }) })
      .first()
  ).toBeVisible();

  const afterText = await page.getByText(/Checked in\s*\d+/).first().innerText();
  const after = parseInt(afterText.replace(/[^0-9/]/g, '').split('/')[0], 10);
  log(`counter after: ${after}`);
  expect(after, 'checked-in counter should increment').toBe(before + 1);
});

test('x. event covers: theme picker, photo upload served via /media, remove restores gradient', async ({ page, request }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the E2E event edit page');
  await page.goto('/host/events');
  await page.getByRole('link', { name: /E2E Concert Night/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const eventId = page.url().match(/\/host\/events\/(\d+)/)[1];
  const publicHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');
  await page.goto(`/host/events/${eventId}/edit`);

  log('cover section: 10 theme radios + file input should be present');
  await expect(page.locator('input[name="coverTheme"]')).toHaveCount(10);
  await expect(page.locator('#cover-image')).toBeVisible();

  log('picking the "tech" theme and saving');
  await page
    .locator('label')
    .filter({ has: page.locator('input[name="coverTheme"][value="tech"]') })
    .click();
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();
  await expect(page.locator('input[name="coverTheme"][value="tech"]')).toBeChecked();

  log('uploading a cover photo and saving');
  await page.locator('#cover-image').setInputFiles(RECEIPT_PNG);
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();
  await expect(page.getByText('Current cover photo')).toBeVisible();
  await expect(page.locator('img[src*="/media/event-cover/"]').first()).toBeVisible();

  log('the public event page should render the uploaded cover from /media/event-cover/');
  await page.goto(publicHref);
  const coverImg = page.locator('img[src*="/media/event-cover/"]').first();
  await expect(coverImg).toBeVisible();
  const coverSrc = await coverImg.getAttribute('src');
  const coverRes = await request.get(coverSrc);
  expect(coverRes.status(), 'cover image should be served').toBe(200);
  expect(coverRes.headers()['content-type']).toContain('image/png');

  log('removing the cover restores the gradient (no /media img on the public page)');
  await page.goto(`/host/events/${eventId}/edit`);
  await page.locator('input[name="removeCover"]').check();
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();
  await page.goto(publicHref);
  await expect(page.locator('img[src*="/media/event-cover/"]')).toHaveCount(0);
});

test('y. browse "when" chips: Next 7 days has results, Today renders cleanly', async ({ page }) => {
  log('clicking the "Next 7 days" chip');
  await page.goto('/browse');
  await page.getByRole('link', { name: 'Next 7 days' }).click();
  await expect(page).toHaveURL(/when=week/);

  log('seeded events inside 7 days (Startup Mixer +3, Baghdad Nights +5) → cards > 0');
  await expect
    .poll(async () => page.locator(EVENT_CARD_LINKS).count(), {
      message: 'expected event cards within the next 7 days',
    })
    .toBeGreaterThan(0);

  log('the "Today" chip may match zero events — page must render cards or the empty state');
  await page.getByRole('link', { name: 'Today', exact: true }).click();
  await expect(page).toHaveURL(/when=today/);
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');
  const cardCount = await page.locator(EVENT_CARD_LINKS).count();
  if (cardCount === 0) {
    await expect(page.getByText('No events match')).toBeVisible();
  }
  log(`Today filter rendered with ${cardCount} card(s)`);
});

test('z. host test email reaches Mailpit', async ({ page, request }, testInfo) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('sending the test email from /host/settings/payments');
  await page.goto('/host/settings/payments');
  await page.getByRole('button', { name: /Send me a test email/ }).click();
  await expect(page.getByText(/Test email sent to/).first()).toBeVisible();

  log('EMAIL: the test mail must land in Mailpit');
  await assertMail(request, testInfo, {
    to: DEMO_HOST_EMAIL,
    subjectPart: 'iEvent test email',
  });
});

test('aa. ticket downloads: QR PNG, single-ticket PDF, buyer-only order PDF', async ({ page, request }) => {
  expect(rsvpTicketCode, 'needs the ticket code captured in test h').toBeTruthy();
  expect(rsvpOrderCode, 'needs the order code captured in test h').toBeTruthy();

  log(`GET /t/${rsvpTicketCode}/qr.png → PNG attachment (public by unguessable code)`);
  const png = await request.get(`/t/${rsvpTicketCode}/qr.png`);
  expect(png.status()).toBe(200);
  expect(png.headers()['content-type']).toContain('image/png');
  expect(png.headers()['content-disposition']).toContain('attachment');

  log(`GET /t/${rsvpTicketCode}/ticket.pdf → PDF starting with %PDF`);
  const pdf = await request.get(`/t/${rsvpTicketCode}/ticket.pdf`);
  expect(pdf.status()).toBe(200);
  expect(pdf.headers()['content-type']).toContain('application/pdf');
  const pdfBody = await pdf.body();
  expect(pdfBody.slice(0, 4).toString()).toBe('%PDF');

  log('the buyer (via the page session) can download the whole-order PDF');
  await login(page, BUYER_EMAIL, buyerPassword);
  const orderPdf = await page.request.get(`/orders/${rsvpOrderCode}/tickets.pdf`);
  expect(orderPdf.status()).toBe(200);
  expect(orderPdf.headers()['content-type']).toContain('application/pdf');
  expect((await orderPdf.body()).slice(0, 4).toString()).toBe('%PDF');

  log('anonymous requests for the order PDF are pushed to sign-in (no PDF leak)');
  const anon = await request.get(`/orders/${rsvpOrderCode}/tickets.pdf`, {
    maxRedirects: 0,
  });
  expect(
    anon.status(),
    'anonymous order-PDF request must redirect to login, not serve the PDF'
  ).toBeGreaterThanOrEqual(300);
  expect(anon.status()).toBeLessThan(400);
  expect(anon.headers()['content-type'] || '').not.toContain('application/pdf');

  log('the /me/tickets stubs expose per-ticket QR PNG and PDF download links');
  await page.goto('/me/tickets');
  await expect(page.getByRole('link', { name: 'QR PNG' }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'PDF', exact: true }).first()).toBeVisible();
});

// ---------- wireframe-parity round ----------

test('ab. browse parity: sort, price radios, when pills, numbered pagination, filter chips', async ({ page }) => {
  log('/browse shows the results count line and numbered pagination');
  await page.goto('/browse');
  await expect(page.getByText(/Showing/).first()).toBeVisible();
  await expect(page.getByText(/events across Iraq/).first()).toBeVisible();
  const pagination = page.locator('nav[aria-label="Pagination"]');
  await expect(pagination.locator('[aria-current="page"]')).toHaveText('1');
  await expect(page.getByRole('link', { name: 'Page 2' })).toBeVisible();
  await expect(pagination.getByText('…').first()).toBeVisible();

  log('clicking "Page 2" navigates to page=1 and marks it current');
  await page.getByRole('link', { name: 'Page 2' }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(page.locator('nav[aria-label="Pagination"] [aria-current="page"]')).toHaveText('2');

  log('sort dropdown: choosing "Most popular" submits sort=popular');
  await page.goto('/browse');
  await page.locator('#sortSelect').selectOption('popular');
  await expect(page).toHaveURL(/sort=popular/);
  await expect(page.locator('#sortSelect')).toHaveValue('popular');

  log('sort=price deep link pre-selects "Price low→high"');
  await page.goto('/browse?sort=price');
  await expect(page.locator('#sortSelect')).toHaveValue('price');

  log('price=paid checks the sidebar radio and shows the removable "Paid" chip');
  await page.goto('/browse?q=Basra&price=paid');
  const sidebar = page.locator('aside[aria-label="Filters"]');
  await expect(sidebar.locator('input[name="price"][value="paid"]')).toBeChecked();
  await expect(page.getByRole('link', { name: 'Remove price filter' })).toBeVisible();
  await expect(page.getByRole('link', { name: /Remove search filter Basra/ })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Clear all' })).toBeVisible();

  log('clicking the price chip clears only the price filter (q stays)');
  await page.getByRole('link', { name: 'Remove price filter' }).click();
  await expect(page).not.toHaveURL(/price=paid/);
  await expect(page).toHaveURL(/q=Basra/);
  await expect(page.getByRole('link', { name: 'Remove price filter' })).toHaveCount(0);

  log('"Tomorrow" when-pill renders cleanly (cards or the empty state)');
  await page.goto('/browse');
  await page.getByRole('link', { name: 'Tomorrow', exact: true }).click();
  await expect(page).toHaveURL(/when=tomorrow/);
  let body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');
  if ((await page.locator(EVENT_CARD_LINKS).count()) === 0) {
    await expect(page.getByText('No events match')).toBeVisible();
  }

  log('"This month" when-pill has seeded events inside 31 days');
  await page.getByRole('link', { name: 'This month', exact: true }).click();
  await expect(page).toHaveURL(/when=month/);
  await expect
    .poll(async () => page.locator(EVENT_CARD_LINKS).count(), {
      message: 'expected event cards within this month',
    })
    .toBeGreaterThan(0);

  log('unknown tracking code /l/... safely redirects to /browse');
  await page.goto('/l/e2e-unknown-code');
  await expect(page).toHaveURL(/\/browse/);
});

test('ac. event page parity: summary, tags, lineup, directions, refund policy, follow form', async ({ page }) => {
  log('opening the enriched Baghdad Nights page anonymously');
  await page.goto('/events/baghdad-nights-music-festival');

  log('short summary under the title (seed enrichment)');
  await expect(page.getByText('One night, three stages').first()).toBeVisible();

  log('tag chips link into browse search');
  const tagChip = page.getByRole('link', { name: '#family-friendly' });
  await expect(tagChip).toBeVisible();
  expect(await tagChip.getAttribute('href')).toContain('/browse');

  log('Lineup section lists the seeded acts');
  await expect(page.getByRole('heading', { name: 'Lineup' })).toBeVisible();
  await expect(page.getByText('DJ Rafi')).toBeVisible();
  await expect(page.getByText('Maqam Reborn Ensemble')).toBeVisible();

  log('round 8: the venue block offers "Open in Google Maps" (maps.google.com link)');
  const directions = page.getByRole('link', { name: /Open in Google Maps/ });
  await expect(directions).toBeVisible();
  expect(await directions.getAttribute('href')).toContain('maps.google.com');

  log('refund policy line is rendered');
  await expect(page.getByRole('heading', { name: 'Refund policy' })).toBeVisible();
  await expect(page.getByText(/Booking fees are non-refundable/).first()).toBeVisible();

  log('signed-in user can follow the organizer straight from the event page (POST form)');
  await login(page, E2E_EMAIL, E2E_PASSWORD);
  await page.goto('/events/baghdad-nights-music-festival');
  const followBtn = page.getByRole('button', { name: 'Follow', exact: true }).first();
  if (await followBtn.isVisible().catch(() => false)) {
    // The follow POST redirects to the organizer profile page.
    await followBtn.click();
    await expect(page).toHaveURL(/\/organizers\/zainevents/);
    await page.goto('/events/baghdad-nights-music-festival');
  }
  await expect(page.getByRole('button', { name: 'Following' }).first()).toBeVisible();
});

test('ad. organizer page: tabs, contact block, initials avatar, past events tab', async ({ page }) => {
  log('opening @zainevents anonymously');
  await page.goto('/organizers/zainevents');

  log('initials avatar (no logo seeded) and the three tabs render');
  await expect(page.getByText('ZE', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: /Upcoming \(/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Past events' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'About' })).toBeVisible();

  log('About tab shows the seeded contact & socials block');
  await page.getByRole('tab', { name: 'About' }).click();
  await expect(page.locator('#tab-about')).toBeVisible();
  await expect(page.getByRole('link', { name: 'hello@zainevents.iq' })).toBeVisible();
  await expect(page.getByText('+964 770 123 4567')).toBeVisible();
  await expect(page.getByText('instagram.com/zainevents')).toBeVisible();
  await expect(page.getByText('zainevents.iq', { exact: true }).first()).toBeVisible();

  log('Past events tab opens (cards or the "No past events yet" empty state)');
  await page.getByRole('tab', { name: 'Past events' }).click();
  await expect(page.locator('#tab-past')).toBeVisible();
  if (
    (await page
      .locator('#tab-past a[href^="/en/events/"], #tab-past a[href^="/events/"]')
      .count()) === 0
  ) {
    await expect(page.getByText('No past events yet')).toBeVisible();
  }
});

test('ae. auth extras: remember-me, forgot-password confirmation, invalid reset token', async ({ page }) => {
  log('login page offers "Remember me" and a forgot-password link');
  await page.goto('/auth/login');
  await expect(page.locator('input[name="remember-me"]')).toBeVisible();
  const forgotLink = page.getByRole('link', { name: 'Forgot password?' });
  await expect(forgotLink).toBeVisible();

  log('/auth/forgot always answers with the "Check your inbox" confirmation');
  await forgotLink.click();
  await expect(page).toHaveURL(/\/auth\/forgot/);
  await page.locator('#fp-email').fill(`no-such-user+${RUN_ID}@test.iq`);
  await page.getByRole('button', { name: /Email me a reset link/ }).click();
  await expect(page.getByText('Check your inbox')).toBeVisible();
  await expect(page.getByText(/If an account exists for/)).toBeVisible();

  log('/auth/reset with a bogus token shows the expired-link state');
  await page.goto('/auth/reset?token=totally-bogus-token');
  await expect(page.getByText('This link has expired')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Request a new link' })).toBeVisible();
  await expect(page.locator('#rp-pass')).toHaveCount(0);
});

test('af. password reset email: Mailpit link completes the reset, new password works', async ({ page, request }, testInfo) => {
  await registerUser(page, { name: RESET_NAME, email: RESET_EMAIL, password: RESET_PASSWORD });

  log('requesting a reset link for the fresh user');
  await page.goto('/auth/forgot');
  await page.locator('#fp-email').fill(RESET_EMAIL);
  await page.getByRole('button', { name: /Email me a reset link/ }).click();
  await expect(page.getByText('Check your inbox')).toBeVisible();

  if (!(await mailpitAvailable(request))) {
    if (IS_CI) {
      throw new Error(`Mailpit API not reachable at ${MAILPIT_API} — password-reset mail cannot be verified in CI`);
    }
    testInfo.annotations.push({
      type: 'mail-check-skipped',
      description: `Mailpit not available at ${MAILPIT_API} — reset round-trip skipped`,
    });
    log('Mailpit unavailable — skipping the reset round-trip locally');
    return;
  }

  log('EMAIL: "Reset your iEvent password" must land in Mailpit');
  const hit = await mailpitFind(request, {
    to: RESET_EMAIL,
    subjectPart: 'Reset your iEvent password',
  });
  expect(hit, 'expected the password-reset mail in Mailpit').toBeTruthy();

  log('extracting the /auth/reset?token=... link from the mail body');
  const mailBody = await mailpitMessageBody(request, hit.ID);
  const tokenMatch = mailBody.match(/\/auth\/reset\?token=([A-Za-z0-9]+)/);
  expect(tokenMatch, 'reset mail should contain the tokenized link').toBeTruthy();

  log('completing the reset with a new password');
  await page.goto(`/auth/reset?token=${tokenMatch[1]}`);
  await expect(page.getByRole('heading', { name: 'Set a new password' })).toBeVisible();
  await page.locator('#rp-pass').fill(RESET_NEW_PASSWORD);
  await page.locator('#rp-confirm').fill(RESET_NEW_PASSWORD);
  await page.getByRole('button', { name: /Update password/ }).click();
  await expect(page).toHaveURL(/\/auth\/login\?reset/);
  await expect(page.getByText(/Password updated/).first()).toBeVisible();

  log('signing in with the NEW password succeeds');
  await login(page, RESET_EMAIL, RESET_NEW_PASSWORD);
});

test('ag. user area: tickets Upcoming/Past tabs, favorites Organizers tab', async ({ page }) => {
  await login(page, BUYER_EMAIL, buyerPassword);

  log('/me/tickets renders the Upcoming and Past tabs');
  await page.goto('/me/tickets');
  await expect(page.getByRole('tab', { name: /Upcoming \(/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /Past \(/ })).toBeVisible();

  log('switching to the Past tab shows past orders or the empty state');
  await page.locator('#tabPast').click();
  await expect(page.locator('#panelPast')).toBeVisible();
  if ((await page.locator('#panelPast article').count()) === 0) {
    await expect(page.getByText("That's everything so far")).toBeVisible();
  }

  log('/favorites?tab=organizers opens the Organizers tab with the followed organizer');
  await page.goto('/favorites?tab=organizers');
  await expect(page.getByRole('tab', { name: /Organizers \(/ })).toHaveAttribute('aria-selected', 'true');
  const orgCard = page.locator('article').filter({ hasText: 'Zain Events Co.' }).first();
  await expect(orgCard).toBeVisible();
  await expect(orgCard.getByText('@zainevents')).toBeVisible();
  await expect(orgCard.getByRole('button', { name: /Following/ })).toBeVisible();
});

test('ah. profile: city select and interests chips persist across a re-GET', async ({ page }) => {
  await login(page, BUYER_EMAIL, buyerPassword);

  log('choosing Baghdad as the profile city and saving');
  await page.goto('/me/profile');
  await page.locator('#pf-city').selectOption('Baghdad');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText('Changes saved.').first()).toBeVisible();

  log('picking the Music and Tech interest chips and saving');
  const musicInput = page.locator('input[name="interests"][value="MUSIC"]');
  const techInput = page.locator('input[name="interests"][value="TECH"]');
  if (!(await musicInput.isChecked())) {
    await page.locator('label').filter({ has: musicInput }).click();
  }
  if (!(await techInput.isChecked())) {
    await page.locator('label').filter({ has: techInput }).click();
  }
  await page.getByRole('button', { name: 'Save interests' }).click();
  await expect(page.getByText('Changes saved.').first()).toBeVisible();

  log('a fresh GET shows the persisted city and interests');
  await page.goto('/me/profile');
  await expect(page.locator('#pf-city')).toHaveValue('Baghdad');
  await expect(page.locator('input[name="interests"][value="MUSIC"]')).toBeChecked();
  await expect(page.locator('input[name="interests"][value="TECH"]')).toBeChecked();
});

test('ai. host dashboard: views/followers cards, delta chips, ?range=7, to-do checklist', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('stat cards: Page views and Followers with delta chips');
  await page.goto('/host');
  await expect(page.getByText('Page views').first()).toBeVisible();
  await expect(page.getByText('Followers').first()).toBeVisible();
  await expect(page.getByText('vs prev. 30 days').first()).toBeVisible();

  log('sales chart range pill: ?range=7 marks 7d as current');
  await page.goto('/host?range=7');
  await expect(page.locator('section[aria-label="Ticket sales chart"]')).toBeVisible();
  await expect(page.locator('a[href*="range=7"]')).toHaveAttribute('aria-current', 'true');

  log("round 13: fahad's org has EVERY checklist item done (live+payments+branding+team) → no To-do card");
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');
  await expect(page.locator('section[aria-label="To-do checklist"]')).toHaveCount(0);

  log('round 13: HOST2 (live event ✓, but no payments/branding/team yet) DOES see the card, pinned at the TOP');
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host');
  const todo = page.locator('section[aria-label="To-do checklist"]');
  await expect(todo).toBeVisible();
  await expect(todo.getByText('Set up payments')).toBeVisible();
  // exactly ONE completed item so far: the live event published in test m
  await expect(todo.locator('span.bg-green-600')).toHaveCount(1);
  // R13 placement: the card renders ABOVE the stat cards
  const todoBox = await todo.boundingBox();
  const statsBox = await page.getByText('Page views').first().boundingBox();
  expect(todoBox, 'To-do card must have a bounding box').toBeTruthy();
  expect(statsBox, 'stat card must have a bounding box').toBeTruthy();
  expect(todoBox.y, 'To-do card must sit above the stat cards').toBeLessThan(statsBox.y);
});

test('aj. host events list: q search filters rows, status filter works', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('searching q=Erbil narrows the list to the Tech Summit');
  await page.goto('/host/events?q=Erbil');
  await expect(page.getByText('Erbil Tech Summit 2026').first()).toBeVisible();
  await expect(page.getByText('Baghdad Nights Music Festival')).toHaveCount(0);

  log('status=live keeps the live flagship visible');
  await page.goto('/host/events?status=live');
  await expect(page).toHaveURL(/status=live/);
  await expect(page.getByText('Baghdad Nights Music Festival').first()).toBeVisible();

  log('the search input and status chips render as a filter bar');
  await expect(page.locator('input[name="q"]')).toBeVisible();
  await expect(page.getByRole('link', { name: /^Draft/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /^Cancelled/ })).toBeVisible();
});

test('ak. event edit: summary/tags/lineup/visibility/refund fields, summary reaches the public page', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the E2E event edit page');
  await page.goto('/host/events');
  await page.getByRole('link', { name: /E2E Concert Night/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const publicHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');
  const eventId = page.url().match(/\/host\/events\/(\d+)/)[1];
  await page.goto(`/host/events/${eventId}/edit`);

  log('all the new descriptive fields are present');
  await expect(page.locator('#ev-summary')).toBeVisible();
  await expect(page.locator('#ev-tags')).toBeVisible();
  await expect(page.locator('#ev-lineup')).toBeVisible();
  await expect(page.locator('#ev-refund')).toBeVisible();
  await expect(page.locator('input[name="visibility"]')).toHaveCount(2);

  log('filling summary, tags, lineup and a NO_REFUNDS policy, then saving');
  await page.locator('#ev-summary').fill('An unforgettable E2E night out in Baghdad.');
  await page.locator('#ev-tags').fill('e2eparity, test');
  await page.locator('#ev-lineup').fill('DJ E2E — 9:00 PM\nThe Regression Band — 10:30 PM');
  await page.locator('#ev-refund').selectOption('NO_REFUNDS');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();

  log('the public page shows the summary, tag chip, lineup and refund line');
  await page.goto(publicHref);
  await expect(page.getByText('An unforgettable E2E night out in Baghdad.')).toBeVisible();
  await expect(page.getByRole('link', { name: '#e2eparity' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Lineup' })).toBeVisible();
  await expect(page.getByText('DJ E2E')).toBeVisible();
  await expect(page.getByText(/All sales are final/).first()).toBeVisible();
});

test('al. event lifecycle: duplicate creates a "(copy)" draft, publish, postpone, cancel', async ({ page }) => {
  // Confirm dialogs (postpone/cancel) must be accepted, not auto-dismissed.
  page.on('dialog', (dialog) => dialog.accept());

  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('opening the ORIGINAL Baghdad Nights console (never the copy)');
  await page.goto('/host/events');
  await page
    .locator('a')
    .filter({ hasText: 'Baghdad Nights Music Festival' })
    .filter({ hasNotText: '(copy)' })
    .first()
    .click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);

  log('Duplicate → lands on the copy\'s edit page as a draft titled "(copy)"');
  await page.getByRole('button', { name: 'Duplicate' }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);
  await expect(page.getByText(/Duplicated as a draft/).first()).toBeVisible();
  const copyId = page.url().match(/\/host\/events\/(\d+)\/edit/)[1];
  await expect(page.locator('#ev-title')).toHaveValue(/\(copy\)$/);

  log('publishing the duplicate from the edit page');
  await page.getByRole('button', { name: 'Publish', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/host/events/${copyId}$`));
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible();
  const publicHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');

  log('postponing the duplicate by three weeks');
  const newDate = new Date(Date.now() + 21 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  await page.locator('#pp-date').fill(newDate);
  await page.getByRole('button', { name: 'Postpone', exact: true }).click();
  await expect(page.getByText(/New date saved — every buyer has been emailed/).first()).toBeVisible();
  await expect(page.locator('#pp-date')).toHaveValue(newDate);

  log('cancelling the duplicate (NEVER a seeded flagship)');
  await page.getByRole('button', { name: 'Cancel event' }).click();
  await expect(page.getByText(/Event cancelled — every buyer has been emailed/).first()).toBeVisible();
  await expect(page.getByText(/This event is cancelled/).first()).toBeVisible();

  log('the public page shows the cancelled banner and no ticket rail');
  await page.goto(publicHref);
  await expect(page.getByText('This event has been cancelled')).toBeVisible();
  await expect(page.getByText('Event cancelled', { exact: true }).first()).toBeVisible();
});

test('am. attendees: filters UI, stats row, CSV export, resend action', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('selecting Baghdad Nights in the attendees event scope');
  await page.goto('/host/attendees');
  const baghdadValue = await page
    .locator('#event-scope option')
    .filter({ hasText: /^Baghdad Nights Music Festival$/ })
    .first()
    .getAttribute('value');
  await page.goto(`/host/attendees?event=${baghdadValue}`);

  log('filter controls: ticket-type select, status select, Apply, search box');
  await expect(page.locator('select[name="type"]')).toBeVisible();
  await expect(page.locator('select[name="status"]')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Apply', exact: true })).toBeVisible();
  await expect(page.locator('input[name="q"]')).toBeVisible();

  log('stats strip: total / checked in / remaining / void cards');
  await expect(page.getByText('Total attendees')).toBeVisible();
  await expect(page.getByText('Checked in', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('Remaining', { exact: true })).toBeVisible();
  await expect(page.getByText('Void / refunded', { exact: true }).first()).toBeVisible();

  log('a "Resend ticket" action exists on confirmed rows');
  await expect(page.getByRole('button', { name: 'Resend ticket' }).first()).toBeVisible();

  log('status filter CHECKED_IN renders without error');
  await page.goto(`/host/attendees?event=${baghdadValue}&status=CHECKED_IN`);
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');

  log('CSV export answers text/csv with the header row');
  const csv = await page.request.get(`/host/attendees/export.csv?event=${baghdadValue}`);
  expect(csv.status(), 'attendees CSV should be 200').toBe(200);
  expect(csv.headers()['content-type']).toContain('text/csv');
  const csvBody = await csv.text();
  expect(csvBody.startsWith('Name,Email,Ticket type,Order code')).toBeTruthy();
});

test('an. orders: enriched view, search, CSV, full refund flow voids the ticket and emails the buyer', async ({ page, request }, testInfo) => {
  // Refund confirm dialog must be accepted.
  page.on('dialog', (dialog) => dialog.accept());

  log('fresh buyer places a direct-transfer order on Baghdad Nights');
  await registerUser(page, { name: REFUND_NAME, email: REFUND_EMAIL, password: REFUND_PASSWORD });
  await login(page, REFUND_EMAIL, REFUND_PASSWORD);
  await page.goto('/events/baghdad-nights-music-festival');
  // Round 9: the rail already defaults to GA ×1 — submit as-is.
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);
  await page.locator('#transferReference').fill('E2E-REF-REFUND');
  await page.locator('#receiptInput').setInputFiles(RECEIPT_PNG);
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  const refundOrderCode = page.url().match(/\/orders\/([A-Z0-9-]+)/)[1];
  log(`captured refund-flow order code: ${refundOrderCode}`);

  log('host approves the order from the pending queue (round 10: via the open detail panel)');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?status=pending');
  const pendingRow = page.locator('tr').filter({ hasText: REFUND_EMAIL }).first();
  await expect(pendingRow).toBeVisible();
  await pendingRow
    .locator('xpath=following-sibling::tr[1]')
    .getByRole('button', { name: /Approve/ })
    .click();
  // Round 8: assert the durable outcome (row leaves the pending queue) — the
  // approve flash does not survive the /host/orders → ?f=1 funnel redirect.
  await expect(page).toHaveURL(/status=pending/);
  await expect(page.locator('tr').filter({ hasText: REFUND_EMAIL })).toHaveCount(0);

  log('buyer grabs the issued ticket code from the order page');
  await signOut(page);
  await login(page, REFUND_EMAIL, REFUND_PASSWORD);
  await page.goto(`/orders/${refundOrderCode}`);
  const refundTicketCode = (
    await page
      .locator('dl > div')
      .filter({ hasText: 'Ticket code' })
      .locator('dd')
      .first()
      .innerText()
  ).trim();
  expect(refundTicketCode.length).toBeGreaterThan(5);
  await page.goto(`/t/${refundTicketCode}`);
  await expect(page.getByText('Valid ticket')).toBeVisible();

  log('enriched orders view: stats row, Refunded tab, search, CSV export');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?f=1');
  await expect(page.getByText('Gross sales')).toBeVisible();
  await expect(page.getByText('Awaiting confirmation')).toBeVisible();
  await expect(page.getByRole('link', { name: /Refunded/ }).first()).toBeVisible();
  await expect(page.locator('input[name="q"]')).toBeVisible();
  const ordersCsv = await page.request.get('/host/orders/export.csv');
  expect(ordersCsv.status(), 'orders CSV should be 200').toBe(200);
  expect(ordersCsv.headers()['content-type']).toContain('text/csv');
  expect((await ordersCsv.text()).startsWith('Order code,Buyer,Email')).toBeTruthy();

  log('searching the buyer, expanding the row and refunding the order');
  await page.goto(`/host/orders?f=1&q=${encodeURIComponent(REFUND_EMAIL)}`);
  const orderRow = page.locator('tr').filter({ hasText: REFUND_EMAIL }).first();
  await expect(orderRow).toBeVisible();
  // CONFIRMED orders still render their detail collapsed — the toggle click stays.
  await orderRow.locator('.detail-toggle').click();
  await orderRow
    .locator('xpath=following-sibling::tr[1]')
    .getByRole('button', { name: /Refund order/ })
    .click();
  await expect(page.getByText(/refunded — tickets voided/).first()).toBeVisible();

  log('the order row now carries the Refunded badge');
  await expect(
    page.locator('tr').filter({ hasText: REFUND_EMAIL }).first().getByText('Refunded', { exact: true })
  ).toBeVisible();

  log('the voided ticket\'s public status page shows "Void ticket"');
  await page.goto(`/t/${refundTicketCode}`);
  await expect(page.getByText('Void ticket')).toBeVisible();

  log('EMAIL: the "refunded" notification must land in Mailpit');
  await assertMail(request, testInfo, {
    to: REFUND_EMAIL,
    subjectPart: 'refunded',
  });
});

test('ao. marketing: tabs, tracking link counts clicks, followers campaign hits Mailpit', async ({ page, request }, testInfo) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('marketing tabs render: Promo codes / Email campaigns / Tracking links / Social');
  await page.goto('/host/marketing');
  await expect(page.getByRole('tab', { name: /Promo codes/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /Email campaigns/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /Tracking links/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /Social/ })).toBeVisible();

  log('creating an Instagram tracking link for Baghdad Nights');
  await page.goto('/host/marketing?tab=links');
  // Scope to the links panel — the (hidden) promo form also has an eventId select.
  const linksPanel = page.locator('#tab-links');
  await linksPanel.locator('select[name="eventId"]').selectOption({ label: 'Baghdad Nights Music Festival' });
  await linksPanel.locator('select[name="channel"]').selectOption('instagram');
  await page.getByRole('button', { name: 'Generate', exact: true }).click();
  const flashText = await page.getByText(/Tracking link ready:/).innerText();
  const codeMatch = flashText.match(/\/l\/([A-Za-z0-9]+)/);
  expect(codeMatch, 'flash should contain the /l/{code} URL').toBeTruthy();
  const linkCode = codeMatch[1];
  log(`tracking link code: ${linkCode}`);

  log('the link is listed with its /l/ URL and 0 clicks');
  const linkRow = page.locator('tr').filter({ hasText: `/l/${linkCode}` }).first();
  await expect(linkRow).toBeVisible();
  await expect(linkRow.locator('td').nth(3)).toHaveText('0');

  log('following /l/{code} redirects to the event page with ?via=');
  await page.goto(`/l/${linkCode}`);
  await expect(page).toHaveURL(new RegExp(`/events/baghdad-nights-music-festival\\?via=${linkCode}`));
  await expect(page.locator('h1')).toContainText('Baghdad Nights Music Festival');

  log('the click counter increments after a reload of the links tab');
  await page.goto('/host/marketing?tab=links');
  await expect(
    page.locator('tr').filter({ hasText: `/l/${linkCode}` }).first().locator('td').nth(3)
  ).toHaveText(/^[1-9]\d*$/);

  log('sending an email campaign to FOLLOWERS (amira/omar follow @zainevents)');
  await page.goto('/host/marketing?tab=email');
  await page.locator('#em-audience').selectOption('FOLLOWERS');
  await page.locator('#em-subject').fill(CAMPAIGN_SUBJECT);
  await page.locator('#em-body').fill('Salam! This is the E2E walkthrough campaign — see you at the festival.');
  await page.getByRole('button', { name: /Send campaign/ }).click();
  await expect(page.getByText(/Campaign sent to \d+ recipient/).first()).toBeVisible();

  log('the campaign shows up in the Sent campaigns history');
  await expect(page.getByText(CAMPAIGN_SUBJECT).first()).toBeVisible();

  log('EMAIL: seeded follower amira@example.iq must receive the campaign');
  await assertMail(request, testInfo, {
    to: 'amira@example.iq',
    subjectPart: CAMPAIGN_SUBJECT,
  });
});

test('ap. host settings: branding prefilled from seed, notifications toggle, ?tab deep links', async ({ page }) => {
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('?tab=brand deep-links straight into the Branding panel');
  await page.goto('/host/settings?tab=brand');
  await expect(page.locator('#st-brand')).toBeVisible();
  await expect(page.locator('#st-org')).toBeHidden();

  log('branding form is prefilled with the seeded contact details');
  await expect(page.locator('#c-email')).toHaveValue('hello@zainevents.iq');
  await expect(page.locator('#c-phone')).toHaveValue('+964 770 123 4567');
  await expect(page.locator('#c-insta')).toHaveValue('zainevents');
  await expect(page.locator('#brand-color')).toBeVisible();
  await expect(page.locator('input[name="logo"]')).toBeVisible();

  log('?tab=notif shows the notifications panel with the pending-orders toggle ON');
  await page.goto('/host/settings?tab=notif');
  await expect(page.locator('#st-notif')).toBeVisible();
  await expect(page.locator('input[name="notifyPendingOrders"]')).toBeChecked();
  await expect(page.getByRole('button', { name: 'Save notifications' })).toBeVisible();
});

// ---------- round 8: notifications, payment methods, locations, org covers ----------

test('aq. notification center: pending order notifies the buyer, summary API has {unread, items}', async ({ page }) => {
  await registerUser(page, { name: NOTIF_NAME, email: NOTIF_EMAIL, password: NOTIF_PASSWORD });
  await login(page, NOTIF_EMAIL, NOTIF_PASSWORD);

  log('placing a direct-transfer order on Baghdad Nights WITHOUT a receipt (host-side warning tested in ar)');
  await page.goto('/events/baghdad-nights-music-festival');
  // Round 9: the rail already defaults to GA ×1 — submit as-is.
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);
  await page.locator('#transferReference').fill('E2E-REF-NOTIF');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  await expect(
    page.getByRole('heading', { name: /pending organizer confirmation/i })
  ).toBeVisible();

  log('/me/notifications lists the "Order received" notification for the pending order');
  await page.goto('/me/notifications');
  await expect(
    page.locator('main').getByText(/Order received — Baghdad Nights Music Festival/).first()
  ).toBeVisible();

  log('GET /api/notifications/summary returns the {unread, items} JSON shape');
  const res = await page.request.get('/api/notifications/summary', {
    headers: { Accept: 'application/json' },
  });
  expect(res.status(), 'summary endpoint should answer 200 for a signed-in user').toBe(200);
  const summary = await res.json();
  expect(typeof summary.unread).toBe('number');
  expect(summary.unread).toBeGreaterThanOrEqual(1);
  expect(Array.isArray(summary.items)).toBeTruthy();
  expect(summary.items.length).toBeGreaterThan(0);
  expect(summary.items[0]).toHaveProperty('title');
  expect(summary.items[0]).toHaveProperty('unread');
});

test('ar. notification center: host bell badge, NEW_ORDER click-through, no-receipt warning, approve', async ({ page }) => {
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('the navbar bell badge shows at least one unread notification');
  await page.goto('/');
  const badge = page.locator('#nbBadge');
  await expect(badge).toBeVisible();
  await expect(badge).toHaveText(/^\d+\+?$/);

  log('/me/notifications lists "New order to confirm" for the direct-transfer order');
  await page.goto('/me/notifications');
  const newOrderItem = page
    .locator('main a[href*="/me/notifications/go/"]')
    .filter({ hasText: 'New order to confirm — Baghdad Nights Music Festival' })
    .first();
  await expect(newOrderItem).toBeVisible();

  log('clicking through marks it read and lands on the pending orders queue');
  await newOrderItem.click();
  await expect(page).toHaveURL(/\/host\/orders\?f=1&status=pending/);

  log('the receipt-less order shows NO chip; its OPEN detail (round 10 default for pending) carries the amber "No receipt uploaded" note');
  const row = page.locator('tr').filter({ hasText: NOTIF_EMAIL }).first();
  await expect(row).toBeVisible();
  await expect(row.getByText('Receipt', { exact: true })).toHaveCount(0);
  // Pending details are server-rendered open — no .detail-toggle click (it would close them).
  const detailRow = row.locator('xpath=following-sibling::tr[1]');
  await expect(detailRow).toBeVisible();
  await expect(detailRow.getByText(/No receipt uploaded/)).toBeVisible();
  await expect(detailRow.getByText('E2E-REF-NOTIF')).toBeVisible();

  log('approving from the detail panel — the pending row must disappear');
  await detailRow.getByRole('button', { name: /Approve/ }).click();
  await expect(page).toHaveURL(/status=pending/);
  await expect(page.locator('tr').filter({ hasText: NOTIF_EMAIL })).toHaveCount(0);
});

test('as. notification center: buyer sees "Order confirmed", mark-all-read clears, clear-all empties', async ({ page }) => {
  await signOut(page);
  await login(page, NOTIF_EMAIL, NOTIF_PASSWORD);

  log('the buyer notification list shows the "Order confirmed" entry');
  await page.goto('/me/notifications');
  await expect(
    page.locator('main').getByText(/Order confirmed — Baghdad Nights Music Festival/).first()
  ).toBeVisible();

  log('"Mark all read" → caught-up line and the API reports 0 unread');
  await page.locator('main').getByRole('button', { name: 'Mark all read' }).click();
  await expect(page).toHaveURL(/\/me\/notifications/);
  await expect(page.locator('main').getByText("You're all caught up.")).toBeVisible();
  const summaryRes = await page.request.get('/api/notifications/summary');
  expect(summaryRes.status()).toBe(200);
  expect((await summaryRes.json()).unread).toBe(0);

  log('with 0 unread the navbar bell badge stays hidden');
  await page.goto('/');
  await expect(page.locator('#nbBadge')).toBeHidden();

  log('"Clear all" empties the list to the "No notifications yet" state');
  await page.goto('/me/notifications');
  await page.locator('main').getByRole('button', { name: 'Clear all' }).click();
  await expect(page.getByText('No notifications yet')).toBeVisible();
});

test('at. payment methods: seeded card listed, add "Test FIB", disable/enable toggle', async ({ page }) => {
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('/host/settings/payments shows "Your payment methods" with the seeded ZainCash wallet');
  await page.goto('/host/settings/payments');
  await expect(page.getByText('Your payment methods')).toBeVisible();
  const zainCard = page.locator('li').filter({ hasText: 'ZainCash wallet' }).first();
  await expect(zainCard).toBeVisible();
  await expect(zainCard.getByText('0770 123 4567')).toBeVisible();
  await expect(zainCard.getByText('Enabled', { exact: true })).toBeVisible();

  log('adding a method "Test FIB" (label + number + holder)');
  await page.locator('#pm-label').fill('Test FIB');
  await page.locator('#pm-number').fill('1234 5678 9012');
  await page.locator('#pm-holder').fill('E2E Holder');
  await page.getByRole('button', { name: 'Add payment method' }).click();
  await expect(page.getByText(/Payment method "Test FIB" added/)).toBeVisible();
  const fibCard = page.locator('li').filter({ hasText: 'Test FIB' }).first();
  await expect(fibCard).toBeVisible();
  await expect(fibCard.getByText('1234 5678 9012')).toBeVisible();
  await expect(fibCard.getByText('Enabled', { exact: true })).toBeVisible();

  log('disabling the method flips its badge to Disabled');
  await fibCard.getByRole('button', { name: 'Disable' }).click();
  await expect(
    page.locator('li').filter({ hasText: 'Test FIB' }).first().getByText('Disabled', { exact: true })
  ).toBeVisible();

  log('re-enabling restores the Enabled badge (needed at checkout in test au)');
  await page.locator('li').filter({ hasText: 'Test FIB' }).first()
    .getByRole('button', { name: 'Enable' }).click();
  await expect(
    page.locator('li').filter({ hasText: 'Test FIB' }).first().getByText('Enabled', { exact: true })
  ).toBeVisible();
});

test('au. checkout method picker: both methods with copy buttons + amount callout; delete cleans up', async ({ page }) => {
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);

  log('buyer keeps the default GA ×1 on Baghdad Nights (round 9) and opens checkout');
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);

  log('the direct-transfer panel lists BOTH enabled methods as selectable cards');
  await expect(page.getByText('Direct transfer to organizer')).toBeVisible();
  const radios = page.locator('input[name="paymentMethodLabel"]');
  await expect(radios).toHaveCount(2);
  await expect(radios.first()).toBeChecked();
  await expect(page.getByText('ZainCash wallet').first()).toBeVisible();
  await expect(page.getByText('0770 123 4567').first()).toBeVisible();
  await expect(page.getByText('Test FIB').first()).toBeVisible();
  await expect(page.getByText('1234 5678 9012').first()).toBeVisible();

  log('each method card has a copy button; the amount callout carries the total');
  await expect(page.locator('.copy-acct')).toHaveCount(2);
  await expect(page.getByText('Amount to transfer')).toBeVisible();
  await expect(page.getByText(/36,500\s*IQD/).first()).toBeVisible();

  log('the buyer can switch the selected method to Test FIB');
  await page.locator('input[name="paymentMethodLabel"][value="Test FIB"]').check();
  await expect(page.locator('input[name="paymentMethodLabel"][value="Test FIB"]')).toBeChecked();

  log('cleanup: host deletes "Test FIB" so the seeded state stays canonical');
  page.on('dialog', (dialog) => dialog.accept());
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/settings/payments');
  await page.locator('li').filter({ hasText: 'Test FIB' }).first()
    .getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('Payment method deleted.')).toBeVisible();
  await expect(page.locator('li').filter({ hasText: 'Test FIB' })).toHaveCount(0);
});

test('av. online events: wizard Online toggle, public page never leaks the URL, RSVP unlocks the join link', async ({ page }) => {
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('creating a free ONLINE event through the wizard');
  await page.goto('/host/events/new');
  await expect(page.locator('#stepIndicator')).toHaveText('Step 1 of 5');
  await page.locator('#ev-title').fill(ONLINE_EVENT_TITLE);
  const inTwoDays = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  await page.locator('#ev-date').fill(inTwoDays);
  await page.locator('#ev-start').fill('18:00');

  log('the "Where is it?" toggle offers Venue / Online / To be announced');
  await expect(page.getByText('Where is it?')).toBeVisible();
  await expect(page.locator('input[name="locationType"]')).toHaveCount(3);
  await page
    .locator('label')
    .filter({ has: page.locator('input[name="locationType"][value="ONLINE"]') })
    .click();
  await expect(page.locator('input[name="locationType"][value="ONLINE"]')).toBeChecked();
  await page.locator('#ev-online').fill(ONLINE_URL);
  await page.locator('#ev-city').selectOption('Baghdad');
  await page.locator('#ev-cat').selectOption('TECH');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 2 of 5');
  await page.locator('#nextBtn').click(); // banner — skip
  await page.locator('input[name="ttName"]').first().fill('RSVP');
  await page.locator('input[name="ttPrice"]').first().fill('0');
  await page.locator('input[name="ttQty"]').first().fill('80');
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click(); // details — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');
  await expect(page.locator('#rv-place')).toHaveText('Online event');
  await page.locator('#finalBtns button[value="publish"]').click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  onlineEventPublicHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');

  log('public page shows the "happens online" card and NEVER contains the join URL');
  await page.goto(onlineEventPublicHref);
  await expect(page.locator('h1')).toContainText(ONLINE_EVENT_TITLE);
  await expect(page.getByText('Online event', { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/This event happens online/).first()).toBeVisible();
  const publicHtml = await page.content();
  expect(publicHtml, 'join URL must not leak into public HTML').not.toContain('meet.example.com');

  log('buyer RSVPs free — the rail already defaults to RSVP ×1 (round 9), instant tickets reveal the "Join online event" link');
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto(onlineEventPublicHref);
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page.locator('#submitLabel')).toHaveText('Register free');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  await expect(page.getByRole('heading', { name: /You're going!/ })).toBeVisible();
  const joinLink = page.getByRole('link', { name: 'Join online event' });
  await expect(joinLink).toBeVisible();
  expect(await joinLink.getAttribute('href')).toContain('meet.example.com/x');

  log('/me/tickets shows the online order with its join link');
  await page.goto('/me/tickets');
  const orderCard = page.locator('article').filter({ hasText: ONLINE_EVENT_TITLE }).first();
  await expect(orderCard).toBeVisible();
  await expect(orderCard.getByRole('link', { name: 'Join online event' })).toBeVisible();
});

test('aw. locations: venue page embeds the keyless Google map; TBA state renders after an edit', async ({ page }) => {
  await signOut(page);

  log('Baghdad Nights (VENUE) embeds the keyless maps.google.com iframe + maps link');
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('iframe[src*="maps.google.com"]')).toHaveCount(1);
  const mapsLink = page.getByRole('link', { name: /Open in Google Maps/ });
  await expect(mapsLink).toBeVisible();
  expect(await mapsLink.getAttribute('href')).toContain('maps.google.com');

  log('HOST2 flips the online event to "To be announced" from the edit page');
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host/events?q=E2E+Online');
  await page.getByRole('link', { name: /E2E Online Meetup/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await page.getByRole('link', { name: /Edit event/ }).click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);
  await page
    .locator('label')
    .filter({ has: page.locator('input[name="locationType"][value="TBA"]') })
    .click();
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();

  log('the public page now shows the "Location to be announced" card (and still no URL leak)');
  expect(onlineEventPublicHref, 'needs the public link captured in test av').toBeTruthy();
  await page.goto(onlineEventPublicHref);
  await expect(page.getByText('Location to be announced').first()).toBeVisible();
  const html = await page.content();
  expect(html).not.toContain('meet.example.com');
});

test('ax. organizer profile: gradient banner without cover, name outside the banner, cover uploader', async ({ page }) => {
  await signOut(page);

  log('@zainevents renders the gradient banner (no cover uploaded) with the h1 BELOW it');
  await page.goto('/organizers/zainevents');
  const banner = page.locator('div.h-44').first();
  await expect(banner).toBeVisible();
  await expect(banner.locator('h1'), 'name/handle must not live inside the banner').toHaveCount(0);
  await expect(banner.locator('img'), 'no cover uploaded → gradient only').toHaveCount(0);
  await expect(page.locator('h1')).toContainText('Zain Events Co.');

  log('Settings → Branding shows the "Profile cover" uploader (input[name=cover])');
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/settings?tab=brand');
  await expect(page.locator('#st-brand')).toBeVisible();
  await expect(page.getByText('Profile cover')).toBeVisible();
  await expect(page.locator('input[name="cover"]')).toBeVisible();
});

test('ay. search styling: the home hero search input carries outline-none', async ({ page }) => {
  log('hero search input on / should use the outline-none reset (round 8 styling fix)');
  await page.goto('/');
  const heroInput = page.locator('input[aria-label="Search events"]');
  await expect(heroInput).toBeVisible();
  await expect(heroInput).toHaveClass(/outline-none/);
});

// ---------- round 9: checkout qty default, widgets tab redesign, signed-in error page ----------

test('az. checkout default: fresh GET preselects 1× first on-sale type; explicit qty params still win; signed-in 404 keeps navbar', async ({ page }) => {
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);

  log('opening the Baghdad Nights checkout DIRECTLY (no qty-* params in the URL)');
  await page.goto('/events/baghdad-nights-music-festival/checkout');

  log('round 9: the first ON_SALE type with stock defaults to qty 1 — General Admission (Early Bird is SOLD_OUT and not rendered)');
  const qtyInputs = page.locator('.qty-input');
  await expect(qtyInputs.first()).toHaveValue('1');
  await expect(qtyInputs.nth(1)).toHaveValue('0'); // VIP Table stays at 0

  log('exactly one holder row is rendered for the defaulted ticket');
  await expect(page.locator('.holder-row')).toHaveCount(1);
  await expect(page.getByText('Ticket 1 · General Admission')).toBeVisible();

  log('the total shows the single-ticket amount: 36,500 IQD (35,000 + 1,500 fee)');
  await expect(page.locator('.sum-total').first()).toHaveText(/36,500\s*IQD/);
  await expect(page.locator('#submitLabel')).toHaveText('Submit order for confirmation');

  log('the stepper starts FROM the default: one + click makes it 2 (not 1)');
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
  await expect(qtyInputs.first()).toHaveValue('2');
  await expect(page.locator('.holder-row')).toHaveCount(2);
  await expect(page.locator('.sum-total').first()).toHaveText(/73,000\s*IQD/);

  log('a deep link WITH explicit qty-* params is respected exactly (all zero → no default, no holder rows)');
  await page.goto('/events/baghdad-nights-music-festival/checkout?qty-0=0');
  await expect(page.locator('.qty-input').first()).toHaveValue('0');
  await expect(page.locator('.holder-row')).toHaveCount(0);

  log('regression (GlobalModelAdvice): a bogus URL while signed in keeps the signed-in navbar');
  await page.goto('/events/does-not-exist-e2e-round9');
  await expect(page.getByText(/Error 404/i).first()).toBeVisible();
  const header = page.locator('header');
  // "E2E Buyer" -> initials "EB"; no "Sign in" link for an authenticated visitor.
  await expect(header.getByText('EB', { exact: true }).first()).toBeVisible();
  await expect(header.getByRole('link', { name: /Sign in/i })).toHaveCount(0);
});

test('ba. marketing Widgets tab: event picker swaps panels, embed snippets, live widget preview', async ({ page }) => {
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);

  log('?tab=share deep-links into the redesigned Widgets tab');
  await page.goto('/host/marketing?tab=share');
  await expect(page.locator('#tab-share')).toBeVisible();

  log('the event dropdown lists the org\'s events, Baghdad Nights among them (exactly once)');
  const select = page.locator('#widget-event-select');
  await expect(select).toBeVisible();
  const options = select.locator('option');
  await expect
    .poll(async () => options.count(), { message: 'expected the org events in the picker' })
    .toBeGreaterThan(1);
  await expect(options.filter({ hasText: /^Baghdad Nights Music Festival$/ })).toHaveCount(1);

  log('initially only the selected event\'s panels show (snippet column + live preview = 2 elements)');
  const initialId = await select.inputValue();
  const visiblePanels = page.locator('div[data-widget-panel]:visible');
  await expect(visiblePanels).toHaveCount(2);
  for (const el of await visiblePanels.all()) {
    expect(await el.getAttribute('data-widget-panel'), 'visible panel must belong to the selected event').toBe(initialId);
  }

  log('switching the picker to Baghdad Nights swaps the visible panels');
  await select.selectOption({ label: 'Baghdad Nights Music Festival' });
  const bnId = await select.inputValue();
  const bnPanels = page.locator(`div[data-widget-panel="${bnId}"]`);
  await expect(bnPanels).toHaveCount(2);
  await expect(bnPanels.first()).toBeVisible();
  await expect(bnPanels.nth(1)).toBeVisible();
  await expect(page.locator('div[data-widget-panel]:visible')).toHaveCount(2);

  log('round 10 builder: the live embed snippet follows the controls (data-color/lang/price/rounded)');
  const snippetPanel = bnPanels.first();
  const liveSnippet = page.locator(`#embed-snippet-${bnId}`);
  await expect(liveSnippet).toContainText('data-event="baghdad-nights-music-festival"');
  await expect(liveSnippet).toContainText('/js/widget.js');
  await expect(liveSnippet).toContainText('data-color=');

  log('share link #share-link-{id} carries /e/{slug} with its "Copy link" button');
  await expect(page.locator(`#share-link-${bnId}`)).toContainText('/e/baghdad-nights-music-festival');
  await expect(snippetPanel.getByText(/\/e\/baghdad-nights-music-festival/).first()).toBeVisible();
  await expect(snippetPanel.getByRole('button', { name: 'Copy link' })).toBeVisible();

  log('fixed Button + Card presets live inside a collapsed <details> — toContainText still reads the DOM text');
  const btnSnippet = page.locator(`#embed-btn-${bnId}`);
  await expect(btnSnippet).toContainText('data-event="baghdad-nights-music-festival"');
  await expect(btnSnippet).toContainText('/js/widget.js');
  await expect(btnSnippet).toContainText('data-type="button"');
  const cardSnippet = page.locator(`#embed-card-${bnId}`);
  await expect(cardSnippet).toContainText('data-event="baghdad-nights-music-festival"');
  await expect(cardSnippet).toContainText('data-type="card"');

  log('the live preview shows the type chips, the widget stage and exactly ONE "POWERED BY IEVENT"');
  const previewPanel = bnPanels.nth(1);
  await expect(previewPanel.getByText('Button widget')).toBeVisible();
  await expect(previewPanel.getByText('Card widget')).toBeVisible();
  await expect(previewPanel.getByText('Get tickets').first()).toBeVisible();
  await expect(previewPanel.getByText('POWERED BY IEVENT')).toHaveCount(1);
  await expect(previewPanel.getByText('POWERED BY IEVENT')).toBeVisible();
});

// ---------- round 10: login-return, fee modes, free toggle, checklist dismiss,
// ----------           open order details, draft preview, branding ----------

test('bb. login-return (#11): anonymous checkout → sign in → land back with the selection → order completes', async ({ page }) => {
  log('registering the return-flow buyer up front (the sign-in itself happens mid-checkout)');
  await signOut(page);
  await registerUser(page, { name: LR_NAME, email: LR_EMAIL, password: LR_PASSWORD });

  log('ANONYMOUS: event page → Get tickets → the checkout still renders (no login wall)');
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout\?/);

  log('anonymous buyers get NO submit button — only "Sign in to complete your order" (#signInToOrder)');
  await expect(page.locator('#submitBtn')).toHaveCount(0);
  const signIn = page.locator('#signInToOrder');
  await expect(signIn).toBeVisible();
  await expect(signIn).toContainText('Sign in to complete your order');

  log('the "Create an account" companion link also carries the ?next continuation');
  const registerHref = await page.locator('#registerToOrder').getAttribute('href');
  expect(registerHref, 'register link must carry ?next=').toContain('/auth/register?next=');

  log('clicking through lands on the login page with the "take you right back" hint');
  await signIn.click();
  await expect(page).toHaveURL(/\/auth\/login\?next=/);
  await expect(page.getByText(/take you right back/).first()).toBeVisible();

  log('signing in from the continuation page');
  await page.locator('input[name="email"]').fill(LR_EMAIL);
  await page.locator('input[name="password"]').fill(LR_PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();

  log('LANDS BACK on the checkout URL with the qty params intact — selection preserved');
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout\?.*qty-/);
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await expect(page.locator('.sum-total').first()).toHaveText(/36,500\s*IQD/);
  await expect(page.locator('#buyerEmail')).toHaveValue(LR_EMAIL);

  log('the direct-transfer order now submits normally');
  await page.locator('#transferReference').fill('E2E-REF-RETURN');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  await expect(
    page.getByRole('heading', { name: /pending organizer confirmation/i })
  ).toBeVisible();
});

test('bc. fee mode (#17): ABSORB event charges the buyer face value; host earnings deduct the fee', async ({ page }) => {
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('HOST2 enables direct payments + adds a method (fresh orgs have none — paid checkout needs one)');
  await page.goto('/host/settings/payments');
  await page.locator('label[title="Enable direct payments"]').click();
  await expect(page.locator('input[name="enabled"]')).toBeChecked();
  await page.getByRole('button', { name: 'Save toggle' }).click();
  await expect(page.locator('input[name="enabled"]')).toBeChecked();
  await page.locator('#pm-label').fill('E2E Wallet');
  await page.locator('#pm-number').fill('0781 000 1111');
  await page.getByRole('button', { name: 'Add payment method' }).click();
  await expect(page.getByText(/Payment method "E2E Wallet" added/)).toBeVisible();

  log('creating a PAID event and switching the wizard fee card to "Absorb it"');
  await wizardBasics(page, {
    title: 'E2E Absorb Gala', daysAhead: 4, start: '19:00',
    venue: 'E2E Absorb Hall', city: 'Basra', category: 'MUSIC',
  });
  await page.locator('#nextBtn').click(); // step 2 · banner — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');
  await page.locator('input[name="ttName"]').first().fill('GA');
  await page.locator('input[name="ttPrice"]').first().fill('20000');
  await page.locator('input[name="ttQty"]').first().fill('40');

  log('the fee card defaults to PASS — this event flips to ABSORB');
  await expect(page.locator('input[name="feeMode"][value="PASS"]')).toBeChecked();
  await page.locator('input[name="feeMode"][value="ABSORB"]').check();
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click(); // step 4 · details — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');
  await expect(page.locator('#rv-fee')).toHaveText('Booking fee: absorbed by you');
  await page.locator('#finalBtns button[value="publish"]').click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const absorbHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');

  log('buyer opens the ABSORB checkout — spec #17: total = 20,000 IQD FACE VALUE, no booking fee added');
  await signOut(page);
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto(absorbHref);
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/checkout/);
  // Soft so the money-flow assertions below still run and pin the charge even
  // if the checkout DISPLAY regresses — the test still fails on a soft miss.
  await expect
    .soft(page.locator('.sum-total').first(), '#17: ABSORB checkout total must be the face value (20,000 IQD, no +1,500 fee)')
    .toHaveText(/20,000\s*IQD/);
  await expect
    .soft(page.locator('.sum-fee').first(), '#17: ABSORB checkout must not show a non-zero booking fee')
    .toHaveText(/^0\s*IQD$/);

  log('submitting — the CHARGED order total must be the 20,000 IQD face value');
  await page.locator('#transferReference').fill('E2E-REF-ABSORB');
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  await expect(
    page.getByRole('heading', { name: /pending organizer confirmation/i })
  ).toBeVisible();
  await expect(page.getByText(/20,000\s*IQD/).first()).toBeVisible();
  const confirmationBody = await page.locator('body').innerText();
  expect(confirmationBody, 'ABSORB order must NOT charge the 1,500 IQD booking fee').not.toMatch(/21,500/);

  log('HOST2 approves the order from the open pending detail');
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host/orders?status=pending');
  const row = page.locator('tr').filter({ hasText: BUYER_EMAIL }).first();
  await expect(row).toBeVisible();
  await row
    .locator('xpath=following-sibling::tr[1]')
    .getByRole('button', { name: /Approve/ })
    .click();
  await expect(page.locator('tr').filter({ hasText: BUYER_EMAIL })).toHaveCount(0);

  log('earnings: the Absorb Gala row shows gross 20,000, fee 1,500 deducted, net 18,500');
  await page.goto('/host/earnings');
  const earningsRow = page.locator('tr').filter({ hasText: 'E2E Absorb Gala' }).first();
  await expect(earningsRow).toBeVisible();
  await expect(earningsRow.getByText(/20,000\s*IQD/).first()).toBeVisible();
  await expect(earningsRow.getByText(/1,500\s*IQD/).first()).toBeVisible();
  await expect(earningsRow.getByText(/18,500\s*IQD/).first()).toBeVisible();
});

test('bd. free toggle (#16) + cover preview (#14): wizard zeroes prices, publishes a Free event', async ({ page }) => {
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  await wizardBasics(page, {
    title: 'E2E Freebie Fair', daysAhead: 3, start: '17:00',
    venue: 'E2E Garden', city: 'Baghdad', category: 'COMMUNITY',
  });

  log('round 10: the wizard is a focused flow — the host bottom tab bar is not rendered here');
  await expect(page.locator('nav[aria-label="Dashboard tabs"]')).toHaveCount(0);

  log('step 2 cover preview (#14): #coverPreviewImg exists but stays hidden until a file is chosen');
  const previewImg = page.locator('#coverPreviewImg');
  await expect(previewImg).toHaveCount(1);
  await expect(previewImg).toBeHidden();

  log('choosing a banner file → the FileReader preview appears instantly');
  await page.locator('#cover-image').setInputFiles(RECEIPT_PNG);
  await expect(previewImg).toBeVisible();

  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');

  log('step 3: typing a paid ticket first, then flipping "My event is free" (#16)');
  await page.locator('input[name="ttName"]').first().fill('Entry');
  await page.locator('input[name="ttPrice"]').first().fill('5000');
  await page.locator('input[name="ttQty"]').first().fill('30');
  await page.locator('label').filter({ has: page.locator('#freeToggle') }).click();
  await expect(page.locator('#freeToggle')).toBeChecked();

  log('every price input is zeroed AND locked; the fee card gives way to the free note');
  const firstPrice = page.locator('input[name="ttPrice"]').first();
  await expect(firstPrice).toHaveValue('0');
  await expect(firstPrice).toHaveJSProperty('readOnly', true);
  await expect(page.locator('#feeCard')).toBeHidden();
  await expect(page.locator('#feeFreeNote')).toBeVisible();

  log('the free toggle must not break step navigation — continuing to publish');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 4 of 5');
  await page.locator('#nextBtn').click();
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');
  await expect(page.locator('#rv-fee')).toHaveText('Free event — no booking fee');
  await expect(page.locator('#rv-tickets')).toContainText('Entry · Free · 30');
  await page.locator('#finalBtns button[value="publish"]').click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible();
  const freeHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');

  log('the public page sells the event as Free (rail + uploaded cover)');
  await page.goto(freeHref);
  await expect(page.locator('h1')).toContainText('E2E Freebie Fair');
  const rail = page.locator('form[action$="/checkout"]');
  await expect(rail.getByText('Free', { exact: true }).first()).toBeVisible();
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await expect(page.locator('#railTotal')).toHaveText(/^0\s*IQD$/);
  await expect(page.locator('img[src*="/media/event-cover/"]').first()).toBeVisible();
});

test('be. to-do dismiss (#7): fresh org sees the checklist, Dismiss hides it permanently', async ({ page }) => {
  await signOut(page);
  await registerUser(page, { name: HOST3_NAME, email: HOST3_EMAIL, password: HOST3_PASSWORD });
  await login(page, HOST3_EMAIL, HOST3_PASSWORD);

  log('running the onboarding funnel for a brand-new org');
  await page.goto('/host');
  await expect(page).toHaveURL(/\/host\/start/);
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click();
  await page.locator('#orgName').fill('E2E Dismiss Org');
  await page.getByRole('button', { name: /Create organizer profile/ }).click();
  await expect(page).toHaveURL(/\/host(\/)?$/);

  log('the fresh dashboard shows the To-do checklist with a Dismiss control');
  const todo = page.locator('section[aria-label="To-do checklist"]');
  await expect(todo).toBeVisible();
  await expect(todo.getByText('Set up payments')).toBeVisible();
  const dismissBtn = todo.getByRole('button', { name: 'Dismiss' });
  await expect(dismissBtn).toBeVisible();

  log('Dismiss → POST /host/checklist/dismiss → back on /host without the card');
  await dismissBtn.click();
  await expect(page).toHaveURL(/\/host(\/)?(\?.*)?$/);
  await expect(page.locator('section[aria-label="To-do checklist"]')).toHaveCount(0);

  log('the card stays gone after a full reload (persisted, not just hidden)');
  await page.goto('/host');
  await expect(page.locator('body')).not.toContainText('Something went wrong');
  await expect(page.locator('section[aria-label="To-do checklist"]')).toHaveCount(0);
});

test('bf. orders UX (#1): pending details are open on arrival, exactly one Approve control per order', async ({ page }) => {
  log('fresh buyer places a direct-transfer order with receipt + reference');
  await signOut(page);
  await registerUser(page, { name: PEND_NAME, email: PEND_EMAIL, password: PEND_PASSWORD });
  await login(page, PEND_EMAIL, PEND_PASSWORD);
  await page.goto('/events/baghdad-nights-music-festival');
  await expect(page.locator('.qty-input').first()).toHaveValue('1');
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await page.locator('#transferReference').fill('E2E-REF-OPEN');
  await page.locator('#receiptInput').setInputFiles(RECEIPT_PNG);
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);

  log('host opens the pending queue — NO clicks anywhere yet');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?f=1&status=pending');

  log('every pending order arrives with its detail row already open');
  await expect(page.locator('tr.detail-row').first()).toBeVisible();
  await expect(page.locator('tr.detail-row.hidden')).toHaveCount(0);

  log('the fresh order: receipt preview + reference are readable immediately');
  const row = page.locator('tr').filter({ hasText: PEND_EMAIL }).first();
  await expect(row).toBeVisible();
  await expect(row.locator('.detail-toggle')).toHaveAttribute('aria-expanded', 'true');
  const detail = row.locator('xpath=following-sibling::tr[1]');
  await expect(detail).toBeVisible();
  await expect(detail.getByText('E2E-REF-OPEN')).toBeVisible();
  await expect(detail.locator('img[src*="/receipt"]')).toBeVisible();

  log('exactly ONE Approve (and one Reject) control per order — none left in the table row');
  const orderCount = await page.locator('.detail-toggle').count();
  expect(orderCount).toBeGreaterThan(0);
  await expect(page.getByRole('button', { name: 'Approve & issue tickets' })).toHaveCount(orderCount);
  await expect(page.getByRole('button', { name: 'Reject', exact: true })).toHaveCount(orderCount);
  await expect(row.getByRole('button', { name: /Approve/ })).toHaveCount(0);
  await expect(row.getByRole('button', { name: /Reject/ })).toHaveCount(0);

  log('approving from the open detail clears the row (cleanup)');
  await detail.getByRole('button', { name: /Approve/ }).click();
  await expect(page.locator('tr').filter({ hasText: PEND_EMAIL })).toHaveCount(0);
});

test('bg. draft preview (#15): owner sees the amber banner, outsiders get a 404, browse stays clean', async ({ page }) => {
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('saving "E2E Draft Symposium" as a DRAFT from the wizard sticky bar');
  await wizardBasics(page, {
    title: 'E2E Draft Symposium', daysAhead: 6, start: '10:00',
    venue: 'E2E Hall B', city: 'Erbil', category: 'TECH',
  });
  await page.locator('#nextBtn').click(); // step 3
  await page.locator('input[name="ttName"]').first().fill('Pass');
  await page.locator('input[name="ttPrice"]').first().fill('15000');
  await page.locator('input[name="ttQty"]').first().fill('25');
  await page.locator('#nextBtn').click(); // step 4
  await page.locator('#nextBtn').click(); // step 5
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');
  await page.locator('#finalBtns button[value="draft"]').click();

  log('the console shows the Draft badge and a "Preview event" link');
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await expect(page.getByText('Draft', { exact: true }).first()).toBeVisible();
  const previewLink = page.getByRole('link', { name: /Preview event/ });
  await expect(previewLink).toBeVisible();
  const draftHref = await previewLink.getAttribute('href');
  expect(draftHref).toContain('/events/');

  log('OWNER preview: 200 with the amber "Draft preview" banner and the not-on-sale rail');
  const ownerRes = await page.goto(draftHref);
  expect(ownerRes.status(), 'owner must reach the draft preview').toBe(200);
  await expect(page.getByText(/Draft preview — only you can see this page/).first()).toBeVisible();
  await expect(page.getByText('Draft — not on sale yet')).toBeVisible();
  await expect(page.locator('h1')).toContainText('E2E Draft Symposium');

  log('the draft never shows up on public browse');
  await page.goto('/browse?q=E2E%20Draft%20Symposium');
  await expect(page.locator(EVENT_CARD_LINKS)).toHaveCount(0);
  await expect(page.getByText('No events match')).toBeVisible();

  log('OUTSIDER (another signed-in user): the draft URL is a plain 404');
  await signOut(page);
  await login(page, E2E_EMAIL, E2E_PASSWORD);
  const outsiderRes = await page.goto(draftHref);
  expect(outsiderRes.status(), 'outsiders must get a 404 for drafts').toBe(404);
  await expect(page.getByText(/Error 404/i).first()).toBeVisible();
});

test('bh. branding (#8/#9): brand color reaches the organizer banner; cover position appears only with a cover', async ({ page }) => {
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('reading the HOST2 organizer handle from settings');
  await page.goto('/host/settings');
  const handle = await page.locator('#org-handle').inputValue();
  expect(handle.length, 'org handle must be present').toBeGreaterThan(0);

  log('Branding tab: NO cover yet → the cover-position slider must NOT render (negative case)');
  await page.goto('/host/settings?tab=brand');
  await expect(page.locator('#st-brand')).toBeVisible();
  await expect(page.getByText('No cover yet')).toBeVisible();
  await expect(page.locator('#cover-focus')).toHaveCount(0);

  log('picking brand color #22c55e (input[type=color] set via JS + input/change events)');
  await page.locator('#brand-color').evaluate((el, v) => {
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }, '#22c55e');
  await expect(page.locator('#brand-hex')).toHaveText('#22c55e'); // live readout follows

  log('uploading a profile cover and saving branding');
  await page.locator('#st-brand input[name="cover"]').setInputFiles(RECEIPT_PNG);
  await page.locator('#st-brand').getByRole('button', { name: 'Save changes' }).click();
  await expect(page).toHaveURL(/tab=brand/);

  log('with a cover saved the position slider appears, defaulting to 50');
  await expect(page.locator('#cover-focus')).toBeVisible();
  await expect(page.locator('#cover-focus')).toHaveValue('50');

  log('public organizer banner: inline style carries the brand hex, cover crops at 50%');
  await page.goto(`/organizers/${handle}`);
  const banner = page.locator('div.h-44').first();
  await expect(banner).toBeVisible();
  const bannerStyle = (await banner.getAttribute('style')) || '';
  expect(bannerStyle, 'banner inline style must contain the saved brand hex').toContain('22c55e');
  const coverImg = banner.locator('img').first();
  await expect(coverImg).toBeVisible();
  expect(await coverImg.getAttribute('style')).toContain('50% 50%');

  log('moving the crop focus to 20% and saving');
  await page.goto('/host/settings?tab=brand');
  await page.locator('#cover-focus').evaluate((el) => {
    el.value = '20';
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('#cover-focus')).toHaveValue('20');
  await page.locator('#st-brand').getByRole('button', { name: 'Save changes' }).click();
  await expect(page).toHaveURL(/tab=brand/);
  await expect(page.locator('#cover-focus')).toHaveValue('20');

  log('the public cover now crops 20% from the top');
  await page.goto(`/organizers/${handle}`);
  expect(await page.locator('div.h-44 img').first().getAttribute('style')).toContain('50% 20%');
});

// ---------- round 11: Arabic-first, /en prefix, governorates, TBA ticket PDF,
// ----------           wizard autosave, places-picker fallback ----------

// Arabic month names (JDK/CLDR "ar" locale — covers both Gregorian naming systems
// used across Arabic locales). Dates render with Arabic words + Western digits.
const ARABIC_MONTHS =
  /(يناير|فبراير|مارس|أبريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر|كانون|شباط|آذار|نيسان|أيار|حزيران|تموز|آب|أيلول|تشرين)/;

test('bi. Arabic default: bare URLs render RTL Arabic with د.ع prices and Arabic governorates', async ({ browser }) => {
  // Fresh context WITHOUT the lang cookie — the site must default to Arabic.
  const context = await browser.newContext();
  const p = await context.newPage();
  try {
    log('cookie-less GET / stays on the bare URL (no /en redirect)');
    await p.goto('/');
    await expect(p).not.toHaveURL(/\/en(\/|\?|$)/);

    log('html carries lang="ar" dir="rtl"');
    await expect(p.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(p.locator('html')).toHaveAttribute('dir', 'rtl');

    log('the navbar shows the Arabic "Browse events" label (ar public.nav.browse)');
    await expect(p.locator('header').getByText('تصفح الفعاليات').first()).toBeVisible();

    log('prices on the home page use the Arabic dinar suffix د.ع');
    await expect(p.getByText(/د\.ع/).first()).toBeVisible();

    log('/browse (bare) renders the Arabic heading and Arabic governorate labels');
    await p.goto('/browse');
    await expect(p).not.toHaveURL(/\/en\//);
    await expect(p.locator('h1').first()).toHaveText('تصفح الفعاليات');
    await expect(
      p.locator('select[name="city"] option[value="Baghdad"]').first()
    ).toHaveText('بغداد');
  } finally {
    await context.close();
  }
});

test('bj. language switching: EN switcher → /en LTR English, AR switcher → bare RTL, cookie persists', async ({ browser }) => {
  const context = await browser.newContext(); // no lang cookie — start Arabic
  const p = await context.newPage();
  try {
    log('Arabic home: the navbar language toggle links to /en');
    await p.goto('/');
    await expect(p.locator('html')).toHaveAttribute('lang', 'ar');
    await p.locator('header a[href="/en"]').first().click();

    log('now on /en: html lang="en" dir="ltr" with the English navbar');
    await expect(p).toHaveURL(/\/en(\/|\?|$)/);
    await expect(p.locator('html')).toHaveAttribute('lang', 'en');
    await expect(p.locator('html')).toHaveAttribute('dir', 'ltr');
    await expect(p.locator('header').getByText('Browse events').first()).toBeVisible();

    log('the AR switcher (/set-lang?to=ar) returns to the bare Arabic home');
    await p.locator('header a[href^="/set-lang?to=ar"]').first().click();
    await expect(p).not.toHaveURL(/\/en(\/|\?|$)/);
    await expect(p.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(p.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(p.locator('header').getByText('تصفح الفعاليات').first()).toBeVisible();

    log('choosing EN again: the lang cookie makes a BARE goto(/browse) bounce onto /en/browse');
    await p.locator('header a[href="/en"]').first().click();
    await expect(p.locator('html')).toHaveAttribute('lang', 'en');
    await p.goto('/browse');
    await expect(p).toHaveURL(/\/en\/browse/);
    await expect(p.locator('h1').first()).toHaveText('Browse events');
  } finally {
    await context.close();
  }
});

test('bk. /en deep equivalence: the same event page renders English under /en and Arabic bare', async ({ page, browser }) => {
  log('/en/events/... renders the familiar ENGLISH event page');
  await page.goto('/en/events/baghdad-nights-music-festival');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.locator('h1')).toContainText('Baghdad Nights Music Festival');
  await expect(page.getByText('General Admission').first()).toBeVisible();
  await expect(page.getByText(/35,000\s*IQD/).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Lineup' })).toBeVisible();

  log('fresh cookie-less context: the BARE URL renders the ARABIC page with the same structure');
  const context = await browser.newContext();
  const p = await context.newPage();
  try {
    await p.goto('/events/baghdad-nights-music-festival');
    await expect(p).not.toHaveURL(/\/en\//);
    await expect(p.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(p.locator('html')).toHaveAttribute('dir', 'rtl');

    log('title is user data — identical in Arabic');
    await expect(p.locator('h1')).toContainText('Baghdad Nights Music Festival');

    log('the date line uses an Arabic month name');
    await expect(p.getByText(ARABIC_MONTHS).first()).toBeVisible();

    log('same structural blocks: ticket price in د.ع, Arabic Lineup heading, tag chips');
    await expect(p.getByText('General Admission').first()).toBeVisible();
    await expect(p.getByText(/35,000\s*د\.ع/).first()).toBeVisible();
    await expect(p.getByRole('heading', { name: 'برنامج العرض' })).toBeVisible();
    await expect(p.getByText('DJ Rafi')).toBeVisible();
    await expect(p.getByRole('link', { name: '#family-friendly' })).toBeVisible();
  } finally {
    await context.close();
  }
});

test('bl. 19 governorates: profile city select — English values everywhere, Arabic labels in Arabic mode', async ({ page }) => {
  await login(page, BUYER_EMAIL, buyerPassword);

  log('/me/profile (English): 19 governorate options + the placeholder');
  await page.goto('/me/profile');
  const citySelect = page.locator('#pf-city');
  await expect(citySelect.locator('option')).toHaveCount(20); // 19 + "Choose your city…"
  for (const g of ['Halabja', 'Kirkuk', 'Dhi Qar']) {
    log(`English mode lists "${g}" with the English label`);
    await expect(citySelect.locator(`option[value="${g}"]`)).toHaveText(g);
  }

  log('switching THIS session to Arabic via /set-lang keeps the login and localizes labels');
  await page.goto('/set-lang?to=ar&next=/me/profile');
  await expect(page).not.toHaveURL(/\/en\//);
  await expect(page.locator('html')).toHaveAttribute('lang', 'ar');

  log('Arabic labels — option VALUES stay canonical English');
  await expect(page.locator('#pf-city option')).toHaveCount(20);
  await expect(page.locator('#pf-city option[value="Halabja"]')).toHaveText('حلبجة');
  await expect(page.locator('#pf-city option[value="Baghdad"]')).toHaveText('بغداد');
  await expect(page.locator('#pf-city option[value="Dhi Qar"]')).toHaveText('ذي قار');
});

test('bm. PDF regression (#2): per-ticket PDF renders for the TBA-located event', async ({ page }) => {
  // The E2E Online Meetup was RSVP\'d by the buyer in test av and flipped to
  // "To be announced" in test aw — exactly the LazyInit shape that 500\'d before.
  await login(page, BUYER_EMAIL, buyerPassword);

  log('grabbing the per-ticket PDF link from the /me/tickets stub of the TBA event');
  await page.goto('/me/tickets');
  const orderCard = page.locator('article').filter({ hasText: ONLINE_EVENT_TITLE }).first();
  await expect(orderCard).toBeVisible();
  const pdfHref = await orderCard
    .getByRole('link', { name: 'PDF', exact: true })
    .first()
    .getAttribute('href');
  expect(pdfHref, 'ticket stub must link the per-ticket PDF').toContain('/ticket.pdf');

  log(`GET ${pdfHref} → 200 application/pdf (this 500\'d before the LazyInit fix)`);
  const res = await page.request.get(pdfHref);
  expect(res.status(), 'TBA-event ticket PDF must not 500').toBe(200);
  expect(res.headers()['content-type']).toContain('application/pdf');
  expect((await res.body()).slice(0, 4).toString()).toBe('%PDF');
});

test('bn. wizard autosave: banner offers Restore (repopulates) and Discard (clears the draft)', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host/events/new');
  await expect(page.locator('#stepIndicator')).toHaveText('Step 1 of 5');
  await expect(page.locator('#autosaveBanner')).toBeHidden(); // nothing saved yet

  log('typing a title — the 1s debounced autosave must land in localStorage');
  await page.locator('#ev-title').fill('E2E Autosave Draft');
  await expect
    .poll(async () => page.evaluate(() => localStorage.getItem('ievent-wizard-draft')), {
      timeout: 10_000,
      message: 'autosave should write the local draft within a few seconds',
    })
    .toContain('E2E Autosave Draft');

  log('reload → the restore banner appears but NEVER auto-applies');
  await page.reload();
  await expect(page.locator('#autosaveBanner')).toBeVisible();
  await expect(page.locator('#ev-title')).toHaveValue('');

  log('Restore → the title repopulates and the banner hides');
  await page.locator('#autosaveRestoreBtn').click();
  await expect(page.locator('#ev-title')).toHaveValue('E2E Autosave Draft');
  await expect(page.locator('#autosaveBanner')).toBeHidden();

  log('Discard path: save a different draft, reload, Discard → banner gone + form empty');
  await page.locator('#ev-title').fill('E2E Autosave Discarded');
  await expect
    .poll(async () => page.evaluate(() => localStorage.getItem('ievent-wizard-draft')), {
      timeout: 10_000,
      message: 'autosave should persist the edited title',
    })
    .toContain('E2E Autosave Discarded');
  await page.reload();
  await expect(page.locator('#autosaveBanner')).toBeVisible();
  await page.locator('#autosaveDiscardBtn').click();
  await expect(page.locator('#autosaveBanner')).toBeHidden();
  await expect(page.locator('#ev-title')).toHaveValue('');

  log('Discard removes the localStorage draft entirely');
  await expect
    .poll(async () => page.evaluate(() => localStorage.getItem('ievent-wizard-draft')), {
      timeout: 5_000,
      message: 'Discard must remove the local draft',
    })
    .toBeNull();
});

test('bo. places picker fallback: without a Maps key the manual fields render and no Places wiring exists', async ({ page }) => {
  // CI runs without GOOGLE_MAPS_KEY — the wizard must fall back to plain inputs.
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host/events/new');
  await expect(page.locator('#stepIndicator')).toHaveText('Step 1 of 5');

  log('manual venue fields all render');
  await expect(page.locator('#ev-venue')).toBeVisible();
  await expect(page.locator('#ev-address')).toBeVisible();
  await expect(page.locator('#ev-maps')).toBeVisible();

  log('negative: no #placesCfg config node, no maps script, no map preview shell');
  await expect(page.locator('#placesCfg')).toHaveCount(0);
  await expect(page.locator('script[src*="maps.googleapis"]')).toHaveCount(0);
  await expect(page.locator('#mapPreviewWrap')).toHaveCount(0);
});

// ---------- round 12: dynamic fee card, edit autosave tick, checklist lifecycle ----------
// (bp — Arabic wizard category labels — lives in SmokeTest as
//  wizardCategoryLabelsLocalize: cheaper than a no-cookie login dance here.)

test('bq. edit fee card (R12): price-derived pass/absorb examples; free toggle hides #editFeeCard', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the edit page of "E2E Absorb Gala" (single GA type at 20,000 IQD)');
  await page.goto('/host/events?q=E2E+Absorb');
  await page.getByRole('link', { name: /E2E Absorb Gala/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await page.getByRole('link', { name: /Edit event/ }).click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);

  log('#editFeeCard is visible for a paid event, with notes rewritten from the 20,000 top price');
  const feeCard = page.locator('#editFeeCard');
  await expect(feeCard).toBeVisible();
  await expect(feeCard.locator('.fee-note-pass')).toContainText('21,500'); // 20,000 + 1,500
  await expect(feeCard.locator('.fee-note-pass')).toContainText('20,000');
  await expect(feeCard.locator('.fee-note-absorb')).toContainText('18,500'); // 20,000 − 1,500

  log('"My event is free" zeroes + locks every price input and HIDES the fee card');
  const firstPrice = page.locator('#ticketTypes input[name="price"]').first();
  await expect(firstPrice).toHaveValue('20000');
  await page.locator('label').filter({ has: page.locator('#freeToggle') }).click();
  await expect(page.locator('#freeToggle')).toBeChecked();
  await expect(firstPrice).toHaveValue('0');
  await expect(firstPrice).toHaveJSProperty('readOnly', true);
  await expect(feeCard).toBeHidden();

  log('flipping the toggle back restores the remembered price and the fee card (nothing saved server-side)');
  await page.locator('label').filter({ has: page.locator('#freeToggle') }).click();
  await expect(page.locator('#freeToggle')).not.toBeChecked();
  await expect(firstPrice).toHaveValue('20000');
  await expect(firstPrice).toHaveJSProperty('readOnly', false);
  await expect(feeCard).toBeVisible();
  await expect(feeCard.locator('.fee-note-pass')).toContainText('21,500');
});

test('br. edit autosave (R12): #autosaveTick appears on changes, hides on revert; reload offers Discard', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the edit page of "E2E Concert Night (Updated)"');
  await page.goto('/host/events?q=E2E+Concert');
  await page.getByRole('link', { name: /E2E Concert Night/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await page.getByRole('link', { name: /Edit event/ }).click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);

  const autosaveKey = await page.locator('#editForm').getAttribute('data-autosave-key');
  expect(autosaveKey, 'edit form must carry its per-event autosave key').toContain('ievent-edit-');

  log('pristine form: the "Draft saved on this device" tick is hidden');
  const tick = page.locator('#autosaveTick');
  await expect(tick).toBeHidden();

  log('editing the summary → after the 1s debounce the tick becomes visible');
  const summary = page.locator('#ev-summary');
  const originalSummary = await summary.inputValue();
  await summary.fill('E2E autosave probe — round 12');
  await expect(tick).toBeVisible({ timeout: 10_000 });

  log('reverting to the server value → the draft is dropped and the tick hides again');
  await summary.fill(originalSummary);
  await expect(tick).toBeHidden({ timeout: 10_000 });
  await expect
    .poll(async () => page.evaluate((k) => localStorage.getItem(k), autosaveKey), {
      timeout: 10_000,
      message: 'a draft equal to server state must be removed from localStorage',
    })
    .toBeNull();

  log('changing again, then reloading → the restore banner appears (never auto-applies)');
  await summary.fill('E2E autosave probe — round 12');
  await expect(tick).toBeVisible({ timeout: 10_000 });
  await page.reload();
  await expect(page.locator('#autosaveBanner')).toBeVisible();
  await expect(page.locator('#ev-summary')).toHaveValue(originalSummary); // not auto-applied

  log('Discard → banner gone, server value kept, local draft removed');
  await page.locator('#autosaveDiscardBtn').click();
  await expect(page.locator('#autosaveBanner')).toBeHidden();
  await expect(page.locator('#ev-summary')).toHaveValue(originalSummary);
  await expect
    .poll(async () => page.evaluate((k) => localStorage.getItem(k), autosaveKey), {
      timeout: 5_000,
      message: 'Discard must remove the local edit draft',
    })
    .toBeNull();
});

test('bs. checklist (R13): pinned at the top for a new org and STAYS after the first publish until allDone', async ({ page }) => {
  await signOut(page);
  await registerUser(page, { name: HOST4_NAME, email: HOST4_EMAIL, password: HOST4_PASSWORD });
  await login(page, HOST4_EMAIL, HOST4_PASSWORD);

  log('running the onboarding funnel for the brand-new org');
  await page.goto('/host');
  await expect(page).toHaveURL(/\/host\/start/);
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click();
  await page.locator('#orgName').fill('E2E Lifecycle Org');
  await page.getByRole('button', { name: /Create organizer profile/ }).click();
  await expect(page).toHaveURL(/\/host(\/)?$/);

  log('brand-new org: To-do card visible ABOVE the stat cards, zero items checked');
  const todo = page.locator('section[aria-label="To-do checklist"]');
  await expect(todo).toBeVisible();
  await expect(todo.getByText('Set up payments')).toBeVisible();
  await expect(todo.locator('span.bg-green-600')).toHaveCount(0);
  let todoBox = await todo.boundingBox();
  let statsBox = await page.getByText('Page views').first().boundingBox();
  expect(todoBox, 'To-do card must have a bounding box').toBeTruthy();
  expect(statsBox, 'stat card must have a bounding box').toBeTruthy();
  expect(todoBox.y, 'To-do card must sit above the stat cards').toBeLessThan(statsBox.y);

  log('publishing the org\'s FIRST event (free — no payment setup needed)');
  await wizardBasics(page, {
    title: 'E2E Lifecycle Launch', daysAhead: 5, start: '18:30',
    venue: 'E2E Lifecycle Hall', city: 'Baghdad', category: 'COMMUNITY',
  });
  await page.locator('#nextBtn').click(); // step 2 · banner — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');
  await page.locator('input[name="ttName"]').first().fill('Entry');
  await page.locator('input[name="ttPrice"]').first().fill('0');
  await page.locator('input[name="ttQty"]').first().fill('20');
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click(); // step 4 · details — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');
  await page.locator('#finalBtns button[value="publish"]').click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible();

  log('round 13: after the FIRST publish the card STAYS (payments/branding/team still open), publish item now checked');
  await page.goto('/host');
  await expect(page.locator('body')).not.toContainText('Something went wrong');
  const todoAfter = page.locator('section[aria-label="To-do checklist"]');
  await expect(todoAfter).toBeVisible();
  await expect(todoAfter.locator('span.bg-green-600')).toHaveCount(1);
  await expect(todoAfter.getByText('Set up payments')).toBeVisible();
  todoBox = await todoAfter.boundingBox();
  statsBox = await page.getByText('Page views').first().boundingBox();
  expect(todoBox, 'To-do card must still have a bounding box').toBeTruthy();
  expect(statsBox, 'stat card must still have a bounding box').toBeTruthy();
  expect(todoBox.y, 'To-do card stays pinned above the stat cards').toBeLessThan(statsBox.y);
  // The dismissal path for this card is covered separately in test be.
});

// ---------- round 13: payment-readiness warnings, rich text, fee-card gating ----------

test('bt. payment warnings (R13): review #rvPayWarn, publish flash, persistent console banner until payments exist', async ({ page }) => {
  // HOST3 (from test be) owns an org with NO payment setup — exactly the warning case.
  await login(page, HOST3_EMAIL, HOST3_PASSWORD);

  log('wizard with a PAID ticket for a payments-less org');
  await wizardBasics(page, {
    title: 'E2E PayWarn Concert', daysAhead: 6, start: '20:30',
    venue: 'E2E PayWarn Hall', city: 'Baghdad', category: 'MUSIC',
  });
  await page.locator('#nextBtn').click(); // step 2 · banner — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');

  log('the wizard form carries data-payments-ready="0" for this org');
  await expect(page.locator('#createForm')).toHaveAttribute('data-payments-ready', '0');
  await page.locator('input[name="ttName"]').first().fill('GA');
  await page.locator('input[name="ttPrice"]').first().fill('15000');
  await page.locator('input[name="ttQty"]').first().fill('30');
  await page.locator('#nextBtn').click();
  await page.locator('#nextBtn').click(); // step 4 · details — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 5 of 5');

  log('review step shows the #rvPayWarn soft warning (paid tickets + payments not ready)');
  await expect(page.locator('#rvPayWarn')).toBeVisible();

  log('publishing anyway → the amber warning FLASH lands on the console');
  await page.locator('#finalBtns button[value="publish"]').click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const payWarnEventId = page.url().match(/\/host\/events\/(\d+)/)[1];
  await expect(page.getByText(/payments aren't set up yet/).first()).toBeVisible();

  log('the PERSISTENT banner + "Set up payments" CTA survive a plain reload (not just the flash)');
  await page.goto(`/host/events/${payWarnEventId}`);
  await expect(
    page.getByText(/This event has paid tickets but you haven't set up payments/).first()
  ).toBeVisible();
  await expect(page.getByRole('link', { name: 'Set up payments' })).toBeVisible();

  log('setting up payments (enable + first method) …');
  await page.goto('/host/settings/payments');
  await page.locator('label[title="Enable direct payments"]').click();
  await expect(page.locator('input[name="enabled"]')).toBeChecked();
  await page.getByRole('button', { name: 'Save toggle' }).click();
  await expect(page.locator('input[name="enabled"]')).toBeChecked();
  await page.locator('#pm-label').fill('BT Wallet');
  await page.locator('#pm-number').fill('0790 222 3333');
  await page.getByRole('button', { name: 'Add payment method' }).click();
  await expect(page.getByText(/Payment method "BT Wallet" added/)).toBeVisible();

  log('… clears the console banner (event stays Live)');
  await page.goto(`/host/events/${payWarnEventId}`);
  await expect(page.getByText(/haven't set up payments/)).toHaveCount(0);
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible();
});

test('bu. rich text (R13): bullet list authored in #ev-desc-editor reaches the public page as real markup', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('opening the E2E Freebie Fair edit page');
  await page.goto('/host/events?q=Freebie');
  await page.getByRole('link', { name: /E2E Freebie Fair/ }).first().click();
  await expect(page).toHaveURL(/\/host\/events\/\d+/);
  const publicHref = await page
    .getByRole('link', { name: /View public page/ })
    .getAttribute('href');
  await page.getByRole('link', { name: /Edit event/ }).click();
  await expect(page).toHaveURL(/\/host\/events\/\d+\/edit/);

  log('R13: the plain description textarea is hidden; contenteditable editor + toolbar render instead');
  await expect(page.locator('textarea[name="description"]')).toBeHidden();
  const editor = page.locator('#ev-desc-editor');
  await expect(editor).toBeVisible();
  await expect(page.locator('[data-rt-for="ev-desc-editor"][data-cmd="bold"]')).toBeVisible();

  log('authoring a bullet list (needs no selection): list button, then two typed lines');
  await editor.click();
  await page.locator('[data-rt-for="ev-desc-editor"][data-cmd="insertUnorderedList"]').click();
  await page.keyboard.type('First E2E point');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Second E2E point');

  log('the editor mirrors its HTML into the hidden textarea on input');
  await expect
    .poll(async () => page.locator('textarea[name="description"]').inputValue(), {
      timeout: 5_000,
      message: 'hidden description textarea should receive the list markup',
    })
    .toContain('<li>');

  log('saving the edit form');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText(/Saved ✓/).first()).toBeVisible();

  log('the public page renders the sanitized list inside div.rich-text');
  await page.goto(publicHref);
  await expect(page.locator('.rich-text li')).toHaveCount(2);
  await expect(page.locator('.rich-text li').first()).toHaveText('First E2E point');
  await expect(page.locator('.rich-text li').nth(1)).toHaveText('Second E2E point');
});

test('bv. wizard fee card gating (R13): hidden until a positive price, dynamic example, hidden again at zero', async ({ page }) => {
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);

  log('reaching wizard step 3 with minimal basics');
  await wizardBasics(page, {
    title: 'E2E Fee Gate Probe', daysAhead: 8, start: '19:00',
    venue: 'E2E Gate Hall', city: 'Baghdad', category: 'MUSIC',
  });
  await page.locator('#nextBtn').click(); // step 2 · banner — skip
  await expect(page.locator('#stepIndicator')).toHaveText('Step 3 of 5');

  log('R13: with no price typed yet #feeCard is hidden (no generic example anymore)');
  await expect(page.locator('#feeCard')).toBeHidden();
  await expect(page.locator('#feeFreeNote')).toBeHidden(); // free toggle is off → no free note either

  log('typing ttPrice 20000 → the card appears with the price-derived notes');
  await page.locator('input[name="ttPrice"]').first().fill('20000');
  await expect(page.locator('#feeCard')).toBeVisible();
  await expect(page.locator('#feeCard .fee-note-pass')).toContainText('21,500'); // 20,000 + 1,500
  await expect(page.locator('#feeCard .fee-note-pass')).toContainText('20,000');
  await expect(page.locator('#feeCard .fee-note-absorb')).toContainText('18,500'); // 20,000 − 1,500

  log('clearing the price back to 0 hides the card again (this wizard is abandoned — nothing published)');
  await page.locator('input[name="ttPrice"]').first().fill('0');
  await expect(page.locator('#feeCard')).toBeHidden();
});
