package datphong.vn.service;

import datphong.vn.client.FakeBankClient;
import datphong.vn.constant.CaseType;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.dto.response.PaymentResponses.CaseDto;
import datphong.vn.exception.entity.Payment;
import datphong.vn.exception.entity.Refund;
import datphong.vn.exception.entity.ReconciliationCase;
import datphong.vn.exception.BusinessException;
import datphong.vn.repository.PaymentRepository;
import datphong.vn.repository.ReconciliationCaseRepository;
import datphong.vn.repository.RefundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * UC-13/35/36: đối soát payment/refund với Fake Bank bằng idempotencyKey/refundKey (KHÔNG retry debit với key mới).
 * Xử lý: callback bị mất, bank success nhưng DB nội bộ lỗi, timeout (UNKNOWN), refund kẹt PROCESSING.
 * Mọi kết quả đi qua cùng điểm áp (PaymentCompletionService / RefundService) nên hành vi y hệt webhook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReconciliationCaseRepository cases;
    private final FakeBankClient bank;
    private final PaymentCompletionService completion;
    private final RefundService refundService;
    private final ConfigService config;
    private final AuditService audit;
    private final MeterRegistry metrics;
    private final Clock clock;

    /** Payment chưa ở trạng thái cuối quá ngưỡng: hỏi bank. */
    public int reconcilePayments() {
        Instant before = clock.instant().minusSeconds(config.getInt("payment.unknown.reconcileAfterSeconds"));
        List<Long> ids = payments.findStaleIds(EnumSet.of(PaymentStatus.INITIATED, PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN, PaymentStatus.TIMED_OUT),
                before, PageRequest.of(0, 50));
        int n = 0;
        for (Long id : ids) {
            try {
                if (reconcileOne(id)) n++;
            } catch (Exception e) {
                metrics.counter("scheduler_failures_total", "job", "payment_reconcile").increment();
                log.warn("reconcile payment {} failed: {}", id, e.getMessage());
            }
        }
        return n;
    }

    boolean reconcileOne(Long paymentId) {
        Payment p = payments.findById(paymentId).orElse(null);
        if (p == null) return false;
        try {
            var res = bank.lookup(p.getIdempotencyKey());
            if (res.isPresent()) {
                var t = res.get();
                String st = "ACCEPTED".equals(t.status()) ? "PROCESSING" : t.status();
                var out = completion.apply(new PaymentCompletionService.BankResult(p.getIdempotencyKey(), t.providerTxnId(), st, t.failureCode(),
                        t.amount(), t.currency(), "recon-pay:" + t.providerTxnId() + ":" + st));
                log.info("reconciled payment {} -> {} ({})", paymentId, st, out);
                return true;
            }
            // Bank KHÔNG có giao dịch: yêu cầu chưa từng tới bank. Chỉ kết luận FAILED khi đã đủ cũ.
            if (p.getCreatedAt().isBefore(clock.instant().minus(Duration.ofMinutes(2)))) {
                completion.apply(new PaymentCompletionService.BankResult(p.getIdempotencyKey(), null, "FAILED", "NOT_FOUND_AT_BANK", null, null,
                        "recon-pay-missing:" + p.getId()));
                return true;
            }
        } catch (FakeBankClient.BankUnknownException e) {
            log.warn("bank unreachable during reconcile of payment {}", paymentId); // thử lại chu kỳ sau
        }
        return false;
    }

    /** Refund đến hạn (REQUESTED / FAILED chờ retry) và refund kẹt PROCESSING. */
    public int processRefunds() {
        Instant now = clock.instant();
        int n = 0;
        for (Long id : refunds.findDispatchable(now, PageRequest.of(0, 50))) {
            try { if (refundService.process(id)) n++; }
            catch (Exception e) {
                metrics.counter("scheduler_failures_total", "job", "refund").increment();
                log.warn("process refund {} failed: {}", id, e.getMessage());
            }
        }
        for (Long id : refunds.findStuckProcessing(now.minusSeconds(config.getInt("payment.unknown.reconcileAfterSeconds")), PageRequest.of(0, 50))) {
            try { reconcileRefund(id); n++; }
            catch (Exception e) { log.warn("reconcile refund {} failed: {}", id, e.getMessage()); }
        }
        return n;
    }

    void reconcileRefund(Long refundId) {
        Refund r = refunds.findById(refundId).orElseThrow(() -> BusinessException.notFound("Refund"));
        try {
            var res = bank.lookupRefund(r.getRefundKey());
            if (res.isPresent() && ("SUCCEEDED".equals(res.get().status()) || "FAILED".equals(res.get().status()))) {
                refundService.applyResult(r.getRefundKey(), res.get().status(), res.get().providerRef(), res.get().failureCode(),
                        "recon-refund:" + r.getRefundKey() + ":" + res.get().status());
            } else if (res.isEmpty()) {
                // bank chưa nhận refund: đưa về FAILED để retry ĐÚNG refund_key cũ
                refundService.applyResult(r.getRefundKey(), "FAILED", null, "NOT_FOUND_AT_BANK", "recon-refund-missing:" + r.getRefundKey() + ":" + r.getAttempts());
            }
        } catch (FakeBankClient.BankUnknownException e) {
            log.warn("bank unreachable during reconcile of refund {}", refundId);
        }
    }

    /** SUPPORT/ADMIN: đối soát thủ công có kiểm soát (không sửa DB tay) - luôn có lý do + audit. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void manualReconcileRefund(Long refundId, String reason) {
        audit.attempt("REFUND_RECONCILE", "REFUND", refundId, reason, Map.of(), "ATTEMPT");
        reconcileRefund(refundId);
        audit.attempt("REFUND_RECONCILE", "REFUND", refundId, reason, Map.of(), "SUCCESS");
    }

    @Transactional(readOnly = true)
    public Page<CaseDto> openCases(int page, int size) {
        return cases.findByStatusOrderByIdDesc("OPEN", PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))).map(this::dto);
    }

    @Transactional
    public CaseDto resolve(Long caseId, Long actorId, String note) {
        ReconciliationCase c = cases.findById(caseId).orElseThrow(() -> BusinessException.notFound("Ca đối soát"));
        if (c.getType() == CaseType.NEEDS_REFUND && c.getRefundId() == null && "OPEN".equals(c.getStatus())) {
            // ca cần hoàn tiền chỉ được đóng khi refund tương ứng đã SUCCEEDED (đóng tự động) - chặn đóng tay làm mất dấu tiền
            throw new BusinessException(datphong.vn.exception.ErrorCode.BUSINESS_RULE_VIOLATION, "Ca NEEDS_REFUND chỉ đóng khi hoàn tiền thành công");
        }
        c.setStatus("RESOLVED");
        c.setResolutionNote(note);
        c.setResolvedBy(actorId);
        c.setResolvedAt(clock.instant());
        audit.record("CASE_RESOLVE", "RECON_CASE", caseId, "OPEN", "RESOLVED", note, null);
        return dto(c);
    }

    private CaseDto dto(ReconciliationCase c) {
        return new CaseDto(c.getId(), c.getType().name(), c.getStatus(), c.getBookingId(), c.getPaymentId(), c.getRefundId(), c.getDetails(), c.getResolutionNote(), c.getCreatedAt());
    }
}
