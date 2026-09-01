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
    private final iq.ievent.repo.PaymentMethodRepository paymentMethods;
    private final Path uploadDir;

    public MediaController(EventRepository events, OrganizationRepository organizations,
                           iq.ievent.repo.PaymentMethodRepository paymentMethods,
                           @org.springframework.beans.factory.annotation.Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.events = events;
        this.organizations = organizations;
        this.paymentMethods = paymentMethods;
        this.uploadDir = Path.of(uploadDir);
    }

    @GetMapping("/media/event-cover/{eventId}")
    public ResponseEntity<FileSystemResource> eventCover(@PathVariable Long eventId) {
        String stored = events.findById(eventId)
                .map(e -> e.getCoverImagePath())
                .orElse(null);
        return serve(stored);
    }

    /** Extra gallery photos uploaded from the host's desktop — see
     *  HostService.storeGalleryUploads. {@code slot} is either a legacy
     *  numeric slot or a unique alphanumeric token (R19); strictly validated
     *  since it's spliced into a filename. The extension isn't tracked
     *  anywhere, so this just tries each accepted one. */
    @GetMapping("/media/event-cover/{eventId}/extra/{slot}")
    public ResponseEntity<FileSystemResource> eventCoverExtra(@PathVariable Long eventId, @PathVariable String slot) {
        if (!slot.matches("[a-zA-Z0-9]{1,40}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path dir = uploadDir.resolve("covers");
        for (String ext : java.util.List.of("jpg", "jpeg", "png", "webp")) {
            Path candidate = dir.resolve("event-" + eventId + "-extra-" + slot + "." + ext);
            if (Files.isReadable(candidate)) return serveFile(candidate);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** User profile photo uploaded from /me/profile — one file per user,
     *  extension unknown, so each accepted one is tried (same pattern as the
     *  gallery extras above). External avatars (Google) never pass through
     *  here — users.avatar_url points straight at them. */
    @GetMapping("/media/user-avatar/{userId}")
    public ResponseEntity<FileSystemResource> userAvatar(@PathVariable Long userId) {
        Path dir = uploadDir.resolve("avatars");
        for (String ext : java.util.List.of("jpg", "jpeg", "png", "webp")) {
            Path candidate = dir.resolve("user-" + userId + "." + ext);
            if (Files.isReadable(candidate)) return serveFile(candidate);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/media/org-logo/{orgId}")
    public ResponseEntity<FileSystemResource> orgLogo(@PathVariable Long orgId) {
        return serve(organizations.findById(orgId).map(o -> o.getLogoPath()).orElse(null));
    }

    @GetMapping("/media/org-cover/{orgId}")
    public ResponseEntity<FileSystemResource> orgCover(@PathVariable Long orgId) {
        return serve(organizations.findById(orgId).map(o -> o.getCoverImagePath()).orElse(null));
    }

    @GetMapping("/media/payment-qr/{methodId}")
    public ResponseEntity<FileSystemResource> paymentQr(@PathVariable Long methodId) {
        return serve(paymentMethods.findById(methodId)
                .filter(m -> m.isEnabled())
                .map(m -> m.getQrImagePath())
                .orElse(null));
    }

    private ResponseEntity<FileSystemResource> serve(String stored) {
        if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return serveFile(Path.of(stored));
    }

    private ResponseEntity<FileSystemResource> serveFile(Path path) {
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
