package datphong.vn.domain;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.constant.RefundStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static datphong.vn.constant.BookingStatus.*;

/**
 * State machine tập trung cho Booking / Payment / Refund. Mọi chuyển trạng thái trong hệ thống
 * phải đi qua canTransition() - ba tầng bảo vệ: DB constraint (chồng lấn), state machine (transition), idempotency (retry).
 */
public final class Transitions {

    private Transitions() {}

    private static final Map<BookingStatus, Set<BookingStatus>> BOOKING = new EnumMap<>(BookingStatus.class);
    private static final Map<PaymentStatus, Set<PaymentStatus>> PAYMENT = new EnumMap<>(PaymentStatus.class);
    private static final Map<RefundStatus, Set<RefundStatus>> REFUND = new EnumMap<>(RefundStatus.class);

    static {
        BOOKING.put(PENDING_PAYMENT, EnumSet.of(CONFIRMED, EXPIRED, CANCELLED));
        BOOKING.put(CONFIRMED, EnumSet.of(CHECKED_IN, CANCELLED, REFUND_PENDING, NO_SHOW));
        BOOKING.put(CHECKED_IN, EnumSet.of(COMPLETED, CANCELLED, REFUND_PENDING));
        BOOKING.put(REFUND_PENDING, EnumSet.of(CANCELLED, NEEDS_ATTENTION));
        BOOKING.put(EXPIRED, EnumSet.of(NEEDS_ATTENTION));
        BOOKING.put(NEEDS_ATTENTION, EnumSet.of(EXPIRED, CANCELLED));
        BOOKING.put(COMPLETED, EnumSet.noneOf(BookingStatus.class));
        BOOKING.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        BOOKING.put(NO_SHOW, EnumSet.noneOf(BookingStatus.class));
        BOOKING.put(CANCELLATION_PENDING, EnumSet.of(CANCELLED, REFUND_PENDING));

        PAYMENT.put(PaymentStatus.INITIATED, EnumSet.of(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.UNKNOWN));
        PAYMENT.put(PaymentStatus.PROCESSING, EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.TIMED_OUT, PaymentStatus.UNKNOWN));
        PAYMENT.put(PaymentStatus.UNKNOWN, EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.TIMED_OUT));
        PAYMENT.put(PaymentStatus.TIMED_OUT, EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED));
        // SUCCEEDED tuyệt đối không quay về FAILED; FAILED là trạng thái cuối.
        PAYMENT.put(PaymentStatus.SUCCEEDED, EnumSet.noneOf(PaymentStatus.class));
        PAYMENT.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));

        REFUND.put(RefundStatus.REQUESTED, EnumSet.of(RefundStatus.PROCESSING));
        REFUND.put(RefundStatus.PROCESSING, EnumSet.of(RefundStatus.SUCCEEDED, RefundStatus.FAILED));
        // retry giữ nguyên refund_key: FAILED -> PROCESSING
        REFUND.put(RefundStatus.FAILED, EnumSet.of(RefundStatus.PROCESSING));
        REFUND.put(RefundStatus.SUCCEEDED, EnumSet.noneOf(RefundStatus.class));
    }

    public static boolean canTransition(BookingStatus from, BookingStatus to) {
        return BOOKING.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return PAYMENT.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(RefundStatus from, RefundStatus to) {
        return REFUND.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Trạng thái chiếm kho. PENDING_PAYMENT chiếm khi hold còn hiệu lực (allocation active tới khi job/hủy giải phóng).
     * Quy ước sản phẩm: hủy -> giải phóng phòng NGAY lúc chốt hủy; hoàn tiền chỉ là luồng tiền (REFUND_PENDING không giữ phòng).
     */
    public static boolean occupiesInventory(BookingStatus s) {
        return s == PENDING_PAYMENT || s == CONFIRMED || s == CHECKED_IN;
    }

    /** Trạng thái được tính vào room-night đã chiếm trong báo cáo công suất. */
    public static boolean countsAsOccupied(BookingStatus s) {
        return s == CONFIRMED || s == CHECKED_IN || s == COMPLETED;
    }
}
