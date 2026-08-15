package iq.ievent.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Read-side helper for like/follow counts (no entity needed in V1). */
@Repository
public class LikeCountRepository {

    private final JdbcTemplate jdbc;

    public LikeCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, Long> likesForEvents(Collection<Long> eventIds) {
        Map<Long, Long> out = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return out;
        String placeholders = String.join(",", eventIds.stream().map(id -> "?").toList());
        jdbc.query(
                "SELECT event_id, count(*) FROM event_likes WHERE event_id IN (" + placeholders + ") GROUP BY event_id",
                rs -> { out.put(rs.getLong(1), rs.getLong(2)); },
                eventIds.toArray());
        return out;
    }

    public long followersForOrganization(long organizationId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM follows WHERE organization_id = ?", Long.class, organizationId);
        return n == null ? 0 : n;
    }

    public long eventsHostedForOrganization(long organizationId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM events WHERE organization_id = ?", Long.class, organizationId);
        return n == null ? 0 : n;
    }
}
