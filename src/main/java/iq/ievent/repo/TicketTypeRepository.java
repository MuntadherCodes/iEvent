package iq.ievent.repo;

import iq.ievent.domain.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    List<TicketType> findByEventIdOrderBySortOrderAsc(Long eventId);

    void deleteByEventId(Long eventId);

    @Query(value = """
           SELECT tt.event_id AS eventId, MIN(tt.price_iqd) AS minPrice
           FROM ticket_types tt
           WHERE tt.event_id IN (:eventIds) AND tt.status = 'ON_SALE'
           GROUP BY tt.event_id
           """,
           nativeQuery = true)
    List<MinPriceRow> minPricesForEvents(@Param("eventIds") Collection<Long> eventIds);

    interface MinPriceRow {
        long getEventId();
        long getMinPrice();
    }
}
