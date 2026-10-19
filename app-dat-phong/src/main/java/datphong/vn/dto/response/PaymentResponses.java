package datphong.vn.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentResponses {
    private PaymentResponses() {}

    public record PaymentDto(Long id, String intentCode, Long bookingId, String status, BigDecimal amount, String currency,
                             String failureCode, Instant capturedAt, String message) {}

    public record CaseDto(Long id, String type, String status, Long bookingId, Long paymentId, Long refundId, String details,
                          String resolutionNote, Instant createdAt) {}
}
