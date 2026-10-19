package datphong.vn.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lỗi JSON thống nhất: {timestamp, traceId, code, message, details}.
 * 400 input sai, 401/403 auth, 404 tài nguyên ẩn, 409 trạng thái/xung đột, 422 vi phạm quy tắc, 429 rate limit.
 * Không trả stack trace cho client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("traceId", MDC.get("traceId"));
        m.put("code", code);
        m.put("message", message);
        if (details != null) m.put("details", details);
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException ex) {
        return body(ex.getCode().status(), ex.getCode().name(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> constraint(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class, IllegalArgumentException.class})
    public ResponseEntity<?> badInput(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ex instanceof IllegalArgumentException && ex.getMessage() != null ? ex.getMessage() : "Dữ liệu gửi lên không hợp lệ", null);
    }

    @ExceptionHandler({BadCredentialsException.class, InternalAuthenticationServiceException.class})
    public ResponseEntity<?> badCredentials(Exception ex) {
        // Không tiết lộ email có tồn tại hay mật khẩu đúng hay không.
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Thông tin đăng nhập không chính xác", null);
    }

    @ExceptionHandler({LockedException.class, DisabledException.class})
    public ResponseEntity<?> locked(Exception ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Tài khoản không thể đăng nhập", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Bạn không có quyền thực hiện thao tác này", null);
    }

    /** @Version bị lệch: hai người cùng sửa một bản ghi. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> optimistic(ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Dữ liệu đã được thay đổi bởi người khác, hãy tải lại", null);
    }

    /** Hết thời gian chờ lock / deadlock sau khi đã retry: 503 có mã rõ ràng, KHÔNG phải 500 mơ hồ. */
    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<?> lock(Exception ex) {
        log.warn("lock contention: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "Hệ thống đang bận, vui lòng thử lại", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> integrity(DataIntegrityViolationException ex) {
        log.warn("integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "STATE_CONFLICT", "Dữ liệu trùng hoặc không thỏa ràng buộc", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "VALIDATION_ERROR", "Phương thức không được hỗ trợ", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> noResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex) {
        log.error("Unhandled {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi hệ thống", null);
    }
}
