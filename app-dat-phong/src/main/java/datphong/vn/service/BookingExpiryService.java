package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.exception.entity.Booking;
import datphong.vn.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UC-11. Mỗi booking một transaction NGẮN; claim bằng FOR UPDATE SKIP LOCKED nên nhiều instance scheduler không xử lý trùng
 * và không chặn nhau. Job RECHECK trạng thái trong transaction (không tin danh sách lấy trước đó).
 * Callback thanh toán và job đua nhau: cả hai khóa booking trước -> đúng một nhánh thắng, nhánh còn lại thấy trạng thái đã đổi.
 */
@Slf4j
@Service
public class BookingExpiryService {

    private static final int BATCH = 100;

    private final BookingRepository bookings;
    private final BookingTransitions transitions;
    private final OutboxService outbox;
    private final BusinessEventService analytics;
    private final ReminderService reminders;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final AtomicLong backlog = new AtomicLong();

    public BookingExpiryService(BookingRepository bookings, BookingTransitions transitions, OutboxService outbox,
                                BusinessEventService analytics, ReminderService reminders, PlatformTransactionManager txm,
                                MeterRegistry metrics, Clock clock) {
        this.bookings = bookings;
        this.transitions = transitions;
        this.outbox = outbox;
        this.analytics = analytics;
        this.reminders = reminders;
        this.tx = new TransactionTemplate(txm);
        this.metrics = metrics;
        this.clock = clock;
        metrics.gauge("hold_expiry_backlog", backlog);
    }

    /** Một lượt chạy: trả số booking đã hết hạn. */
    public int runOnce() {
        String jobRunId = UUID.randomUUID().toString().substring(0, 8);
        Instant now = clock.instant();
        List<Long> ids = bookings.findExpiredIds(now, BATCH);
        backlog.set(ids.size());
        int done = 0;
        for (Long id : ids) {
            try {
                Boolean expired = tx.execute(s -> expireOne(id, now));
                if (Boolean.TRUE.equals(expired)) done++;
            } catch (Exception e) {
                // lỗi một bản ghi không chặn cả batch; lần sau retry
                metrics.counter("scheduler_failures_total", "job", "hold_expiry").increment();
                log.warn("[job {}] expire booking {} failed: {}", jobRunId, id, e.getMessage());
            }
        }
        if (done > 0) log.info("[job {}] expired {} holds", jobRunId, done);
        return done;
    }

    private boolean expireOne(Long id, Instant now) {
        Booking b = bookings.claimExpired(id, now).orElse(null);   // đã bị worker khác khóa / đã đổi trạng thái -> bỏ qua
        if (b == null) return false;
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT) return false;       // recheck trong transaction
        transitions.to(b, BookingStatus.EXPIRED, "SCHEDULER", null, "Hết hạn giữ chỗ");
        transitions.release(b.getId());                                          // giải phóng đúng một lần
        reminders.cancelForBooking(b.getId());
        outbox.publish("BOOKING_EXPIRED:" + b.getId(), "BOOKING_EXPIRED", "BOOKING", b.getId(),
                Map.of("bookingId", b.getId(), "bookingCode", b.getBookingCode(), "customerId", b.getCustomerId(), "propertyId", b.getPropertyId()));
        metrics.counter("hold_expired_total").increment();
        analytics.record("HOLD_EXPIRED:" + b.getId(), "HOLD_EXPIRED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(b.getId()), null);
        return true;
    }
}
