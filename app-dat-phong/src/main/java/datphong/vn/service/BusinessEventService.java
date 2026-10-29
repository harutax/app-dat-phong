package datphong.vn.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Event log analytics append-only (UC-25). Ghi theo kiểu best-effort: lỗi ghi event KHÔNG được làm hỏng nghiệp vụ,
 * nên analytics phải công bố coverage thay vì suy diễn funnel từ riêng bảng booking.
 * event_id unique: nghiệp vụ truyền eventId xác định (vd. PAYMENT_CAPTURED:{paymentId}) thì retry không đếm trùng.
 *
 * NOT_SUPPORTED + INSERT ... ON CONFLICT DO NOTHING ở chế độ autocommit: không tham gia transaction của caller
 * (không ghi vào transaction read-only, không giữ lock chung) và KHÔNG có commit nào có thể ném lỗi ngược về nghiệp vụ.
 * (Bản cũ dùng REQUIRES_NEW + JPA save: vi phạm unique event_id khi hai luồng ghi cùng lúc nổ ở lúc commit, ngoài try/catch,
 * làm hỏng cả giao dịch thanh toán/booking đang gọi.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessEventService {

    private static final String SQL = "INSERT INTO business_events (event_id, event_type, property_id, session_key, ref_id, code, event_time, ingested_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void record(String type, Long propertyId, String sessionKey, String refId, String code) {
        insert(type + ":" + UUID.randomUUID(), type, propertyId, sessionKey, refId, code);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void record(String eventId, String type, Long propertyId, String sessionKey, String refId, String code) {
        insert(eventId, type, propertyId, sessionKey, refId, code);
    }

    private void insert(String eventId, String type, Long propertyId, String sessionKey, String refId, String code) {
        try {
            Instant now = clock.instant();
            jdbc.update(SQL, cut(eventId, 80), cut(type, 40), propertyId, cut(sessionKey, 80), cut(refId, 60), cut(code, 60),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (Exception ex) {
            log.warn("business event {} dropped: {}", type, ex.getMessage());
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(max, s.length()));
    }
}
