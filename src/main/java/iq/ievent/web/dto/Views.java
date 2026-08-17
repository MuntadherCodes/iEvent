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
            String coverImageUrl,   // "/media/event-cover/{id}" or null → gradient fallback
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
            String coverImageUrl,   // null → gradient fallback
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
            List<TicketTypeView> ticketTypes,
            String locationType,     // VENUE | ONLINE | TBA
            String mapsUrl) {}       // organizer-provided exact-pin link, or null

    public record CityCount(String city, long count) {}

    /** One act/row of the event lineup ("Name — 10:00 PM" parsed server-side). */
    public record LineupItem(String name, String note) {}

    /** Past event card on the organizer profile (Past tab). */
    public record PastEventCard(
            String slug,
            String title,
            String coverTheme,
            String coverImageUrl,
            String city,
            String venueName,
            String dateLine,
            String attendedLine) {}   // "2,140 attended" or null

    /** Extra organizer-profile data (contact block, logo, past events). */
    public record OrganizerExtras(
            long orgId,
            String logoUrl,          // "/media/org-logo/{id}" or null → initials avatar
            String coverUrl,         // "/media/org-cover/{id}" or null → gradient banner
            String contactEmail,
            String contactPhone,
            String website,          // display text, e.g. "zainevents.iq"
            String websiteHref,      // absolute URL
            String instagram,        // display text, e.g. "instagram.com/zainevents"
            String instagramHref,    // absolute URL
            java.util.List<PastEventCard> pastEvents) {}

    /** Organizer direct-transfer payment details shown at checkout (null when not enabled). */
    public record DirectPayInfo(String cardNumber, String accountName, String walletBank, String instructions) {}
}
