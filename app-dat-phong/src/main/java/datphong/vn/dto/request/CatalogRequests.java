package datphong.vn.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public final class CatalogRequests {
    private CatalogRequests() {}

    public record PropertyUpsert(@NotBlank @Size(max = 200) String name,
                                 @Size(max = 5000) String description,
                                 @Size(max = 30) String propertyType,
                                 @Size(max = 500) String address,
                                 @Size(max = 100) String city,
                                 @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                                 @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                                 @Size(max = 50) String timezone,
                                 LocalTime checkInTime,
                                 LocalTime checkOutTime,
                                 @Min(0) @Max(720) Integer freeCancelHours,
                                 @Min(0) @Max(100) Integer penaltyPercent,
                                 @Size(max = 30) String contactPhone,
                                 @Email @Size(max = 255) String contactEmail,
                                 Set<String> amenityCodes,
                                 Long version) {}

    public record RoomTypeUpsert(@NotNull Long propertyId,
                                 @NotBlank @Size(max = 150) String name,
                                 @Size(max = 3000) String description,
                                 @NotNull @Min(1) @Max(50) Integer capacity,
                                 @Size(max = 50) String bedType,
                                 Set<String> amenityCodes,
                                 Long version) {}

    public record RoomCreate(@NotNull Long propertyId, @NotNull Long roomTypeId,
                             @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String roomCode) {}

    public record RoomStatusUpdate(@NotBlank String status) {}

    /** Đặt giá cho các đêm [from, to) của một loại phòng. */
    public record RateUpsert(@NotNull Long roomTypeId, @NotNull LocalDate from, @NotNull LocalDate to,
                             @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 0) BigDecimal amount) {}

    public record UploadInit(@NotNull Long propertyId, Long roomTypeId,
                             @NotBlank @Size(max = 255) String filename,
                             @NotNull @Min(1) Long size,
                             @NotBlank String contentType) {}

    public record UploadFinalize(@NotNull Long assetId) {}
}
