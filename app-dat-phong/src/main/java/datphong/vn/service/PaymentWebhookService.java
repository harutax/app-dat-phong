package datphong.vn.service;

import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/** Phân tích payload webhook đã xác thực chữ ký và chuyển cho dịch vụ áp kết quả. */
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final PaymentCompletionService completion;
    private final RefundService refunds;

    public String handle(String rawBody) {
        Map<String, Object> m = Json.readMap(rawBody);
        String type = String.valueOf(m.get("type"));
        String eventId = (String) m.get("eventId");
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Thiếu eventId");
        if ("REFUND_RESULT".equals(type)) {
            refunds.applyResult((String) m.get("refundKey"), String.valueOf(m.get("status")), (String) m.get("providerRef"),
                    (String) m.get("failureCode"), eventId);
            return "REFUND_APPLIED";
        }
        BigDecimal amount = m.get("amount") == null ? null : new BigDecimal(String.valueOf(m.get("amount")));
        var out = completion.apply(new PaymentCompletionService.BankResult((String) m.get("idempotencyKey"), (String) m.get("providerTxnId"),
                String.valueOf(m.get("status")), (String) m.get("failureCode"), amount, (String) m.get("currency"), eventId));
        return out.name();
    }
}
