package datphong.vn.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PricingCalculatorTest {

    private static Map<LocalDate, BigDecimal> rates() {
        Map<LocalDate, BigDecimal> m = new HashMap<>();
        m.put(LocalDate.parse("2026-12-01"), new BigDecimal("500000"));
        m.put(LocalDate.parse("2026-12-02"), new BigDecimal("700000"));
        m.put(LocalDate.parse("2026-12-03"), new BigDecimal("900000.50"));
        return m;
    }

    @Test
    void sumsEveryNightWithDifferentPrices() {
        var q = PricingCalculator.quote(new StayPeriod(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-04")), rates(), BigDecimal.ZERO);
        assertEquals(3, q.nights().size());
        assertEquals(new BigDecimal("2100001"), q.subtotal()); // 900000.50 làm tròn HALF_UP -> 900001
        assertEquals(q.subtotal(), q.total());
        assertEquals("VND", q.currency());
    }

    @Test
    void missingNightIsRejectedNotAssumed() {
        var ex = assertThrows(PricingCalculator.RateMissingException.class, () ->
                PricingCalculator.quote(new StayPeriod(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-05")), rates(), BigDecimal.ZERO));
        assertEquals(LocalDate.parse("2026-12-04"), ex.missingDate());
    }

    @Test
    void taxIsSeparateAndRounded() {
        var q = PricingCalculator.quote(new StayPeriod(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-02")), rates(), new BigDecimal("8"));
        assertEquals(new BigDecimal("40000"), q.tax());
        assertEquals(new BigDecimal("540000"), q.total());
    }

    @Test
    void checkoutNightIsNotCharged() {
        var q = PricingCalculator.quote(new StayPeriod(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-03")), rates(), BigDecimal.ZERO);
        assertEquals(new BigDecimal("1200000"), q.total());
    }
}
