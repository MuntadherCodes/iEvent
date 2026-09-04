package iq.ievent.service;

import iq.ievent.domain.Event;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * "Open in Google Maps" / "Open in Waze" links for a physical venue (R32/R33).
 *
 * Online and date/venue-to-be-announced events have nothing to navigate to, so
 * {@link #available(Event)} gates every caller. Waze cannot consume a Google
 * Maps URL, so it always navigates by the venue text, while the Google link
 * prefers the organizer's exact pin when they pasted one.
 */
public final class MapLinks {

    private MapLinks() {}

    public static boolean available(Event e) {
        if (e == null) return false;
        String type = e.getLocationType();
        if ("ONLINE".equals(type) || "TBA".equals(type)) return false;
        return !venueQuery(e).isBlank();
    }

    /** Google link for the event: the organizer's exact pin when set, else a venue search. */
    public static String directions(Event e) {
        String pin = e.getMapsUrl();
        return pin != null && !pin.isBlank() ? pin.trim() : googleSearch(e);
    }

    public static String googleSearch(Event e) {
        return "https://maps.google.com/?q=" + encodedVenue(e);
    }

    public static String waze(Event e) {
        return "https://www.waze.com/ul?navigate=yes&q=" + encodedVenue(e);
    }

    /** "Venue name, address or city": the text both map apps search for. */
    static String venueQuery(Event e) {
        StringBuilder sb = new StringBuilder();
        if (e.getVenueName() != null && !e.getVenueName().isBlank()) sb.append(e.getVenueName().trim());
        String addr = e.getVenueAddress() != null && !e.getVenueAddress().isBlank()
                ? e.getVenueAddress().trim() : e.getCity();
        if (addr != null && !addr.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(addr.trim());
        }
        return sb.toString();
    }

    /** Waze decodes with decodeURIComponent, which leaves a form-encoded "+" literal. */
    private static String encodedVenue(Event e) {
        return URLEncoder.encode(venueQuery(e), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
