package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Order;
import iq.ievent.domain.Organization;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.LikeCountRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.service.Format;
import iq.ievent.service.HostService;
import iq.ievent.service.OrderService;
import iq.ievent.service.UserService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Host dashboard (everything under /host). Every request resolves the current
 * user's organization; without one the user is sent to /host/start (onboarding).
 *
 * Template contracts (all pages also get: currentUser, org):
 *  host/start.html      — form fields orgName, handle, city, bio
 *  host/dashboard.html  — stats (HostService.HostStats + labels revenueLabel), upcoming (List<EventRow>),
 *                         recentOrders (List<OrderRow>), pendingCount (long)
 *  host/events.html     — eventRows (List<EventRow>)
 *  host/event-form.html — categories (PageController.CATEGORIES), cities (List<String>), form re-post target /host/events/new
 *  host/event-console.html — ev (EventRow), ticketRows (List<TicketTypeRow>), pendingCount, stats per event
 *  host/orders.html     — orders (Page<OrderRow>), statusFilter (String), pendingCount
 *  host/attendees.html  — ev (EventRow or null), eventOptions (List<EventRow>), rows (List<AttendeeRow>), q
 *  host/checkin.html    — eventOptions, ev, result (flash CheckinResult), counts {in,total}
 *  host/settings-payments.html — org, saved (flash)
 */
@Controller
@RequestMapping("/host")
public class HostController {

    public record EventRow(Long id, String slug, String title, String statusLabel, String statusKey, String dateLine,
                           String city, String venueName, long sold, long capacity, String salesLabel,
                           String revenueLabel, String coverImageUrl, String coverTheme) {}

    public record OrderRow(Long id, String orderCode, String buyerName, String buyerEmail,
                           String eventTitle, String itemsLabel, String totalLabel, String methodLabel,
                           String statusLabel, String statusKey, boolean pending, String createdLine,
                           boolean hasReceipt, String transferReference) {}

    public record TicketTypeRow(Long id, String name, String priceLabel, int quantity, int sold,
                                String statusLabel, String statusKey, String revenueLabel) {}

    /** Compact order line for the per-event console (queried via JDBC). */
    public record EventOrderRow(String orderCode, String buyerName, String itemsLabel,
                                String totalLabel, String statusLabel, String statusKey, boolean pending,
                                String createdLine) {}

    public record AttendeeRow(Long ticketId, String holderName, String typeName, String orderCode,
                              String code, boolean checkedIn, String checkedInLine, String avatarUrl) {}

    public record CheckinResult(boolean ok, String message, String holderName, String typeName) {}

    public record CheckinAjaxResult(boolean ok, String message, String holderName, String typeName,
                                     Long ticketId, String code, String checkedInLine,
                                     long checkedIn, long ticketsTotal) {}

    private final UserService userService;
    private final HostService hostService;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final TicketTypeRepository ticketTypes;
    private final EventRepository events;
    private final LikeCountRepository likeCounts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final String baseUrl;
    private final iq.ievent.service.MailService mailService;
    private final iq.ievent.service.AiContentService aiContentService;
    private final iq.ievent.service.PexelsService pexelsService;
    private final MessageSource messages;

