package iq.ievent.web;

import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Serves uploaded event cover images from the uploads volume (public). */
@Controller
public class MediaController {

    private final EventRepository events;
    private final OrganizationRepository organizations;

    public MediaController(EventRepository events, OrganizationRepository organizations) {
        this.events = events;
        this.organizations = organizations;
    }

    @GetMapping("/media/event-cover/{eventId}")
    public ResponseEntity<FileSystemResource> eventCover(@PathVariable Long eventId) {
        String stored = events.findById(eventId)
                .map(e -> e.getCoverImagePath())
                .orElse(null);
        if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = Path.of(stored);
        if (!Files.isReadable(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        MediaType type = MediaType.IMAGE_JPEG;
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) type = MediaType.IMAGE_PNG;
        else if (name.endsWith(".webp")) type = MediaType.parseMediaType("image/webp");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new FileSystemResource(path));
    }

    @GetMapping("/media/org-logo/{orgId}")
    public ResponseEntity<FileSystemResource> orgLogo(@PathVariable Long orgId) {
        String stored = organizations.findById(orgId)
                .map(o -> o.getLogoPath())
                .orElse(null);
        if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = Path.of(stored);
        if (!Files.isReadable(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        MediaType type = MediaType.IMAGE_JPEG;
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) type = MediaType.IMAGE_PNG;
        else if (name.endsWith(".webp")) type = MediaType.parseMediaType("image/webp");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new FileSystemResource(path));
    }
}
