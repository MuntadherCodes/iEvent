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
@TestPropertySource(properties = {"app.seed.demo=true"})
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
        mockMvc.perform(get("/en/events/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("General Admission")));
    }

    @Test
    void unknownEventSlugIs404() throws Exception {
        // Status-only — stays bare and exercises the Arabic default path.
        mockMvc.perform(get("/events/nope"))
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
        // Round 10 (#11): anonymous checkout renders fully — the submit button is
        // replaced by the "Sign in to complete your order" continuation, and the
        // promo card (#promoSection) renders for anonymous buyers too.
        mockMvc.perform(get("/en/events/baghdad-nights-music-festival/checkout"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in to complete your order")))
                .andExpect(content().string(containsString("Promo code")));
    }

    @Test
    void calendarIcsDownloads() throws Exception {
        mockMvc.perform(get("/events/baghdad-nights-music-festival/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andExpect(content().string(containsString("BEGIN:VEVENT")));
    }

    @Test
    void shortLinkRedirectsToEventPage() throws Exception {
        mockMvc.perform(get("/e/baghdad-nights-music-festival"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events/baghdad-nights-music-festival"));
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
        mockMvc.perform(get("/en/events/baghdad-nights-music-festival/checkout")
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
        mockMvc.perform(get("/en/events/baghdad-nights-music-festival/checkout")
                        .with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ticket 1 · General Admission")));
    }

    @Test
    void checkoutRespectsExplicitZeroQuantities() throws Exception {
        // Any explicit qty-* param disables the first-visit default entirely —
        // an all-zero deep link renders no holder rows at all.
        mockMvc.perform(get("/en/events/baghdad-nights-music-festival/checkout")
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
                        .param("next", "/events/baghdad-nights-music-festival/checkout?qty-1=1"))
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
        mockMvc.perform(get("/events/" + slug))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/events/" + slug).with(user(DEMO_BUYER_EMAIL)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/en/events/" + slug).with(user(DEMO_HOST_EMAIL)))
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
        mockMvc.perform(get("/en/events/" + slug).with(user(DEMO_HOST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<p>ok-rich</p>")))
                .andExpect(content().string(not(containsString("alert(1)"))))
                .andExpect(content().string(not(containsString("javascript:"))));
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
}
