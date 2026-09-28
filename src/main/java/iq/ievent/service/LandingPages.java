package iq.ievent.service;

import iq.ievent.domain.Event;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The evergreen, indexable listing pages (R34): one per city (/city/baghdad), one per
 * category (/category/business) and one per event type (/exhibitions, /conferences ...),
 * each in Arabic (bare path) and English (/en/...). They give search engines a stable
 * page to rank for "فعاليات بغداد" / "events in Erbil" / "معارض العراق" style queries,
 * while the /browse?... filter combinations stay noindex (see PageController#browse).
 *
 * An event's "type" is not a stored field: it is read off the words hosts put in the
 * title (معرض, مؤتمر, ورشة, دورة, حفل, مهرجان and their English counterparts), in
 * either language, with a case-insensitive Postgres regex (~*).
 */
@Service
public class LandingPages {

    /** slug = the public path segment (/exhibitions); regex = Postgres ARE syntax. */
    public record Type(String slug, String titleRegex) {}

    public static final List<Type> TYPES = List.of(
            new Type("exhibitions", "معرض|معارض|exhibition|expo"),
            new Type("conferences", "مؤتمر|موتمر|ملتقى|منتدى|قمة|conference|summit|congress|forum"),
            // "الدورة السابعة" is an EDITION of a fair, not a course: skip دورة right after ال
            new Type("courses", "(?<!ال)دورة|دورات|كورس|تدريب|course|training|bootcamp"),
            new Type("workshops", "ورش|workshop|masterclass"),
            new Type("concerts", "حفل|أمسية|امسية|concert|live music"),
            new Type("festivals", "مهرجان|كرنفال|festival|carnival"));

    private static final String PUBLIC_EVENTS = """
            FROM events e
            JOIN organizations o ON o.id = e.organization_id
            WHERE e.status IN ('LIVE', 'ENDED') AND e.visibility = 'PUBLIC'
              AND e.admin_hidden = false AND o.disabled = false
            """;

    private final JdbcTemplate jdbc;

    public LandingPages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Type typeBySlug(String slug) {
        if (slug == null) return null;
        String s = slug.trim().toLowerCase(Locale.ENGLISH);
        for (Type t : TYPES) {
            if (t.slug().equals(s)) return t;
        }
        return null;
    }

    /** Event.Category for a /category/{slug} segment, or null when unknown. */
    public static Event.Category categoryBySlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        try {
            return Event.Category.valueOf(slug.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String cityPath(String canonicalEn) {
        return "/city/" + Cities.slug(canonicalEn);
    }

    public static String categoryPath(Event.Category c) {
        return "/category/" + c.name().toLowerCase(Locale.ROOT);
    }

    public static String typePath(Type t) {
        return "/" + t.slug();
    }

    // ---- what the sitemap lists: only pages that currently have something on them ----

    /** Canonical English city names with at least one public LIVE/ENDED event. */
    public List<String> citiesWithEvents() {
        List<String> out = new ArrayList<>();
        jdbc.query("SELECT DISTINCT e.city " + PUBLIC_EVENTS + " AND e.city IS NOT NULL",
                rs -> {
                    String city = rs.getString(1);
                    if (Cities.isValid(city)) out.add(city);
                });
        // Keep the display order of Cities.ALL (Baghdad first) rather than DB order.
        List<String> ordered = new ArrayList<>();
        for (String name : Cities.NAMES) {
            if (out.contains(name)) ordered.add(name);
        }
        return ordered;
    }

    /** Categories (primary or secondary) carried by at least one public LIVE/ENDED event. */
    public List<Event.Category> categoriesWithEvents() {
        java.util.Set<String> names = new java.util.HashSet<>();
        jdbc.query("SELECT DISTINCT e.category " + PUBLIC_EVENTS,
                rs -> { names.add(rs.getString(1)); });
        jdbc.query("SELECT DISTINCT ec.category FROM event_categories ec JOIN events e ON e.id = ec.event_id "
                        + "JOIN organizations o ON o.id = e.organization_id "
                        + "WHERE e.status IN ('LIVE', 'ENDED') AND e.visibility = 'PUBLIC' "
                        + "AND e.admin_hidden = false AND o.disabled = false",
                rs -> { names.add(rs.getString(1)); });
        List<Event.Category> out = new ArrayList<>();
        for (Event.Category c : Event.Category.values()) {
            if (names.contains(c.name())) out.add(c);
        }
        return out;
    }

    /** Types whose title regex matches at least one public LIVE/ENDED event. */
    public List<Type> typesWithEvents() {
        List<Type> out = new ArrayList<>();
        for (Type t : TYPES) {
            Boolean any = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 " + PUBLIC_EVENTS
                            + " AND (e.title ~* ? OR COALESCE(e.title_translated, '') ~* ?))",
                    Boolean.class, t.titleRegex(), t.titleRegex());
            if (Boolean.TRUE.equals(any)) out.add(t);
        }
        return out;
    }
}
