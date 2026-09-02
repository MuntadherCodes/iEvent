package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.service.CatalogService;
import iq.ievent.service.Format;
import iq.ievent.service.UserService;
import iq.ievent.web.dto.Views.EventCard;
import iq.ievent.web.dto.Views.EventDetail;
import iq.ievent.web.dto.Views.GalleryImageView;
import iq.ievent.web.dto.Views.LineupItem;
import iq.ievent.web.dto.Views.OrganizerExtras;
import iq.ievent.web.dto.Views.OrganizerView;
import iq.ievent.web.dto.Views.PastEventCard;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class PageController {

    /** Category filter options shown on browse; value ↔ Event.Category name.
     *  Order drives every place this list is rendered (browse filter, host
     *  wizard dropdown, homepage tiles, newsletter picker) — led with the
     *  platform's focus (business, learning, community, culture), see R23. */
    public record CategoryOption(String value, String label) {}

    public static final List<CategoryOption> CATEGORIES = List.of(
            // Order = platform focus (R23): business, learning, community and culture
            // lead; music/film close the list. Drives the home tiles and browse chips.
            new CategoryOption("BUSINESS", "Business"),
            new CategoryOption("EDUCATION", "Education"),
            new CategoryOption("COMMUNITY", "Community"),
            new CategoryOption("ARTS", "Arts & Culture"),
            new CategoryOption("TECH", "Tech"),
            new CategoryOption("FOOD", "Food & Drink"),
            new CategoryOption("SPORTS", "Sports"),
            new CategoryOption("FAMILY", "Family"),
            new CategoryOption("FILM", "Film & Media"),
            new CategoryOption("MUSIC", "Music"));

    private static final List<String> WHEN_VALUES = List.of("today", "tomorrow", "weekend", "week", "month");

    private final CatalogService catalog;
    private final UserService userService;
    private final MessageSource messages;
    private final iq.ievent.service.InteractionService interactions;
    private final iq.ievent.service.TeamService teamService;
    private final iq.ievent.repo.EventRepository events;
    private final iq.ievent.repo.OrganizationRepository organizations;
    private final iq.ievent.repo.LikeCountRepository likeCounts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final String supportEmail;

    public PageController(CatalogService catalog, UserService userService,
                          MessageSource messages,
                          iq.ievent.service.InteractionService interactions,
                          iq.ievent.service.TeamService teamService,
                          iq.ievent.repo.EventRepository events,
                          iq.ievent.repo.OrganizationRepository organizations,
                          iq.ievent.repo.LikeCountRepository likeCounts,
                          org.springframework.jdbc.core.JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Value("${app.mail.support}") String supportEmail) {
        this.catalog = catalog;
        this.userService = userService;
        this.messages = messages;
        this.interactions = interactions;
        this.teamService = teamService;
        this.events = events;
        this.organizations = organizations;
        this.likeCounts = likeCounts;
        this.jdbc = jdbc;
        this.supportEmail = supportEmail;
    }

    @GetMapping("/about")
    public String about() {
        return "about";
    }

    @GetMapping("/help")
    public String help() {
        return "help";
    }

    @GetMapping("/pricing")
    public String pricing() {
        return "pricing";
    }

    @GetMapping("/how-it-works")
    public String howItWorks() {
        return "how-it-works";
    }

    @GetMapping("/features")
    public String features() {
        return "features";
    }

    @GetMapping("/solutions")
    public String solutions() {
        return "solutions";
    }

    @GetMapping("/guides")
    public String guides() {
        return "guides";
    }

    @GetMapping("/guides/attendees")
    public String guideAttendees() {
        return "guides/attendees";
    }

    @GetMapping("/guides/organizers")
    public String guideOrganizers() {
        return "guides/organizers";
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        model.addAttribute("supportEmail", supportEmail);
        return "privacy";
    }

    @GetMapping("/terms")
    public String terms(Model model) {
        model.addAttribute("supportEmail", supportEmail);
        return "terms";
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /** CATEGORIES with labels localized for the current request (values unchanged). */
    private List<CategoryOption> localizedCategories() {
        return CATEGORIES.stream()
                .map(c -> new CategoryOption(c.value(),
                        Format.categoryLabel(Event.Category.valueOf(c.value()))))
                .toList();
    }

    @ModelAttribute
    public void currentUser(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("currentUser",
                principal == null ? null : userService.byEmail(principal.getUsername()));
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("weekendEvents", catalog.upcomingThisWeek(4));
        model.addAttribute("trendingEvents", catalog.trending(8));
        model.addAttribute("cities", catalog.liveCities());
        model.addAttribute("categories", localizedCategories());
        // R20: the hero chips reflect the live catalog, not a hardcoded guess
        model.addAttribute("popularChips", catalog.popularCategories(3));
        model.addAttribute("hasFreeThisWeek", catalog.hasFreeEventThisWeek());
        return "index";
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String category,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String price,
                         @RequestParam(required = false) String when,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false, defaultValue = "0") int page,
                         Model model) {
        int safePage = Math.max(0, page);
        String safeWhen = when != null && WHEN_VALUES.contains(when) ? when : "";
        String safePrice = "free".equals(price) || "paid".equals(price) ? price : "";
        String safeSort = "price".equals(sort) || "popular".equals(sort) ? sort : "";

        Page<EventCard> results = catalog.search(q, category, city,
                safePrice.isEmpty() ? null : safePrice,
                safeWhen.isEmpty() ? null : safeWhen,
                safeSort.isEmpty() ? "soonest" : safeSort,
                PageRequest.of(safePage, 12));

        String safeQ = q == null ? "" : q.trim();
        String safeCategory = category == null ? "" : category;
        String selectedCategoryLabel = CATEGORIES.stream()
                .filter(c -> c.value().equals(safeCategory))
                .map(c -> Format.categoryLabel(Event.Category.valueOf(c.value())))
                .findFirst().orElse("");

        model.addAttribute("results", results);
        model.addAttribute("q", safeQ);
        model.addAttribute("selectedCategory", safeCategory);
        model.addAttribute("selectedCategoryLabel", selectedCategoryLabel);
        model.addAttribute("selectedCity", city == null ? "" : city);
        model.addAttribute("selectedPrice", safePrice);
        model.addAttribute("selectedWhen", safeWhen);
        model.addAttribute("selectedWhenLabel", safeWhen.isEmpty() ? "" : msg("when." + safeWhen));
        model.addAttribute("selectedSort", safeSort.isEmpty() ? "soonest" : safeSort);
        model.addAttribute("hasFilters", !safeQ.isEmpty() || !safeCategory.isEmpty()
                || (city != null && !city.isBlank()) || !safePrice.isEmpty() || !safeWhen.isEmpty());
        model.addAttribute("totalCountLine",
                String.format(Locale.ENGLISH, "%,d", results.getTotalElements()));
        model.addAttribute("pageItems", pageItems(results.getNumber(), results.getTotalPages()));
        model.addAttribute("cities", catalog.liveCities());
        model.addAttribute("categories", localizedCategories());
        return "browse";
    }

    /** Windowed 0-based page indices for numbered pagination; -1 marks an ellipsis. */
    static List<Integer> pageItems(int current, int totalPages) {
        List<Integer> items = new ArrayList<>();
        if (totalPages <= 1) return items;
        int last = totalPages - 1;
        int prev = -2;
        for (int i = 0; i <= last; i++) {
            boolean show = i == 0 || i == last || Math.abs(i - current) <= 1;
            if (!show) continue;
            if (prev >= 0 && i - prev > 1) items.add(-1); // ellipsis
            items.add(i);
            prev = i;
        }
        return items;
    }

    @GetMapping("/e/{slug}")
    @Transactional(readOnly = true)
    public String event(@PathVariable String slug,
                        @AuthenticationPrincipal UserDetails principal,
                        Model model) {
        Event entity = events.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        // #15 draft preview: a DRAFT renders only for signed-in members of the
        // owning organization (owner/manager/staff via TeamService). Everyone
        // else keeps getting the same 404 as before, so drafts stay unguessable.
        // Same gate covers a super-admin takedown (event or whole org) — the
        // host still needs to see it to understand what happened, but it must
        // stay unguessable to the public exactly like a draft.
        boolean adminBlocked = entity.isAdminHidden() || entity.getOrganization().isDisabled();
        boolean previewMode = false;
        if (entity.getStatus() == Event.Status.DRAFT || adminBlocked) {
            iq.ievent.domain.User viewer = principal == null ? null : userService.byEmail(principal.getUsername());
            boolean allowed = viewer != null && teamService.accessOf(viewer)
                    .map(a -> a.org().getId().equals(entity.getOrganization().getId()))
                    .orElse(false);
            if (!allowed) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
            }
            previewMode = true;
        }

        EventDetail detail = catalog.eventDetail(slug)
                .orElseGet(() -> fallbackDetail(entity)); // CANCELLED/DRAFT events still render (with banner)

        if (!previewMode) {
            catalog.recordView(slug); // fire-and-forget view counter; previews don't count
        }

        boolean liked = false;
        boolean followingOrganizer = false;
        if (principal != null) {
            var user = userService.byEmail(principal.getUsername());
            if (user != null) {
                liked = interactions.isLiked(user.getId(), entity.getId());
                followingOrganizer = interactions.isFollowing(user.getId(), entity.getOrganization().getId());
            }
        }
        model.addAttribute("previewMode", previewMode);
        model.addAttribute("adminBlocked", adminBlocked);

        model.addAttribute("event", detail);
        model.addAttribute("liked", liked);
        model.addAttribute("followingOrganizer", followingOrganizer);
        model.addAttribute("related", catalog.related(slug, 3));

        // ---- wireframe extras computed server-side ----
        String statusName = entity.getStatus().name();
        model.addAttribute("eventStatus", statusName);
        model.addAttribute("purchasable",
                entity.getStatus() == Event.Status.LIVE && !entity.isAnnounceOnly() && !adminBlocked);
        // Fee now depends on each ticket type's own price (Format.bookingFeeFor / @t.bookingFee
        // in the template) rather than one flat number, so the model only needs to say whether
        // buyers pay a fee at all here (ABSORB events never show a fee on the public page).
        boolean absorbFee = "ABSORB".equals(entity.getFeeMode());
        model.addAttribute("absorbFee", absorbFee);
        boolean anyFeeShown = !absorbFee && detail.ticketTypes().stream().anyMatch(t -> t.priceIqd() > 0);
        model.addAttribute("anyFeeShown", anyFeeShown);
        // First buyable ticket type defaults to qty 1 in the rail (user request R9).
        model.addAttribute("defaultQtyTypeId", detail.ticketTypes().stream()
                .filter(t -> "ON_SALE".equals(t.status()) && t.remaining() > 0)
                .map(t -> t.id())
                .findFirst().orElse(null));
        String descriptionText = Format.localized(
                entity.getDescription(), entity.getDescriptionTranslated(), entity.getLanguage());
        model.addAttribute("descriptionHtml", iq.ievent.service.RichText.toDisplayHtml(descriptionText));
        String summaryText = Format.localized(
                entity.getSummary(), entity.getSummaryTranslated(), entity.getLanguage());
        model.addAttribute("summary",
                summaryText == null || summaryText.isBlank() ? null : summaryText.trim());
        model.addAttribute("tags", parseTags(entity.getTags()));
        String lineupText = Format.localized(
                entity.getLineup(), entity.getLineupTranslated(), entity.getLanguage());
        model.addAttribute("lineup", parseLineup(lineupText));
        model.addAttribute("refundPolicyText", refundPolicyText(entity.getRefundPolicy(), messages));
        model.addAttribute("directionsUrl", directionsUrl(entity));
        return "event";
    }

    /** Minimal detail for CANCELLED events (CatalogService only maps LIVE/ENDED).
     *  Reloads the organization by id: open-in-view is off, so the lazy proxy on the
     *  detached event cannot be initialized here (getId() alone is proxy-safe). */
    private EventDetail fallbackDetail(Event e) {
        Organization org = organizations.findById(e.getOrganization().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        long likes = likeCounts.likesForEvents(List.of(e.getId())).getOrDefault(e.getId(), 0L);
        OrganizerView organizer = new OrganizerView(
                org.getName(), org.getHandle(), org.getBio(), org.isVerified(),
                Format.compactCount(likeCounts.followersForOrganization(org.getId())),
                likeCounts.eventsHostedForOrganization(org.getId()),
                initialsOf(org.getName()),
                org.getLogoPath() == null || org.getLogoPath().isBlank() ? null : "/media/org-logo/" + org.getId());
        String descriptionText = Format.localized(e.getDescription(), e.getDescriptionTranslated(), e.getLanguage());
        List<String> paragraphs = Arrays.stream(descriptionText.split("\n\n"))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
        String primary = Format.coverUrl(e);
        List<GalleryImageView> allImages = new ArrayList<>();
        if (primary != null) allImages.add(new GalleryImageView(primary, e.getCoverFocusY()));
        allImages.addAll(jdbc.query(
                "SELECT url, focus_y FROM event_images WHERE event_id = ? ORDER BY sort_order",
                (rs, i) -> new GalleryImageView(rs.getString(1), rs.getInt(2)), e.getId()));
        return new EventDetail(
                e.getSlug(), Format.localized(e.getTitle(), e.getTitleTranslated(), e.getLanguage()),
                Format.categoryLabel(e.getCategory()),
                e.getCoverTheme(),
                primary,
                e.getCoverFocusY(),
                allImages,
                e.getCity(), Format.venueDisplay(e.getVenueName(), e.getLocationType()), e.getVenueAddress(),
                Format.longDateLine(e.getStartsAt(), e.getEndsAt(), e.isHasStartTime(), e.getDatePrecision()),
                Format.monthShort(e.getStartsAt(), e.getDatePrecision()),
                Format.dayOfMonth(e.getStartsAt(), e.getDatePrecision()),
                paragraphs,
                Format.priceLabel(0),
                likes,
                organizer,
                List.of(),
                e.getLocationType(),
                e.isAnnounceOnly(),
                e.getMapsUrl(),
                e.getDatePrecision(),
                Format.translatedNotice(e.getLanguage(), e.getTitleTranslated()),
                catalog.categoryChips(e),
                CatalogService.countdownTarget(e));
    }

    static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            while (t.startsWith("#")) t = t.substring(1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** One act per line, "Name — 10:00 PM" (em/en dash or hyphen separated). */
    static List<LineupItem> parseLineup(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<LineupItem> out = new ArrayList<>();
        for (String line : raw.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+[—–-]\\s+", 2);
            String name = parts[0].trim();
            String note = parts.length > 1 ? parts[1].trim() : null;
            if (!name.isEmpty()) out.add(new LineupItem(name, note == null || note.isEmpty() ? null : note));
        }
        return out;
    }

    static String refundPolicyText(String policy, MessageSource messages) {
        String p = policy == null ? "" : policy;
        String code = switch (p) {
            case "NO_REFUNDS" -> "page.refund.none";
            case "UP_TO_48H" -> "page.refund.48h";
            default -> "page.refund.7d";
        };
        return messages.getMessage(code, null, LocaleContextHolder.getLocale());
    }

    static String directionsUrl(Event e) {
        StringBuilder sb = new StringBuilder();
        if (e.getVenueName() != null && !e.getVenueName().isBlank()) sb.append(e.getVenueName().trim());
        String addr = e.getVenueAddress() != null && !e.getVenueAddress().isBlank()
                ? e.getVenueAddress().trim() : e.getCity();
        if (sb.length() > 0) sb.append(", ");
        sb.append(addr);
        return "https://maps.google.com/?q=" + URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Organizer-profile extras (logo, contact block, past events). Called from
     * organizer.html via ${@pageController.organizerExtras(org.handle)} because the
     * /organizers/{handle} handler lives in another controller this module cannot edit.
     */
    public OrganizerExtras organizerExtras(String handle) {
        Organization org = organizations.findByHandle(handle).orElse(null);
        if (org == null) {
            return new OrganizerExtras(0L, null, null, 50, null, null, null, null, null, null, null, List.of());
        }
        List<Event> all = events.findByOrganizationIdOrderByStartsAtDesc(org.getId());
        OffsetDateTime now = OffsetDateTime.now();
        List<Event> past = all.stream()
                .filter(e -> e.getStartsAt().isBefore(now))
                .filter(e -> e.getStatus() == Event.Status.LIVE || e.getStatus() == Event.Status.ENDED)
                .filter(e -> !e.isAdminHidden())
                .limit(12)
                .toList();
        Map<Long, Long> attended = new HashMap<>();
        if (!past.isEmpty()) {
            String placeholders = String.join(",", past.stream().map(e -> "?").toList());
            Object[] ids = past.stream().map(Event::getId).toArray();
            jdbc.query("SELECT event_id, count(*) FROM tickets WHERE status IN ('VALID','CHECKED_IN') "
                            + "AND event_id IN (" + placeholders + ") GROUP BY event_id",
                    rs -> { attended.put(rs.getLong(1), rs.getLong(2)); },
                    ids);
        }
        List<PastEventCard> pastCards = new ArrayList<>(past.size());
        for (Event e : past) {
            long n = attended.getOrDefault(e.getId(), 0L);
            pastCards.add(new PastEventCard(
                    e.getSlug(), e.getTitle(), e.getCoverTheme(),
                    Format.coverUrl(e),
                    e.getCity(), Format.venueDisplay(e.getVenueName(), e.getLocationType()),
                    Format.cardDateLine(e.getStartsAt(), e.getEndsAt(), e.isHasStartTime(), e.getDatePrecision()),
                    n > 0 ? msg("page.attended", String.format(Locale.ENGLISH, "%,d", n)) : null));
        }
        String website = clean(org.getWebsite());
        String instagram = clean(org.getInstagram());
        String instagramHandle = instagram == null ? null
                : instagram.replaceFirst("^https?://(www\\.)?instagram\\.com/", "").replaceFirst("^@", "").replaceAll("/+$", "");
        return new OrganizerExtras(
                org.getId(),
                org.getLogoPath() == null || org.getLogoPath().isBlank() ? null : "/media/org-logo/" + org.getId(),
                org.getCoverImagePath() == null || org.getCoverImagePath().isBlank() ? null : "/media/org-cover/" + org.getId(),
                Math.max(0, Math.min(100, org.getCoverFocusY())),
                org.getBrandColor() != null && org.getBrandColor().matches("#[0-9a-fA-F]{6}") ? org.getBrandColor() : null,
                clean(org.getContactEmail()),
                clean(org.getContactPhone()),
                website == null ? null : website.replaceFirst("^https?://", "").replaceAll("/+$", ""),
                website == null ? null : (website.startsWith("http") ? website : "https://" + website),
                instagramHandle == null ? null : "instagram.com/" + instagramHandle,
                instagramHandle == null ? null : "https://instagram.com/" + instagramHandle,
                pastCards);
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String initialsOf(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
