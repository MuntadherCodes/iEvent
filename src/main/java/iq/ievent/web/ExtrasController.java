package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Ticket;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.service.QrService;
import iq.ievent.service.TicketPdfService;
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
    private final TicketRepository tickets;
    private final JdbcTemplate jdbc;
    private final QrService qr;
    private final TicketPdfService pdf;

    public ExtrasController(EventRepository events, OrderRepository orders, TicketRepository tickets,
                            JdbcTemplate jdbc, QrService qr, TicketPdfService pdf) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.jdbc = jdbc;
        this.qr = qr;
        this.pdf = pdf;
    }

    /** QR image download for a confirmed ticket (access = knowing the unguessable code). */
    @GetMapping("/t/{code}/qr.png")
    public ResponseEntity<byte[]> ticketQrPng(@PathVariable String code) {
        Ticket t = tickets.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ievent-ticket-" + t.getCode() + ".png\"")
                .body(qr.ticketQrPng(t.getCode()));
    }

    /** Printable single-ticket PDF. Transactional: the PDF renderer walks the
     *  ticket's LAZY event/ticketType associations and OSIV is off — without a
     *  transaction this 500'd with LazyInitializationException. */
    @GetMapping("/t/{code}/ticket.pdf")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<byte[]> ticketPdf(@PathVariable String code) {
        Ticket t = tickets.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ievent-ticket-" + t.getCode() + ".pdf\"")
                .body(pdf.ticketsPdf(java.util.List.of(t)));
    }

    /** Legacy path kept working for any already-shared /events/{slug} links.
     *  Slugs are Arabic by default — must be path-segment-encoded, or
     *  sendRedirect throws on the non-Latin1 characters in the raw string. */
    @GetMapping("/events/{slug}")
    public String legacyEventLink(@PathVariable String slug) {
        return "redirect:/e/" + org.springframework.web.util.UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
    }

    @GetMapping("/e/{slug}/calendar.ics")
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
          .append("DESCRIPTION:").append(icsEscape("Tickets & details: https://ievent.iq/e/" + e.getSlug()))
          .append("\r\n")
          .append("END:VEVENT\r\n")
          .append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String icsEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }
}
