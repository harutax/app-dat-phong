package datphong.vn.service;

import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ngoài transaction (KHÔNG @Transactional): chịu trách nhiệm retry có giới hạn khi chờ lock quá lâu/deadlock
 * và ánh xạ vi phạm constraint thành lỗi nghiệp vụ. Retry an toàn vì use case idempotent theo idempotencyKey.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFacade {

    private static final int MAX_ATTEMPTS = 3;

    private final BookingCreator creator;
    private final IdempotencyService idempotency;
    private final BookingQueryService queries;
    private final BusinessEventService events;
    private final MeterRegistry metrics;

    public record Outcome(datphong.vn.dto.response.BookingResponses.BookingDto booking, boolean replayed) {}

    public Outcome create(Long customerId, BookingRequests.Create req) {
        String hash = HmacSigner.sha256Hex(req.roomId() + "|" + req.checkIn() + "|" + req.checkOut() + "|" + req.guestCount());
        String scope = "booking:create:" + customerId;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                BookingCreator.Result r = creator.create(customerId, req, hash);
                if (!r.replayed()) {
                    events.record("HOLD_CREATED:" + r.booking().getId(), "HOLD_CREATED", r.booking().getPropertyId(), "u" + customerId,
                            String.valueOf(r.booking().getId()), null);
                }
                return new Outcome(queries.customerView(customerId, r.booking().getId()), r.replayed());
            } catch (CannotAcquireLockException | PessimisticLockingFailureException e) {
                last = e;
                metrics.counter("booking_lock_retry_total").increment();
                log.warn("booking lock contention attempt {}/{}", attempt, MAX_ATTEMPTS);
                sleep(40L * attempt + ThreadLocalRandom.current().nextInt(40));
            } catch (DataIntegrityViolationException e) {
                String msg = String.valueOf(e.getMostSpecificCause().getMessage());
                if (msg.contains("ex_room_no_overlap")) {
                    metrics.counter("booking_conflict_total").increment();
                    throw new BusinessException(ErrorCode.ROOM_UNAVAILABLE, "Phòng đã được đặt hoặc đang được giữ cho khoảng ngày này");
                }
                if (msg.contains("uq_idem")) {
                    // request trùng key vừa commit: trả kết quả cũ thay vì lỗi
                    var ref = idempotency.lookupCommitted(scope, req.idempotencyKey(), hash);
                    if (ref.isPresent()) return new Outcome(queries.customerView(customerId, Long.valueOf(ref.get())), true);
                }
                throw e;
            }
        }
        throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "Hệ thống đang bận, vui lòng thử lại", last == null ? null : last.getClass().getSimpleName());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
