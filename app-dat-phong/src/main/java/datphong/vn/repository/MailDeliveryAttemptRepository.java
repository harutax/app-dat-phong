package datphong.vn.repository;

import datphong.vn.exception.entity.MailDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailDeliveryAttemptRepository extends JpaRepository<MailDeliveryAttempt, Long> {
    List<MailDeliveryAttempt> findByMailEventIdOrderByAttemptNo(Long mailEventId);

    Optional<MailDeliveryAttempt> findFirstByMailEventIdAndAttemptNoOrderByIdDesc(Long mailEventId, int attemptNo);

    boolean existsByMailEventIdAndOperatorIdemKey(Long mailEventId, String key);
}
