package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Dedupe webhook: provider_event_id unique -> callback lặp chỉ được ack. */
@Entity
@Table(name = "payment_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "provider_event_id", nullable = false, unique = true, length = 100)
    private String providerEventId;
    @Column(name = "payment_id")
    private Long paymentId;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(columnDefinition = "text")
    private String payload;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
