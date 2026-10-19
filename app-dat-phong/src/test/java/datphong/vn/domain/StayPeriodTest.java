package datphong.vn.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class StayPeriodTest {

    private static StayPeriod p(String a, String b) {
        return new StayPeriod(LocalDate.parse(a), LocalDate.parse(b));
    }

    @Test
    void threeNightsCountedWithoutCheckoutNight() {
        StayPeriod s = p("2026-12-01", "2026-12-04");
        assertEquals(3, s.nights());
        assertEquals(3, s.stayDates().size());
        assertEquals(LocalDate.parse("2026-12-03"), s.stayDates().get(2));
    }

    @Test
    void checkoutDayEqualsNextCheckinDoesNotOverlap() {
        assertFalse(p("2026-12-01", "2026-12-04").overlaps(p("2026-12-04", "2026-12-06")));
        assertFalse(p("2026-12-04", "2026-12-06").overlaps(p("2026-12-01", "2026-12-04")));
    }

    @Test
    void realOverlapDetected() {
        assertTrue(p("2026-12-01", "2026-12-04").overlaps(p("2026-12-03", "2026-12-05")));
        assertTrue(p("2026-12-01", "2026-12-10").overlaps(p("2026-12-03", "2026-12-04")));
    }

    @Test
    void checkoutMustBeAfterCheckin() {
        assertThrows(IllegalArgumentException.class, () -> p("2026-12-01", "2026-12-01"));
        assertThrows(IllegalArgumentException.class, () -> p("2026-12-02", "2026-12-01"));
    }

    @Test
    void leapDayIsCounted() {
        assertEquals(2, p("2028-02-28", "2028-03-01").nights());
    }
}
