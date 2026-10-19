package datphong.vn.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SearchResponses {
    private SearchResponses() {}

    public record RoomTypeOffer(Long roomTypeId, String name, int capacity, String bedType, int availableRooms,
                                Long sampleRoomId, BigDecimal subtotal, BigDecimal avgNightly) {}

    public record PropertyCard(Long propertyId, String name, String city, String address, BigDecimal latitude, BigDecimal longitude,
                               String propertyType, BigDecimal fromPrice, int roomsLeft, double rating, long reviewCount,
                               String coverUrl, List<RoomTypeOffer> roomTypes) {}

    /** Kết quả tìm kiếm chỉ mang tính THAM KHẢO: không cam kết giữ phòng. */
    public record SearchPage(List<PropertyCard> items, long total, int page, int size, Instant asOf, boolean fromCache,
                             LocalDate checkIn, LocalDate checkOut, int guests) {}

    public record FlatOffer(Long propertyId, String propertyName, String city, Long roomTypeId, String roomTypeName, int capacity,
                            int availableRooms, Long sampleRoomId, BigDecimal subtotal, BigDecimal avgNightly) {}

    public record FlatPage(List<FlatOffer> items, long total, int page, int size, Instant asOf) {}

    public record NightLine(LocalDate stayDate, BigDecimal amount) {}

    public record CancellationPolicyView(Instant freeCancelUntil, int freeCancelHours, int penaltyPercent, String summary) {}

    public record QuoteDto(Long roomId, Long roomTypeId, Long propertyId, LocalDate checkIn, LocalDate checkOut, int guests,
                           int nights, List<NightLine> nightly, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total,
                           String currency, String priceVersion, Instant quoteExpiresAt, boolean availableNow,
                           CancellationPolicyView cancellationPolicy) {}

    public record PropertyDetail(Long id, String name, String description, String propertyType, String address, String city,
                                 BigDecimal latitude, BigDecimal longitude, String timezone, String checkInTime, String checkOutTime,
                                 String contactPhone, List<String> amenities, List<CatalogResponses.MediaDto> images,
                                 List<RoomTypeInfo> roomTypes, double rating, long reviewCount,
                                 CancellationPolicyView cancellationPolicy) {}

    public record RoomTypeInfo(Long id, String name, String description, int capacity, String bedType, List<String> amenities,
                               BigDecimal referencePrice) {}
}
