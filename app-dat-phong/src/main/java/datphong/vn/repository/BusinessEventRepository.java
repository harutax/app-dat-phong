package datphong.vn.repository;

import datphong.vn.exception.entity.BusinessEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessEventRepository extends JpaRepository<BusinessEvent, Long> {
    boolean existsByEventId(String eventId);
}
