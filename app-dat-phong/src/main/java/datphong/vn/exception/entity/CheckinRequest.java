package datphong.vn.exception.entity;

import datphong.vn.constant.CheckinRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Lịch hẹn check-in: TÁCH BIỆT với booking.status; trả lời trong chat không đồng nghĩa check-in thực tế. */
@Entity
@Table(name = "checkin_requests")
@Getter
@Setter
@NoArgsConstructor
public class CheckinRequest extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "conversation_id")
    private Long conversationId;
    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;
    @Column(name = "agreed_checkin_at")
    private Instant agreedCheckinAt;
    @Column(length = 500)
    private String note;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CheckinRequestStatus status = CheckinRequestStatus.PENDING;
    @Column(name = "responded_by")
    private Long respondedBy;
    @Column(name = "response_note", length = 500)
    private String responseNote;
    @Version
    private long version;
}
