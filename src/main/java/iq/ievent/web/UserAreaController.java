package iq.ievent.web;

import iq.ievent.domain.Organization;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.LikeCountRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.service.CatalogService;
import iq.ievent.service.Format;
import iq.ievent.service.InteractionService;
import iq.ievent.service.QrService;
import iq.ievent.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Signed-in user area + public organizer page + public ticket status.
 *
 * Template contracts:
 *  tickets.html:   groups (List<TicketGroup{eventTitle,eventSlug,dateLine,venueLine,tickets:List<CheckoutController.TicketView>}>), currentUser
 *  favorites.html: events (List<Views.EventCard>), currentUser
 *  profile.html:   currentUser, saved (flash Boolean), error (flash String)
 *  organizer.html: org (OrganizerPage), events (List<Views.EventCard>), following (boolean), currentUser
 *  ticket-status.html: t (TicketStatus{code,eventTitle,dateLine,typeName,holderName,status,checkedInAt}), qrSvg
 */
@Controller
public class UserAreaController {

    public record TicketGroup(String eventTitle, String eventSlug, String dateLine, String venueLine,
                              List<CheckoutController.TicketView> tickets) {}

    public record OrganizerPage(String name, String handle, String bio, String city, boolean verified,
                                String followersDisplay, long eventsHosted, String initials) {}

    public record TicketStatus(String code, String eventTitle, String dateLine, String typeName,
                               String holderName, String status, String checkedInLine) {}

    private final UserService userService;
    private final TicketRepository tickets;
    private final CatalogService catalog;
    private final InteractionService interactions;
    private final EventRepository events;
    private final OrganizationRepository organizations;
    private final LikeCountRepository counts;
    private final QrService qr;

    public UserAreaController(UserService userService, TicketRepository tickets, CatalogService catalog,
                              InteractionService interactions, EventRepository events,
                              OrganizationRepository organizations, LikeCountRepository counts, QrService qr) {
        this.userService = userService;
        this.tickets = tickets;
        this.catalog = catalog;
        this.interactions = interactions;
        this.events = events;
        this.organizations = organizations;
        this.counts = counts;
        this.qr = qr;
    }

    private User required(UserDetails principal) {
        User user = principal == null ? null : userService.byEmail(principal.getUsername());
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return user;
    }

    @GetMapping("/me/tickets")
    @Transactional(readOnly = true)
    public String myTickets(@AuthenticationPrincipal UserDetails principal, Model model) {
        User user = required(principal);
        List<Ticket> all = tickets.findForBuyer(user.getId());
        List<TicketGroup> groups = new java.util.ArrayList<>();
        String currentSlug = null;
        List<CheckoutController.TicketView> bucket = null;
        for (Ticket t : all) {
            if (!t.getEvent().getSlug().equals(currentSlug)) {
                currentSlug = t.getEvent().getSlug();
                bucket = new java.util.ArrayList<>();
                groups.add(new TicketGroup(
                        t.getEvent().getTitle(), currentSlug,
                        Format.longDateLine(t.getEvent().getStartsAt(), t.getEvent().getEndsAt()),
                        (t.getEvent().getVenueName() == null ? "" : t.getEvent().getVenueName() + ", ")
                                + t.getEvent().getCity(),
                        bucket));
            }
            bucket.add(new CheckoutController.TicketView(
                    t.getCode(), t.getTicketType().getName(), t.getHolderName(),
                    qr.ticketQrSvg(t.getCode()), t.getStatus().name()));
        }
        model.addAttribute("currentUser", user);
        model.addAttribute("groups", groups);
        return "tickets";
    }

    @GetMapping("/favorites")
    @Transactional(readOnly = true)
    public String favorites(@AuthenticationPrincipal UserDetails principal, Model model) {
        User user = required(principal);
        List<Long> ids = interactions.likedEventIds(user.getId());
        model.addAttribute("currentUser", user);
        model.addAttribute("events", catalog.cardsForIds(ids));
        return "favorites";
    }

    @PostMapping("/events/{slug}/like")
    public String toggleLike(@PathVariable String slug,
                             @AuthenticationPrincipal UserDetails principal,
                             @RequestHeader(value = "Referer", required = false) String referer) {
        User user = principal == null ? null : userService.byEmail(principal.getUsername());
        if (user == null) return "redirect:/auth/login";
        events.findBySlug(slug).ifPresent(e -> interactions.toggleLike(user.getId(), e.getId()));
        return "redirect:" + (referer != null && referer.contains("/events/") ? "/events/" + slug : "/favorites");
    }

    @GetMapping("/organizers/{handle}")
    @Transactional(readOnly = true)
    public String organizer(@PathVariable String handle,
                            @AuthenticationPrincipal UserDetails principal,
                            Model model) {
        User user = principal == null ? null : userService.byEmail(principal.getUsername());
        Organization org = organizations.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organizer not found"));
        model.addAttribute("currentUser", user);
        model.addAttribute("org", new OrganizerPage(org.getName(), org.getHandle(), org.getBio(),
                org.getCity(), org.isVerified(),
                Format.compactCount(counts.followersForOrganization(org.getId())),
                counts.eventsHostedForOrganization(org.getId()),
                initials(org.getName())));
        model.addAttribute("events", catalog.upcomingForOrganization(org.getId(), 12));
        model.addAttribute("following",
                user != null && interactions.isFollowing(user.getId(), org.getId()));
        return "organizer";
    }

    @PostMapping("/organizers/{handle}/follow")
    public String toggleFollow(@PathVariable String handle,
                               @AuthenticationPrincipal UserDetails principal) {
        User user = principal == null ? null : userService.byEmail(principal.getUsername());
        if (user == null) return "redirect:/auth/login";
        organizations.findByHandle(handle)
                .ifPresent(o -> interactions.toggleFollow(user.getId(), o.getId()));
        return "redirect:/organizers/" + handle;
    }

    @GetMapping("/me/profile")
    public String profile(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("currentUser", required(principal));
        return "profile";
    }

    @PostMapping("/me/profile")
    public String updateProfile(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam String fullName,
                                @RequestParam(required = false) String phone,
                                RedirectAttributes redirect) {
        User user = required(principal);
        if (fullName == null || fullName.isBlank()) {
            redirect.addFlashAttribute("error", "Name cannot be empty.");
        } else {
            userService.updateProfile(user, fullName, phone);
            redirect.addFlashAttribute("saved", true);
        }
        return "redirect:/me/profile";
    }

    @GetMapping("/t/{code}")
    @Transactional(readOnly = true)
    public String ticketStatus(@PathVariable String code, Model model,
                               @AuthenticationPrincipal UserDetails principal) {
        Ticket t = tickets.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        model.addAttribute("currentUser",
                principal == null ? null : userService.byEmail(principal.getUsername()));
        model.addAttribute("t", new TicketStatus(
                t.getCode(), t.getEvent().getTitle(),
                Format.longDateLine(t.getEvent().getStartsAt(), t.getEvent().getEndsAt()),
                t.getTicketType().getName(), t.getHolderName(), t.getStatus().name(),
                t.getCheckedInAt() == null ? null
                        : "Checked in " + Format.cardDateLine(t.getCheckedInAt())));
        model.addAttribute("qrSvg", qr.ticketQrSvg(t.getCode()));
        return "ticket-status";
    }

    private static String initials(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
