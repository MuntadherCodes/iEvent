package iq.ievent.web;

import iq.ievent.service.CatalogService;
import iq.ievent.service.UserService;
import iq.ievent.web.dto.Views.EventCard;
import iq.ievent.web.dto.Views.EventDetail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class PageController {

    /** Category filter options shown on browse; value ↔ Event.Category name. */
    public record CategoryOption(String value, String label) {}

    public static final List<CategoryOption> CATEGORIES = List.of(
            new CategoryOption("MUSIC", "Music"),
            new CategoryOption("TECH", "Tech"),
            new CategoryOption("BUSINESS", "Business"),
            new CategoryOption("ARTS", "Arts & Culture"),
            new CategoryOption("FOOD", "Food & Drink"),
            new CategoryOption("SPORTS", "Sports"),
            new CategoryOption("COMMUNITY", "Community"),
            new CategoryOption("EDUCATION", "Education"),
            new CategoryOption("FILM", "Film & Media"),
            new CategoryOption("FAMILY", "Family"));

    private final CatalogService catalog;
    private final UserService userService;
    private final iq.ievent.service.InteractionService interactions;
    private final iq.ievent.repo.EventRepository events;

    public PageController(CatalogService catalog, UserService userService,
                          iq.ievent.service.InteractionService interactions,
                          iq.ievent.repo.EventRepository events) {
        this.catalog = catalog;
        this.userService = userService;
        this.interactions = interactions;
        this.events = events;
    }

    @ModelAttribute
    public void currentUser(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("currentUser",
                principal == null ? null : userService.byEmail(principal.getUsername()));
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("weekendEvents", catalog.upcomingThisWeek(4));
        model.addAttribute("trendingEvents", catalog.trending(8));
        model.addAttribute("cities", catalog.liveCities());
        model.addAttribute("categories", CATEGORIES);
        return "index";
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String category,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false, defaultValue = "false") boolean free,
                         @RequestParam(required = false) String when,
                         @RequestParam(required = false, defaultValue = "0") int page,
                         Model model) {
        int safePage = Math.max(0, page);
        String safeWhen = when == null || !java.util.List.of("today", "weekend", "week").contains(when)
                ? "" : when;
        Page<EventCard> results = catalog.search(q, category, city, free,
                safeWhen.isEmpty() ? null : safeWhen, PageRequest.of(safePage, 12));
        model.addAttribute("results", results);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("selectedCategory", category == null ? "" : category);
        model.addAttribute("selectedCity", city == null ? "" : city);
        model.addAttribute("freeOnly", free);
        model.addAttribute("selectedWhen", safeWhen);
        model.addAttribute("cities", catalog.liveCities());
        model.addAttribute("categories", CATEGORIES);
        return "browse";
    }

    @GetMapping("/events/{slug}")
    public String event(@PathVariable String slug,
                        @AuthenticationPrincipal UserDetails principal,
                        Model model) {
        EventDetail detail = catalog.eventDetail(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        boolean liked = false;
        if (principal != null) {
            var user = userService.byEmail(principal.getUsername());
            liked = user != null && events.findBySlug(slug)
                    .map(e -> interactions.isLiked(user.getId(), e.getId()))
                    .orElse(false);
        }
        model.addAttribute("event", detail);
        model.addAttribute("liked", liked);
        model.addAttribute("related", catalog.related(slug, 3));
        return "event";
    }
}
