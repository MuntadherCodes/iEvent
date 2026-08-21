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

    /** The event's primary cover, wherever it's shown as a single thumbnail
     *  (cards, dashboard, checkout) — an uploaded file always wins; a
     *  Pexels-picked primary (no file uploaded) is the fallback; null means
     *  the gradient/theme placeholder. */
    public static String coverUrl(Event e) {
        if (e.getCoverImagePath() != null) return "/media/event-cover/" + e.getId();
        return e.getCoverImageUrl();
    }

    public static final long BOOKING_FEE_FLAT_THRESHOLD_IQD = 15_000L;
    public static final long BOOKING_FEE_FLAT_IQD = 750L;
    public static final double BOOKING_FEE_PERCENT = 0.03;
    public static final long BOOKING_FEE_PERCENT_ADDON_IQD = 2_000L;
    public static final long BOOKING_FEE_MAX_IQD = 15_000L;

    /** Platform booking fee for one ticket at this price: 750 IQD flat at or
     *  under 15,000 IQD; above that, 3% of price + 2,000 IQD, capped at
     *  15,000 IQD no matter how high the price climbs. Free tickets (price 0)
     *  never carry a fee. */
    public static long bookingFeeFor(long priceIqd) {
        if (priceIqd <= 0) return 0;
        if (priceIqd <= BOOKING_FEE_FLAT_THRESHOLD_IQD) return BOOKING_FEE_FLAT_IQD;
        long percentFee = Math.round(priceIqd * BOOKING_FEE_PERCENT) + BOOKING_FEE_PERCENT_ADDON_IQD;
        return Math.min(BOOKING_FEE_MAX_IQD, percentFee);
    }

    /** Arabic locale for date words (day/month names); digits stay Western. */
    private static final Locale ARABIC = new Locale("ar");

    private Format() {}

    /** Current display locale: Arabic unless the request is on /en. */
    private static Locale displayLocale() {
        Locale l = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        return l != null && "en".equals(l.getLanguage()) ? Locale.ENGLISH : ARABIC;
    }

    private static boolean isEnglish() {
        return displayLocale() == Locale.ENGLISH;
    }

    public static String cardDateLine(OffsetDateTime startsAt) {
        Locale loc = displayLocale();
        ZonedDateTime z = startsAt.atZoneSameInstant(BAGHDAD);
        String s = z.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", loc)) + " · "
                + z.format(DateTimeFormatter.ofPattern("h:mm a", loc));
        return isEnglish() ? s.toUpperCase(Locale.ENGLISH) : s;
    }

    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return longDateLine(startsAt, endsAt, displayLocale());
    }

    /** Explicit-locale variant — PDFs must stay English (Helvetica has no Arabic glyphs). */
    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt, Locale loc) {
        ZonedDateTime s = startsAt.atZoneSameInstant(BAGHDAD);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("h:mm a", loc);
        String base = s.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", loc)) + " · " + s.format(time);
        if (endsAt != null) {
            base += " – " + endsAt.atZoneSameInstant(BAGHDAD).format(time);
        }
        return base;
    }

    public static String monthShort(OffsetDateTime startsAt) {
        Locale loc = displayLocale();
        String s = startsAt.atZoneSameInstant(BAGHDAD)
                .format(DateTimeFormatter.ofPattern("MMM", loc));
        return isEnglish() ? s.toUpperCase(Locale.ENGLISH) : s;
    }

    public static String dayOfMonth(OffsetDateTime startsAt) {
        return String.valueOf(startsAt.atZoneSameInstant(BAGHDAD).getDayOfMonth());
    }

    public static String iqd(long amount) {
        // Western digits in both languages (common for prices in Iraq).
        return String.format(Locale.ENGLISH, "%,d", amount) + (isEnglish() ? " IQD" : " د.ع");
    }

    public static String priceLineFromMin(Long minPrice) {
        if (minPrice == null || minPrice == 0) return isEnglish() ? "Free" : "مجاني";
        return (isEnglish() ? "From " : "ابتداءً من ") + iqd(minPrice);
    }

    public static String priceLabel(long price) {
        if (price == 0) return isEnglish() ? "Free" : "مجاني";
        return iqd(price);
    }

    public static String compactCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 100_000) return String.format(Locale.ENGLISH, "%.1fK", n / 1000.0).replace(".0K", "K");
        return String.format(Locale.ENGLISH, "%dK", n / 1000);
    }

    /** "just now", "5m ago", "3h ago", "2d ago", else a short date (localized). */
    public static String timeAgo(OffsetDateTime when) {
        if (when == null) return "";
        boolean en = isEnglish();
        long mins = java.time.Duration.between(when, OffsetDateTime.now()).toMinutes();
        if (mins < 1) return en ? "just now" : "الآن";
        if (mins < 60) return en ? mins + "m ago" : "منذ " + mins + " د";
        long hours = mins / 60;
        if (hours < 24) return en ? hours + "h ago" : "منذ " + hours + " س";
        long days = hours / 24;
        if (days < 7) return en ? days + "d ago" : "منذ " + days + " ي";
        return cardDateLine(when);
    }

    /** Card-level venue text: localizes the ONLINE/TBA placeholders (stored
     *  canonically in English) without touching real venue names. */
    public static String venueDisplay(String venueName, String locationType) {
        boolean en = isEnglish();
        if ("ONLINE".equals(locationType) || "Online event".equals(venueName)) {
            return en ? "Online event" : "فعالية عبر الإنترنت";
        }
        if ("TBA".equals(locationType) || "To be announced".equals(venueName)) {
            return en ? "To be announced" : "يُعلن لاحقًا";
        }
        return venueName;
    }

    public static String categoryLabel(Event.Category c) {
        if (isEnglish()) {
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
        return switch (c) {
            case MUSIC -> "موسيقى";
            case TECH -> "تقنية";
            case BUSINESS -> "أعمال";
            case ARTS -> "فنون وثقافة";
            case FOOD -> "مأكولات ومشروبات";
            case SPORTS -> "رياضة";
            case COMMUNITY -> "مجتمع";
            case EDUCATION -> "تعليم";
            case FILM -> "سينما وإعلام";
            case FAMILY -> "عائلة";
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
