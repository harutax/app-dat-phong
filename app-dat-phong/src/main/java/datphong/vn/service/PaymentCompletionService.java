package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.CaseType;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.domain.Money;
import datphong.vn.domain.Transitions;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.Payment;
import datphong.vn.exception.entity.PaymentEvent;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/**
 * Điểm DUY NHẤT áp kết quả thanh toán từ bank (đồng bộ / webhook / đối soát) vào hệ thống đặt phòng.
 * Payment và Booking là hai vòng đời riêng. Thứ tự khóa cố định: booking -> payment -> allocation.
 * Callback trễ KHÔNG BAO GIỜ biến booking đã hết hạn/hủy thành CONFIRMED: tiền được ghi nhận, tạo ca đối soát + refund bù trừ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCompletionService {

    public record BankResult(String paymentKey, String providerTxnId, String status, String failureCode,
                             BigDecimal amount, String currency, String eventId) {}

    public enum Outcome { CONFIRMED, LATE_REFUND_REQUESTED, FAILED, PROCESSING, UNKNOWN, NOOP, DUPLICATE, IGNORED, MISMATCH }

    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final PaymentEventRepository events;
    private final BookingTransitions transitions;
    private final RefundService refunds;
    private final OutboxService outbox;
    private final BusinessEventService analytics;
    private final Clock clock;

    @Transactional
    public Outcome apply(BankResult r) {
        Payment probe = payments.findByIdempotencyKey(r.paymentKey()).orElse(null);
        if (probe == null) {
            log.warn("bank result for unknown payment key {}", r.paymentKey());
            return Outcome.IGNORED;
        }
        Booking b = bookings.lockById(probe.getBookingId()).orElseThrow();   // 1. booking
        Payment p = payments.lockById(probe.getId()).orElseThrow();          // 2. payment
        if (r.eventId() != null) {
            // kiểm tra SAU khi giữ khóa: hai lần giao đồng thời được tuần tự hóa, lần sau thấy event đã commit
            if (events.existsByProviderEventId(r.eventId())) return Outcome.DUPLICATE;
            PaymentEvent ev = new PaymentEvent();
            ev.setProviderEventId(r.eventId());
            ev.setPaymentId(p.getId());
            ev.setEventType("PAYMENT_" + r.status());
            ev.setReceivedAt(clock.instant());
            events.save(ev);
        }
        if (p.getProviderTxnId() == null && r.providerTxnId() != null) p.setProviderTxnId(r.providerTxnId());
        p.setLastCheckedAt(clock.instant());

        return switch (r.status()) {
            case "SUCCEEDED" -> onSuccess(b, p, r);
            case "FAILED" -> onFailed(b, p, r);
            case "TIMEOUT" -> onTimeout(p, r);
            case "PROCESSING", "ACCEPTED" -> {
                if (p.getStatus() == PaymentStatus.INITIATED) p.setStatus(PaymentStatus.PROCESSING);
                payments.save(p);
                yield Outcome.PROCESSING;
            }
            default -> {
                log.warn("unknown bank status {}", r.status());
                yield Outcome.IGNORED;
            }
        };
    }

    private Outcome onSuccess(Booking b, Payment p, BankResult r) {
        if (p.getStatus() == PaymentStatus.SUCCEEDED) return Outcome.NOOP; // webhook lặp: không đổi hiệu ứng
        if ((r.amount() != null && !Money.eq(r.amount(), p.getAmount()))
                || (r.currency() != null && !r.currency().equals(p.getCurrency()))) {
            refunds.openCase(b, p, null, CaseType.AMOUNT_MISMATCH,
                    "Bank báo " + r.amount() + " " + r.currency() + " nhưng payment " + p.getAmount() + " " + p.getCurrency());
            payments.save(p);
            return Outcome.MISMATCH; // không tin số tiền từ phía ngoài; chờ người xử lý
        }
        if (!Transitions.canTransition(p.getStatus(), PaymentStatus.SUCCEEDED)) {
            refunds.openCase(b, p, null, CaseType.UNKNOWN_PAYMENT, "Bank SUCCEEDED nhưng payment nội bộ đang " + p.getStatus());
            return Outcome.MISMATCH;
        }
        p.setStatus(PaymentStatus.SUCCEEDED);
        p.setCapturedAt(clock.instant());
        p.setFailureCode(null);
        payments.save(p);

        boolean holdValid = b.getStatus() == BookingStatus.PENDING_PAYMENT && b.getExpiresAt() != null && b.getExpiresAt().isAfter(clock.instant());
        if (holdValid) {
            transitions.to(b, BookingStatus.CONFIRMED, "SYSTEM", null, "Thanh toán thành công");
            outbox.publish("PAYMENT_SUCCEEDED:" + p.getId(), "PAYMENT_SUCCEEDED", "PAYMENT", p.getId(), payload(b, p));
            outbox.publish("BOOKING_CONFIRMED:" + b.getId(), "BOOKING_CONFIRMED", "BOOKING", b.getId(), payload(b, p));
            analytics.record("PAYMENT_CAPTURED:" + p.getId(), "PAYMENT_CAPTURED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(b.getId()), null);
            analytics.record("BOOKING_CONFIRMED:" + b.getId(), "BOOKING_CONFIRMED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(b.getId()), null);
            return Outcome.CONFIRMED;
        }

        // ---- callback muộn / booking không còn hợp lệ: ghi nhận tiền, KHÔNG confirm, hoàn tiền bù trừ
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT) {
            // hết hạn theo giờ nhưng job chưa chạy: chốt hết hạn tại đây (cùng lock order) rồi xử lý như callback muộn
            transitions.to(b, BookingStatus.EXPIRED, "SYSTEM", null, "Hết hạn giữ chỗ (phát hiện khi callback đến)");
            transitions.release(b.getId());
        }
        if (b.getStatus() == BookingStatus.EXPIRED) {
            transitions.to(b, BookingStatus.NEEDS_ATTENTION, "SYSTEM", null, "Thanh toán thành công sau khi booking hết hạn");
        }
        refunds.openCase(b, p, null, CaseType.NEEDS_REFUND, "Thanh toán thành công muộn, booking " + b.getStatus());
        refunds.request(p, p.getAmount(), "late-" + p.getId(), "LATE_CALLBACK", null);
        analytics.record("PAYMENT_CAPTURED:" + p.getId(), "PAYMENT_CAPTURED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(b.getId()), "LATE");
        return Outcome.LATE_REFUND_REQUESTED;
    }

    private Outcome onFailed(Booking b, Payment p, BankResult r) {
        if (p.getStatus() == PaymentStatus.SUCCEEDED) return Outcome.NOOP; // SUCCEEDED không quay về FAILED
        if (p.getStatus() == PaymentStatus.FAILED) return Outcome.NOOP;
        if (!Transitions.canTransition(p.getStatus(), PaymentStatus.FAILED)) return Outcome.NOOP;
        p.setStatus(PaymentStatus.FAILED);
        p.setFailureCode(r.failureCode() == null ? "DECLINED" : r.failureCode());
        payments.save(p);
        // Booking giữ PENDING_PAYMENT tới khi hết hạn: khách có thể thanh toán lại nếu còn hạn.
        outbox.publish("PAYMENT_FAILED:" + p.getId(), "PAYMENT_FAILED", "PAYMENT", p.getId(), payload(b, p));
        analytics.record("PAYMENT_FAILED:" + p.getId(), "PAYMENT_FAILED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(b.getId()), p.getFailureCode());
        return Outcome.FAILED;
    }

    /** Timeout KHÔNG mặc định là thất bại: chuyển UNKNOWN và đối soát bằng idempotencyKey. */
    private Outcome onTimeout(Payment p, BankResult r) {
        if (p.getStatus() == PaymentStatus.INITIATED || p.getStatus() == PaymentStatus.PROCESSING) {
            p.setStatus(PaymentStatus.UNKNOWN);
            p.setFailureCode("TIMEOUT");
        }
        payments.save(p);
        return Outcome.UNKNOWN;
    }

    private Map<String, Object> payload(Booking b, Payment p) {
        return Map.of("bookingId", b.getId(), "bookingCode", b.getBookingCode(), "paymentId", p.getId(), "customerId", b.getCustomerId(),
                "propertyId", b.getPropertyId(), "amount", p.getAmount(), "currency", p.getCurrency(),
                "checkInDate", b.getCheckIn().toString(), "checkOutDate", b.getCheckOut().toString());
    }
}
