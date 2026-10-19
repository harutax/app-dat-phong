package datphong.vn.service;

import datphong.vn.constant.OutboxStatus;
import datphong.vn.exception.entity.OutboxEvent;
import datphong.vn.repository.OutboxEventRepository;
import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Ghi outbox TRONG CÙNG transaction nghiệp vụ (MANDATORY): transaction rollback thì sự kiện cũng không tồn tại,
 * nên không bao giờ có thông báo "thành công" cho thao tác thất bại.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repo;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, String aggregateType, Object aggregateId, Map<String, Object> payload) {
        publish(eventType + ":" + aggregateId + ":" + UUID.randomUUID(), eventType, aggregateType, aggregateId, payload);
    }

    /** eventId do nghiệp vụ chọn khi cần dedupe (vd. PAYMENT_SUCCEEDED:{paymentId}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventId, String eventType, String aggregateType, Object aggregateId, Map<String, Object> payload) {
        if (repo.existsByEventId(eventId)) return;
        OutboxEvent e = new OutboxEvent();
        e.setEventId(eventId);
        e.setEventType(eventType);
        e.setAggregateType(aggregateType);
        e.setAggregateId(String.valueOf(aggregateId));
        e.setPayload(Json.write(payload));
        e.setStatus(OutboxStatus.PENDING);
        e.setNextAttemptAt(clock.instant());
        e.setCreatedAt(clock.instant());
        repo.save(e);
    }
}
