package datphong.vn.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class BookingResponses {
    private BookingResponses() {}

    public record Night(LocalDate stayDate, BigDecimal nightlyRate) {}

    public record PaymentView(Long id, String status, BigDecimal amount, String failureCode, Instant capturedAt) {}

    public record StatusEntry(String from, String to, String actorType, String reason, Instant at) {}

    public record BookingDto(Long id, String bookingCode, String status, Long propertyId, String propertyName, Long roomId,
                             String roomCode, Long roomTypeId, String roomTypeName, LocalDate checkIn, LocalDate checkOut,
                             int guestCount, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal totalAmount, String currency,
                             Instant expiresAt, Instant freeCancelDeadline, int penaltyPercent, String specialNote,
                             Instant cancelledAt, String cancelReason, BigDecimal cancellationFee, BigDecimal refundableAmount,
                             Instant actualCheckIn, Instant actualCheckOut, List<Night> nights, PaymentView payment,
                             BigDecimal capturedAmount, BigDecimal refundedAmount, String paymentInstructions, long version,
                             Instant createdAt) {}

    /** Góc nhìn của HOST: email che, số điện thoại chỉ hiện khi booking đang hiệu lực. */
    public record HostBookingDto(BookingDto booking, String customerName, String customerEmailMasked, String customerPhone,
                                 LocalTime expectedArrivalTime, String hostNote, List<StatusEntry> history) {}

    public record CancelPreview(Long bookingId, boolean freeCancellation, BigDecimal capturedAmount, BigDecimal cancellationFee,
                                BigDecimal refundableAmount, Instant freeCancelDeadline, int penaltyPercent, String status) {}

    public record CancelResult(Long bookingId, String status, BigDecimal cancellationFee, BigDecimal refundableAmount,
                               Long refundId, boolean replayed) {}
}
