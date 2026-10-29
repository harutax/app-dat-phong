package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.domain.CancellationCalculator;
import datphong.vn.domain.HmacSigner;
import datphong.vn.domain.Money;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.dto.response.BookingResponses.CancelPreview;
import datphong.vn.dto.response.BookingResponses.CancelResult;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.Payment;
import datphong.vn.exception.entity.Refund;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.*;
import datphong.vn.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/**
 * UC-12. Phân biệt rõ: (1) quyết định hủy đơn, (2) giải phóng tồn kho, (3) hoàn tiền - đều chống lặp.
 * Quy ước: giải phóng phòng NGAY khi chốt hủy; tiền hoàn đi theo luồng refund riêng (REFUND_PENDING không giữ phòng).
 * Phí tính từ SNAPSHOT chính sách trong booking, không dùng chính sách mới của HOST.
 */
@Service
@RequiredArgsConstructor
public class CancellationService {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final RefundRepository refundRepo;
    private final BookingTransitions transitions;
    private final RefundService refunds;
    private final IdempotencyService idempotency;
    private final ReminderService reminders;
    private final CheckinRequestRepository checkinRequests;
    private final OutboxService outbox;
    private final AuditService audit;
    private final Clock clock;

    private boolean isSupport(AuthUser u) {
        return u.hasPermission("SUPPORT_BOOKING_CANCEL");
    }

    private Booking scoped(AuthUser u, Booking b) {
        if (!b.getCustomerId().equals(u.getId()) && !isSupport(u)) throw BusinessException.notFound("Booking"); // ẩn tài nguyên
        return b;
    }

    @Transactional(readOnly = true)
    public CancelPreview preview(AuthUser u, Long id) {
        Booking b = scoped(u, bookings.findById(id).orElseThrow(() -> BusinessException.notFound("Booking")));
        // Cùng công thức với cancel(): dựa trên payment SUCCEEDED gần nhất của booking (khoản sẽ được hoàn khi hủy)
        Payment paid = latestSucceeded(id);
        BigDecimal captured = paid == null ? Money.zero() : paid.getAmount();
        BigDecimal refunded = paid == null ? Money.zero() : refundRepo.committedForPayment(paid.getId());
        var res = CancellationCalculator.compute(clock.instant(), b.getFreeCancelDeadline(), b.getPenaltyPercent(), captured, refunded);
        return new CancelPreview(id, res.freeCancellation(), captured, res.fee(), res.refundable(), b.getFreeCancelDeadline(), b.getPenaltyPercent(), b.getStatus().name());
    }

    private Payment latestSucceeded(Long bookingId) {
        return payments.findByBookingIdOrderByIdDesc(bookingId).stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).findFirst().orElse(null);
    }

    @Transactional
    public CancelResult cancel(AuthUser u, Long id, BookingRequests.Cancel req) {
        String scope = "booking:cancel:" + u.getId();
        String hash = HmacSigner.sha256Hex(id + "|" + req.reason());
        Booking b = scoped(u, bookings.lockById(id).orElseThrow(() -> BusinessException.notFound("Booking"))); // 1. booking
        var replay = idempotency.lookup(scope, req.idempotencyKey(), hash);
        if (replay.isPresent()) return result(b, true);

        switch (b.getStatus()) {
            case CANCELLED, REFUND_PENDING, CANCELLATION_PENDING -> { return result(b, true); } // đã hủy: trả kết quả cũ, không tạo refund thứ hai
            case COMPLETED, EXPIRED, NO_SHOW, NEEDS_ATTENTION ->
                    throw new BusinessException(ErrorCode.CANCELLATION_NOT_ALLOWED, "Booking ở trạng thái " + b.getStatus() + " không thể hủy");
            case CHECKED_IN -> {
                if (!isSupport(u)) throw new BusinessException(ErrorCode.CANCELLATION_NOT_ALLOWED, "Đang lưu trú: liên hệ hỗ trợ để xử lý ngoại lệ");
            }
            default -> { }
        }
        Payment paid = payments.findByBookingIdOrderByIdDesc(id).stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).findFirst().orElse(null);
        if (paid != null) paid = payments.lockById(paid.getId()).orElseThrow();       // 2. payment
        BigDecimal captured = paid == null ? Money.zero() : paid.getAmount();
        BigDecimal alreadyRefunded = paid == null ? Money.zero() : refundRepo.committedForPayment(paid.getId());
        var calc = CancellationCalculator.compute(clock.instant(), b.getFreeCancelDeadline(), b.getPenaltyPercent(), captured, alreadyRefunded);

        b.setCancelledBy(u.getId());
        b.setCancelledAt(clock.instant());
        b.setCancelReason(req.reason());
        b.setCancellationFee(calc.fee());
        b.setRefundableAmount(calc.refundable());
        String actor = isSupport(u) && !b.getCustomerId().equals(u.getId()) ? "SUPPORT" : "CUSTOMER";
        BookingStatus from = b.getStatus();

        Long refundId = null;
        if (paid != null && calc.refundable().signum() > 0) {
            transitions.to(b, BookingStatus.REFUND_PENDING, actor, u.getId(), req.reason());
            Refund r = refunds.request(paid, calc.refundable(), "cancel-" + b.getId(), "BOOKING_CANCELLED", u.getId());
            refundId = r.getId();
        } else {
            transitions.to(b, BookingStatus.CANCELLED, actor, u.getId(), req.reason());   // chưa thu tiền / phí 100%: không refund
        }
        transitions.release(b.getId());                                                  // 3. allocation: đúng một lần
        reminders.cancelForBooking(b.getId());
        checkinRequests.closeOpenForBooking(b.getId());

        idempotency.store(scope, req.idempotencyKey(), hash, String.valueOf(b.getId()));
        outbox.publish("BOOKING_CANCELLED:" + b.getId(), "BOOKING_CANCELLED", "BOOKING", b.getId(),
                Map.of("bookingId", b.getId(), "bookingCode", b.getBookingCode(), "customerId", b.getCustomerId(), "propertyId", b.getPropertyId(),
                        "cancelledBy", actor, "fee", calc.fee()));
        audit.record(actor.equals("SUPPORT") ? "SUPPORT_CANCEL_BOOKING" : "BOOKING_CANCEL", "BOOKING", b.getId(), from.name(), b.getStatus().name(),
                req.reason(), Map.of("fee", calc.fee(), "refundable", calc.refundable()));
        return new CancelResult(b.getId(), b.getStatus().name(), calc.fee(), calc.refundable(), refundId, false);
    }

    private CancelResult result(Booking b, boolean replayed) {
        Long refundId = refundRepo.findByBookingIdOrderByIdDesc(b.getId()).stream().findFirst().map(Refund::getId).orElse(null);
        return new CancelResult(b.getId(), b.getStatus().name(), b.getCancellationFee(), b.getRefundableAmount(), refundId, replayed);
    }
}
