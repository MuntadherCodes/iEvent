package iq.ievent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small in-memory throttle for the endpoints an attacker or a bot would hammer:
 * password guessing, reset-mail floods, admin password guessing, contact and
 * newsletter spam, and scripted checkouts. Fixed one-minute windows keyed by
 * client IP (ForwardedHeaderFilter has already resolved X-Forwarded-For when
 * the app sits behind Caddy). Deliberately simple: a single JVM, no external
 * store, and limits generous enough that a real person never meets them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class RateLimitFilter extends OncePerRequestFilter {

    /** path prefix (after an optional /en) → requests allowed per IP per minute. */
    private static final Map<String, Integer> LIMITS = Map.of(
            "/auth/login", 10,
            "/auth/register", 10,
            "/auth/forgot", 5,
            "/auth/reset", 10,
            "/admin/login", 5,
            "/contact", 5,
            "/newsletter", 5
    );
    private static final int CHECKOUT_PER_MINUTE = 20;

    private record Window(long minute, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep = 0;
    private final boolean enabled;

    /** RATE_LIMIT_ENABLED=false switches the throttle off (CI and automated
     *  walkthroughs sign in dozens of times a minute from one address). */
    public RateLimitFilter(@org.springframework.beans.factory.annotation.Value("${app.security.rate-limit:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = RequestPaths.appPath(request);
        if (path.startsWith("/en/")) path = path.substring(3);
        Integer limit = LIMITS.get(path);
        if (limit == null && path.startsWith("/e/") && path.endsWith("/checkout")) limit = CHECKOUT_PER_MINUTE;
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        String key = clientIp(request) + "|" + path;
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        int n = w.count().incrementAndGet();
        sweep(minute);
        if (n > limit) {
            response.setHeader("Retry-After", "60");
            response.sendError(429, "Too many requests, please wait a minute and try again.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "?" : ip;
    }

    /** Drop stale windows now and then so the map never grows without bound. */
    private void sweep(long minute) {
        if (minute == lastSweep) return;
        lastSweep = minute;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute - 1);
    }
}
