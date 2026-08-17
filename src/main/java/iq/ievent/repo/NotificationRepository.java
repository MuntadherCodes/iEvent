package iq.ievent.repo;

import iq.ievent.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop15ByUserIdOrderByCreatedAtDesc(Long userId);

    List<Notification> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(value = "UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL",
           nativeQuery = true)
    int markAllRead(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM notifications WHERE user_id = :userId", nativeQuery = true)
    int clearAll(@Param("userId") Long userId);
}
