package datphong.vn.domain;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.PaymentStatus;
import datphong.vn.constant.RefundStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionsTest {

    @Test
    void expiredBookingCanNeverBecomeConfirmed() {
        assertFalse(Transitions.canTransition(BookingStatus.EXPIRED, BookingStatus.CONFIRMED));
        assertTrue(Transitions.canTransition(BookingStatus.EXPIRED, BookingStatus.NEEDS_ATTENTION));
    }

    @Test
    void happyPathBooking() {
        assertTrue(Transitions.canTransition(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED));
        assertTrue(Transitions.canTransition(BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN));
        assertTrue(Transitions.canTransition(BookingStatus.CHECKED_IN, BookingStatus.COMPLETED));
        assertFalse(Transitions.canTransition(BookingStatus.COMPLETED, BookingStatus.CANCELLED));
        assertFalse(Transitions.canTransition(BookingStatus.PENDING_PAYMENT, BookingStatus.CHECKED_IN));
    }

    @Test
    void succeededPaymentNeverGoesBack() {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertFalse(Transitions.canTransition(PaymentStatus.SUCCEEDED, to), "SUCCEEDED -> " + to);
        }
        assertTrue(Transitions.canTransition(PaymentStatus.UNKNOWN, PaymentStatus.SUCCEEDED));
        assertTrue(Transitions.canTransition(PaymentStatus.TIMED_OUT, PaymentStatus.SUCCEEDED));
    }

    @Test
    void refundRetryKeepsSameRecord() {
        assertTrue(Transitions.canTransition(RefundStatus.FAILED, RefundStatus.PROCESSING));
        assertFalse(Transitions.canTransition(RefundStatus.SUCCEEDED, RefundStatus.PROCESSING));
    }

    @Test
    void inventoryOccupancyRules() {
        assertTrue(Transitions.occupiesInventory(BookingStatus.PENDING_PAYMENT));
        assertTrue(Transitions.occupiesInventory(BookingStatus.CONFIRMED));
        assertTrue(Transitions.occupiesInventory(BookingStatus.CHECKED_IN));
        assertFalse(Transitions.occupiesInventory(BookingStatus.EXPIRED));
        assertFalse(Transitions.occupiesInventory(BookingStatus.CANCELLED));
        assertFalse(Transitions.occupiesInventory(BookingStatus.REFUND_PENDING));
    }
}
