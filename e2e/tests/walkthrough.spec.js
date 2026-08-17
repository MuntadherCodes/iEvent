// @ts-check
/**
 * iEvent end-to-end walkthrough (serial).
 *
 * Assumes the docker-compose stack is up with the demo seed loaded:
 *   SEED_DEMO=true  -> organizer "Zain Events Co." (@zainevents, direct payments
 *                      ENABLED, card 5326 1102 4478 4821; host login
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
  const cards = page.locator('main a[href^="/events/"]');
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
          .locator('main a[href^="/events/"]:not([href*="baghdad-nights-music-festival"])')
          .count(),
      { message: 'expected related event cards' }
    )
    .toBeGreaterThan(0);

  log('ticket stepper: +1 General Admission should total 36,500 IQD (35,000 + 1,500 fee)');
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
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
    for (const url of ['/auth/login', '/auth/register']) {
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
    await freshPage.goto('/auth/login');
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

  log('stepper: +1 RSVP, then submit the rail form ("Get tickets")');
  await page.getByRole('button', { name: 'Add one RSVP ticket' }).click();
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
  log('buyer opens Baghdad Nights and picks 1 General Admission');
  await page.goto('/auth/login');
  await login(page, BUYER_EMAIL, buyerPassword);
  await page.goto('/events/baghdad-nights-music-festival');
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
  await expect(page.locator('#railTotal')).toHaveText(/36,500\s*IQD/);
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);

  log('direct-transfer panel with the organizer card number should be visible');
  await expect(page.getByText('Direct transfer to organizer')).toBeVisible();
  await expect(page.getByText('5326 1102 4478 4821')).toBeVisible();

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

  log('the row should link to the uploaded receipt and show the reference');
  await expect(row.getByRole('link', { name: /Receipt/ })).toBeVisible();
  await expect(row.getByText('E2E-REF-1')).toBeVisible();

  log('approving the order');
  await row.getByRole('button', { name: /Approve/ }).click();
  await expect(page.getByText(/approved — tickets emailed to the buyer/).first()).toBeVisible();

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

  log('buyer picks GA ×1 on Baghdad Nights and goes to checkout');
  await page.goto('/events/baghdad-nights-music-festival');
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
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

  log('fahad approves the promo order');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?status=pending');
  const row = page.locator('tr').filter({ hasText: BUYER_EMAIL }).first();
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: /Approve/ }).click();
  await expect(page.getByText(/approved — tickets emailed to the buyer/).first()).toBeVisible();

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
    .poll(async () => page.locator('main a[href^="/events/"]').count(), {
      message: 'expected event cards within the next 7 days',
    })
    .toBeGreaterThan(0);

  log('the "Today" chip may match zero events — page must render cards or the empty state');
  await page.getByRole('link', { name: 'Today', exact: true }).click();
  await expect(page).toHaveURL(/when=today/);
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');
  const cardCount = await page.locator('main a[href^="/events/"]').count();
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
  if ((await page.locator('main a[href^="/events/"]').count()) === 0) {
    await expect(page.getByText('No events match')).toBeVisible();
  }

  log('"This month" when-pill has seeded events inside 31 days');
  await page.getByRole('link', { name: 'This month', exact: true }).click();
  await expect(page).toHaveURL(/when=month/);
  await expect
    .poll(async () => page.locator('main a[href^="/events/"]').count(), {
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

  log('"Get directions" opens a maps.google.com link');
  const directions = page.getByRole('link', { name: /Get directions/ });
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
  if ((await page.locator('#tab-past a[href^="/events/"]').count()) === 0) {
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

  log('fahad\'s mature org hides or shows the To-do card without erroring');
  const body = await page.locator('body').innerText();
  expect(body).not.toContain('Something went wrong');

  log('the fresh E2E host org still shows the To-do checklist (payments not set up)');
  await signOut(page);
  await login(page, HOST2_EMAIL, HOST2_PASSWORD);
  await page.goto('/host');
  await expect(page.getByRole('heading', { name: 'To-do' })).toBeVisible();
  await expect(page.getByText('Set up payments')).toBeVisible();
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
  await page.getByRole('button', { name: 'Add one General Admission ticket' }).click();
  await page.getByRole('button', { name: /Get tickets/ }).click();
  await expect(page).toHaveURL(/\/events\/baghdad-nights-music-festival\/checkout/);
  await page.locator('#transferReference').fill('E2E-REF-REFUND');
  await page.locator('#receiptInput').setInputFiles(RECEIPT_PNG);
  await page.locator('#submitBtn').click();
  await expect(page).toHaveURL(/\/orders\//);
  const refundOrderCode = page.url().match(/\/orders\/([A-Z0-9-]+)/)[1];
  log(`captured refund-flow order code: ${refundOrderCode}`);

  log('host approves the order from the pending queue');
  await signOut(page);
  await login(page, DEMO_HOST_EMAIL, DEMO_HOST_PASSWORD);
  await page.goto('/host/orders?status=pending');
  const pendingRow = page.locator('tr').filter({ hasText: REFUND_EMAIL }).first();
  await expect(pendingRow).toBeVisible();
  await pendingRow.getByRole('button', { name: /Approve/ }).click();
  await expect(page.getByText(/approved — tickets emailed to the buyer/).first()).toBeVisible();

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
  await orderRow.locator('.detail-toggle').click();
  await page.getByRole('button', { name: /Refund order/ }).click();
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
