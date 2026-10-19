package datphong.vn.repository;

import datphong.vn.exception.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByIdDesc(Long recipientId, Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /** Idempotent: unique (event_id, recipient_id) -> worker restart không tạo thông báo thứ hai. */
    @Modifying
    @Query(value = "insert into notifications (event_id, recipient_id, type, title, body, object_type, object_id, created_at) "
            + "values (:eventId, :rid, :type, :title, :body, :ot, :oid, :now) on conflict (event_id, recipient_id) do nothing", nativeQuery = true)
    int insertIgnore(@Param("eventId") String eventId, @Param("rid") Long recipientId, @Param("type") String type, @Param("title") String title,
                     @Param("body") String body, @Param("ot") String objectType, @Param("oid") String objectId, @Param("now") Instant now);
}
