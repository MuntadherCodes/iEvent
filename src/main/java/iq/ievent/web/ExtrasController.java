package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.service.Format;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Calendar (.ics) downloads, short event links for the embed widget, newsletter signup. */
@Controller
public class ExtrasController {

    private static final DateTimeFormatter ICS_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final EventRepository events;
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    public ExtrasController(EventRepository events, OrderRepository orders, JdbcTemplate jdbc) {
        this.events = events;
        this.orders = orders;
        this.jdbc = jdbc;
    }

    /** Short link used by the embeddable widget and social shares. */
    @GetMapping("/e/{slug}")
    public String shortLink(@PathVariable String slug) {
        return "redirect:/events/" + slug;
    }

    @GetMapping("/events/{slug}/calendar.ics")
    public ResponseEntity<byte[]> eventIcs(@PathVariable String slug) {
        Event e = events.findBySlug(slug)
                .filter(ev -> ev.getStatus() == Event.Status.LIVE || ev.getStatus() == Event.Status.ENDED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String ics = buildIcs(e);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + e.getSlug() + ".ics\"")
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/newsletter")
    public String newsletter(@RequestParam String email,
                             @RequestParam(required = false) String city,
                             @RequestHeader(value = "Referer", required = false) String referer) {
        String clean = email == null ? "" : email.trim().toLowerCase();
        if (!clean.isBlank() && clean.contains("@") && clean.length() <= 255) {
            jdbc.update("""
                    INSERT INTO newsletter_subscribers (email, city) VALUES (?, ?)
                    ON CONFLICT (lower(email)) DO NOTHING
                    """, clean, city == null || city.isBlank() ? null : city);
        }
        String back = referer != null && referer.contains("/browse") ? "/browse" : "/";
        return "redirect:" + back + "?subscribed";
    }

    private static String buildIcs(Event e) {
        OffsetDateTime start = e.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = (e.getEndsAt() == null ? e.getStartsAt().plusHours(3) : e.getEndsAt())
                .withOffsetSameInstant(ZoneOffset.UTC);
        String location = (e.getVenueName() == null ? "" : e.getVenueName() + ", ") + e.getCity();
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n")
          .append("VERSION:2.0\r\n")
          .append("PRODID:-//iEvent//ievent.iq//EN\r\n")
          .append("BEGIN:VEVENT\r\n")
          .append("UID:").append(e.getSlug()).append("@ievent.iq\r\n")
          .append("DTSTAMP:").append(ICS_STAMP.format(OffsetDateTime.now(ZoneOffset.UTC))).append("\r\n")
          .append("DTSTART:").append(ICS_STAMP.format(start)).append("\r\n")
          .append("DTEND:").append(ICS_STAMP.format(end)).append("\r\n")
          .append("SUMMARY:").append(icsEscape(e.getTitle())).append("\r\n")
          .append("LOCATION:").append(icsEscape(location)).append("\r\n")
          .append("DESCRIPTION:").append(icsEscape("Tickets & details: https://ievent.iq/events/" + e.getSlug()))
          .append("\r\n")
          .append("END:VEVENT\r\n")
          .append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String icsEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }
}
