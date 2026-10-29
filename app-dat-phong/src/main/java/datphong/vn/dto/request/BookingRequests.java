package datphong.vn.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public final class BookingRequests {
    private BookingRequests() {}

    /** totalAmount KHÔNG có trong request: server tự tính. expectedTotal/priceVersion chỉ dùng để đối chiếu (PRICE_CHANGED). */
    public record Create(@NotNull Long roomId,
                         @NotNull LocalDate checkIn,
                         @NotNull LocalDate checkOut,
                         @NotNull @Min(1) @Max(50) Integer guestCount,
                         @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
                         BigDecimal expectedTotal,
                         @Size(max = 40) String priceVersion,
                         @Size(max = 1000) String specialNote) {}

    public record Cancel(@NotBlank @Size(min = 8, max = 100) String idempotencyKey, @NotBlank @Size(max = 500) String reason) {}

    /** UC-14: HOST chỉ được sửa giờ đến dự kiến / ghi chú / yêu cầu đặc biệt; không có trường tiền hay trạng thái thanh toán. */
    public record HostUpdate(@Size(max = 1000) String specialNote, @Size(max = 1000) String hostNote, LocalTime expectedArrivalTime,
                             @NotNull Long version) {}

    public record PayInit(@NotNull Long bookingId, @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
                          @NotBlank @Size(max = 40) String payerAccount) {}

    public record Review(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 3000) String comment) {}

    public record NoShow(@NotBlank @Size(max = 500) String reason) {}

    public record SupportAction(@NotBlank @Size(max = 500) String reason) {}
}
