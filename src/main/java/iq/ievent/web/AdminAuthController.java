package iq.ievent.web;

import iq.ievent.config.SuperAdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Login/logout for the super-admin console — a single shared password from
 *  .env, not a user account. See SuperAdminAuthFilter for the actual gate. */
@Controller
public class AdminAuthController {

    private final String adminPassword;

    public AdminAuthController(@Value("${app.admin.password:}") String adminPassword) {
        this.adminPassword = adminPassword;
    }

    private static String safeNext(String next) {
        if (next == null || next.isBlank() || !next.startsWith("/admin")
                || next.startsWith("//") || next.contains("://")) {
            return "/admin";
        }
        return next;
    }

    @GetMapping("/admin/login")
    public String loginForm(@RequestParam(required = false) String next, Model model) {
        model.addAttribute("next", safeNext(next));
        return "admin/login";
    }

    @PostMapping("/admin/login")
    public String login(@RequestParam String password,
                        @RequestParam(required = false) String next,
                        HttpServletRequest request, Model model) {
        String target = safeNext(next);
        boolean configured = adminPassword != null && !adminPassword.isBlank();
        boolean match = configured
                && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                        adminPassword.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            model.addAttribute("error", true);
            model.addAttribute("next", target);
            return "admin/login";
        }
        request.getSession(true);
        request.changeSessionId(); // fixation protection: new session id post-auth
        request.getSession(false).setAttribute(SuperAdminAuthFilter.SESSION_ATTR, Boolean.TRUE);
        return "redirect:" + target;
    }

    @PostMapping("/admin/logout")
    public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/admin/login";
    }
}
