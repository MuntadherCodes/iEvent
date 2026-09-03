package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Super-admin console — platform-wide oversight, gated by SuperAdminAuthFilter
 *  behind a single shared password (not a user account). Lets the operator see
 *  every host org with stats, take individual events down (or restore them),
 *  and suspend/reactivate a whole account. English-only UI: this is an
 *  internal ops tool, not part of the bilingual public/host surface. */
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

    public AdminController(OrganizationRepository organizations, EventRepository events,
                           UserRepository users, JdbcTemplate jdbc) {
        this.organizations = organizations;
        this.events = events;
        this.users = users;
        this.jdbc = jdbc;
    }

    private static String dateLine(OffsetDateTime t) {
        return t == null ? "-" : t.atZoneSameInstant(BAGHDAD).format(DATE_FMT);
    }

    public record OrgRow(Long id, String name, String handle, String ownerName, String ownerEmail,
                         long activeEvents, long totalEvents, boolean disabled, String createdLine) {}

    @GetMapping({"", "/"})
    public String dashboard(@RequestParam(required = false) String q,
                            @RequestParam(required = false, defaultValue = "all") String status,
                            Model model) {
        List<OrgRow> all = jdbc.query("""
                SELECT o.id, o.name, o.handle, o.disabled, o.created_at,
                       u.full_name AS owner_name, u.email AS owner_email,
                       (SELECT count(*) FROM events e WHERE e.organization_id = o.id
                            AND e.status = 'LIVE' AND e.admin_hidden = false) AS active_events,
                       (SELECT count(*) FROM events e WHERE e.organization_id = o.id) AS total_events
                FROM organizations o
                JOIN users u ON u.id = o.owner_user_id
                ORDER BY o.created_at DESC
                """,
                (rs, i) -> new OrgRow(
                        rs.getLong("id"), rs.getString("name"), rs.getString("handle"),
                        rs.getString("owner_name"), rs.getString("owner_email"),
                        rs.getLong("active_events"), rs.getLong("total_events"),
                        rs.getBoolean("disabled"),
                        dateLine(rs.getObject("created_at", OffsetDateTime.class))));

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String statusFilter = status == null || status.isBlank() ? "all" : status;
        List<OrgRow> filtered = all.stream()
                .filter(o -> needle.isEmpty()
                        || o.name().toLowerCase(Locale.ROOT).contains(needle)
                        || o.handle().toLowerCase(Locale.ROOT).contains(needle)
                        || o.ownerName().toLowerCase(Locale.ROOT).contains(needle)
                        || o.ownerEmail().toLowerCase(Locale.ROOT).contains(needle))
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
