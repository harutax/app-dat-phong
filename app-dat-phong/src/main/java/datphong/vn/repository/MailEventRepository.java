package datphong.vn.repository;

import datphong.vn.exception.entity.MailEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MailEventRepository extends JpaRepository<MailEvent, Long> {

    boolean existsByDedupeKey(String key);

    /** Claim một mail đến hạn (QUEUED) hoặc mail SENDING có lease đã hết (worker cũ crash). */
    @Query(value = "select * from mail_events where (status = 'QUEUED' and next_attempt_at <= :now) "
            + "or (status = 'SENDING' and lease_until < :now) order by next_attempt_at limit 1 for update skip locked", nativeQuery = true)
    java.util.Optional<MailEvent> claimNext(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailEvent m set m.status = datphong.vn.constant.MailStatus.CANCELLED, m.updatedAt = :now where m.bookingId = :bid "
            + "and m.eventType = 'CHECKIN_REMINDER' and m.status = datphong.vn.constant.MailStatus.QUEUED")
    int cancelQueuedReminders(@Param("bid") Long bookingId, @Param("now") Instant now);

    @Query("select m from MailEvent m where (:status is null or m.status = :status) and (:type is null or m.eventType = :type) "
            + "and (:bookingId is null or m.bookingId = :bookingId) and m.createdAt >= :from and m.createdAt < :to order by m.id desc")
    Page<MailEvent> search(@Param("status") datphong.vn.constant.MailStatus status, @Param("type") String type,
                           @Param("bookingId") Long bookingId, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query(value = "select event_type, status, count(*) from mail_events where created_at >= :from group by event_type, status", nativeQuery = true)
    List<Object[]> statsSince(@Param("from") Instant from);
}
