package datphong.vn.repository;

import datphong.vn.exception.entity.ScheduledReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScheduledReminderRepository extends JpaRepository<ScheduledReminder, Long> {

    List<ScheduledReminder> findByBookingIdAndReminderTypeAndWindowCode(Long bookingId, String type, String window);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScheduledReminder r set r.status = 'CANCELLED', r.updatedAt = :now where r.bookingId = :bid and r.status = 'PENDING'")
    int cancelPending(@Param("bid") Long bookingId, @Param("now") Instant now);

    /** Claim bằng SKIP LOCKED: nhiều instance scheduler không gửi trùng; không phụ thuộc Redis lock. */
    @Query(value = "select * from scheduled_reminders where status = 'PENDING' and due_at <= :now order by due_at limit :limit for update skip locked", nativeQuery = true)
    List<ScheduledReminder> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
