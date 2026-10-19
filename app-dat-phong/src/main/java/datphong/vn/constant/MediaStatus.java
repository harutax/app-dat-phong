package datphong.vn.constant;

/** Vòng đời ảnh MinIO. */
public enum MediaStatus {
    PENDING,
    READY,
    DELETING,
    DELETED,
    PENDING_EXPIRED,
    FAILED
}
