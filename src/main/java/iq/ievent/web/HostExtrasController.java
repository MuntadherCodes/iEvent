package iq.ievent.web;

import iq.ievent.domain.Campaign;
import iq.ievent.domain.Event;
import iq.ievent.domain.Order;
import iq.ievent.domain.PromoCode;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.TrackingLink;
import iq.ievent.domain.User;
import iq.ievent.repo.CampaignRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.service.CampaignService;
import iq.ievent.service.Format;
import iq.ievent.service.HostService;
import iq.ievent.service.MailService;
import iq.ievent.service.OrderService;
import iq.ievent.service.PromoService;
import iq.ievent.service.TeamService;
import iq.ievent.service.TrackingService;
import iq.ievent.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Host operations beyond the core CRUD in HostController: enriched attendees
 * and orders views (filters, stats, CSV export, refunds/resends), marketing
 * (promo codes, email campaigns, tracking links, social share), earnings
 * summary, organization settings (profile, branding, notifications) and team.
 *
 * Route sharing with HostController: this controller takes over
 * GET /host/attendees when the {@code event} param is present and
 * GET /host/orders when the {@code f} (filter) param is present — Spring
 * routes to the mapping with the more specific params condition. The
 * templates tolerate both model shapes.
 */
@Controller
@RequestMapping("/host")
public class HostExtrasController {

    public record PromoView(Long id, String code, String kindLabel, String scopeLabel,
                            String usesLabel, boolean active, String expiresLabel) {}

    public record IdTitle(Long id, String title, String slug) {}

    /** Attendee row for the enriched attendees table. */
    public record ARow(Long ticketId, String holderName, String initial, String typeName,
                       String orderCode, String code, String email, String purchasedLine,
                       String status, boolean checkedIn, boolean voided, String checkedInLine,
                       Long orderId, boolean resendable) {}

    /** Stats strip + progress ring for the attendees page. Pct is 0–100. */
    public record AStats(long total, long checkedIn, long remaining, long voided,
                         int pct, String dashOffset) {}

    /** "Tickets by type" bar in the attendance insights panel. */
    public record TypeCount(String name, long count, int pct) {}

    /** Summary cards on the orders page. */
    public record OStats(String grossLabel, long ordersCount, long pendingCount,
                         long refundsCount, String refundedLabel) {}

    /** Sent-campaign history card. */
    public record CampaignRow(String subject, String audienceLabel, String sentLine,
                              int recipients, String eventTitle) {}

    /** Tracking link table row. */
    public record LinkRow(Long id, String url, String channel, long clicks, String eventTitle) {}

    /** Pre-built social share links per event (server-encoded). */
    public record ShareRow(String title, String dateLine, String url, String waHref,
                           String tgHref, String fbHref, String xHref, Long eventId) {}

    private static final Set<String> CHANNELS =
            Set.of("instagram", "facebook", "whatsapp", "telegram", "twitter", "other");

    private final UserService userService;
    private final HostService hostService;
    private final PromoService promoService;
    private final TeamService teamService;
    private final MailService mailService;
    private final OrderService orderService;
    private final CampaignService campaignService;
    private final TrackingService trackingService;
    private final CampaignRepository campaigns;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final TicketTypeRepository ticketTypes;
    private final JdbcTemplate jdbc;
    private final String baseUrl;

    public HostExtrasController(UserService userService, HostService hostService,
                                PromoService promoService, TeamService teamService,
                                MailService mailService, OrderService orderService,
                                CampaignService campaignService, TrackingService trackingService,
                                CampaignRepository campaigns, OrderRepository orders,
                                TicketRepository tickets, TicketTypeRepository ticketTypes,
                                JdbcTemplate jdbc,
                                @Value("${app.base-url}") String baseUrl) {
        this.userService = userService;
        this.hostService = hostService;
        this.promoService = promoService;
        this.teamService = teamService;
        this.mailService = mailService;
        this.orderService = orderService;
        this.campaignService = campaignService;
        this.trackingService = trackingService;
        this.campaigns = campaigns;
        this.orders = orders;
        this.tickets = tickets;
        this.ticketTypes = ticketTypes;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl;
    }

