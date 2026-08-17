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
    private final UserService userService;

    public GlobalModelAdvice(@Value("${app.google.client-id:}") String googleClientId,
                             @Value("${app.google.maps-key:}") String mapsKey,
                             UserService userService) {
        this.googleLoginEnabled = googleClientId != null && !googleClientId.isBlank();
        this.mapsKey = mapsKey == null ? "" : mapsKey;
        this.userService = userService;
    }

    @ModelAttribute
    public void globals(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("googleLoginEnabled", googleLoginEnabled);
        model.addAttribute("mapsKey", mapsKey.isBlank() ? null : mapsKey);
        // Controllers may overwrite this with their own lookup; this default keeps
        // pages that don't (error page, simple views) consistent for OAuth + form users.
        model.addAttribute("currentUser",
                principal == null ? null : userService.byEmail(principal.getUsername()));
    }
}
