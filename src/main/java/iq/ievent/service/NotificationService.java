package iq.ievent.service;

import iq.ievent.domain.Notification;
import iq.ievent.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * In-app notification center. Creating a notification must NEVER break the
 * business action that triggered it, so {@link #notify} swallows failures and
 * runs in its own transaction.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long userId, String type, String title, String body, String url) {
        if (userId == null) return;
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(title == null ? "" : title.substring(0, Math.min(200, title.length())));
            n.setBody(body == null ? null : body.substring(0, Math.min(400, body.length())));
            n.setUrl(url == null ? null : url.substring(0, Math.min(300, url.length())));
            notifications.save(n);
        } catch (Exception e) {
            log.error("Notification create failed for user {}", userId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(Long userId) {
        return notifications.findTop15ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Notification> all(Long userId) {
        return notifications.findTop100ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** Marks read and returns the click-through URL (empty if not this user's). */
    @Transactional
    public Optional<String> open(Long userId, Long notificationId) {
        return notifications.findByIdAndUserId(notificationId, userId)
                .map(n -> {
                    if (n.getReadAt() == null) {
                        n.setReadAt(java.time.OffsetDateTime.now());
                        notifications.save(n);
                    }
                    return n.getUrl() == null || n.getUrl().isBlank() ? "/me/notifications" : n.getUrl();
                });
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId);
    }

    @Transactional
    public void clearAll(Long userId) {
        notifications.clearAll(userId);
    }
}
