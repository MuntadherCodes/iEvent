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

    List<Event> findByOrganizationIdAndStatusAndStartsAtAfterOrderByStartsAtAsc(
            Long organizationId, Event.Status status, OffsetDateTime after, Pageable pageable);

    @Query("""
           select e from Event e
           where e.status = :status
             and e.startsAt between :from and :to
           order by e.startsAt asc
           """)
    List<Event> findUpcomingWindow(@Param("status") Event.Status status,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   Pageable pageable);

    @Query(value = """
           SELECT e.* FROM events e
           LEFT JOIN (SELECT event_id, count(*) AS n FROM event_likes GROUP BY event_id) l
                  ON l.event_id = e.id
           WHERE e.status = 'LIVE' AND e.starts_at > now()
           ORDER BY COALESCE(l.n, 0) DESC, e.starts_at ASC
           """,
           nativeQuery = true)
    List<Event> findTrending(Pageable pageable);

    @Query(value = """
           SELECT e.* FROM events e
           WHERE e.status = 'LIVE'
             AND (:q IS NULL OR lower(e.title) LIKE lower(CONCAT('%', CAST(:q AS text), '%')))
             AND (:category IS NULL OR e.category = CAST(:category AS text))
             AND (:city IS NULL OR e.city = CAST(:city AS text))
             AND (:freeOnly = FALSE OR COALESCE(
                   (SELECT MIN(tt.price_iqd) FROM ticket_types tt
                     WHERE tt.event_id = e.id AND tt.status = 'ON_SALE'), 0) = 0)
           ORDER BY e.starts_at ASC
           """,
           countQuery = """
           SELECT count(*) FROM events e
           WHERE e.status = 'LIVE'
             AND (:q IS NULL OR lower(e.title) LIKE lower(CONCAT('%', CAST(:q AS text), '%')))
             AND (:category IS NULL OR e.category = CAST(:category AS text))
             AND (:city IS NULL OR e.city = CAST(:city AS text))
             AND (:freeOnly = FALSE OR COALESCE(
                   (SELECT MIN(tt.price_iqd) FROM ticket_types tt
                     WHERE tt.event_id = e.id AND tt.status = 'ON_SALE'), 0) = 0)
           """,
           nativeQuery = true)
    Page<Event> search(@Param("q") String q,
                       @Param("category") String category,
                       @Param("city") String city,
                       @Param("freeOnly") boolean freeOnly,
                       Pageable pageable);

    @Query(value = """
           SELECT e.* FROM events e
           WHERE e.status = 'LIVE' AND e.id <> :excludeId AND e.starts_at > now()
             AND (e.category = CAST(:category AS text) OR e.city = CAST(:city AS text))
           ORDER BY (e.category = CAST(:category AS text)) DESC, e.starts_at ASC
           """,
           nativeQuery = true)
    List<Event> findRelated(@Param("excludeId") long excludeId,
                            @Param("category") String category,
                            @Param("city") String city,
                            Pageable pageable);

    @Query(value = "SELECT e.city AS city, count(*) AS n FROM events e WHERE e.status = 'LIVE' GROUP BY e.city ORDER BY n DESC",
           nativeQuery = true)
    List<CityCountRow> countLiveByCity();

    interface CityCountRow {
        String getCity();
        long getN();
    }
}
