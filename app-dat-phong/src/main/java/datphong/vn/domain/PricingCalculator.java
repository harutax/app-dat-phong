package datphong.vn.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tính giá từng đêm bằng BigDecimal. Thiếu giá của BẤT KỲ đêm nào -> RateMissingException,
 * không bao giờ giả định base rate.
 */
public final class PricingCalculator {

    private PricingCalculator() {}

    public record Quote(List<NightPrice> nights, BigDecimal subtotal, BigDecimal tax, BigDecimal total, String currency) {}

    public record NightPrice(LocalDate stayDate, BigDecimal amount) {}

    public static class RateMissingException extends RuntimeException {
        private final LocalDate missingDate;

        public RateMissingException(LocalDate d) {
            super("Chưa cấu hình giá cho đêm " + d);
            this.missingDate = d;
        }

        public LocalDate missingDate() {
            return missingDate;
        }
    }

    /**
     * @param ratesByDate giá hiện hành theo stay_date của loại phòng
     * @param taxPercent  % thuế/phụ phí (0 nếu không áp dụng), hiển thị tách riêng
     */
    public static Quote quote(StayPeriod period, Map<LocalDate, BigDecimal> ratesByDate, BigDecimal taxPercent) {
        List<NightPrice> nights = period.stayDates().stream().map(d -> {
            BigDecimal r = ratesByDate.get(d);
            if (r == null) throw new RateMissingException(d);
            if (r.signum() < 0) throw new IllegalArgumentException("Giá âm tại " + d);
            return new NightPrice(d, Money.vnd(r));
        }).toList();
        BigDecimal subtotal = nights.stream().map(NightPrice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pct = taxPercent == null ? BigDecimal.ZERO : taxPercent;
        BigDecimal tax = Money.vnd(subtotal.multiply(pct).divide(BigDecimal.valueOf(100)));
        return new Quote(nights, Money.vnd(subtotal), tax, Money.vnd(subtotal.add(tax)), Money.VND);
    }
}
