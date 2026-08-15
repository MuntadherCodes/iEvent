package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final TicketTypeRepository ticketTypes;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TeamService teamService;

    public HostService(OrganizationRepository organizations,
                       EventRepository events,
                       TicketTypeRepository ticketTypes,
                       UserRepository users,
                       JdbcTemplate jdbc,
                       TeamService teamService) {
        this.organizations = organizations;
        this.events = events;
        this.ticketTypes = ticketTypes;
        this.users = users;
        this.jdbc = jdbc;
        this.teamService = teamService;
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
        e.setCoverTheme(Format.coverTheme(category));
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
        return jdbc.query("""
                SELECT d::date AS day, COALESCE(SUM(o.total_iqd), 0) AS amount
                FROM generate_series(now() - interval '29 days', now(), interval '1 day') d
                LEFT JOIN orders o ON o.created_at::date = d::date AND o.status = 'CONFIRMED'
                    AND o.event_id IN (SELECT id FROM events WHERE organization_id = ?)
                GROUP BY d::date ORDER BY d::date
                """,
                (rs, i) -> new DayPoint(
                        rs.getDate(1).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)),
                        rs.getLong(2)),
                orgId);
    }

    /** Per-event earnings: gross (confirmed subtotals - discounts), booking fees collected, net. */
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
                         WHERE o.event_id = e.id AND o.status = 'CONFIRMED') AS fees
                FROM events e
                WHERE e.organization_id = ?
                ORDER BY gross DESC, e.starts_at DESC
                """,
                (rs, i) -> new EarningsRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        Format.iqd(rs.getLong(4)), Format.iqd(rs.getLong(5)),
                        Format.iqd(Math.max(0, rs.getLong(4)))),
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

    @Transactional
    public void updateOrganizationProfile(Organization org, String name, String city, String bio) {
        org.setName(name.trim());
        org.setCity(city == null || city.isBlank() ? null : city);
        org.setBio(bio == null || bio.isBlank() ? null : bio.strip());
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
