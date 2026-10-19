package datphong.vn.exception.entity;

import datphong.vn.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Payment intent + payment trong một bản ghi. idempotency_key là merchant key gửi Fake Bank. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "intent_code", nullable = false, unique = true, length = 40)
    private String intentCode;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency = "VND";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.INITIATED;
    @Column(name = "provider_txn_id", unique = true, length = 100)
    private String providerTxnId;
    @Column(name = "payer_account", length = 40)
    private String payerAccount;
    @Column(name = "failure_code", length = 50)
    private String failureCode;
    @Column(name = "captured_at")
    private Instant capturedAt;
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
    @Column(nullable = false)
    private int attempts;
    @Version
    private long version;
}
