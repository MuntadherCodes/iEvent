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

    /** Beta: the platform charges no booking fee yet. Flip to false to turn
     *  the tiered formula below back on for new orders — it's kept intact,
     *  not deleted, for exactly that switch-over. */
    public static final boolean BOOKING_FEE_WAIVED = true;

    /** Platform booking fee for one ticket at this price: 750 IQD flat at or
     *  under 15,000 IQD; above that, 3% of price + 2,000 IQD, capped at
     *  15,000 IQD no matter how high the price climbs. Free tickets (price 0)
     *  never carry a fee. */
    public static long bookingFeeFor(long priceIqd) {
        if (BOOKING_FEE_WAIVED) return 0;
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

    /** Picks event copy matching the viewer's locale: {@code origin} as-written
     *  when the request locale matches the language the host wrote it in
     *  ({@code originLang}, "ar"/"en"); otherwise {@code translated} when a
     *  Google-Translate auto-translation exists, falling back to {@code origin}
     *  itself when it doesn't (translation never configured, or hasn't run
     *  yet — the event is still readable, just not in the viewer's language). */
    public static String localized(String origin, String translated, String originLang) {
        boolean originIsEnglish = "en".equals(originLang);
        if (isEnglish() == originIsEnglish) return origin;
        return (translated == null || translated.isBlank()) ? origin : translated;
    }

    public static String cardDateLine(OffsetDateTime startsAt) {
        Locale loc = displayLocale();
        ZonedDateTime z = startsAt.atZoneSameInstant(BAGHDAD);
        String s = z.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", loc)) + " · "
                + z.format(DateTimeFormatter.ofPattern("h:mm a", loc));
        return isEnglish() ? s.toUpperCase(Locale.ENGLISH) : s;
    }

    /** Event-specific variant: shows just the date, with no time portion at all,
     *  when the host never actually set a start time (see Event.hasStartTime —
     *  the timestamp itself still needs a real placeholder value under the hood,
     *  this is what keeps that placeholder from being shown as if it were real). */
    public static String cardDateLine(OffsetDateTime startsAt, boolean hasStartTime) {
        if (hasStartTime) return cardDateLine(startsAt);
        Locale loc = displayLocale();
        String s = startsAt.atZoneSameInstant(BAGHDAD).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", loc));
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

    /** Event-specific variant — see the cardDateLine(startsAt, hasStartTime) note.
     *  When there's no real start time, the end time (if any) is dropped too:
     *  showing just an end time with no start would read as broken, not informative. */
    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt, boolean hasStartTime) {
        return longDateLine(startsAt, endsAt, hasStartTime, displayLocale());
    }

    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt, boolean hasStartTime, Locale loc) {
        if (hasStartTime) return longDateLine(startsAt, endsAt, loc);
        return startsAt.atZoneSameInstant(BAGHDAD).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", loc));
    }

    // ---------- flexible date precision (see Event.datePrecision, V23) ----------

    /** starts_at placeholder stored for TBA-dated events: far enough out that
     *  every existing "upcoming" sort puts TBA events after real dates, and
     *  deterministic so tests and the edit form can recognize it. Never shown
     *  to anyone — display code branches on the precision first. */
    public static final OffsetDateTime TBA_PLACEHOLDER =
            java.time.LocalDateTime.of(2099, 12, 31, 12, 0).atZone(BAGHDAD).toOffsetDateTime();

    /** "Date TBA" chip/line text. */
    public static String dateTbaLabel() {
        return isEnglish() ? "Date to be announced" : "الموعد يُعلن لاحقًا";
    }

    /** "September 2026" (month-precision events). */
    public static String monthYearLine(OffsetDateTime startsAt, Locale loc) {
        return startsAt.atZoneSameInstant(BAGHDAD).format(DateTimeFormatter.ofPattern("MMMM yyyy", loc));
    }

    /** Compact "Sep 12 – 14, 2026" / "Sep 28 – Oct 2, 2026" range, plus the
     *  start time when the host set one. */
    private static String rangeCardLine(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                        boolean hasStartTime, Locale loc) {
        ZonedDateTime s = startsAt.atZoneSameInstant(BAGHDAD);
        ZonedDateTime e = (endsAt == null ? startsAt : endsAt).atZoneSameInstant(BAGHDAD);
        String out;
        if (s.getYear() != e.getYear()) {
            out = s.format(DateTimeFormatter.ofPattern("MMM d, yyyy", loc)) + " – "
                    + e.format(DateTimeFormatter.ofPattern("MMM d, yyyy", loc));
        } else if (s.getMonth() != e.getMonth()) {
            out = s.format(DateTimeFormatter.ofPattern("MMM d", loc)) + " – "
                    + e.format(DateTimeFormatter.ofPattern("MMM d, yyyy", loc));
        } else {
            out = s.format(DateTimeFormatter.ofPattern("MMM d", loc)) + " – "
                    + e.format(DateTimeFormatter.ofPattern("d, yyyy", loc));
        }
        if (hasStartTime) out += " · " + s.format(DateTimeFormatter.ofPattern("h:mm a", loc));
        return out;
    }

    /** Precision-aware card line — THE entry point for event dates on cards,
     *  rows and lists. DAY falls through to the classic single-day line. */
    public static String cardDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                      boolean hasStartTime, String precision) {
        Locale loc = displayLocale();
        String s = switch (precision == null ? "DAY" : precision) {
            case "TBA" -> dateTbaLabel();
            case "MONTH" -> monthYearLine(startsAt, loc);
            case "RANGE" -> rangeCardLine(startsAt, endsAt, hasStartTime, loc);
            default -> { yield null; }
        };
        if (s == null) return cardDateLine(startsAt, hasStartTime);
        return isEnglish() ? s.toUpperCase(Locale.ENGLISH) : s;
    }

    /** Precision-aware long line — event detail page, checkout, emails.
     *  RANGE spells out both weekday-dates; end time is dropped for ranges
     *  (each day differs anyway), start time kept when the host set one. */
    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                      boolean hasStartTime, String precision) {
        return longDateLine(startsAt, endsAt, hasStartTime, precision, displayLocale());
    }

    /** Explicit-locale variant — PDFs must stay English (Helvetica has no Arabic glyphs). */
    public static String longDateLine(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                      boolean hasStartTime, String precision, Locale loc) {
        switch (precision == null ? "DAY" : precision) {
            case "TBA":
                return loc == Locale.ENGLISH || "en".equals(loc.getLanguage())
                        ? "Date to be announced" : "الموعد يُعلن لاحقًا";
            case "MONTH":
                return monthYearLine(startsAt, loc);
            case "RANGE": {
                ZonedDateTime s = startsAt.atZoneSameInstant(BAGHDAD);
                ZonedDateTime e = (endsAt == null ? startsAt : endsAt).atZoneSameInstant(BAGHDAD);
                DateTimeFormatter full = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", loc);
                DateTimeFormatter noYear = DateTimeFormatter.ofPattern("EEEE, MMMM d", loc);
                String base = (s.getYear() == e.getYear() ? s.format(noYear) : s.format(full))
                        + " – " + e.format(full);
                if (hasStartTime) base += " · " + s.format(DateTimeFormatter.ofPattern("h:mm a", loc));
                return base;
            }
            default:
                return longDateLine(startsAt, endsAt, hasStartTime, loc);
        }
    }

    /** Date-badge top label (small chip on cards/event page). */
    public static String monthShort(OffsetDateTime startsAt, String precision) {
        if ("TBA".equals(precision)) return isEnglish() ? "TBA" : "لاحقًا";
        return monthShort(startsAt);
    }

    /** Date-badge big label: the day number normally, the year for
     *  month-precision ("SEP / 2026" reads naturally), a dash for TBA. */
    public static String dayOfMonth(OffsetDateTime startsAt, String precision) {
        if ("TBA".equals(precision)) return "—";
        if ("MONTH".equals(precision)) {
            return String.valueOf(startsAt.atZoneSameInstant(BAGHDAD).getYear());
        }
        return dayOfMonth(startsAt);
    }

    /** Non-null exactly when the viewer is reading the AUTO-TRANSLATED copy of
     *  this event (their locale differs from the language the host wrote it
     *  in, and a machine translation exists to serve them) — the public page
     *  shows this as a small transparency notice. Null when the viewer sees
     *  the host's original text, so the notice never renders then. */
    public static String translatedNotice(String originLang, String titleTranslated) {
        boolean originIsEnglish = "en".equals(originLang);
        if (isEnglish() == originIsEnglish) return null;                  // viewing the original
        if (titleTranslated == null || titleTranslated.isBlank()) return null; // fallback shows original
        return isEnglish()
                ? "Translated automatically from Arabic — switch the site language to see the original."
                : "تُرجم هذا المحتوى آليًا من الإنجليزية — بدّل لغة الموقع لعرض النص الأصلي.";
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
