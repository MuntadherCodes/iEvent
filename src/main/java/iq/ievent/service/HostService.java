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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HostService.class);

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
    private final GoogleTranslateService translator;
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
                       GoogleTranslateService translator,
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
        this.translator = translator;
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

    /** The URL the legacy single-file cover (events.cover_image_path) is served
     *  from — inside the unified gallery model it's treated as just another
     *  pick with this URL, so it can be reordered, demoted or removed like
     *  any Pexels pick or uploaded extra. */
    public static String fileCoverUrl(Event event) {
        return "/media/event-cover/" + event.getId();
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

    /** Replaces the event's whole gallery with {@code picks} — ONE ordered
     *  list where position 0 IS the cover, exactly as the host arranged it in
     *  the picker (uploads, Pexels picks and the legacy single-file cover are
     *  all just entries here; a legacy file cover appears as its
     *  {@link #fileCoverUrl} URL). Duplicate URLs are collapsed (keeping the
     *  first occurrence) so a save can never multiply images — this also
     *  self-heals galleries that the old carry-forward logic had duplicated,
     *  on their next save.
     *
     *  Cover resolution: picks[0] = the file-cover URL keeps the uploaded
     *  file primary (coverImageUrl cleared); anything else becomes
     *  coverImageUrl — which now WINS over an existing file (see
     *  Format.coverUrl), the file simply riding along as a gallery row at
     *  whatever position the host gave it. When {@code managed} is true (the
     *  picker rendered and submitted its state) and the file-cover URL is
     *  absent from picks, the host removed that photo: the file is deleted
     *  and cover_image_path cleared. managed=false (no picker fields in the
     *  request at all — JS failure or a legacy client) never deletes the
     *  file. */
    @Transactional
    public void replaceGalleryImages(Event event, List<GalleryImageForm> picks, boolean managed) {
        boolean anyPick = picks != null && picks.stream()
                .anyMatch(p -> p.url() != null && !p.url().isBlank());
        if (!managed && !anyPick) {
            // The request carried no picker state at all (JS failure, a raw
            // POST, or an old client) — leave the saved gallery untouched
            // rather than interpreting silence as "delete everything".
            return;
        }
        eventImages.deleteByEventId(event.getId());
        String fileUrl = fileCoverUrl(event);
        List<GalleryImageForm> valid = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GalleryImageForm p : picks == null ? List.<GalleryImageForm>of() : picks) {
            if (p.url() == null || p.url().isBlank()) continue;
            // stale reference to a file cover that no longer exists
            if (p.url().equals(fileUrl) && event.getCoverImagePath() == null) continue;
            if (seen.add(p.url())) valid.add(p);
        }
        if (managed && event.getCoverImagePath() != null && !seen.contains(fileUrl)) {
            try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(event.getCoverImagePath())); }
            catch (Exception ignored) { }
            event.setCoverImagePath(null);
        }
        if (valid.isEmpty()) {
            event.setCoverImageUrl(null);
            event.setCoverImageCreditName(null);
            event.setCoverImageCreditUrl(null);
            events.save(event);
            cleanupOrphanExtraFiles(event, seen);
            return;
        }
        GalleryImageForm primary = valid.get(0);
        if (primary.url().equals(fileUrl)) {
            event.setCoverImageUrl(null);
            event.setCoverImageCreditName(null);
            event.setCoverImageCreditUrl(null);
        } else {
            event.setCoverImageUrl(primary.url());
            event.setCoverImageCreditName(primary.creditName());
            event.setCoverImageCreditUrl(primary.creditUrl());
        }
        event.setCoverFocusY(Math.max(0, Math.min(100, primary.focusY())));
        events.save(event);
        int order = 0;
        for (int i = 1; i < valid.size(); i++) {
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
        cleanupOrphanExtraFiles(event, seen);
    }

    /** Deletes uploaded gallery files (event-{id}-extra-*) no longer referenced
     *  by any pick — the disk-side half of removing an uploaded photo from the
     *  gallery. Never touches other events' files or the legacy single cover
     *  (event-{id}.{ext}). */
    private void cleanupOrphanExtraFiles(Event event, java.util.Set<String> keptUrls) {
        java.nio.file.Path dir = uploadDir.resolve("covers");
        if (!java.nio.file.Files.isDirectory(dir)) return;
        String prefix = "event-" + event.getId() + "-extra-";
        String urlPrefix = fileCoverUrl(event) + "/extra/";
        try (var stream = java.nio.file.Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                  .forEach(p -> {
                      String name = p.getFileName().toString();
                      String token = name.substring(prefix.length());
                      int dot = token.lastIndexOf('.');
                      if (dot > 0) token = token.substring(0, dot);
                      if (!keptUrls.contains(urlPrefix + token)) {
                          try { java.nio.file.Files.deleteIfExists(p); }
                          catch (java.io.IOException ignored) { }
                      }
                  });
        } catch (java.io.IOException ignored) { }
    }

    /** Stores photos uploaded from the host's desktop as gallery files with
     *  UNIQUE token names (event-{id}-extra-{token}.{ext}) — adding photos
     *  never renames or wipes previously saved ones, so URLs the picker
     *  resubmits stay valid; files that are no longer referenced are removed
     *  by cleanupOrphanExtraFiles() after the gallery is replaced. Returns a
     *  list INDEX-ALIGNED with {@code files}: the served URL for each stored
     *  file, or null for an empty/oversize/wrong-type entry — alignment is
     *  what lets the picker's "upload:{k}" placeholders resolve to the right
     *  file even when some were rejected. */
    @Transactional
    public List<String> storeGalleryUploads(Event event, List<MultipartFile> files) {
        List<String> out = new java.util.ArrayList<>();
        if (files == null) return out;
        java.nio.file.Path dir = uploadDir.resolve("covers");
        // millis + a random component: two concurrent saves for the same event
        // in the same millisecond must not mint colliding filenames
        String base = Long.toString(System.currentTimeMillis(), 36)
                + Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 36 * 36 * 36), 36);
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty() || file.getSize() > 3 * 1024 * 1024) { out.add(null); continue; }
            String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!java.util.Set.of("jpg", "jpeg", "png", "webp").contains(ext)) { out.add(null); continue; }
            try {
                java.nio.file.Files.createDirectories(dir);
                String token = base + "x" + i;
                java.nio.file.Path target = dir.resolve("event-" + event.getId() + "-extra-" + token + "." + ext);
                java.nio.file.Files.copy(file.getInputStream(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                out.add(fileCoverUrl(event) + "/extra/" + token);
            } catch (java.io.IOException e) {
                out.add(null);
            }
        }
        return out;
    }

    /** Every image on the event's public page, primary first, with attribution —
     *  0 items means the gradient/theme fallback, 1 means a static cover, 2+
     *  means a slider. */
    public record GalleryImage(String url, String creditName, String creditUrl) {}

    @Transactional(readOnly = true)
    public List<GalleryImage> gallery(Event event) {
        List<GalleryImage> out = new java.util.ArrayList<>();
        // coverImageUrl is the host's CHOSEN primary (see replaceGalleryImages)
        // — when set it wins even if a legacy uploaded file also exists (that
        // file then shows up as one of the ordered gallery rows below).
        if (event.getCoverImageUrl() != null) {
            out.add(new GalleryImage(event.getCoverImageUrl(),
                    event.getCoverImageCreditName(), event.getCoverImageCreditUrl()));
        } else if (event.getCoverImagePath() != null) {
            out.add(new GalleryImage(fileCoverUrl(event), null, null));
        }
        eventImages.findByEventIdOrderBySortOrderAsc(event.getId())
                .forEach(img -> out.add(new GalleryImage(img.getUrl(), img.getCreditName(), img.getCreditUrl())));
        return out;
    }

    /** The picker widget's full state, in display order (position 0 = cover)
     *  — used to pre-populate the edit page so a save that never touches the
     *  widget resubmits the event's gallery unchanged, and so the legacy
     *  uploaded-file cover is manageable (reorder/remove/re-crop) exactly
     *  like any other image. */
    @Transactional(readOnly = true)
    public List<GalleryImageForm> currentGalleryPicks(Event event) {
        List<GalleryImageForm> out = new java.util.ArrayList<>();
        if (event.getCoverImageUrl() != null) {
            out.add(new GalleryImageForm(event.getCoverImageUrl(),
                    event.getCoverImageCreditName(), event.getCoverImageCreditUrl(), event.getCoverFocusY()));
        } else if (event.getCoverImagePath() != null) {
            out.add(new GalleryImageForm(fileCoverUrl(event), null, null, event.getCoverFocusY()));
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

    /** Server-side clamp for the wizard/edit category selection: 1..3 valid,
     *  de-duplicated categories in the order the host picked them; falls back
     *  to COMMUNITY when nothing valid was submitted. First = primary. */
    public static List<Event.Category> cleanCategories(List<String> submitted) {
        java.util.LinkedHashSet<Event.Category> out = new java.util.LinkedHashSet<>();
        if (submitted != null) {
            for (String s : submitted) {
                if (s == null || s.isBlank()) continue;
                try { out.add(Event.Category.valueOf(s.trim().toUpperCase(Locale.ENGLISH))); }
                catch (IllegalArgumentException ignored) { }
                if (out.size() == 3) break;
            }
        }
        if (out.isEmpty()) out.add(Event.Category.COMMUNITY);
        return List.copyOf(out);
    }

    /** Replaces the event's full ordered category set (see V24). The caller
     *  keeps events.category = list.get(0) — that stays the primary. */
    @Transactional
    public void syncEventCategories(Long eventId, List<Event.Category> categories) {
        jdbc.update("DELETE FROM event_categories WHERE event_id = ?", eventId);
        int order = 0;
        for (Event.Category c : categories) {
            jdbc.update("INSERT INTO event_categories (event_id, category, sort_order) VALUES (?, ?, ?)",
                    eventId, c.name(), order++);
        }
    }

    /** The event's ordered categories — primary first. Falls back to the
     *  events.category column for rows that predate V24 junction rows. */
    @Transactional(readOnly = true)
    public List<Event.Category> categoriesOf(Event event) {
        List<Event.Category> out = new java.util.ArrayList<>();
        jdbc.query("SELECT category FROM event_categories WHERE event_id = ? ORDER BY sort_order",
                rs -> {
                    try { out.add(Event.Category.valueOf(rs.getString(1))); }
                    catch (IllegalArgumentException ignored) { }
                }, event.getId());
        if (out.isEmpty()) out.add(event.getCategory());
        else if (!out.get(0).equals(event.getCategory())) {
            // primary column is authoritative for position 0
            out.remove(event.getCategory());
            out.add(0, event.getCategory());
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
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
        // R27 #4: new organizers start with direct payments AND cash-on-arrival
        // switched on (both can be turned off in Settings > Payments). Direct
        // payments is the entity default; cash is a PaymentMethod row.
        jdbc.update("""
                INSERT INTO payment_methods (organization_id, label, method_type, enabled, sort_order)
                VALUES (?, 'Cash', 'CASH', TRUE, 99)
                """, org.getId());
        // sort_order 99: transfer methods the host adds later (sort = list size)
        // stay ahead of cash at checkout, so the preselected option is a transfer.
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

    /** Console events list with search + status filter ("all"/"live"/"draft"/"ended"/"cancelled")
     *  and sort ("date" = event date, the default; "created" = newest created
     *  first; "updated" = most recently modified first). */
    @Transactional(readOnly = true)
    public List<Event> eventsOf(Long orgId, String q, String status, String sort) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ENGLISH);
        Event.Status wanted = null;
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            try { wanted = Event.Status.valueOf(status.trim().toUpperCase(Locale.ENGLISH)); }
            catch (Exception ignored) { }
        }
        final Event.Status ws = wanted;
        java.util.Comparator<Event> order = switch (sort == null ? "date" : sort) {
            case "created" -> java.util.Comparator.comparing(Event::getCreatedAt).reversed();
            case "updated" -> java.util.Comparator.comparing(Event::getUpdatedAt).reversed();
            default -> java.util.Comparator.comparing(Event::getStartsAt).reversed();
        };
        return events.findByOrganizationIdOrderByStartsAtDesc(orgId).stream()
                .filter(e -> ws == null || e.getStatus() == ws)
                .filter(e -> needle == null || needle.isEmpty()
                        || e.getTitle().toLowerCase(Locale.ENGLISH).contains(needle))
                .sorted(order)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Event> eventOf(Long orgId, Long eventId) {
        return events.findById(eventId)
                .filter(e -> e.getOrganization().getId().equals(orgId));
    }

    /** One resolved "when": how precise the schedule is, plus concrete values.
     *  precision ∈ DAY|RANGE|MONTH|TBA (see Event.datePrecision). For MONTH,
     *  {@code date} is the first of the month; for TBA it's ignored entirely;
     *  {@code endDate} only matters for RANGE. */
    public record When(String precision, LocalDate date, LocalTime start, boolean hasStartTime,
                       LocalTime end, LocalDate endDate) {

        /** Classic exact-day When — used by callers that predate flexible dates
         *  (postpone, autosave-start). */
        public static When day(LocalDate date, LocalTime start, boolean hasStartTime, LocalTime end) {
            return new When(Event.PRECISION_DAY, date, start, hasStartTime, end, null);
        }
    }

    /** Writes the When onto the event: startsAt always gets a real timestamp
     *  (placeholders for MONTH/TBA — display branches on datePrecision). */
    private static void applyWhen(Event e, When w) {
        switch (w.precision()) {
            case Event.PRECISION_TBA -> {
                e.setStartsAt(Format.TBA_PLACEHOLDER);
                e.setEndsAt(null);
                e.setHasStartTime(false);
                e.setDatePrecision(Event.PRECISION_TBA);
            }
            case Event.PRECISION_MONTH -> {
                // Placeholder = LAST day of the month at noon, not the first:
                // every "upcoming" comparison in the app is starts_at vs now(),
                // so a first-of-month placeholder would make a month-only event
                // vanish from trending/related/organizer-upcoming (and move a
                // buyer's ticket to the Past tab) on day 2 of its own month.
                // Display never shows the day — only the month + year.
                e.setStartsAt(LocalDateTime.of(
                                w.date().withDayOfMonth(w.date().lengthOfMonth()), LocalTime.NOON)
                        .atZone(Format.BAGHDAD).toOffsetDateTime());
                e.setEndsAt(null);
                e.setHasStartTime(false);
                e.setDatePrecision(Event.PRECISION_MONTH);
            }
            case Event.PRECISION_RANGE -> {
                LocalDate endDate = w.endDate();
                if (endDate == null || !endDate.isAfter(w.date())) {
                    // Degenerate range (same day, or end before start): treat it
                    // as a plain exact-day event so the classic end-time logic
                    // (end before start rolls to the next morning) applies.
                    applyWhen(e, new When(Event.PRECISION_DAY, w.date(), w.start(),
                            w.hasStartTime(), w.end(), null));
                    return;
                }
                LocalTime start = w.start() == null ? LocalTime.NOON : w.start();
                e.setStartsAt(LocalDateTime.of(w.date(), start).atZone(Format.BAGHDAD).toOffsetDateTime());
                // No explicit end time ⇒ the event runs to the END of its last
                // day — the sweeper (and the .ics DTEND) read ends_at directly,
                // so a fabricated "start time on the last day" would end a
                // festival the moment its final evening begins.
                e.setEndsAt(LocalDateTime.of(endDate, w.end() == null ? LocalTime.of(23, 59) : w.end())
                        .atZone(Format.BAGHDAD).toOffsetDateTime());
                e.setHasStartTime(w.hasStartTime());
                e.setDatePrecision(Event.PRECISION_RANGE);
            }
            default -> {
                LocalTime start = w.start() == null ? LocalTime.NOON : w.start();
                e.setStartsAt(LocalDateTime.of(w.date(), start).atZone(Format.BAGHDAD).toOffsetDateTime());
                e.setHasStartTime(w.hasStartTime());
                e.setEndsAt(w.end() == null ? null
                        : LocalDateTime.of(w.end().isBefore(start) ? w.date().plusDays(1) : w.date(), w.end())
                                .atZone(Format.BAGHDAD).toOffsetDateTime());
                e.setDatePrecision(Event.PRECISION_DAY);
            }
        }
    }

    @Transactional
    public Event createEvent(Organization org, String title, Event.Category category, String city,
                             String venueName, String venueAddress, When when,
                             String description, List<TicketTypeForm> ticketForms) {
        Event e = new Event();
        e.setOrganization(org);
        e.setTitle(title.trim());
        e.setSlug(uniqueSlug(title));
        // Detected from the title's own script, not the host's UI locale —
        // a host browsing in English can still type an Arabic title (or vice
        // versa), and the actual text is what auto-translation (see
        // translateEventContent) needs to get the direction right.
        e.setLanguage(containsArabic(title) ? "ar" : "en");
        e.setCategory(category);
        e.setCity(city);
        e.setVenueName(venueName);
        e.setVenueAddress(venueAddress);
        applyWhen(e, when);
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
                            String venueName, String venueAddress, When when, String description) {
        e.setTitle(title.trim());
        e.setCategory(category);
        e.setCity(city);
        e.setVenueName(venueName);
        e.setVenueAddress(venueAddress);
        applyWhen(e, when);
        // Mirror of postponeEvent's revive: once the sweeper auto-ENDs an
        // event, fixing its date through the ordinary edit form (the only
        // place a date can become TBA/month-only) must bring it back too —
        // otherwise the save "succeeds" while the event silently stays off
        // the public site.
        if (e.getStatus() == Event.Status.ENDED
                && e.getStartsAt().isAfter(OffsetDateTime.now())) {
            e.setStatus(Event.Status.LIVE);
        }
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

    /** Why this event cannot go live yet (a message key), or null when it can.
     *  A listing that sells tickets needs at least one ticket type, otherwise the
     *  public page shows an empty ticket box and checkout has nothing to pick. */
    public String publishBlocker(Event event) {
        if (event.isAnnounceOnly()) return null;
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ticket_types WHERE event_id = ?",
                Long.class, event.getId());
        return n == null || n == 0 ? "flash.event.publishNoTickets" : null;
    }

    @Transactional
    public void publish(Event event) {
        event.setStatus(Event.Status.LIVE);
        // A host who's already generated (or hand-edited) a translation owns
        // it from then on — publish() only fills it in the first time, so a
        // routine unpublish/republish can never silently clobber their edit.
        // A visible "Regenerate" button (see regenerateTranslation) is the
        // only thing that overwrites an existing translation, and only on
        // the host's explicit click.
        if (event.getTitleTranslated() == null) translateEventContent(event);
        events.save(event);
    }

    public enum TranslateResult { NOT_CONFIGURED, FAILED, OK }

    /** Host-triggered "Generate/Refresh translation" — always overwrites
     *  whatever translated text is already there, since the host clicked the
     *  button on purpose. Works on a draft or a live event alike (translation
     *  isn't gated on publish status here — only publish()'s automatic
     *  first-fill is). */
    @Transactional
    public TranslateResult regenerateTranslation(Event event) {
        if (!translator.available()) return TranslateResult.NOT_CONFIGURED;
        boolean ok = translateEventContent(event);
        events.save(event);
        return ok ? TranslateResult.OK : TranslateResult.FAILED;
    }

    /** Auto-translates title/summary/description/lineup to the other language
     *  (see Event#language, #titleTranslated etc.), always overwriting any
     *  existing translated text — callers decide when that's appropriate
     *  (see publish() and regenerateTranslation() above). Silent no-op when
     *  GOOGLE_TRANSLATE_API_KEY isn't configured, or on any API failure,
     *  since the event is still perfectly displayable in its original
     *  language either way (Format.localized falls back to that). Returns
     *  whether the title — the one field guaranteed non-blank — translated
     *  successfully, as a simple proxy for "did this mostly work". */
    private boolean translateEventContent(Event event) {
        if (!translator.available()) return false;
        // Self-heals Event#language from the title's own script every time a
        // translation runs — covers rows created before this flag was ever
        // set correctly (see V22 for the one-time backfill of those), and
        // costs nothing when it already matched.
        String source = containsArabic(event.getTitle()) ? "ar" : "en";
        event.setLanguage(source);
        String target = "ar".equals(source) ? "en" : "ar";
        try {
            String titleT = translator.translate(event.getTitle(), source, target, false);
            if (titleT != null) event.setTitleTranslated(titleT);

            event.setSummaryTranslated(event.getSummary() == null || event.getSummary().isBlank() ? null
                    : translator.translate(event.getSummary(), source, target, false));

            event.setDescriptionTranslated(event.getDescription() == null || event.getDescription().isBlank() ? null
                    : translator.translate(event.getDescription(), source, target, true));

            if (event.getLineup() == null || event.getLineup().isBlank()) {
                event.setLineupTranslated(null);
            } else {
                List<String> lines = List.of(event.getLineup().split("\n"));
                List<String> linesT = translator.translateBatch(lines, source, target, false);
                event.setLineupTranslated(linesT != null && linesT.size() == lines.size()
                        ? String.join("\n", linesT) : null);
            }
            return titleT != null;
        } catch (Exception e) {
            log.warn("Event translation failed for event {}", event.getId(), e);
            return false;
        }
    }

    /** Whether {@code s} contains at least one Arabic-script letter (U+0600
     *  to U+06FF) — a simple, reliable origin-language signal on a platform
     *  that's strictly bilingual Arabic/English, unlike guessing from the
     *  host's current UI locale (see createEvent, translateEventContent). */
    private static boolean containsArabic(String s) {
        if (s == null) return false;
        return s.chars().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);
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
        // The cancellation email tells buyers their tickets are no longer valid,
        // so make that true: every ticket is voided (a QR can no longer check in)
        // and orders still awaiting the host's confirmation are closed. Confirmed
        // paid orders stay CONFIRMED so the host can still record each refund.
        jdbc.update("UPDATE tickets SET status = 'VOID' WHERE event_id = ? AND status <> 'VOID'", event.getId());
        // notify first (the recipient query reads CONFIRMED + PENDING orders), then close pending ones
        notifyBuyers(event, locale -> new String[] {
                msgFor(locale, "mail.eventCancelled.subject", event.getTitle()),
                msgFor(locale, "mail.eventCancelled.body", event.getTitle())});
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE event_id = ? AND status = 'PENDING_CONFIRMATION'",
                event.getId());
    }

    /** Moves the event to a new date/time and emails every buyer. Picking a
     *  concrete new date also resolves a previously TBA/month-only schedule to
     *  an exact day, and revives an auto-ENDED event whose new date is in the
     *  future (see EventStatusSweeper). */
    @Transactional
    public void postponeEvent(Event event, LocalDate date, LocalTime start, boolean hasStartTime, LocalTime end) {
        applyWhen(event, When.day(date, start, hasStartTime, end));
        if (event.getStatus() == Event.Status.ENDED
                && event.getStartsAt().isAfter(OffsetDateTime.now())) {
            event.setStatus(Event.Status.LIVE);
        }
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
                                Format.longDateLine(event.getStartsAt(), event.getEndsAt(),
                                        event.isHasStartTime(), event.getDatePrecision()))};
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
        // TBA/MONTH placeholders stay canonical — shifting them a week would
        // quietly move a month-only copy into the next month (or break the
        // TBA placeholder); only real dates move one week out.
        boolean placeholderDate = Event.PRECISION_TBA.equals(source.getDatePrecision())
                || Event.PRECISION_MONTH.equals(source.getDatePrecision());
        copy.setStartsAt(placeholderDate ? source.getStartsAt() : source.getStartsAt().plusDays(7));
        copy.setEndsAt(source.getEndsAt() == null ? null
                : (placeholderDate ? source.getEndsAt() : source.getEndsAt().plusDays(7)));
        copy.setHasStartTime(source.isHasStartTime());
        copy.setDatePrecision(source.getDatePrecision());
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
        syncEventCategories(copy.getId(), categoriesOf(source));
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
        // R27: cash-on-arrival is on by default, so the checklist item only counts
        // a TRANSFER method the host actually configured.
        Long methods = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_methods WHERE organization_id = ? AND enabled = TRUE AND method_type = 'TRANSFER'",
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

    /** R27 #5: organizer-level refund policy + whether event pages show it. */
    @Transactional
    public void saveRefundPolicy(Organization org, String policy, boolean visible) {
        org.setRefundPolicy("UP_TO_7_DAYS".equals(policy) || "UP_TO_48H".equals(policy) ? policy : "NO_REFUNDS");
        org.setRefundPolicyVisible(visible);
        organizations.save(org);
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
