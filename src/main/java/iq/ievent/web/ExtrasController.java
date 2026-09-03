package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.service.MailService;
import iq.ievent.service.QrService;
import iq.ievent.service.TicketPdfService;
import iq.ievent.service.UserService;
import iq.ievent.service.Format;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Calendar (.ics) downloads, short event links for the embed widget, newsletter signup. */
@Controller
public class ExtrasController {

    private static final DateTimeFormatter ICS_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final JdbcTemplate jdbc;
    private final QrService qr;
    private final TicketPdfService pdf;
    private final MailService mail;
    private final UserService userService;
    private final String supportEmail;
    private final String siteBaseUrl;

    public ExtrasController(EventRepository events, OrderRepository orders, TicketRepository tickets,
                            JdbcTemplate jdbc, QrService qr, TicketPdfService pdf,
                            MailService mail, UserService userService,
                            @org.springframework.beans.factory.annotation.Value("${app.mail.support}") String supportEmail,
                            @org.springframework.beans.factory.annotation.Value("${app.base-url}") String siteBaseUrl) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.jdbc = jdbc;
        this.qr = qr;
        this.pdf = pdf;
        this.siteBaseUrl = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
        this.mail = mail;
        this.userService = userService;
        this.supportEmail = supportEmail;
    }

    /** QR image download for a confirmed ticket (access = knowing the unguessable code). */
    @GetMapping("/t/{code}/qr.png")
    public ResponseEntity<byte[]> ticketQrPng(@PathVariable String code) {
        Ticket t = tickets.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ievent-ticket-" + t.getCode() + ".png\"")
                .body(qr.ticketQrPng(t.getCode()));
    }

    /** Printable single-ticket PDF. Transactional: the PDF renderer walks the
     *  ticket's LAZY event/ticketType associations and OSIV is off — without a
     *  transaction this 500'd with LazyInitializationException. */
    @GetMapping("/t/{code}/ticket.pdf")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<byte[]> ticketPdf(@PathVariable String code) {
        Ticket t = tickets.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ievent-ticket-" + t.getCode() + ".pdf\"")
                .body(pdf.ticketsPdf(java.util.List.of(t)));
    }

    /** Legacy path kept working for any already-shared /events/{slug} links.
     *  Slugs are Arabic by default — must be path-segment-encoded, or
     *  sendRedirect throws on the non-Latin1 characters in the raw string. */
    @GetMapping("/events/{slug}")
    public String legacyEventLink(@PathVariable String slug) {
        return "redirect:/e/" + org.springframework.web.util.UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
    }

    /** Search-box autocomplete (R20 #6): top matching PUBLISHED events for the
     *  typed needle, localized to the viewer's language. Public + read-only. */
    @GetMapping("/api/events/suggest")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.List<java.util.Map<String, String>> suggestEvents(
            @org.springframework.web.bind.annotation.RequestParam(name = "q", required = false) String q) {
        String needle = q == null ? "" : q.trim();
        if (needle.length() < 2 || needle.length() > 80) return java.util.List.of();
        return events.suggestByTitle(needle, org.springframework.data.domain.PageRequest.of(0, 8)).stream()
                .map(e -> java.util.Map.of(
                        "title", iq.ievent.service.Format.localized(
                                e.getTitle(), e.getTitleTranslated(), e.getLanguage()),
                        "slug", e.getSlug(),
                        "dateLine", iq.ievent.service.Format.cardDateLine(
                                e.getStartsAt(), e.getEndsAt(), e.isHasStartTime(), e.getDatePrecision()),
                        "city", iq.ievent.service.Cities.label(e.getCity(),
                                org.springframework.context.i18n.LocaleContextHolder.getLocale())))
                .toList();
    }

    @GetMapping("/e/{slug}/calendar.ics")
    public ResponseEntity<byte[]> eventIcs(@PathVariable String slug) {
        Event e = events.findBySlug(slug)
                .filter(ev -> ev.getStatus() == Event.Status.LIVE || ev.getStatus() == Event.Status.ENDED)
                // Month-only and date-TBA schedules store a placeholder
                // timestamp (see Event.datePrecision) — a calendar entry made
                // from that would be misinformation, so no .ics for them (the
                // templates hide the link too; this covers a typed URL).
                .filter(ev -> !"TBA".equals(ev.getDatePrecision()) && !"MONTH".equals(ev.getDatePrecision()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String ics = buildIcs(e);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + e.getSlug() + ".ics\"")
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }

    /** Dynamic so the Sitemap: line always points at the right host — a static
     *  file couldn't tell localhost apart from production. */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robotsTxt() {
        String body = "User-agent: *\nDisallow: /admin\n\nSitemap: " + siteBaseUrl + "/sitemap.xml\n";
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    /** llms.txt (see llmstxt.org): a short Markdown orientation page for AI
     *  agents/crawlers — what the site is, and the handful of pages worth
     *  starting from. Dynamic for the same reason robots.txt is. */
    @GetMapping(value = "/llms.txt", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> llmsTxt() {
        String body = """
                # iEvent

                > iEvent is an events and ticketing platform for Iraq: discover concerts, \
                conferences, courses and community events, and buy tickets directly on the site.

                Listings span every major Iraqi city and cover categories from education and \
                business to music, sports, arts and food. Organizers can also publish free \
                informational announcements with no ticket sale attached.

                ## Key pages

                - [Browse events](%1$s/browse): searchable, filterable directory of every live event
                - [Host an event](%1$s/host): create and publish an event, sell tickets or post an announcement
                - [About iEvent](%1$s/about): vision, mission and what the platform is building for Iraq
                - [How it works](%1$s/how-it-works): the attendee and organizer journeys, step by step
                - [Features](%1$s/features): full tour of event pages, ticketing, check-in, promotion and team tools
                - [Solutions](%1$s/solutions): how iEvent fits conferences, concerts, workshops, community events, bazaars and sports
                - [Guides](%1$s/guides): detailed attendee and organizer manuals
                - [Help center](%1$s/help): how tickets, payments, refunds and hosting work
                - [Pricing](%1$s/pricing): platform fees (currently waived during the beta)
                - [Privacy policy](%1$s/privacy)
                - [Terms of use](%1$s/terms)

                ## Notes

                - Content is bilingual (Arabic default, English at %1$s/en/**); each event page \
                carries hreflang alternates for both.
                - [Sitemap](%1$s/sitemap.xml) lists every live event and organizer page.
                """.formatted(siteBaseUrl);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(body);
    }

    /** One canonical (Arabic-default) URL per public page — event/organizer
     *  pages already carry hreflang alternates for English via layout.html's
     *  head fragment, so the sitemap itself doesn't need to list both. Excludes
     *  admin-hidden events and disabled orgs, same boundary the public site
     *  itself enforces (see CatalogService/EventRepository). */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        appendUrl(xml, siteBaseUrl + "/", null, "daily");
        appendUrl(xml, siteBaseUrl + "/browse", null, "hourly");
        appendUrl(xml, siteBaseUrl + "/about", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/how-it-works", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/features", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/solutions", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/guides", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/guides/attendees", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/guides/organizers", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/help", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/pricing", null, "monthly");
        appendUrl(xml, siteBaseUrl + "/privacy", null, "yearly");
        appendUrl(xml, siteBaseUrl + "/terms", null, "yearly");

        jdbc.query("""
                SELECT e.slug, e.updated_at FROM events e
                JOIN organizations o ON o.id = e.organization_id
                WHERE e.status = 'LIVE' AND e.visibility = 'PUBLIC'
                  AND e.admin_hidden = false AND o.disabled = false
                ORDER BY e.updated_at DESC
                """,
                (rs, i) -> {
                    String slug = rs.getString("slug");
                    OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
                    appendUrl(xml, siteBaseUrl + "/e/" + org.springframework.web.util.UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8),
                            updated, "weekly");
                    return null;
                });

        jdbc.query("SELECT handle FROM organizations WHERE disabled = false ORDER BY id",
                (rs, i) -> {
                    String handle = rs.getString("handle");
                    appendUrl(xml, siteBaseUrl + "/organizers/" + org.springframework.web.util.UriUtils.encodePathSegment(handle, StandardCharsets.UTF_8),
                            null, "weekly");
                    return null;
                });

        xml.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    private static void appendUrl(StringBuilder xml, String loc, OffsetDateTime lastmod, String changefreq) {
        xml.append("  <url>\n    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod.atZoneSameInstant(ZoneOffset.UTC).toLocalDate()).append("</lastmod>\n");
        }
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n  </url>\n");
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final java.util.Set<String> VALID_CATEGORIES = PageController.CATEGORIES.stream()
            .map(PageController.CategoryOption::value)
            .collect(java.util.stream.Collectors.toSet());

    /** Comma-separated category codes from the multi-step signup widget, filtered
     *  to known codes and capped at 3 — never trust client-supplied CSV as-is. */
    private static String cleanCategories(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String joined = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .filter(VALID_CATEGORIES::contains)
                .distinct()
                .limit(3)
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isBlank() ? null : joined;
    }

    @PostMapping("/newsletter")
    public ResponseEntity<?> newsletter(@RequestParam String email,
                             @RequestParam(required = false) String city,
                             @RequestParam(required = false) String categories,
                             @RequestHeader(value = "Referer", required = false) String referer,
                             @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        String clean = email == null ? "" : email.trim().toLowerCase();
        boolean valid = !clean.isBlank() && clean.contains("@") && clean.length() <= 255;
        if (valid) {
            String cleanCity = city == null || city.isBlank() ? null : city.trim();
            String cleanCats = cleanCategories(categories);
            // Re-submitting (e.g. finishing the category/city steps for an email
            // already on the list from a bare subscribe elsewhere) updates the
            // profile instead of no-op'ing — the marketing list wants the latest
            // signal, not just the first one.
            jdbc.update("""
                    INSERT INTO newsletter_subscribers (email, city, categories) VALUES (?, ?, ?)
                    ON CONFLICT (lower(email)) DO UPDATE SET
                        city = COALESCE(EXCLUDED.city, newsletter_subscribers.city),
                        categories = COALESCE(EXCLUDED.categories, newsletter_subscribers.categories)
                    """, clean, cleanCity, cleanCats);
        }
        if ("XMLHttpRequest".equals(requestedWith)) {
            return ResponseEntity.ok(java.util.Map.of("ok", valid));
        }
        String back = referer != null && referer.contains("/browse") ? "/browse" : "/";
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, back + "?subscribed").build();
    }

    private static final java.util.Set<String> CONTACT_TOPICS =
            java.util.Set.of("general", "attendee", "organizer", "billing");

    @GetMapping("/contact")
    public String contactForm(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = principal == null ? null : userService.byEmail(principal.getUsername());
        model.addAttribute("loggedIn", u != null);
        model.addAttribute("prefillName", u == null ? "" : u.getFullName());
        model.addAttribute("prefillEmail", u == null ? "" : u.getEmail());
        model.addAttribute("prefillTopic", "general");
        model.addAttribute("prefillMessage", "");
        model.addAttribute("supportEmail", supportEmail);
        return "contact";
    }

    @PostMapping("/contact")
    public String contactSubmit(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam String name,
                                @RequestParam String email,
                                @RequestParam(required = false) String topic,
                                @RequestParam String message,
                                @RequestParam(required = false) String website, // honeypot
                                Model model) {
        // Signed-in visitors get their name/email fixed to the account on record —
        // the fields render disabled client-side, but the server must not trust a
        // POST body over that; it just re-derives from the session either way.
        User u = principal == null ? null : userService.byEmail(principal.getUsername());
        String cleanName = u != null ? u.getFullName() : (name == null ? "" : name.trim());
        String cleanEmail = u != null ? u.getEmail() : (email == null ? "" : email.trim());
        String cleanMessage = message == null ? "" : message.trim();
        String cleanTopic = CONTACT_TOPICS.contains(topic) ? topic : "general";

        model.addAttribute("loggedIn", u != null);
        model.addAttribute("prefillName", cleanName);
        model.addAttribute("prefillEmail", cleanEmail);
        model.addAttribute("prefillTopic", cleanTopic);
        model.addAttribute("prefillMessage", cleanMessage);
        model.addAttribute("supportEmail", supportEmail);

        boolean spam = website != null && !website.isBlank();
        if (spam) {
            model.addAttribute("sent", true); // silently drop, no tell for bots
            return "contact";
        }

        boolean valid = !cleanName.isEmpty() && cleanName.length() <= 120
                && cleanEmail.contains("@") && cleanEmail.length() <= 255
                && !cleanMessage.isEmpty() && cleanMessage.length() <= 5000;
        if (!valid) {
            model.addAttribute("formError", true);
            return "contact";
        }

        boolean sent = mail.sendSupportContact(cleanName, cleanEmail, cleanTopic, cleanMessage,
                LocaleContextHolder.getLocale());
        model.addAttribute(sent ? "sent" : "sendFailed", true);
        return "contact";
    }

    private String buildIcs(Event e) {
        OffsetDateTime start = e.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = (e.getEndsAt() == null ? e.getStartsAt().plusHours(3) : e.getEndsAt())
                .withOffsetSameInstant(ZoneOffset.UTC);
        String location = (e.getVenueName() == null ? "" : e.getVenueName() + ", ") + e.getCity();
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n")
          .append("VERSION:2.0\r\n")
          .append("PRODID:-//iEvent//ievent.iq//EN\r\n")
          .append("BEGIN:VEVENT\r\n")
          .append("UID:").append(e.getSlug()).append("@ievent.iq\r\n")
          .append("DTSTAMP:").append(ICS_STAMP.format(OffsetDateTime.now(ZoneOffset.UTC))).append("\r\n")
          .append("DTSTART:").append(ICS_STAMP.format(start)).append("\r\n")
          .append("DTEND:").append(ICS_STAMP.format(end)).append("\r\n")
          .append("SUMMARY:").append(icsEscape(e.getTitle())).append("\r\n")
          .append("LOCATION:").append(icsEscape(location)).append("\r\n")
          .append("DESCRIPTION:").append(icsEscape("Tickets & details: " + siteBaseUrl + "/e/" + e.getSlug()))
          .append("\r\n")
          .append("END:VEVENT\r\n")
          .append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String icsEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }
}
