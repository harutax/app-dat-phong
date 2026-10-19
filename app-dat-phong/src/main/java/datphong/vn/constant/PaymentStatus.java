package datphong.vn.constant;

/** Vòng đời payment. UNKNOWN = chưa xác định, cần đối soát, KHÔNG coi là FAILED. */
public enum PaymentStatus {
    INITIATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    UNKNOWN
}
