package iq.ievent.repo;

import iq.ievent.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findBySlug(String slug);

    List<Event> findByOrganizationIdOrderByStartsAtDesc(Long organizationId);

    List<Event> findByOrganizationIdAndStatusAndAdminHiddenFalseAndStartsAtAfterOrderByStartsAtAsc(
            Long organizationId, Event.Status status, OffsetDateTime after, Pageable pageable);

    @Query("""
           select e from Event e
           where e.status = :status
             and e.visibility = 'PUBLIC'
             and e.adminHidden = false
             and e.organization.disabled = false
             and e.startsAt between :from and :to
           order by e.startsAt asc
           """)
    List<Event> findUpcomingWindow(@Param("status") Event.Status status,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   Pageable pageable);

    @Query(value = """
           SELECT e.* FROM events e
           JOIN organizations o ON o.id = e.organization_id
           LEFT JOIN (SELECT event_id, count(*) AS n FROM event_likes GROUP BY event_id) l
                  ON l.event_id = e.id
           WHERE e.status = 'LIVE' AND e.visibility = 'PUBLIC' AND e.starts_at > now()
             AND e.admin_hidden = false AND o.disabled = false
           ORDER BY COALESCE(l.n, 0) DESC, e.starts_at ASC
           """,
           nativeQuery = true)
    List<Event> findTrending(Pageable pageable);

    @Query(value = """
           SELECT e.* FROM events e
           JOIN organizations o ON o.id = e.organization_id
           LEFT JOIN (SELECT event_id, count(*) AS n FROM event_likes GROUP BY event_id) l
                  ON l.event_id = e.id
           LEFT JOIN (SELECT event_id, MIN(price_iqd) AS p FROM ticket_types
                       WHERE status = 'ON_SALE' GROUP BY event_id) mp
                  ON mp.event_id = e.id
           WHERE e.status = 'LIVE' AND e.visibility = 'PUBLIC'
             AND e.admin_hidden = false AND o.disabled = false
             AND (:q IS NULL OR lower(e.title) LIKE lower(CONCAT('%', CAST(:q AS text), '%')))
             AND (:category IS NULL OR e.category = CAST(:category AS text))
             AND (:city IS NULL OR e.city = CAST(:city AS text))
             AND (:freeOnly = FALSE OR COALESCE(mp.p, 0) = 0)
             AND (:paidOnly = FALSE OR COALESCE(mp.p, 0) > 0)
             AND (CAST(:fromTs AS timestamptz) IS NULL OR e.starts_at >= CAST(:fromTs AS timestamptz))
             AND (CAST(:toTs AS timestamptz) IS NULL OR e.starts_at <= CAST(:toTs AS timestamptz))
           ORDER BY
             CASE WHEN CAST(:sort AS text) = 'popular' THEN COALESCE(l.n, 0) END DESC,
             CASE WHEN CAST(:sort AS text) = 'price' THEN COALESCE(mp.p, 0) END ASC,
             e.starts_at ASC
           """,
           countQuery = """
           SELECT count(*) FROM events e
           JOIN organizations o ON o.id = e.organization_id
           LEFT JOIN (SELECT event_id, MIN(price_iqd) AS p FROM ticket_types
                       WHERE status = 'ON_SALE' GROUP BY event_id) mp
                  ON mp.event_id = e.id
           WHERE e.status = 'LIVE' AND e.visibility = 'PUBLIC'
             AND e.admin_hidden = false AND o.disabled = false
             AND (:q IS NULL OR lower(e.title) LIKE lower(CONCAT('%', CAST(:q AS text), '%')))
             AND (:category IS NULL OR e.category = CAST(:category AS text))
             AND (:city IS NULL OR e.city = CAST(:city AS text))
             AND (:freeOnly = FALSE OR COALESCE(mp.p, 0) = 0)
             AND (:paidOnly = FALSE OR COALESCE(mp.p, 0) > 0)
             AND (CAST(:fromTs AS timestamptz) IS NULL OR e.starts_at >= CAST(:fromTs AS timestamptz))
             AND (CAST(:toTs AS timestamptz) IS NULL OR e.starts_at <= CAST(:toTs AS timestamptz))
           """,
           nativeQuery = true)
    Page<Event> search(@Param("q") String q,
                       @Param("category") String category,
                       @Param("city") String city,
                       @Param("freeOnly") boolean freeOnly,
                       @Param("paidOnly") boolean paidOnly,
                       @Param("fromTs") OffsetDateTime fromTs,
                       @Param("toTs") OffsetDateTime toTs,
                       @Param("sort") String sort,
                       Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE events SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
    void incrementViewCount(@Param("id") long id);

    @Query(value = """
           SELECT e.* FROM events e
           JOIN organizations o ON o.id = e.organization_id
           WHERE e.status = 'LIVE' AND e.visibility = 'PUBLIC' AND e.id <> :excludeId AND e.starts_at > now()
             AND e.admin_hidden = false AND o.disabled = false
             AND (e.category = CAST(:category AS text) OR e.city = CAST(:city AS text))
           ORDER BY (e.category = CAST(:category AS text)) DESC, e.starts_at ASC
           """,
           nativeQuery = true)
    List<Event> findRelated(@Param("excludeId") long excludeId,
                            @Param("category") String category,
                            @Param("city") String city,
                            Pageable pageable);

    @Query(value = """
           SELECT e.city AS city, count(*) AS n FROM events e
           JOIN organizations o ON o.id = e.organization_id
           WHERE e.status = 'LIVE' AND e.admin_hidden = false AND o.disabled = false
           GROUP BY e.city ORDER BY n DESC
           """,
           nativeQuery = true)
    List<CityCountRow> countLiveByCity();

    interface CityCountRow {
        String getCity();
        long getN();
    }
}
