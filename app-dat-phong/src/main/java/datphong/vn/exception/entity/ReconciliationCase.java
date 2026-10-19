package datphong.vn.exception.entity;

import datphong.vn.constant.CaseType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "reconciliation_cases")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationCase extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CaseType type;
    @Column(nullable = false, length = 20)
    private String status = "OPEN";
    @Column(name = "booking_id")
    private Long bookingId;
    @Column(name = "payment_id")
    private Long paymentId;
    @Column(name = "refund_id")
    private Long refundId;
    @Column(columnDefinition = "text")
    private String details;
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;
    @Column(name = "resolved_by")
    private Long resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
