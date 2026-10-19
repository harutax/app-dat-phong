package datphong.vn.exception.entity;

import datphong.vn.constant.OutboxStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Transactional outbox: ghi cùng transaction nghiệp vụ, worker xử lý sau commit (at-least-once). */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 80)
    private String eventId;
    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;
    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 60)
    private String aggregateId;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "processed_at")
    private Instant processedAt;
}
