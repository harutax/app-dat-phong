package datphong.vn.repository;

import datphong.vn.exception.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    boolean existsByEventId(String eventId);

    /** Claim một event đến hạn; SKIP LOCKED để nhiều worker/instance không xử lý trùng. */
    @Query(value = "select * from outbox_events where status in ('PENDING','PROCESSING') and next_attempt_at <= :now "
            + "order by id limit 1 for update skip locked", nativeQuery = true)
    Optional<OutboxEvent> claimNext(@Param("now") Instant now);
}
