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

    public NotificationController(NotificationService notifications, UserService userService) {
        this.notifications = notifications;
        this.userService = userService;
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
    public String open(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        User u = current(principal);
        if (u == null) return "redirect:/auth/login";
        return "redirect:" + notifications.open(u.getId(), id).orElse("/me/notifications");
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
