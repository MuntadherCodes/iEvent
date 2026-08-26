package iq.ievent.web;

import iq.ievent.service.HostService;
import iq.ievent.service.TeamService;
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
    private final boolean translateAvailable;
    private final String gaId;
    private final String googleSiteVerification;
    private final UserService userService;
    private final HostService hostService;
    private final String siteBaseUrl;

    public GlobalModelAdvice(@Value("${app.google.client-id:}") String googleClientId,
                             @Value("${app.google.maps-key:}") String mapsKey,
                             @Value("${app.openai.api-key:}") String openaiApiKey,
                             @Value("${app.pexels.api-key:}") String pexelsApiKey,
                             @Value("${app.google.translate-api-key:}") String translateApiKey,
                             @Value("${app.google.analytics-id:}") String gaId,
                             @Value("${app.google.site-verification:}") String googleSiteVerification,
                             @Value("${app.base-url}") String siteBaseUrl,
                             UserService userService,
                             HostService hostService) {
        this.googleLoginEnabled = googleClientId != null && !googleClientId.isBlank();
        this.mapsKey = mapsKey == null ? "" : mapsKey;
        this.aiAvailable = openaiApiKey != null && !openaiApiKey.isBlank();
        this.pexelsAvailable = pexelsApiKey != null && !pexelsApiKey.isBlank();
        this.translateAvailable = translateApiKey != null && !translateApiKey.isBlank();
        this.gaId = gaId == null ? "" : gaId.trim();
        this.googleSiteVerification = googleSiteVerification == null ? "" : googleSiteVerification.trim();
        this.userService = userService;
        this.hostService = hostService;
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
        model.addAttribute("translateAvailable", translateAvailable);
        model.addAttribute("gaId", gaId.isBlank() ? null : gaId);
        model.addAttribute("googleSiteVerification", googleSiteVerification.isBlank() ? null : googleSiteVerification);
        model.addAttribute("feeWaived", iq.ievent.service.Format.BOOKING_FEE_WAIVED);
        // Controllers may overwrite this with their own lookup; this default keeps
        // pages that don't (error page, simple views) consistent for OAuth + form users.
        iq.ievent.domain.User current =
                principal == null ? null : userService.byEmail(principal.getUsername());
        model.addAttribute("currentUser", current);
        // Nav CTA text (top pill, drawer list item, bottom button) switches
        // "Host an event" → "Host console" once the account is a host — set on
        // creating an org (HostService) or being invited onto one (TeamService),
        // same signal event.html's own rail already keyed off locally.
        boolean isHostRole = current != null
                && (current.getRole() == iq.ievent.domain.User.Role.HOST
                        || current.getRole() == iq.ievent.domain.User.Role.ADMIN);
        model.addAttribute("isHost", isHostRole);
        // Sidebar nav visibility per the team role matrix — gated here (rather
        // than per-controller) so a link never dangles into a 403 for whoever
        // is looking at it. Only looked up for hosts, since regular buyer
        // accounts never see the host sidebar anyway.
        var access = isHostRole ? hostService.accessOf(current) : java.util.Optional.<TeamService.Access>empty();
        model.addAttribute("isOrgOwner", access.map(TeamService.Access::owner).orElse(false));
        model.addAttribute("isOrgManager", access.map(TeamService.Access::canManage).orElse(false));

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
