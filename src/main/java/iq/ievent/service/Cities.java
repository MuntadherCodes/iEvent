package iq.ievent.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * All 19 Iraqi governorates. The ENGLISH name is the canonical value stored in
 * the database and used in URLs/filters; templates display the localized label.
 */
public final class Cities {

    public record City(String en, String ar) {}

    public static final List<City> ALL = List.of(
            new City("Baghdad", "بغداد"),
            new City("Basra", "البصرة"),
            new City("Mosul", "الموصل"),            // Nineveh governorate seat
            new City("Erbil", "أربيل"),
            new City("Sulaymaniyah", "السليمانية"),
            new City("Duhok", "دهوك"),
            new City("Halabja", "حلبجة"),
            new City("Kirkuk", "كركوك"),
            new City("Najaf", "النجف"),
            new City("Karbala", "كربلاء"),
            new City("Anbar", "الأنبار"),
            new City("Diyala", "ديالى"),
            new City("Babil", "بابل"),
            new City("Wasit", "واسط"),
            new City("Maysan", "ميسان"),
            new City("Dhi Qar", "ذي قار"),
            new City("Muthanna", "المثنى"),
            new City("Qadisiyyah", "القادسية"),
            new City("Salahaddin", "صلاح الدين"));

    /** Canonical English names, in display order. */
    public static final List<String> NAMES = ALL.stream().map(City::en).toList();

    private static final Map<String, String> AR_BY_EN = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(City::en, City::ar));

    /** URL slug (lower-case, hyphenated: "Dhi Qar" becomes "dhi-qar") to canonical
     *  English name, for the per-city landing pages under /city/{slug}. */
    private static final Map<String, String> EN_BY_SLUG = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(c -> slug(c.en()), City::en));

    private Cities() { }

    public static boolean isValid(String en) {
        return en != null && AR_BY_EN.containsKey(en);
    }

    /** Slug for a canonical English city name ("Dhi Qar" becomes "dhi-qar"). */
    public static String slug(String en) {
        return en == null ? "" : en.trim().toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-");
    }

    /** Canonical English name for a landing-page slug, or null when unknown. */
    public static String fromSlug(String slug) {
        return slug == null ? null : EN_BY_SLUG.get(slug.trim().toLowerCase(Locale.ENGLISH));
    }

    /** Localized display label for a stored English city name. */
    public static String label(String en, Locale locale) {
        if (en == null) return "";
        if (locale != null && "ar".equals(locale.getLanguage())) {
            return AR_BY_EN.getOrDefault(en, en);
        }
        return en;
    }
}
