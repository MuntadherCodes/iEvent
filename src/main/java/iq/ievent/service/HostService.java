package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class HostService {

    public record HostStats(long ticketsSold, long revenueIqd, long pendingOrders, long liveEvents) {}

    public record TicketTypeForm(String name, Long priceIqd, Integer quantity) {}

    public record DayPoint(String label, long amountIqd) {}

    public record EarningsRow(Long eventId, String title, long ticketsSold, String grossLabel,
                              String feesLabel, String netLabel) {}

    /** "+12%" style vs the previous 30 days; label is "—" when there is no prior data. */
    public record StatDeltas(String soldDelta, boolean soldUp, String revenueDelta, boolean revenueUp) {}

    /** First-steps checklist on the dashboard. */
    public record Checklist(boolean hasLiveEvent, boolean paymentsSetup,
                            boolean brandingDone, boolean teamInvited) {
        public boolean allDone() { return hasLiveEvent && paymentsSetup && brandingDone && teamInvited; }
    }

    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final TicketTypeRepository ticketTypes;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TeamService teamService;
    private final MailService mail;
    private final NotificationService notifications;
    private final String baseUrl;
    private final java.nio.file.Path uploadDir;

    public static final java.util.List<String> COVER_THEMES = java.util.List.of(
            "music", "tech", "business", "arts", "food",
            "sports", "community", "education", "film", "family");

    public HostService(OrganizationRepository organizations,
                       EventRepository events,
                       TicketTypeRepository ticketTypes,
                       UserRepository users,
                       JdbcTemplate jdbc,
                       TeamService teamService,
                       MailService mail,
                       NotificationService notifications,
                       @Value("${app.base-url}") String baseUrl,
                       @Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.organizations = organizations;
        this.events = events;
        this.ticketTypes = ticketTypes;
        this.users = users;
        this.jdbc = jdbc;
        this.teamService = teamService;
        this.mail = mail;
        this.notifications = notifications;
        this.baseUrl = baseUrl;
        this.uploadDir = java.nio.file.Path.of(uploadDir);
    }

    /** Stores/replaces the event cover image. Returns an error message or null. */
    @Transactional
    public String storeCover(Event event, MultipartFile cover) {
        if (cover == null || cover.isEmpty()) return null;
        if (cover.getSize() > 3 * 1024 * 1024) return "Cover image is too large (max 3 MB).";
        String original = cover.getOriginalFilename() == null ? "" : cover.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
            return "Cover must be a JPG, PNG or WEBP image.";
        }
        try {
            java.nio.file.Path dir = uploadDir.resolve("covers");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve("event-" + event.getId() + "." + ext);
            java.nio.file.Files.copy(cover.getInputStream(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (event.getCoverImagePath() != null && !event.getCoverImagePath().equals(target.toString())) {
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(event.getCoverImagePath())); }
                catch (Exception ignored) {}
            }
            event.setCoverImagePath(target.toString());
            events.save(event);
            return null;
        } catch (java.io.IOException e) {
            return "Could not store the cover image — try again.";
        }
    }

    @Transactional
    public void removeCover(Event event) {
        if (event.getCoverImagePath() != null) {
            try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(event.getCoverImagePath())); }
            catch (Exception ignored) {}
            event.setCoverImagePath(null);
            events.save(event);
        }
    }

    @Transactional
    public void applyCoverTheme(Event event, String theme) {
        if (theme != null && COVER_THEMES.contains(theme)) {
            event.setCoverTheme(theme);
            events.save(event);
        }
    }

    /** Organization the user can act for: as owner or as team member. */
    @Transactional(readOnly = true)
    public Optional<Organization> organizationOf(User user) {
        return teamService.accessOf(user).map(TeamService.Access::org);
    }

    @Transactional(readOnly = true)
    public Optional<TeamService.Access> accessOf(User user) {
        return teamService.accessOf(user);
    }

    @Transactional
    public Organization createOrganization(User owner, String name, String handle, String city, String bio) {
        String cleanHandle = slugify(handle == null || handle.isBlank() ? name : handle);
        if (organizations.findByHandle(cleanHandle).isPresent()) {
            cleanHandle = cleanHandle + "-" + (System.nanoTime() % 1000);
        }
        Organization org = new Organization();
        org.setOwnerUserId(owner.getId());
        org.setName(name.trim());
        org.setHandle(cleanHandle);
        org.setCity(city);
        org.setBio(bio);
        org = organizations.save(org);
        if (owner.getRole() == User.Role.USER) {
            owner.setRole(User.Role.HOST);
            users.save(owner);
        }
        return org;
    }

    @Transactional(readOnly = true)
    public HostStats stats(Long orgId) {
        Long sold = jdbc.queryForObject("""
                SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                """, Long.class, orgId);
        Long revenue = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.subtotal_iqd - o.discount_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                """, Long.class, orgId);
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'PENDING_CONFIRMATION'
                """, Long.class, orgId);
        Long live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE organization_id = ? AND status = 'LIVE'",
                Long.class, orgId);
        return new HostStats(nz(sold), nz(revenue), nz(pending), nz(live));
    }

    @Transactional(readOnly = true)
    public List<Event> eventsOf(Long orgId) {
        return events.findByOrganizationIdOrderByStartsAtDesc(orgId);
    }

    /** Console events list with search + status filter ("all"/"live"/"draft"/"ended"/"cancelled"). */
    @Transactional(readOnly = true)
    public List<Event> eventsOf(Long orgId, String q, String status) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ENGLISH);
        Event.Status wanted = null;
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            try { wanted = Event.Status.valueOf(status.trim().toUpperCase(Locale.ENGLISH)); }
            catch (Exception ignored) { }
        }
        final Event.Status ws = wanted;
        return events.findByOrganizationIdOrderByStartsAtDesc(orgId).stream()
                .filter(e -> ws == null || e.getStatus() == ws)
                .filter(e -> needle == null || needle.isEmpty()
                        || e.getTitle().toLowerCase(Locale.ENGLISH).contains(needle))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Event> eventOf(Long orgId, Long eventId) {
        return events.findById(eventId)
                .filter(e -> e.getOrganization().getId().equals(orgId));
    }

    @Transactional
    public Event createEvent(Organization org, String title, Event.Category category, String city,
                             String venueName, String venueAddress, LocalDate date, LocalTime start,
                             LocalTime end, String description, List<TicketTypeForm> ticketForms) {
        Event e = new Event();
        e.setOrganization(org);
        e.setTitle(title.trim());
        e.setSlug(uniqueSlug(title));
        e.setCategory(category);
        e.setCity(city);
        e.setVenueName(venueName);
        e.setVenueAddress(venueAddress);
        OffsetDateTime startsAt = LocalDateTime.of(date, start).atZone(Format.BAGHDAD).toOffsetDateTime();
        e.setStartsAt(startsAt);
        e.setEndsAt(end == null ? null
                : LocalDateTime.of(end.isBefore(start) ? date.plusDays(1) : date, end)
                        .atZone(Format.BAGHDAD).toOffsetDateTime());
        e.setDescription(description == null ? "" : description.strip());
        e.setStatus(Event.Status.DRAFT);
        e.setCoverTheme(Format.coverTheme(category));
        e = events.save(e);

        int order = 0;
        for (TicketTypeForm form : ticketForms) {
            if (form.name() == null || form.name().isBlank() || form.quantity() == null) continue;
            TicketType tt = new TicketType();
            tt.setEvent(e);
            tt.setName(form.name().trim());
            tt.setPriceIqd(form.priceIqd() == null ? 0 : Math.max(0, form.priceIqd()));
            tt.setQuantity(Math.max(0, form.quantity()));
            tt.setSortOrder(order++);
            tt.setStatus(TicketType.Status.ON_SALE);
            ticketTypes.save(tt);
        }
        return e;
    }

    @Transactional
    public void updateEvent(Event e, String title, Event.Category category, String city,
                            String venueName, String venueAddress, LocalDate date, LocalTime start,
                            LocalTime end, String description) {
        e.setTitle(title.trim());
        e.setCategory(category);
        e.setCity(city);
        e.setVenueName(venueName);
        e.setVenueAddress(venueAddress);
        OffsetDateTime startsAt = LocalDateTime.of(date, start).atZone(Format.BAGHDAD).toOffsetDateTime();
        e.setStartsAt(startsAt);
        e.setEndsAt(end == null ? null
                : LocalDateTime.of(end.isBefore(start) ? date.plusDays(1) : date, end)
                        .atZone(Format.BAGHDAD).toOffsetDateTime());
        e.setDescription(description == null ? "" : description.strip());
        events.save(e);
        jdbc.update("UPDATE events SET updated_at = now() WHERE id = ?", e.getId());
    }

    @Transactional
    public String upsertTicketType(Event event, Long ttId, String name, long priceIqd,
                                   int quantity, String status) {
        if (name == null || name.isBlank()) return "Ticket name is required.";
        TicketType tt;
        if (ttId == null) {
            tt = new TicketType();
            tt.setEvent(event);
            tt.setSortOrder((int) ticketTypes.findByEventIdOrderBySortOrderAsc(event.getId()).size());
        } else {
            tt = ticketTypes.findById(ttId)
                    .filter(x -> x.getEvent().getId().equals(event.getId()))
                    .orElse(null);
            if (tt == null) return "Unknown ticket type.";
            if (quantity < tt.getSold()) {
                return "Quantity cannot be below tickets already sold (" + tt.getSold() + ").";
            }
        }
        tt.setName(name.trim());
        tt.setPriceIqd(Math.max(0, priceIqd));
        tt.setQuantity(Math.max(0, quantity));
        try {
            tt.setStatus(TicketType.Status.valueOf(status));
        } catch (Exception ignored) {
            tt.setStatus(TicketType.Status.ON_SALE);
        }
        ticketTypes.save(tt);
        return null;
    }

    /** Daily gross (confirmed orders) for the last 30 days, oldest first. */
    @Transactional(readOnly = true)
    public List<DayPoint> dailySales(Long orgId) {
        return dailySales(orgId, 30);
    }

    /** Daily gross (confirmed orders) for the last {@code days} days (7/30/90), oldest first. */
    @Transactional(readOnly = true)
    public List<DayPoint> dailySales(Long orgId, int days) {
        int d = days == 7 || days == 90 ? days : 30;
        return jdbc.query("""
                SELECT d::date AS day, COALESCE(SUM(o.total_iqd), 0) AS amount
                FROM generate_series(now() - make_interval(days => ?), now(), interval '1 day') d
                LEFT JOIN orders o ON o.created_at::date = d::date AND o.status = 'CONFIRMED'
                    AND o.event_id IN (SELECT id FROM events WHERE organization_id = ?)
                GROUP BY d::date ORDER BY d::date
                """,
                (rs, i) -> new DayPoint(
                        rs.getDate(1).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)),
                        rs.getLong(2)),
                d - 1, orgId);
    }

    /** Per-event earnings. Fee handling depends on events.fee_mode:
     *  PASS   — buyers paid the booking fee on top; host nets the full gross.
     *  ABSORB — host swallows 1,500 IQD per confirmed paid ticket; net = gross − absorbed. */
    @Transactional(readOnly = true)
    public List<EarningsRow> earnings(Long orgId) {
        return jdbc.query("""
                SELECT e.id, e.title,
                       (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi
                          JOIN orders o ON o.id = oi.order_id
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED') AS sold,
                       (SELECT COALESCE(SUM(o.subtotal_iqd - o.discount_iqd), 0) FROM orders o
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED') AS gross,
                       (SELECT COALESCE(SUM(o.booking_fee_iqd), 0) FROM orders o
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED') AS buyer_fees,
                       (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi
                          JOIN orders o ON o.id = oi.order_id
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED'
                           AND oi.unit_price_iqd > 0
                           AND o.booking_fee_iqd = 0) AS absorbed_paid_sold
                FROM events e
                WHERE e.organization_id = ?
                ORDER BY gross DESC, e.starts_at DESC
                """,
                (rs, i) -> {
                    long gross = rs.getLong(4);
                    long buyerFees = rs.getLong(5);
                    // Each order self-describes the mode it was charged under:
                    // paid tickets with booking_fee_iqd = 0 were absorbed by the
                    // host — so flipping fee_mode later never rewrites history.
                    long absorbed = rs.getLong(6) * iq.ievent.service.OrderService.BOOKING_FEE_PER_PAID_TICKET;
                    long net = Math.max(0, gross - absorbed);
                    return new EarningsRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                            Format.iqd(gross), Format.iqd(buyerFees + absorbed), Format.iqd(net));
                },
                orgId);
    }

    @Transactional
    public void publish(Event event) {
        event.setStatus(Event.Status.LIVE);
        events.save(event);
    }

    @Transactional
    public void unpublish(Event event) {
        event.setStatus(Event.Status.DRAFT);
        events.save(event);
    }

    /** Cancels the event and emails every confirmed/pending buyer. */
    @Transactional
    public void cancelEvent(Event event) {
        event.setStatus(Event.Status.CANCELLED);
        events.save(event);
        notifyBuyers(event,
                "Event cancelled: " + event.getTitle(),
                "We're sorry — the organizer has cancelled " + event.getTitle() + ".\n\n"
                        + "If you paid for tickets, the organizer will arrange your refund through "
                        + "the payment method you used. Tickets for this event are no longer valid.");
    }

    /** Moves the event to a new date/time and emails every buyer. */
    @Transactional
    public void postponeEvent(Event event, LocalDate date, LocalTime start, LocalTime end) {
        OffsetDateTime startsAt = LocalDateTime.of(date, start).atZone(Format.BAGHDAD).toOffsetDateTime();
        event.setStartsAt(startsAt);
        event.setEndsAt(end == null ? null
                : LocalDateTime.of(end.isBefore(start) ? date.plusDays(1) : date, end)
                        .atZone(Format.BAGHDAD).toOffsetDateTime());
        events.save(event);
        notifyBuyers(event,
                "New date for " + event.getTitle(),
                "The organizer has moved " + event.getTitle() + " to a new date:\n\n"
                        + Format.longDateLine(event.getStartsAt(), event.getEndsAt())
                        + "\n\nYour existing tickets remain valid for the new date.");
    }

    private void notifyBuyers(Event event, String subject, String body) {
        List<String> emails = jdbc.queryForList("""
                SELECT DISTINCT o.buyer_email FROM orders o
                WHERE o.event_id = ? AND o.status IN ('CONFIRMED', 'PENDING_CONFIRMATION')
                """, String.class, event.getId());
        String url = baseUrl + "/events/" + event.getSlug();
        for (String email : emails) {
            mail.sendCampaign(email, subject, body, event.getTitle(), url);
        }
        // in-app notifications for buyers who have accounts
        List<Long> userIds = jdbc.queryForList("""
                SELECT DISTINCT o.buyer_user_id FROM orders o
                WHERE o.event_id = ? AND o.status IN ('CONFIRMED', 'PENDING_CONFIRMATION')
                """, Long.class, event.getId());
        String type = event.getStatus() == Event.Status.CANCELLED ? "EVENT_CANCELLED" : "EVENT_POSTPONED";
        for (Long uid : userIds) {
            notifications.notify(uid, type, subject,
                    body.length() > 380 ? body.substring(0, 380) : body,
                    "/events/" + event.getSlug());
        }
    }

    /** Copies an event (details, cover theme, ticket types) as a fresh DRAFT one week later. */
    @Transactional
    public Event duplicateEvent(Event source) {
        Event copy = new Event();
        copy.setOrganization(source.getOrganization());
        copy.setTitle(source.getTitle() + " (copy)");
        copy.setSlug(uniqueSlug(source.getTitle() + " copy"));
        copy.setCategory(source.getCategory());
        copy.setCity(source.getCity());
        copy.setVenueName(source.getVenueName());
        copy.setVenueAddress(source.getVenueAddress());
        copy.setStartsAt(source.getStartsAt().plusDays(7));
        copy.setEndsAt(source.getEndsAt() == null ? null : source.getEndsAt().plusDays(7));
        copy.setDescription(source.getDescription());
        copy.setSummary(source.getSummary());
        copy.setTags(source.getTags());
        copy.setLineup(source.getLineup());
        copy.setVisibility(source.getVisibility());
        copy.setRefundPolicy(source.getRefundPolicy());
        copy.setFeeMode(source.getFeeMode());
        copy.setLocationType(source.getLocationType());
        copy.setOnlineUrl(source.getOnlineUrl());
        copy.setMapsUrl(source.getMapsUrl());
        copy.setStatus(Event.Status.DRAFT);
        copy.setCoverTheme(source.getCoverTheme());
        copy = events.save(copy);
        for (TicketType tt : ticketTypes.findByEventIdOrderBySortOrderAsc(source.getId())) {
            TicketType c = new TicketType();
            c.setEvent(copy);
            c.setName(tt.getName());
            c.setPriceIqd(tt.getPriceIqd());
            c.setQuantity(tt.getQuantity());
            c.setSortOrder(tt.getSortOrder());
            c.setStatus(tt.getStatus());
            ticketTypes.save(c);
        }
        return copy;
    }

    /** Total event-page views across the organization's events. */
    @Transactional(readOnly = true)
    public long totalViews(Long orgId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(view_count), 0) FROM events WHERE organization_id = ?",
                Long.class, orgId);
        return nz(v);
    }

    /** Tickets-sold and revenue movement: last 30 days vs the 30 days before. */
    @Transactional(readOnly = true)
    public StatDeltas statDeltas(Long orgId) {
        long soldNow = period(orgId, "COALESCE(SUM(oi.quantity), 0)", 30, 0);
        long soldPrev = period(orgId, "COALESCE(SUM(oi.quantity), 0)", 60, 30);
        long revNow = periodRevenue(orgId, 30, 0);
        long revPrev = periodRevenue(orgId, 60, 30);
        return new StatDeltas(deltaLabel(soldNow, soldPrev), soldNow >= soldPrev,
                              deltaLabel(revNow, revPrev), revNow >= revPrev);
    }

    private long period(Long orgId, String agg, int fromDaysAgo, int toDaysAgo) {
        Long v = jdbc.queryForObject("""
                SELECT %s FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                  AND o.created_at >= now() - make_interval(days => ?)
                  AND o.created_at <  now() - make_interval(days => ?)
                """.formatted(agg), Long.class, orgId, fromDaysAgo, toDaysAgo);
        return nz(v);
    }

    private long periodRevenue(Long orgId, int fromDaysAgo, int toDaysAgo) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.subtotal_iqd - o.discount_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                  AND o.created_at >= now() - make_interval(days => ?)
                  AND o.created_at <  now() - make_interval(days => ?)
                """, Long.class, orgId, fromDaysAgo, toDaysAgo);
        return nz(v);
    }

    private static String deltaLabel(long now, long prev) {
        if (prev == 0) return now == 0 ? "—" : "new";
        long pct = Math.round((now - prev) * 100.0 / prev);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    /** Dashboard first-steps checklist. Payments counts as set up when the org
     *  has at least one ENABLED payment method (the multi-method table — the
     *  legacy single-method columns alone no longer count as complete). */
    @Transactional(readOnly = true)
    public Checklist checklist(Organization org) {
        Long live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE organization_id = ? AND status IN ('LIVE','ENDED')",
                Long.class, org.getId());
        Long team = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_members WHERE organization_id = ?",
                Long.class, org.getId());
        Long methods = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_methods WHERE organization_id = ? AND enabled = TRUE",
                Long.class, org.getId());
        boolean paymentsSetup = nz(methods) > 0
                || (org.isDirectPaymentsEnabled() && org.getPayCardNumber() != null);
        boolean brandingDone = org.getBio() != null
                && (org.getContactEmail() != null || org.getContactPhone() != null || org.getWebsite() != null
                    || org.getInstagram() != null || org.getLogoPath() != null);
        return new Checklist(nz(live) > 0, paymentsSetup, brandingDone, nz(team) > 0);
    }

    /** Permanently hides the dashboard first-steps checklist for this org. */
    @Transactional
    public void dismissChecklist(Organization org) {
        org.setChecklistDismissed(true);
        organizations.save(org);
    }

    @Transactional
    public void updateOrganizationProfile(Organization org, String name, String city, String bio) {
        org.setName(name.trim());
        org.setCity(city == null || city.isBlank() ? null : city);
        org.setBio(bio == null || bio.isBlank() ? null : bio.strip());
        organizations.save(org);
    }

    /** Branding + contact/socials + notification preference. Returns an error message or null. */
    @Transactional
    public String saveBranding(Organization org, String contactEmail, String contactPhone,
                               String website, String instagram, String brandColor,
                               boolean notifyPendingOrders, MultipartFile logo, MultipartFile cover) {
        org.setContactEmail(blankToNull(contactEmail));
        org.setContactPhone(blankToNull(contactPhone));
        org.setWebsite(blankToNull(website));
        String ig = blankToNull(instagram);
        org.setInstagram(ig == null ? null : ig.replaceFirst("^@", ""));
        String color = blankToNull(brandColor);
        org.setBrandColor(color != null && color.matches("#[0-9a-fA-F]{6}") ? color : null);
        org.setNotifyPendingOrders(notifyPendingOrders);
        if (logo != null && !logo.isEmpty()) {
            if (logo.getSize() > 1024 * 1024) return "Logo is too large (max 1 MB).";
            String original = logo.getOriginalFilename() == null ? "" : logo.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
                return "Logo must be a JPG, PNG or WEBP image.";
            }
            try {
                java.nio.file.Path dir = uploadDir.resolve("logos");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve("org-" + org.getId() + "." + ext);
                java.nio.file.Files.copy(logo.getInputStream(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                org.setLogoPath(target.toString());
            } catch (java.io.IOException e) {
                return "Could not store the logo — try again.";
            }
        }
        if (cover != null && !cover.isEmpty()) {
            if (cover.getSize() > 3 * 1024 * 1024) return "Cover image is too large (max 3 MB).";
            String original = cover.getOriginalFilename() == null ? "" : cover.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
                return "Cover must be a JPG, PNG or WEBP image.";
            }
            try {
                java.nio.file.Path dir = uploadDir.resolve("org-covers");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve("org-" + org.getId() + "." + ext);
                java.nio.file.Files.copy(cover.getInputStream(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                org.setCoverImagePath(target.toString());
            } catch (java.io.IOException e) {
                return "Could not store the cover image — try again.";
            }
        }
        organizations.save(org);
        return null;
    }

    @Transactional
    public void savePaymentSettings(Organization org, boolean enabled, String cardNumber,
                                    String accountName, String walletBank, String instructions) {
        org.setDirectPaymentsEnabled(enabled);
        org.setPayCardNumber(blankToNull(cardNumber));
        org.setPayAccountName(blankToNull(accountName));
        org.setPayWalletBank(blankToNull(walletBank));
        org.setPayInstructions(blankToNull(instructions));
        organizations.save(org);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String uniqueSlug(String title) {
        String base = slugify(title);
        String slug = base;
        int i = 2;
        while (events.findBySlug(slug).isPresent()) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private static String slugify(String input) {
        String n = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9\\u0600-\\u06FF]+", "-")
                .replaceAll("(^-|-$)", "");
        return n.isBlank() ? "event" : n;
    }

    private static long nz(Long v) { return v == null ? 0 : v; }
}
