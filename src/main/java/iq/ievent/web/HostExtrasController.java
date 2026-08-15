package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.PromoCode;
import iq.ievent.domain.User;
import iq.ievent.service.HostService;
import iq.ievent.service.MailService;
import iq.ievent.service.PromoService;
import iq.ievent.service.TeamService;
import iq.ievent.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Host marketing, earnings, organization settings and team.
 *
 * Template contracts (all also get currentUser, org, access (TeamService.Access), pendingCount omitted):
 *  host/marketing.html — promos (List<PromoView>), events (List<HostController.EventRow>),
 *                        shareBase (String), created/error flash
 *  host/earnings.html  — rows (List<HostService.EarningsRow>), totals (gross/fees labels)
 *  host/settings.html  — org, members (List<TeamService.Member>), canManage (boolean),
 *                        saved/teamError/invited flash
 */
@Controller
@RequestMapping("/host")
public class HostExtrasController {

    public record PromoView(Long id, String code, String kindLabel, String scopeLabel,
                            String usesLabel, boolean active, String expiresLabel) {}

    private final UserService userService;
    private final HostService hostService;
    private final PromoService promoService;
    private final TeamService teamService;
    private final MailService mailService;
    private final JdbcTemplate jdbc;
    private final String baseUrl;

    public HostExtrasController(UserService userService, HostService hostService,
                                PromoService promoService, TeamService teamService,
                                MailService mailService, JdbcTemplate jdbc,
                                @Value("${app.base-url}") String baseUrl) {
        this.userService = userService;
        this.hostService = hostService;
        this.promoService = promoService;
        this.teamService = teamService;
        this.mailService = mailService;
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

    // ---------- marketing ----------

    @GetMapping("/marketing")
    @Transactional(readOnly = true)
    public String marketing(@AuthenticationPrincipal UserDetails principal, Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        List<PromoView> promos = promoService.forOrganization(access.org().getId()).stream()
                .map(this::toView).toList();
        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("canManage", access.canManage());
        model.addAttribute("promos", promos);
        model.addAttribute("events", hostService.eventsOf(access.org().getId()).stream()
                .map(e -> new IdTitle(e.getId(), e.getTitle(), e.getSlug())).toList());
        model.addAttribute("shareBase", baseUrl);
        return "host/marketing";
    }

    public record IdTitle(Long id, String title, String slug) {}

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
        if (!access.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (code == null || code.isBlank()) {
            redirect.addFlashAttribute("error", "Give the code a name, e.g. EARLY20.");
            return "redirect:/host/marketing";
        }
        try {
            OffsetDateTime exp = expires == null || expires.isBlank() ? null
                    : LocalDate.parse(expires).atTime(23, 59)
                        .atZone(iq.ievent.service.Format.BAGHDAD).toOffsetDateTime();
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
        if (!access.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        promoService.setActive(access.org().getId(), id, active);
        return "redirect:/host/marketing";
    }

    @PostMapping("/marketing/email")
    @Transactional(readOnly = true)
    public String emailAttendees(@AuthenticationPrincipal UserDetails principal,
                                 @RequestParam Long eventId,
                                 @RequestParam String subject,
                                 @RequestParam String message,
                                 RedirectAttributes redirect) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        if (!access.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Event event = hostService.eventOf(access.org().getId(), eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (subject == null || subject.isBlank() || message == null || message.isBlank()) {
            redirect.addFlashAttribute("error", "Subject and message are both required.");
            return "redirect:/host/marketing";
        }
        List<String> recipients = jdbc.queryForList("""
                SELECT DISTINCT o.buyer_email FROM orders o
                JOIN users u ON u.id = o.buyer_user_id
                WHERE o.event_id = ? AND o.status = 'CONFIRMED' AND u.notify_events = TRUE
                """, String.class, event.getId());
        String url = baseUrl + "/events/" + event.getSlug();
        for (String to : recipients) {
            mailService.sendCampaign(to, subject.trim(), message.trim(), event.getTitle(), url);
        }
        redirect.addFlashAttribute("created",
                "Update sent to " + recipients.size() + " attendee" + (recipients.size() == 1 ? "" : "s") + ".");
        return "redirect:/host/marketing";
    }

    // ---------- earnings ----------

    @GetMapping("/earnings")
    @Transactional(readOnly = true)
    public String earnings(@AuthenticationPrincipal UserDetails principal, Model model) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        List<HostService.EarningsRow> rows = hostService.earnings(access.org().getId());
        model.addAttribute("currentUser", user(principal));
        model.addAttribute("org", access.org());
        model.addAttribute("access", access);
        model.addAttribute("rows", rows);
        model.addAttribute("stats", hostService.stats(access.org().getId()));
        model.addAttribute("revenueLabel",
                iq.ievent.service.Format.iqd(hostService.stats(access.org().getId()).revenueIqd()));
        return "host/earnings";
    }

    // ---------- organization settings & team ----------

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
        if (!access.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (name != null && !name.isBlank()) {
            hostService.updateOrganizationProfile(access.org(), name, city, bio);
            redirect.addFlashAttribute("saved", true);
        }
        return "redirect:/host/settings";
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
        return "redirect:/host/settings";
    }

    @PostMapping("/settings/team/{memberId}/remove")
    public String removeMember(@PathVariable long memberId,
                               @AuthenticationPrincipal UserDetails principal) {
        TeamService.Access access = access(principal);
        if (access == null) return "redirect:/host/start";
        if (!access.owner()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        teamService.remove(access.org(), memberId);
        return "redirect:/host/settings";
    }

    private PromoView toView(PromoCode p) {
        String kind = p.getKind() == PromoCode.Kind.PERCENT
                ? p.getValue() + "% off"
                : iq.ievent.service.Format.iqd(p.getValue()) + " off";
        String scope = p.getEventId() == null ? "All events" : "One event";
        String uses = p.getMaxUses() == 0 ? p.getUsed() + " used"
                : p.getUsed() + " / " + p.getMaxUses();
        String expires = p.getExpiresAt() == null ? null
                : "Expires " + iq.ievent.service.Format.cardDateLine(p.getExpiresAt());
        return new PromoView(p.getId(), p.getCode(), kind, scope, uses, p.isActive(), expires);
    }
}
