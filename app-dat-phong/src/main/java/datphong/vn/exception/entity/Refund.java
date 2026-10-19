package datphong.vn.exception.entity;

import datphong.vn.constant.RefundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "refund_key", nullable = false, unique = true, length = 100)
    private String refundKey;
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status = RefundStatus.REQUESTED;
    @Column(length = 300)
    private String reason;
    @Column(name = "provider_ref", unique = true, length = 100)
    private String providerRef;
    @Column(name = "failure_code", length = 50)
    private String failureCode;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "requested_by")
    private Long requestedBy;
    @Column(name = "succeeded_at")
    private Instant succeededAt;
    @Version
    private long version;
}
