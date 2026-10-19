package datphong.vn.exception.entity;

import datphong.vn.constant.MailStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Một email LOGICAL (dedupe_key unique = eventType + businessId + recipient + templateVersion).
 * Gửi lại tạo delivery_attempt mới trong cùng bản ghi, không nhân đôi business event.
 */
@Entity
@Table(name = "mail_events")
@Getter
@Setter
@NoArgsConstructor
public class MailEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dedupe_key", nullable = false, unique = true, length = 250)
    private String dedupeKey;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(name = "business_id", length = 60)
    private String businessId;
    @Column(name = "booking_id")
    private Long bookingId;
    @Column(nullable = false)
    private String recipient;
    @Column(name = "template_version_id")
    private Long templateVersionId;
    @Column(name = "payload_snapshot", nullable = false, columnDefinition = "text")
    private String payloadSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private MailStatus status = MailStatus.QUEUED;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
