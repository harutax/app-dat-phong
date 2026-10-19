package datphong.vn.service;

import datphong.vn.exception.entity.BusinessEvent;
import datphong.vn.repository.BusinessEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Event log analytics append-only (UC-25). Ghi theo kiểu best-effort: lỗi ghi event KHÔNG được làm hỏng nghiệp vụ,
 * nên analytics phải công bố coverage thay vì suy diễn funnel từ riêng bảng booking.
 * event_id unique: nghiệp vụ truyền eventId xác định (vd. PAYMENT_CAPTURED:{paymentId}) thì retry không đếm trùng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessEventService {

    private final BusinessEventRepository repo;
    private final Clock clock;

    /**
     * Cả hai overload đều REQUIRES_NEW và KHÔNG gọi lẫn nhau (self-invocation sẽ bỏ qua proxy): event được ghi trong
     * transaction riêng, nên không bao giờ ghi vào transaction read-only của caller và lỗi ghi event không làm hỏng nghiệp vụ.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String type, Long propertyId, String sessionKey, String refId, String code) {
        insert(type + ":" + UUID.randomUUID(), type, propertyId, sessionKey, refId, code);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventId, String type, Long propertyId, String sessionKey, String refId, String code) {
        insert(eventId, type, propertyId, sessionKey, refId, code);
    }

    private void insert(String eventId, String type, Long propertyId, String sessionKey, String refId, String code) {
        try {
            if (repo.existsByEventId(eventId)) return;
            BusinessEvent e = new BusinessEvent();
            e.setEventId(eventId);
            e.setEventType(type);
            e.setPropertyId(propertyId);
            e.setSessionKey(sessionKey);
            e.setRefId(refId);
            e.setCode(code);
            e.setEventTime(clock.instant());
            e.setIngestedAt(clock.instant());
            repo.save(e);
        } catch (Exception ex) {
            log.warn("business event {} dropped: {}", type, ex.getMessage());
        }
    }
}
