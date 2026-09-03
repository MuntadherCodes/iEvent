package iq.ievent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests against the full Spring context with the demo seed enabled.
 * The datasource comes from the environment (CI provides a postgres:16
 * service via SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD).
 *
 * Round 11 (Arabic-first URLs): bare paths render ARABIC; English lives under
 * /en/** (the LocaleFilter strips the prefix before controllers/security see
 * the path). Migration rules applied here:
 *  - page GETs that assert ENGLISH copy request the /en/... path;
 *  - status-only page GETs (404s, auth redirects) stay bare — they exercise the
 *    Arabic default and their outcomes are locale-independent;
 *  - POSTs stay bare (never language-redirected) and their redirectedUrl(...)
 *    targets KEEP bare values (controller redirects are un-prefixed; the /en
 *    bounce only happens on the browser's follow-up GET, which MockMvc never
 *    issues) — EXCEPT POSTs asserting an English 200 re-render, which must POST
 *    to /en/... so the re-render localizes to English;
 *  - asset/API/binary paths (/js, /media, /api, .csv, .pdf, .png, .ics) are
 *    never language-handled and stay bare.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.seed.demo=true", "app.upload-dir=${java.io.tmpdir}/ievent-test-uploads"})
class SmokeTest {

    /** Seeded demo host (owner of @zainevents) — loaded by the demo seed. */
    private static final String DEMO_HOST_EMAIL = "fahad@zainevents.iq";

    /** Seeded demo buyer (plain USER) — loaded by the demo seed. */
    private static final String DEMO_BUYER_EMAIL = "amira@example.iq";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private iq.ievent.repo.EventRepository events;

    @Autowired
    private iq.ievent.repo.OrganizationRepository organizations;

    @Autowired
    private iq.ievent.repo.TicketRepository tickets;

    @Autowired
    private iq.ievent.service.EventStatusSweeper statusSweeper;

    @Autowired
    private iq.ievent.repo.EventImageRepository eventImages;

    @Autowired
    private iq.ievent.repo.LikeCountRepository likeCounts;

    @Autowired
    private iq.ievent.repo.UserRepository usersRepo;

    @Test
    void homeRendersAndMentionsIevent() throws Exception {
        // English home lives under /en (the bare root is covered by arabicDefaultAtRoot).
        mockMvc.perform(get("/en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("iEvent")));
    }

    @Test
    void browseRenders() throws Exception {
        mockMvc.perform(get("/en/browse"))
                .andExpect(status().isOk());
    }

    @Test
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/en/auth/login"))
                .andExpect(status().isOk());
    }

    @Test
    void registerPageRenders() throws Exception {
        mockMvc.perform(get("/en/auth/register"))
                .andExpect(status().isOk());
    }

    @Test
    void seededEventDetailRenders() throws Exception {
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("General Admission")));
    }

    @Test
    void unknownEventSlugIs404() throws Exception {
        // Status-only — stays bare and exercises the Arabic default path.
        mockMvc.perform(get("/e/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void myTicketsRequiresLogin() throws Exception {
        mockMvc.perform(get("/me/tickets"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/login"));
    }

    @Test
    void hostAreaRequiresLogin() throws Exception {
        mockMvc.perform(get("/host"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/login"));
    }

    @Test
    void unknownTicketCodeIs404() throws Exception {
        mockMvc.perform(get("/t/NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutPageRendersForAnonymousVisitor() throws Exception {
        // Since guest checkout (R17), anonymous visitors are not pushed to sign
        // in — the page renders the guest notice, and the promo card renders
        // for anonymous buyers too.
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival/checkout"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("check out as a guest")))
                .andExpect(content().string(containsString("Promo code")));
    }

    @Test
    void calendarIcsDownloads() throws Exception {
        mockMvc.perform(get("/e/baghdad-nights-music-festival/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andExpect(content().string(containsString("BEGIN:VEVENT")));
    }

    @Test
    void shortLinkRedirectsToEventPage() throws Exception {
        // Since the /e/ canonical-URL migration (R16), the redirect runs the
        // OTHER way: legacy /events/{slug} links bounce to the canonical /e/.
        mockMvc.perform(get("/events/baghdad-nights-music-festival"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/e/baghdad-nights-music-festival"));
    }

    @Test
    void widgetScriptIsServed() throws Exception {
        mockMvc.perform(get("/js/widget.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("iEvent")));
    }

    @Test
    void unknownEventCoverIs404() throws Exception {
        mockMvc.perform(get("/media/event-cover/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hostMarketingRequiresLogin() throws Exception {
        mockMvc.perform(get("/host/marketing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/login"));
    }

    @Test
    void unknownTicketQrPngIs404() throws Exception {
        mockMvc.perform(get("/t/NOPE/qr.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void orderTicketsPdfRequiresLogin() throws Exception {
        mockMvc.perform(get("/orders/EVT-2026-00000/tickets.pdf"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/login"));
    }

    @Test
    void registrationRedirectsToLoginWithFlag() throws Exception {
        String uniqueEmail = "smoke+" + System.currentTimeMillis() + "@test.iq";
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .param("fullName", "Smoke Tester")
                        .param("email", uniqueEmail)
                        .param("phone", "")
                        .param("password", "SmokePassw0rd!")
                        .param("terms", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?registered"));
    }

    // ---- wireframe-parity round ----

    @Test
    void registrationWithoutTermsReRendersForm() throws Exception {
        // 200 re-render asserts English copy → POST to /en (the filter strips the
        // prefix, so the same handler + CSRF processing run; only the locale flips).
        String uniqueEmail = "smoke-noterms+" + System.currentTimeMillis() + "@test.iq";
        mockMvc.perform(post("/en/auth/register")
                        .with(csrf())
                        .param("fullName", "Smoke NoTerms")
                        .param("email", uniqueEmail)
                        .param("phone", "")
                        .param("password", "SmokePassw0rd!"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please accept the Terms of Service")));
    }

    @Test
    void forgotPasswordPageRenders() throws Exception {
        mockMvc.perform(get("/en/auth/forgot"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Forgot your password")));
    }

    @Test
    void resetWithBadTokenShowsExpiredState() throws Exception {
        mockMvc.perform(get("/en/auth/reset").param("token", "bogus-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This link has expired")));
    }

    @Test
    void unknownTrackingLinkRedirectsToBrowse() throws Exception {
        mockMvc.perform(get("/l/nope"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/browse"));
    }

    @Test
    void browseWithSortAndPriceFiltersRenders() throws Exception {
        mockMvc.perform(get("/en/browse")
                        .param("sort", "popular")
                        .param("price", "paid"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Browse events")));
    }

    @Test
    void enrichedHostOrdersViewRendersForHost() throws Exception {
        mockMvc.perform(get("/en/host/orders")
                        .param("f", "1")
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Gross sales")))
                .andExpect(content().string(containsString("Refunded")));
    }

    @Test
    void attendeesCsvExportsForHost() throws Exception {
        Long eventId = events.findBySlug("baghdad-nights-music-festival")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"))
                .getId();
        mockMvc.perform(get("/host/attendees/export.csv")
                        .param("event", String.valueOf(eventId))
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("Name,Email,Ticket type,Order code")));
    }

    // ---- round 8: notification center, payment methods, location types ----

    @Test
    void notificationsSummaryReturnsJsonWithUnreadCount() throws Exception {
        mockMvc.perform(get("/api/notifications/summary").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("unread")));
    }

    @Test
    void notificationsPageRendersForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/en/me/notifications").with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Notifications")));
    }

    @Test
    void notificationsReadAllRedirects() throws Exception {
        mockMvc.perform(post("/me/notifications/read-all")
                        .with(csrf())
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/me/notifications"));
    }

    @Test
    void hostPaymentsSettingsListSeededMethod() throws Exception {
        mockMvc.perform(get("/en/host/settings/payments").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your payment methods")))
                .andExpect(content().string(containsString("ZainCash wallet")));
    }

    @Test
    void unknownPaymentQrIs404() throws Exception {
        mockMvc.perform(get("/media/payment-qr/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutShowsPaymentMethodPickerForSignedInBuyer() throws Exception {
        // Fresh databases seed exactly one enabled direct-payment method for
        // @zainevents ("ZainCash wallet") — checkout must render it in the picker.
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival/checkout")
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Direct transfer to organizer")))
                .andExpect(content().string(containsString("ZainCash wallet")));
    }

    @Test
    void addPaymentMethodWithoutLabelRedirectsBackWithErrorFlash() throws Exception {
        mockMvc.perform(multipart("/host/settings/payments/methods")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/host/settings/payments"));
    }

    // ---- round 9: checkout qty default, marketing widgets tab ----

    @Test
    void checkoutDefaultsFirstOnSaleTicketToOneOnFirstVisit() throws Exception {
        // A first GET with no qty-* params preselects 1× the first ON_SALE type
        // with stock (General Admission — Early Bird is SOLD_OUT), so exactly one
        // holder row is server-rendered.
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival/checkout")
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ticket 1 · General Admission")));
    }

    @Test
    void checkoutRespectsExplicitZeroQuantities() throws Exception {
        // Any explicit qty-* param disables the first-visit default entirely —
        // an all-zero deep link renders no holder rows at all.
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival/checkout")
                        .param("qty-0", "0")
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Ticket 1 ·"))));
    }

    // ---- round 10: login-return, draft preview, checklist dismiss ----

    @Test
    void loginPageWithNextShowsContinuationHint() throws Exception {
        // #11: a ?next= continuation renders the "take you right back" hint.
        mockMvc.perform(get("/en/auth/login")
                        .param("next", "/e/baghdad-nights-music-festival/checkout?qty-1=1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("take you right back")));
    }

    @Test
    void draftEventIsHiddenFromEveryoneButTheOwningOrg() throws Exception {
        // #15: DRAFT events 404 for anonymous visitors and non-member users, but
        // render (with the preview banner) for members of the owning org.
        var org = organizations.findByHandle("zainevents")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"));
        String slug = "smoke-draft-" + System.currentTimeMillis();
        var draft = new iq.ievent.domain.Event();
        draft.setOrganization(org);
        draft.setTitle("Smoke Draft Event");
        draft.setSlug(slug);
        draft.setCategory(iq.ievent.domain.Event.Category.COMMUNITY);
        draft.setCity("Baghdad");
        draft.setStartsAt(java.time.OffsetDateTime.now().plusDays(14));
        draft.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        events.save(draft);

        // 404s are status-only → bare (Arabic) paths; the owner render asserts the
        // ENGLISH banner copy → /en path.
        mockMvc.perform(get("/e/" + slug))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/e/" + slug).with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/en/e/" + slug).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Draft preview")));
    }

    @Test
    void checklistDismissRedirectsBackToDashboard() throws Exception {
        // #7: the To-do dismiss endpoint answers with a redirect to /host.
        mockMvc.perform(post("/host/checklist/dismiss")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/host"));
    }

    @Test
    void marketingWidgetsTabRendersEventPickerAndSnippets() throws Exception {
        // Round 9 redesign: event dropdown + per-event panels with Button/Card
        // embed snippets (HTML-escaped by th:text) and the live widget preview.
        mockMvc.perform(get("/en/host/marketing").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("widget-event-select")))
                .andExpect(content().string(containsString("data-widget-panel")))
                .andExpect(content().string(containsString(
                        "data-event=&quot;baghdad-nights-music-festival&quot;")))
                .andExpect(content().string(containsString("/js/widget.js")))
                .andExpect(content().string(containsString("POWERED BY IEVENT")))
                // round 10 rebuild: per-event share link + fixed Button/Card presets
                .andExpect(content().string(containsString("share-link-")))
                .andExpect(content().string(containsString("embed-btn-")))
                .andExpect(content().string(containsString("embed-card-")));
    }

    // ---- round 11: Arabic-first URLs, /en prefix, governorates, ticket PDF ----

    @Test
    void arabicDefaultAtRoot() throws Exception {
        // Bare URLs are the Arabic RTL site: dir="rtl", lang="ar" and the Arabic
        // navbar label (ar/public.json public.nav.browse = "تصفح الفعاليات").
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dir=\"rtl\"")))
                .andExpect(content().string(containsString("lang=\"ar\"")))
                .andExpect(content().string(containsString("تصفح الفعاليات")));
    }

    @Test
    void englishUnderPrefix() throws Exception {
        mockMvc.perform(get("/en/browse"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dir=\"ltr\"")))
                .andExpect(content().string(containsString("Browse events")));
    }

    @Test
    void setLangRedirects() throws Exception {
        mockMvc.perform(get("/set-lang").param("to", "en").param("next", "/browse"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/en/browse"));
    }

    @Test
    void englishCookieBouncesBarePageGets() throws Exception {
        // A lang=en cookie holder GETting a bare page path is bounced onto /en
        // (this is how controller redirects keep English users on English pages).
        mockMvc.perform(get("/browse").cookie(new jakarta.servlet.http.Cookie("lang", "en")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/en/browse"));
    }

    @Test
    void governoratesInProfile() throws Exception {
        // All 19 governorates in the profile city select. English labels on /en …
        mockMvc.perform(get("/en/me/profile").with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Halabja")))
                .andExpect(content().string(containsString("Dhi Qar")))
                .andExpect(content().string(containsString("Kirkuk")));
        // … Arabic labels on the bare path, while option VALUES stay English.
        mockMvc.perform(get("/me/profile").with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("حلبجة")))
                .andExpect(content().string(containsString("value=\"Halabja\"")));
    }

    @Test
    void wizardCategoryLabelsLocalize() throws Exception {
        // Round 12 (#1): category labels localize via @t.category(...) while the
        // option VALUES stay the canonical enum names. (Covers the e2e "bp" slot —
        // asserting the bare-path Arabic render here is cheaper than a cookie-less
        // login dance in the Playwright suite.)
        // Arabic (bare path): Format.categoryLabel(MUSIC) = "موسيقى", value="MUSIC".
        mockMvc.perform(get("/host/events/new").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("موسيقى")))
                .andExpect(content().string(containsString("value=\"MUSIC\"")));
        // English under /en: same markup, English label.
        mockMvc.perform(get("/en/host/events/new").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Music")))
                .andExpect(content().string(containsString("value=\"MUSIC\"")));
    }

    @Test
    void descriptionSanitizedOnSave() throws Exception {
        // Round 13: rich-text descriptions are sanitized on SAVE (jsoup safelist in
        // RichText) and rendered via th:utext inside div.rich-text. Script tags and
        // javascript: URLs must never survive to the rendered page. Repo-created
        // draft + edit POST, mirroring the draftEventIsHidden... pattern.
        var org = organizations.findByHandle("zainevents")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"));
        String slug = "smoke-richtext-" + System.currentTimeMillis();
        var draft = new iq.ievent.domain.Event();
        draft.setOrganization(org);
        draft.setTitle("Smoke Rich Text Event");
        draft.setSlug(slug);
        draft.setCategory(iq.ievent.domain.Event.Category.COMMUNITY);
        draft.setCity("Baghdad");
        draft.setStartsAt(java.time.OffsetDateTime.now().plusDays(12));
        draft.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        Long id = events.save(draft).getId();

        mockMvc.perform(multipart("/host/events/" + id + "/edit")
                        .param("title", "Smoke Rich Text Event")
                        .param("category", "COMMUNITY")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("date", java.time.LocalDate.now().plusDays(12).toString())
                        .param("startTime", "18:00")
                        .param("description",
                                "<p>ok-rich</p><script>alert(1)</script><a href=\"javascript:x\">bad</a>")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());

        // Owner render of the draft: allowed markup survives, the payload doesn't.
        // (The page legitimately contains <script> tags of its own, so the negative
        // assertions target the PAYLOAD, not the tag name.)
        mockMvc.perform(get("/en/e/" + slug).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<p>ok-rich</p>")))
                .andExpect(content().string(not(containsString("alert(1)"))))
                .andExpect(content().string(not(containsString("javascript:"))));
    }

    @Test
    void arabicVenuePlaceholderRenders() throws Exception {
        // Round 14: venue placeholders flow through Format.venueDisplay — English
        // output unchanged ("To be announced"), Arabic localized ("يُعلن لاحقًا").
        // Repo-created LIVE TBA event so the placeholder reliably exists.
        var org = organizations.findByHandle("zainevents")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"));
        String slug = "smoke-tba-" + System.currentTimeMillis();
        var ev = new iq.ievent.domain.Event();
        ev.setOrganization(org);
        ev.setTitle("Smoke TBA Event");
        ev.setSlug(slug);
        ev.setCategory(iq.ievent.domain.Event.Category.COMMUNITY);
        ev.setCity("Baghdad");
        ev.setStartsAt(java.time.OffsetDateTime.now().plusDays(9));
        ev.setStatus(iq.ievent.domain.Event.Status.LIVE);
        ev.setLocationType("TBA");
        events.save(ev);

        // Bare path → Arabic placeholder (exact string from Format.venueDisplay).
        mockMvc.perform(get("/e/" + slug))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("يُعلن لاحقًا")));
        // /en → the familiar English placeholder, character-identical to before.
        mockMvc.perform(get("/en/e/" + slug))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("To be announced")));
    }

    @Test
    void ticketPdfRenders() throws Exception {
        // Round 11 regression (#2): the per-ticket PDF 500'd with a LazyInit error.
        // The demo seed creates EVT-DEMO orders with tickets for Baghdad Nights —
        // grab any seeded ticket code straight from the repository.
        Long eventId = events.findBySlug("baghdad-nights-music-festival")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"))
                .getId();
        var seeded = tickets.searchForEvent(eventId, null);
        if (seeded.isEmpty()) {
            throw new IllegalStateException("demo seed created no tickets for the flagship event");
        }
        String code = seeded.get(0).getCode();
        mockMvc.perform(get("/t/" + code + "/ticket.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"));
    }

    // ---------- Round 18: flexible event dates, translation clarity, my-events sort, status sweep ----------

    /** Repo-created event with the shared demo org — the R18 tests all start here. */
    private iq.ievent.domain.Event r18Event(String titlePrefix, String slugPrefix) {
        var org = organizations.findByHandle("zainevents")
                .orElseThrow(() -> new IllegalStateException("demo seed missing"));
        var ev = new iq.ievent.domain.Event();
        ev.setOrganization(org);
        ev.setTitle(titlePrefix + " " + System.currentTimeMillis());
        ev.setSlug(slugPrefix + "-" + System.currentTimeMillis());
        ev.setCategory(iq.ievent.domain.Event.Category.COMMUNITY);
        ev.setCity("Baghdad");
        ev.setStartsAt(java.time.OffsetDateTime.now().plusDays(10));
        ev.setStatus(iq.ievent.domain.Event.Status.LIVE);
        return ev;
    }

    @Test
    void monthOnlyEventCreatedViaWizardAndRendersMonthYear() throws Exception {
        // R18 #1: dateMode=MONTH stores precision MONTH with the 1st of the
        // month as placeholder, and the public page shows just "March 2030"
        // (longDateLine) — never a fabricated exact day.
        String title = "Smoke Month Fest " + System.currentTimeMillis();
        mockMvc.perform(multipart("/host/events/new")
                        .param("title", title)
                        .param("category", "COMMUNITY")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("dateMode", "MONTH")
                        .param("month", "2030-03")
                        .param("action", "draft")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/host/events/*"));
        String slug = title.toLowerCase().replace(' ', '-');
        var ev = events.findBySlug(slug)
                .orElseThrow(() -> new IllegalStateException("month event not created"));
        org.junit.jupiter.api.Assertions.assertEquals("MONTH", ev.getDatePrecision());
        var z = ev.getStartsAt().atZoneSameInstant(iq.ievent.service.Format.BAGHDAD);
        org.junit.jupiter.api.Assertions.assertEquals(java.time.Month.MARCH, z.getMonth());
        org.junit.jupiter.api.Assertions.assertEquals(2030, z.getYear());
        // placeholder sits at the END of the month so every starts_at-vs-now
        // "upcoming" comparison keeps the event visible for its whole month
        org.junit.jupiter.api.Assertions.assertEquals(31, z.getDayOfMonth());
        // Owner render of the draft page: month+year line, no invented day-of-month date.
        mockMvc.perform(get("/en/e/" + slug).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("March 2030")))
                .andExpect(content().string(not(containsString("March 1, 2030"))));
    }

    @Test
    void tbaEventRendersDateTbaBothLanguagesAndBlocksIcs() throws Exception {
        // R18 #1: TBA precision renders "Date to be announced" (AR: الموعد يُعلن
        // لاحقًا), never leaks the 2099 placeholder, and serves no calendar file.
        var ev = r18Event("Smoke Date TBA", "smoke-date-tba");
        ev.setDatePrecision(iq.ievent.domain.Event.PRECISION_TBA);
        ev.setStartsAt(iq.ievent.service.Format.TBA_PLACEHOLDER);
        ev.setHasStartTime(false);
        events.save(ev);
        mockMvc.perform(get("/en/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Date to be announced")))
                .andExpect(content().string(not(containsString("2099-12"))))
                .andExpect(content().string(not(containsString("December 31"))));
        mockMvc.perform(get("/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("الموعد يُعلن لاحقًا")));
        mockMvc.perform(get("/e/" + ev.getSlug() + "/calendar.ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void multiDayRangeRendersBothDates() throws Exception {
        // R18 #1: RANGE precision spells out both days on the event page.
        var ev = r18Event("Smoke Range Expo", "smoke-range-expo");
        ev.setDatePrecision(iq.ievent.domain.Event.PRECISION_RANGE);
        ev.setStartsAt(java.time.LocalDateTime.of(2030, 9, 12, 12, 0)
                .atZone(iq.ievent.service.Format.BAGHDAD).toOffsetDateTime());
        ev.setEndsAt(java.time.LocalDateTime.of(2030, 9, 14, 12, 0)
                .atZone(iq.ievent.service.Format.BAGHDAD).toOffsetDateTime());
        ev.setHasStartTime(false);
        events.save(ev);
        mockMvc.perform(get("/en/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("September 12")))
                .andExpect(content().string(containsString("September 14, 2030")));
    }

    @Test
    void statusSweeperEndsPastLiveEventsButNeverTba() throws Exception {
        // R18 #4: a LIVE event whose day has fully passed flips to ENDED on the
        // next sweep; a TBA event (far-future placeholder) never does.
        var past = r18Event("Smoke Past Gig", "smoke-past-gig");
        past.setStartsAt(java.time.OffsetDateTime.now().minusDays(3));
        Long pastId = events.save(past).getId();
        var tba = r18Event("Smoke Tba Hold", "smoke-tba-hold");
        tba.setDatePrecision(iq.ievent.domain.Event.PRECISION_TBA);
        tba.setStartsAt(iq.ievent.service.Format.TBA_PLACEHOLDER);
        Long tbaId = events.save(tba).getId();

        statusSweeper.sweep();

        org.junit.jupiter.api.Assertions.assertEquals(iq.ievent.domain.Event.Status.ENDED,
                events.findById(pastId).orElseThrow().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(iq.ievent.domain.Event.Status.LIVE,
                events.findById(tbaId).orElseThrow().getStatus());

        // The host's Ended filter now actually contains it.
        mockMvc.perform(get("/en/host/events").param("status", "ended").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(past.getTitle())));
    }

    @Test
    void cancelledFilterShowsCancelledEvents() throws Exception {
        // R18 #4: a CANCELLED event appears under status=cancelled and nowhere
        // near the live filter.
        var ev = r18Event("Smoke Cancelled Show", "smoke-cancelled-show");
        ev.setStatus(iq.ievent.domain.Event.Status.CANCELLED);
        events.save(ev);
        mockMvc.perform(get("/en/host/events").param("status", "cancelled").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ev.getTitle())));
        mockMvc.perform(get("/en/host/events").param("status", "live").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(ev.getTitle()))));
    }

    @Test
    void myEventsShowsCreatedColumnAndSortOptions() throws Exception {
        // R18 #3: the events list shows a Created column and a sort control with
        // event-date / date-created / last-modified options; sort params are 200s.
        mockMvc.perform(get("/en/host/events").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Created")))
                .andExpect(content().string(containsString("Sort by")))
                .andExpect(content().string(containsString("Date created")))
                .andExpect(content().string(containsString("Last modified")));
        mockMvc.perform(get("/en/host/events").param("sort", "created").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/en/host/events").param("sort", "updated").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void translatedNoticeOnlyOnTranslatedView() throws Exception {
        // R18 #2: an English-origin event with a stored auto-translation shows
        // the transparency notice to ARABIC viewers only — the /en (original)
        // view never carries it.
        var ev = r18Event("Smoke Translated Talk", "smoke-translated-talk");
        ev.setLanguage("en");
        ev.setTitleTranslated("محاضرة مترجمة للاختبار");
        events.save(ev);
        mockMvc.perform(get("/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("محاضرة مترجمة للاختبار")))
                .andExpect(content().string(containsString("تُرجم هذا المحتوى آليًا")));
        mockMvc.perform(get("/en/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("تُرجم هذا المحتوى آليًا"))));
    }

    @Test
    void editPageLabelsOriginalAndTranslationLanguages() throws Exception {
        // R18 #2: the edit page marks which language is the detected ORIGINAL and
        // which is the translation. (Chips render only when translation is
        // configured; the date-mode pills below always render.)
        var ev = r18Event("Smoke Edit Langs", "smoke-edit-langs");
        ev.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        Long id = events.save(ev).getId();
        mockMvc.perform(get("/en/host/events/" + id + "/edit").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Exact date")))
                .andExpect(content().string(containsString("Multiple days")))
                .andExpect(content().string(containsString("Month only")));
    }

    // ---------- Round 19: unified gallery — dedupe, cover choice, uploads, no silent wipes ----------

    @Test
    void gallerySaveDedupesAndIsIdempotent() throws Exception {
        // The reported bug: every save duplicated the gallery. Duplicate URLs in
        // one submission collapse to one, and resubmitting the same picks
        // (exactly what the picker does on an untouched save) changes nothing.
        var ev = r18Event("Smoke Gallery Dedupe", "smoke-gallery-dedupe");
        Long id = events.save(ev).getId();
        for (int round = 0; round < 2; round++) {
            mockMvc.perform(multipart("/host/events/" + id + "/edit")
                            .param("title", ev.getTitle())
                            .param("category", "COMMUNITY")
                            .param("city", "Baghdad")
                            .param("locationType", "TBA")
                            .param("date", java.time.LocalDate.now().plusDays(10).toString())
                            .param("galleryManaged", "1")
                            .param("galleryUrl", "https://images.example/a.jpg",
                                    "https://images.example/b.jpg", "https://images.example/a.jpg")
                            .param("galleryCreditName", "", "", "")
                            .param("galleryCreditUrl", "", "", "")
                            .param("galleryFocusY", "40", "60", "40")
                            .with(csrf())
                            .with(user(DEMO_HOST_EMAIL)))
                    .andExpect(status().is3xxRedirection());
            var e = events.findById(id).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("https://images.example/a.jpg", e.getCoverImageUrl());
            org.junit.jupiter.api.Assertions.assertEquals(40, e.getCoverFocusY());
            var rows = eventImages.findByEventIdOrderBySortOrderAsc(id);
            org.junit.jupiter.api.Assertions.assertEquals(1, rows.size(),
                    "duplicates must collapse and saves must be idempotent");
            org.junit.jupiter.api.Assertions.assertEquals("https://images.example/b.jpg", rows.get(0).getUrl());
        }
    }

    @Test
    void galleryFirstPickBecomesTheCover() throws Exception {
        // "choose which one is the cover" — the picker submits its order and
        // position 0 wins; reordering flips the cover accordingly.
        var ev = r18Event("Smoke Gallery Cover", "smoke-gallery-cover");
        Long id = events.save(ev).getId();
        for (String[] order : new String[][] {
                {"https://images.example/x.jpg", "https://images.example/y.jpg"},
                {"https://images.example/y.jpg", "https://images.example/x.jpg"}}) {
            mockMvc.perform(multipart("/host/events/" + id + "/edit")
                            .param("title", ev.getTitle())
                            .param("category", "COMMUNITY")
                            .param("city", "Baghdad")
                            .param("locationType", "TBA")
                            .param("date", java.time.LocalDate.now().plusDays(10).toString())
                            .param("galleryManaged", "1")
                            .param("galleryUrl", order[0], order[1])
                            .param("galleryFocusY", "50", "50")
                            .with(csrf())
                            .with(user(DEMO_HOST_EMAIL)))
                    .andExpect(status().is3xxRedirection());
            var e = events.findById(id).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(order[0], e.getCoverImageUrl());
            var rows = eventImages.findByEventIdOrderBySortOrderAsc(id);
            org.junit.jupiter.api.Assertions.assertEquals(1, rows.size());
            org.junit.jupiter.api.Assertions.assertEquals(order[1], rows.get(0).getUrl());
        }
    }

    @Test
    void galleryUploadPlaceholderStoresServesAndCleansUp() throws Exception {
        // "upload:0" resolves to the stored file's unique URL; that URL serves;
        // removing every image on a managed save orphan-cleans the file (404).
        var ev = r18Event("Smoke Gallery Upload", "smoke-gallery-upload");
        Long id = events.save(ev).getId();
        var file = new org.springframework.mock.web.MockMultipartFile(
                "coverImage", "photo.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        mockMvc.perform(multipart("/host/events/" + id + "/edit")
                        .file(file)
                        .param("title", ev.getTitle())
                        .param("category", "COMMUNITY")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("date", java.time.LocalDate.now().plusDays(10).toString())
                        .param("galleryManaged", "1")
                        .param("galleryUrl", "upload:0")
                        .param("galleryFocusY", "35")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
        var e = events.findById(id).orElseThrow();
        String url = e.getCoverImageUrl();
        org.junit.jupiter.api.Assertions.assertNotNull(url, "uploaded file must become the cover");
        org.junit.jupiter.api.Assertions.assertTrue(
                url.startsWith("/media/event-cover/" + id + "/extra/"), url);
        org.junit.jupiter.api.Assertions.assertEquals(35, e.getCoverFocusY());
        mockMvc.perform(get(url)).andExpect(status().isOk());
        // managed save with no picks at all → gallery emptied, file cleaned up
        mockMvc.perform(multipart("/host/events/" + id + "/edit")
                        .param("title", ev.getTitle())
                        .param("category", "COMMUNITY")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("date", java.time.LocalDate.now().plusDays(10).toString())
                        .param("galleryManaged", "1")
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertNull(events.findById(id).orElseThrow().getCoverImageUrl());
        mockMvc.perform(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void saveWithoutPickerFieldsLeavesGalleryAlone() throws Exception {
        // A POST that carries no picker state (JS failure / old client) must
        // not be read as "delete everything".
        var ev = r18Event("Smoke Gallery Silent", "smoke-gallery-silent");
        ev.setCoverImageUrl("https://images.example/keep.jpg");
        Long id = events.save(ev).getId();
        mockMvc.perform(multipart("/host/events/" + id + "/edit")
                        .param("title", ev.getTitle())
                        .param("category", "COMMUNITY")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("date", java.time.LocalDate.now().plusDays(10).toString())
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertEquals("https://images.example/keep.jpg",
                events.findById(id).orElseThrow().getCoverImageUrl());
    }

    // ---------- Round 20: multi-category, ended listings, counts, suggest, avatar, home hero ----------

    @Test
    void multiCategorySearchMatchesAnyOfThreeAndClampsAtThree() throws Exception {
        // R20 #4: up to 3 categories; browse matches ANY of them; a 4th is dropped.
        var ev = r18Event("Smoke Multicat Expo", "smoke-multicat-expo");
        Long id = events.save(ev).getId();
        mockMvc.perform(multipart("/host/events/" + id + "/edit")
                        .param("title", ev.getTitle())
                        .param("category", "MUSIC", "TECH", "FOOD", "SPORTS")
                        .param("city", "Baghdad")
                        .param("locationType", "TBA")
                        .param("date", java.time.LocalDate.now().plusDays(9).toString())
                        .with(csrf())
                        .with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertEquals(iq.ievent.domain.Event.Category.MUSIC,
                events.findById(id).orElseThrow().getCategory()); // first pick = primary
        // matches on a SECONDARY category…
        mockMvc.perform(get("/en/browse").param("category", "TECH").param("q", "Smoke Multicat"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ev.getTitle())));
        // …and the 4th pick was clamped away
        mockMvc.perform(get("/en/browse").param("category", "SPORTS").param("q", "Smoke Multicat"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(ev.getTitle()))));
        // event page lists all three as #chips
        mockMvc.perform(get("/en/e/" + ev.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("#Tech")))
                .andExpect(content().string(containsString("#Food")));
    }

    @Test
    void endedEventsStayListedButMarked() throws Exception {
        // R20 #12: ENDED events remain in the public listings (record + SEO),
        // rendered with the Ended badge; DRAFTs still never appear.
        var ev = r18Event("Smoke Ended Listing", "smoke-ended-listing");
        ev.setStatus(iq.ievent.domain.Event.Status.ENDED);
        events.save(ev);
        mockMvc.perform(get("/en/browse").param("q", "Smoke Ended Listing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ev.getTitle())))
                .andExpect(content().string(containsString(">Ended<")));
        var draft = r18Event("Smoke Draft Unlisted", "smoke-draft-unlisted");
        draft.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        events.save(draft);
        mockMvc.perform(get("/en/browse").param("q", "Smoke Draft Unlisted"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(draft.getTitle()))));
    }

    @Test
    void organizerEventCountExcludesUnpublished() throws Exception {
        // R20 #3: the public "events hosted" figure counts LIVE + ENDED only.
        // NOTE: the local must not be named "org" — it would shadow the
        // org.* package root and break the org.junit FQNs below (the known
        // offline-javac blind spot; this exact bug shipped once in R20).
        var zain = organizations.findByHandle("zainevents").orElseThrow();
        long before = likeCounts.eventsHostedForOrganization(zain.getId());
        var draft = r18Event("Smoke Count Draft", "smoke-count-draft");
        draft.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        events.save(draft);
        org.junit.jupiter.api.Assertions.assertEquals(before,
                likeCounts.eventsHostedForOrganization(zain.getId()),
                "a draft must not inflate the public event count");
        var live = r18Event("Smoke Count Live", "smoke-count-live");
        events.save(live);
        org.junit.jupiter.api.Assertions.assertEquals(before + 1,
                likeCounts.eventsHostedForOrganization(zain.getId()));
    }

    @Test
    void suggestEndpointReturnsOnlyPublishedMatches() throws Exception {
        // R20 #6: autocomplete returns LIVE events matching the needle; drafts
        // never leak; too-short queries return nothing.
        mockMvc.perform(get("/api/events/suggest").param("q", "Baghdad Nights"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("baghdad-nights-music-festival")));
        var draft = r18Event("Smoke Suggest Draft", "smoke-suggest-draft");
        draft.setStatus(iq.ievent.domain.Event.Status.DRAFT);
        events.save(draft);
        mockMvc.perform(get("/api/events/suggest").param("q", "Smoke Suggest Draft"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("smoke-suggest-draft"))));
        mockMvc.perform(get("/api/events/suggest").param("q", "B"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("baghdad"))));
    }

    @Test
    void avatarUploadServesAndRemoves() throws Exception {
        // R20 #7: a user can upload a profile photo (served from
        // /media/user-avatar/{id}) and remove it again.
        var buyer = users_findByEmail(DEMO_BUYER_EMAIL);
        var photo = new org.springframework.mock.web.MockMultipartFile(
                "avatar", "me.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9, 9});
        mockMvc.perform(multipart("/me/profile")
                        .file(photo)
                        .param("fullName", buyer.getFullName())
                        .with(csrf())
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().is3xxRedirection());
        buyer = users_findByEmail(DEMO_BUYER_EMAIL);
        org.junit.jupiter.api.Assertions.assertNotNull(buyer.getAvatarUrl());
        org.junit.jupiter.api.Assertions.assertTrue(
                buyer.getAvatarUrl().startsWith("/media/user-avatar/" + buyer.getId()), buyer.getAvatarUrl());
        mockMvc.perform(get("/media/user-avatar/" + buyer.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/me/profile")
                        .param("fullName", buyer.getFullName())
                        .param("removeAvatar", "true")
                        .with(csrf())
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().is3xxRedirection());
        org.junit.jupiter.api.Assertions.assertNull(users_findByEmail(DEMO_BUYER_EMAIL).getAvatarUrl());
        mockMvc.perform(get("/media/user-avatar/" + buyer.getId()))
                .andExpect(status().isNotFound());
    }

    private iq.ievent.domain.User users_findByEmail(String email) {
        return usersRepo.findByEmailIgnoreCase(email).orElseThrow();
    }

    @Test
    void homeHeroShowsViewAllAndDataDrivenChips() throws Exception {
        // R20 #10/#11: the month-count pill is gone in favor of a View-all link,
        // and the "Popular" chips come from the live catalog (seeded events
        // guarantee at least one).
        mockMvc.perform(get("/en"))
                .andExpect(status().isOk())
                // R28: the seeded catalog fills the 31-day "Happening this month" rail
                .andExpect(content().string(containsString("Happening this month")))
                .andExpect(content().string(containsString("id=\"monthRail\"")))
                .andExpect(content().string(containsString("View all events")))
                .andExpect(content().string(not(containsString("events across Iraq this month"))))
                .andExpect(content().string(containsString("Popular:")));
    }

    @Test
    void countdownRendersOnlyForRealFutureDates() throws Exception {
        // R20 #2: upcoming DAY-precision events carry the countdown element;
        // TBA events don't (and their badge shows no orphan dash).
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("eventCountdown")));
        var tba = r18Event("Smoke Cd Tba", "smoke-cd-tba");
        tba.setDatePrecision(iq.ievent.domain.Event.PRECISION_TBA);
        tba.setStartsAt(iq.ievent.service.Format.TBA_PLACEHOLDER);
        events.save(tba);
        mockMvc.perform(get("/en/e/" + tba.getSlug()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("eventCountdown"))));
    }

    @Test
    void wizardHasNoDisabledLanguageCheckboxes() throws Exception {
        // R20 #8: the placeholder "Language of event" block is gone.
        mockMvc.perform(get("/en/host/events/new").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("کوردی (KU)"))));
    }

    // ---- R21: public content pages (About, Help center, Pricing) ----

    @Test
    void aboutPageRendersVisionInBothLanguages() throws Exception {
        // English under /en, Arabic on the bare path (Arabic-first routing).
        mockMvc.perform(get("/en/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("before they miss it")))
                .andExpect(content().string(containsString("Your audience finds you")));
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("أن يعرف كل عراقي ما يجري في مدينته")))
                .andExpect(content().string(containsString("جمهورك يلگاك")));
    }

    @Test
    void helpCenterRendersAttendeeAndOrganizerFaqs() throws Exception {
        mockMvc.perform(get("/en/help"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("How do I find events near me?")))
                .andExpect(content().string(containsString("id=\"organizers\"")));
        mockMvc.perform(get("/help"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("شلون ألگي الفعاليات القريبة مني؟")));
    }

    @Test
    void pricingPageRendersBetaAndFeeTiers() throws Exception {
        mockMvc.perform(get("/en/pricing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Free while we build")))
                .andExpect(content().string(containsString("3% + 2,000 IQD, capped at 15,000 IQD")));
        mockMvc.perform(get("/pricing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("مجاني ونحن نبني")));
    }

    @Test
    void publicContentPagesAreLinkedAndIndexed() throws Exception {
        // Footer links to the new pages (links stay un-prefixed; the lang
        // cookie bounce puts English readers on /en/... on the follow-up GET);
        // sitemap and llms.txt list them.
        mockMvc.perform(get("/en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/about\"")))
                .andExpect(content().string(containsString("href=\"/help\"")))
                .andExpect(content().string(containsString("href=\"/pricing\"")));
        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/about")))
                .andExpect(content().string(containsString("/help")))
                .andExpect(content().string(containsString("/pricing")));
        mockMvc.perform(get("/llms.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/about")));
    }

    // ---- R22: learn section (How it works, Features, Solutions, Guides) ----

    @Test
    void howItWorksRendersBothJourneysInBothLanguages() throws Exception {
        mockMvc.perform(get("/en/how-it-works"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Going to an event")))
                .andExpect(content().string(containsString("Track sales and confirm orders")));
        mockMvc.perform(get("/how-it-works"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("رايح لفعالية")));
    }

    @Test
    void featuresAndSolutionsRenderInBothLanguages() throws Exception {
        mockMvc.perform(get("/en/features"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ticketing built for Iraq")))
                .andExpect(content().string(containsString("Arabic first, English always")));
        mockMvc.perform(get("/features"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("تذاكر مبنية للعراق")));
        mockMvc.perform(get("/en/solutions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Registration is where conferences get messy")))
                .andExpect(content().string(containsString("id=\"bazaars\"")));
        mockMvc.perform(get("/solutions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("المؤتمرات والقمم")));
    }

    @Test
    void guidesRenderWithChaptersAndScreenshotSlots() throws Exception {
        mockMvc.perform(get("/en/guides"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The attendee guide")))
                .andExpect(content().string(containsString("The organizer guide")));
        mockMvc.perform(get("/en/guides/attendees"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("In this guide")))
                .andExpect(content().string(containsString("one QR code per person")))
                // R28: screenshot slots are hidden until CONTENT_SCREENSHOTS=true
                .andExpect(content().string(not(containsString("ga-tickets.png"))))
                .andExpect(content().string(not(containsString("data-shot="))));
        mockMvc.perform(get("/en/guides/organizers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Set up your organizer profile")))
                .andExpect(content().string(not(containsString("go-checkin.png"))));
        mockMvc.perform(get("/guides/organizers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("دليل المنظمين")));
    }

    @Test
    void learnSectionIsWiredIntoNavSitemapAndLlms() throws Exception {
        // learn sub-nav shows on pricing; footer Learn column links the guides
        mockMvc.perform(get("/en/pricing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/how-it-works\"")));
        mockMvc.perform(get("/en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/guides/organizers\"")));
        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/how-it-works")))
                .andExpect(content().string(containsString("/guides/organizers")));
        mockMvc.perform(get("/llms.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/solutions")));
    }

    // ---- R23: focus copy, city-led footer, lively content pages, onboarding visual ----

    @Test
    void homeAndFooterLeadWithTheVisionNotMusic() throws Exception {
        mockMvc.perform(get("/en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conferences, workshops, exhibitions, festivals and community gatherings")))
                // footer Discover column is city-led now
                .andExpect(content().string(containsString("href=\"/browse?city=Baghdad\"")))
                .andExpect(content().string(containsString("href=\"/browse?city=Erbil\"")))
                // the old footer "Tech & Business" shortcut is gone (the category
                // strip on the home page still lists every category, music last)
                .andExpect(content().string(not(containsString("Tech &amp; Business"))));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("اعرف ما يجري في مدينتك قبل أن يفوتك")));
    }

    @Test
    void contentPagesCarryStatsWhyAndCityTiles() throws Exception {
        mockMvc.perform(get("/en/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Why people choose iEvent")))
                // R25: public type scale is scoped to content pages
                .andExpect(content().string(containsString("class=\"type-public flex-1\"")))
                .andExpect(content().string(containsString("governorates, all of Iraq")))
                .andExpect(content().string(containsString("href=\"/browse?city=Najaf\"")));
        mockMvc.perform(get("/en/features"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event categories, from summits to sports")));
    }

    @Test
    void onboardingVisualShowsBreadthNotJustMusic() throws Exception {
        // the demo buyer has no organization, so /host/start renders the funnel
        mockMvc.perform(get("/en/host/start").with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Business Summit 2026")))
                .andExpect(content().string(containsString("Founders Workshop")))
                .andExpect(content().string(containsString("checked in at the door")))
                .andExpect(content().string(not(containsString("Baghdad Nights Music Festival"))));
    }

    // ---- R24: listing layout (2-up mobile grid, borderless cards, deduped browse filters) ----

    @Test
    void browseGridIsTwoUpOnMobileWithBorderlessCardsAndSingleDateControl() throws Exception {
        mockMvc.perform(get("/en/browse?when=month&price=free"))
                .andExpect(status().isOk())
                // two cards per row on phones
                .andExpect(content().string(containsString("grid grid-cols-2 gap-3 sm:gap-6")))
                // cards no longer carry the boxed look
                .andExpect(content().string(containsString("data-event-card=")))
                .andExpect(content().string(not(containsString("bg-white shadow-card transition hover:-translate-y-0.5 hover:shadow-cardHover"))))
                // the date filter lives only in the chip row; the side forms carry it hidden
                .andExpect(content().string(not(containsString("type=\"radio\" name=\"when\""))))
                .andExpect(content().string(containsString("type=\"hidden\" name=\"when\" value=\"month\"")))
                // the price radios remain in the side forms
                .andExpect(content().string(containsString("type=\"radio\" name=\"price\" value=\"free\"")));
    }

    // ---- R27: wizard rows, refund policy in settings, auto-confirm, auth tabs ----

    @Test
    void wizardHasAddRowAutoConfirmAndNoRefundField() throws Exception {
        mockMvc.perform(get("/en/host/events/new").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"addTicketRowBtn\"")))
                .andExpect(content().string(containsString("name=\"autoConfirmOrders\"")))
                .andExpect(content().string(containsString("Directory listing")))
                .andExpect(content().string(not(containsString("id=\"ev-refund\""))));
    }

    @Test
    void refundPolicyLivesInPaymentSettingsAndHidesFromEventPage() throws Exception {
        // the settings card renders with the organizer's policy select
        mockMvc.perform(get("/en/host/settings/payments").with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"refund\"")))
                .andExpect(content().string(containsString("id=\"org-refund\"")));
        // hiding the policy removes the refund section from the public event page
        mockMvc.perform(post("/host/settings/payments/refund")
                        .param("refundPolicy", "UP_TO_48H")
                        .with(csrf()).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/host/settings/payments#refund"));
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"refund-policy\""))));
        // showing it again renders the organizer-level text (48 hours)
        mockMvc.perform(post("/host/settings/payments/refund")
                        .param("refundPolicy", "UP_TO_48H")
                        .param("refundPolicyVisible", "true")
                        .with(csrf()).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/en/e/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"refund-policy\"")))
                .andExpect(content().string(containsString("Full refund up to 48 hours before doors open")));
        // restore the default so other tests see the seeded state
        mockMvc.perform(post("/host/settings/payments/refund")
                        .param("refundPolicy", "NO_REFUNDS")
                        .param("refundPolicyVisible", "true")
                        .with(csrf()).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authPagesCarrySignInSignUpTabs() throws Exception {
        mockMvc.perform(get("/en/auth/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Sign in or create an account\"")))
                .andExpect(content().string(containsString("href=\"/auth/register\"")));
        mockMvc.perform(get("/en/auth/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Sign in or create an account\"")))
                .andExpect(content().string(containsString("href=\"/auth/login\"")));
    }

    // ---- R29: mobile header (bell in the top bar, no duplicate CTA in the menu) ----

    @Test
    void headerShowsBellInTopBarAndMenuHasNoDuplicateCta() throws Exception {
        String html = mockMvc.perform(get("/en").with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"nbBtn\""), "bell button present");
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("id=\"nbBadgeMobile\""), "menu notifications row gone");
        String menu = html.substring(html.indexOf("id=\"mobileMenu\""));
        menu = menu.substring(0, menu.indexOf("</div>\n  </div>") > 0 ? menu.indexOf("</div>\n  </div>") : menu.length());
        org.junit.jupiter.api.Assertions.assertFalse(menu.contains("rounded-xl bg-brand-500 px-4 py-3.5"), "no duplicate Host CTA inside the menu");
    }
}
