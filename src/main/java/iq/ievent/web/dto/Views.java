package iq.ievent.web.dto;

import java.util.List;

/** Read-model DTOs for the public site. All display strings are precomputed server-side. */
public final class Views {

    private Views() {}

    /** Card shown in home/browse/related grids. */
    public record EventCard(
            String slug,
            String title,
            String categoryLabel,   // "Music"
            String coverTheme,      // music|tech|business|arts|food|sports|community|education|film|family
            String city,
            String venueName,       // may be null
            String dateLine,        // "SAT, SEP 12 · 7:00 PM"
            String priceLine,       // "From 25,000 IQD" or "Free"
            long likes) {}

    public record TicketTypeView(
            long id,
            String name,
            long priceIqd,
            String priceLabel,      // "35,000 IQD" or "Free"
            String status,          // ON_SALE | SOLD_OUT | HIDDEN | ENDED
            int remaining) {}

    public record OrganizerView(
            String name,
            String handle,
            String bio,
            boolean verified,
            String followersDisplay, // "12.4K"
            long eventsHosted,
            String initials) {}

    public record EventDetail(
            String slug,
            String title,
            String categoryLabel,
            String coverTheme,
            String city,
            String venueName,
            String venueAddress,
            String dateLine,        // "Saturday, September 12 · 7:00 PM – 12:00 AM"
            String monthShort,      // "SEP"
            String dayOfMonth,      // "12"
            List<String> paragraphs,
            String priceFromLabel,  // "25,000" (IQD suffix rendered by template) or "Free"
            long likes,
            OrganizerView organizer,
            List<TicketTypeView> ticketTypes) {}

    public record CityCount(String city, long count) {}
}
