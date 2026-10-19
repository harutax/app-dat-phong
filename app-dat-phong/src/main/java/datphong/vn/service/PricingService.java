package datphong.vn.service;

import datphong.vn.domain.HmacSigner;
import datphong.vn.domain.PricingCalculator;
import datphong.vn.domain.StayPeriod;
import datphong.vn.exception.entity.Property;
import datphong.vn.exception.entity.RoomRate;
import datphong.vn.exception.entity.RoomType;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.RoomRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.stream.Collectors;

/** Tính giá từng đêm (BigDecimal), xác thực khoảng lưu trú theo múi giờ cơ sở, chốt hạn hủy miễn phí. */
@Service
@RequiredArgsConstructor
public class PricingService {

    public static final int MAX_NIGHTS = 60;

    private final RoomRateRepository rates;
    private final ConfigService config;
    private final Clock clock;

    public StayPeriod validateStay(Property p, RoomType rt, LocalDate checkIn, LocalDate checkOut, int guests) {
        StayPeriod period;
        try {
            period = new StayPeriod(checkIn, checkOut);
        } catch (IllegalArgumentException e) {
            throw BusinessException.validation(e.getMessage());
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(p.getTimezone())));
        if (checkIn.isBefore(today)) throw BusinessException.validation("Ngày nhận phòng không được ở quá khứ");
        if (period.nights() > MAX_NIGHTS) throw BusinessException.validation("Tối đa " + MAX_NIGHTS + " đêm mỗi booking");
        if (guests < 1 || guests > rt.getCapacity()) throw BusinessException.validation("Số khách vượt sức chứa (" + rt.getCapacity() + ")");
        return period;
    }

    public PricingCalculator.Quote price(RoomType rt, StayPeriod period) {
        Map<LocalDate, BigDecimal> byDate = rates.findRange(rt.getId(), period.checkIn(), period.checkOut()).stream()
                .collect(Collectors.toMap(RoomRate::getStayDate, RoomRate::getAmount));
        try {
            return PricingCalculator.quote(period, byDate, config.getDecimal("pricing.taxPercent"));
        } catch (PricingCalculator.RateMissingException e) {
            throw new BusinessException(ErrorCode.RATE_NOT_CONFIGURED, e.getMessage(), Map.of("missingDate", e.missingDate().toString()));
        }
    }

    /** priceVersion: băm của giá từng đêm + thuế - client gửi lại khi đặt để phát hiện PRICE_CHANGED. */
    public String priceVersion(PricingCalculator.Quote q) {
        StringBuilder sb = new StringBuilder();
        q.nights().forEach(n -> sb.append(n.stayDate()).append(':').append(n.amount().toPlainString()).append(';'));
        sb.append("tax=").append(q.tax().toPlainString());
        return HmacSigner.sha256Hex(sb.toString()).substring(0, 16);
    }

    /** Hạn hủy miễn phí = (ngày nhận phòng + giờ check-in theo múi giờ cơ sở) - freeCancelHours; lưu UTC Instant. */
    public Instant freeCancelDeadline(Property p, LocalDate checkIn) {
        ZonedDateTime checkInAt = ZonedDateTime.of(checkIn, p.getCheckInTime(), ZoneId.of(p.getTimezone()));
        return checkInAt.minusHours(p.getFreeCancelHours()).toInstant();
    }

    /** Thời điểm nhận phòng dự kiến (mặc định theo property) dùng cho nhắc check-in và cửa sổ check-in. */
    public Instant scheduledCheckIn(Property p, LocalDate checkIn) {
        return ZonedDateTime.of(checkIn, p.getCheckInTime(), ZoneId.of(p.getTimezone())).toInstant();
    }

    public Instant scheduledCheckOut(Property p, LocalDate checkOut) {
        return ZonedDateTime.of(checkOut, p.getCheckOutTime(), ZoneId.of(p.getTimezone())).toInstant();
    }
}
