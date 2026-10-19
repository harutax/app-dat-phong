package datphong.vn.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CancellationCalculatorTest {

    private static final Instant DEADLINE = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void freeBeforeDeadlineRefundsEverything() {
        var r = CancellationCalculator.compute(DEADLINE.minusSeconds(60), DEADLINE, 50, new BigDecimal("1000000"), BigDecimal.ZERO);
        assertTrue(r.freeCancellation());
        assertEquals(new BigDecimal("0"), r.fee());
        assertEquals(new BigDecimal("1000000"), r.refundable());
    }

    @Test
    void afterDeadlineAppliesSnapshotPenalty() {
        var r = CancellationCalculator.compute(DEADLINE.plusSeconds(1), DEADLINE, 50, new BigDecimal("1000000"), BigDecimal.ZERO);
        assertFalse(r.freeCancellation());
        assertEquals(new BigDecimal("500000"), r.fee());
        assertEquals(new BigDecimal("500000"), r.refundable());
    }

    @Test
    void fullPenaltyMeansNoRefund() {
        var r = CancellationCalculator.compute(DEADLINE.plusSeconds(1), DEADLINE, 100, new BigDecimal("1000000"), BigDecimal.ZERO);
        assertEquals(0, r.refundable().signum());
    }

    @Test
    void unpaidBookingHasNoFeeAndNoRefund() {
        var r = CancellationCalculator.compute(DEADLINE.plusSeconds(1), DEADLINE, 100, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(0, r.fee().signum());
        assertEquals(0, r.refundable().signum());
    }

    @Test
    void earlierRefundsAreSubtracted() {
        var r = CancellationCalculator.compute(DEADLINE.minusSeconds(1), DEADLINE, 50, new BigDecimal("1000000"), new BigDecimal("300000"));
        assertEquals(new BigDecimal("700000"), r.refundable());
    }

    @Test
    void refundLimitInvariant() {
        BigDecimal captured = new BigDecimal("1000000");
        assertTrue(CancellationCalculator.refundWithinLimit(captured, new BigDecimal("400000"), new BigDecimal("600000")));
        assertFalse(CancellationCalculator.refundWithinLimit(captured, new BigDecimal("400000"), new BigDecimal("600001")));
    }
}
