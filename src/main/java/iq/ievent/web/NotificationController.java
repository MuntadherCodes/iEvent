package iq.ievent.web;

import iq.ievent.domain.Notification;
import iq.ievent.domain.User;
import iq.ievent.service.Format;
import iq.ievent.service.NotificationService;
import iq.ievent.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * In-app notification center: dropdown polling endpoint + full page +
 * mark-read / mark-all / clear actions. All routes require authentication
 * (anyRequest().authenticated() covers /me/** and /api/**).
 */
@Controller
public class NotificationController {

    public record Item(Long id, String type, String title, String body,
                       String timeAgo, boolean unread) {}

    private final NotificationService notifications;
    private final UserService userService;

    private final String baseUrl;
    private final iq.ievent.service.NotificationStream notificationStream;

    public NotificationController(NotificationService notifications, UserService userService,
                                  iq.ievent.service.NotificationStream notificationStream,
                                  @org.springframework.beans.factory.annotation.Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.notificationStream = notificationStream;
        this.notifications = notifications;
        this.userService = userService;
    }

    /** Live channel for the bell: one Server-Sent-Events stream per open tab (R31 #9). */
    @GetMapping(value = "/api/notifications/stream",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @AuthenticationPrincipal UserDetails principal) {
        User u = current(principal);
        if (u == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED);
        return notificationStream.subscribe(u.getId());
    }

    /** Polled by the navbar bell every ~30s. */
    @GetMapping("/api/notifications/summary")
    @ResponseBody
    public Map<String, Object> summary(@AuthenticationPrincipal UserDetails principal) {
        User u = current(principal);
        if (u == null) return Map.of("unread", 0, "items", List.of());
        List<Item> items = notifications.recent(u.getId()).stream().map(this::toItem).toList();
        return Map.of("unread", notifications.unreadCount(u.getId()), "items", items);
    }

    @GetMapping("/me/notifications")
    public String page(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = current(principal);
        if (u == null) return "redirect:/auth/login";
        model.addAttribute("currentUser", u);
        model.addAttribute("items", notifications.all(u.getId()).stream().map(this::toItem).toList());
        model.addAttribute("unread", notifications.unreadCount(u.getId()));
        return "notifications";
    }

    /** Click-through: marks the notification read, then redirects to its target. */
    @GetMapping("/me/notifications/go/{id}")
    public org.springframework.http.ResponseEntity<Void> open(@PathVariable Long id,
                                                              @AuthenticationPrincipal UserDetails principal) {
        User u = current(principal);
        String target = u == null ? "/auth/login"
                : notifications.open(u.getId(), id).orElse("/me/notifications");
        // Only same-site targets: relative paths as stored, or our own absolute
        // base URL stripped back to a path. Anything else falls back to the list.
        if (target.startsWith("http://") || target.startsWith("https://")) {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            target = target.startsWith(base) ? target.substring(base.length()) : "/me/notifications";
            if (target.isEmpty()) target = "/";
        }
        if (!target.startsWith("/")) target = "/" + target;
        // Explicit 303 + Location (built and encoded ourselves) rather than a view
        // name, so nothing between here and the browser can swallow the redirect.
        java.net.URI location = org.springframework.web.util.UriComponentsBuilder
                .fromUriString(target).build().encode().toUri();
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.SEE_OTHER)
                .location(location).build();
    }

    @PostMapping("/me/notifications/read-all")
    public String readAll(@AuthenticationPrincipal UserDetails principal,
                          @RequestHeader(value = "Referer", required = false) String referer) {
        User u = current(principal);
        if (u != null) notifications.markAllRead(u.getId());
        return "redirect:" + (referer == null ? "/me/notifications" : referer);
    }

    @PostMapping("/me/notifications/clear")
    public String clear(@AuthenticationPrincipal UserDetails principal,
                        @RequestHeader(value = "Referer", required = false) String referer) {
        User u = current(principal);
        if (u != null) notifications.clearAll(u.getId());
        return "redirect:" + (referer == null ? "/me/notifications" : referer);
    }

    private Item toItem(Notification n) {
        return new Item(n.getId(), n.getType(), n.getTitle(),
                n.getBody() == null ? "" : n.getBody(),
                Format.timeAgo(n.getCreatedAt()), n.isUnread());
    }

    private User current(UserDetails principal) {
        return principal == null ? null : userService.byEmail(principal.getUsername());
    }
}
