package iq.ievent.repo;

import iq.ievent.domain.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    List<PromoCode> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    @Query("""
           select p from PromoCode p
           where p.organizationId = :orgId and upper(p.code) = upper(:code)
           """)
    Optional<PromoCode> findByOrgAndCode(@Param("orgId") Long orgId, @Param("code") String code);
}
