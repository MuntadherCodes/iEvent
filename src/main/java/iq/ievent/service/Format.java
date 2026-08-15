package iq.ievent.service;

import iq.ievent.domain.Event;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Display formatting helpers. All event times are rendered in Asia/Baghdad. */
public final class Format {

    public static final ZoneId BAGHDAD = ZoneId.of("Asia/Baghdad");

    private static final DateTimeFormatter CARD_DATE =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_SHORT =
            DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    private Format() {}

    public static String cardDateLine(OffsetDateTime startsAt) {
        ZonedDateTime z = startsAt.atZoneSameInstant(BAGHDAD);
        return (z.format(CARD_DATE) + " · " + z.format(TIME)).toUpperCase(Locale.ENGLISH);
    }

    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        ZonedDateTime s = startsAt.atZoneSameInstant(BAGHDAD);
        String base = s.format(LONG_DATE) + " · " + s.format(TIME);
        if (endsAt != null) {
            base += " – " + endsAt.atZoneSameInstant(BAGHDAD).format(TIME);
        }
        return base;
    }

    public static String monthShort(OffsetDateTime startsAt) {
        return startsAt.atZoneSameInstant(BAGHDAD).format(MONTH_SHORT).toUpperCase(Locale.ENGLISH);
    }

    public static String dayOfMonth(OffsetDateTime startsAt) {
        return String.valueOf(startsAt.atZoneSameInstant(BAGHDAD).getDayOfMonth());
    }

    public static String iqd(long amount) {
        return String.format(Locale.ENGLISH, "%,d IQD", amount);
    }

    public static String priceLineFromMin(Long minPrice) {
        if (minPrice == null || minPrice == 0) return "Free";
        return "From " + iqd(minPrice);
    }

    public static String priceLabel(long price) {
        return price == 0 ? "Free" : iqd(price);
    }

    public static String compactCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 100_000) return String.format(Locale.ENGLISH, "%.1fK", n / 1000.0).replace(".0K", "K");
        return String.format(Locale.ENGLISH, "%dK", n / 1000);
    }

    public static String categoryLabel(Event.Category c) {
        return switch (c) {
            case MUSIC -> "Music";
            case TECH -> "Tech";
            case BUSINESS -> "Business";
            case ARTS -> "Arts & Culture";
            case FOOD -> "Food & Drink";
            case SPORTS -> "Sports";
            case COMMUNITY -> "Community";
            case EDUCATION -> "Education";
            case FILM -> "Film & Media";
            case FAMILY -> "Family";
        };
    }

    public static String coverTheme(Event.Category c) {
        return switch (c) {
            case MUSIC -> "music";
            case TECH -> "tech";
            case BUSINESS -> "business";
            case ARTS -> "arts";
            case FOOD -> "food";
            case SPORTS -> "sports";
            case COMMUNITY -> "community";
            case EDUCATION -> "education";
            case FILM -> "film";
            case FAMILY -> "family";
        };
    }
}
