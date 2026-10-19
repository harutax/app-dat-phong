package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "mail_delivery_attempts")
@Getter
@Setter
@NoArgsConstructor
public class MailDeliveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "mail_event_id", nullable = false)
    private Long mailEventId;
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(nullable = false, length = 20)
    private String outcome;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;
    @Column(name = "operator_id")
    private Long operatorId;
    @Column(name = "operator_idem_key", length = 100)
    private String operatorIdemKey;
}
