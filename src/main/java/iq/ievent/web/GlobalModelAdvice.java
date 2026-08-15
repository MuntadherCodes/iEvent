package iq.ievent.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Feature flags every template can rely on. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final boolean googleLoginEnabled;
    private final String mapsKey;

    public GlobalModelAdvice(@Value("${app.google.client-id:}") String googleClientId,
                             @Value("${app.google.maps-key:}") String mapsKey) {
        this.googleLoginEnabled = googleClientId != null && !googleClientId.isBlank();
        this.mapsKey = mapsKey == null ? "" : mapsKey;
    }

    @ModelAttribute
    public void globals(Model model) {
        model.addAttribute("googleLoginEnabled", googleLoginEnabled);
        model.addAttribute("mapsKey", mapsKey.isBlank() ? null : mapsKey);
    }
}
