package datphong.vn.exception.entity;

import datphong.vn.constant.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "complaint_tickets")
@Getter
@Setter
@NoArgsConstructor
public class ComplaintTicket extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;
    @Column(name = "booking_id")
    private Long bookingId;
    @Column(name = "opened_by", nullable = false)
    private Long openedBy;
    @Column(nullable = false, length = 40)
    private String category;
    @Column(nullable = false, length = 20)
    private String severity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.OPEN;
    @Column(name = "deadline_at")
    private Instant deadlineAt;
    @Column(name = "handler_id")
    private Long handlerId;
    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;
    @Version
    private long version;
}
