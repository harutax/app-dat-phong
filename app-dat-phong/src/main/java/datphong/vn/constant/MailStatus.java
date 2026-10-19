package datphong.vn.constant;

/** Trạng thái mail_event. */
public enum MailStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    FAILED_VALIDATION,
    UNKNOWN,
    DLQ,
    CANCELLED
}
