package iq.ievent.repo;

import iq.ievent.domain.TeamInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamInviteRepository extends JpaRepository<TeamInvite, Long> {

    Optional<TeamInvite> findByToken(String token);

    List<TeamInvite> findByOrganizationIdAndAcceptedAtIsNullOrderByCreatedAtDesc(Long organizationId);

    Optional<TeamInvite> findByOrganizationIdAndEmailIgnoreCaseAndAcceptedAtIsNull(Long organizationId, String email);
}
