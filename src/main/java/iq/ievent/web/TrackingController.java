package iq.ievent.web;

import iq.ievent.service.TrackingService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Public short-link redirect: /l/{code} → the event page, counting the click. */
@Controller
public class TrackingController {

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/l/{code}")
    public String follow(@PathVariable String code) {
        return tracking.resolveAndCount(code)
                .map(slug -> "redirect:/events/" + slug + "?via=" + code)
                .orElse("redirect:/browse");
    }
}
