package datphong.vn.exception;

import org.springframework.http.HttpStatus;

/** Mã lỗi nghiệp vụ thống nhất + HTTP status tương ứng (theo đặc tả mục 7 và 11.1). */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN_SCOPE(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    STATE_CONFLICT(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
    VERIFICATION_INVALID(HttpStatus.BAD_REQUEST),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST),
    ACCOUNT_NOT_VERIFIED(HttpStatus.UNAUTHORIZED),

    PROPERTY_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY),
    ROOM_CODE_DUPLICATE(HttpStatus.CONFLICT),
    ROOM_HAS_FUTURE_BOOKINGS(HttpStatus.CONFLICT),
    MEDIA_INVALID(HttpStatus.BAD_REQUEST),

    RATE_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_ENTITY),
    ROOM_UNAVAILABLE(HttpStatus.CONFLICT),
    PRICE_CHANGED(HttpStatus.CONFLICT),
    BOOKING_EXPIRED(HttpStatus.CONFLICT),
    CANCELLATION_NOT_ALLOWED(HttpStatus.CONFLICT),
    CHECKIN_NOT_ALLOWED(HttpStatus.CONFLICT),
    PAYMENT_REQUIRES_REVIEW(HttpStatus.CONFLICT),
    REFUND_AMOUNT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    REVIEW_NOT_ELIGIBLE(HttpStatus.CONFLICT),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
