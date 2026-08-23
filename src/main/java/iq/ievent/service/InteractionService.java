package iq.ievent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Likes (saved events) and organizer follows. */
@Service
public class InteractionService {

    private final JdbcTemplate jdbc;

    public InteractionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean toggleLike(long userId, long eventId) {
        int removed = jdbc.update("DELETE FROM event_likes WHERE user_id = ? AND event_id = ?", userId, eventId);
        if (removed > 0) return false;
        jdbc.update("INSERT INTO event_likes (user_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, eventId);
        return true;
    }

    public boolean isLiked(long userId, long eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_likes WHERE user_id = ? AND event_id = ?",
                Integer.class, userId, eventId);
        return n != null && n > 0;
    }

    public List<Long> likedEventIds(long userId) {
        return jdbc.queryForList(
                "SELECT event_id FROM event_likes WHERE user_id = ? ORDER BY created_at DESC",
                Long.class, userId);
    }

    /** Slugs (not ids) since event cards everywhere key their like button off
     *  the slug — lets any page mark its cards as already-liked in one small
     *  fetch on load instead of the server needing to precompute "liked" on
     *  every card list (home/browse/related/etc. all share one code path). */
    public List<String> likedEventSlugs(long userId) {
        return jdbc.queryForList(
                "SELECT e.slug FROM event_likes l JOIN events e ON e.id = l.event_id WHERE l.user_id = ? ORDER BY l.created_at DESC",
                String.class, userId);
    }

    @Transactional
    public boolean toggleFollow(long userId, long organizationId) {
        int removed = jdbc.update("DELETE FROM follows WHERE user_id = ? AND organization_id = ?",
                userId, organizationId);
        if (removed > 0) return false;
        jdbc.update("INSERT INTO follows (user_id, organization_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, organizationId);
        return true;
    }

    public boolean isFollowing(long userId, long organizationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE user_id = ? AND organization_id = ?",
                Integer.class, userId, organizationId);
        return n != null && n > 0;
    }
}
