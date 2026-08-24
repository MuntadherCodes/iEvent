package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.EventImage;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.EventImageRepository;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
    private final EventImageRepository eventImages;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TeamService teamService;
    private final MailService mail;
    private final NotificationService notifications;
    private final MessageSource messages;
    private final String baseUrl;
    private final java.nio.file.Path uploadDir;

    public static final java.util.List<String> COVER_THEMES = java.util.List.of(
            "music", "tech", "business", "arts", "food",
            "sports", "community", "education", "film", "family");

    public HostService(OrganizationRepository organizations,
                       EventRepository events,
                       TicketTypeRepository ticketTypes,
                       EventImageRepository eventImages,
                       UserRepository users,
                       JdbcTemplate jdbc,
                       TeamService teamService,
                       MailService mail,
                       NotificationService notifications,
                       MessageSource messages,
                       @Value("${app.base-url}") String baseUrl,
                       @Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.organizations = organizations;
        this.events = events;
        this.ticketTypes = ticketTypes;
        this.eventImages = eventImages;
        this.users = users;
        this.jdbc = jdbc;
        this.teamService = teamService;
        this.mail = mail;
        this.notifications = notifications;
        this.messages = messages;
        this.baseUrl = baseUrl;
        this.uploadDir = java.nio.file.Path.of(uploadDir);
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /** Localized message in an explicit recipient locale (buyer-directed texts). */
    private String msgFor(java.util.Locale locale, String code, Object... args) {
        return messages.getMessage(code, args, locale);
    }

    /** Stores/replaces the event cover image. Returns an error message or null. */
    @Transactional
    public String storeCover(Event event, MultipartFile cover) {
        if (cover == null || cover.isEmpty()) return null;
        if (cover.getSize() > 3 * 1024 * 1024) return msg("host.cover.tooLarge");
        String original = cover.getOriginalFilename() == null ? "" : cover.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
            return msg("host.cover.badType");
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
            return msg("host.cover.storeFailed");
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

    public record GalleryImageForm(String url, String creditName, String creditUrl, int focusY) {}

    /** Applies the wizard's picked stock photos (Pexels). If the event has no
     *  uploaded file (coverImagePath), the first pick becomes the primary cover
     *  (coverImageUrl, with its focusY carried onto the event's own
     *  coverFocusY) and the rest are the extra gallery, each keeping its own
     *  focusY; if a file WAS uploaded, that file stays primary and every pick
     *  becomes gallery — either way, 2+ images total is what makes the public
     *  page a slider. An empty list clears both, matching "the user removed
     *  all their picks". */
    @Transactional
    public void replaceGalleryImages(Event event, List<GalleryImageForm> picks) {
        eventImages.deleteByEventId(event.getId());
        List<GalleryImageForm> valid = picks == null ? List.of()
                : picks.stream().filter(p -> p.url() != null && !p.url().isBlank()).toList();
        int start = 0;
        if (event.getCoverImagePath() == null && !valid.isEmpty()) {
            GalleryImageForm primary = valid.get(0);
            event.setCoverImageUrl(primary.url());
            event.setCoverImageCreditName(primary.creditName());
            event.setCoverImageCreditUrl(primary.creditUrl());
            event.setCoverFocusY(Math.max(0, Math.min(100, primary.focusY())));
            start = 1;
        } else {
            event.setCoverImageUrl(null);
            event.setCoverImageCreditName(null);
            event.setCoverImageCreditUrl(null);
        }
        events.save(event);
        int order = 0;
        for (int i = start; i < valid.size(); i++) {
            GalleryImageForm p = valid.get(i);
            EventImage img = new EventImage();
            img.setEvent(event);
            img.setUrl(p.url());
            img.setCreditName(p.creditName());
            img.setCreditUrl(p.creditUrl());
            img.setSortOrder(order++);
            img.setFocusY(Math.max(0, Math.min(100, p.focusY())));
            eventImages.save(img);
        }
    }

    /** Every image on the event's public page, primary first, with attribution —
     *  0 items means the gradient/theme fallback, 1 means a static cover, 2+
     *  means a slider. */
    public record GalleryImage(String url, String creditName, String creditUrl) {}

    @Transactional(readOnly = true)
    public List<GalleryImage> gallery(Event event) {
        List<GalleryImage> out = new java.util.ArrayList<>();
        if (event.getCoverImagePath() != null) {
            out.add(new GalleryImage("/media/event-cover/" + event.getId(), null, null));
        } else if (event.getCoverImageUrl() != null) {
            out.add(new GalleryImage(event.getCoverImageUrl(),
                    event.getCoverImageCreditName(), event.getCoverImageCreditUrl()));
        }
        eventImages.findByEventIdOrderBySortOrderAsc(event.getId())
                .forEach(img -> out.add(new GalleryImage(img.getUrl(), img.getCreditName(), img.getCreditUrl())));
        return out;
    }

    /** What the picker widget itself manages, as picks it can re-render and
     *  resubmit unchanged — used to pre-populate the edit page so a save that
     *  never touches the picker doesn't wipe the existing gallery. Unlike
     *  {@link #gallery}, this excludes an uploaded-file primary: that slot is
     *  managed by the separate upload input / "remove cover" checkbox, not
     *  the picker, so showing it there too would let removing it via the
     *  picker silently disagree with what "remove cover" actually does. */
    @Transactional(readOnly = true)
    public List<GalleryImageForm> currentGalleryPicks(Event event) {
        List<GalleryImageForm> out = new java.util.ArrayList<>();
        if (event.getCoverImagePath() == null && event.getCoverImageUrl() != null) {
            out.add(new GalleryImageForm(event.getCoverImageUrl(),
                    event.getCoverImageCreditName(), event.getCoverImageCreditUrl(), event.getCoverFocusY()));
        }
        eventImages.findByEventIdOrderBySortOrderAsc(event.getId())
                .forEach(img -> out.add(new GalleryImageForm(img.getUrl(), img.getCreditName(), img.getCreditUrl(), img.getFocusY())));
        return out;
    }

    @Transactional
    public void applyCoverTheme(Event event, String theme) {
        if (theme != null && COVER_THEMES.contains(theme)) {
            event.setCoverTheme(theme);
            events.save(event);
        }
    }

    /** Sets the cover crop focus regardless of which cover source (upload,
     *  Pexels, or existing photo) is active — storeCover() only saves on a
     *  new file, so this can't just piggyback on that call. */
    @Transactional
    public void setCoverFocusY(Event event, int focusY) {
        event.setCoverFocusY(Math.max(0, Math.min(100, focusY)));
        events.save(event);
    }

    /** Payment method ids explicitly selected for this event, or empty when
     *  the event just uses every enabled org method (the default). */
    @Transactional(readOnly = true)
    public List<Long> selectedPaymentMethodIds(Long eventId) {
        return jdbc.queryForList(
                "SELECT payment_method_id FROM event_payment_methods WHERE event_id = ?",
                Long.class, eventId);
    }

    /** Replaces the event's payment-method selection. An empty/null list
     *  clears it back to the default (every enabled org method, dynamically —
     *  including ones added later). */
    @Transactional
    public void syncEventPaymentMethods(Long eventId, List<Long> methodIds) {
        jdbc.update("DELETE FROM event_payment_methods WHERE event_id = ?", eventId);
        if (methodIds == null || methodIds.isEmpty()) return;
        for (Long id : methodIds) {
            jdbc.update("INSERT INTO event_payment_methods (event_id, payment_method_id) VALUES (?, ?)",
                    eventId, id);
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
        e.setDescription(RichText.forStorage(description));
        e.setStatus(Event.Status.DRAFT);
        e.setCoverTheme(Format.coverTheme(category));
        e = events.save(e);
        replaceTicketTypes(e, ticketForms);
        return e;
    }

    /** Replaces every ticket type on the event with the submitted set. Draft-only by
     *  contract of its callers (the create wizard's own draft, or a wizard-autosaved
     *  draft being finalized) — a ticket type that already has real order_items would
     *  reject the delete via its FK, which is the intended safety net since a LIVE
     *  event's ticket types must go through the per-row edit endpoint instead. */
    @Transactional
    public void replaceTicketTypes(Event e, List<TicketTypeForm> ticketForms) {
        ticketTypes.deleteByEventId(e.getId());
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
        e.setDescription(RichText.forStorage(description));
        events.save(e);
        jdbc.update("UPDATE events SET updated_at = now() WHERE id = ?", e.getId());
    }

    @Transactional
    public String upsertTicketType(Event event, Long ttId, String name, long priceIqd,
                                   int quantity, String status) {
        if (name == null || name.isBlank()) return msg("host.ticket.nameRequired");
        TicketType tt;
        if (ttId == null) {
            tt = new TicketType();
            tt.setEvent(event);
            tt.setSortOrder((int) ticketTypes.findByEventIdOrderBySortOrderAsc(event.getId()).size());
        } else {
            tt = ticketTypes.findById(ttId)
                    .filter(x -> x.getEvent().getId().equals(event.getId()))
                    .orElse(null);
            if (tt == null) return msg("host.ticket.unknown");
            if (quantity < tt.getSold()) {
                return msg("host.ticket.belowSold", String.valueOf(tt.getSold()));
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
        Locale chartLocale = "en".equals(LocaleContextHolder.getLocale().getLanguage())
                ? Locale.ENGLISH : new Locale("ar");
        return jdbc.query("""
                SELECT d::date AS day, COALESCE(SUM(o.total_iqd), 0) AS amount
                FROM generate_series(now() - make_interval(days => ?), now(), interval '1 day') d
                LEFT JOIN orders o ON o.created_at::date = d::date AND o.status = 'CONFIRMED'
                    AND o.event_id IN (SELECT id FROM events WHERE organization_id = ?)
                GROUP BY d::date ORDER BY d::date
                """,
                (rs, i) -> new DayPoint(
                        rs.getDate(1).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", chartLocale)),
                        rs.getLong(2)),
                d - 1, orgId);
    }

    /** Per-event earnings. Fee handling depends on events.fee_mode:
     *  PASS   — buyers paid the booking fee on top; host nets the full gross.
     *  ABSORB — host swallows the booking fee per confirmed paid ticket; net = gross − absorbed.
     *  The CASE below mirrors Format.bookingFeeFor exactly (750 flat at/under 15,000 IQD,
     *  else 3% + 2,000 capped at 15,000) — an aggregate SQL sum can't call that Java method
     *  directly, so if the fee structure ever changes, update both places. */
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
                       (SELECT CASE WHEN ? THEN 0 ELSE COALESCE(SUM(oi.quantity *
                                CASE WHEN oi.unit_price_iqd <= 15000 THEN 750
                                     ELSE LEAST(15000, ROUND(oi.unit_price_iqd * 0.03) + 2000) END), 0) END
                          FROM order_items oi JOIN orders o ON o.id = oi.order_id
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED'
                           AND oi.unit_price_iqd > 0
                           AND o.booking_fee_iqd = 0) AS absorbed_fee
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
                    // While the platform fee is waived (Format.BOOKING_FEE_WAIVED),
                    // EVERY paid order has booking_fee_iqd = 0 regardless of
                    // fee_mode, so that signal alone can't distinguish "absorbed"
                    // from "nothing was ever charged" — the query short-circuits
                    // absorbed to 0 in that case instead of guessing.
                    long absorbed = rs.getLong(6);
                    long net = Math.max(0, gross - absorbed);
                    return new EarningsRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                            Format.iqd(gross), Format.iqd(buyerFees + absorbed), Format.iqd(net));
                },
                Format.BOOKING_FEE_WAIVED, orgId);
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

    /** Draft-only deletion. Ticket types cascade in the schema; orders/tickets don't,
     *  so a draft that somehow already has real orders (published, then unpublished
     *  back to draft) throws DataIntegrityViolationException instead of silently
     *  destroying revenue history — the caller turns that into a friendly message. */
    @Transactional
    public void deleteEvent(Event event) {
        events.delete(event);
    }

    /** Cancels the event and emails every confirmed/pending buyer. */
    @Transactional
    public void cancelEvent(Event event) {
        event.setStatus(Event.Status.CANCELLED);
        events.save(event);
        notifyBuyers(event, locale -> new String[] {
                msgFor(locale, "mail.eventCancelled.subject", event.getTitle()),
                msgFor(locale, "mail.eventCancelled.body", event.getTitle())});
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
        notifyBuyers(event, locale -> {
            // Format.* reads LocaleContextHolder — pin it so the recipient's date
            // line localizes even though we're still on the actor's thread.
            java.util.Locale previous = LocaleContextHolder.getLocale();
            LocaleContextHolder.setLocale(locale);
            try {
                return new String[] {
                        msgFor(locale, "mail.eventPostponed.subject", event.getTitle()),
                        msgFor(locale, "mail.eventPostponed.body", event.getTitle(),
                                Format.longDateLine(event.getStartsAt(), event.getEndsAt()))};
            } finally {
                LocaleContextHolder.setLocale(previous);
            }
        });
    }

    /** One (userId, email, preferredLang) row per affected buyer. */
    private record BuyerRecipient(Long userId, String email, String lang) {}

    /**
     * Emails + notifies every confirmed/pending buyer, each in THEIR preferred
     * language (users.preferred_lang; null → ar) — not the acting host's locale.
     * The texts function renders {subject, body} for a given locale; only the
     * two site locales exist, so both variants are built once up front.
     */
    private void notifyBuyers(Event event,
                              java.util.function.Function<java.util.Locale, String[]> texts) {
        List<BuyerRecipient> recipients = jdbc.query("""
                SELECT DISTINCT o.buyer_user_id, o.buyer_email, u.preferred_lang
                FROM orders o
                LEFT JOIN users u ON u.id = o.buyer_user_id
                WHERE o.event_id = ? AND o.status IN ('CONFIRMED', 'PENDING_CONFIRMATION')
                """,
                (rs, i) -> new BuyerRecipient(rs.getObject(1, Long.class),
                        rs.getString(2), rs.getString(3)),
                event.getId());
        String url = baseUrl + "/e/" + event.getSlug();
        String type = event.getStatus() == Event.Status.CANCELLED ? "EVENT_CANCELLED" : "EVENT_POSTPONED";
        final java.util.Locale en = java.util.Locale.ENGLISH;
        final java.util.Locale ar = new java.util.Locale("ar");
        final String[] enTexts = texts.apply(en);
        final String[] arTexts = texts.apply(ar);
        // The DISTINCT triple can repeat an email (two accounts) or a user id
        // (two buyer emails) — send each email and each notification only once.
        java.util.Set<String> mailedEmails = new java.util.HashSet<>();
        java.util.Set<Long> notifiedUsers = new java.util.HashSet<>();
        for (BuyerRecipient r : recipients) {
            boolean english = "en".equals(r.lang());
            String subject = english ? enTexts[0] : arTexts[0];
            String body = english ? enTexts[1] : arTexts[1];
            if (r.email() != null && mailedEmails.add(r.email())) {
                mail.sendCampaign(r.email(), subject, body, event.getTitle(), url,
                        english ? en : ar);
            }
            if (r.userId() != null && notifiedUsers.add(r.userId())) {
                notifications.notify(r.userId(), type, subject,
                        body.length() > 380 ? body.substring(0, 380) : body,
                        "/e/" + event.getSlug());
            }
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
        copy.setAnnounceOnly(source.isAnnounceOnly());
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

    private String deltaLabel(long now, long prev) {
        if (prev == 0) return now == 0 ? "—" : msg("host.delta.new");
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

    /** True when buyers can actually pay this org at checkout: direct payments
     *  enabled AND at least one enabled method (or the legacy card fields). */
    @Transactional(readOnly = true)
    public boolean paymentsReady(Organization org) {
        if (!org.isDirectPaymentsEnabled()) return false;
        Long m = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_methods WHERE organization_id = ? AND enabled = TRUE",
                Long.class, org.getId());
        return nz(m) > 0 || org.getPayCardNumber() != null;
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
            if (logo.getSize() > 1024 * 1024) return msg("host.logo.tooLarge");
            String original = logo.getOriginalFilename() == null ? "" : logo.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
                return msg("host.logo.badType");
            }
            try {
                java.nio.file.Path dir = uploadDir.resolve("logos");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve("org-" + org.getId() + "." + ext);
                java.nio.file.Files.copy(logo.getInputStream(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                org.setLogoPath(target.toString());
            } catch (java.io.IOException e) {
                return msg("host.logo.storeFailed");
            }
        }
        if (cover != null && !cover.isEmpty()) {
            if (cover.getSize() > 3 * 1024 * 1024) return msg("host.cover.tooLarge");
            String original = cover.getOriginalFilename() == null ? "" : cover.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
                return msg("host.cover.badType");
            }
            try {
                java.nio.file.Path dir = uploadDir.resolve("org-covers");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve("org-" + org.getId() + "." + ext);
                java.nio.file.Files.copy(cover.getInputStream(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                org.setCoverImagePath(target.toString());
            } catch (java.io.IOException e) {
                return msg("host.cover.storeFailed");
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
