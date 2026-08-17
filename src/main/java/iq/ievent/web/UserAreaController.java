package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Order;
import iq.ievent.domain.Organization;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.LikeCountRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.repo.UserRepository;
import iq.ievent.service.CatalogService;
import iq.ievent.service.Format;
import iq.ievent.service.InteractionService;
import iq.ievent.service.QrService;
import iq.ievent.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Signed-in user area + public organizer page + public ticket status.
 *
 * Template contracts:
 *  tickets.html:   upcomingOrders/pastOrders (List<OrderCard>), currentUser
 *  favorites.html: events (List<Views.EventCard>), orgs (List<OrgCard>), currentUser
 *  profile.html:   currentUser, cities (List<String>), interestOptions (List<InterestOption>),
 *                  saved (flash Boolean), error (flash String)
 *  organizer.html: org (OrganizerPage), events (List<Views.EventCard>), following (boolean), currentUser
 *  ticket-status.html: t (TicketStatus{code,eventTitle,dateLine,typeName,holderName,status,checkedInAt}), qrSvg
 */
@Controller
public class UserAreaController {

    /** Iraqi cities offered in the profile "City" select. */
    public static final List<String> CITIES = List.of(
            "Baghdad", "Erbil", "Basra", "Sulaymaniyah", "Najaf",
            "Karbala", "Mosul", "Duhok", "Kirkuk", "Anbar");

    /** One order on the "My tickets" page (grouped per order, per wireframe). */
    public record OrderCard(String orderCode, String eventTitle, String eventSlug,
                            String dateLine, String venueLine,
                            String coverTheme, String coverImageUrl,
                            String statusKey, String statusLabel, boolean confirmed,
                            List<String> itemLines, List<TicketRow> tickets,
                            boolean online, String onlineUrl) {}

    /** One ticket row inside an order card (rendered only for confirmed orders). */
    public record TicketRow(String code, String typeName, String holderName) {}

    /** Followed organizer card on the favorites "Organizers" tab. */
    public record OrgCard(String name, String handle, String bio, boolean verified,
                          String logoUrl, String initials,
                          String followersDisplay, long eventsHosted) {}

    /** One category chip in the profile interests picker. */
    public record InterestOption(String key, String label, String icon, boolean selected) {}

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
    private final OrderRepository orders;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public UserAreaController(UserService userService, TicketRepository tickets, CatalogService catalog,
                              InteractionService interactions, EventRepository events,
                              OrganizationRepository organizations, LikeCountRepository counts, QrService qr,
                              OrderRepository orders, UserRepository users, JdbcTemplate jdbc) {
        this.userService = userService;
        this.tickets = tickets;
        this.catalog = catalog;
        this.interactions = interactions;
        this.events = events;
        this.organizations = organizations;
        this.counts = counts;
        this.qr = qr;
        this.orders = orders;
        this.users = users;
        this.jdbc = jdbc;
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
        OffsetDateTime now = OffsetDateTime.now();
        record Dated(OffsetDateTime startsAt, OrderCard card) {}
        List<Dated> upcoming = new ArrayList<>();
        List<Dated> past = new ArrayList<>();
        for (Order o : orders.findByBuyerUserIdOrderByCreatedAtDesc(user.getId())) {
            Event e = o.getEvent();
            OffsetDateTime cutoff = e.getEndsAt() != null ? e.getEndsAt() : e.getStartsAt();
            Dated d = new Dated(e.getStartsAt(), toOrderCard(o, e));
            if (cutoff.isAfter(now)) upcoming.add(d);
            else past.add(d);
        }
        // Upcoming soonest-first; past most-recent-first. Sorted here — never in the template.
        upcoming.sort(Comparator.comparing(Dated::startsAt));
        past.sort(Comparator.comparing(Dated::startsAt).reversed());
        model.addAttribute("currentUser", user);
        model.addAttribute("upcomingOrders", upcoming.stream().map(Dated::card).toList());
        model.addAttribute("pastOrders", past.stream().map(Dated::card).toList());
        return "tickets";
    }

    private OrderCard toOrderCard(Order o, Event e) {
        boolean confirmed = o.getStatus() == Order.Status.CONFIRMED;
        List<String> itemLines = o.getItems().stream()
                .map(i -> i.getQuantity() + " × " + i.getTicketType().getName())
                .toList();
        List<TicketRow> rows = !confirmed ? List.of()
                : tickets.findByOrderIdOrderByIdAsc(o.getId()).stream()
                        .map(t -> new TicketRow(t.getCode(), t.getTicketType().getName(), t.getHolderName()))
                        .toList();
        // Join link is SECRET until the organizer confirms the order — null otherwise.
        String locType = e.getLocationType();
        boolean online = "ONLINE".equals(locType);
        String venueLine = online ? "Online event"
                : "TBA".equals(locType) ? "Location to be announced"
                : (e.getVenueName() == null ? "" : e.getVenueName() + ", ") + e.getCity();
        String joinUrl = confirmed && online
                && e.getOnlineUrl() != null && !e.getOnlineUrl().isBlank() ? e.getOnlineUrl() : null;
        return new OrderCard(o.getOrderCode(), e.getTitle(), e.getSlug(),
                Format.cardDateLine(e.getStartsAt()),
                venueLine,
                Format.coverTheme(e.getCategory()),
                e.getCoverImagePath() == null ? null : "/media/event-cover/" + e.getId(),
                o.getStatus().name(), statusLabel(o.getStatus()), confirmed, itemLines, rows,
                online, joinUrl);
    }

    private static String statusLabel(Order.Status s) {
        return switch (s) {
            case PENDING_CONFIRMATION -> "Pending confirmation";
            case CONFIRMED -> "Confirmed";
            case REJECTED -> "Rejected";
            case CANCELLED -> "Cancelled";
            case REFUNDED -> "Refunded";
        };
    }

