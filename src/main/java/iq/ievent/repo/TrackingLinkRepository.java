package iq.ievent.repo;

import iq.ievent.domain.TrackingLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrackingLinkRepository extends JpaRepository<TrackingLink, Long> {

    Optional<TrackingLink> findByCode(String code);

    List<TrackingLink> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<TrackingLink> findByEventIdOrderByCreatedAtDesc(Long eventId);

    boolean existsByCode(String code);

    @Modifying
    @Query(value = "UPDATE tracking_links SET clicks = clicks + 1 WHERE code = :code", nativeQuery = true)
    void incrementClicks(@Param("code") String code);
}
