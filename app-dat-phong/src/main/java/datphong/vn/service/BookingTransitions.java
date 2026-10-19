package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.domain.Transitions;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.BookingStatusHistory;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.BookingRepository;
import datphong.vn.repository.BookingStatusHistoryRepository;
import datphong.vn.repository.RoomAllocationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Điểm DUY NHẤT đổi trạng thái booking: kiểm tra state machine + ghi booking_status_history (from/to/actor/reason/at/correlationId). */
@Component
@RequiredArgsConstructor
public class BookingTransitions {

    private final BookingRepository bookings;
    private final BookingStatusHistoryRepository history;
    private final RoomAllocationRepository allocations;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void to(Booking b, BookingStatus next, String actorType, Long actorId, String reason) {
        BookingStatus from = b.getStatus();
        if (from == next) return;
        if (!Transitions.canTransition(from, next)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "Không thể chuyển booking từ " + from + " sang " + next);
        }
        b.setStatus(next);
        bookings.save(b);
        record(b.getId(), from, next, actorType, actorId, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long bookingId, BookingStatus from, BookingStatus to, String actorType, Long actorId, String reason) {
        BookingStatusHistory h = new BookingStatusHistory();
        h.setBookingId(bookingId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setActorType(actorType);
        h.setActorId(actorId);
        h.setReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        h.setCorrelationId(MDC.get("traceId"));
        h.setAt(clock.instant());
        history.save(h);
    }

    /** Giải phóng phòng ĐÚNG MỘT LẦN (UPDATE có điều kiện active=true). Trả true nếu lần này thực sự giải phóng. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(Long bookingId) {
        return allocations.release(bookingId, clock.instant()) > 0;
    }
}
