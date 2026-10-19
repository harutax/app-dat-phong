package datphong.vn.service;

import datphong.vn.client.FakeBankClient;
import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.dto.response.PaymentResponses.PaymentDto;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.Payment;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.BookingRepository;
import datphong.vn.repository.PaymentRepository;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

/**
 * UC-10/35/36 phía khởi tạo. KHÔNG @Transactional ở mức method: transaction DB chỉ bao các bước ngắn,
 * lời gọi Fake Bank luôn nằm NGOÀI transaction (không giữ lock/connection trong lúc chờ mạng).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Set<PaymentStatus> IN_FLIGHT = EnumSet.of(PaymentStatus.INITIATED, PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN, PaymentStatus.TIMED_OUT);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final FakeBankClient bank;
    private final PaymentCompletionService completion;
    private final BusinessEventService analytics;
    private final RateLimitService rateLimit;
    private final PlatformTransactionManager txManager;
    private final Clock clock;

    public PaymentDto initiate(Long customerId, BookingRequests.PayInit req) {
        rateLimit.check("pay:" + customerId, 20, java.time.Duration.ofMinutes(1));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // ---- bước 1 (DB, ngắn): kiểm tra booking, tạo payment INITIATED
        record Step1(Payment payment, Booking booking, boolean created) {}
        Step1 s1 = tx.execute(st -> {
            Booking b = bookings.lockById(req.bookingId()).orElseThrow(() -> BusinessException.notFound("Booking"));
            if (!b.getCustomerId().equals(customerId)) throw BusinessException.notFound("Booking"); // không lộ booking người khác
            String key = "pay-" + HmacSigner.sha256Hex(customerId + "|" + b.getId() + "|" + req.idempotencyKey()).substring(0, 32);

            var same = payments.findByIdempotencyKey(key);
            if (same.isPresent()) return new Step1(same.get(), b, false);          // cùng intent -> cùng kết quả

            if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
                throw new BusinessException(b.getStatus() == BookingStatus.EXPIRED ? ErrorCode.BOOKING_EXPIRED : ErrorCode.STATE_CONFLICT,
                        "Booking không ở trạng thái chờ thanh toán (" + b.getStatus() + ")");
            }
            if (b.getExpiresAt() == null || !b.getExpiresAt().isAfter(clock.instant())) {
                throw new BusinessException(ErrorCode.BOOKING_EXPIRED, "Booking đã hết hạn giữ chỗ");
            }
            // Cùng booking chỉ có intent mới khi intent trước đã kết thúc (FAILED)
            var open = payments.findByBookingIdOrderByIdDesc(b.getId()).stream().filter(p -> IN_FLIGHT.contains(p.getStatus())).findFirst();
            if (open.isPresent()) return new Step1(open.get(), b, false);

            Payment p = new Payment();
            p.setIntentCode(Texts.code("PI", 14));
            p.setBookingId(b.getId());
            p.setCustomerId(customerId);
            p.setIdempotencyKey(key);
            p.setAmount(b.getTotalAmount());          // số tiền lấy từ snapshot booking, không từ client
            p.setCurrency(b.getCurrency());
            p.setStatus(PaymentStatus.INITIATED);
            p.setPayerAccount(req.payerAccount());
            payments.saveAndFlush(p);
            return new Step1(p, b, true);
        });
        if (s1 == null) throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "Không khởi tạo được thanh toán");
        Payment p = s1.payment();
        if (!s1.created()) return dto(p, "Đã có giao dịch cho booking này");

        analytics.record("PAYMENT_INITIATED:" + p.getId(), "PAYMENT_INITIATED", s1.booking().getPropertyId(), "u" + customerId, String.valueOf(s1.booking().getId()), null);

        // ---- bước 2: gọi Fake Bank NGOÀI transaction
        try {
            FakeBankClient.BankTxn t = bank.debit(p.getIntentCode(), s1.booking().getBookingCode(), p.getAmount(), p.getCurrency(), p.getIdempotencyKey(), p.getPayerAccount());
            String status = "ACCEPTED".equals(t.status()) ? "PROCESSING" : t.status();
            completion.apply(new PaymentCompletionService.BankResult(p.getIdempotencyKey(), t.providerTxnId(), status, t.failureCode(),
                    t.amount(), t.currency(), "SUCCEEDED".equals(status) || "FAILED".equals(status) ? "sync-pay:" + p.getIdempotencyKey() + ":" + status : null));
        } catch (FakeBankClient.BankUnknownException e) {
            // Timeout sau khi bank có thể đã trừ tiền: KHÔNG đánh FAILED, chuyển UNKNOWN để đối soát bằng idempotencyKey.
            log.warn("bank unknown for payment {}: {}", p.getId(), e.getMessage());
            completion.apply(new PaymentCompletionService.BankResult(p.getIdempotencyKey(), null, "TIMEOUT", "TIMEOUT", null, null, null));
        }
        return dto(payments.findById(p.getId()).orElseThrow(), null);
    }

    public PaymentDto get(Long customerId, Long paymentId) {
        Payment p = payments.findByIdAndCustomerId(paymentId, customerId).orElseThrow(() -> BusinessException.notFound("Payment"));
        return dto(p, null);
    }

    static PaymentDto dto(Payment p, String msg) {
        return new PaymentDto(p.getId(), p.getIntentCode(), p.getBookingId(), p.getStatus().name(), p.getAmount(), p.getCurrency(),
                p.getFailureCode(), p.getCapturedAt(), msg);
    }
}
