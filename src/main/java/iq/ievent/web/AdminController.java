package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.UserRepository;
import iq.ievent.service.AdminContacts;
import iq.ievent.service.InquiryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Super-admin console — platform-wide oversight, gated by SuperAdminAuthFilter
 *  behind a single shared password (not a user account). Lets the operator see
 *  every host org with stats, take individual events down (or restore them),
 *  and suspend/reactivate a whole account. English-only UI: this is an
 *  internal ops tool, not part of the bilingual public/host surface.
 *  R42: organizer phone numbers on both pages, the Buyers page (everyone who
 *  placed an order, with the contact details they gave at checkout), and
 *  Inquiries (contact-form messages: when they came, whether they were
 *  answered, and a reply by email). */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final ZoneId BAGHDAD = ZoneId.of("Asia/Baghdad");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final InquiryService inquiries;

    public AdminController(OrganizationRepository organizations, EventRepository events,
                           UserRepository users, JdbcTemplate jdbc, InquiryService inquiries) {
        this.organizations = organizations;
        this.events = events;
        this.users = users;
        this.jdbc = jdbc;
        this.inquiries = inquiries;
    }

    /** R42: the number on the Inquiries tab (messages still waiting for an answer). Every
     *  page of this console shows the tabs, so every request of this controller gets it. */
    @ModelAttribute("openInquiries")
    public long openInquiries() {
        return inquiries.openCount();
    }

    private static String dateLine(OffsetDateTime t) {
        return t == null ? "-" : t.atZoneSameInstant(BAGHDAD).format(DATE_FMT);
    }

    /** R42: phone is the number to reach the organizer on (see AdminContacts.organizerPhone
     *  for which one), phoneNote says where it came from when it is not the owner's own,
     *  phoneDigits feeds the search box. */
    public record OrgRow(Long id, String name, String handle, String ownerName, String ownerEmail,
                         AdminContacts.Phone phone, String phoneNote, String phoneDigits,
                         long activeEvents, long totalEvents, boolean disabled, String createdLine) {}

    @GetMapping({"", "/"})
    public String dashboard(@RequestParam(required = false) String q,
                            @RequestParam(required = false, defaultValue = "all") String status,
                            Model model) {
        List<OrgRow> all = jdbc.query("""
                SELECT o.id, o.name, o.handle, o.disabled, o.created_at,
                       o.contact_phone, o.payment_phone,
                       u.full_name AS owner_name, u.email AS owner_email, u.phone AS owner_phone,
                       (SELECT count(*) FROM events e WHERE e.organization_id = o.id
                            AND e.status = 'LIVE' AND e.admin_hidden = false) AS active_events,
                       (SELECT count(*) FROM events e WHERE e.organization_id = o.id) AS total_events
                FROM organizations o
                JOIN users u ON u.id = o.owner_user_id
                ORDER BY o.created_at DESC
                """,
                (rs, i) -> {
                    AdminContacts.OrganizerPhone reach = AdminContacts.organizerPhone(
                            rs.getString("owner_phone"), rs.getString("contact_phone"),
                            rs.getString("payment_phone"));
                    return new OrgRow(
                            rs.getLong("id"), rs.getString("name"), rs.getString("handle"),
                            rs.getString("owner_name"), rs.getString("owner_email"),
                            reach.phone(), reach.note(), reach.searchDigits(),
                            rs.getLong("active_events"), rs.getLong("total_events"),
                            rs.getBoolean("disabled"),
                            dateLine(rs.getObject("created_at", OffsetDateTime.class)));
                });

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        // R42: an entry that looks like a phone number also searches the organizers' numbers
        String phoneNeedle = AdminContacts.phoneNeedle(q);
        String statusFilter = status == null || status.isBlank() ? "all" : status;
        List<OrgRow> filtered = all.stream()
                .filter(o -> needle.isEmpty()
                        || o.name().toLowerCase(Locale.ROOT).contains(needle)
                        || o.handle().toLowerCase(Locale.ROOT).contains(needle)
                        || o.ownerName().toLowerCase(Locale.ROOT).contains(needle)
                        || o.ownerEmail().toLowerCase(Locale.ROOT).contains(needle)
                        || AdminContacts.hasNumber(o.phoneDigits(), phoneNeedle))
                .filter(o -> switch (statusFilter) {
                    case "active" -> !o.disabled();
                    case "disabled" -> o.disabled();
                    default -> true;
                })
                .toList();

        model.addAttribute("orgs", filtered);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("statusFilter", statusFilter);
        model.addAttribute("countAll", all.size());
        model.addAttribute("countActive", all.stream().filter(o -> !o.disabled()).count());
        model.addAttribute("countDisabled", all.stream().filter(OrgRow::disabled).count());
        model.addAttribute("totalOrgs", all.size());
        model.addAttribute("totalActiveEvents", all.stream().mapToLong(OrgRow::activeEvents).sum());
        return "admin/dashboard";
    }

    public record EventRow(Long id, String slug, String title, String status, boolean adminHidden,
                           String startsLine, long sold) {}

    @GetMapping("/orgs/{id}")
    @Transactional(readOnly = true)
    public String orgDetail(@PathVariable Long id,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false, defaultValue = "all") String status,
                            Model model) {
        Organization org = organizations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User owner = users.findById(org.getOwnerUserId()).orElse(null);

        List<EventRow> all = events.findByOrganizationIdOrderByStartsAtDesc(id).stream()
                .map(e -> {
                    Long soldCount = jdbc.queryForObject(
                            "SELECT COALESCE(SUM(sold), 0) FROM ticket_types WHERE event_id = ?",
                            Long.class, e.getId());
                    return new EventRow(e.getId(), e.getSlug(), e.getTitle(), e.getStatus().name(),
                            e.isAdminHidden(),
                            // precision-aware: TBA/month placeholders must not
                            // show up as literal 2099 / first-of-month dates
                            iq.ievent.service.Format.cardDateLine(e.getStartsAt(), e.getEndsAt(),
                                    e.isHasStartTime(), e.getDatePrecision()),
                            soldCount == null ? 0 : soldCount);
                })
                .toList();

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String statusFilter = status == null || status.isBlank() ? "all" : status;
        List<EventRow> filtered = all.stream()
                .filter(e -> needle.isEmpty() || e.title().toLowerCase(Locale.ROOT).contains(needle))
                .filter(e -> switch (statusFilter) {
                    case "hidden" -> e.adminHidden();
                    case "all" -> true;
                    default -> !e.adminHidden() && e.status().equalsIgnoreCase(statusFilter);
                })
                .toList();

        model.addAttribute("org", org);
        model.addAttribute("ownerName", owner == null ? "-" : owner.getFullName());
        model.addAttribute("ownerEmail", owner == null ? "-" : owner.getEmail());
        // R42: every number this organizer can be reached on, each under its own label
        model.addAttribute("ownerPhone", AdminContacts.Phone.of(owner == null ? null : owner.getPhone()));
        model.addAttribute("contactPhone", AdminContacts.Phone.of(org.getContactPhone()));
        model.addAttribute("paymentPhone", AdminContacts.Phone.of(org.getPaymentPhone()));
        model.addAttribute("contactEmail",
                org.getContactEmail() == null || org.getContactEmail().isBlank() ? "" : org.getContactEmail().trim());
        model.addAttribute("createdLine", dateLine(org.getCreatedAt()));
        model.addAttribute("eventRows", filtered);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("statusFilter", statusFilter);
        model.addAttribute("countAll", all.size());
        model.addAttribute("countLive", all.stream().filter(e -> !e.adminHidden() && "LIVE".equals(e.status())).count());
        model.addAttribute("countDraft", all.stream().filter(e -> !e.adminHidden() && "DRAFT".equals(e.status())).count());
        model.addAttribute("countEnded", all.stream().filter(e -> !e.adminHidden() && "ENDED".equals(e.status())).count());
        model.addAttribute("countCancelled", all.stream().filter(e -> !e.adminHidden() && "CANCELLED".equals(e.status())).count());
        model.addAttribute("countHidden", all.stream().filter(EventRow::adminHidden).count());
        return "admin/org";
    }

    // ============================================================
    // R42: Buyers (everyone who placed an order, and how to reach them)
    // ============================================================

    /** Rows per page on the Buyers list. */
    private static final int BUYERS_PER_PAGE = 50;

    /**
     * One row per person who placed an order, newest buyer first, with the name, email and
     * phone number they gave at checkout. Without parameters it covers the whole platform;
     * ?event=ID narrows it to one event and ?org=ID to one organizer (the links on the
     * organizer page use these), and the counts on the page then describe that event or
     * organizer only. The phone field at checkout is optional, so a buyer may have none.
     *
     * The orders in view are read in one query and folded into people in memory
     * (AdminContacts.buyers), like the organizations list above: simple, and fine for
     * tens of thousands of orders. Move the grouping into SQL when it stops being so.
     */
    @GetMapping("/buyers")
    public String buyers(@RequestParam(required = false) String q,
                         @RequestParam(required = false, defaultValue = "all") String status,
                         @RequestParam(name = "org", required = false) String orgParam,
                         @RequestParam(name = "event", required = false) String eventParam,
                         @RequestParam(name = "page", required = false) String pageParam,
                         Model model) {
        // an event always belongs to one organizer, so when both are given the event wins
        Long eventId = AdminContacts.parseId(eventParam);
        Long organizerId = eventId != null ? null : AdminContacts.parseId(orgParam);

        String scopeEventTitle = null;
        String scopeOrgName = null;
        if (eventId != null) {
            List<String[]> found = jdbc.query("""
                    SELECT e.title, g.id, g.name
                    FROM events e
                    JOIN organizations g ON g.id = e.organization_id
                    WHERE e.id = ?
                    """,
                    (rs, i) -> new String[] {rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3)},
                    eventId);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            scopeEventTitle = found.get(0)[0];
            organizerId = Long.valueOf(found.get(0)[1]);
            scopeOrgName = found.get(0)[2];
        } else if (organizerId != null) {
            scopeOrgName = organizations.findById(organizerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                    .getName();
        }

        String where = eventId != null ? "WHERE o.event_id = ?"
                : organizerId != null ? "WHERE e.organization_id = ?" : "";
        Object[] args = eventId != null ? new Object[] {eventId}
                : organizerId != null ? new Object[] {organizerId} : new Object[0];
        List<AdminContacts.OrderLine> lines = jdbc.query("""
                SELECT o.order_code, o.buyer_user_id, o.buyer_name, o.buyer_email, o.buyer_phone,
                       u.email AS account_email, u.phone AS account_phone,
                       o.status, o.created_at,
                       (SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i
                         WHERE i.order_id = o.id) AS tickets,
                       e.id AS event_id, e.title AS event_title, e.slug AS event_slug,
                       g.name AS org_name
                FROM orders o
                JOIN users u ON u.id = o.buyer_user_id
                JOIN events e ON e.id = o.event_id
                JOIN organizations g ON g.id = e.organization_id
                """ + where + " ORDER BY o.created_at DESC, o.id DESC",
                (rs, i) -> new AdminContacts.OrderLine(
                        rs.getString("order_code"), rs.getLong("buyer_user_id"),
                        rs.getString("buyer_name"), rs.getString("buyer_email"),
                        rs.getString("buyer_phone"),
                        rs.getString("account_email"), rs.getString("account_phone"),
                        rs.getString("status"), rs.getInt("tickets"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getLong("event_id"), rs.getString("event_title"),
                        rs.getString("event_slug"), rs.getString("org_name")),
                args);

        List<AdminContacts.Buyer> all = AdminContacts.buyers(lines);
        String statusFilter = "confirmed".equals(status) || "pending".equals(status) ? status : "all";
        List<AdminContacts.Buyer> filtered = all.stream()
                .filter(b -> AdminContacts.matches(b, q))
                .filter(b -> switch (statusFilter) {
                    case "confirmed" -> b.confirmed() > 0;
                    case "pending" -> b.pending() > 0;
                    default -> true;
                })
                .toList();
        AdminContacts.Paged paged = AdminContacts.page(filtered, AdminContacts.parsePage(pageParam), BUYERS_PER_PAGE);

        model.addAttribute("buyers", paged.rows());
        model.addAttribute("pageNumber", paged.number());
        model.addAttribute("pageCount", paged.pages());
        model.addAttribute("matchCount", paged.total());
        model.addAttribute("shownFrom", paged.from());
        model.addAttribute("shownTo", paged.to());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("statusFilter", statusFilter);
        // the counts describe everything in view (the platform, one organizer or one event),
        // whatever the search box and the status pills are set to
        model.addAttribute("countAll", all.size());
        model.addAttribute("countConfirmed", all.stream().filter(b -> b.confirmed() > 0).count());
        model.addAttribute("countPending", all.stream().filter(b -> b.pending() > 0).count());
        // a number that can be dialled: words typed into the phone box do not count
        model.addAttribute("withPhone", all.stream().filter(b -> !b.phone().tel().isEmpty()).count());
        model.addAttribute("totalOrders", lines.size());
        model.addAttribute("scopeEventId", eventId);
        model.addAttribute("scopeEventTitle", scopeEventTitle);
        model.addAttribute("scopeOrgId", organizerId);
        model.addAttribute("scopeOrgName", scopeOrgName);
        // the same view, as the links on the page carry it ("" = not narrowed)
        model.addAttribute("eventParam", eventId == null ? "" : String.valueOf(eventId));
        model.addAttribute("orgParam", eventId != null || organizerId == null ? "" : String.valueOf(organizerId));
        return "admin/buyers";
    }

    // ============================================================
    // R42: Inquiries (messages from the website's contact form)
    // ============================================================

    /** Every contact-form message, newest first, with the time it arrived and whether it
     *  has been answered. ?status=open shows the ones still waiting, ?status=replied the
     *  answered ones; ?q= searches the sender's name, their email and the message. */
    @GetMapping("/inquiries")
    public String inquiryList(@RequestParam(required = false) String q,
                              @RequestParam(required = false, defaultValue = "all") String status,
                              @RequestParam(name = "page", required = false) String pageParam,
                              Model model) {
        String statusFilter = "open".equals(status) || "replied".equals(status) ? status : "all";
        InquiryService.Listing listing = inquiries.list(q, statusFilter, AdminContacts.parsePage(pageParam));
        InquiryService.Counts counts = inquiries.counts();
        model.addAttribute("inquiries", listing.rows());
        model.addAttribute("pageNumber", listing.number());
        model.addAttribute("pageCount", listing.pages());
        model.addAttribute("matchCount", listing.total());
        model.addAttribute("shownFrom", listing.from());
        model.addAttribute("shownTo", listing.to());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("statusFilter", statusFilter);
        model.addAttribute("countAll", counts.all());
        model.addAttribute("countOpen", counts.open());
        model.addAttribute("countReplied", counts.replied());
        return "admin/inquiries";
    }

    /** One message in full, the replies already sent, and the reply box. */
    @GetMapping("/inquiries/{id}")
    public String inquiryDetail(@PathVariable Long id, Model model) {
        InquiryService.Inquiry inquiry = inquiries.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("inquiry", inquiry);
        model.addAttribute("replies", inquiries.replies(id));
        model.addAttribute("mailFrom", inquiries.mailFrom());
        model.addAttribute("supportEmail", inquiries.supportEmail());
        // a reply that could not be sent comes back as a flash attribute, so the text
        // the admin typed is still in the box
        if (!model.containsAttribute("replyDraft")) model.addAttribute("replyDraft", "");
        return "admin/inquiry";
    }

    /** Emails the reply to the person who wrote and, when the mail server accepted it,
     *  records it and marks the inquiry as replied (InquiryService.reply). */
    @PostMapping("/inquiries/{id}/reply")
    public String replyToInquiry(@PathVariable Long id,
                                 @RequestParam(name = "body", required = false) String body,
                                 RedirectAttributes redirect) {
        InquiryService.ReplyOutcome outcome = inquiries.reply(id, body);
        String problem = switch (outcome) {
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            case SENT -> null;
            case SENT_NOT_RECORDED -> "The reply was emailed, but it could not be recorded here. "
                    + "Do not send it again. Use Mark as replied below instead.";
            case EMPTY -> "Write a reply before sending.";
            case TOO_LONG -> "That reply is longer than 5,000 characters. Shorten it and send again.";
            case BAD_ADDRESS -> "The address this person typed is not a valid email address, so nothing was sent.";
            case MAIL_FAILED -> "The mail server did not accept the reply, so nothing was sent or recorded. "
                    + "Try again in a moment.";
        };
        if (problem == null) {
            redirect.addFlashAttribute("notice", "Reply sent by email.");
        } else {
            redirect.addFlashAttribute("replyError", problem);
            // keep the text in the box, except when it already went out
            if (outcome != InquiryService.ReplyOutcome.SENT_NOT_RECORDED) {
                redirect.addFlashAttribute("replyDraft", body == null ? "" : body);
            }
        }
        return "redirect:/admin/inquiries/" + id;
    }

    /** By hand: answered from the mailbox or by phone, or nothing to answer. */
    @PostMapping("/inquiries/{id}/replied")
    public String markInquiryReplied(@PathVariable Long id) {
        inquiries.markReplied(id);
        return "redirect:/admin/inquiries/" + id;
    }

    /** Back among the waiting ones. */
    @PostMapping("/inquiries/{id}/open")
    public String markInquiryOpen(@PathVariable Long id) {
        inquiries.markOpen(id);
        return "redirect:/admin/inquiries/" + id;
    }

    @PostMapping("/orgs/{id}/disable")
    @Transactional
    public String disableOrg(@PathVariable Long id) {
        Organization org = organizations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        org.setDisabled(true);
        org.setDisabledAt(OffsetDateTime.now());
        organizations.save(org);
        return "redirect:/admin/orgs/" + id;
    }

    @PostMapping("/orgs/{id}/enable")
    @Transactional
    public String enableOrg(@PathVariable Long id) {
        Organization org = organizations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        org.setDisabled(false);
        org.setDisabledAt(null);
        organizations.save(org);
        return "redirect:/admin/orgs/" + id;
    }

    @PostMapping("/events/{id}/hide")
    @Transactional
    public String hideEvent(@PathVariable Long id) {
        Event e = events.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.setAdminHidden(true);
        e.setAdminHiddenAt(OffsetDateTime.now());
        events.save(e);
        return "redirect:/admin/orgs/" + e.getOrganization().getId();
    }

    @PostMapping("/events/{id}/unhide")
    @Transactional
    public String unhideEvent(@PathVariable Long id) {
        Event e = events.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.setAdminHidden(false);
        e.setAdminHiddenAt(null);
        events.save(e);
        return "redirect:/admin/orgs/" + e.getOrganization().getId();
    }
}
