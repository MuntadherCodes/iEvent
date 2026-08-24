package iq.ievent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gates every /admin/** page behind a single shared password (SUPER_ADMIN_PASSWORD
 * in .env) rather than a user account — there's no "admin user" row, just an
 * operator-held secret. AdminAuthController sets the SUPER_ADMIN session
 * attribute after a correct password; this filter is what actually enforces it
 * on every subsequent request. /admin/** is permitAll in SecurityConfig — Spring
 * Security itself doesn't gate it, this filter does.
 */
@Component
public class SuperAdminAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTR = "SUPER_ADMIN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean underAdmin = path.equals("/admin") || path.startsWith("/admin/");
        boolean exempt = path.equals("/admin/login");
        if (underAdmin && !exempt) {
            HttpSession session = request.getSession(false);
            boolean ok = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ATTR));
            if (!ok) {
                String next = request.getRequestURI()
                        + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
                response.sendRedirect(request.getContextPath() + "/admin/login?next="
                        + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