    public HostController(UserService userService, HostService hostService, OrderService orderService,
                          OrderRepository orders, TicketRepository tickets, TicketTypeRepository ticketTypes,
                          EventRepository events, LikeCountRepository likeCounts,
                          org.springframework.jdbc.core.JdbcTemplate jdbc,
                          iq.ievent.service.MailService mailService,
                          iq.ievent.service.AiContentService aiContentService,
                          iq.ievent.service.PexelsService pexelsService,
                          MessageSource messages,
                          @org.springframework.beans.factory.annotation.Value("${app.base-url}") String baseUrl) {
        this.userService = userService;
        this.hostService = hostService;
        this.orderService = orderService;
        this.orders = orders;
        this.tickets = tickets;
        this.ticketTypes = ticketTypes;
        this.events = events;
        this.likeCounts = likeCounts;
        this.jdbc = jdbc;
        this.mailService = mailService;
        this.aiContentService = aiContentService;
        this.pexelsService = pexelsService;
        this.messages = messages;
        this.baseUrl = baseUrl;
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /** Wizard "Write it for me" — generates event copy via OpenAI for the
     *  description, summary, or lineup/agenda field (see "kind").
     *  "lang" is passed explicitly from document.documentElement.lang rather than
     *  relying on the ambient request locale: this endpoint's own URL is never
     *  under the "/en" prefix (the fetch always POSTs the bare path), so the
     *  usual URL-based locale resolution wouldn't see which language page the
     *  host is actually on. Hidden client-side (see GlobalModelAdvice's
     *  "aiAvailable") whenever OPENAI_API_KEY isn't configured. */
    @PostMapping("/events/ai/write")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, String>> aiWrite(
            @RequestParam String title, @RequestParam(required = false) String category,
            @RequestParam(required = false) String lang, @RequestParam(defaultValue = "DESCRIPTION") String kind,
            @RequestParam(required = false) String startTime, @RequestParam(required = false) String endTime,
            @AuthenticationPrincipal UserDetails principal) {
        requireManage(user(principal));
        if (!aiContentService.available() || title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "unavailable"));
        }
        iq.ievent.service.AiContentService.Kind parsedKind;
        try {
            parsedKind = iq.ievent.service.AiContentService.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "bad_kind"));
        }
        try {
            String text = aiContentService.generate(parsedKind, title.strip(), category, !"en".equals(lang), startTime, endTime);
            return ResponseEntity.ok(java.util.Map.of("text", text));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("error", "generation_failed"));
        }
    }

    /** Wizard cover-picker "search stock photos" tab. Hidden client-side (see
     *  GlobalModelAdvice's "pexelsAvailable") whenever PEXELS_API_KEY isn't
     *  configured. Just a thin auth-gated proxy — the API key never reaches
     *  the browser. */
    @GetMapping("/events/pexels-search")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> pexelsSearch(
            @RequestParam String q, @AuthenticationPrincipal UserDetails principal) {
        requireManage(user(principal));
        if (!pexelsService.available() || q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "unavailable"));
        }
        try {
            List<iq.ievent.service.PexelsService.Photo> photos = pexelsService.search(q.strip());
            return ResponseEntity.ok(java.util.Map.of("photos", photos));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("error", "search_failed"));
        }
    }

    private User user(UserDetails principal) {
        User u = principal == null ? null : userService.byEmail(principal.getUsername());
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return u;
    }

    // ---------- onboarding ----------

    @GetMapping("/start")
    public String start(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        if (hostService.organizationOf(u).isPresent()) return "redirect:/host";
        model.addAttribute("currentUser", u);
        return "host/start";
    }

    @PostMapping("/start")
    public String createOrg(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam String orgName,
                            @RequestParam(required = false) String handle,
                            @RequestParam(required = false) String city,
                            @RequestParam(required = false) String bio,
                            RedirectAttributes redirect) {
        User u = user(principal);
        if (hostService.organizationOf(u).isPresent()) return "redirect:/host";
        if (orgName == null || orgName.isBlank()) {
            redirect.addFlashAttribute("error", msg("flash.org.nameRequired"));
            return "redirect:/host/start";
        }
        hostService.createOrganization(u, orgName, handle, city, bio);
        return "redirect:/host";
    }

    // ---------- dashboard ----------

    @GetMapping({"", "/"})
    @Transactional(readOnly = true)
    public String dashboard(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam(defaultValue = "30") int range,
                            Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        // The overview shows revenue/orders/upcoming-events — all manager+
        // territory per the team role matrix. A staff-only member has nowhere
        // useful to land here, so send them straight to the one console page
        // they actually have (attendee check-in) instead of a locked landing
        // page right after login.
        if (hostService.accessOf(u).map(a -> !a.canManage()).orElse(true)) {
            return "redirect:/host/checkin";
        }

        HostService.HostStats stats = hostService.stats(org.getId());
        List<EventRow> upcoming = hostService.eventsOf(org.getId()).stream()
                .filter(e -> e.getStartsAt().isAfter(OffsetDateTime.now().minusDays(1)))
                .limit(5).map(this::toRow).toList();
        Page<Order> recent = orders.findForOrganization(org.getId(), null, PageRequest.of(0, 5));

        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        java.util.Locale dayLocale = "en".equals(LocaleContextHolder.getLocale().getLanguage())
                ? java.util.Locale.ENGLISH : new java.util.Locale("ar");
        model.addAttribute("todayLine", LocalDate.now(Format.BAGHDAD).format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", dayLocale)));
        model.addAttribute("stats", stats);
        model.addAttribute("revenueLabel", Format.iqd(stats.revenueIqd()));
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("recentOrders", recent.getContent().stream().map(this::toRow).toList());
        model.addAttribute("pendingCount", stats.pendingOrders());

        // Movement chips + audience stats
        model.addAttribute("deltas", hostService.statDeltas(org.getId()));
        model.addAttribute("viewsLabel", Format.compactCount(hostService.totalViews(org.getId())));
        model.addAttribute("followersLabel",
                Format.compactCount(likeCounts.followersForOrganization(org.getId())));

        // First-steps checklist (hidden by the template when allDone())
        model.addAttribute("checklist", hostService.checklist(org));

        // Sales chart with 7d/30d/90d range pills
        int days = (range == 7 || range == 90) ? range : 30;
        List<HostService.DayPoint> salesPoints = hostService.dailySales(org.getId(), days);
        long salesMax = salesPoints.stream().mapToLong(HostService.DayPoint::amountIqd).max().orElse(0L);
        model.addAttribute("salesRange", days);
        model.addAttribute("salesPoints", salesPoints);
        model.addAttribute("salesMax", salesMax);
        model.addAttribute("salesMaxLabel", Format.iqd(salesMax));
        // Formatted per-day amount, parallel to salesPoints — the hover
        // tooltip previously showed only the date, with no actual figure.
        model.addAttribute("salesTooltips", salesPoints.stream()
                .map(p -> Format.iqd(p.amountIqd())).toList());
        String[] sparkline = sparklinePaths(salesPoints, salesMax);
        model.addAttribute("salesLinePath", sparkline[0]);
        model.addAttribute("salesAreaPath", sparkline[1]);
        return "host/dashboard";
    }

    /** SVG path data for the dashboard sales sparkline — a line graph reads far
     *  more naturally than bars for a "mostly flat, occasional spike" daily
     *  series (a wall of near-invisible min-height slivers looked broken).
     *  Fixed 1000x160 viewBox scaled by the SVG's own width/height attributes,
     *  so no client-side measurement or JS is needed. Returns [linePath, areaPath]. */
    private static String[] sparklinePaths(List<HostService.DayPoint> points, long max) {
        int n = points.size();
        if (n == 0 || max <= 0) return new String[] { "", "" };
        double w = 1000, h = 160, pad = 4;
        double stepX = n > 1 ? w / (n - 1) : 0;
        double[] xs = new double[n];
        double[] ys = new double[n];
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = n > 1 ? i * stepX : w / 2;
            double amt = points.get(i).amountIqd();
            double y = h - pad - (amt / (double) max) * (h - 2 * pad);
            xs[i] = x;
            ys[i] = y;
            line.append(i == 0 ? "M" : "L").append(fmtCoord(x)).append(',').append(fmtCoord(y)).append(' ');
        }
        StringBuilder area = new StringBuilder("M").append(fmtCoord(xs[0])).append(',').append(fmtCoord(h)).append(' ');
        for (int i = 0; i < n; i++) area.append("L").append(fmtCoord(xs[i])).append(',').append(fmtCoord(ys[i])).append(' ');
        area.append("L").append(fmtCoord(xs[n - 1])).append(',').append(fmtCoord(h)).append(" Z");
        return new String[] { line.toString(), area.toString() };
    }

    private static String fmtCoord(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    // ---------- events ----------

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public String events(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false, defaultValue = "all") String status,
                         Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        List<Event> all = hostService.eventsOf(org.getId());
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventRows",
                hostService.eventsOf(org.getId(), q, status).stream().map(this::toRow).toList());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("statusFilter", status == null || status.isBlank() ? "all" : status.toLowerCase());
        model.addAttribute("countAll", (long) all.size());
        model.addAttribute("countLive", all.stream().filter(e -> e.getStatus() == Event.Status.LIVE).count());
        model.addAttribute("countDraft", all.stream().filter(e -> e.getStatus() == Event.Status.DRAFT).count());
        model.addAttribute("countEnded", all.stream().filter(e -> e.getStatus() == Event.Status.ENDED).count());
        model.addAttribute("countCancelled", all.stream().filter(e -> e.getStatus() == Event.Status.CANCELLED).count());
        model.addAttribute("canManage", hostService.accessOf(u).map(a -> a.canManage()).orElse(false));
        model.addAttribute("pendingCount", hostService.stats(org.getId()).pendingOrders());
        return "host/events";
    }

    @GetMapping("/events/new")
    public String newEvent(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("categories", PageController.CATEGORIES);
        model.addAttribute("coverThemes", HostService.COVER_THEMES);
        model.addAttribute("paymentsReady", hostService.paymentsReady(org));
        return "host/event-form";
    }

    @PostMapping("/events/new")
    public String createEvent(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam String title,
                              @RequestParam String category,
                              @RequestParam String city,
                              @RequestParam(required = false) String venueName,
                              @RequestParam(required = false) String venueAddress,
                              @RequestParam(required = false) String locationType,
                              @RequestParam(required = false) String onlineUrl,
                              @RequestParam(required = false) String mapsUrl,
                              @RequestParam String date,
                              @RequestParam String startTime,
                              @RequestParam(required = false) String endTime,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String summary,
                              @RequestParam(required = false) String tags,
                              @RequestParam(required = false) String lineup,
                              @RequestParam(required = false) String visibility,
                              @RequestParam(required = false) String refundPolicy,
                              @RequestParam(required = false) String feeMode,
                              @RequestParam(name = "freeEvent", defaultValue = "false") boolean freeEvent,
                              @RequestParam(name = "announceOnly", defaultValue = "false") boolean announceOnly,
                              @RequestParam(name = "ttName", required = false) List<String> ttNames,
                              @RequestParam(name = "ttPrice", required = false) List<String> ttPrices,
                              @RequestParam(name = "ttQty", required = false) List<String> ttQtys,
                              @RequestParam(name = "action", defaultValue = "draft") String action,
                              @RequestParam(name = "coverImage", required = false)
                                  org.springframework.web.multipart.MultipartFile coverImage,
                              @RequestParam(name = "coverTheme", required = false) String coverTheme,
                              @RequestParam(required = false) Integer coverFocusY,
                              @RequestParam(required = false) Long draftEventId,
                              @RequestParam(name = "galleryUrl", required = false) List<String> galleryUrls,
                              @RequestParam(name = "galleryCreditName", required = false) List<String> galleryCreditNames,
                              @RequestParam(name = "galleryCreditUrl", required = false) List<String> galleryCreditUrls,
                              @RequestParam(name = "galleryFocusY", required = false) List<String> galleryFocusYs,
                              RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        LocationForm loc = locationForm(locationType, venueName, venueAddress, onlineUrl, mapsUrl);
        if (loc.error() != null) {
            redirect.addFlashAttribute("error", loc.error());
            return "redirect:/host/events/new";
        }
        try {
            List<HostService.TicketTypeForm> forms = new ArrayList<>();
            if (ttNames != null) {
                for (int i = 0; i < ttNames.size(); i++) {
                    String name = ttNames.get(i);
                    if (name == null || name.isBlank()) continue;
                    long price = 0;
                    int qty = 0;
                    try { price = Long.parseLong(ttPrices.get(i).replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                    try { qty = Integer.parseInt(ttQtys.get(i).replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                    if (freeEvent) price = 0; // "My event is free" wins over any typed price
                    forms.add(new HostService.TicketTypeForm(name, price, qty));
                }
            }
            // draftEventId: the wizard autosaved a real draft row mid-flow (see
            // /events/autosave-start) — finish that same row instead of creating a
            // second, duplicate event.
            Event existingDraft = draftEventId == null ? null
                    : hostService.eventOf(org.getId(), draftEventId)
                            .filter(e -> e.getStatus() == Event.Status.DRAFT).orElse(null);
            Event created;
            if (existingDraft != null) {
                hostService.updateEvent(existingDraft, title, Event.Category.valueOf(category), city,
                        loc.venueName(), loc.venueAddress(), LocalDate.parse(date), LocalTime.parse(startTime),
                        endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime), description);
                hostService.replaceTicketTypes(existingDraft, forms);
                created = existingDraft;
            } else {
                created = hostService.createEvent(org, title, Event.Category.valueOf(category), city,
                        loc.venueName(), loc.venueAddress(), LocalDate.parse(date), LocalTime.parse(startTime),
                        endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime),
                        description, forms);
            }
            created.setLocationType(loc.type());
            created.setOnlineUrl(loc.onlineUrl());
            created.setMapsUrl(loc.mapsUrl());
            created.setAnnounceOnly(announceOnly);
            applyExtras(created, summary, tags, lineup, visibility, refundPolicy, feeMode);
            hostService.applyCoverTheme(created, coverTheme);
            if (coverFocusY != null) hostService.setCoverFocusY(created, coverFocusY);
            String coverError = hostService.storeCover(created, coverImage);
            if (coverError != null) redirect.addFlashAttribute("error", coverError);
            hostService.replaceGalleryImages(created,
                    buildGalleryPicks(galleryUrls, galleryCreditNames, galleryCreditUrls, galleryFocusYs));
            if ("publish".equals(action)) {
                hostService.publish(created);
                redirect.addFlashAttribute("published", true);
            }
            return "redirect:/host/events/" + created.getId();
        } catch (Exception e) {
            redirect.addFlashAttribute("error", msg("flash.event.createFailed", e.getMessage()));
            return "redirect:/host/events/new";
        }
    }

    // ---------- autosave (real server persistence, not just the browser's localStorage backup) ----------

    /** Fires once the wizard's step-1 required fields are all filled — creates the
     *  real draft row so it survives a lost tab / different device, then every
     *  later field the wizard collects PATCHes that same row via /events/{id}/autosave.
     *  Deliberately minimal: no location/description/tickets yet, just enough to exist. */
    @PostMapping("/events/autosave-start")
    @ResponseBody
    public java.util.Map<String, Object> autosaveStart(@AuthenticationPrincipal UserDetails principal,
                                                        @RequestParam String title,
                                                        @RequestParam String category,
                                                        @RequestParam String city,
                                                        @RequestParam String date,
                                                        @RequestParam String startTime) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return java.util.Map.of("error", "no-org");
        requireManage(u);
        try {
            Event created = hostService.createEvent(org, title, Event.Category.valueOf(category), city,
                    null, null, LocalDate.parse(date), LocalTime.parse(startTime), null, "", List.of());
            return java.util.Map.of("id", created.getId());
        } catch (Exception e) {
            return java.util.Map.of("error", e.getMessage() == null ? "failed" : e.getMessage());
        }
    }

    /** Periodic PATCH for an already-autosaved wizard draft, or a real edit-page draft
     *  (event-edit.html points its own autosave timer at this too). Mirrors the fields
     *  {@link #updateEvent} accepts, minus ticket types and the cover image — those only
     *  change on an explicit Save/Publish click, not on every few seconds of typing. */
    @PostMapping("/events/{id}/autosave")
    @ResponseBody
    public java.util.Map<String, Object> autosave(@PathVariable Long id,
                              @AuthenticationPrincipal UserDetails principal,
                              @RequestParam String title,
                              @RequestParam String category,
                              @RequestParam String city,
                              @RequestParam(required = false) String venueName,
                              @RequestParam(required = false) String venueAddress,
                              @RequestParam(required = false) String locationType,
                              @RequestParam(required = false) String onlineUrl,
                              @RequestParam(required = false) String mapsUrl,
                              @RequestParam String date,
                              @RequestParam String startTime,
                              @RequestParam(required = false) String endTime,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String summary,
                              @RequestParam(required = false) String tags,
                              @RequestParam(required = false) String lineup,
                              @RequestParam(required = false) String visibility,
                              @RequestParam(required = false) String refundPolicy,
                              @RequestParam(required = false) String feeMode,
                              @RequestParam(name = "announceOnly", defaultValue = "false") boolean announceOnly,
                              @RequestParam(name = "coverTheme", required = false) String coverTheme) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return java.util.Map.of("error", "no-org");
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id).orElse(null);
        if (ev == null) return java.util.Map.of("error", "not-found");
        LocationForm loc = locationForm(locationType, venueName, venueAddress, onlineUrl, mapsUrl);
        if (loc.error() != null) return java.util.Map.of("error", loc.error());
        try {
            hostService.updateEvent(ev, title, Event.Category.valueOf(category), city,
                    loc.venueName(), loc.venueAddress(), LocalDate.parse(date), LocalTime.parse(startTime),
                    endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime), description);
            ev.setLocationType(loc.type());
            ev.setOnlineUrl(loc.onlineUrl());
            ev.setMapsUrl(loc.mapsUrl());
            ev.setAnnounceOnly(announceOnly);
            applyExtras(ev, summary, tags, lineup, visibility, refundPolicy, feeMode);
            hostService.applyCoverTheme(ev, coverTheme);
            return java.util.Map.of("ok", true);
        } catch (Exception e) {
            return java.util.Map.of("error", e.getMessage() == null ? "failed" : e.getMessage());
        }
    }

    @GetMapping("/events/{id}")
    @Transactional(readOnly = true)
    public String eventConsole(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<TicketTypeRow> ttRows = ticketTypes.findByEventIdOrderBySortOrderAsc(ev.getId()).stream()
                .map(tt -> new TicketTypeRow(tt.getId(), tt.getName(),
                        Format.priceLabel(tt.getPriceIqd()), tt.getQuantity(), tt.getSold(),
                        statusLabel(tt.getStatus().name()), tt.getStatus().name(),
                        Format.iqd((long) tt.getSold() * tt.getPriceIqd())))
                .toList();
        long sold = ttRows.stream().mapToLong(TicketTypeRow::sold).sum();
        // Paid event without a working payment setup → persistent console warning
        model.addAttribute("paymentsReady", hostService.paymentsReady(org));
        model.addAttribute("evHasPaid", hasPaidTickets(ev.getId()));
        long views = ev.getViewCount();
        long likes = likeCounts.likesForEvents(List.of(ev.getId())).getOrDefault(ev.getId(), 0L);
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("ev", toRow(ev));
        model.addAttribute("isLive", ev.getStatus() == Event.Status.LIVE);
        model.addAttribute("isDraft", ev.getStatus() == Event.Status.DRAFT);
        model.addAttribute("isCancelled", ev.getStatus() == Event.Status.CANCELLED);
        model.addAttribute("canManage", hostService.accessOf(u).map(a -> a.canManage()).orElse(false));
        model.addAttribute("ticketRows", ttRows);
        model.addAttribute("checkedIn", tickets.countByEventIdAndStatus(ev.getId(), Ticket.Status.CHECKED_IN));
        model.addAttribute("ticketsTotal", tickets.countByEventId(ev.getId()));
        model.addAttribute("viewsLabel", Format.compactCount(views));
        model.addAttribute("likesLabel", Format.compactCount(likes));
        model.addAttribute("conversionLabel", views > 0
                ? msg("console.conversion",
                        String.format(java.util.Locale.ENGLISH, "%.1f", 100.0 * sold / views))
                : msg("console.noViews"));
        model.addAttribute("eventOrders", ordersForEvent(ev.getId()));
        java.time.ZonedDateTime zc = ev.getStartsAt().atZoneSameInstant(Format.BAGHDAD);
        model.addAttribute("postponeDate", zc.toLocalDate().toString());
        model.addAttribute("postponeStart", zc.toLocalTime().toString().substring(0, 5));
        model.addAttribute("postponeEnd", ev.getEndsAt() == null ? ""
                : ev.getEndsAt().atZoneSameInstant(Format.BAGHDAD).toLocalTime().toString().substring(0, 5));
        model.addAttribute("shareBase", baseUrl);
        return "host/event-console";
    }

    /** Latest orders for one event — read-only JDBC (no repo method needed). */
    private List<EventOrderRow> ordersForEvent(Long eventId) {
        return jdbc.query("""
                SELECT o.order_code, o.buyer_name, o.total_iqd, o.status, o.created_at,
                       COALESCE((SELECT string_agg(oi.quantity || '× ' || tt.name, ', ')
                                   FROM order_items oi JOIN ticket_types tt ON tt.id = oi.ticket_type_id
                                  WHERE oi.order_id = o.id), '—') AS items
                FROM orders o
                WHERE o.event_id = ?
                ORDER BY o.created_at DESC
                LIMIT 6
                """,
                (rs, i) -> new EventOrderRow(
                        rs.getString(1), rs.getString(2), rs.getString(6),
                        Format.iqd(rs.getLong(3)), statusLabel(rs.getString(4)), rs.getString(4),
                        "PENDING_CONFIRMATION".equals(rs.getString(4)),
                        Format.cardDateLine(rs.getObject(5, java.time.OffsetDateTime.class))),
                eventId);
    }

    @GetMapping("/events/{id}/edit")
    @Transactional(readOnly = true)
    public String editEvent(@PathVariable Long id,
                            @AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        java.time.ZonedDateTime z = ev.getStartsAt().atZoneSameInstant(Format.BAGHDAD);
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("ev", toRow(ev));
        model.addAttribute("evEntity", new EventEditView(ev.getTitle(), ev.getCategory().name(),
                ev.getCity(), ev.getVenueName(), ev.getVenueAddress(),
                z.toLocalDate().toString(), z.toLocalTime().toString().substring(0, 5),
                ev.getEndsAt() == null ? "" : ev.getEndsAt().atZoneSameInstant(Format.BAGHDAD)
                        .toLocalTime().toString().substring(0, 5),
                ev.getDescription(),
                ev.getSummary() == null ? "" : ev.getSummary(),
                ev.getTags() == null ? "" : ev.getTags(),
                ev.getLineup() == null ? "" : ev.getLineup(),
                ev.getVisibility() == null ? "PUBLIC" : ev.getVisibility(),
                ev.getRefundPolicy() == null ? "UP_TO_7_DAYS" : ev.getRefundPolicy(),
                ev.getLocationType() == null ? "VENUE" : ev.getLocationType(),
                ev.getOnlineUrl() == null ? "" : ev.getOnlineUrl(),
                ev.getMapsUrl() == null ? "" : ev.getMapsUrl(),
                ev.isAnnounceOnly(),
                ev.getFeeMode() == null ? "PASS" : ev.getFeeMode()));
        model.addAttribute("isLive", ev.getStatus() == Event.Status.LIVE);
        model.addAttribute("isDraft", ev.getStatus() == Event.Status.DRAFT);
        model.addAttribute("isCancelled", ev.getStatus() == Event.Status.CANCELLED);
        model.addAttribute("canManage", hostService.accessOf(u).map(a -> a.canManage()).orElse(false));
        model.addAttribute("ticketRows",
                ticketTypes.findByEventIdOrderBySortOrderAsc(ev.getId()).stream()
                        .map(tt -> new TicketTypeEditRow(tt.getId(), tt.getName(), tt.getPriceIqd(),
                                tt.getQuantity(), tt.getSold(), tt.getStatus().name()))
                        .toList());
        model.addAttribute("categories", PageController.CATEGORIES);
        model.addAttribute("coverThemes", HostService.COVER_THEMES);
        model.addAttribute("currentTheme", ev.getCoverTheme());
        model.addAttribute("hasCoverImage", ev.getCoverImagePath() != null);
        model.addAttribute("coverImageUrl", Format.coverUrl(ev));
        model.addAttribute("coverFocusY", ev.getCoverFocusY());
        model.addAttribute("galleryImagesJson", galleryImagesJson(hostService.currentGalleryPicks(ev)));
        model.addAttribute("postponeDate", z.toLocalDate().toString());
        return "host/event-edit";
    }

    /** Pre-populates the edit page's picker widget — a small hand-built JSON
     *  array (url/thumbnailUrl/creditName/creditUrl/focusY) rather than pulling
     *  in Jackson's ObjectMapper for five already-plain-text/numeric fields.
     *  Escaping "</" defends against breaking out of the surrounding
     *  &lt;script&gt; tag if a credit name/URL ever contained it. */
    private String galleryImagesJson(List<HostService.GalleryImageForm> picks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < picks.size(); i++) {
            HostService.GalleryImageForm p = picks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"url\":").append(jsonString(p.url()))
              .append(",\"thumbnailUrl\":").append(jsonString(p.url()))
              .append(",\"creditName\":").append(jsonString(p.creditName()))
              .append(",\"creditUrl\":").append(jsonString(p.creditUrl()))
              .append(",\"focusY\":").append(p.focusY())
              .append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("</", "<\\/").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    public record EventEditView(String title, String category, String city, String venueName,
                                String venueAddress, String date, String startTime, String endTime,
                                String description, String summary, String tags, String lineup,
                                String visibility, String refundPolicy,
                                String locationType, String onlineUrl, String mapsUrl,
                                boolean announceOnly, String feeMode) {}

    public record TicketTypeEditRow(Long id, String name, long priceIqd, int quantity, int sold,
                                    String status) {}

    @PostMapping("/events/{id}/edit")
    public String updateEvent(@PathVariable Long id,
                              @AuthenticationPrincipal UserDetails principal,
                              @RequestParam String title,
                              @RequestParam String category,
                              @RequestParam String city,
                              @RequestParam(required = false) String venueName,
                              @RequestParam(required = false) String venueAddress,
                              @RequestParam(required = false) String locationType,
                              @RequestParam(required = false) String onlineUrl,
                              @RequestParam(required = false) String mapsUrl,
                              @RequestParam String date,
                              @RequestParam String startTime,
                              @RequestParam(required = false) String endTime,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String summary,
                              @RequestParam(required = false) String tags,
                              @RequestParam(required = false) String lineup,
                              @RequestParam(required = false) String visibility,
                              @RequestParam(required = false) String refundPolicy,
                              @RequestParam(required = false) String feeMode,
                              @RequestParam(name = "announceOnly", defaultValue = "false") boolean announceOnly,
                              @RequestParam(name = "coverImage", required = false)
                                  org.springframework.web.multipart.MultipartFile coverImage,
                              @RequestParam(name = "coverTheme", required = false) String coverTheme,
                              @RequestParam(required = false) Integer coverFocusY,
                              @RequestParam(name = "removeCover", defaultValue = "false") boolean removeCover,
                              @RequestParam(name = "galleryUrl", required = false) List<String> galleryUrls,
                              @RequestParam(name = "galleryCreditName", required = false) List<String> galleryCreditNames,
                              @RequestParam(name = "galleryCreditUrl", required = false) List<String> galleryCreditUrls,
                              @RequestParam(name = "galleryFocusY", required = false) List<String> galleryFocusYs,
                              RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocationForm loc = locationForm(locationType, venueName, venueAddress, onlineUrl, mapsUrl);
        if (loc.error() != null) {
            redirect.addFlashAttribute("error", loc.error());
            return "redirect:/host/events/" + id + "/edit";
        }
        try {
            hostService.updateEvent(ev, title, Event.Category.valueOf(category), city,
                    loc.venueName(), loc.venueAddress(), LocalDate.parse(date), LocalTime.parse(startTime),
                    endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime), description);
            ev.setLocationType(loc.type());
            ev.setOnlineUrl(loc.onlineUrl());
            ev.setMapsUrl(loc.mapsUrl());
            ev.setAnnounceOnly(announceOnly);
            applyExtras(ev, summary, tags, lineup, visibility, refundPolicy, feeMode);
            hostService.applyCoverTheme(ev, coverTheme);
            if (coverFocusY != null) hostService.setCoverFocusY(ev, coverFocusY);
            if (removeCover) hostService.removeCover(ev);
            String coverError = hostService.storeCover(ev, coverImage);
            if (coverError != null) redirect.addFlashAttribute("error", coverError);
            else redirect.addFlashAttribute("saved", true);
            hostService.replaceGalleryImages(ev,
                    buildGalleryPicks(galleryUrls, galleryCreditNames, galleryCreditUrls, galleryFocusYs));
        } catch (Exception e) {
            redirect.addFlashAttribute("error", msg("flash.event.saveFailed", e.getMessage()));
        }
        return "redirect:/host/events/" + id + "/edit";
    }

    @PostMapping("/events/{id}/tickets")
    public String upsertTicket(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails principal,
                               @RequestParam(required = false) Long ttId,
                               @RequestParam String name,
                               @RequestParam(defaultValue = "0") long price,
                               @RequestParam(defaultValue = "0") int quantity,
                               @RequestParam(defaultValue = "ON_SALE") String status,
                               RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String error = hostService.upsertTicketType(ev, ttId, name, price, quantity, status);
        if (error != null) redirect.addFlashAttribute("error", error);
        else redirect.addFlashAttribute("saved", true);
        return "redirect:/host/events/" + id + "/edit";
    }

    private void requireManage(User u) {
        if (hostService.accessOf(u).map(a -> !a.canManage()).orElse(true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** Team, payments & org settings are owner-only — canManage() (owner OR
     *  manager) is the wrong gate for these, unlike event/order/marketing
     *  mutations where requireManage is correct. */
    private void requireOwner(User u) {
        if (hostService.accessOf(u).map(a -> !a.owner()).orElse(true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** Normalised "Where is it?" form values (wizard + edit). error() != null → reject the post. */
    private record LocationForm(String type, String venueName, String venueAddress,
                                String onlineUrl, String mapsUrl, String error) {}

    /**
     * Whitelists the location type (VENUE/ONLINE/TBA, default VENUE) and derives the
     * dependent fields: VENUE requires a venue name and may carry a Google Maps link;
     * ONLINE stores the meeting link (revealed to confirmed buyers only) and a "Online
     * event" venue placeholder; TBA stores "To be announced". Whether the event sells
     * tickets at all is a separate flag (Event.announceOnly, see applyExtras) —
     * independent of location, since an announce-only event can still have a real venue.
     */
    private LocationForm locationForm(String locationType, String venueName,
                                      String venueAddress, String onlineUrl, String mapsUrl) {
        String type = "ONLINE".equals(locationType) || "TBA".equals(locationType) ? locationType : "VENUE";
        String online = onlineUrl == null || onlineUrl.isBlank() ? null : onlineUrl.trim();
        String maps = mapsUrl == null || mapsUrl.isBlank() ? null : mapsUrl.trim();
        if ("VENUE".equals(type)) {
            if (venueName == null || venueName.isBlank()) {
                return new LocationForm(type, null, null, null, null,
                        msg("location.venueRequired"));
            }
            if (maps != null && !(maps.startsWith("http://") || maps.startsWith("https://"))) {
                return new LocationForm(type, null, null, null, null,
                        msg("location.mapsUrlInvalid"));
            }
            return new LocationForm(type, venueName.trim(),
                    venueAddress == null || venueAddress.isBlank() ? null : venueAddress.trim(),
                    null, maps, null);
        }
        if ("ONLINE".equals(type)) {
            if (online != null && !(online.startsWith("http://") || online.startsWith("https://"))) {
                return new LocationForm(type, null, null, null, null,
                        msg("location.meetingUrlInvalid"));
            }
            return new LocationForm(type, "Online event", null, online, null, null);
        }
        return new LocationForm(type, "To be announced", null, null, null, null);
    }

    /** Persists the descriptive extras (summary, tags, lineup, visibility, refund policy, fee mode). */
    private void applyExtras(Event ev, String summary, String tags, String lineup,
                             String visibility, String refundPolicy, String feeMode) {
        String s = summary == null || summary.isBlank() ? null : summary.strip();
        ev.setSummary(s != null && s.length() > 160 ? s.substring(0, 160) : s);
        ev.setTags(tags == null || tags.isBlank() ? null : tags.strip());
        ev.setLineup(lineup == null || lineup.isBlank() ? null : lineup.strip());
        ev.setVisibility("UNLISTED".equalsIgnoreCase(visibility) ? "UNLISTED" : "PUBLIC");
        ev.setRefundPolicy(
                "UP_TO_7_DAYS".equals(refundPolicy) || "UP_TO_48H".equals(refundPolicy)
                        ? refundPolicy : "NO_REFUNDS");
        // Booking fee mode, whitelisted: ABSORB (organizer swallows the fee) or PASS (buyer pays it).
        ev.setFeeMode("ABSORB".equals(feeMode) ? "ABSORB" : "PASS");
        events.save(ev);
    }

    /** Parallel arrays (one per picked stock photo) into gallery-form records —
     *  same shape as the ttName/ttPrice/ttQty ticket-row arrays above. */
    private List<HostService.GalleryImageForm> buildGalleryPicks(
            List<String> urls, List<String> creditNames, List<String> creditUrls, List<String> focusYs) {
        if (urls == null) return List.of();
        List<HostService.GalleryImageForm> out = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (url == null || url.isBlank()) continue;
            String name = creditNames != null && i < creditNames.size() ? creditNames.get(i) : null;
            String link = creditUrls != null && i < creditUrls.size() ? creditUrls.get(i) : null;
            int focusY = 50;
            if (focusYs != null && i < focusYs.size()) {
                try { focusY = Integer.parseInt(focusYs.get(i)); } catch (Exception ignored) {}
            }
            out.add(new HostService.GalleryImageForm(url, name, link, focusY));
        }
        return out;
    }

    // ---------- lifecycle: duplicate / cancel / postpone ----------

    @PostMapping("/events/{id}/duplicate")
    public String duplicate(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                            RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Event copy = hostService.duplicateEvent(ev);
        redirect.addFlashAttribute("duplicated", msg("flash.event.duplicated"));
        return "redirect:/host/events/" + copy.getId() + "/edit";
    }

    @PostMapping("/events/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (ev.getStatus() != Event.Status.CANCELLED) {
            hostService.cancelEvent(ev);
            redirect.addFlashAttribute("actioned", msg("flash.event.cancelled"));
        }
        return "redirect:/host/events/" + id;
    }

    @PostMapping("/events/{id}/postpone")
    public String postpone(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                           @RequestParam String date,
                           @RequestParam String startTime,
                           @RequestParam(required = false) String endTime,
                           RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            hostService.postponeEvent(ev, LocalDate.parse(date), LocalTime.parse(startTime),
                    endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime));
            redirect.addFlashAttribute("actioned", msg("flash.event.postponed"));
        } catch (Exception e) {
            redirect.addFlashAttribute("error", msg("flash.event.postponeFailed"));
        }
        return "redirect:/host/events/" + id;
    }

    @PostMapping("/events/{id}/publish")
    public String publish(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                          RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        hostService.eventOf(org.getId(), id).ifPresent(e -> {
            hostService.publish(e);
            if (hasPaidTickets(e.getId()) && !hostService.paymentsReady(org)) {
                redirect.addFlashAttribute("warning", msg("flash.event.publishedNoPayments"));
            }
        });
        return "redirect:/host/events/" + id;
    }

    /** Any ticket type with a positive price on this event? */
    private boolean hasPaidTickets(Long eventId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_types WHERE event_id = ? AND price_iqd > 0",
                Long.class, eventId);
        return n != null && n > 0;
    }

    @PostMapping("/events/{id}/unpublish")
    public String unpublish(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        hostService.eventOf(org.getId(), id).ifPresent(hostService::unpublish);
        return "redirect:/host/events/" + id;
    }

    /** Drafts only — an event that ever went LIVE keeps its history and can only be cancelled. */
    @PostMapping("/events/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (ev.getStatus() != Event.Status.DRAFT) {
            redirect.addFlashAttribute("error", msg("flash.event.deleteNotDraft"));
            return "redirect:/host/events";
        }
        try {
            hostService.deleteEvent(ev);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", msg("flash.event.deleteHasOrders"));
            return "redirect:/host/events";
        }
        redirect.addFlashAttribute("actioned", msg("flash.event.deleted"));
        return "redirect:/host/events";
    }

    // ---------- orders ----------

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public String orders(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        // The enriched orders view (filters, stats, export) lives in
        // HostExtrasController behind params="f" — always funnel into it so the
        // template renders under exactly one model shape.
        String target = "redirect:/host/orders?f=1";
        if (status != null && !status.isBlank()) target += "&status=" + status;
        if (page > 0) target += "&page=" + page;
        return target;
    }

    @PostMapping("/orders/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                          RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        try {
            Order o = orderService.approve(id, org.getId());
            redirect.addFlashAttribute("actioned", msg("flash.order.approved", o.getOrderCode()));
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/host/orders?f=1&status=pending"; // direct to enriched view so flash survives
    }

    @PostMapping("/orders/{id}/reject")
    public String reject(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        try {
            Order o = orderService.reject(id, org.getId());
            redirect.addFlashAttribute("actioned", msg("flash.order.rejected", o.getOrderCode()));
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/host/orders?f=1&status=pending"; // direct to enriched view so flash survives
    }

    @GetMapping("/orders/{id}/receipt")
    @Transactional(readOnly = true)
    public ResponseEntity<FileSystemResource> receipt(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails principal) {
        User u = user(principal);
        requireManage(u);
        Organization org = hostService.organizationOf(u)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Order order = orders.findById(id)
                .filter(o -> o.getEvent().getOrganization().getId().equals(org.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (order.getReceiptPath() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = Path.of(order.getReceiptPath());
        MediaType type = order.getReceiptPath().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF : MediaType.IMAGE_JPEG;
        if (order.getReceiptPath().endsWith(".png")) type = MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(path));
    }

    // ---------- attendees & check-in ----------

    @GetMapping("/attendees")
    @Transactional(readOnly = true)
    public String attendees(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam(required = false) Long event,
                            @RequestParam(required = false) String q,
                            Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        List<EventRow> options = sortForSelect(hostService.eventsOf(org.getId()));
        EventRow selected = null;
        List<AttendeeRow> rows = List.of();
        if (event != null) {
            Event ev = hostService.eventOf(org.getId(), event).orElse(null);
            if (ev != null) {
                selected = toRow(ev);
                String qLike = q == null || q.isBlank() ? null
                        : "%" + q.trim().toLowerCase() + "%";
                List<Ticket> matched = tickets.searchForEvent(ev.getId(), qLike);
                java.util.Map<String, String> avatars = userService.avatarsByEmail(matched.stream()
                        .map(t -> t.getHolderEmail() != null ? t.getHolderEmail() : t.getOrder().getBuyerEmail())
                        .toList());
                rows = matched.stream().map(t -> toRow(t, avatars)).toList();
            }
        } else if (!options.isEmpty()) {
            return "redirect:/host/attendees?event=" + options.get(0).id();
        }
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventOptions", options);
        model.addAttribute("ev", selected);
        model.addAttribute("rows", rows);
        model.addAttribute("q", q == null ? "" : q);
        return "host/attendees";
    }

    private ResponseEntity<?> redirectOrDeny(String path, String requestedWith) {
        if ("XMLHttpRequest".equals(requestedWith)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", path).build();
    }

    @PostMapping("/tickets/{id}/checkin")
    @Transactional
    public ResponseEntity<?> checkin(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                          @RequestHeader(value = "Referer", required = false) String referer,
                          @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return redirectOrDeny("/host/start", requestedWith);
        Ticket t = tickets.findById(id)
                .filter(x -> x.getEvent().getOrganization().getId().equals(org.getId()))
                .orElse(null);
        boolean ok = t != null && t.getStatus() == Ticket.Status.VALID;
        if (ok) {
            t.setStatus(Ticket.Status.CHECKED_IN);
            t.setCheckedInAt(OffsetDateTime.now());
            tickets.save(t);
        }
        if ("XMLHttpRequest".equals(requestedWith)) {
            long in = t != null ? tickets.countByEventIdAndStatus(t.getEvent().getId(), Ticket.Status.CHECKED_IN) : 0;
            long total = t != null ? tickets.countByEventId(t.getEvent().getId()) : 0;
            return ResponseEntity.ok(new CheckinAjaxResult(ok, ok ? msg("checkin.ok") : msg("checkin.notFound"),
                    t != null ? t.getHolderName() : null, t != null ? t.getTicketType().getName() : null,
                    id, t != null ? t.getCode() : null,
                    t != null && t.getCheckedInAt() != null ? Format.cardDateLine(t.getCheckedInAt()) : null,
                    in, total));
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", referer == null ? "/host/attendees" : referer).build();
    }

    @PostMapping("/tickets/{id}/undo-checkin")
    @Transactional
    public ResponseEntity<?> undoCheckin(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              @RequestHeader(value = "X-Requested-With", required = false) String requestedWith) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return redirectOrDeny("/host/start", requestedWith);
        Ticket t = tickets.findById(id)
                .filter(x -> x.getEvent().getOrganization().getId().equals(org.getId()))
                .orElse(null);
        boolean ok = t != null && t.getStatus() == Ticket.Status.CHECKED_IN;
        if (ok) {
            t.setStatus(Ticket.Status.VALID);
            t.setCheckedInAt(null);
            tickets.save(t);
        }
        if ("XMLHttpRequest".equals(requestedWith)) {
            long in = t != null ? tickets.countByEventIdAndStatus(t.getEvent().getId(), Ticket.Status.CHECKED_IN) : 0;
            long total = t != null ? tickets.countByEventId(t.getEvent().getId()) : 0;
            return ResponseEntity.ok(new CheckinAjaxResult(ok, null, t != null ? t.getHolderName() : null,
                    t != null ? t.getTicketType().getName() : null, id, t != null ? t.getCode() : null,
                    null, in, total));
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", referer == null ? "/host/attendees" : referer).build();
    }

    @GetMapping("/checkin")
    @Transactional(readOnly = true)
    public String checkinPage(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam(required = false) Long event,
                              @RequestParam(required = false) String q,
                              Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        List<EventRow> options = sortForSelect(hostService.eventsOf(org.getId()));
        EventRow selected = null;
        long in = 0, total = 0;
        List<AttendeeRow> doorList = List.of();
        if (event != null) {
            Event ev = hostService.eventOf(org.getId(), event).orElse(null);
            if (ev != null) {
                selected = toRow(ev);
                in = tickets.countByEventIdAndStatus(ev.getId(), Ticket.Status.CHECKED_IN);
                total = tickets.countByEventId(ev.getId());
                String qLike = q == null || q.isBlank() ? null
                        : "%" + q.trim().toLowerCase() + "%";
                List<Ticket> doorMatched = tickets.searchForEvent(ev.getId(), qLike).stream().limit(50).toList();
                java.util.Map<String, String> doorAvatars = userService.avatarsByEmail(doorMatched.stream()
                        .map(t -> t.getHolderEmail() != null ? t.getHolderEmail() : t.getOrder().getBuyerEmail())
                        .toList());
                doorList = doorMatched.stream().map(t -> toRow(t, doorAvatars)).toList();
            }
        } else if (!options.isEmpty()) {
            return "redirect:/host/checkin?event=" + options.get(0).id();
        }
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventOptions", options);
        model.addAttribute("ev", selected);
        model.addAttribute("checkedIn", in);
        model.addAttribute("ticketsTotal", total);
        model.addAttribute("doorList", doorList);
        model.addAttribute("q", q == null ? "" : q);
        return "host/checkin";
    }

    @PostMapping("/checkin")
    @Transactional
    public ResponseEntity<?> checkinByCode(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam Long event,
                                @RequestParam String code,
                                @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                                RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return redirectOrDeny("/host/start", requestedWith);
        String clean = code == null ? "" : code.trim().toUpperCase().replaceAll(".*/T/", "");
        Ticket t = tickets.findByCode(clean).orElse(null);
        CheckinResult result;
        if (t == null || !t.getEvent().getOrganization().getId().equals(org.getId())) {
            result = new CheckinResult(false, msg("checkin.notFound"), null, null);
        } else if (!t.getEvent().getId().equals(event)) {
            result = new CheckinResult(false, msg("checkin.wrongEvent", t.getEvent().getTitle()), t.getHolderName(), t.getTicketType().getName());
        } else if (t.getStatus() == Ticket.Status.CHECKED_IN) {
            result = new CheckinResult(false, msg("checkin.already", t.getCheckedInAt() == null ? "" : Format.cardDateLine(t.getCheckedInAt())), t.getHolderName(), t.getTicketType().getName());
        } else if (t.getStatus() == Ticket.Status.VOID) {
            result = new CheckinResult(false, msg("checkin.void"), t.getHolderName(), t.getTicketType().getName());
        } else {
            t.setStatus(Ticket.Status.CHECKED_IN);
            t.setCheckedInAt(OffsetDateTime.now());
            tickets.save(t);
            result = new CheckinResult(true, msg("checkin.ok"), t.getHolderName(), t.getTicketType().getName());
        }
        if ("XMLHttpRequest".equals(requestedWith)) {
            long in = tickets.countByEventIdAndStatus(event, Ticket.Status.CHECKED_IN);
            long total = tickets.countByEventId(event);
            return ResponseEntity.ok(new CheckinAjaxResult(result.ok(), result.message(), result.holderName(), result.typeName(),
                    t != null ? t.getId() : null, t != null ? t.getCode() : null,
                    t != null && t.getCheckedInAt() != null ? Format.cardDateLine(t.getCheckedInAt()) : null,
                    in, total));
        }
        redirect.addFlashAttribute("result", result);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", "/host/checkin?event=" + event).build();
    }

    // ---------- payment settings ----------

    @GetMapping("/settings/payments")
    public String paymentSettings(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        // Payments are owner-only (see the role matrix on the team settings
        // page) — rather than a raw 403 for whoever clicked one of the several
        // in-context links pointing here (dashboard checklist, earnings note,
        // event paywarn banners), show the page in a read-only "ask the owner"
        // state so those links never dead-end.
        model.addAttribute("isOwner", hostService.accessOf(u).map(a -> a.owner()).orElse(false));
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        return "host/settings-payments";
    }

    @PostMapping("/settings/payments")
    public String savePaymentSettings(@AuthenticationPrincipal UserDetails principal,
                                      @RequestParam(defaultValue = "false") boolean enabled,
                                      @RequestParam(required = false) String cardNumber,
                                      @RequestParam(required = false) String accountName,
                                      @RequestParam(required = false) String walletBank,
                                      @RequestParam(required = false) String instructions,
                                      RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireOwner(u);
        hostService.savePaymentSettings(org, enabled, cardNumber, accountName, walletBank, instructions);
        redirect.addFlashAttribute("saved", true);
        return "redirect:/host/settings/payments";
    }

    @PostMapping("/test-mail")
    public String testMail(@AuthenticationPrincipal UserDetails principal,
                           RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireOwner(u);
        mailService.sendCampaign(u.getEmail(), msg("mail.test.subject"), msg("mail.test.body"),
                org.getName(), baseUrl + "/host", LocaleContextHolder.getLocale());
        redirect.addFlashAttribute("testMailSent", u.getEmail());
        return "redirect:/host/settings/payments";
    }

    /** Soonest upcoming events first, then past events (most recent first). */
    private List<EventRow> sortForSelect(List<Event> events) {
        OffsetDateTime now = OffsetDateTime.now();
        return events.stream()
                .sorted((a, b) -> {
                    boolean fa = a.getStartsAt().isAfter(now);
                    boolean fb = b.getStartsAt().isAfter(now);
                    if (fa != fb) return fa ? -1 : 1;
                    return fa ? a.getStartsAt().compareTo(b.getStartsAt())
                              : b.getStartsAt().compareTo(a.getStartsAt());
                })
                .map(this::toRow)
                .toList();
    }

    // ---------- mapping ----------

    private EventRow toRow(Event e) {
        List<TicketType> tts = ticketTypes.findByEventIdOrderBySortOrderAsc(e.getId());
        long sold = tts.stream().mapToLong(TicketType::getSold).sum();
        long cap = tts.stream().mapToLong(TicketType::getQuantity).sum();
        long revenue = tts.stream().mapToLong(t -> t.getSold() * t.getPriceIqd()).sum();
        return new EventRow(e.getId(), e.getSlug(), e.getTitle(), statusLabel(e.getStatus().name()),
                e.getStatus().name(),
                Format.cardDateLine(e.getStartsAt()), e.getCity(), Format.venueDisplay(e.getVenueName(), e.getLocationType()),
                sold, cap, sold + " / " + cap, Format.iqd(revenue),
                Format.coverUrl(e),
                e.getCoverTheme());
    }

    private OrderRow toRow(Order o) {
        String items = o.getItems().stream()
                .map(i -> i.getQuantity() + "× " + i.getTicketType().getName())
                .reduce((a, b) -> a + ", " + b).orElse("—");
        return new OrderRow(o.getId(), o.getOrderCode(), o.getBuyerName(), o.getBuyerEmail(),
                o.getEvent().getTitle(), items, Format.iqd(o.getTotalIqd()),
                o.getPaymentMethod() == Order.PaymentMethod.FREE
                        ? msg("order.method.free") : msg("order.method.direct"),
                statusLabel(o.getStatus().name()),
                o.getStatus().name(),
                o.getStatus() == Order.Status.PENDING_CONFIRMATION,
                Format.cardDateLine(o.getCreatedAt()),
                o.getReceiptPath() != null,
                o.getTransferReference());
    }

    private AttendeeRow toRow(Ticket t, java.util.Map<String, String> avatars) {
        String email = t.getHolderEmail() != null ? t.getHolderEmail() : t.getOrder().getBuyerEmail();
        return new AttendeeRow(t.getId(), t.getHolderName(), t.getTicketType().getName(),
                t.getOrder().getOrderCode(), t.getCode(),
                t.getStatus() == Ticket.Status.CHECKED_IN,
                t.getCheckedInAt() == null ? null : Format.cardDateLine(t.getCheckedInAt()),
                email == null ? null : avatars.get(email.trim().toLowerCase()));
    }

    private String statusLabel(String name) {
        return switch (name) {
            case "PENDING_CONFIRMATION" -> msg("status.order.pending");
            case "CONFIRMED" -> msg("status.order.confirmed");
            case "REJECTED" -> msg("status.order.rejected");
            case "REFUNDED" -> msg("status.order.refunded");
            case "CANCELLED" -> msg("status.order.cancelled");
            case "DRAFT" -> msg("status.event.draft");
            case "LIVE" -> msg("status.event.live");
            case "ENDED" -> msg("status.event.ended");
            case "ON_SALE" -> msg("status.ticketType.onSale");
            case "SOLD_OUT" -> msg("status.ticketType.soldOut");
            case "HIDDEN" -> msg("status.ticketType.hidden");
            default -> name;
        };
    }
}
