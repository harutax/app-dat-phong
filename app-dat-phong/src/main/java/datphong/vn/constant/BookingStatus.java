package datphong.vn.constant;

/** Vòng đời booking (tách biệt với PaymentStatus). */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    CANCELLATION_PENDING,
    REFUND_PENDING,
    NEEDS_ATTENTION,
    NO_SHOW
}
