package iq.ievent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.service.Cities;
import iq.ievent.service.Format;
import iq.ievent.web.dto.Views.EventDetail;
import iq.ievent.web.dto.Views.TicketTypeView;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * schema.org JSON-LD for the public pages (R34). Google's event rich results (the
 * "events" carousel that owns "events this weekend" style queries) need an Event with a
 * name, a startDate and a location; the rest is recommended. Serialized here, in Java,
 * and dropped into the page with th:utext, so the shape is fixed regardless of the
 * template, and "</" is escaped so no title can close the script tag early.
 */
final class SeoJsonLd {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeoJsonLd() {}

    /** WebSite + Organization for the home page: the Arabic wordmark as alternateName is
     *  what lets Google tie searches for ايفنت (and its many spellings) to the brand. */
    static String site(String siteBaseUrl) {
        Map<String, Object> website = new LinkedHashMap<>();
        website.put("@type", "WebSite");
        website.put("@id", siteBaseUrl + "/#website");
        website.put("url", siteBaseUrl + "/");
        website.put("name", "iEvent");
        website.put("alternateName", List.of("ايفنت", "i event"));
        website.put("inLanguage", List.of("ar", "en"));
        website.put("publisher", Map.of("@id", siteBaseUrl + "/#organization"));

        Map<String, Object> organization = new LinkedHashMap<>();
        organization.put("@type", "Organization");
        organization.put("@id", siteBaseUrl + "/#organization");
        organization.put("name", "iEvent");
        organization.put("alternateName", List.of("ايفنت"));
        organization.put("url", siteBaseUrl + "/");
        organization.put("logo", Map.of("@type", "ImageObject",
                "url", siteBaseUrl + "/img/favicon/web-app-manifest-512x512.png"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@context", "https://schema.org");
        root.put("@graph", List.of(website, organization));
        return write(root);
    }

    /**
     * Event markup for /e/{slug}, or null when the schedule is not concrete enough
     * (month-only and date-TBA events have no real startDate to publish).
     *
     * @param pageUrl  absolute URL of the page being rendered (language-specific)
     * @param lang     "ar" or "en": the language the page is rendered in
     * @param imageUrl absolute cover URL or null
     */
    static String event(Event e, EventDetail detail, Organization owner, String pageUrl,
                        String lang, String imageUrl, String summary, String siteBaseUrl) {
        String precision = e.getDatePrecision() == null ? Event.PRECISION_DAY : e.getDatePrecision();
        if (!Event.PRECISION_DAY.equals(precision) && !Event.PRECISION_RANGE.equals(precision)) return null;
        if (e.getStartsAt() == null) return null;

        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Event");
        ld.put("name", detail.title());
        String description = plainDescription(summary, detail);
        if (description != null) ld.put("description", description);
        if (imageUrl != null && !imageUrl.isBlank()) ld.put("image", List.of(imageUrl));
        ld.put("startDate", isoDate(e.getStartsAt(), e.isHasStartTime()));
        if (e.getEndsAt() != null) ld.put("endDate", isoDate(e.getEndsAt(), e.isHasStartTime()));
        ld.put("eventStatus", e.getStatus() == Event.Status.CANCELLED
                ? "https://schema.org/EventCancelled" : "https://schema.org/EventScheduled");

        Locale locale = "en".equals(lang) ? Locale.ENGLISH : new Locale("ar");
        boolean online = "ONLINE".equals(e.getLocationType());
        ld.put("eventAttendanceMode", online
                ? "https://schema.org/OnlineEventAttendanceMode"
                : "https://schema.org/OfflineEventAttendanceMode");
        if (online) {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("@type", "VirtualLocation");
            String onlineUrl = e.getOnlineUrl();
            loc.put("url", onlineUrl == null || onlineUrl.isBlank() ? pageUrl : onlineUrl);
            ld.put("location", loc);
        } else {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("@type", "PostalAddress");
            if (e.getVenueAddress() != null && !e.getVenueAddress().isBlank()) {
                address.put("streetAddress", e.getVenueAddress());
            }
            if (e.getCity() != null && !e.getCity().isBlank()) {
                address.put("addressLocality", Cities.label(e.getCity(), locale));
            }
            address.put("addressCountry", "IQ");
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("@type", "Place");
            String venue = e.getVenueName();
            place.put("name", venue == null || venue.isBlank()
                    ? Cities.label(e.getCity(), locale) : venue);
            place.put("address", address);
            ld.put("location", place);
        }

        if (owner != null && owner.getName() != null) {
            Map<String, Object> organizer = new LinkedHashMap<>();
            organizer.put("@type", "Organization");
            organizer.put("name", owner.getName());
            if (owner.getHandle() != null) {
                organizer.put("url", siteBaseUrl + "/organizers/"
                        + UriUtils.encodePathSegment(owner.getHandle(), StandardCharsets.UTF_8));
            }
            ld.put("organizer", organizer);
        }

        if (!e.isAnnounceOnly() && detail.ticketTypes() != null && !detail.ticketTypes().isEmpty()) {
            List<Map<String, Object>> offers = new ArrayList<>();
            for (TicketTypeView tt : detail.ticketTypes()) {
                Map<String, Object> offer = new LinkedHashMap<>();
                offer.put("@type", "Offer");
                offer.put("name", tt.name());
                offer.put("price", String.valueOf(Math.max(0, tt.priceIqd())));
                offer.put("priceCurrency", "IQD");
                boolean available = "ON_SALE".equals(tt.status()) && tt.remaining() > 0
                        && e.getStatus() == Event.Status.LIVE;
                offer.put("availability", available
                        ? "https://schema.org/InStock" : "https://schema.org/SoldOut");
                offer.put("url", pageUrl + "/checkout");
                offers.add(offer);
            }
            ld.put("offers", offers);
        }

        ld.put("inLanguage", lang);
        ld.put("url", pageUrl);
        return write(ld);
    }

    /** "2026-09-30T10:00+03:00" when the host set a time, else the bare "2026-09-30". */
    private static String isoDate(OffsetDateTime ts, boolean hasTime) {
        OffsetDateTime local = ts.atZoneSameInstant(Format.BAGHDAD).toOffsetDateTime();
        return hasTime ? local.toString() : local.toLocalDate().toString();
    }

    /** Summary when the host wrote one, else the first paragraph of the description,
     *  as plain text, capped at 300 characters. */
    private static String plainDescription(String summary, EventDetail detail) {
        String text = null;
        if (summary != null && !summary.isBlank()) {
            String plain = org.jsoup.Jsoup.parse(summary).text().trim();
            if (!plain.isEmpty()) text = plain;
        }
        if (text == null && detail.paragraphs() != null) {
            for (String p : detail.paragraphs()) {
                String plain = org.jsoup.Jsoup.parse(p).text().trim();
                if (!plain.isEmpty()) { text = plain; break; }
            }
        }
        if (text == null || text.isEmpty()) return null;
        return text.length() > 300 ? text.substring(0, 297).trim() + "..." : text;
    }

    private static String write(Object value) {
        try {
            // "</" must never appear verbatim inside a <script> body; "<\/" is the same JSON.
            return MAPPER.writeValueAsString(value).replace("</", "<\\/");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}
