package datphong.vn.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tính phí hủy từ SNAPSHOT chính sách lưu trong booking (không đọc chính sách hiện hành của HOST).
 * Trước hạn miễn phí: phí 0. Sau hạn: phí = captured * penaltyPercent / 100.
 * Tiền hoàn = captured - phí - đã hoàn (không âm). Booking chưa thu tiền: không phí, không hoàn.
 */
public final class CancellationCalculator {

    private CancellationCalculator() {}

    public record Result(BigDecimal fee, BigDecimal refundable, boolean freeCancellation) {}

    public static Result compute(Instant now, Instant freeCancelDeadline, int penaltyPercent,
                                 BigDecimal captured, BigDecimal alreadyRefunded) {
        if (penaltyPercent < 0 || penaltyPercent > 100) throw new IllegalArgumentException("penaltyPercent không hợp lệ");
        BigDecimal paid = captured == null ? Money.zero() : captured;
        BigDecimal refunded = alreadyRefunded == null ? Money.zero() : alreadyRefunded;
        if (paid.signum() == 0) return new Result(Money.zero(), Money.zero(), true);

        boolean free = !now.isAfter(freeCancelDeadline);
        BigDecimal fee = free ? Money.zero()
                : Money.vnd(paid.multiply(BigDecimal.valueOf(penaltyPercent)).divide(BigDecimal.valueOf(100)));
        BigDecimal refundable = paid.subtract(fee).subtract(refunded);
        if (refundable.signum() < 0) refundable = Money.zero();
        return new Result(fee, Money.vnd(refundable), free);
    }

    /** Bất biến: tổng hoàn (đã hoàn + đang xử lý + yêu cầu mới) không vượt số tiền thực thu. */
    public static boolean refundWithinLimit(BigDecimal captured, BigDecimal refundedOrPending, BigDecimal requested) {
        return refundedOrPending.add(requested).compareTo(captured) <= 0;
    }
}
