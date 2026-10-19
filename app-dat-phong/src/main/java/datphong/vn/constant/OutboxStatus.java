package datphong.vn.constant;

/** Trạng thái outbox. */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
