package iq.ievent.repo;

import iq.ievent.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderCode(String orderCode);

    List<Order> findByBuyerUserIdOrderByCreatedAtDesc(Long buyerUserId);

    @Query("""
           select o from Order o
           where o.event.organization.id = :orgId
             and (:status is null or o.status = :status)
           order by o.createdAt desc
           """)
    Page<Order> findForOrganization(@Param("orgId") Long orgId,
                                    @Param("status") Order.Status status,
                                    Pageable pageable);

    @Query("""
           select count(o) from Order o
           where o.event.organization.id = :orgId and o.status = :status
           """)
    long countForOrganizationByStatus(@Param("orgId") Long orgId, @Param("status") Order.Status status);
}
