package iq.ievent.repo;

import iq.ievent.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    List<PaymentMethod> findByOrganizationIdOrderBySortOrderAscIdAsc(Long organizationId);

    List<PaymentMethod> findByOrganizationIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long organizationId);
}
