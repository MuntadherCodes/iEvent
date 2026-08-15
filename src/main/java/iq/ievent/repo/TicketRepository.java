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

    @Query("""
           select t from Ticket t
           where t.event.id = :eventId
             and (:q is null or lower(t.holderName) like lower(concat('%', :q, '%'))
                  or lower(t.code) like lower(concat('%', :q, '%'))
                  or lower(t.order.orderCode) like lower(concat('%', :q, '%')))
           order by t.holderName asc
           """)
    List<Ticket> searchForEvent(@Param("eventId") Long eventId, @Param("q") String q);

    long countByEventIdAndStatus(Long eventId, Ticket.Status status);

    long countByEventId(Long eventId);
}
