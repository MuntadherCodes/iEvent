package iq.ievent.repo;

import iq.ievent.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByCode(String code);

    List<Ticket> findByOrderIdOrderByIdAsc(Long orderId);

    @Query("""
           select t from Ticket t
           where t.order.buyerUserId = :userId
           order by t.event.startsAt asc, t.id asc
           """)
    List<Ticket> findForBuyer(@Param("userId") Long userId);

    /** qLike must be pre-lowercased and %-wrapped by the caller, or null for "all".
     *  (concat() with a nullable parameter breaks PostgreSQL type inference.) */
    @Query("""
           select t from Ticket t
           where t.event.id = :eventId
             and (:qLike is null
                  or lower(t.holderName) like :qLike
                  or lower(t.code) like :qLike
                  or lower(t.order.orderCode) like :qLike)
           order by t.holderName asc
           """)
    List<Ticket> searchForEvent(@Param("eventId") Long eventId, @Param("qLike") String qLike);

    long countByEventIdAndStatus(Long eventId, Ticket.Status status);

    long countByEventId(Long eventId);
}
