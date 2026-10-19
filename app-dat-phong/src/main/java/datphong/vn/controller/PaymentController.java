package datphong.vn.controller;

import datphong.vn.config.AppProperties;
import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.dto.response.PaymentResponses.PaymentDto;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.PaymentService;
import datphong.vn.service.PaymentWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final PaymentWebhookService webhook;
    private final AppProperties props;

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public PaymentDto initiate(@Valid @RequestBody BookingRequests.PayInit req) {
        return payments.initiate(CurrentUser.id(), req);
    }

    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public PaymentDto get(@PathVariable Long id) {
        return payments.get(CurrentUser.id(), id);
    }

    /**
     * Webhook ký HMAC: chữ ký = HMAC(webhookSecret, timestamp + "." + rawBody), timestamp trong replay window,
     * eventId unique. KHÔNG tin status/amount do client báo - chỉ nhận từ chữ ký của Fake Bank.
     * Trả 2xx khi đã xử lý hoặc là bản lặp; 5xx để bank gửi lại khi lỗi nội bộ (at-least-once).
     */
    @PostMapping("/payments/fakebank/webhook")
    public ResponseEntity<Map<String, String>> webhook(@RequestBody String rawBody,
                                                       @RequestHeader("X-Timestamp") String timestamp,
                                                       @RequestHeader("X-Signature") String signature) {
        long ts;
        try { ts = Long.parseLong(timestamp); } catch (NumberFormatException e) { throw new BusinessException(ErrorCode.UNAUTHORIZED, "Timestamp không hợp lệ"); }
        long skew = Math.abs(System.currentTimeMillis() / 1000 - ts);
        if (skew > props.getFakeBank().getWebhookReplayWindowSeconds()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Webhook ngoài cửa sổ replay");
        }
        if (!HmacSigner.verify(props.getFakeBank().getWebhookSecret(), timestamp + "." + rawBody, signature)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Chữ ký webhook không hợp lệ");
        }
        String outcome = webhook.handle(rawBody);
        return ResponseEntity.ok(Map.of("status", "OK", "outcome", outcome));
    }
}
