package datphong.vn.repository;

import datphong.vn.exception.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    boolean existsByProviderEventId(String eventId);
}
