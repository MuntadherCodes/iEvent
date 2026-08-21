package iq.ievent.web;

import iq.ievent.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Feature flags + the signed-in user, available to every template (incl. the
 *  error page, whose render otherwise showed the navbar as signed-out). */
@ControllerAdvice
public class GlobalModelAdvice {

    private final boolean googleLoginEnabled;
    private final String mapsKey;
    private final boolean aiAvailable;
    private final boolean pexelsAvailable;
    private final UserService userService;
    private final String siteBaseUrl;

    public GlobalModelAdvice(@Value("${app.google.client-id:}") String googleClientId,
                             @Value("${app.google.maps-key:}") String mapsKey,
                             @Value("${app.openai.api-key:}") String openaiApiKey,
                             @Value("${app.pexels.api-key:}") String pexelsApiKey,
                             @Value("${app.base-url}") String siteBaseUrl,
                             UserService userService) {
        this.googleLoginEnabled = googleClientId != null && !googleClientId.isBlank();
        this.mapsKey = mapsKey == null ? "" : mapsKey;
        this.aiAvailable = openaiApiKey != null && !openaiApiKey.isBlank();
        this.pexelsAvailable = pexelsApiKey != null && !pexelsApiKey.isBlank();
        this.userService = userService;
        // Trimmed once here so every canonical/hreflang/OG URL built in templates
        // via siteBaseUrl + path doesn't end up with an accidental "//".
        this.siteBaseUrl = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
    }

    @ModelAttribute
    public void globals(@AuthenticationPrincipal UserDetails principal, Model model,
                        jakarta.servlet.http.HttpServletRequest request) {
        model.addAttribute("googleLoginEnabled", googleLoginEnabled);
        model.addAttribute("mapsKey", mapsKey.isBlank() ? null : mapsKey);
        model.addAttribute("aiAvailable", aiAvailable);
        model.addAttribute("pexelsAvailable", pexelsAvailable);
        // Controllers may overwrite this with their own lookup; this default keeps
        // pages that don't (error page, simple views) consistent for OAuth + form users.
        iq.ievent.domain.User current =
                principal == null ? null : userService.byEmail(principal.getUsername());
        model.addAttribute("currentUser", current);

        // ---- locale / RTL (Arabic-first) ----
        java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        boolean en = "en".equals(locale.getLanguage());
        // Remember the signed-in user's language (writes only on change) so
        // emails/notifications later localize to the RECIPIENT's preference.
        if (current != null) {
            try { userService.rememberLanguage(current, en ? "en" : "ar"); }
            catch (Exception ignored) { }
        }
        model.addAttribute("lang", en ? "en" : "ar");
        model.addAttribute("dir", en ? "ltr" : "rtl");
        model.addAttribute("rtl", !en);
        // Same-page language switch targets (request URI is already /en-stripped).
        // On the Boot error dispatch, prefer the URI that actually failed.
        Object failedUri = request.getAttribute("jakarta.servlet.error.request_uri");
        String path = failedUri instanceof String f && f.startsWith("/") ? f : request.getRequestURI();
        if (path.equals("/en")) path = "/";
        else if (path.startsWith("/en/")) path = path.substring(3);
        String qs = request.getQueryString();
        String qsPart = qs == null || qs.isEmpty() ? "" : "?" + qs;
        String full = path + qsPart;
        model.addAttribute("urlEn", "/en" + ("/".equals(path) ? "" : path) + qsPart);
        model.addAttribute("urlAr", "/set-lang?to=ar&next="
                + java.net.URLEncoder.encode(full, java.nio.charset.StandardCharsets.UTF_8));

        // ---- SEO: canonical / hreflang, built from the query-free path ----
        model.addAttribute("siteBaseUrl", siteBaseUrl);
        model.addAttribute("canonicalPath", path);
    }
}
