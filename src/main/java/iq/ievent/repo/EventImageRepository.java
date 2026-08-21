package iq.ievent.repo;

import iq.ievent.domain.EventImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventImageRepository extends JpaRepository<EventImage, Long> {

    List<EventImage> findByEventIdOrderBySortOrderAsc(Long eventId);

    void deleteByEventId(Long eventId);
}
