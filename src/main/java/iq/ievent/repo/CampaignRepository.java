package iq.ievent.repo;

import iq.ievent.domain.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findTop20ByOrganizationIdOrderBySentAtDesc(Long organizationId);
}