    private TeamService.Access access(UserDetails principal) {
        User u = principal == null ? null : userService.byEmail(principal.getUsername());
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return hostService.accessOf(u).orElse(null);
    }

    private User user(UserDetails principal) {
        return userService.byEmail(principal.getUsername());
    }

    private static void requireManage(TeamService.Access access) {
        if (access == null || !access.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    // ============================================================
    // Attendees (enriched view — takes over when ?event= is present)
    // ============================================================

    @GetMapping(value = "/attendees", params = "event")
    @Transactional(readOnly = true)
    public String attendees(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam Long event,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String status,
                            Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        Long orgId = access.org().getId();
        Event ev = hostService.eventOf(orgId, event).orElse(null);
        if (ev == null) return "redirect:/host/attendees";

        String qLike = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        List<Ticket> all = tickets.searchForEvent(ev.getId(), null);
        List<Ticket> filtered = tickets.searchForEvent(ev.getId(), qLike).stream()
                .filter(t -> type == null || type.isBlank()
                        || t.getTicketType().getId().toString().equals(type))
                .filter(t -> status == null || status.isBlank()
                        || t.getStatus().name().equals(status))
                .toList();

        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("canManage", access.canManage());
        model.addAttribute("eventOptions", sortForSelect(hostService.eventsOf(orgId)));
        model.addAttribute("ev", new IdTitle(ev.getId(), ev.getTitle(), ev.getSlug()));
        model.addAttribute("evDateLine", Format.cardDateLine(ev.getStartsAt()));
        model.addAttribute("rows", filtered.stream().map(this::toARow).toList());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("typeFilter", type == null ? "" : type);
        model.addAttribute("statusFilter", status == null ? "" : status);
        model.addAttribute("typeOptions",
                ticketTypes.findByEventIdOrderBySortOrderAsc(ev.getId()).stream()
                        .map(tt -> new IdTitle(tt.getId(), tt.getName(), null)).toList());
        model.addAttribute("astats", attendeeStats(all));
        model.addAttribute("typeCounts", typeCounts(all));
        return "host/attendees";
    }

    /** CSV export of the currently filtered attendee list. */
    @GetMapping("/attendees/export.csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> attendeesCsv(@AuthenticationPrincipal UserDetails principal,
                                               @RequestParam Long event,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String status) {
        TeamService.Access access = access(principal);
        if (access == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Event ev = hostService.eventOf(access.org().getId(), event)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String qLike = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        StringBuilder sb = new StringBuilder("Name,Email,Ticket type,Order code,Status,Checked in at\r\n");
        tickets.searchForEvent(ev.getId(), qLike).stream()
                .filter(t -> type == null || type.isBlank()
                        || t.getTicketType().getId().toString().equals(type))
                .filter(t -> status == null || status.isBlank()
                        || t.getStatus().name().equals(status))
                .forEach(t -> sb.append(csv(t.getHolderName())).append(',')
                        .append(csv(t.getHolderEmail() != null ? t.getHolderEmail()
                                : t.getOrder().getBuyerEmail())).append(',')
                        .append(csv(t.getTicketType().getName())).append(',')
                        .append(csv(t.getOrder().getOrderCode())).append(',')
                        .append(csv(attendeeStatusLabel(t.getStatus()))).append(',')
                        .append(csv(t.getCheckedInAt() == null ? ""
                                : Format.cardDateLine(t.getCheckedInAt())))
                        .append("\r\n"));
        return csvResponse(sb, "attendees-" + ev.getSlug() + ".csv");
    }

    /** Re-emails the confirmed order behind one ticket (per-row "Resend ticket"). */
    @PostMapping("/attendees/{ticketId}/resend")
    public String resendTicket(@PathVariable Long ticketId,
                               @AuthenticationPrincipal UserDetails principal,
                               @RequestHeader(value = "Referer", required = false) String referer,
                               RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        // OSIV is off — resolve the ticket's order via SQL instead of lazy traversal.
        List<Long> orderIds = jdbc.queryForList("""
                SELECT t.order_id FROM tickets t
                JOIN events e ON e.id = t.event_id
                WHERE t.id = ? AND e.organization_id = ?
                """, Long.class, ticketId, access.org().getId());
        if (orderIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            Order o = orderService.resend(orderIds.get(0), access.org().getId());
            redirect.addFlashAttribute("actioned",
                    "Tickets for order " + o.getOrderCode() + " were re-emailed to " + o.getBuyerEmail() + ".");
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + (referer == null ? "/host/attendees" : referer);
    }

    /** Bulk "mark checked in" for the selected rows. */
    @PostMapping("/attendees/bulk-checkin")
    @Transactional
    public String bulkCheckin(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam(name = "ids", required = false) List<Long> ids,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        int done = 0;
        if (ids != null) {
            for (Long id : ids) {
                done += jdbc.update("""
                        UPDATE tickets SET status = 'CHECKED_IN', checked_in_at = now()
                        WHERE id = ? AND status = 'VALID'
                          AND event_id IN (SELECT id FROM events WHERE organization_id = ?)
                        """, id, access.org().getId());
            }
        }
        redirect.addFlashAttribute("actioned",
                done + (done == 1 ? " attendee" : " attendees") + " checked in.");
        return "redirect:" + (referer == null ? "/host/attendees" : referer);
    }

    // ============================================================
    // Orders (enriched view — takes over when ?f= is present)
    // ============================================================

    @GetMapping(value = "/orders", params = "f")
    @Transactional(readOnly = true)
    public String orders(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        Long orgId = access.org().getId();

        Order.Status filter = statusParam(status);
        List<Order> pool = orders.findForOrganization(orgId, filter, PageRequest.of(0, 1000)).getContent();

        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
        OffsetDateTime fromTs = parseDayStart(from);
        OffsetDateTime toTs = parseDayEnd(to);
        List<Order> filtered = pool.stream()
                .filter(o -> needle == null
                        || o.getOrderCode().toLowerCase().contains(needle)
                        || o.getBuyerName().toLowerCase().contains(needle)
                        || o.getBuyerEmail().toLowerCase().contains(needle))
                .filter(o -> fromTs == null || !o.getCreatedAt().isBefore(fromTs))
                .filter(o -> toTs == null || !o.getCreatedAt().isAfter(toTs))
                .toList();

        int p = Math.max(0, page);
        int fromIdx = Math.min(p * 20, filtered.size());
        int toIdx = Math.min(fromIdx + 20, filtered.size());
        List<HostController.OrderRow> rows = filtered.subList(fromIdx, toIdx).stream()
                .map(this::toOrderRow).toList();
        Page<HostController.OrderRow> pageOut =
                new PageImpl<>(rows, PageRequest.of(p, 20), filtered.size());

        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("canManage", access.canManage());
        model.addAttribute("orders", pageOut);
        model.addAttribute("statusFilter", status == null ? "" : status);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("from", from == null ? "" : from);
        model.addAttribute("to", to == null ? "" : to);
        model.addAttribute("pendingCount",
                orders.countForOrganizationByStatus(orgId, Order.Status.PENDING_CONFIRMATION));
        model.addAttribute("confirmedCount",
                orders.countForOrganizationByStatus(orgId, Order.Status.CONFIRMED));
        model.addAttribute("rejectedCount",
                orders.countForOrganizationByStatus(orgId, Order.Status.REJECTED));
        model.addAttribute("refundedCount",
                orders.countForOrganizationByStatus(orgId, Order.Status.REFUNDED));
        model.addAttribute("ostats", orderStats(orgId));
        return "host/orders";
    }

    /** CSV export of the currently filtered orders view. */
    @GetMapping("/orders/export.csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> ordersCsv(@AuthenticationPrincipal UserDetails principal,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        TeamService.Access access = access(principal);
        if (access == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Long orgId = access.org().getId();
        Order.Status filter = statusParam(status);
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
        OffsetDateTime fromTs = parseDayStart(from);
        OffsetDateTime toTs = parseDayEnd(to);
        StringBuilder sb = new StringBuilder(
                "Order code,Buyer,Email,Event,Tickets,Total IQD,Payment,Status,Transfer ref,Placed\r\n");
        orders.findForOrganization(orgId, filter, PageRequest.of(0, 1000)).getContent().stream()
                .filter(o -> needle == null
                        || o.getOrderCode().toLowerCase().contains(needle)
                        || o.getBuyerName().toLowerCase().contains(needle)
                        || o.getBuyerEmail().toLowerCase().contains(needle))
                .filter(o -> fromTs == null || !o.getCreatedAt().isBefore(fromTs))
                .filter(o -> toTs == null || !o.getCreatedAt().isAfter(toTs))
                .forEach(o -> sb.append(csv(o.getOrderCode())).append(',')
                        .append(csv(o.getBuyerName())).append(',')
                        .append(csv(o.getBuyerEmail())).append(',')
                        .append(csv(o.getEvent().getTitle())).append(',')
                        .append(csv(itemsLabel(o))).append(',')
                        .append(o.getTotalIqd()).append(',')
                        .append(csv(o.getPaymentMethod() == Order.PaymentMethod.FREE
                                ? "Free" : "Direct transfer")).append(',')
                        .append(csv(orderStatusLabel(o.getStatus()))).append(',')
                        .append(csv(o.getTransferReference() == null ? "" : o.getTransferReference())).append(',')
                        .append(csv(Format.cardDateLine(o.getCreatedAt())))
                        .append("\r\n"));
        return csvResponse(sb, "orders.csv");
    }

    @PostMapping("/orders/{id}/refund")
    public String refundOrder(@PathVariable Long id,
                              @AuthenticationPrincipal UserDetails principal,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        try {
            Order o = orderService.refund(id, access.org().getId());
            redirect.addFlashAttribute("actioned", "Order " + o.getOrderCode()
                    + " refunded — tickets voided and the buyer was notified. Return "
                    + Format.iqd(o.getTotalIqd()) + " to the buyer yourself (direct transfer).");
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + (referer == null ? "/host/orders?f=1" : referer);
    }

    @PostMapping("/orders/{id}/resend")
    public String resendOrder(@PathVariable Long id,
                              @AuthenticationPrincipal UserDetails principal,
                              @RequestHeader(value = "Referer", required = false) String referer,
                              RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        try {
            Order o = orderService.resend(id, access.org().getId());
            redirect.addFlashAttribute("actioned",
                    "Confirmation re-sent to " + o.getBuyerEmail() + " for order " + o.getOrderCode() + ".");
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + (referer == null ? "/host/orders?f=1" : referer);
    }

    // ============================================================
    // Marketing
    // ============================================================

    @GetMapping("/marketing")
    @Transactional(readOnly = true)
    public String marketing(@AuthenticationPrincipal UserDetails principal, Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        Long orgId = access.org().getId();
        List<PromoView> promos = promoService.forOrganization(orgId).stream()
                .map(this::toView).toList();
        List<Event> events = hostService.eventsOf(orgId);

        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("canManage", access.canManage());
        model.addAttribute("promos", promos);
        model.addAttribute("events", events.stream()
                .map(e -> new IdTitle(e.getId(), e.getTitle(), e.getSlug())).toList());
        model.addAttribute("shareBase", baseUrl);
        model.addAttribute("campaignRows",
                campaigns.findTop20ByOrganizationIdOrderBySentAtDesc(orgId).stream()
                        .map(this::toCampaignRow).toList());
        model.addAttribute("followersCount",
                campaignService.audienceSize(access.org(), null, Campaign.Audience.FOLLOWERS));
        model.addAttribute("pastCount",
                campaignService.audienceSize(access.org(), null, Campaign.Audience.PAST_ATTENDEES));
        model.addAttribute("linkRows", trackingService.forOrganization(orgId).stream()
                .map(this::toLinkRow).toList());
        model.addAttribute("shareRows", events.stream().map(this::toShareRow).toList());
        return "host/marketing";
    }

    @PostMapping("/marketing/promos")
    public String createPromo(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam String code,
                              @RequestParam String kind,
                              @RequestParam long value,
                              @RequestParam(defaultValue = "0") int maxUses,
                              @RequestParam(required = false) String expires,
                              @RequestParam(required = false) Long eventId,
                              RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        if (code == null || code.isBlank()) {
            redirect.addFlashAttribute("error", "Give the code a name, e.g. EARLY20.");
            return "redirect:/host/marketing";
        }
        try {
            OffsetDateTime exp = expires == null || expires.isBlank() ? null
                    : LocalDate.parse(expires).atTime(23, 59)
                        .atZone(Format.BAGHDAD).toOffsetDateTime();
            promoService.create(access.org().getId(), eventId,
                    code, "FIXED".equals(kind) ? PromoCode.Kind.FIXED : PromoCode.Kind.PERCENT,
                    value, maxUses, exp);
            redirect.addFlashAttribute("created", "Promo code " + code.trim().toUpperCase() + " is live.");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Could not create the code — is it already used?");
        }
        return "redirect:/host/marketing";
    }

    @PostMapping("/marketing/promos/{id}/toggle")
    public String togglePromo(@PathVariable Long id,
                              @RequestParam boolean active,
                              @AuthenticationPrincipal UserDetails principal) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        promoService.setActive(access.org().getId(), id, active);
        return "redirect:/host/marketing";
    }

    /** Sends an email campaign to one of the three audiences. */
    @PostMapping("/marketing/email")
    public String sendCampaign(@AuthenticationPrincipal UserDetails principal,
                               @RequestParam String audience,
                               @RequestParam(required = false) Long eventId,
                               @RequestParam String subject,
                               @RequestParam String message,
                               RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        if (subject == null || subject.isBlank() || message == null || message.isBlank()) {
            redirect.addFlashAttribute("error", "Subject and message are both required.");
            return "redirect:/host/marketing?tab=email";
        }
        Campaign.Audience aud;
        try {
            aud = Campaign.Audience.valueOf(audience);
        } catch (Exception e) {
            aud = Campaign.Audience.EVENT_ATTENDEES;
        }
        Event event = null;
        if (eventId != null) {
            event = hostService.eventOf(access.org().getId(), eventId).orElse(null);
        }
        if (aud == Campaign.Audience.EVENT_ATTENDEES && event == null) {
            redirect.addFlashAttribute("error", "Pick the event whose attendees should get this email.");
            return "redirect:/host/marketing?tab=email";
        }
        String linkUrl = event != null
                ? baseUrl + "/events/" + event.getSlug()
                : baseUrl + "/organizers/" + access.org().getHandle();
        int sent = campaignService.send(access.org(), event, aud, subject.trim(), message.trim(), linkUrl);
        redirect.addFlashAttribute("created",
                "Campaign sent to " + sent + " recipient" + (sent == 1 ? "" : "s") + ".");
        return "redirect:/host/marketing?tab=email";
    }

    /** Creates a tracking link /l/{code} for an event + channel. */
    @PostMapping("/marketing/links")
    public String createTrackingLink(@AuthenticationPrincipal UserDetails principal,
                                     @RequestParam Long eventId,
                                     @RequestParam String channel,
                                     RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        Event event = hostService.eventOf(access.org().getId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String ch = channel == null ? "other" : channel.trim().toLowerCase();
        if (!CHANNELS.contains(ch)) ch = "other";
        TrackingLink link = trackingService.create(access.org(), event, ch);
        redirect.addFlashAttribute("created",
                "Tracking link ready: " + baseUrl + "/l/" + link.getCode()
                        + " — clicks are counted per channel.");
        return "redirect:/host/marketing?tab=links";
    }

    @PostMapping("/marketing/links/{id}/delete")
    public String deleteTrackingLink(@PathVariable Long id,
                                     @AuthenticationPrincipal UserDetails principal,
                                     RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        trackingService.delete(id, access.org().getId());
        redirect.addFlashAttribute("created", "Tracking link deleted.");
        return "redirect:/host/marketing?tab=links";
    }

    // ============================================================
    // Earnings
    // ============================================================

    @GetMapping("/earnings")
    @Transactional(readOnly = true)
    public String earnings(@AuthenticationPrincipal UserDetails principal, Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        Long orgId = access.org().getId();
        List<HostService.EarningsRow> rows = hostService.earnings(orgId);
        HostService.HostStats stats = hostService.stats(orgId);

        long gross = queryLong("""
                SELECT COALESCE(SUM(o.subtotal_iqd - o.discount_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                """, orgId);
        long fees = queryLong("""
                SELECT COALESCE(SUM(o.booking_fee_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                """, orgId);
        long refunded = queryLong("""
                SELECT COALESCE(SUM(o.total_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'REFUNDED'
                """, orgId);

        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("rows", rows);
        model.addAttribute("stats", stats);
        model.addAttribute("revenueLabel", Format.iqd(stats.revenueIqd()));
        model.addAttribute("grossTotalLabel", Format.iqd(gross));
        model.addAttribute("feesTotalLabel", Format.iqd(fees));
        model.addAttribute("netTotalLabel", Format.iqd(gross));
        model.addAttribute("refundedTotalLabel", refunded == 0 ? null : Format.iqd(refunded));
        return "host/earnings";
    }

    // ============================================================
    // Organization settings & team
    // ============================================================

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    public String settings(@AuthenticationPrincipal UserDetails principal, Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("canManage", access.canManage());
        model.addAttribute("members", teamService.members(access.org()));
        model.addAttribute("logoUrl", access.org().getLogoPath() == null ? null
                : "/media/org-logo/" + access.org().getId());
        model.addAttribute("brandColorValue", access.org().getBrandColor() == null
                ? "#8f7ac9" : access.org().getBrandColor());
        return "host/settings";
    }

    @PostMapping("/settings/org")
    public String saveOrg(@AuthenticationPrincipal UserDetails principal,
                          @RequestParam String name,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String bio,
                          RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        if (name != null && !name.isBlank()) {
            hostService.updateOrganizationProfile(access.org(), name, city, bio);
            redirect.addFlashAttribute("saved", true);
        }
        return "redirect:/host/settings";
    }

    /** Branding: logo, brand color, contact & socials. One POST → HostService.saveBranding. */
    @PostMapping("/settings/branding")
    public String saveBranding(@AuthenticationPrincipal UserDetails principal,
                               @RequestParam(required = false) String contactEmail,
                               @RequestParam(required = false) String contactPhone,
                               @RequestParam(required = false) String website,
                               @RequestParam(required = false) String instagram,
                               @RequestParam(required = false) String brandColor,
                               @RequestParam(name = "logo", required = false) MultipartFile logo,
                               RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        String error = hostService.saveBranding(access.org(), contactEmail, contactPhone,
                website, instagram, brandColor,
                access.org().isNotifyPendingOrders(), logo);
        if (error != null) redirect.addFlashAttribute("brandError", error);
        else redirect.addFlashAttribute("saved", true);
        return "redirect:/host/settings?tab=brand";
    }

    /** Notifications: the pending-orders email toggle (keeps branding fields as-is). */
    @PostMapping("/settings/notifications")
    public String saveNotifications(@AuthenticationPrincipal UserDetails principal,
                                    @RequestParam(defaultValue = "false") boolean notifyPendingOrders,
                                    RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        requireManage(access);
        var org = access.org();
        hostService.saveBranding(org, org.getContactEmail(), org.getContactPhone(),
                org.getWebsite(), org.getInstagram(), org.getBrandColor(),
                notifyPendingOrders, null);
        redirect.addFlashAttribute("saved", true);
        return "redirect:/host/settings?tab=notif";
    }

    @PostMapping("/settings/team/invite")
    public String invite(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam String email,
                         @RequestParam String role,
                         RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        if (!access.owner()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String error = teamService.invite(access.org(), email, role);
        if (error != null) redirect.addFlashAttribute("teamError", error);
        else redirect.addFlashAttribute("invited", email);
        return "redirect:/host/settings?tab=team";
    }

    @PostMapping("/settings/team/{memberId}/remove")
    public String removeMember(@PathVariable long memberId,
                               @AuthenticationPrincipal UserDetails principal) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        if (!access.owner()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        teamService.remove(access.org(), memberId);
        return "redirect:/host/settings?tab=team";
    }

    // ============================================================
    // Mapping helpers
    // ============================================================

    private ARow toARow(Ticket t) {
        String email = t.getHolderEmail() != null ? t.getHolderEmail()
                : t.getOrder().getBuyerEmail();
        boolean confirmed = t.getOrder().getStatus() == Order.Status.CONFIRMED;
        return new ARow(t.getId(), t.getHolderName(),
                t.getHolderName() == null || t.getHolderName().isBlank() ? "?"
                        : t.getHolderName().substring(0, 1).toUpperCase(),
                t.getTicketType().getName(), t.getOrder().getOrderCode(), t.getCode(),
                email, Format.cardDateLine(t.getOrder().getCreatedAt()),
                t.getStatus().name(),
                t.getStatus() == Ticket.Status.CHECKED_IN,
                t.getStatus() == Ticket.Status.VOID,
                t.getCheckedInAt() == null ? null : Format.cardDateLine(t.getCheckedInAt()),
                t.getOrder().getId(), confirmed);
    }

    private AStats attendeeStats(List<Ticket> all) {
        long voided = all.stream().filter(t -> t.getStatus() == Ticket.Status.VOID).count();
        long total = all.size() - voided;
        long in = all.stream().filter(t -> t.getStatus() == Ticket.Status.CHECKED_IN).count();
        int pct = total == 0 ? 0 : (int) Math.round(in * 100.0 / total);
        // ring circumference r=62 → 389.6; offset shrinks as pct grows
        double offset = 389.6 * (1 - pct / 100.0);
        return new AStats(total, in, Math.max(0, total - in), voided, pct,
                String.format(java.util.Locale.US, "%.1f", offset));
    }

    private List<TypeCount> typeCounts(List<Ticket> all) {
        List<Ticket> live = all.stream().filter(t -> t.getStatus() != Ticket.Status.VOID).toList();
        long total = live.size();
        java.util.LinkedHashMap<String, Long> byType = new java.util.LinkedHashMap<>();
        for (Ticket t : live) {
            byType.merge(t.getTicketType().getName(), 1L, Long::sum);
        }
        List<TypeCount> out = new ArrayList<>();
        for (var e : byType.entrySet()) {
            out.add(new TypeCount(e.getKey(), e.getValue(),
                    total == 0 ? 0 : (int) Math.round(e.getValue() * 100.0 / total)));
        }
        return out;
    }

    private HostController.OrderRow toOrderRow(Order o) {
        return new HostController.OrderRow(o.getId(), o.getOrderCode(), o.getBuyerName(),
                o.getBuyerEmail(), o.getEvent().getTitle(), itemsLabel(o),
                Format.iqd(o.getTotalIqd()),
                o.getPaymentMethod() == Order.PaymentMethod.FREE ? "Free" : "Direct transfer",
                orderStatusLabel(o.getStatus()),
                o.getStatus() == Order.Status.PENDING_CONFIRMATION,
                Format.cardDateLine(o.getCreatedAt()),
                o.getReceiptPath() != null,
                o.getTransferReference());
    }

    private static String itemsLabel(Order o) {
        return o.getItems().stream()
                .map(i -> i.getQuantity() + "× " + i.getTicketType().getName())
                .reduce((a, b) -> a + ", " + b).orElse("—");
    }

    private OStats orderStats(Long orgId) {
        long gross = queryLong("""
                SELECT COALESCE(SUM(o.total_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                """, orgId);
        long count = queryLong("""
                SELECT COUNT(*) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ?
                """, orgId);
        long refunds = orders.countForOrganizationByStatus(orgId, Order.Status.REFUNDED);
        long refunded = queryLong("""
                SELECT COALESCE(SUM(o.total_iqd), 0) FROM orders o
                JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.status = 'REFUNDED'
                """, orgId);
        long pending = orders.countForOrganizationByStatus(orgId, Order.Status.PENDING_CONFIRMATION);
        return new OStats(Format.iqd(gross), count, pending, refunds, Format.iqd(refunded));
    }

    private CampaignRow toCampaignRow(Campaign c) {
        String audience = switch (c.getAudience()) {
            case EVENT_ATTENDEES -> "Event attendees";
            case PAST_ATTENDEES -> "Past attendees";
            case FOLLOWERS -> "Followers";
        };
        String eventTitle = c.getEvent() == null ? null : c.getEvent().getTitle();
        return new CampaignRow(c.getSubject(), audience,
                Format.cardDateLine(c.getSentAt()), c.getRecipients(), eventTitle);
    }

    private LinkRow toLinkRow(TrackingLink l) {
        return new LinkRow(l.getId(), baseUrl + "/l/" + l.getCode(), l.getChannel(),
                l.getClicks(), l.getEvent().getTitle());
    }

    private ShareRow toShareRow(Event e) {
        String url = baseUrl + "/events/" + e.getSlug();
        String encUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
        String encTitle = URLEncoder.encode(e.getTitle(), StandardCharsets.UTF_8);
        String encBoth = URLEncoder.encode(e.getTitle() + " — " + url, StandardCharsets.UTF_8);
        return new ShareRow(e.getTitle(), Format.cardDateLine(e.getStartsAt()), url,
                "https://wa.me/?text=" + encBoth,
                "https://t.me/share/url?url=" + encUrl + "&text=" + encTitle,
                "https://www.facebook.com/sharer/sharer.php?u=" + encUrl,
                "https://twitter.com/intent/tweet?text=" + encTitle + "&url=" + encUrl,
                e.getId());
    }

    /** Soonest upcoming events first, then past events (most recent first). */
    private List<IdTitle> sortForSelect(List<Event> events) {
        OffsetDateTime now = OffsetDateTime.now();
        return events.stream()
                .sorted((a, b) -> {
                    boolean fa = a.getStartsAt().isAfter(now);
                    boolean fb = b.getStartsAt().isAfter(now);
                    if (fa != fb) return fa ? -1 : 1;
                    return fa ? a.getStartsAt().compareTo(b.getStartsAt())
                              : b.getStartsAt().compareTo(a.getStartsAt());
                })
                .map(e -> new IdTitle(e.getId(), e.getTitle(), e.getSlug()))
                .toList();
    }

    private static Order.Status statusParam(String status) {
        if ("pending".equalsIgnoreCase(status)) return Order.Status.PENDING_CONFIRMATION;
        if ("confirmed".equalsIgnoreCase(status)) return Order.Status.CONFIRMED;
        if ("rejected".equalsIgnoreCase(status)) return Order.Status.REJECTED;
        if ("refunded".equalsIgnoreCase(status)) return Order.Status.REFUNDED;
        return null;
    }

    private static String orderStatusLabel(Order.Status s) {
        return switch (s) {
            case PENDING_CONFIRMATION -> "Pending confirmation";
            case CONFIRMED -> "Confirmed";
            case REJECTED -> "Rejected";
            case CANCELLED -> "Cancelled";
            case REFUNDED -> "Refunded";
        };
    }

    private static String attendeeStatusLabel(Ticket.Status s) {
        return switch (s) {
            case VALID -> "Valid";
            case CHECKED_IN -> "Checked in";
            case VOID -> "Void";
        };
    }

    private static OffsetDateTime parseDayStart(String day) {
        if (day == null || day.isBlank()) return null;
        try {
            return LocalDate.parse(day.trim()).atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime parseDayEnd(String day) {
        if (day == null || day.isBlank()) return null;
        try {
            return LocalDate.parse(day.trim()).atTime(23, 59, 59)
                    .atZone(Format.BAGHDAD).toOffsetDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private long queryLong(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    /** CSV field escaping: quote when the value contains a comma, quote or newline. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static ResponseEntity<byte[]> csvResponse(StringBuilder sb, String filename) {
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private PromoView toView(PromoCode p) {
        String kind = p.getKind() == PromoCode.Kind.PERCENT
                ? p.getValue() + "% off"
                : Format.iqd(p.getValue()) + " off";
        String scope = p.getEventId() == null ? "All events" : "One event";
        String uses = p.getMaxUses() == 0 ? p.getUsed() + " used"
                : p.getUsed() + " / " + p.getMaxUses();
        String expires = p.getExpiresAt() == null ? null
                : "Expires " + Format.cardDateLine(p.getExpiresAt());
        return new PromoView(p.getId(), p.getCode(), kind, scope, uses, p.isActive(), expires);
    }
}
