package datphong.vn.service;

import datphong.vn.constant.MailEventType;
import datphong.vn.constant.MailStatus;
import datphong.vn.exception.entity.MailDeliveryAttempt;
import datphong.vn.exception.entity.MailEvent;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.BookingRepository;
import datphong.vn.repository.MailDeliveryAttemptRepository;
import datphong.vn.repository.MailEventRepository;
import datphong.vn.security.AuthUser;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** UC-34: tra cứu / retry email an toàn. SUPPORT thấy email đã che; ADMIN thấy đầy đủ. Payload (biến) không bao giờ được trả ra. */
@Service
@RequiredArgsConstructor
public class MailAdminService {

    private final MailEventRepository mails;
    private final MailDeliveryAttemptRepository attempts;
    private final BookingRepository bookings;
    private final ConfigService config;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> search(AuthUser who, MailStatus status, String type, Long bookingId, Instant from, Instant to, int page, int size) {
        boolean full = who.hasRole("ADMIN");
        Instant f = from == null ? clock.instant().minus(Duration.ofDays(30)) : from;
        Instant t = to == null ? clock.instant().plusSeconds(60) : to;
        return mails.search(status, type, bookingId, f, t, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(m -> view(m, full));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(AuthUser who, Long id) {
        MailEvent m = mails.findById(id).orElseThrow(() -> BusinessException.notFound("Mail event"));
        Map<String, Object> out = new LinkedHashMap<>(view(m, who.hasRole("ADMIN")));
        out.put("attempts", attempts.findByMailEventIdOrderByAttemptNo(id).stream()
                .map(a -> Map.of("no", a.getAttemptNo(), "outcome", a.getOutcome(), "errorCode", String.valueOf(a.getErrorCode()), "startedAt", a.getStartedAt())).toList());
        audit.record("MAIL_EVENT_VIEW", "MAIL_EVENT", id, null, null, null, null); // audit truy cập
        return out;
    }

    /**
     * Gửi lại CÙNG email logical bằng delivery_attempt mới. operatorIdempotencyKey (unique với mail_event) chống hai support cùng bấm.
     * Kiểm tra email còn ý nghĩa nghiệp vụ trước khi gửi lại.
     */
    @Transactional
    public Map<String, Object> retry(AuthUser who, Long id, String operatorKey, String reason) {
        if (operatorKey == null || operatorKey.isBlank()) throw BusinessException.validation("Thiếu Idempotency-Key của thao tác");
        MailEvent m = mails.findById(id).orElseThrow(() -> BusinessException.notFound("Mail event"));
        if (attempts.existsByMailEventIdAndOperatorIdemKey(id, operatorKey)) return Map.of("status", m.getStatus().name(), "replayed", true);
        if (m.getStatus() == MailStatus.SENT || m.getStatus() == MailStatus.QUEUED || m.getStatus() == MailStatus.SENDING) {
            throw BusinessException.conflict("Email đang/đã được gửi, không cần gửi lại");
        }
        if (m.getStatus() == MailStatus.FAILED_VALIDATION) throw BusinessException.conflict("Email lỗi dữ liệu/template, cần sửa nguyên nhân trước");
        MailEventType type = MailEventType.valueOf(m.getEventType());
        if (type == MailEventType.EMAIL_VERIFICATION) {
            // link/mã hết hạn phải tạo token MỚI, không gửi lại token cũ
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Không gửi lại mã xác minh cũ; hãy yêu cầu người dùng gửi lại mã mới");
        }
        if (m.getBookingId() != null && (type == MailEventType.CHECKIN_REMINDER || type == MailEventType.BOOKING_CONFIRMED || type == MailEventType.PAYMENT_SUCCEEDED)) {
            var b = bookings.findById(m.getBookingId()).orElse(null);
            if (b == null || b.getStatus() == datphong.vn.constant.BookingStatus.CANCELLED || b.getStatus() == datphong.vn.constant.BookingStatus.EXPIRED
                    || b.getStatus() == datphong.vn.constant.BookingStatus.COMPLETED || b.getStatus() == datphong.vn.constant.BookingStatus.CHECKED_IN) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Email không còn ý nghĩa nghiệp vụ (booking " + (b == null ? "?" : b.getStatus()) + ")");
            }
        }
        // marker thao tác của operator (unique mail_event + key) - nếu hai support đua nhau, bản thứ hai vi phạm unique và bị từ chối
        MailDeliveryAttempt marker = new MailDeliveryAttempt();
        marker.setMailEventId(id);
        marker.setAttemptNo(m.getAttemptCount());
        marker.setStartedAt(clock.instant());
        marker.setOutcome("OPERATOR_RETRY");
        marker.setOperatorId(who.getId());
        marker.setOperatorIdemKey(operatorKey);
        attempts.saveAndFlush(marker);

        m.setStatus(MailStatus.QUEUED);
        m.setNextAttemptAt(clock.instant());
        m.setErrorCode(null);
        m.setUpdatedAt(clock.instant());
        audit.record("MAIL_RETRY", "MAIL_EVENT", id, "FAILED/DLQ/UNKNOWN", "QUEUED", reason, Map.of("operatorKey", operatorKey));
        return Map.of("status", "QUEUED", "replayed", false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> stats(int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : mails.statsSince(clock.instant().minus(Duration.ofDays(Math.min(Math.max(days, 1), 90))))) {
            out.add(Map.of("eventType", r[0], "status", r[1], "count", ((Number) r[2]).longValue()));
        }
        return out;
    }

    private Map<String, Object> view(MailEvent m, boolean full) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId());
        v.put("eventType", m.getEventType());
        v.put("businessId", m.getBusinessId());
        v.put("bookingId", m.getBookingId());
        v.put("recipient", full ? m.getRecipient() : Texts.maskEmail(m.getRecipient()));
        v.put("status", m.getStatus().name());
        v.put("attemptCount", m.getAttemptCount());
        v.put("errorCode", m.getErrorCode());
        v.put("providerMessageId", m.getProviderMessageId());
        v.put("dueAt", m.getDueAt());
        v.put("sentAt", m.getSentAt());
        v.put("createdAt", m.getCreatedAt());
        return v;
    }
}
