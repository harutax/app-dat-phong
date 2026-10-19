package datphong.vn.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class CatalogResponses {
    private CatalogResponses() {}

    public record PropertyDto(Long id, Long ownerId, String name, String description, String propertyType, String address,
                              String city, BigDecimal latitude, BigDecimal longitude, String timezone, String status,
                              LocalTime checkInTime, LocalTime checkOutTime, int freeCancelHours, int penaltyPercent,
                              String contactPhone, String contactEmail, List<String> amenities, long version) {}

    public record RoomTypeDto(Long id, Long propertyId, String name, String description, int capacity, String bedType,
                              String status, List<String> amenities, long version) {}

    public record RoomDto(Long id, Long propertyId, Long roomTypeId, String roomCode, String status) {}

    public record RateDto(LocalDate stayDate, BigDecimal amount, String currency) {}

    public record MediaDto(Long id, String status, boolean cover, int sortOrder, String url) {}

    public record UploadTicket(Long assetId, String objectKey, String uploadUrl, Instant expiresAt) {}
}
