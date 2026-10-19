package datphong.vn.service;

import datphong.vn.constant.PropertyStatus;
import datphong.vn.constant.RoomStatus;
import datphong.vn.domain.PricingCalculator;
import datphong.vn.domain.StayPeriod;
import datphong.vn.dto.response.SearchResponses.*;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** UC-08: chi tiết property và báo giá theo từng đêm. Quote KHÔNG phải cam kết tồn kho. */
@Service
@RequiredArgsConstructor
public class PublicCatalogService {

    private final PropertyRepository properties;
    private final RoomTypeRepository roomTypes;
    private final RoomRepository rooms;
    private final RoomRateRepository rates;
    private final RoomAllocationRepository allocations;
    private final MediaService media;
    private final PricingService pricing;
    private final BusinessEventService events;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PropertyDetail detail(Long id) {
        Property p = properties.findWithAmenities(id).filter(x -> x.getStatus() == PropertyStatus.PUBLISHED)
                .orElseThrow(() -> BusinessException.notFound("Cơ sở"));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(p.getTimezone())));
        List<RoomTypeInfo> types = roomTypes.findWithAmenities(id).stream().filter(t -> t.getStatus() == RoomStatus.ACTIVE).map(t -> {
            List<RoomRate> upcoming = rates.findRange(t.getId(), today, today.plusDays(30));
            BigDecimal ref = upcoming.stream().map(RoomRate::getAmount).min(BigDecimal::compareTo).orElse(null);
            return new RoomTypeInfo(t.getId(), t.getName(), t.getDescription(), t.getCapacity(), t.getBedType(),
                    t.getAmenities().stream().map(Amenity::getCode).sorted().toList(), ref);
        }).toList();
        Map<String, Object> agg = jdbc.queryForMap("SELECT COALESCE(AVG(rating),0) AS avg_r, COUNT(*) AS cnt FROM reviews WHERE property_id = :id AND status = 'PUBLISHED'",
                new MapSqlParameterSource("id", id));
        double rating = Math.round(((Number) agg.get("avg_r")).doubleValue() * 10) / 10.0;
        return new PropertyDetail(p.getId(), p.getName(), p.getDescription(), p.getPropertyType(), p.getAddress(), p.getCity(),
                p.getLatitude(), p.getLongitude(), p.getTimezone(), p.getCheckInTime().toString(), p.getCheckOutTime().toString(),
                p.getContactPhone(), p.getAmenities().stream().map(Amenity::getCode).sorted().toList(), media.list(id), types,
                rating, ((Number) agg.get("cnt")).longValue(), policyView(p, null));
    }

    @Transactional(readOnly = true)
    public QuoteDto quote(Long roomId, LocalDate checkIn, LocalDate checkOut, int guests, String sessionKey) {
        Room room = rooms.findById(roomId).orElseThrow(() -> BusinessException.notFound("Phòng"));
        Property p = properties.findById(room.getPropertyId()).filter(x -> x.getStatus() == PropertyStatus.PUBLISHED)
                .orElseThrow(() -> BusinessException.notFound("Phòng"));
        RoomType rt = roomTypes.findById(room.getRoomTypeId()).orElseThrow();
        StayPeriod period = pricing.validateStay(p, rt, checkIn, checkOut, guests);
        PricingCalculator.Quote q = pricing.price(rt, period);
        boolean available = room.getStatus() == RoomStatus.ACTIVE && rt.getStatus() == RoomStatus.ACTIVE
                && !allocations.existsOverlap(roomId, checkIn, checkOut);
        events.record("QUOTE_ISSUED", p.getId(), sessionKey, String.valueOf(roomId), null);
        return new QuoteDto(roomId, rt.getId(), p.getId(), checkIn, checkOut, guests, period.nights(),
                q.nights().stream().map(n -> new NightLine(n.stayDate(), n.amount())).toList(),
                q.subtotal(), q.tax(), q.total(), q.currency(), pricing.priceVersion(q),
                clock.instant().plus(Duration.ofMinutes(10)), available, policyView(p, checkIn));
    }

    private CancellationPolicyView policyView(Property p, LocalDate checkIn) {
        var until = checkIn == null ? null : pricing.freeCancelDeadline(p, checkIn);
        String summary = "Hủy miễn phí trước giờ nhận phòng " + p.getFreeCancelHours() + " giờ; sau đó thu phí "
                + p.getPenaltyPercent() + "% số tiền đã thanh toán.";
        return new CancellationPolicyView(until, p.getFreeCancelHours(), p.getPenaltyPercent(), summary);
    }
}
