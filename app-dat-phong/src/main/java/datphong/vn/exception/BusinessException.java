package datphong.vn.exception;

import lombok.Getter;

/** Lỗi nghiệp vụ có mã. details (nếu có) được trả nguyên cho client - tuyệt đối không nhét dữ liệu nhạy cảm. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object details;

    public BusinessException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public BusinessException(ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public static BusinessException notFound(String what) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, what + " không tồn tại");
    }

    public static BusinessException forbidden(String msg) {
        return new BusinessException(ErrorCode.FORBIDDEN_SCOPE, msg);
    }

    public static BusinessException validation(String msg) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, msg);
    }

    public static BusinessException conflict(String msg) {
        return new BusinessException(ErrorCode.STATE_CONFLICT, msg);
    }

    /** Dừng stack trace để ném lỗi nghiệp vụ rẻ hơn (không cần trace). */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