    @GetMapping("/favorites")
    @Transactional(readOnly = true)
    public String favorites(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam(name = "tab", required = false) String tab,
                            Model model) {
        User user = required(principal);
        List<Long> ids = interactions.likedEventIds(user.getId());
        List<OrgCard> orgs = jdbc.query("""
                SELECT o.id, o.name, o.handle, o.bio, o.verified, o.logo_path
                FROM follows f JOIN organizations o ON o.id = f.organization_id
                WHERE f.user_id = ?
                ORDER BY o.name ASC
                """,
                (rs, i) -> {
                    long orgId = rs.getLong("id");
                    String name = rs.getString("name");
                    String logo = rs.getString("logo_path");
                    return new OrgCard(name, rs.getString("handle"), rs.getString("bio"),
                            rs.getBoolean("verified"),
                            logo == null ? null : "/media/org-logo/" + orgId,
                            initials(name),
                            Format.compactCount(counts.followersForOrganization(orgId)),
                            counts.eventsHostedForOrganization(orgId));
                },
                user.getId());
        model.addAttribute("currentUser", user);
        model.addAttribute("events", catalog.cardsForIds(ids));
        model.addAttribute("orgs", orgs);
        model.addAttribute("activeTab", "organizers".equals(tab) ? "organizers" : "events");
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
                               @AuthenticationPrincipal UserDetails principal,
                               @RequestHeader(value = "Referer", required = false) String referer) {
        User user = principal == null ? null : userService.byEmail(principal.getUsername());
        if (user == null) return "redirect:/auth/login";
        organizations.findByHandle(handle)
                .ifPresent(o -> interactions.toggleFollow(user.getId(), o.getId()));
        // Unfollow from the favorites "Organizers" tab returns there; otherwise back to the organizer page.
        if (referer != null && referer.contains("/favorites")) return "redirect:/favorites?tab=organizers";
        return "redirect:/organizers/" + handle;
    }

    @GetMapping("/me/profile")
    public String profile(@AuthenticationPrincipal UserDetails principal, Model model) {
        User user = required(principal);
        model.addAttribute("currentUser", user);
        model.addAttribute("cities", CITIES);
        model.addAttribute("interestOptions", interestOptions(user.getInterests()));
        return "profile";
    }

    private static List<InterestOption> interestOptions(String stored) {
        Set<String> selected = new LinkedHashSet<>();
        if (stored != null && !stored.isBlank()) {
            for (String part : stored.split(",")) {
                if (!part.isBlank()) selected.add(part.trim().toUpperCase());
            }
        }
        List<InterestOption> options = new ArrayList<>();
        for (Event.Category c : Event.Category.values()) {
            options.add(new InterestOption(c.name(), Format.categoryLabel(c),
                    categoryIcon(c), selected.contains(c.name())));
        }
        return options;
    }

    private static String categoryIcon(Event.Category c) {
        return switch (c) {
            case MUSIC -> "i-music";
            case TECH -> "i-laptop";
            case BUSINESS -> "i-briefcase";
            case ARTS -> "i-palette";
            case FOOD -> "i-utensils";
            case SPORTS -> "i-volleyball";
            case COMMUNITY -> "i-heart-handshake";
            case EDUCATION -> "i-graduation-cap";
            case FILM -> "i-clapperboard";
            case FAMILY -> "i-baby";
        };
    }

    @PostMapping("/me/profile")
    public String updateProfile(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam String fullName,
                                @RequestParam(required = false) String phone,
                                @RequestParam(required = false) String city,
                                RedirectAttributes redirect) {
        User user = required(principal);
        if (fullName == null || fullName.isBlank()) {
            redirect.addFlashAttribute("error", "Name cannot be empty.");
        } else {
            userService.updateProfile(user, fullName, phone);
            String cleanCity = (city == null || city.isBlank() || !CITIES.contains(city)) ? null : city;
            user.setCity(cleanCity);
            users.save(user);
            redirect.addFlashAttribute("saved", true);
        }
        return "redirect:/me/profile";
    }

    @PostMapping("/me/profile/interests")
    public String updateInterests(@AuthenticationPrincipal UserDetails principal,
                                  @RequestParam(name = "interests", required = false) List<String> interests,
                                  RedirectAttributes redirect) {
        User user = required(principal);
        List<String> clean = new ArrayList<>();
        if (interests != null) {
            for (String raw : interests) {
                if (raw == null) continue;
                try {
                    Event.Category c = Event.Category.valueOf(raw.trim().toUpperCase());
                    if (!clean.contains(c.name())) clean.add(c.name());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        user.setInterests(clean.isEmpty() ? null : String.join(",", clean));
        users.save(user);
        redirect.addFlashAttribute("saved", true);
        return "redirect:/me/profile";
    }

    @PostMapping("/me/profile/password")
    public String changePassword(@AuthenticationPrincipal UserDetails principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 RedirectAttributes redirect) {
        User user = required(principal);
        String error = userService.changePassword(user, currentPassword, newPassword);
        if (error != null) redirect.addFlashAttribute("error", error);
        else redirect.addFlashAttribute("saved", true);
        return "redirect:/me/profile";
    }

    @PostMapping("/me/profile/notifications")
    public String updateNotifications(@AuthenticationPrincipal UserDetails principal,
                                      @RequestParam(defaultValue = "false") boolean notifyEvents,
                                      @RequestParam(defaultValue = "false") boolean notifyMarketing,
                                      RedirectAttributes redirect) {
        User user = required(principal);
        userService.updateNotifications(user, notifyEvents, notifyMarketing);
        redirect.addFlashAttribute("saved", true);
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
