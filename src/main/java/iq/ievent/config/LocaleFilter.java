package iq.ievent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Arabic-first URL scheme:
 *   /...      → Arabic (site default)
 *   /en/...   → English (prefix is stripped here; controllers never see it)
 *   /set-lang?to=ar|en&next=/path → switches the language cookie and redirects.
 *
 * A "lang" cookie remembers the choice so redirects coming out of controllers
 * (which are always un-prefixed) bounce English users back onto /en/... on the
 * next GET. The resolved locale is exposed as the APP_LOCALE request attribute
 * (read by the LocaleResolver) and on LocaleContextHolder.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LocaleFilter extends OncePerRequestFilter {

    public static final String LOCALE_ATTR = "APP_LOCALE";
    public static final Locale ARABIC = new Locale("ar");
    public static final Locale ENGLISH = Locale.ENGLISH;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // --- language switcher endpoint ---
        if (path.equals("/set-lang")) {
            String to = "en".equals(request.getParameter("to")) ? "en" : "ar";
            String next = safePath(request.getParameter("next"));
            setLangCookie(response, to);
            response.sendRedirect("en".equals(to) ? "/en" + ("/".equals(next) ? "" : next) : next);
            return;
        }

        // --- explicit English prefix ---
        if (path.equals("/en") || path.startsWith("/en/")) {
            String stripped = path.length() <= 3 ? "/" : path.substring(3);
            if (!"en".equals(cookieLang(request))) setLangCookie(response, "en");
            serve(request, response, chain, stripped, ENGLISH);
            return;
        }

        // --- un-prefixed path: English cookie holders get bounced onto /en on page GETs ---
        if ("en".equals(cookieLang(request)) && "GET".equalsIgnoreCase(request.getMethod())
                && isPagePath(path)) {
            String qs = request.getQueryString();
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", "/en" + ("/".equals(path) ? "" : path)
                    + (qs == null || qs.isEmpty() ? "" : "?" + qs));
            return;
        }

        serve(request, response, chain, null, ARABIC);
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                       String strippedPath, Locale locale) throws ServletException, IOException {
        HttpServletRequest effective = request;
        if (strippedPath != null) {
            // getRequestURI() stays percent-encoded (matches how Tomcat provides it on a
            // normal, un-wrapped request) but getServletPath() must be decoded — per the
            // servlet spec it's never percent-encoded. Leaving both encoded (as this used
            // to) makes Spring Security's StrictHttpFirewall see a "%" in getServletPath()
            // it doesn't expect and reject the request as a potential double-encoding
            // attack (RequestRejectedException: "potentially malicious String %") — this
            // only ever showed up for slugs containing non-ASCII (e.g. Arabic) characters,
            // since ASCII paths have nothing left to decode either way.
            final String encodedPath = strippedPath;
            final String decodedPath = org.springframework.web.util.UriUtils.decode(
                    encodedPath, java.nio.charset.StandardCharsets.UTF_8);
            effective = new HttpServletRequestWrapper(request) {
                @Override public String getRequestURI() { return encodedPath; }
                @Override public String getServletPath() { return decodedPath; }
                @Override public StringBuffer getRequestURL() {
                    StringBuffer url = new StringBuffer();
                    url.append(getScheme()).append("://").append(getServerName());
                    int port = getServerPort();
                    if (port != 80 && port != 443) url.append(':').append(port);
                    return url.append(encodedPath);
                }
            };
        }
        effective.setAttribute(LOCALE_ATTR, locale);
        Locale previous = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(locale);
        try {
            chain.doFilter(effective, response);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    private static String cookieLang(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("lang".equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static void setLangCookie(HttpServletResponse response, String lang) {
        Cookie cookie = new Cookie("lang", lang);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 365);
        cookie.setHttpOnly(false); // harmless preference, readable client-side
        response.addCookie(cookie);
    }

    /** Only page navigations get language-redirected — never assets or APIs. */
    private static boolean isPagePath(String path) {
        // Root-level static files (sw.js, site.webmanifest under /img/favicon/ are
        // already caught by the /img/ prefix, but sw.js MUST live at the origin
        // root for its scope to cover the whole site) can't be redirected at all —
        // a service worker registration is required by spec to fail outright if its
        // script URL resolves via a redirect, so bouncing /sw.js to /en/sw.js would
        // silently break installability for every English-cookie visitor.
        return !(path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/")
                || path.startsWith("/media/") || path.startsWith("/api/")
                || path.startsWith("/actuator/") || path.equals("/favicon.ico")
                || path.endsWith(".png") || path.endsWith(".pdf") || path.endsWith(".ics")
                || path.endsWith(".csv") || path.endsWith(".js") || path.endsWith(".webmanifest")
                || path.endsWith(".ico") || path.endsWith(".svg") || path.equals("/error"));
    }

    private static String safePath(String p) {
        if (p == null || p.isBlank() || !p.startsWith("/") || p.startsWith("//") || p.contains("://")
                || p.contains("\\")) {
            return "/";
        }
        return p.startsWith("/en/") ? p.substring(3) : (p.equals("/en") ? "/" : p);
    }
}
