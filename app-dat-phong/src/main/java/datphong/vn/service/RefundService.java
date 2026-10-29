package datphong.vn.service;

import datphong.vn.client.FakeBankClient;
import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.CaseType;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.constant.RefundStatus;
import datphong.vn.domain.CancellationCalculator;
import datphong.vn.domain.Transitions;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * UC-13/36: hoàn tiền mô phỏng dạng saga. Bất biến:
 *  - tổng hoàn (REQUESTED + PROCESSING + SUCCEEDED) <= số tiền payment đã thực thu;
 *  - refund_key unique -> request lặp không hoàn hai lần; retry GIỮ NGUYÊN refund_key;
 *  - trạng thái PROCESSING được COMMIT trước khi gọi bank; gọi bank ngoài transaction;
 *  - thứ tự khóa: booking -> payment -> refund.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final PaymentEventRepository paymentEvents;
    private final ReconciliationCaseRepository cases;
    private final BookingTransitions transitions;
    private final FakeBankClient bank;
    private final OutboxService outbox;
    private final ConfigService config;
    private final PlatformTransactionManager txManager;
    private final Clock clock;

    // ------------------------------------------------------------------ tạo yêu cầu (trong transaction của caller)
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund request(Payment payment, BigDecimal amount, String refundKey, String reason, Long requestedBy) {
        var existing = refunds.findByRefundKey(refundKey);
        if (existing.isPresent()) return existing.get(); // trùng yêu cầu -> trả bản cũ, không tạo thứ hai
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Chỉ hoàn tiền cho payment SUCCEEDED");
        }
        BigDecimal committed = refunds.committedForPayment(payment.getId());
        if (!CancellationCalculator.refundWithinLimit(payment.getAmount(), committed, amount)) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_EXCEEDED, "Tổng tiền hoàn vượt số tiền đã thực thu");
        }
        Refund r = new Refund();
        r.setRefundKey(refundKey);
        r.setPaymentId(payment.getId());
        r.setBookingId(payment.getBookingId());
        r.setAmount(amount);
        r.setStatus(RefundStatus.REQUESTED);
        r.setReason(reason);
        r.setRequestedBy(requestedBy);
        r.setNextAttemptAt(clock.instant());
        refunds.saveAndFlush(r);
        outbox.publish("REFUND_REQUESTED:" + r.getId(), "REFUND_REQUESTED", "REFUND", r.getId(),
                Map.of("refundId", r.getId(), "bookingId", r.getBookingId(), "amount", amount));
        return r;
    }

    // ------------------------------------------------------------------ xử lý (worker) - KHÔNG @Transactional
    /** @return true nếu đã gọi bank. */
    public boolean process(Long refundId) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        record Ctx(String key, String providerTxnId, BigDecimal amount, String currency, String reason, int attempt) {}

        // Bước 1 (commit trước khi gọi bank): REQUESTED/FAILED -> PROCESSING
        Ctx ctx = tx.execute(s -> {
            Refund r = refunds.lockById(refundId).orElse(null);
            if (r == null) return null;
            boolean dueRetry = r.getStatus() == RefundStatus.FAILED && r.getNextAttemptAt() != null && !r.getNextAttemptAt().isAfter(clock.instant());
            if (r.getStatus() != RefundStatus.REQUESTED && !dueRetry) return null;
            Payment p = payments.findById(r.getPaymentId()).orElseThrow();
            if (p.getProviderTxnId() == null) return null; // chưa có giao dịch bank để hoàn
            r.setStatus(RefundStatus.PROCESSING);
            r.setAttempts(r.getAttempts() + 1);
            return new Ctx(r.getRefundKey(), p.getProviderTxnId(), r.getAmount(), p.getCurrency(), r.getReason(), r.getAttempts());
        });
        if (ctx == null) return false;

        // Bước 2: gọi bank ngoài transaction
        try {
            FakeBankClient.BankRefund res = bank.refund(ctx.key(), ctx.providerTxnId(), ctx.amount(), ctx.currency(), ctx.reason());
            if ("SUCCEEDED".equals(res.status()) || "FAILED".equals(res.status())) {
                // eventId gắn số lần thử: kết quả FAILED của lần retry sau KHÔNG bị coi là bản lặp của lần trước (nếu không refund kẹt PROCESSING mãi)
                applyResult(ctx.key(), res.status(), res.providerRef(), res.failureCode(), "sync-refund:" + ctx.key() + ":" + ctx.attempt() + ":" + res.status());
            }
            // PROCESSING/ACCEPTED: chờ webhook hoặc đối soát
        } catch (FakeBankClient.BankUnknownException e) {
            // timeout: giữ PROCESSING, đối soát hỏi lại bằng cùng refund_key (KHÔNG tạo refund mới)
            log.warn("refund {} timeout/unknown, reconcile later: {}", refundId, e.getMessage());
        }
        return true;
    }

    // ------------------------------------------------------------------ áp kết quả từ bank (sync/webhook/đối soát)
    @Transactional
    public void applyResult(String refundKey, String status, String providerRef, String failureCode, String eventId) {
        Refund probe = refunds.findByRefundKey(refundKey).orElse(null);
        if (probe == null) { log.warn("refund result for unknown key {}", refundKey); return; }
        // khóa theo thứ tự booking -> payment -> refund
        Booking b = bookings.lockById(probe.getBookingId()).orElseThrow();
        Payment p = payments.lockById(probe.getPaymentId()).orElseThrow();
        Refund r = refunds.lockById(probe.getId()).orElseThrow();
        if (eventId != null) {
            if (paymentEvents.existsByProviderEventId(eventId)) return; // callback lặp: không cộng tiền hoàn thêm
            PaymentEvent ev = new PaymentEvent();
            ev.setProviderEventId(eventId);
            ev.setPaymentId(p.getId());
            ev.setEventType("REFUND_" + status);
            ev.setReceivedAt(clock.instant());
            paymentEvents.save(ev);
        }
        if (r.getStatus() == RefundStatus.SUCCEEDED) return; // idempotent

        if ("SUCCEEDED".equals(status)) {
            // webhook đến trước bước PROCESSING, hoặc bank xác nhận thành công cho một lần thử đã bị coi là FAILED (timeout/đối soát sớm):
            // tiền đã hoàn thật ở bank là nguồn sự thật -> đi qua PROCESSING rồi SUCCEEDED (không hoàn thêm lần nào nữa).
            if (r.getStatus() == RefundStatus.REQUESTED || r.getStatus() == RefundStatus.FAILED) r.setStatus(RefundStatus.PROCESSING);
            if (!Transitions.canTransition(r.getStatus(), RefundStatus.SUCCEEDED)) return;
            r.setStatus(RefundStatus.SUCCEEDED);
            r.setProviderRef(providerRef);
            r.setSucceededAt(clock.instant());
            r.setFailureCode(null);
            refunds.save(r);
            closeCases(r, p);
            settleBooking(b, r);
            outbox.publish("REFUND_SUCCEEDED:" + r.getId(), "REFUND_SUCCEEDED", "REFUND", r.getId(),
                    Map.of("refundId", r.getId(), "bookingId", b.getId(), "amount", r.getAmount(), "refundCode", "RF" + r.getId(),
                            "currency", p.getCurrency()));
        } else if ("FAILED".equals(status)) {
            if (r.getStatus() == RefundStatus.REQUESTED) r.setStatus(RefundStatus.PROCESSING);
            if (!Transitions.canTransition(r.getStatus(), RefundStatus.FAILED)) return;
            r.setStatus(RefundStatus.FAILED);
            r.setFailureCode(failureCode);
            int max = config.getInt("refund.retry.maxAttempts");
            if (r.getAttempts() < max) {
                long backoff = Math.min(3600, 30L * (1L << Math.min(r.getAttempts(), 7)));
                r.setNextAttemptAt(clock.instant().plus(Duration.ofSeconds(backoff))); // retry GIỮ cùng refund_key
            } else {
                r.setNextAttemptAt(null);
                openCase(b, p, r, CaseType.REFUND_STUCK, "Refund thất bại sau " + r.getAttempts() + " lần: " + failureCode);
                outbox.publish("REFUND_FAILED:" + r.getId(), "REFUND_FAILED", "REFUND", r.getId(),
                        Map.of("refundId", r.getId(), "bookingId", b.getId(), "amount", r.getAmount(), "refundCode", "RF" + r.getId(), "currency", p.getCurrency()));
            }
            refunds.save(r);
        }
    }

    /** Khi mọi refund của booking đã xong: REFUND_PENDING -> CANCELLED; NEEDS_ATTENTION -> EXPIRED (hoặc giữ nguyên CANCELLED). */
    private void settleBooking(Booking b, Refund done) {
        if (refunds.existsOpenForBooking(b.getId())) return;
        if (b.getStatus() == BookingStatus.REFUND_PENDING) {
            transitions.to(b, BookingStatus.CANCELLED, "SYSTEM", null, "Hoàn tiền hoàn tất");
        } else if (b.getStatus() == BookingStatus.NEEDS_ATTENTION) {
            transitions.to(b, BookingStatus.EXPIRED, "SYSTEM", null, "Đã hoàn tiền cho thanh toán muộn");
        }
    }

    private void closeCases(Refund r, Payment p) {
        cases.findFirstByPaymentIdAndTypeAndStatus(p.getId(), CaseType.NEEDS_REFUND, "OPEN").ifPresent(c -> {
            c.setStatus("RESOLVED");
            c.setResolutionNote("Refund " + r.getId() + " thành công");
            c.setResolvedAt(clock.instant());
        });
        cases.findFirstByRefundIdAndTypeAndStatus(r.getId(), CaseType.REFUND_STUCK, "OPEN").ifPresent(c -> {
            c.setStatus("RESOLVED");
            c.setResolutionNote("Refund thành công ở lần sau");
            c.setResolvedAt(clock.instant());
        });
    }

    public void openCase(Booking b, Payment p, Refund r, CaseType type, String details) {
        // unique index (payment_id, type) WHERE OPEN: kiểm tra ở mức payment để không vi phạm
        boolean exists = cases.findFirstByPaymentIdAndTypeAndStatus(p.getId(), type, "OPEN").isPresent();
        if (exists) return;
        ReconciliationCase c = new ReconciliationCase();
        c.setType(type);
        c.setStatus("OPEN");
        c.setBookingId(b == null ? null : b.getId());
        c.setPaymentId(p == null ? null : p.getId());
        c.setRefundId(r == null ? null : r.getId());
        c.setDetails(details);
        cases.save(c);
    }
}
